package ru.azeroth.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RestartTest {
  @TempDir Path path;

  @Test
  void killedJvmRecoversPreparedInventoryTransfers() throws Exception {
    for (String direction : List.of("in", "out")) {
      Path file = path.resolve(direction + ".db");
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String cp =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      Process process =
          new ProcessBuilder(
                  java, "-cp", cp, CrashHarness.class.getName(), file.toString(), direction)
              .redirectErrorStream(true)
              .redirectOutput(path.resolve(direction + ".log").toFile())
              .start();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals(17, process.exitValue());
      try (Database db = new Database(file)) {
        Ledger l =
            new Ledger(
                db,
                Map.of(
                    "gold",
                    new Money.Currency(
                        "gold", "G", "g", "PAPER", 0, 0, 10000, true, true, true, "")));
        l.recover();
        assertEquals(1, l.pending().size());
        assertEquals(
            "QUARANTINE_" + direction.toUpperCase(Locale.ROOT),
            Database.str(l.pending().getFirst(), "state"));
        assertEquals(0, l.assets("11111111-1111-1111-1111-111111111111", "INBOX").size());
      }
    }
  }

  @Test
  void rejectsThreeCurrencyArbitrage() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Money.validateRates(
                List.of(
                    new Money.Rate("a", "b", 2, 1, 0),
                    new Money.Rate("b", "c", 2, 1, 0),
                    new Money.Rate("c", "a", 1, 3, 0))));
    Money.validateRates(
        List.of(new Money.Rate("a", "b", 1, 3, 0), new Money.Rate("b", "a", 3, 1, 0)));
  }

  @Test
  void migratesExistingV1WithoutReset() throws Exception {
    Path file = path.resolve("migration.db");
    try (Database d = new Database(file)) {
      d.tx(
          db -> {
            db.exec("INSERT INTO accounts VALUES('owner','gold',12345)");
            db.exec("DROP TABLE xp_accounts");
            db.exec("DROP TABLE xp_operations");
            db.exec("DROP TABLE currency_schema");
            db.exec("DELETE FROM schema_version WHERE version>1");
            return null;
          });
    }
    try (Database d = new Database(file)) {
      assertEquals(3L, (long) d.tx(db -> db.number("SELECT max(version) FROM schema_version")));
      assertEquals(
          12345L, (long) d.tx(db -> db.number("SELECT amount FROM accounts WHERE owner='owner'")));
    }
  }
}
