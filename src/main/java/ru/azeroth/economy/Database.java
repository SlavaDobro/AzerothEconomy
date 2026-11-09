package ru.azeroth.economy;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** One writer, short transactions, fail-fast contention. All bulk work runs on the worker. */
public final class Database implements AutoCloseable {
  private final Connection c;
  private final ReentrantLock lock = new ReentrantLock(true);

  public interface Work<T> {
    T run(Database d) throws Exception;
  }

  private final StorageDialect dialect;
  private volatile boolean broken;

  public boolean remote() {
    return dialect.remote();
  }

  public String integrity() throws Exception {
    return dialect.integrity(c);
  }

  public Database(Path path) throws Exception {
    this(new SQLiteStorage(path));
  }

  public Database(StorageDialect dialect) throws Exception {
    this.dialect = dialect;
    c = dialect.open();
    try {
      dialect.configure(c);
      migrate();
    } catch (Exception e) {
      c.close();
      dialect.close();
      throw e;
    }
  }

  private void migrate() throws Exception {
    tx(
        d -> {
          exec("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY)");
          long version = number("SELECT COALESCE(MAX(version),0) FROM schema_version");
          if (version > 3) throw new IllegalStateException("БД новее плагина");
          if (version == 0) {
            for (String sql :
                List.of(
                    "CREATE TABLE accounts(owner TEXT NOT NULL,currency TEXT NOT NULL,amount"
                        + " INTEGER NOT NULL CHECK(amount>=0),PRIMARY KEY(owner,currency))",
                    "CREATE TABLE operations(id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,"
                        + " created INTEGER NOT NULL)",
                    "CREATE TABLE ledger(seq INTEGER PRIMARY KEY AUTOINCREMENT,op TEXT NOT"
                        + " NULL,owner TEXT NOT NULL,currency TEXT NOT NULL,delta INTEGER NOT"
                        + " NULL,balance INTEGER NOT NULL,reason TEXT NOT NULL,created INTEGER NOT"
                        + " NULL)",
                    "CREATE INDEX ledger_owner ON ledger(owner,seq)",
                    "CREATE TABLE players(uuid TEXT PRIMARY KEY,name TEXT NOT NULL)",
                    "CREATE TABLE tabs(owner TEXT NOT NULL,tab INTEGER NOT NULL,name TEXT NOT"
                        + " NULL,icon TEXT NOT NULL,slots INTEGER NOT NULL,PRIMARY KEY(owner,tab))",
                    "CREATE TABLE assets(id TEXT PRIMARY KEY,owner TEXT NOT NULL,place TEXT NOT"
                        + " NULL,data BLOB NOT NULL,label TEXT NOT NULL,state TEXT NOT NULL,created"
                        + " INTEGER NOT NULL)",
                    "CREATE INDEX assets_place ON assets(owner,place,state)",
                    "CREATE TABLE item_journal(seq INTEGER PRIMARY KEY AUTOINCREMENT,asset TEXT NOT"
                        + " NULL,owner TEXT NOT NULL,action TEXT NOT NULL,created INTEGER NOT"
                        + " NULL)",
                    "CREATE TABLE mails(id TEXT PRIMARY KEY,sender TEXT NOT NULL,recipient TEXT NOT"
                        + " NULL,subject TEXT NOT NULL,body TEXT NOT NULL,currency TEXT NOT"
                        + " NULL,money INTEGER NOT NULL,cod INTEGER NOT NULL,paid INTEGER NOT NULL"
                        + " DEFAULT 0,deliver INTEGER NOT NULL,expires INTEGER NOT NULL,state TEXT"
                        + " NOT NULL,returned INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX mails_recipient ON mails(recipient,state,deliver)",
                    "CREATE TABLE lots(id TEXT PRIMARY KEY,seller TEXT NOT NULL,asset TEXT NOT"
                        + " NULL,price INTEGER NOT NULL,deposit INTEGER NOT NULL,expires INTEGER"
                        + " NOT NULL,state TEXT NOT NULL,buyer TEXT)",
                    "CREATE TABLE trades(id TEXT PRIMARY KEY,a TEXT NOT NULL,b TEXT NOT NULL,state"
                        + " TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,confirm_a INTEGER NOT"
                        + " NULL DEFAULT -1,confirm_b INTEGER NOT NULL DEFAULT -1,expires INTEGER"
                        + " NOT NULL)",
                    "CREATE TABLE offers(trade_id TEXT NOT NULL,owner TEXT NOT NULL,currency TEXT"
                        + " NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(trade_id,owner,currency))",
                    "CREATE TABLE vouchers(id TEXT PRIMARY KEY,currency TEXT NOT NULL,amount"
                        + " INTEGER NOT NULL,state TEXT NOT NULL)",
                    "CREATE TABLE schedule_runs(id TEXT PRIMARY KEY,created INTEGER NOT NULL)",
                    "CREATE TABLE guild_roles(guild TEXT NOT NULL,owner TEXT NOT NULL,role TEXT NOT"
                        + " NULL,PRIMARY KEY(guild,owner))",
                    "CREATE TABLE guild_usage(guild TEXT NOT NULL,owner TEXT NOT NULL,day INTEGER"
                        + " NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(guild,owner,day))"))
              exec(sql);
            exec("INSERT INTO schema_version VALUES(1)");
          }
          if (version < 2) {
            exec(
                "CREATE TABLE IF NOT EXISTS xp_accounts(owner TEXT PRIMARY KEY,amount INTEGER NOT"
                    + " NULL CHECK(amount>=0))");
            exec(
                "CREATE TABLE IF NOT EXISTS xp_operations(id TEXT PRIMARY KEY,owner TEXT NOT"
                    + " NULL,amount INTEGER NOT NULL,before_points INTEGER NOT NULL,after_points"
                    + " INTEGER NOT NULL,deposit INTEGER NOT NULL,state TEXT NOT NULL)");
            exec("INSERT INTO schema_version VALUES(2)");
          }
          if (version < 3) {
            exec(
                "CREATE TABLE IF NOT EXISTS currency_schema(id TEXT PRIMARY KEY,precision_digits"
                    + " INTEGER NOT NULL)");
            exec("INSERT INTO schema_version VALUES(3)");
          }
          return null;
        });
  }

