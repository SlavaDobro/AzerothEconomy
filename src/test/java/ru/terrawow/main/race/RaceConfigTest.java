package ru.terrawow.main.race;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.terrawow.main.race.RaceConfig.*;

class RaceConfigTest {
  @TempDir Path dir;

  static RaceConfig bundled() throws Exception {
    try (var in = RaceConfigTest.class.getResourceAsStream("/races.yml")) {
      return new RaceConfig(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }
  }

  static final Set<String> CLASSES = Set.of("warrior", "paladin", "hunter", "rogue", "priest", "shaman", "mage", "warlock", "druid");

  @Test
  void twelveRacesSixPerFaction() throws Exception {
    RaceConfig c = bundled();
    assertEquals(12, c.races.size());
    assertEquals(6, c.of("alliance").size());
    assertEquals(6, c.of("horde").size());
    for (Race r : c.races.values()) {
      assertNotNull(r.racial(), r.id() + " has a racial");
      assertFalse(r.racial().actions().isEmpty(), r.id());
      assertTrue(CLASSES.containsAll(r.classes()), r.id() + " lists only our MMOCore classes");
      assertTrue(r.classes().contains("warrior"), r.id() + ": every race can be a warrior");
      assertFalse(r.passives().isEmpty(), r.id());
      assertTrue(r.iconMale().startsWith("nexo:wf_race_") && r.iconFemale().endsWith("_female"), r.id());
      for (String cls : r.classes()) assertTrue(c.classNames.containsKey(cls), "name for " + cls);
    }
  }

  @Test
  void heightsAndClassRules() throws Exception {
    RaceConfig c = bundled();
    Race gnome = c.get("gnome"), tauren = c.get("tauren"), human = c.get("human");
    assertTrue(gnome.scale() < human.scale() && human.scale() < tauren.scale());
    assertEquals("низкий", RaceConfig.height(c.scale(gnome, Gender.MALE)));
    assertEquals("очень высокий", RaceConfig.height(c.scale(tauren, Gender.MALE)));
    assertTrue(c.scale(human, Gender.FEMALE) < c.scale(human, Gender.MALE));
    assertFalse(c.classAllowed(gnome, "shaman"), "no gnome shamans");
    assertTrue(c.classAllowed(tauren, "SHAMAN"), "class ids are case-insensitive");
    assertFalse(c.classAllowed(c.get("orc"), "paladin"));
    assertTrue(c.classAllowed(gnome, "arctic_knight"), "special classes are open to all");
    assertTrue(c.classAllowed(gnome, "human"), "the start class is always fine");
  }

  @Test
  void passivesAndActionsParse() throws Exception {
    RaceConfig c = bundled();
    var tauren = c.get("tauren");
    var hp = tauren.passives().stream().filter(p -> p.target().equals("MAX_HEALTH")).findFirst().orElseThrow();
    assertEquals("stat", hp.kind());
    assertEquals("relative", hp.mode());
    assertEquals(5, hp.amount());
    var stomp = tauren.racial().actions().get(0);
    assertEquals("area", stomp.type());
    assertEquals(new EffectSpec("SLOWNESS", 7, 2), stomp.effects().get(0));
    var dwarf = c.get("dwarf").passives().stream().filter(p -> p.kind().equals("attribute")).findFirst().orElseThrow();
    assertEquals("block_break_speed", dwarf.target());
    assertEquals("add_multiplied_base", dwarf.mode());
    assertEquals("stealth", c.get("nightelf").racial().actions().get(0).type());
  }

  @Test
  void badConfigIsRejected() throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString("factions: {a: {name: A}}\nraces:\n  x: {faction: a, racial: {actions: [{type: fly}]}}\n");
    assertThrows(IllegalArgumentException.class, () -> new RaceConfig(y), "unknown action");
    var z = new YamlConfiguration();
    z.loadFromString("factions: {a: {name: A}}\nraces:\n  x: {faction: b}\n");
    assertThrows(IllegalArgumentException.class, () -> new RaceConfig(z), "unknown faction");
    var w = new YamlConfiguration();
    w.loadFromString("factions: {a: {name: A}}\nraces:\n  x: {faction: a, passives: [{attribute: scale, operation: times}]}\n");
    assertThrows(IllegalArgumentException.class, () -> new RaceConfig(w), "bad operation");
  }

  @Test
  void skinsFromRaceSkinsFile() throws Exception {
    YamlConfiguration races;
    try (var in = RaceConfigTest.class.getResourceAsStream("/races.yml")) {
      races = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    var skins = new YamlConfiguration();
    skins.loadFromString("orc:\n  male:\n  - {name: Тралл, value: abc, signature: sig}\n  - {name: Б, value: d, signature: e}\n");
    RaceConfig c = new RaceConfig(races, skins);
    assertEquals(2, c.get("orc").skins().get(Gender.MALE).size());
    assertEquals("abc", c.get("orc").skins().get(Gender.MALE).get(0).value());
    assertTrue(c.get("orc").skins().get(Gender.FEMALE).isEmpty());
    assertTrue(bundled().get("orc").skins().get(Gender.MALE).isEmpty(), "bundled config has no skins");
    var bad = new YamlConfiguration();
    bad.loadFromString("orc:\n  male:\n  - {name: x}\n");
    assertThrows(IllegalArgumentException.class, () -> new RaceConfig(races, bad), "unsigned skin");
  }

  @Test
  void playerDataRoundTrip() throws Exception {
    UUID id = UUID.randomUUID();
    PlayerRace r = new PlayerRace(id);
    r.race = "troll";
    r.gender = Gender.FEMALE;
    r.skin = 2;
    r.ownValue = "v";
    r.ownSignature = "s";
    PlayerRace.write(dir.toFile(), id, r.snapshot());
    PlayerRace q = PlayerRace.load(dir.toFile(), id);
    assertEquals("troll", q.race);
    assertEquals(Gender.FEMALE, q.gender);
    assertEquals(2, q.skin);
    assertEquals("v", q.ownValue);
    assertFalse(PlayerRace.load(dir.toFile(), UUID.randomUUID()).chosen());
  }
}
