package ru.terrawow.main;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.entity.Player;

/** Obtain through Bukkit ServicesManager. Amounts always use integer minimum units. */
public final class EconomyApi {
  private final TerraWoWmain p;

  EconomyApi(TerraWoWmain p) {
    this.p = p;
  }

  public long balance(UUID owner, String currency) {
    return p.ledger.cached(owner.toString(), currency);
  }

  public CompletableFuture<Void> transfer(
      UUID operation, UUID from, UUID to, String currency, long amount) {
    var f = new CompletableFuture<Void>();
    p.async(
        () -> {
          p.ledger.transfer(
              operation.toString(), from.toString(), to.toString(), currency, amount, 0);
          return true;
        },
        v -> {
          f.complete(null);
          org.bukkit.Bukkit.getPluginManager()
              .callEvent(new EconomyCommittedEvent(operation, "transfer"));
        },
        f::completeExceptionally);
    return f;
  }

  public CompletableFuture<Void> reward(
      UUID operation, UUID to, String subject, String body, String currency, long amount) {
    var f = new CompletableFuture<Void>();
    p.async(
        () -> {
          p.ledger.sendMail(
              operation.toString(),
              "Система",
              to.toString(),
              subject,
              body,
              currency,
              amount,
              0,
              List.of(),
              0,
              0,
              30L * 86400000,
              true);
          return true;
        },
        v -> {
          f.complete(null);
          org.bukkit.Bukkit.getPluginManager()
              .callEvent(new EconomyCommittedEvent(operation, "reward"));
        },
        f::completeExceptionally);
    return f;
  }

  public CompletableFuture<Void> rewardItems(
      UUID operation, UUID to, String subject, List<org.bukkit.inventory.ItemStack> items) {
    if (!org.bukkit.Bukkit.isPrimaryThread())
      throw new IllegalStateException("Сериализуйте ItemStack только на потоке Paper");
    List<byte[]> immutable = items.stream().map(i -> i.clone().serializeAsBytes()).toList();
    var f = new CompletableFuture<Void>();
    p.async(
        () -> {
          p.ledger.rewardItems(operation.toString(), to.toString(), subject, immutable);
          return true;
        },
        v -> {
          f.complete(null);
          org.bukkit.Bukkit.getPluginManager()
              .callEvent(new EconomyCommittedEvent(operation, "reward-items"));
        },
        f::completeExceptionally);
    return f;
  }

  /**
   * Call on the Paper server thread from a trusted NPC interaction; grants access for 60 seconds.
   */
  public void openBank(Player player) {
    p.menus.localAccess.put(player.getUniqueId() + ":bank", Ledger.now() + 60000);
    p.menus.bank(player);
  }

  public void openMail(Player player) {
    p.menus.localAccess.put(player.getUniqueId() + ":mail", Ledger.now() + 60000);
    p.menus.mailbox(player, 0);
  }
}
