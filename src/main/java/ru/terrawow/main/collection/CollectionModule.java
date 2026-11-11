package ru.terrawow.main.collection;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import ru.terrawow.main.collection.CollectionConfig.*;

/**
 * Collection module of TerraWoWmain: mounts and pets (MythicMobs + ModelEngine mobs) with skins.
 *
 * <ul>
 *   <li>Mount: cast (default 3 s, moving cancels), the mob spawns with the player as owner and puts
 *       the owner into the saddle itself ({@code mountmodel ... @owner}). One mount per player.
 *   <li>Pet: one per player, follows the owner, comes back after teleport, death and re-login.
 *   <li>Skin: another MythicMobs mob for the same mount/pet; the choice is saved per player.
 * </ul>
 */
public final class CollectionModule implements Listener, CommandExecutor, TabCompleter {
  final JavaPlugin plugin;
  CollectionConfig config;
  final CollectionMenus menus;
  private final File playersFolder;
  private final Map<UUID, PlayerCollection> data = new HashMap<>();
  private final Map<UUID, UUID> mounts = new HashMap<>();
  private final Map<UUID, UUID> pets = new HashMap<>();
  private final Map<UUID, BukkitTask> casts = new HashMap<>();
  private BukkitTask ticker;

  public CollectionModule(JavaPlugin plugin) {
    this.plugin = plugin;
    this.playersFolder = new File(plugin.getDataFolder(), "collections/players");
    this.menus = new CollectionMenus(this);
  }

  public void enable() {
    reload();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getPluginManager().registerEvents(menus, plugin);
    for (String cmd : List.of("mounts", "pets", "twm")) {
      PluginCommand c = Objects.requireNonNull(plugin.getCommand(cmd), cmd);
      c.setExecutor(this);
      c.setTabCompleter(this);
    }
    ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
    int m = config.all(Kind.MOUNT).size(), p = config.all(Kind.PET).size();
    plugin.getLogger().info("Коллекция: маунтов " + m + ", питомцев " + p + ".");
    // first server tick: MythicMobs loads after us (we load before MMOCore), its packs are ready by then
    Bukkit.getScheduler().runTask(plugin, this::checkMobs);
  }

  private void checkMobs() {
    for (Kind k : Kind.values())
      for (Entry e : config.all(k))
        for (Skin s : e.skins().values())
          if (!Mythic.exists(s.mob()))
            plugin.getLogger().warning("Коллекция: нет моба MythicMobs " + s.mob() + " (" + e.key() + "/" + s.id() + ")");
  }

  public void disable() {
    if (ticker != null) ticker.cancel();
    casts.values().forEach(BukkitTask::cancel);
    casts.clear();
    for (UUID id : new ArrayList<>(mounts.values())) Mythic.find(id).ifPresent(Mythic::remove);
    for (UUID id : new ArrayList<>(pets.values())) Mythic.find(id).ifPresent(Mythic::remove);
    mounts.clear();
    pets.clear();
  }

  void reload() {
    File f = new File(plugin.getDataFolder(), "collections.yml");
    if (!f.exists()) plugin.saveResource("collections.yml", false);
    config = new CollectionConfig(YamlConfiguration.loadConfiguration(f));
  }

  // ---------------------------------------------------------------- player data
  PlayerCollection of(UUID id) {
    return data.computeIfAbsent(id, u -> PlayerCollection.load(playersFolder, u));
  }

