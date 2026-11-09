package ru.azeroth.economy;

import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;

/**
 * Uses installed MMOItems' public API through its own class loader; fails closed on API mismatch.
 */
public final class ItemRules {
  public static void check(ItemStack item, UUID owner, boolean transfer) {
    if (item == null || item.getType().isAir() || item.getAmount() <= 0)
      throw new IllegalArgumentException("Возьмите предмет в основную руку");
    if (item.getType() == Material.BUNDLE || item.getType().name().endsWith("SHULKER_BOX"))
      throw new IllegalArgumentException("Контейнеры нельзя вносить: сначала извлеките содержимое");
    Plugin mi = Bukkit.getPluginManager().getPlugin("MMOItems");
    if (mi == null || !mi.isEnabled()) return;
    try {
      ClassLoader cl = mi.getClass().getClassLoader();
      Class<?> nbt = cl.loadClass("io.lumine.mythic.lib.api.item.NBTItem"),
          stat = cl.loadClass("net.Indyuce.mmoitems.stat.type.ItemStat");
      Object n = nbt.getMethod("get", ItemStack.class).invoke(null, item);
      if (!(boolean) nbt.getMethod("hasType").invoke(n)) return;
      Class<?> vol = cl.loadClass("net.Indyuce.mmoitems.api.item.mmoitem.VolatileMMOItem"),
          stats = cl.loadClass("net.Indyuce.mmoitems.ItemStats");
      Object read = vol.getConstructor(nbt).newInstance(n);
      Class<?> readType = cl.loadClass("net.Indyuce.mmoitems.api.item.mmoitem.ReadMMOItem");
      Object soulStat = stats.getField("SOULBOUND").get(null),
          dropStat = stats.getField("DISABLE_DROP").get(null);
      // VolatileMMOItem.getData does NOT lazy-load. Call public whenLoaded directly:
      // unlike hasData(), malformed stat data propagates an error and fails closed.
      stat.getMethod("whenLoaded", readType).invoke(soulStat, read);
      stat.getMethod("whenLoaded", readType).invoke(dropStat, read);
      Object bound = vol.getMethod("getData", stat).invoke(read, soulStat);
      if (bound != null) {
        UUID who = (UUID) bound.getClass().getMethod("getUniqueId").invoke(bound);
        if (transfer
            || !owner.equals(who)
            || !((org.bukkit.plugin.java.JavaPlugin) mi)
                .getConfig()
                .getBoolean("soulbound.can-drop", false))
          throw new IllegalArgumentException("Привязанный предмет нельзя изъять или передать");
      }
      Object noDrop = vol.getMethod("getData", stat).invoke(read, dropStat);
      if (transfer
          && noDrop != null
          && (boolean) noDrop.getClass().getMethod("isEnabled").invoke(noDrop))
        throw new IllegalArgumentException("MMOItems запрещает передачу предмета");
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception | LinkageError e) {
      throw new IllegalArgumentException(
          "Не удалось проверить ограничения MMOItems; передача заблокирована", e);
    }
  }
}
