package com.hitit.aviation.core.data;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class ConnectionFactory {
   private ConnectionFactory() {}
   public static Connection open() throws SQLException {
       Path dbFile = DatabaseLocation.resolve();
       Path parentDir = dbFile.getParent();
       if (parentDir != null) {
           try {
               Files.createDirectories(parentDir);
           } catch (IOException e) {
               throw new SQLException("Cannot create directory: " + parentDir, e);
           }
       }
       Connection connection = DriverManager.getConnection(
               "jdbc:sqlite:" + dbFile.toAbsolutePath());
       try (Statement stmt = connection.createStatement()) {
           stmt.execute("PRAGMA journal_mode = WAL");
           stmt.execute("PRAGMA busy_timeout = 5000");
           stmt.execute("PRAGMA foreign_keys = ON");
       } catch (SQLException e) {
           try { connection.close(); } catch (SQLException ignored) {}
           throw e;
       }
       return connection;
   }
}