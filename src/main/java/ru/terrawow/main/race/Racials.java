package ru.terrawow.main.race;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.terrawow.main.race.RaceConfig.*;

/** Racial abilities (/racial): runs the race's action list, keeps cooldowns, stealth and safe fall. */
final class Racials implements Listener {
  private final RaceModule m;
  private final Map<UUID, Long> cooldown = new HashMap<>();
  private final Map<UUID, Long> safeFall = new HashMap<>();
  private final Map<UUID, Location> stealthAt = new HashMap<>();
  private final Map<UUID, BukkitTask> stealthTask = new HashMap<>();

  Racials(RaceModule m) {
    this.m = m;
  }

  long left(Player p) {
    return Math.max(0, cooldown.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis());
  }

  void cast(Player p, Race race) {
    Racial r = race.racial();
    if (r == null) {
      RaceModule.msg(p, "&cУ вашей расы нет активной способности.");
      return;
    }
    long left = left(p);
    if (left > 0) {
      RaceModule.msg(p, "&c" + r.name() + " &7— ещё &f" + (left + 999) / 1000 + " сек.");
      return;
    }
    for (Action a : r.actions()) run(p, race, a);
    cooldown.put(p.getUniqueId(), System.currentTimeMillis() + r.cooldown() * 1000L);
    RaceModule.bar(p, "<#FFD100>✦ " + r.name());
  }

  private void run(Player p, Race race, Action a) {
    switch (a.type()) {
      case "cleanse" -> {
        for (String n : a.names()) {
          PotionEffectType t = effect(n);
          if (t != null) p.removePotionEffect(t);
        }
        if (a.names().contains("FIRE")) p.setFireTicks(0);
      }
      case "effect" -> a.effects().forEach(e -> give(p, e));
      case "area" -> {
        for (Entity e : p.getNearbyEntities(a.radius(), a.radius(), a.radius())) {
          if (!(e instanceof LivingEntity le) || e instanceof ArmorStand || !e.isValid()) continue;
          if (e.getPersistentDataContainer().getKeys().stream().anyMatch(k -> k.getKey().equals("collection_owner")))
            continue; // our mounts and pets
          if (e instanceof Player other) {
            if (!a.players() || other.getGameMode() == GameMode.SPECTATOR || m.sameFaction(p, other)) continue;
          }
          a.effects().forEach(x -> give(le, x));
        }
      }
      case "heal" -> {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) return;
        double total = max.getValue() * a.percent() / 100.0;
        int ticks = (int) Math.round(a.seconds());
        if (ticks <= 0) {
          p.setHealth(Math.min(max.getValue(), p.getHealth() + total));
          return;
        }
        double per = total / ticks;
        new org.bukkit.scheduler.BukkitRunnable() {
          int n;

          @Override
          public void run() {
            if (!p.isOnline() || p.isDead() || n++ >= ticks) {
              cancel();
              return;
            }
            p.setHealth(Math.min(max.getValue(), p.getHealth() + per));
          }
        }.runTaskTimer(m.plugin, 0, 20);
      }
      case "mana" -> {
        double max = Mmo.stat(p.getUniqueId(), "MAX_MANA");
        if (max > 0) Mmo.giveMana(p, max * a.percent() / 100.0);
      }
      case "velocity" -> {
        Vector v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() > 1.0E-4) v.normalize();
        p.setVelocity(v.multiply(a.forward()).setY(a.up()));
      }
      case "safe-fall" -> safeFall.put(p.getUniqueId(), System.currentTimeMillis() + (long) (a.seconds() * 1000));
      case "stealth" -> stealth(p, a.seconds());
      case "sound" -> {
        if (!a.sound().isBlank()) p.getWorld().playSound(p.getLocation(), a.sound(), a.volume(), a.pitch());
      }
      case "particle" -> {
        try {
          Particle pt = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(a.particle().toLowerCase(Locale.ROOT)));
          if (pt != null && pt.getDataType() == Void.class)
            p.getWorld().spawnParticle(pt, p.getLocation().add(0, 1, 0), a.count(), a.spread(), a.spread(), a.spread(), 0.02);
        } catch (RuntimeException ignored) {
        }
      }
      default -> {}
    }
  }

  static PotionEffectType effect(String name) {
    return Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
  }

  private static void give(LivingEntity e, EffectSpec s) {
    PotionEffectType t = effect(s.type());
    if (t != null) e.addPotionEffect(new PotionEffect(t, (int) Math.round(s.seconds() * 20), s.level() - 1, false, true, true));
  }

  // ---------------------------------------------------------------- stealth (Shadowmeld)
  private void stealth(Player p, double seconds) {
    UUID id = p.getUniqueId();
    reveal(p);
    stealthAt.put(id, p.getLocation().clone());
    for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.hidePlayer(m.plugin, p);
    p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) (seconds * 20), 0, false, false, true));
    for (Entity e : p.getNearbyEntities(32, 16, 32))
      if (e instanceof Mob mob && p.equals(mob.getTarget())) mob.setTarget(null);
    RaceModule.msg(p, "&7Вы слились с тенью. Движение или атака снимут невидимость.");
    long end = System.currentTimeMillis() + (long) (seconds * 1000);
    stealthTask.put(id, Bukkit.getScheduler().runTaskTimer(m.plugin, () -> {
      Location at = stealthAt.get(id);
      if (!p.isOnline() || at == null) {
        reveal(p);
        return;
      }
      Location now = p.getLocation();
      double dx = now.getX() - at.getX(), dz = now.getZ() - at.getZ();
      if (System.currentTimeMillis() > end || now.getWorld() != at.getWorld() || dx * dx + dz * dz > 0.04) reveal(p);
    }, 2, 2));
  }

  boolean stealthed(Player p) {
    return stealthAt.containsKey(p.getUniqueId());
  }

  void reveal(Player p) {
    UUID id = p.getUniqueId();
    Optional.ofNullable(stealthTask.remove(id)).ifPresent(BukkitTask::cancel);
    if (stealthAt.remove(id) == null) return;
    p.removePotionEffect(PotionEffectType.INVISIBILITY);
    for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.showPlayer(m.plugin, p);
    if (p.isOnline()) RaceModule.bar(p, "&7Вы вышли из тени.");
  }

  void stopAll() {
    for (UUID id : new ArrayList<>(stealthAt.keySet())) {
      Player p = Bukkit.getPlayer(id);
      if (p != null) reveal(p);
    }
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void target(EntityTargetLivingEntityEvent e) {
    if (e.getTarget() instanceof Player p && stealthed(p)) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void attack(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p && stealthed(p)) reveal(p);
    if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p && stealthed(p)) reveal(p);
  }

  @EventHandler(ignoreCancelled = true)
  public void fall(EntityDamageEvent e) {
    if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
    Long until = safeFall.get(p.getUniqueId());
    if (until != null && until > System.currentTimeMillis()) e.setCancelled(true);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) { // keep stealthed players hidden from newcomers
    for (UUID id : stealthAt.keySet()) {
      Player p = Bukkit.getPlayer(id);
      if (p != null && p != e.getPlayer()) e.getPlayer().hidePlayer(m.plugin, p);
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    reveal(e.getPlayer());
    safeFall.remove(e.getPlayer().getUniqueId());
  }
}
