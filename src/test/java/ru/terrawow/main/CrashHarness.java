package ru.terrawow.main;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class CrashHarness {
  public static void main(String[] args) throws Exception {
    Database db = new Database(Path.of(args[0]));
    Ledger l =
        new Ledger(
            db,
            Map.of(
                "gold",
                new Money.Currency("gold", "G", "g", "PAPER", 0, 0, 10000, true, true, true, "")));
    String owner = "11111111-1111-1111-1111-111111111111";
    l.register(UUID.fromString(owner), "crash-test");
    l.prepareIn("crash-asset", owner, new byte[] {7, 8, 9}, "CRASH");
    if (args[1].equals("out")) {
      l.confirmIn("crash-asset", owner);
      l.reserveOut("crash-asset", owner);
    }
    Runtime.getRuntime().halt(17);
  }
}
