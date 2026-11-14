package ru.terrawow.main.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UiBackgroundsTest {
  @Test
  void oneBackgroundPerWindowHeight() {
    assertEquals('', UiBackgrounds.picture(1));
    assertEquals('', UiBackgrounds.picture(6), "same characters as tools/wow_gui_backgrounds.py panel_1..6");
    assertEquals(0, UiBackgrounds.picture(0));
    assertEquals(0, UiBackgrounds.picture(7));
  }

  @Test
  void titlesWithTheirOwnPictureAreKept() {
    assertTrue(UiBackgrounds.hasPicture("Азерот • Главное меню"), "main menu has its own");
    assertFalse(UiBackgrounds.hasPicture("✦ Книга заклинаний • Удар героя ✦"));
  }
}
