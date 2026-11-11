package ru.terrawow.main.collection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "&a", "&#RRGGBB" and "<#RRGGBB>" -> section codes for item names, lore and titles. */
final class Text {
  private Text() {}

  private static final Pattern HEX = Pattern.compile("(?:&#|<#)([0-9A-Fa-f]{6})>?");

  static String c(String s) {
    if (s == null) return "";
    Matcher m = HEX.matcher(s);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      StringBuilder code = new StringBuilder("§x");
      for (char ch : m.group(1).toCharArray()) code.append('§').append(ch);
      m.appendReplacement(out, Matcher.quoteReplacement(code.toString()));
    }
    m.appendTail(out);
    return out.toString().replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
  }
}
