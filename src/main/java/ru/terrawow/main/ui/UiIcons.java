package ru.terrawow.main.ui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.terrawow.main.Icons;

/**
 * WoW icons of window buttons: Nexo items {@code wf_icon_<key>} made by tools/install_wow_icons.py.
 * Without Nexo or without the item the vanilla fallback is used, so windows always work.
 */
public final class UiIcons {
  private UiIcons() {}

  private static volatile boolean enabled = true;

  public static void enable(boolean on) {
    enabled = on;
  }

  /** Icon id for {@link Icons#create}: "nexo:wf_icon_<key>" if that item exists, else the fallback. */
  public static String id(String key, String fallback) {
    if (!enabled) return fallback;
    String id = "nexo:wf_icon_" + key;
    try {
      Icons.create(id);
      return id;
    } catch (Exception e) {
      return fallback;
    }
  }

  public static ItemStack stack(String key, Material fallback) {
    if (enabled)
      try {
        return Icons.create("nexo:wf_icon_" + key);
      } catch (Exception ignored) {
      }
    return new ItemStack(fallback);
  }
}
