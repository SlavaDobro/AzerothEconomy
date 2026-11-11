package ru.terrawow.main.race;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** races.yml: factions, races, passives, racial abilities, class rules and skins. Pure parsing. */
public final class RaceConfig {
  public enum Gender {
    MALE("male", "Мужской"),
    FEMALE("female", "Женский");

    public final String key;
    public final String ru;

    Gender(String key, String ru) {
      this.key = key;
      this.ru = ru;
    }

    public static Gender parse(String s) {
      for (Gender g : values()) if (g.key.equalsIgnoreCase(s) || g.name().equalsIgnoreCase(s)) return g;
      if (s != null && (s.equalsIgnoreCase("m") || s.equalsIgnoreCase("м"))) return MALE;
      if (s != null && (s.equalsIgnoreCase("f") || s.equalsIgnoreCase("ж"))) return FEMALE;
      throw new IllegalArgumentException("Пол: male или female");
    }
  }

  public record Faction(String id, String name, String icon, List<String> lore) {}

  /** A permanent bonus. kind: stat (MythicLib), attribute (Minecraft) or text (description only). */
  public record Passive(String name, String text, String kind, String target, double amount, String mode) {}

  /** "SLOWNESS 7 2" = type, level (1 = I), seconds. */
  public record EffectSpec(String type, int level, double seconds) {
    static EffectSpec parse(String s) {
      String[] p = s.trim().split("\\s+");
      if (p.length < 1 || p[0].isBlank()) throw new IllegalArgumentException("эффект: " + s);
      int level = p.length > 1 ? Integer.parseInt(p[1]) : 1;
      double sec = p.length > 2 ? Double.parseDouble(p[2]) : 5;
      if (level < 1 || level > 255 || sec <= 0) throw new IllegalArgumentException("эффект: " + s);
      return new EffectSpec(p[0].toUpperCase(Locale.ROOT), level, sec);
    }
  }

  /** One step of a racial ability; the fields used depend on the type. */
  public record Action(
      String type,
      List<String> names, // cleanse
      List<EffectSpec> effects, // effect, area
      double radius,
      boolean players,
      double percent,
      double seconds,
      double forward,
      double up,
      String sound,
      float volume,
      float pitch,
      String particle,
      int count,
      double spread) {}

  public record Racial(String name, List<String> text, int cooldown, List<Action> actions) {}

  public record Skin(String name, String value, String signature) {}

  public record Race(
      String id,
      String faction,
      String name,
      String iconMale,
      String iconFemale,
      double scale,
      List<String> lore,
      Set<String> classes,
      List<Passive> passives,
      Racial racial,
      Map<Gender, List<Skin>> skins) {
    public String icon(Gender g) {
      return g == Gender.FEMALE ? iconFemale : iconMale;
    }

    public boolean allows(String classId) {
      return classes.contains(classId.toLowerCase(Locale.ROOT));
    }
  }

  static final Set<String> ACTIONS =
      Set.of("cleanse", "effect", "area", "heal", "mana", "velocity", "safe-fall", "stealth", "sound", "particle");
  static final Set<String> OPERATIONS = Set.of("add_number", "add_multiplied_base", "add_multiplied_total");

  public final boolean openOnJoin, forceChoice, classRules;
  public final String afterChoiceCommand, defaultClass;
  public final double femaleScale;
  public final Map<String, String> classNames = new LinkedHashMap<>();
  public final Map<String, Faction> factions = new LinkedHashMap<>();
  public final Map<String, Race> races = new LinkedHashMap<>();
  /** Every class some race lists; other classes (e.g. special ones) are open to all races. */
  public final Set<String> restrictedClasses = new HashSet<>();
  private final YamlConfiguration extraSkins;

  public RaceConfig(YamlConfiguration y) {
    this(y, null);
  }

  /** {@code extraSkins}: race-skins.yml ({@code <race>: {male: [...], female: [...]}}) filled by tools. */
  public RaceConfig(YamlConfiguration y, YamlConfiguration extraSkins) {
    this.extraSkins = extraSkins;
    openOnJoin = y.getBoolean("settings.open-on-join", true);
    forceChoice = y.getBoolean("settings.force-choice", true);
    classRules = y.getBoolean("settings.class-rules", true);
    afterChoiceCommand = y.getString("settings.after-choice-command", "");
    defaultClass = y.getString("settings.default-class", "human").toLowerCase(Locale.ROOT);
    femaleScale = y.getDouble("settings.female-scale", 0.96);
    ConfigurationSection cn = y.getConfigurationSection("class-names");
    if (cn != null) for (String k : cn.getKeys(false)) classNames.put(k.toLowerCase(Locale.ROOT), cn.getString(k));
    ConfigurationSection fs = y.getConfigurationSection("factions");
    if (fs == null || fs.getKeys(false).isEmpty()) throw new IllegalArgumentException("races.yml: нет factions");
    for (String id : fs.getKeys(false)) {
      ConfigurationSection f = fs.getConfigurationSection(id);
      factions.put(id, new Faction(id, f.getString("name", id), f.getString("icon", "WHITE_BANNER"), f.getStringList("lore")));
    }
    ConfigurationSection rs = y.getConfigurationSection("races");
    if (rs == null || rs.getKeys(false).isEmpty()) throw new IllegalArgumentException("races.yml: нет races");
    for (String id : rs.getKeys(false)) {
      if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("ID расы только [a-z0-9_]: " + id);
      Race r = race(id, rs.getConfigurationSection(id));
      if (!factions.containsKey(r.faction())) throw new IllegalArgumentException(id + ": нет фракции " + r.faction());
      races.put(id, r);
      restrictedClasses.addAll(r.classes());
    }
  }

