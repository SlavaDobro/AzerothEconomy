package ru.terrawow.main.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.terrawow.main.collection.CollectionConfig.Kind;

class CollectionTest {
  @TempDir Path dir;

  private static CollectionConfig bundled() throws Exception {
    try (var in = CollectionTest.class.getResourceAsStream("/collections.yml")) {
      return new CollectionConfig(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }
  }

  @Test
  void bundledConfigParses() throws Exception {
    CollectionConfig c = bundled();
    assertEquals(3.0, c.mountCastSeconds);
    assertEquals(0.0, c.petCastSeconds);
    assertEquals(3.0, c.petFollowDistance);
    var inv = c.get(Kind.MOUNT, "invincible");
    assertEquals("flying", inv.mode());
    assertFalse(inv.free());
    assertEquals("WF_Mount_Invincible", inv.defaultSkin().mob());
    assertTrue(inv.defaultSkin().free(), "first skin comes with the mount");
    assertFalse(inv.skins().get("spectral").free());
    var murky = c.get(Kind.PET, "murky");
    assertTrue(murky.free());
    assertEquals(2, murky.skins().size());
    assertThrows(IllegalArgumentException.class, () -> c.get(Kind.MOUNT, "nope"));
  }

  @Test
  void ownershipAndSkins() throws Exception {
    CollectionConfig c = bundled();
    var inv = c.get(Kind.MOUNT, "invincible");
    var spectral = inv.skins().get("spectral");
    PlayerCollection p = new PlayerCollection(UUID.randomUUID());
    assertFalse(p.owns(inv));
    assertFalse(p.ownsSkin(inv, inv.defaultSkin()), "no skins without the mount");
    p.owned.add(inv.key());
    assertTrue(p.ownsSkin(inv, inv.defaultSkin()));
    assertFalse(p.ownsSkin(inv, spectral));
    p.selected.put(inv.key(), "spectral");
    assertEquals("default", p.skinOf(inv).id(), "a chosen but not owned skin falls back to the base look");
    p.skins.add(inv.key() + ":spectral");
    assertEquals("spectral", p.skinOf(inv).id());
  }

  @Test
  void bundledSkinMobsFollowNaming() throws Exception {
    CollectionConfig c = bundled();
    // tools/install_mount.py builds WF_Mount_<Id>[_<Skin>] / WF_Pet_<Id>[_<Skin>]
    assertEquals("WF_Mount_Invincible_Spectral", c.get(Kind.MOUNT, "invincible").skins().get("spectral").mob());
    assertEquals("WF_Pet_Murky", c.get(Kind.PET, "murky").defaultSkin().mob());
    assertEquals("WF_Pet_Murky_Golden", c.get(Kind.PET, "murky").skins().get("golden").mob());
  }

  @Test
  void unlockOnKill() throws Exception {
    CollectionConfig c = bundled();
    var u = c.get(Kind.MOUNT, "invincible").unlock();
    assertNotNull(u, "test rule: Invincible from a zombie");
    assertEquals(100.0, u.chance());
    assertTrue(u.matches(null, "ZOMBIE"), "vanilla zombie");
    assertFalse(u.matches("WF_Murloc", "ZOMBIE"), "ZOMBIE-based MythicMobs mob is not a zombie");
    assertFalse(u.matches(null, "SKELETON"));
    assertNull(c.get(Kind.PET, "murky").unlock());
    var y = new YamlConfiguration();
    y.loadFromString("pets:\n  x:\n    skins:\n      a: {mob: A}\n      b: {mob: B, unlock: {kill: [wf_murloc], chance: 250}}\n");
    var skin = new CollectionConfig(y).get(Kind.PET, "x").skins().get("b").unlock();
    assertTrue(skin.matches("WF_Murloc", "ZOMBIE"), "MythicMobs IDs are case-insensitive");
    assertEquals(100.0, skin.chance(), "chance is capped at 100");
  }

  @Test
  void playerDataRoundTrip() throws Exception {
    UUID id = UUID.randomUUID();
    PlayerCollection p = new PlayerCollection(id);
    p.owned.add("mount:invincible");
    p.skins.add("pet:murky:golden");
    p.selected.put("pet:murky", "golden");
    p.lastPet = "murky";
    PlayerCollection.write(dir.toFile(), id, p.snapshot());
    PlayerCollection q = PlayerCollection.load(dir.toFile(), id);
    assertEquals(p.owned, q.owned);
    assertEquals(p.skins, q.skins);
    assertEquals("golden", q.selected.get("pet:murky"));
    assertEquals("murky", q.lastPet);
  }

  @Test
  void colours() {
    assertEquals("§x§A§3§3§5§E§EНепобедимый", Text.c("<#A335EE>Непобедимый"));
    assertEquals("§x§f§f§d§1§0§0a §cb", Text.c("&#ffd100a &cb"));
  }
}
