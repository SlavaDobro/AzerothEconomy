package ru.terrawow.main.ui;

import static org.junit.jupiter.api.Assertions.*;
import static ru.terrawow.main.ui.UiSounds.Kind.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UiSoundsTest {
  // decide(window before, window now, a window was opened meanwhile)

  @Test
  void openFromTheGameAndCloseBackToIt() {
    assertEquals(OPEN, UiSounds.decide(false, true, true));
    assertEquals(CLOSE, UiSounds.decide(true, false, false));
  }

  @Test
  void goingToAnotherWindowTurnsThePage() {
    assertEquals(PAGE, UiSounds.decide(true, true, true), "close + open of the next window = one page sound");
    assertEquals(NONE, UiSounds.decide(true, true, false), "nothing changed");
    assertEquals(NONE, UiSounds.decide(false, false, true), "opened and closed again at once");
  }

  static class Base {}

  static class Child extends Base {}

  @Test
  void windowsAreFoundByOwnerClassAndItsParents() {
    String self = UiSoundsTest.class.getName();
    assertTrue(UiSounds.isWindow(new Child(), List.of(self + "$Base")), "MMOCore GUIs extend MythicLib ones");
    assertTrue(UiSounds.isWindow(new Child(), List.of("ru.terrawow.")));
    assertFalse(UiSounds.isWindow(new Object(), List.of("ru.terrawow.")));
    assertFalse(UiSounds.isWindow(null, List.of("ru.terrawow.")));
  }
}
