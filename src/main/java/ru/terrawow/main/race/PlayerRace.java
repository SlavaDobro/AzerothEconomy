package ru.terrawow.main.race;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.terrawow.main.race.RaceConfig.Gender;

/**
 * A player's race: race id, gender, the skin picked for them and their own skin from before the
 * race (to give it back on reset). plugins/TerraWoWmain/races/players/UUID.yml.
 */
public final class PlayerRace {
  final UUID owner;
  String race;
  Gender gender = Gender.MALE;
  int skin = -1; // index in the race skin list; -1 = not picked yet
  String ownValue, ownSignature; // original textures

  PlayerRace(UUID owner) {
    this.owner = owner;
  }

  boolean chosen() {
    return race != null;
  }

  static PlayerRace load(File folder, UUID id) {
    PlayerRace r = new PlayerRace(id);
    File f = new File(folder, id + ".yml");
    if (!f.exists()) return r;
    YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
    r.race = y.getString("race");
    try {
      r.gender = Gender.parse(y.getString("gender", "male"));
    } catch (IllegalArgumentException e) {
      r.gender = Gender.MALE;
    }
    r.skin = y.getInt("skin", -1);
    r.ownValue = y.getString("own-skin.value");
    r.ownSignature = y.getString("own-skin.signature");
    return r;
  }

  String snapshot() {
    YamlConfiguration y = new YamlConfiguration();
    y.set("race", race);
    y.set("gender", gender.key);
    y.set("skin", skin);
    y.set("own-skin.value", ownValue);
    y.set("own-skin.signature", ownSignature);
    return y.saveToString();
  }

  static void write(File folder, UUID id, String text) throws IOException {
    Files.createDirectories(folder.toPath());
    Path target = folder.toPath().resolve(id + ".yml");
    Path tmp = folder.toPath().resolve(id + ".yml.tmp");
    Files.writeString(tmp, text);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