  public <T> T tx(Work<T> work) {
    return tx(work, () -> {});
  }

  public <T> T tx(Work<T> work, Runnable afterCommit) {
    if (broken)
      throw new IllegalStateException(
          "БД остановлена после неоднозначного COMMIT; требуется перезапуск и аудит");
    if (!lock.tryLock()) throw new IllegalStateException("Хранилище занято, повторите позже");
    try {
      c.setAutoCommit(false);
      try {
        T result = work.run(this);
        try {
          c.commit();
        } catch (SQLException commitError) {
          broken = true;
          throw commitError;
        }
        afterCommit.run();
        return result;
      } catch (Exception e) {
        c.rollback();
        if (e instanceof RuntimeException r) throw r;
        throw new IllegalStateException("Ошибка БД: " + e.getMessage(), e);
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Ошибка БД", e);
    } finally {
      lock.unlock();
    }
  }

  public int exec(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(dialect.sql(sql))) {
      bind(p, args);
      p.execute();
      return p.getUpdateCount();
    }
  }

  public List<Map<String, Object>> rows(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(dialect.sql(sql))) {
      bind(p, args);
      try (ResultSet rs = p.executeQuery()) {
        List<Map<String, Object>> list = new ArrayList<>();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
          list.add(row);
        }
        return list;
      }
    }
  }

  public Map<String, Object> one(String sql, Object... args) throws SQLException {
    var r = rows(sql, args);
    if (r.isEmpty()) throw new IllegalArgumentException("Запись не найдена");
    return r.getFirst();
  }

  public long number(String sql, Object... args) throws SQLException {
    return ((Number) one(sql, args).values().iterator().next()).longValue();
  }

  private static void bind(PreparedStatement p, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }

  public void backup(Path target) {
    if (!lock.tryLock()) throw new IllegalStateException("БД занята");
    try {
      dialect.backup(c, target);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws Exception {
    lock.lock();
    try {
      try {
        dialect.close();
      } finally {
        c.close();
      }
    } finally {
      lock.unlock();
    }
  }

  public static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  public static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }
}
