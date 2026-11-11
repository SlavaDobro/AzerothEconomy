package ru.terrawow.main.collection;

import static org.junit.jupiter.api.Assertions.*;
import static ru.terrawow.main.collection.FlightLogic.Act.*;

import org.junit.jupiter.api.Test;

class FlightLogicTest {
  // step(up, sneak, ground, water)

  @Test
  void runsOnTheGroundAndTakesOffWithSpace() {
    FlightLogic f = new FlightLogic(true);
    assertEquals(WALK, f.step(false, false, true, false), "summoned on the ground = running, not flying");
    assertFalse(f.flying);
    assertEquals(FLY_UP, f.step(true, false, true, false), "Space takes off at once");
    assertEquals(FLY, f.step(false, false, false, false), "released Space in the air: hover");
  }

  @Test
  void shiftInTheAirDescendsAndLandsWithoutDismount() {
    FlightLogic f = new FlightLogic(true);
    f.step(true, false, true, false);
    f.step(false, false, false, false);
    assertEquals(FLY_DOWN, f.step(false, true, false, false));
    assertEquals(FLY_DOWN, f.step(false, true, false, false));
    assertEquals(WALK, f.step(false, true, true, false), "touchdown with Shift held: run on");
    assertEquals(WALK, f.step(false, true, true, false), "still held: no dismount");
    assertEquals(WALK, f.step(false, false, true, false), "released");
    assertEquals(DISMOUNT, f.step(false, true, true, false), "a fresh Shift on the ground dismounts");
  }

  @Test
  void flyingIntoTheGroundLands() {
    FlightLogic f = new FlightLogic(true);
    f.step(true, false, true, false);
    f.step(false, false, false, false);
    assertEquals(WALK, f.step(false, false, true, false));
    assertFalse(f.flying);
  }

  @Test
  void spaceWhileFallingFliesAgain() {
    FlightLogic f = new FlightLogic(true);
    assertEquals(WALK, f.step(false, false, false, false), "walked off a ledge: falls");
    assertEquals(FLY_UP, f.step(true, false, false, false));
  }

  @Test
  void shiftHeldBeforeTakeOffDoesNotDismountAfterLanding() {
    FlightLogic f = new FlightLogic(true);
    assertEquals(WALK, f.step(false, false, true, false));
    assertEquals(FLY_UP, f.step(true, false, true, false));
    assertEquals(FLY_DOWN, f.step(false, true, false, false));
    assertEquals(WALK, f.step(false, true, true, false));
  }

  @Test
  void groundMount() {
    FlightLogic f = new FlightLogic(false);
    assertEquals(WALK_JUMP, f.step(true, false, true, false));
    assertEquals(WALK, f.step(true, false, false, false), "no double jump in the air");
    assertEquals(WALK_JUMP, f.step(true, false, false, true), "swims up in water");
    assertEquals(DISMOUNT, f.step(false, true, true, false));
  }
}
