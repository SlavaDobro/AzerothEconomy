package ru.terrawow.main.ui;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Window sounds for every server window (TerraWoWmain menus and windows, MMOCore/MythicLib GUIs):
 * opening from the game, closing back to the game, and going from one window to another (page turn).
 *
 * <p>Switching windows fires close + open in the same or the next tick (menus close, wait 1 tick and
 * run a command), so the events of a player are collected for {@link #SETTLE_TICKS} and one sound is
 * chosen by {@link #decide}.
 */
public final class UiSounds implements Listener {
  public enum Kind { OPEN, CLOSE, PAGE, NONE }

  static final long SETTLE_TICKS = 2;

  /** Sound after a burst of events: window before, window now, was any window opened meanwhile. */
  public static Kind decide(boolean wasWindow, boolean isWindow, boolean opened) {
    if (!wasWindow && isWindow) return Kind.OPEN;
    if (wasWindow && !isWindow) return Kind.CLOSE;
    if (wasWindow && isWindow && opened) return Kind.PAGE;
    return Kind.NONE;
  }

  /** Is this inventory holder one of the server windows? (by holder class name prefix) */
  public static boolean isWindow(Object holder, List<String> prefixes) {
    if (holder == null) return false;
    for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      for (String prefix : prefixes) if (c.getName().startsWith(prefix)) return true;
    return false;
  }

  private static volatile boolean active;

  /** True while WoW window sounds are on: windows must not play the vanilla click on top. */
  public static boolean active() {
    return active;
  }

  private final JavaPlugin plugin;
  private final Map<Kind, String> sounds = new EnumMap<>(Kind.class);
  private final float volume;
  private final List<String> windows;
  private final Map<UUID, Boolean> wasWindow = new HashMap<>();
  private final Set<UUID> opened = new HashSet<>();
  private final Map<UUID, BukkitTask> pending = new HashMap<>();

  public UiSounds(JavaPlugin plugin, ConfigurationSection cfg) {
    this.plugin = plugin;
    sounds.put(Kind.OPEN, cfg.getString("open", ""));
    sounds.put(Kind.CLOSE, cfg.getString("close", ""));
    sounds.put(Kind.PAGE, cfg.getString("page", ""));
    volume = (float) cfg.getDouble("volume", 1.0);
    List<String> w = cfg.getStringList("windows");
    windows = w.isEmpty() ? DEFAULT_WINDOWS : List.copyOf(w);
    active = true;
  }

  public static final List<String> DEFAULT_WINDOWS =
      List.of("ru.terrawow.", "io.lumine.mythic.lib.gui.", "net.Indyuce.mmocore.");

  public static List<String> windows(ConfigurationSection cfg) {
    List<String> w = cfg == null ? List.of() : cfg.getStringList("windows");
    return w.isEmpty() ? DEFAULT_WINDOWS : List.copyOf(w);
  }

  private boolean window(Inventory inv) {
    return inv != null && isWindow(inv.getHolder(false), windows);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void open(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof Player p && window(e.getInventory())) {
      opened.add(p.getUniqueId());
      settle(p);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void close(InventoryCloseEvent e) {
    if (e.getPlayer() instanceof Player p && window(e.getInventory())) settle(p);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    BukkitTask t = pending.remove(id);
    if (t != null) t.cancel();
    wasWindow.remove(id);
    opened.remove(id);
  }

  private void settle(Player p) {
    UUID id = p.getUniqueId();
    BukkitTask old = pending.remove(id);
    if (old != null) old.cancel();
    pending.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
      pending.remove(id);
      if (!p.isOnline()) return;
      boolean now = window(p.getOpenInventory().getTopInventory());
      Kind kind = decide(wasWindow.getOrDefault(id, false), now, opened.remove(id));
      wasWindow.put(id, now);
      play(p, kind);
    }, SETTLE_TICKS));
  }

  /** Plays a window sound now (for windows that change their page without reopening). */
  public void play(Player p, Kind kind) {
    String s = sounds.get(kind);
    if (s == null || s.isBlank()) return;
    p.playSound(p.getLocation(), s, SoundCategory.MASTER, volume, 1f);
  }
}
