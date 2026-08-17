package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.hitit.aviation.core.model.Airport;

public class AirportDao {

    private static final String COLUMNS = "code, name, city, country, lat, lon";

    private final Database db;

    /** Upsert ifadesi bir kez kurulup saklanır (bkz. {@link Sql}). */
    private final String upsert;

    public AirportDao(Database db) {
        this.db = db;
        this.upsert = Sql.upsert("airports", COLUMNS, "code");
    }

    /** Tek havalimanını ekler ya da günceller. */
    public void save(Airport airport) throws SQLException {
        db.inTransaction(c -> {
            save(c, airport);
            return null;
        });
    }

    /** Verilenlerin tamamını TEK işlemde ekler/günceller; listede olmayanlara dokunmaz. */
    public void saveAll(Collection<Airport> airports) throws SQLException {
        db.inTransaction(c -> {
            saveAll(c, airports);
            return null;
        });
    }

    /**
     * Havalimanını siler. Ona bağlı uçuş varsa yabancı anahtar kısıtı silmeyi
     * reddeder (sessizce yetim uçuş bırakmaktansa hata almak doğrudur).
     * Satır silindiyse {@code true}.
     */
    public boolean delete(String code) throws SQLException {
        return db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM airports WHERE code = ?")) {
                ps.setString(1, code);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Tüm havalimanları, koda göre eşlenmiş (ekleme sırası = kod sırası). */
    public Map<String, Airport> findAll() throws SQLException {
        try (Connection c = db.open()) {
            return findAll(c);
        }
    }

    public Optional<Airport> find(String code) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + COLUMNS + " FROM airports WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public int count() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM airports");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ── Var olan bir işleme katılan sürümler ────────────────────────────────
    // Uçuşları kaydetmek, önce havalimanlarını kaydetmeyi gerektirir (yabancı
    // anahtar). İkisinin AYNI işlemde olması için bağlantıyı dışarıdan alan bu
    // sürümler var; bkz. FlightDao#saveAll.

    void save(Connection c, Airport a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            bind(ps, a);
            ps.executeUpdate();
        }
    }

    void saveAll(Connection c, Collection<Airport> airports) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            for (Airport a : airports) {
                bind(ps, a);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    Map<String, Airport> findAll(Connection c) throws SQLException {
        Map<String, Airport> byCode = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT " + COLUMNS + " FROM airports ORDER BY code");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Airport a = map(rs);
                byCode.put(a.code(), a);
            }
        }
        return byCode;
    }

    private static void bind(PreparedStatement ps, Airport a) throws SQLException {
        ps.setString(1, a.code());
        ps.setString(2, a.name());
        ps.setString(3, a.city());
        ps.setString(4, a.country());
        ps.setDouble(5, a.lat());
        ps.setDouble(6, a.lon());
    }

    private static Airport map(ResultSet rs) throws SQLException {
        return new Airport(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("country"),
                rs.getDouble("lat"),
                rs.getDouble("lon"));
    }
}