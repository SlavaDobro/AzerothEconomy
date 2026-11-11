package ru.terrawow.main.race;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * MythicLib / MMOCore through their public API by reflection (no compile dependency, like the rest
 * of TerraWoWmain). Main thread only.
 */
final class Mmo {
  private Mmo() {}

  /** Our MythicLib stat modifier key: everything the race gives is removed by this key. */
  static final String KEY = "terrawow_race";

  private static ClassLoader loader(String plugin) {
    Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
    if (p == null || !p.isEnabled()) throw new IllegalStateException(plugin + " не запущен");
    return p.getClass().getClassLoader();
  }

  private static Object statMap(UUID id) throws Exception {
    ClassLoader cl = loader("MythicLib");
    Class<?> data = Class.forName("io.lumine.mythic.lib.api.player.MMOPlayerData", true, cl);
    Object d = data.getMethod("get", UUID.class).invoke(null, id); // throws while MythicLib is still loading
    return data.getMethod("getStatMap").invoke(d);
  }

  /** Removes the race modifiers from every stat. False if MythicLib has no data for the player yet. */
  static boolean clearStats(UUID id) {
    try {
      Object map = statMap(id);
      Collection<?> all = (Collection<?>) map.getClass().getMethod("getInstances").invoke(map);
      for (Object inst : all) inst.getClass().getMethod("remove", String.class).invoke(inst, KEY);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Adds a race modifier; {@code relative} = percent (5 = +5%). */
  static void addStat(UUID id, String stat, double amount, boolean relative) throws Exception {
    ClassLoader cl = loader("MythicLib");
    Object map = statMap(id);
    Class<?> type = Class.forName("io.lumine.mythic.lib.player.modifier.ModifierType", true, cl);
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object t = Enum.valueOf((Class<Enum>) type, relative ? "RELATIVE" : "FLAT");
    Class<?> mod = Class.forName("io.lumine.mythic.lib.api.stat.modifier.StatModifier", true, cl);
    Object m = mod.getConstructor(String.class, String.class, double.class, type).newInstance(KEY, stat, amount, t);
    Object inst = map.getClass().getMethod("getInstance", String.class).invoke(map, stat);
    inst.getClass().getMethod("addModifier", mod).invoke(inst, m);
  }

  static double stat(UUID id, String stat) {
    try {
      Object map = statMap(id);
      Object inst = map.getClass().getMethod("getInstance", String.class).invoke(map, stat);
      return (double) inst.getClass().getMethod("getTotal").invoke(inst);
    } catch (Exception e) {
      return 0;
    }
  }

  /** MMOCore mana. */
  static void giveMana(Player p, double amount) {
    try {
      ClassLoader cl = loader("MMOCore");
      Class<?> pd = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, cl);
      Object data = pd.getMethod("get", UUID.class).invoke(null, p.getUniqueId());
      Class<?> reason = Class.forName("net.Indyuce.mmocore.api.event.PlayerResourceUpdateEvent$UpdateReason", true, cl);
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object other = Enum.valueOf((Class<Enum>) reason, "OTHER");
      pd.getMethod("giveMana", double.class, reason).invoke(data, amount, other);
    } catch (Exception ignored) {
    }
  }

  /** MMOCore class id of the player (lower case), or null. */
  static String classId(Player p) {
    try {
      ClassLoader cl = loader("MMOCore");
      Class<?> pd = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, cl);
      Object data = pd.getMethod("get", UUID.class).invoke(null, p.getUniqueId());
      Object profess = pd.getMethod("getProfess").invoke(data);
      return profess == null ? null : String.valueOf(profess.getClass().getMethod("getId").invoke(profess)).toLowerCase();
    } catch (Exception e) {
      return null;
    }
  }

  /** What a class change looks like to us. */
  record ClassChange(Player player, String newClass, boolean forced, Consumer<Boolean> cancel) {}

  /** Listens to MMOCore's PlayerChangeClassEvent (cancellable). False if MMOCore is missing. */
  static boolean onClassChange(Plugin plugin, Consumer<ClassChange> handler) {
    try {
      ClassLoader cl = loader("MMOCore");
      @SuppressWarnings("unchecked")
      Class<? extends Event> ev =
          (Class<? extends Event>) Class.forName("net.Indyuce.mmocore.api.event.PlayerChangeClassEvent", true, cl);
      Method getPlayer = ev.getMethod("getPlayer");
      Method getNewClass = ev.getMethod("getNewClass");
      Method getReason = ev.getMethod("getReason");
      Method setCancelled = ev.getMethod("setCancelled", boolean.class);
      Listener owner = new Listener() {};
      Bukkit.getPluginManager().registerEvent(ev, owner, EventPriority.HIGH, (l, e) -> {
        if (!ev.isInstance(e)) return;
        try {
          Player p = (Player) getPlayer.invoke(e);
          Object cls = getNewClass.invoke(e);
          String id = cls == null ? "" : String.valueOf(cls.getClass().getMethod("getId").invoke(cls));
          boolean forced = String.valueOf(getReason.invoke(e)).equals("COMMAND_FORCE");
          handler.accept(new ClassChange(p, id.toLowerCase(), forced, c -> {
            try {
              setCancelled.invoke(e, c);
            } catch (Exception ignored) {
            }
          }));
        } catch (Exception ex) {
          plugin.getLogger().warning("Расы: проверка класса не удалась: " + ex);
        }
      }, plugin, true);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
