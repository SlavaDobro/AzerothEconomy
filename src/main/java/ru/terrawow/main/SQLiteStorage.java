package ru.terrawow.main;

import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;

public final class SQLiteStorage implements StorageDialect {
  private final Path path;
  private FileChannel lease;
  private FileLock lock;

  public SQLiteStorage(Path path) {
    this.path = path.toAbsolutePath();
  }

  public Connection open() throws Exception {
    Files.createDirectories(path.getParent());
    lease =
        FileChannel.open(
            Path.of(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      lock = lease.tryLock();
      if (lock == null) throw new IllegalStateException("БД уже открыта другим экземпляром");
      Class.forName("org.sqlite.JDBC");
      return DriverManager.getConnection("jdbc:sqlite:" + path);
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  public void configure(Connection c) throws Exception {
    try (Statement s = c.createStatement()) {
      for (String q :
          new String[] {
            "PRAGMA journal_mode=WAL",
            "PRAGMA synchronous=FULL",
            "PRAGMA foreign_keys=ON",
            "PRAGMA busy_timeout=25"
          }) s.execute(q);
    }
  }

  public String integrity(Connection c) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
      return r.next() ? r.getString(1) : "no-result";
    }
  }

  public void backup(Connection c, Path path) throws Exception {
    if (Files.exists(path)) throw new IllegalArgumentException("Копия существует");
    try (Statement s = c.createStatement()) {
      s.execute("VACUUM INTO '" + path.toAbsolutePath().toString().replace("'", "''") + "'");
    }
  }

  public void close() throws Exception {
    if (lock != null && lock.isValid()) lock.release();
    if (lease != null) lease.close();
  }
}
