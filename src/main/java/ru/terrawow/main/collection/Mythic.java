package ru.terrawow.main.collection;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * MythicMobs through its public API by reflection (no compile dependency, like Nexo/MMOItems here).
 * Main thread only.
 */
final class Mythic {
  private Mythic() {}

  private static Object helper() throws Exception {
    var plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
    if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("MythicMobs не запущен");
    Class<?> mb = plugin.getClass().getClassLoader().loadClass("io.lumine.mythic.bukkit.MythicBukkit");
    Object inst = mb.getMethod("inst").invoke(null);
    return mb.getMethod("getAPIHelper").invoke(inst);
  }

  static boolean exists(String mob) {
    try {
      Object h = helper();
      return h.getClass().getMethod("getMythicMob", String.class).invoke(h, mob) != null;
    } catch (Exception e) {
      return false;
    }
  }

  /** Spawns the mob and makes {@code owner} its MythicMobs owner (targeter @owner in its skills). */
  static Entity spawn(String mob, Location at, UUID owner) {
    try {
      Object h = helper();
      Entity e = (Entity) h.getClass().getMethod("spawnMythicMob", String.class, Location.class).invoke(h, mob, at);
      if (e == null) throw new IllegalStateException("MythicMobs не создал моба " + mob);
      Object active = h.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(h, e);
      if (active != null) {
        Method set = active.getClass().getMethod("setOwnerUUID", UUID.class);
        set.invoke(active, owner);
      }
      e.setPersistent(false);
      return e;
    } catch (RuntimeException e) {
      throw e;
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw new IllegalStateException("MythicMobs: " + e.getCause().getMessage(), e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException("MythicMobs API недоступен: " + e, e);
    }
  }

  /** Removes the mob the MythicMobs way (despawn skills, ModelEngine model and riders). */
  static void remove(Entity e) {
    if (e == null || !e.isValid()) return;
    try {
      Object h = helper();
      Object active = h.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(h, e);
      if (active != null) {
        active.getClass().getMethod("remove").invoke(active);
        return;
      }
    } catch (Exception ignored) {
    }
    e.remove();
  }

  /** MythicMobs mob ID of the entity, or null for a vanilla entity / no MythicMobs. */
  static String mobId(Entity e) {
    try {
      Object h = helper();
      Object active = h.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(h, e);
      if (active == null) return null;
      Object type = active.getClass().getMethod("getType").invoke(active);
      return (String) type.getClass().getMethod("getInternalName").invoke(type);
    } catch (Exception ex) {
      return null;
    }
  }

  static Optional<Entity> find(UUID id) {
    return Optional.ofNullable(id == null ? null : Bukkit.getEntity(id)).filter(Entity::isValid);
  }
}
