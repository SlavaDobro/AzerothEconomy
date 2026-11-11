package ru.terrawow.main;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Vanilla points only. Refuses MMOCore XP redirection or overlay. */
public final class XpBank {
  private final TerraWoWmain p;

  public XpBank(TerraWoWmain p) {
    this.p = p;
  }

  public void move(Player pl, int amount, boolean deposit) {
    p.access(pl, "bank");
    p.module("xp");
    p.check(pl, "bank.xp");
    var core = Bukkit.getPluginManager().getPlugin("MMOCore");
    if (core instanceof JavaPlugin j
        && j.isEnabled()
        && (j.getConfig().getBoolean("override-vanilla-exp")
            || j.getConfig().getBoolean("vanilla-exp-redirection.enabled")))
      throw new IllegalArgumentException(
          "Банк XP несовместим с текущим перенаправлением/индикатором XP MMOCore; настройки MMOCore"
              + " не изменены");
    if (amount <= 0) throw new IllegalArgumentException("Положительное целое количество XP");
    UUID uuid = pl.getUniqueId();
    if (!p.custody.busy.add(uuid)) throw new IllegalArgumentException("Перенос уже выполняется");
    int before = pl.calculateTotalExperiencePoints(), after;
    try {
      after = deposit ? Math.subtractExact(before, amount) : Math.addExact(before, amount);
      if (after < 0) throw new IllegalArgumentException("Недостаточно обычного XP");
    } catch (Exception e) {
      p.custody.busy.remove(uuid);
      throw e;
    }
    String id = UUID.randomUUID().toString();
    p.async(
        () -> {
          p.ledger.xpPrepare(
              id,
              uuid.toString(),
              before,
              after,
              amount,
              deposit,
              p.getConfig().getLong("xp.limit"));
          return true;
        },
        ok -> {
          if (!pl.isOnline() || pl.isDead() || pl.calculateTotalExperiencePoints() != before) {
            p.async(
                () -> {
                  p.ledger.xpFinish(id, false, false);
                  return true;
                },
                v -> {
                  p.custody.busy.remove(uuid);
                  p.error(pl, new IllegalStateException("XP изменился; операция отменена"));
                },
                e -> {
                  p.custody.busy.remove(uuid);
                  p.error(pl, e);
                });
            return;
          }
          try {
            pl.setExperienceLevelAndProgress(after);
            pl.setTotalExperience(after);
            pl.saveData();
          } catch (Exception e) {
            p.custody.busy.remove(uuid);
            p.error(
                pl, new IllegalStateException("Операция XP " + id + " требует восстановления", e));
            return;
          }
          p.async(
              () -> {
                p.ledger.xpFinish(id, true, false);
                return true;
              },
              v -> {
                p.custody.busy.remove(uuid);
                p.tell(pl, "done");
              },
              e -> {
                p.custody.busy.remove(uuid);
                p.error(pl, e);
              });
        },
        e -> {
          p.custody.busy.remove(uuid);
          p.error(pl, e);
        });
  }
}
