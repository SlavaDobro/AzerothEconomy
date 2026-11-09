package ru.azeroth.economy;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

/**
 * Inventory <-> DB saga. Any uncertain outcome is retained for manual recovery, never retried
 * automatically.
 */
public final class Custody implements Listener {
  private final AzerothEconomy p;
  public final Set<UUID> busy = new HashSet<>();

  public Custody(AzerothEconomy p) {
    this.p = p;
  }

  public void deposit(Player player, boolean transfer, Consumer<String> done) {
    p.check(player, "deposit");
    if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)
      throw new IllegalArgumentException("Внесение доступно в выживании и приключении");
    if (busy.contains(player.getUniqueId()))
      throw new IllegalArgumentException("Перенос уже выполняется");
    ItemStack snapshot = player.getInventory().getItemInMainHand().clone();
    ItemRules.check(snapshot, player.getUniqueId(), transfer);
    byte[] bytes = snapshot.serializeAsBytes();
    String label =
        snapshot.getType().name()
            + " "
            + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(snapshot.effectiveName());
    String id = UUID.randomUUID().toString(), owner = player.getUniqueId().toString();
    int slot = player.getInventory().getHeldItemSlot();
    busy.add(player.getUniqueId());
    p.async(
        () -> {
          p.ledger.prepareIn(id, owner, bytes, label);
          return id;
        },
        value -> {
          if (!player.isOnline()
              || player.isDead()
              || !snapshot.equals(player.getInventory().getItem(slot))) {
            p.async(
                () -> {
                  p.ledger.abortIn(id, owner);
                  return true;
                },
                v -> {
                  busy.remove(player.getUniqueId());
                  p.error(
                      player, new IllegalStateException("Инвентарь изменился; внесение отменено"));
                },
                e -> {
                  busy.remove(player.getUniqueId());
                  p.error(player, e);
                });
            return;
          }
          try {
            player.getInventory().setItem(slot, null);
            player.saveData();
          } catch (Exception e) {
            busy.remove(player.getUniqueId());
            p.error(
                player,
                new IllegalStateException(
                    "Неоднозначный перенос " + id + "; нужна диагностика", e));
            return;
          }
          p.async(
              () -> {
                p.ledger.confirmIn(id, owner);
                return id;
              },
              ok -> {
                busy.remove(player.getUniqueId());
                done.accept(id);
              },
              e -> {
                busy.remove(player.getUniqueId());
                p.error(
                    player,
                    new IllegalStateException(
                        "Предмет сохранён в журнале " + id + "; требуется восстановление", e));
              });
        },
        e -> {
          busy.remove(player.getUniqueId());
          p.error(player, e);
        });
  }

  public void withdraw(Player player, String id, Runnable done) {
    p.check(player, "withdraw");
    if (!busy.add(player.getUniqueId()))
      throw new IllegalArgumentException("Перенос уже выполняется");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.reserveOut(id, owner),
        row -> {
          try {
            if (Database.str(row, "place").startsWith("BANK:")) p.access(player, "bank");
          } catch (Exception denied) {
            p.async(
                () -> {
                  p.ledger.finishOut(id, owner, false);
                  return true;
                },
                v -> {
                  busy.remove(player.getUniqueId());
                  p.error(player, denied);
                },
                e -> {
                  busy.remove(player.getUniqueId());
                  p.error(player, e);
                });
            return;
          }
          int slot = player.getInventory().firstEmpty();
          if (!player.isOnline() || player.isDead() || slot < 0 || slot > 35) {
            p.async(
                () -> {
                  p.ledger.finishOut(id, owner, false);
                  return true;
                },
                v -> {
                  busy.remove(player.getUniqueId());
                  p.tell(player, "full");
                },
                e -> {
                  busy.remove(player.getUniqueId());
                  p.error(player, e);
                });
            return;
          }
          try {
            ItemStack item = ItemStack.deserializeBytes((byte[]) row.get("data"));
            player.getInventory().setItem(slot, item);
            player.saveData();
          } catch (Exception e) {
            busy.remove(player.getUniqueId());
            p.error(player, new IllegalStateException("Неоднозначное получение " + id, e));
            return;
          }
          p.async(
              () -> {
                p.ledger.finishOut(id, owner, true);
                return true;
              },
              v -> {
                busy.remove(player.getUniqueId());
                done.run();
              },
              e -> {
                busy.remove(player.getUniqueId());
                p.error(
                    player,
                    new IllegalStateException(
                        "Не повторяйте выдачу; операция " + id + " требует проверки", e));
              });
        },
        e -> {
          busy.remove(player.getUniqueId());
          p.error(player, e);
        });
  }

  public void all(Player player) {
    p.async(
        () -> p.ledger.assets(player.getUniqueId().toString(), "INBOX"),
        rows -> {
          if (rows.isEmpty()) {
            p.tell(player, "done");
            return;
          }
          withdraw(player, Database.str(rows.getFirst(), "id"), () -> all(player));
        },
        e -> p.error(player, e));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (busy.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (busy.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drop(PlayerDropItemEvent e) {
    if (busy.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pickup(EntityPickupItemEvent e) {
    if (busy.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent e) {
    if (busy.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void swap(PlayerSwapHandItemsEvent e) {
    if (busy.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void damage(EntityDamageEvent e) {
    if (busy.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void command(PlayerCommandPreprocessEvent e) {
    if (busy.contains(e.getPlayer().getUniqueId())) {
      e.setCancelled(true);
      p.tell(e.getPlayer(), "busy");
    }
  }
}
