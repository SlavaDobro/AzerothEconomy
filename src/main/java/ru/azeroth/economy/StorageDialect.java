package ru.azeroth.economy;

import java.nio.file.Path;
import java.sql.*;

/** Backend-specific DDL, upsert, leases and backup. Domain SQL stays in Ledger. */
public interface StorageDialect extends AutoCloseable {
  Connection open() throws Exception;

  default String sql(String statement) {
    return statement;
  }

  default void configure(Connection c) throws Exception {}

  String integrity(Connection c) throws Exception;

  void backup(Connection c, Path target) throws Exception;

  default boolean remote() {
    return false;
  }

  default void close() throws Exception {}
}
