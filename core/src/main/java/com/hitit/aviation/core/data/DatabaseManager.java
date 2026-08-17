package com.hitit.aviation.core.data;
 
import java.sql.Connection;
import java.sql.SQLException;
 
public final class DatabaseManager {
 
    private static DatabaseManager instance;
 
    private DatabaseManager() {
        try (Connection connection = ConnectionFactory.open()) {
        } catch (SQLException e) {
            throw new IllegalStateException("Database init failed", e);
        }
        System.out.println("Database ready: " + DatabaseLocation.resolve());
    }
 
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }
 
    public Connection getConnection() throws SQLException {
        return ConnectionFactory.open();
    }
}