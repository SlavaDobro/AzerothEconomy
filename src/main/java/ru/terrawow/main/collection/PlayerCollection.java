package ru.terrawow.main.collection;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * What one player has collected: mounts/pets ("mount:id"), extra skins ("mount:id:skin"),
 * the chosen skin per entry and the last summoned pet. Stored as plugins/TerraWoWmain/collections/players/UUID.yml.
 */
public final class PlayerCollection {
  final UUID owner;
  final Set<String> owned = new LinkedHashSet<>();
  final Set<String> skins = new LinkedHashSet<>();
  final Map<String, String> selected = new LinkedHashMap<>();
  String lastPet;

  PlayerCollection(UUID owner) {
    this.owner = owner;
  }

  static PlayerCollection load(File folder, UUID id) {
    PlayerCollection c = new PlayerCollection(id);
    File f = new File(folder, id + ".yml");
    if (!f.exists()) return c;
    YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
    c.owned.addAll(y.getStringList("owned"));
    c.skins.addAll(y.getStringList("skins"));
    var sel = y.getConfigurationSection("selected");
    if (sel != null) for (String k : sel.getKeys(false)) c.selected.put(k.replace('|', ':'), sel.getString(k));
    c.lastPet = y.getString("last-pet");
    return c;
  }

  /** Text snapshot taken on the main thread; written by {@link #write}. */
  String snapshot() {
    YamlConfiguration y = new YamlConfiguration();
    y.set("owned", new ArrayList<>(owned));
    y.set("skins", new ArrayList<>(skins));
    for (var e : selected.entrySet()) y.set("selected." + e.getKey().replace(':', '|'), e.getValue());
    y.set("last-pet", lastPet);
    return y.saveToString();
  }

  static void write(File folder, UUID id, String text) throws IOException {
    Files.createDirectories(folder.toPath());
    Path target = folder.toPath().resolve(id + ".yml");
    Path tmp = folder.toPath().resolve(id + ".yml.tmp");
    Files.writeString(tmp, text);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  public boolean owns(CollectionConfig.Entry e) {
    return e.free() || owned.contains(e.key());
  }

  public boolean ownsSkin(CollectionConfig.Entry e, CollectionConfig.Skin s) {
    return owns(e) && (s.free() || skins.contains(e.key() + ":" + s.id()));
  }

  public CollectionConfig.Skin skinOf(CollectionConfig.Entry e) {
    CollectionConfig.Skin s = e.skin(selected.get(e.key()));
    return ownsSkin(e, s) ? s : e.defaultSkin();
  }
}
