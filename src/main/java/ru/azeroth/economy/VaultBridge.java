package ru.azeroth.economy;

import java.util.*;
import net.milkbowl.vault.economy.*;
import org.bukkit.*;

/** Legacy Vault API on VaultUnlocked. One Vault unit is ONE COPPER, precision zero. */
public final class VaultBridge implements Economy {
  private final AzerothEconomy plugin;
  private final Ledger l;

  public VaultBridge(AzerothEconomy p) {
    plugin = p;
    l = p.ledger;
  }

  private OfflinePlayer player(String name) {
    OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(name);
    if (p == null) throw new IllegalArgumentException("Неизвестный игрок");
    return p;
  }

  private EconomyResponse unavailable() {
    return new EconomyResponse(
        0,
        0,
        EconomyResponse.ResponseType.NOT_IMPLEMENTED,
        "Именованные банки Vault не поддерживаются");
  }

  private EconomyResponse change(OfflinePlayer p, double amount, boolean deposit) {
    try {
      long n = Money.vault(amount);
      if (!plugin.economyHealthy)
        throw new IllegalStateException("Экономика заблокирована: конфликт провайдеров");
      if (l.db.remote() && Bukkit.isPrimaryThread())
        throw new IllegalStateException(
            "Сетевой backend: синхронная запись Vault на игровом потоке запрещена; используйте"
                + " EconomyApi async");
      l.admin(
          "vault:" + UUID.randomUUID(),
          p.getUniqueId().toString(),
          "gold",
          n,
          deposit ? "give" : "take");
      return new EconomyResponse(n, getBalance(p), EconomyResponse.ResponseType.SUCCESS, "");
    } catch (Exception e) {
      return new EconomyResponse(
          0, getBalance(p), EconomyResponse.ResponseType.FAILURE, e.getMessage());
    }
  }

  public boolean isEnabled() {
    return plugin.isEnabled() && plugin.economyHealthy;
  }

  public String getName() {
    return "AzerothEconomy";
  }

  public boolean hasBankSupport() {
    return false;
  }

  public int fractionalDigits() {
    return 0;
  }

  public String format(double n) {
    try {
      return l.currency("gold").format(Money.vault(n));
    } catch (Exception e) {
      return "Недопустимая сумма";
    }
  }

  public String currencyNamePlural() {
    return "медные";
  }

  public String currencyNameSingular() {
    return "медная";
  }

  public boolean hasAccount(String n) {
    try {
      return hasAccount(player(n));
    } catch (Exception e) {
      return false;
    }
  }

  public boolean hasAccount(OfflinePlayer p) {
    return p != null;
  }

  public boolean hasAccount(String n, String w) {
    return hasAccount(n);
  }

  public boolean hasAccount(OfflinePlayer p, String w) {
    return hasAccount(p);
  }

  public double getBalance(String n) {
    try {
      return getBalance(player(n));
    } catch (Exception e) {
      return 0;
    }
  }

  public double getBalance(OfflinePlayer p) {
    return p == null ? 0 : l.cached(p.getUniqueId().toString(), "gold");
  }

  public double getBalance(String n, String w) {
    return getBalance(n);
  }

  public double getBalance(OfflinePlayer p, String w) {
    return getBalance(p);
  }

  public boolean has(String n, double v) {
    try {
      return has(player(n), v);
    } catch (Exception e) {
      return false;
    }
  }

  public boolean has(OfflinePlayer p, double v) {
    try {
      return Money.vault(v) <= getBalance(p);
    } catch (Exception e) {
      return false;
    }
  }

  public boolean has(String n, String w, double v) {
    return has(n, v);
  }

  public boolean has(OfflinePlayer p, String w, double v) {
    return has(p, v);
  }

  public EconomyResponse withdrawPlayer(OfflinePlayer p, double n) {
    return change(p, n, false);
  }

  public EconomyResponse depositPlayer(OfflinePlayer p, double n) {
    return change(p, n, true);
  }

  public EconomyResponse withdrawPlayer(String p, double n) {
    try {
      return withdrawPlayer(player(p), n);
    } catch (Exception e) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
    }
  }

  public EconomyResponse depositPlayer(String p, double n) {
    try {
      return depositPlayer(player(p), n);
    } catch (Exception e) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
    }
  }

  public EconomyResponse withdrawPlayer(String p, String w, double n) {
    return withdrawPlayer(p, n);
  }

  public EconomyResponse withdrawPlayer(OfflinePlayer p, String w, double n) {
    return withdrawPlayer(p, n);
  }

  public EconomyResponse depositPlayer(String p, String w, double n) {
    return depositPlayer(p, n);
  }

  public EconomyResponse depositPlayer(OfflinePlayer p, String w, double n) {
    return depositPlayer(p, n);
  }

  public EconomyResponse createBank(String n, String p) {
    return unavailable();
  }

  public EconomyResponse createBank(String n, OfflinePlayer p) {
    return unavailable();
  }

  public EconomyResponse deleteBank(String n) {
    return unavailable();
  }

  public EconomyResponse bankBalance(String n) {
    return unavailable();
  }

  public EconomyResponse bankHas(String n, double a) {
    return unavailable();
  }

  public EconomyResponse bankWithdraw(String n, double a) {
    return unavailable();
  }

  public EconomyResponse bankDeposit(String n, double a) {
    return unavailable();
  }

  public EconomyResponse isBankOwner(String n, String p) {
    return unavailable();
  }

  public EconomyResponse isBankOwner(String n, OfflinePlayer p) {
    return unavailable();
  }

  public EconomyResponse isBankMember(String n, String p) {
    return unavailable();
  }

  public EconomyResponse isBankMember(String n, OfflinePlayer p) {
    return unavailable();
  }

  public List<String> getBanks() {
    return List.of();
  }

  public boolean createPlayerAccount(String n) {
    try {
      return createPlayerAccount(player(n));
    } catch (Exception e) {
      return false;
    }
  }

  public boolean createPlayerAccount(OfflinePlayer p) {
    try {
      l.register(p.getUniqueId(), Objects.toString(p.getName(), p.getUniqueId().toString()));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public boolean createPlayerAccount(String n, String w) {
    return createPlayerAccount(n);
  }

  public boolean createPlayerAccount(OfflinePlayer p, String w) {
    return createPlayerAccount(p);
  }
}