  void save(PlayerCollection c) {
    String text = c.snapshot();
    UUID id = c.owner;
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        PlayerCollection.write(playersFolder, id, text);
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Коллекция: не удалось сохранить " + id, e);
      }
    });
  }

  // ---------------------------------------------------------------- state
  Optional<Entity> mountOf(Player p) {
    return Mythic.find(mounts.get(p.getUniqueId()));
  }

  Optional<Entity> petOf(Player p) {
    return Mythic.find(pets.get(p.getUniqueId()));
  }

  String activeMob(Player p, Kind k) {
    return (k == Kind.MOUNT ? mountOf(p) : petOf(p))
        .map(e -> e.getPersistentDataContainer().get(key("entry"), org.bukkit.persistence.PersistentDataType.STRING))
        .orElse(null);
  }

  private NamespacedKey key(String k) {
    return new NamespacedKey(plugin, "collection_" + k);
  }

  boolean casting(Player p) {
    return casts.containsKey(p.getUniqueId());
  }

  // ---------------------------------------------------------------- summon
  void summon(Player p, Entry e) {
    PlayerCollection c = of(p.getUniqueId());
    if (!c.owns(e)) {
      msg(p, "&cЭтот " + e.kind().ru + " ещё не получен.");
      return;
    }
    if (casting(p)) {
      msg(p, "&cВы уже призываете.");
      return;
    }
    if (e.kind() == Kind.PET && e.id().equals(activeMob(p, Kind.PET))) { // click the active pet again = dismiss
      dismiss(p, Kind.PET, true);
      return;
    }
    if (e.kind() == Kind.MOUNT && p.isInsideVehicle() && mountOf(p).isEmpty()) {
      msg(p, "&cСначала сойдите с текущего транспорта.");
      return;
    }
    dismiss(p, e.kind(), false);
    Skin skin = c.skinOf(e);
    double seconds = e.kind() == Kind.MOUNT ? config.mountCastSeconds : config.petCastSeconds;
    if (seconds <= 0) {
      spawn(p, e, skin);
      return;
    }
    Location start = p.getLocation().clone();
    int total = (int) Math.round(seconds * 20);
    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
    msg(p, "&6✦ Призыв: " + skin.name() + "&6… &7Не двигайтесь.");
    BukkitTask task = new CastTask(p, start, total, e, skin).runTaskTimer(plugin, 0, 2);
    casts.put(p.getUniqueId(), task);
  }

  private final class CastTask extends org.bukkit.scheduler.BukkitRunnable {
    final Player p;
    final Location start;
    final int total;
    final Entry e;
    final Skin skin;
    int t;

    CastTask(Player p, Location start, int total, Entry e, Skin skin) {
      this.p = p;
      this.start = start;
      this.total = total;
      this.e = e;
      this.skin = skin;
    }

    @Override
    public void run() {
      if (!p.isOnline() || p.isDead()) {
        stop(null);
        return;
      }
      Location now = p.getLocation();
      double dx = now.getX() - start.getX(), dz = now.getZ() - start.getZ();
      if (now.getWorld() != start.getWorld() || dx * dx + dz * dz > config.castMoveTolerance * config.castMoveTolerance) {
        stop("&cПризыв прерван: вы сдвинулись с места.");
        p.playSound(now, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f);
        return;
      }
      t += 2;
      int cells = 10, filled = Math.min(cells, t * cells / total);
      bar(p, "&6Призыв: " + skin.name() + "  &8[&e" + "■".repeat(filled) + "&8" + "■".repeat(cells - filled) + "&8] &f"
          + String.format(Locale.ROOT, "%.1f", Math.max(0, (total - t) / 20.0)) + " с");
      p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, now.clone().add(0, 0.2, 0), 4, 0.6, 0.05, 0.6, 0.01);
      if (t >= total) {
        stop(null);
        spawn(p, e, skin);
      }
    }

    void stop(String message) {
      cancel();
      casts.remove(p.getUniqueId());
      if (message != null) {
        msg(p, message);
        bar(p, message);
      }
    }
  }

  private void spawn(Player p, Entry e, Skin skin) {
    try {
      Location at = p.getLocation();
      if (e.kind() == Kind.PET) at = at.clone().add(at.getDirection().setY(0).normalize().multiply(-1.2));
      Entity mob = Mythic.spawn(skin.mob(), at, p.getUniqueId());
      mob.getPersistentDataContainer().set(key("entry"), org.bukkit.persistence.PersistentDataType.STRING, e.id());
      mob.getPersistentDataContainer().set(key("owner"), org.bukkit.persistence.PersistentDataType.STRING, p.getUniqueId().toString());
      (e.kind() == Kind.MOUNT ? mounts : pets).put(p.getUniqueId(), mob.getUniqueId());
      if (e.kind() == Kind.PET) {
        PlayerCollection c = of(p.getUniqueId());
        c.lastPet = e.id();
        save(c);
      }
      bar(p, "&a✦ " + skin.name());
    } catch (Exception ex) {
      msg(p, "&cНе удалось призвать: " + ex.getMessage());
      plugin.getLogger().log(Level.WARNING, "Коллекция: призыв " + e.key() + "/" + skin.id(), ex);
    }
  }

  /** Removes the player's mount or pet. {@code byPlayer} = the player asked (forget the last pet). */
  void dismiss(Player p, Kind k, boolean byPlayer) {
    Map<UUID, UUID> map = k == Kind.MOUNT ? mounts : pets;
    UUID id = map.remove(p.getUniqueId());
    Mythic.find(id).ifPresent(mob -> {
      if (k == Kind.MOUNT && !((Entity) p).isOnGround() && p.getLocation().getY() > p.getWorld().getMinHeight())
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, true));
      Mythic.remove(mob);
    });
    if (k == Kind.PET && byPlayer) {
      PlayerCollection c = of(p.getUniqueId());
      c.lastPet = null;
      save(c);
    }
  }

  void select(Player p, Entry e, Skin s) {
    PlayerCollection c = of(p.getUniqueId());
    if (!c.ownsSkin(e, s)) {
      msg(p, "&cЭтот облик ещё не получен.");
      return;
    }
    c.selected.put(e.key(), s.id());
    save(c);
    msg(p, "&6Облик: " + s.name());
    if (e.id().equals(activeMob(p, e.kind()))) { // swap the active one at once
      if (e.kind() == Kind.PET) spawnSwap(p, e, s);
      else msg(p, "&7Новый облик появится при следующем призыве.");
    }
  }

  private void spawnSwap(Player p, Entry e, Skin s) {
    dismiss(p, Kind.PET, false);
    spawn(p, e, s);
  }

  // ---------------------------------------------------------------- every 10 ticks
  private void tick() {
    mounts.entrySet().removeIf(x -> Mythic.find(x.getValue()).isEmpty()); // dismounted -> the mob removed itself
    for (var it = pets.entrySet().iterator(); it.hasNext(); ) {
      var x = it.next();
      Player owner = Bukkit.getPlayer(x.getKey());
      Optional<Entity> pet = Mythic.find(x.getValue());
      if (owner == null || pet.isEmpty()) {
        pet.ifPresent(Mythic::remove);
        it.remove();
        continue;
      }
      follow(owner, pet.get());
    }
  }

  private void follow(Player owner, Entity pet) {
    Location o = owner.getLocation();
    if (pet.getWorld() != o.getWorld() || pet.getLocation().distanceSquared(o) > sq(config.petTeleportDistance)) {
      Location behind = o.clone().add(o.getDirection().setY(0).normalize().multiply(-1.5));
      behind.setY(o.getY());
      pet.teleport(behind);
      return;
    }
    if (!(pet instanceof Mob mob)) return;
    double d = pet.getLocation().distanceSquared(o);
    if (d > sq(config.petFollowDistance)) mob.getPathfinder().moveTo(o, config.petSpeed);
    else if (d < sq(config.petFollowDistance - 1.2)) mob.getPathfinder().stopPathfinding();
  }

  private static double sq(double v) {
    return v * v;
  }

  // ---------------------------------------------------------------- events
  @EventHandler
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    PlayerCollection c = of(p.getUniqueId());
    if (config.petResummon && c.lastPet != null && config.entries.get(Kind.PET).containsKey(c.lastPet))
      Bukkit.getScheduler().runTaskLater(plugin, () -> {
        if (p.isOnline() && petOf(p).isEmpty()) {
          Entry pet = config.get(Kind.PET, c.lastPet);
          if (c.owns(pet)) spawn(p, pet, c.skinOf(pet));
        }
      }, 40);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    Player p = e.getPlayer();
    Optional.ofNullable(casts.remove(p.getUniqueId())).ifPresent(BukkitTask::cancel);
    Mythic.find(mounts.remove(p.getUniqueId())).ifPresent(Mythic::remove);
    Mythic.find(pets.remove(p.getUniqueId())).ifPresent(Mythic::remove); // lastPet stays -> back on join
    data.remove(p.getUniqueId());
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    Player p = e.getEntity();
    Optional.ofNullable(casts.remove(p.getUniqueId())).ifPresent(BukkitTask::cancel);
    Mythic.find(mounts.remove(p.getUniqueId())).ifPresent(Mythic::remove);
    Mythic.find(pets.remove(p.getUniqueId())).ifPresent(Mythic::remove);
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    Player p = e.getPlayer();
    PlayerCollection c = of(p.getUniqueId());
    if (c.lastPet != null && config.entries.get(Kind.PET).containsKey(c.lastPet))
      Bukkit.getScheduler().runTaskLater(plugin, () -> {
        if (p.isOnline() && petOf(p).isEmpty()) {
          Entry pet = config.get(Kind.PET, c.lastPet);
          if (c.owns(pet)) spawn(p, pet, c.skinOf(pet));
        }
      }, 40);
  }

  /** "unlock: {kill: [...]}" in collections.yml: the killer gets the mount/pet or skin. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void kill(org.bukkit.event.entity.EntityDeathEvent e) {
    Player killer = e.getEntity().getKiller();
    if (killer == null || e.getEntity() instanceof Player) return;
    String mythic = Mythic.mobId(e.getEntity());
    String type = e.getEntity().getType().name();
    PlayerCollection c = of(killer.getUniqueId());
    boolean changed = false;
    for (Kind k : Kind.values())
      for (Entry en : config.all(k)) {
        if (!en.free() && !c.owns(en) && rolls(en.unlock(), mythic, type)) {
          c.owned.add(en.key());
          changed = true;
          announce(killer, en, null);
        }
        for (Skin s : en.skins().values())
          if (!c.ownsSkin(en, s) && rolls(s.unlock(), mythic, type)) {
            c.skins.add(en.key() + ":" + s.id());
            c.owned.add(en.key());
            changed = true;
            announce(killer, en, s);
          }
      }
    if (changed) save(c);
  }

  private static boolean rolls(Unlock u, String mythic, String type) {
    return u != null && u.matches(mythic, type) && Math.random() * 100 < u.chance();
  }

  private void announce(Player p, Entry e, Skin s) {
    String what = s == null ? e.name() : s.name();
    msg(p, "<#FFD100>✦ Новое в коллекции" + (s == null ? " (" + e.kind().ru + ")" : " (облик)") + ": &r" + what);
    msg(p, "&7Откройте &f/" + (e.kind() == Kind.MOUNT ? "mounts" : "pets") + "&7, чтобы призвать.");
    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void teleport(PlayerTeleportEvent e) {
    Player p = e.getPlayer();
    if (e.getFrom().getWorld() != e.getTo().getWorld() || e.getFrom().distanceSquared(e.getTo()) > 100)
      if (mounts.containsKey(p.getUniqueId())) dismiss(p, Kind.MOUNT, false); // the pet follows on its own
  }

  // ---------------------------------------------------------------- commands
  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    try {
      switch (cmd.getName()) {
        case "mounts" -> menus.list(player(s), Kind.MOUNT, 0);
        case "pets" -> menus.list(player(s), Kind.PET, 0);
        default -> admin(s, a);
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      s.sendMessage(Text.c("&c" + e.getMessage()));
    }
    return true;
  }

  private static Player player(CommandSender s) {
    if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игрока");
    return p;
  }

  private void admin(CommandSender s, String[] a) {
    if (a.length == 0) {
      s.sendMessage(Text.c("&6/twm give|take <ник> <mount|pet> <id> [skin] &7— выдать/забрать"));
      s.sendMessage(Text.c("&6/twm unlock <id|all> [ник] &7— разблокировать маунта со всеми обликами (без ника — себе)"));
      s.sendMessage(Text.c("&6/twm list <ник> &7— коллекция игрока"));
      s.sendMessage(Text.c("&6/twm open <mounts|pets> <ник> &7— открыть меню (для консоли/панелей)"));
      s.sendMessage(Text.c("&6/twm summon <ник> <mount|pet> <id> &7| &6/twm dismiss <ник> <mount|pet>"));
      s.sendMessage(Text.c("&6/twm reload &7— перечитать collections.yml"));
      return;
    }
    switch (a[0]) {
      case "reload" -> {
        reload();
        s.sendMessage(Text.c("&acollections.yml перечитан."));
      }
      case "open" -> {
        need(a, 3);
        Player p = online(a[2]);
        menus.list(p, a[1].startsWith("pet") ? Kind.PET : Kind.MOUNT, 0);
      }
      case "summon" -> {
        need(a, 4);
        Player p = online(a[1]);
        summon(p, config.get(Kind.parse(a[2]), a[3]));
      }
      case "dismiss" -> {
        need(a, 3);
        dismiss(online(a[1]), Kind.parse(a[2]), true);
      }
      case "give", "take" -> {
        need(a, 4);
        OfflinePlayer target = offline(a[1]);
        Entry e = config.get(Kind.parse(a[2]), a[3]);
        PlayerCollection c = of(target.getUniqueId());
        boolean give = a[0].equals("give");
        String what;
        if (a.length >= 5) {
          Skin skin = e.skins().get(a[4]);
          if (skin == null) throw new IllegalArgumentException("Нет облика " + a[4] + " у " + e.key());
          String k = e.key() + ":" + skin.id();
          if (give) {
            c.skins.add(k);
            c.owned.add(e.key()); // a skin is useless without the mount/pet itself
          } else c.skins.remove(k);
          what = e.key() + " / облик " + skin.id();
        } else {
          if (give) c.owned.add(e.key()); else c.owned.remove(e.key());
          what = e.key();
        }
        save(c);
        s.sendMessage(Text.c((give ? "&aВыдано " : "&eЗабрано ") + what + " &7→ " + target.getName()));
        Player online = target.getPlayer();
        if (online != null && give) msg(online, "&6✦ В вашу коллекцию добавлено: " + (a.length >= 5 ? e.skins().get(a[4]).name() : e.name()));
      }
      case "unlock" -> {
        // /twm unlock <id|all> [ник] — маунт со всеми обликами; без ника — себе
        need(a, 2);
        OfflinePlayer target = a.length >= 3 ? offline(a[2]) : player(s);
        List<Entry> list = a[1].equalsIgnoreCase("all")
            ? new ArrayList<>(config.all(Kind.MOUNT))
            : List.of(config.get(Kind.MOUNT, a[1].toLowerCase(Locale.ROOT)));
        PlayerCollection c = of(target.getUniqueId());
        for (Entry e : list) {
          c.owned.add(e.key());
          for (Skin sk : e.skins().values()) c.skins.add(e.key() + ":" + sk.id());
        }
        save(c);
        String names = String.join("&7, ", list.stream().map(Entry::name).toList());
        s.sendMessage(Text.c("&aРазблокировано для " + target.getName() + ": &r" + names + " &7(со всеми обликами)"));
        Player online = target.getPlayer();
        if (online != null && online != s) msg(online, "&6✦ В вашу коллекцию добавлено: &r" + names);
      }
      case "list" -> {
        need(a, 2);
        OfflinePlayer target = offline(a[1]);
        PlayerCollection c = of(target.getUniqueId());
        s.sendMessage(Text.c("&6Коллекция " + target.getName() + ": &f" + String.join(", ", c.owned)));
        s.sendMessage(Text.c("&6Облики: &f" + String.join(", ", c.skins)));
      }
      default -> throw new IllegalArgumentException("Неизвестная команда. /twm");
    }
  }

  private static void need(String[] a, int n) {
    if (a.length < n) throw new IllegalArgumentException("Не хватает аргументов. /twm");
  }

  private static Player online(String name) {
    Player p = Bukkit.getPlayerExact(name);
    if (p == null) throw new IllegalArgumentException("Игрок не в сети: " + name);
    return p;
  }

  @SuppressWarnings("deprecation")
  private static OfflinePlayer offline(String name) {
    Player p = Bukkit.getPlayerExact(name);
    if (p != null) return p;
    OfflinePlayer o = Bukkit.getOfflinePlayer(name);
    if (!o.hasPlayedBefore()) throw new IllegalArgumentException("Игрок не найден: " + name);
    return o;
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
    if (!cmd.getName().equals("twm")) return List.of();
    List<String> out = switch (a.length) {
      case 1 -> List.of("give", "take", "unlock", "list", "open", "summon", "dismiss", "reload");
      case 2 -> a[0].equals("open") ? List.of("mounts", "pets")
          : a[0].equals("unlock") ? unlockIds()
          : Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
      case 3 -> a[0].equals("open") || a[0].equals("unlock") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
          : List.of("mount", "pet");
      case 4 -> a[0].equals("unlock") ? List.of() : safeIds(a[2]);
      case 5 -> a[0].equals("unlock") ? List.of() : safeSkins(a[2], a[3]);
      default -> List.of();
    };
    return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(a[a.length - 1].toLowerCase(Locale.ROOT))).toList();
  }

  private List<String> unlockIds() {
    List<String> ids = new ArrayList<>(config.entries.get(Kind.MOUNT).keySet());
    ids.add("all");
    return ids;
  }

  private List<String> safeIds(String kind) {
    try {
      return new ArrayList<>(config.entries.get(Kind.parse(kind)).keySet());
    } catch (Exception e) {
      return List.of();
    }
  }

  private List<String> safeSkins(String kind, String id) {
    try {
      return new ArrayList<>(config.get(Kind.parse(kind), id).skins().keySet());
    } catch (Exception e) {
      return List.of();
    }
  }

  // ---------------------------------------------------------------- text
  static void msg(Player p, String s) {
    p.sendMessage(Text.c(s));
  }

  static void bar(Player p, String s) {
    p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(Text.c(s)));
  }
}
