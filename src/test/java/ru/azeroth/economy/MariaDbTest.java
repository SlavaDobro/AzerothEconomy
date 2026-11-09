package ru.azeroth.economy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Same ledger contract suite against a real isolated MariaDB on loopback:33077. */
@EnabledIfSystemProperty(named = "ae.mariadb.tests", matches = "true")
class MariaDbTest extends LedgerTest {
  String schema;

  @Override
  @BeforeEach
  void setup() throws Exception {
    schema = "ae_test_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:33077/", "root", "");
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + schema);
    }
    db = new Database(new MariaDbStorage("jdbc:mariadb://127.0.0.1:33077/" + schema, "root", ""));
    l = new Ledger(db, currencies());
    for (String id : List.of(a, b, c)) l.register(UUID.fromString(id), id);
    l.admin("seed", a, "gold", 10000, "give");
  }

  @Override
  @AfterEach
  void close() throws Exception {
    if (db != null) db.close();
    if (schema != null)
      try (Connection c =
              DriverManager.getConnection("jdbc:mariadb://127.0.0.1:33077/", "root", "");
          Statement s = c.createStatement()) {
        s.execute("DROP DATABASE " + schema);
      }
  }

  @Override
  @Test
  void backupAndMigration() throws Exception {
    Path backup = dir.resolve("backup.sql");
    l.transfer("x", a, b, "gold", 123, 0);
    db.backup(backup);
    String other = schema + "_restore";
    try (Connection c = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:33077/", "root", "");
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + other);
      try {
        s.execute("USE " + other);
        for (String q : Files.readString(backup).split(";\\s*")) if (!q.isBlank()) s.execute(q);
        try (Database restored =
            new Database(
                new MariaDbStorage("jdbc:mariadb://127.0.0.1:33077/" + other, "root", ""))) {
          Ledger copy = new Ledger(restored, currencies());
          assertEquals(123, copy.cached(b, "gold"));
        }
      } finally {
        s.execute("DROP DATABASE " + other);
      }
    }
  }

  @Override
  @Test
  void databaseFailureDoesNotPublish() throws Exception {
    db.close();
    assertThrows(RuntimeException.class, () -> l.transfer("fail", a, b, "gold", 100, 0));
    assertEquals(10000, l.cached(a, "gold"));
    assertEquals(0, l.cached(b, "gold"));
  }

  @Override
  @Test
  void databaseLeaseRejectsSecondInstance() {
    assertThrows(
        Exception.class,
        () -> {
          try (Database other =
              new Database(
                  new MariaDbStorage("jdbc:mariadb://127.0.0.1:33077/" + schema, "root", ""))) {}
          ;
        });
  }
}
