package ru.azeroth.economy;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class Placeholders extends PlaceholderExpansion {
  private final AzerothEconomy p;

  public Placeholders(AzerothEconomy p) {
    this.p = p;
  }

  public String getIdentifier() {
    return "aeconomy";
  }

  public String getAuthor() {
    return "Azeroth";
  }

  public String getVersion() {
    return "1.0.0";
  }

  public boolean persist() {
    return true;
  }

  public String onRequest(OfflinePlayer player, String param) {
    if (player == null) return "";
    String c = param.startsWith("raw_") ? param.substring(4) : param;
    try {
      long n = p.ledger.cached(player.getUniqueId().toString(), c);
      return param.startsWith("raw_") ? Long.toString(n) : p.ledger.currency(c).format(n);
    } catch (Exception e) {
      return null;
    }
  }
}
