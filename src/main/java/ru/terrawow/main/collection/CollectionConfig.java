package ru.terrawow.main.collection;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** collections.yml: mounts and pets with their skins. */
public final class CollectionConfig {
  public enum Kind {
    MOUNT("mount", "маунт"),
    PET("pet", "питомец");

    public final String key;
    public final String ru;

    Kind(String key, String ru) {
      this.key = key;
      this.ru = ru;
    }

    public static Kind parse(String s) {
      for (Kind k : values()) if (k.key.equalsIgnoreCase(s) || k.name().equalsIgnoreCase(s)) return k;
      throw new IllegalArgumentException("Тип: mount или pet");
    }
  }

  /** One look of a mount/pet: a separate MythicMobs mob (own model, tint, effects). */
  public record Skin(String id, String name, String mob, String icon, boolean free, List<String> lore) {}

  public record Entry(
      Kind kind,
      String id,
      String name,
      String icon,
      String mode,
      boolean free,
      String source,
      List<String> lore,
      LinkedHashMap<String, Skin> skins) {
    public Skin defaultSkin() {
      return skins.values().iterator().next();
    }

    public Skin skin(String id) {
      Skin s = id == null ? null : skins.get(id);
      return s != null ? s : defaultSkin();
    }

    public String key() {
      return kind.key + ":" + id;
    }
  }

  public final double mountCastSeconds;
  public final double petCastSeconds;
  public final double castMoveTolerance;
  public final double petFollowDistance;
  public final double petTeleportDistance;
  public final double petSpeed;
  public final boolean petResummon;
  public final Map<Kind, LinkedHashMap<String, Entry>> entries = new EnumMap<>(Kind.class);

  public CollectionConfig(YamlConfiguration y) {
    mountCastSeconds = y.getDouble("summon.mount-cast-seconds", 3.0);
    petCastSeconds = y.getDouble("summon.pet-cast-seconds", 0.0);
    castMoveTolerance = y.getDouble("summon.move-tolerance", 1.0);
    petFollowDistance = y.getDouble("pet-follow.follow-distance", 3.0);
    petTeleportDistance = y.getDouble("pet-follow.teleport-distance", 20.0);
    petSpeed = y.getDouble("pet-follow.speed", 1.3);
    petResummon = y.getBoolean("pet-follow.resummon-on-join", true);
    for (Kind kind : Kind.values()) {
      LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
      ConfigurationSection root = y.getConfigurationSection(kind.key + "s");
      if (root != null)
        for (String id : root.getKeys(false)) map.put(id, entry(kind, id, root.getConfigurationSection(id)));
      entries.put(kind, map);
    }
  }

  private static Entry entry(Kind kind, String id, ConfigurationSection s) {
    if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("ID только [a-z0-9_]: " + id);
    String name = s.getString("name", id);
    String icon = s.getString("icon", "SADDLE");
    LinkedHashMap<String, Skin> skins = new LinkedHashMap<>();
    ConfigurationSection sk = s.getConfigurationSection("skins");
    if (sk == null || sk.getKeys(false).isEmpty())
      throw new IllegalArgumentException(kind.key + " " + id + ": нужен хотя бы один скин (skins)");
    boolean first = true;
    for (String skinId : sk.getKeys(false)) {
      ConfigurationSection x = sk.getConfigurationSection(skinId);
      String mob = x.getString("mob");
      if (mob == null || mob.isBlank())
        throw new IllegalArgumentException(kind.key + " " + id + "/" + skinId + ": нет mob");
      // the first skin is the base look and comes with the mount/pet itself
      skins.put(
          skinId,
          new Skin(skinId, x.getString("name", name), mob, x.getString("icon", icon),
              x.getBoolean("free", first), x.getStringList("lore")));
      first = false;
    }
    String mode = s.getString("mode", kind == Kind.MOUNT ? "walking" : "follow");
    return new Entry(kind, id, name, icon, mode, s.getBoolean("free", false), s.getString("source", ""),
        s.getStringList("lore"), skins);
  }

  public Entry get(Kind kind, String id) {
    Entry e = entries.get(kind).get(id);
    if (e == null) throw new IllegalArgumentException("Нет " + kind.ru + "а с ID " + id);
    return e;
  }

  public Collection<Entry> all(Kind kind) {
    return entries.get(kind).values();
  }
}
