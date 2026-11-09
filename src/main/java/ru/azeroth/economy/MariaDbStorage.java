package ru.azeroth.economy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** MariaDB/MySQL SQL dialect. A database-wide named lease prevents stale multi-server caches. */
public final class MariaDbStorage implements StorageDialect {
  private final String url, user, password;
  private Connection connection;

  public MariaDbStorage(String url, String user, String password) {
    if (!url.startsWith("jdbc:mariadb:"))
      throw new IllegalArgumentException("Ожидается jdbc:mariadb URL");
    this.url = url;
    this.user = user;
    this.password = password;
  }

  public Connection open() throws Exception {
    Class.forName("org.mariadb.jdbc.Driver");
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    props.setProperty("connectTimeout", "2000");
    props.setProperty("socketTimeout", "2000");
    connection = DriverManager.getConnection(url, props);
    return connection;
  }

  public boolean remote() {
    return true;
  }

  public void configure(Connection c) throws Exception {
    try (Statement s = c.createStatement()) {
      try (ResultSet r =
          s.executeQuery("SELECT GET_LOCK(CONCAT('AzerothEconomy:',DATABASE()),0)")) {
        if (!r.next() || r.getInt(1) != 1)
          throw new IllegalStateException("Общая БД уже занята другим экземпляром AzerothEconomy");
      }
      s.execute("SET SESSION innodb_lock_wait_timeout=1");
      s.execute("SET NAMES utf8mb4");
    }
  }

  public String sql(String q) {
    if (q.equals("PRAGMA query_only=ON")) return "SET SESSION TRANSACTION READ ONLY";
    if (q.equals("PRAGMA query_only=OFF")) return "SET SESSION TRANSACTION READ WRITE";
    q = q.replace("INSERT OR IGNORE INTO", "INSERT IGNORE INTO");
    q = q.replaceAll("ON CONFLICT\\([^)]*\\) DO UPDATE SET ([^;]*)", "ON DUPLICATE KEY UPDATE $1");
    q = q.replaceAll("excluded\\.([a-z_]+)", "VALUES($1)");
    if (q.startsWith("CREATE TABLE")) {
      q =
          q.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGINT PRIMARY KEY AUTO_INCREMENT")
              .replace(" INTEGER", " BIGINT")
              .replace(" BLOB", " LONGBLOB")
              .replace(" TEXT", " VARCHAR(190)");
      q =
          q.replace("fingerprint VARCHAR(190)", "fingerprint LONGTEXT")
              .replace("body VARCHAR(190)", "body TEXT")
              .replace("label VARCHAR(190)", "label TEXT");
      if (!q.startsWith("CREATE TABLE IF"))
        q = q.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
      q += " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
    }
    return q;
  }

  public String integrity(Connection c) throws Exception {
    List<String> tables = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SHOW TABLES")) {
      while (r.next()) tables.add(r.getString(1));
    }
    for (String table : tables) {
      if (!table.matches("[a-z_]+")) return "unexpected-table";
      try (Statement s = c.createStatement();
          ResultSet r = s.executeQuery("CHECK TABLE `" + table + "` QUICK")) {
        boolean ok = false;
        while (r.next())
          if ("status".equals(r.getString("Msg_type")) && "OK".equals(r.getString("Msg_text")))
            ok = true;
        if (!ok) return "check-failed:" + table;
      }
    }
    return "ok";
  }

  public void backup(Connection c, Path target) throws Exception {
    if (Files.exists(target)) throw new IllegalArgumentException("Копия существует");
    StringBuilder out = new StringBuilder("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n");
    List<String> tables = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SHOW TABLES")) {
      while (r.next()) tables.add(r.getString(1));
    }
    for (String table : tables) {
      if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("Неожиданная таблица");
      try (Statement s = c.createStatement();
          ResultSet r = s.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
        r.next();
        out.append(r.getString(2)).append(";\n");
      }
      try (Statement s = c.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM `" + table + "`")) {
        while (r.next()) {
          out.append("INSERT INTO `").append(table).append("` VALUES(");
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
            if (i > 1) out.append(',');
            Object v = r.getObject(i);
            if (v == null) out.append("NULL");
            else if (v instanceof Number) out.append(v);
            else if (v instanceof byte[] b)
              out.append("X'").append(HexFormat.of().formatHex(b)).append("'");
            else
              out.append("CONVERT(X'")
                  .append(HexFormat.of().formatHex(v.toString().getBytes(StandardCharsets.UTF_8)))
                  .append("' USING utf8mb4)");
          }
          out.append(");\n");
        }
      }
    }
    out.append("SET FOREIGN_KEY_CHECKS=1;\n");
    Files.writeString(target, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  public void close() throws Exception {
    if (connection != null && !connection.isClosed())
      try (Statement s = connection.createStatement()) {
        s.execute("DO RELEASE_LOCK(CONCAT('AzerothEconomy:',DATABASE()))");
      }
  }
}
