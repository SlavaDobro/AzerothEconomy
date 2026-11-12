package ru.terrawow.main.menu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MenuConfigTest {
  private static YamlConfiguration yml(String text) throws Exception {
    YamlConfiguration y = new YamlConfiguration();
    y.loadFromString(text);
    return y;
  }

  @Test
  void bundledExampleParses() throws Exception {
    YamlConfiguration y;
    try (var in = MenuConfigTest.class.getResourceAsStream("/menus/example.yml")) {
      y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    MenuConfig c = new MenuConfig(Map.of("example.yml", y));
    var m = c.menus.get("example");
    assertEquals(3, m.rows());
    assertEquals("frame", m.fill());
    assertEquals(List.of(0, 8, 18, 26), m.item("corner").slots());
    assertEquals(List.of("close", "message <#FFD100>Меню работает!"), m.item("hello").left());
    assertEquals(m.item("hello").left(), m.item("hello").right(), "click = both buttons");
  }

  @Test
  void slotsActionsAndLinks() throws Exception {
    MenuConfig c = new MenuConfig(Map.of("a.yml", yml("""
        main:
          rows: 6
          items:
            bar: {slots: ['45-48', 50], icon: STONE}
            go: {slot: 4, icon: STONE, click: ['open second'], right-click: [close]}
        second:
          rows: 1
          items:
            back: {slot: 0, icon: ARROW, click: ['open main', 'delay 5', 'console say hi {player}']}
        """)));
    assertEquals(List.of(45, 46, 47, 48, 50), c.menus.get("main").item("bar").slots());
    assertEquals(List.of("close"), c.menus.get("main").item("go").right());
    assertThrows(IllegalArgumentException.class, () -> new MenuConfig(Map.of("b.yml", yml("""
        m: {rows: 1, items: {x: {slot: 9, icon: STONE}}}
        """))), "slot outside a 1-row menu");
    assertThrows(IllegalArgumentException.class, () -> new MenuConfig(Map.of("b.yml", yml("""
        m: {rows: 1, items: {x: {slot: 0, click: ['fly away']}}}
        """))), "unknown action");
    assertThrows(IllegalArgumentException.class, () -> new MenuConfig(Map.of("b.yml", yml("""
        m: {rows: 1, items: {x: {slot: 0, click: ['open nowhere']}}}
        """))), "open of a missing menu");
    assertThrows(IllegalArgumentException.class, () -> new MenuConfig(Map.of("b.yml", yml("""
        m: {rows: 1, items: {x: {slot: 0, click: ['delay soon']}}}
        """))), "delay needs ticks");
    assertThrows(IllegalArgumentException.class, () -> new MenuConfig(Map.of("b.yml", yml("""
        m: {rows: 1, fill: nope, items: {x: {slot: 0}}}
        """))), "fill item must exist");
  }

  @Test
  void conditions() {
    assertTrue(MenuConfig.test(""));
    assertTrue(MenuConfig.test("HUMAN == human"), "case-insensitive");
    assertFalse(MenuConfig.test("MAGE == HUMAN"));
    assertTrue(MenuConfig.test("MAGE != HUMAN"));
    assertTrue(MenuConfig.test("12 >= 10"));
    assertFalse(MenuConfig.test("9 > 10"));
    assertFalse(MenuConfig.test("abc > 1"), "not a number");
    assertTrue(MenuConfig.test("true && 5 < 6"));
    assertFalse(MenuConfig.test("true && false"));
    assertTrue(MenuConfig.test("false || HUMAN == HUMAN"));
  }

  @Test
  void legacyColoursBecomeMiniMessage() {
    assertEquals("<gray>Класс: <white>Маг", MenuConfig.toMini("&7Класс: &fМаг"));
    assertEquals("<#FFD100>Золото <bold>!", MenuConfig.toMini("&#FFD100Золото &l!"));
    assertEquals("<gold>уже <green>mini", MenuConfig.toMini("<gold>уже §amini"));
    assertEquals("Том & Джерри", MenuConfig.toMini("Том & Джерри"), "& followed by a space is plain text");
  }
}
