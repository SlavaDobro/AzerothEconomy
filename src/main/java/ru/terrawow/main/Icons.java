package ru.terrawow.main;

import org.bukkit.*;
import org.bukkit.inventory.*;

public final class Icons {
  private Icons() {}

  /** IDs: MATERIAL, nexo:id, mmo:TYPE:ID. Invoked only on the Paper main thread. */
  public static ItemStack create(String id) {
    try {
      if (id.startsWith("nexo:")) {
        var plugin = Bukkit.getPluginManager().getPlugin("Nexo");
        if (plugin == null || !plugin.isEnabled())
          throw new IllegalArgumentException("Nexo недоступен");
        Class<?> api =
            plugin.getClass().getClassLoader().loadClass("com.nexomc.nexo.api.NexoItems");
        Object builder = api.getMethod("itemFromId", String.class).invoke(null, id.substring(5));
        if (builder == null) throw new IllegalArgumentException("Неизвестный Nexo ID");
        return (ItemStack) builder.getClass().getMethod("build").invoke(builder);
      }
      if (id.startsWith("mmo:")) {
        String[] parts = id.split(":", 3);
        var plugin = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (plugin == null || !plugin.isEnabled() || parts.length != 3)
          throw new IllegalArgumentException("MMOItems недоступен или неверный ID");
        ItemStack item =
            (ItemStack)
                plugin
                    .getClass()
                    .getMethod("getItem", String.class, String.class)
                    .invoke(plugin, parts[1], parts[2]);
        if (item == null) throw new IllegalArgumentException("Неизвестный MMOItems ID");
        return item;
      }
      Material material = Material.matchMaterial(id);
      if (material == null || !material.isItem() || material.isAir())
        throw new IllegalArgumentException("Неизвестный материал");
      return new ItemStack(material);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Ошибка API предметов", e);
    }
  }
}