  private Race race(String id, ConfigurationSection s) {
    String where = "раса " + id;
    double scale = s.getDouble("scale", 1.0);
    if (scale < 0.3 || scale > 3) throw new IllegalArgumentException(where + ": scale 0.3–3");
    Set<String> classes = new LinkedHashSet<>();
    for (String c : s.getStringList("classes")) classes.add(c.toLowerCase(Locale.ROOT));
    List<Passive> passives = new ArrayList<>();
    for (Map<?, ?> m : s.getMapList("passives")) passives.add(passive(where, m));
    Racial racial = null;
    ConfigurationSection rc = s.getConfigurationSection("racial");
    if (rc != null) {
      List<Action> actions = new ArrayList<>();
      for (Map<?, ?> m : rc.getMapList("actions")) actions.add(action(where, m));
      racial = new Racial(rc.getString("name", "Расовая способность"), rc.getStringList("text"),
          Math.max(0, rc.getInt("cooldown", 120)), List.copyOf(actions));
    }
    Map<Gender, List<Skin>> skins = new EnumMap<>(Gender.class);
    for (Gender g : Gender.values()) {
      List<Skin> list = new ArrayList<>();
      List<Map<?, ?>> raw = new ArrayList<>(s.getMapList("skins." + g.key));
      if (extraSkins != null) raw.addAll(extraSkins.getMapList(id + "." + g.key));
      for (Map<?, ?> m : raw) {
        String value = str(m, "value", ""), sig = str(m, "signature", "");
        if (value.isBlank() || sig.isBlank()) throw new IllegalArgumentException(where + ": скин без value/signature");
        list.add(new Skin(str(m, "name", ""), value, sig));
      }
      skins.put(g, List.copyOf(list));
    }
    return new Race(id, s.getString("faction", ""), s.getString("name", id),
        s.getString("icon-male", "PLAYER_HEAD"), s.getString("icon-female", s.getString("icon-male", "PLAYER_HEAD")),
        scale, s.getStringList("lore"), Collections.unmodifiableSet(classes), List.copyOf(passives), racial, skins);
  }

  private static Passive passive(String where, Map<?, ?> m) {
    String name = str(m, "name", ""), text = str(m, "text", "");
    if (m.get("stat") != null) {
      String type = str(m, "type", "flat").toLowerCase(Locale.ROOT);
      if (!type.equals("flat") && !type.equals("relative"))
        throw new IllegalArgumentException(where + " / " + name + ": type flat или relative");
      return new Passive(name, text, "stat", str(m, "stat", "").toUpperCase(Locale.ROOT), num(m, "amount", 0), type);
    }
    if (m.get("attribute") != null) {
      String op = str(m, "operation", "add_number").toLowerCase(Locale.ROOT);
      if (!OPERATIONS.contains(op)) throw new IllegalArgumentException(where + " / " + name + ": operation " + OPERATIONS);
      return new Passive(name, text, "attribute", str(m, "attribute", "").toLowerCase(Locale.ROOT).replace("minecraft:", ""),
          num(m, "amount", 0), op);
    }
    return new Passive(name, text, "text", "", 0, "");
  }

  private static Action action(String where, Map<?, ?> m) {
    String type = str(m, "type", "").toLowerCase(Locale.ROOT);
    if (!ACTIONS.contains(type)) throw new IllegalArgumentException(where + ": действие " + type + " — одно из " + ACTIONS);
    List<String> names = new ArrayList<>();
    List<EffectSpec> effects = new ArrayList<>();
    if (m.get("effects") instanceof List<?> l)
      for (Object o : l) {
        if (type.equals("cleanse")) names.add(String.valueOf(o).toUpperCase(Locale.ROOT));
        else effects.add(EffectSpec.parse(String.valueOf(o)));
      }
    return new Action(type, List.copyOf(names), List.copyOf(effects), num(m, "radius", 5), Boolean.TRUE.equals(m.get("players")),
        num(m, "percent", 0), num(m, "seconds", 0), num(m, "forward", 0), num(m, "up", 0), str(m, "sound", ""),
        (float) num(m, "volume", 1), (float) num(m, "pitch", 1), str(m, "particle", ""), (int) num(m, "count", 20),
        num(m, "spread", 0.5));
  }

  private static String str(Map<?, ?> m, String k, String def) {
    Object o = m.get(k);
    return o == null ? def : String.valueOf(o);
  }

  private static double num(Map<?, ?> m, String k, double def) {
    Object o = m.get(k);
    if (o == null) return def;
    if (o instanceof Number n) return n.doubleValue();
    return Double.parseDouble(String.valueOf(o));
  }

  public Race get(String id) {
    Race r = id == null ? null : races.get(id.toLowerCase(Locale.ROOT));
    if (r == null) throw new IllegalArgumentException("Нет расы " + id + ". Есть: " + String.join(", ", races.keySet()));
    return r;
  }

  public List<Race> of(String faction) {
    return races.values().stream().filter(r -> r.faction().equals(faction)).toList();
  }

  /** Is this MMOCore class open to the race? Classes no race lists are open to all. */
  public boolean classAllowed(Race r, String classId) {
    String c = classId.toLowerCase(Locale.ROOT);
    return c.equals(defaultClass) || !restrictedClasses.contains(c) || r.allows(c);
  }

  public double scale(Race r, Gender g) {
    return g == Gender.FEMALE ? r.scale() * femaleScale : r.scale();
  }

  public static String height(double scale) {
    if (scale < 0.75) return "низкий";
    if (scale < 0.92) return "ниже среднего";
    if (scale <= 1.05) return "средний";
    if (scale <= 1.15) return "высокий";
    return "очень высокий";
  }

  public String className(String id) {
    return classNames.getOrDefault(id.toLowerCase(Locale.ROOT), id);
  }
}
