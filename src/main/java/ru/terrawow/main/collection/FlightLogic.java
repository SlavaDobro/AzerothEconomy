package ru.terrawow.main.collection;

/**
 * What a mount does this tick, from the rider's keys (pure logic, unit-tested; {@link MountControl}
 * turns it into ModelEngine movement). WoW-style flying mount:
 *
 * <ul>
 *   <li>On the ground: runs with gravity, Space takes off at once, a fresh Shift press dismounts.
 *   <li>In the air: Space up, Shift down, no gravity; touching the ground lands into running. Shift
 *       held from the air does not dismount: release it and press again.
 * </ul>
 *
 * Ground mount: Space jumps, a fresh Shift press dismounts.
 */
final class FlightLogic {
  enum Act {
    DISMOUNT,
    WALK,
    WALK_JUMP,
    FLY,
    FLY_UP,
    FLY_DOWN
  }

  final boolean canFly;
  boolean flying;
  private boolean prevSneak;
  private boolean sneakFromFlight; // Shift held since the air: landing must not dismount

  FlightLogic(boolean canFly) {
    this.canFly = canFly;
  }

  Act step(boolean up, boolean sneak, boolean ground, boolean water) {
    boolean pressed = sneak && !prevSneak;
    prevSneak = sneak;
    if (!canFly) {
      if (pressed) return Act.DISMOUNT;
      return up && (ground || water) ? Act.WALK_JUMP : Act.WALK;
    }
    if (!flying && up) { // take off at once (also when falling off a ledge)
      flying = true;
      sneakFromFlight = false;
    }
    if (flying) {
      if (up) return Act.FLY_UP;
      if (sneak && !ground) return Act.FLY_DOWN;
      if (!ground) return Act.FLY;
      flying = false; // touched down: run on
      sneakFromFlight = sneak;
      return Act.WALK;
    }
    if (!sneak) sneakFromFlight = false;
    if (pressed && !sneakFromFlight && ground) return Act.DISMOUNT;
    return Act.WALK;
  }
}
