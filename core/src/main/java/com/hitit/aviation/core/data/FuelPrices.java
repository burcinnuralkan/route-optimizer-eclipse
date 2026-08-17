package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

//{@code fuel_prices} tablosunun kapısı: (tarih, yakıt tipi) → 1 kg yakıtın kaç ABD doları ettiği
public class FuelPrices {

    /** Konvansiyonel jet yakıtı. */
    public static final String JET_A1 = "JET_A1";

    /** Sürdürülebilir havacılık yakıtı. */
    public static final String SAF = "SAF";

    /** Fiyatların saklandığı para birimi tabanı. */
    public static final String BASE = ExchangeRates.BASE;

    private final Database db;

    /**
     * Yazma anında para birimi çevirisi için. {@link CostDao} de kendi
     * {@link ExchangeRates}'ini yaratıyor; kurun tek okuma kaynağı olması yeterli,
     * nesnenin paylaşılması gerekmiyor (önbellek dışında durumu yok).
     */
    private final ExchangeRates rates;
    /** Aynı (tip, tarih) tarife boyunca tekrar tekrar sorulur; bir okuma yeter. */
    private final Map<String, Double> cache = new HashMap<>();

    public FuelPrices(Database db) {
        this.db = db;
        this.rates = new ExchangeRates(db);
    }

    // Verilen tarihte 1 kg yakıtın dolar fiyatı.
    public double pricePerKgUsd(String fuelType, LocalDate date) throws SQLException {
        String type = fuelType == null ? "" : fuelType.trim().toUpperCase();
        String key = type + "@" + date;

        Double cached = cache.get(key);
        if (cached != null) return cached;

        Double found = lookup(type, date);
        if (found == null) {
            throw new IllegalStateException(
                    "Yakıt fiyatı bulunamadı: " + type + " için " + date + " ya da öncesine ait"
                    + " bir satır yok (fuel_prices tablosu). Fiyat girilmeden yakıt maliyeti"
                    + " hesaplanamaz.");
        }
        cache.put(key, found);
        return found;
    }

    /** Kısayol: konvansiyonel jet yakıtının o günkü dolar fiyatı. */
    public double jetA1PerKgUsd(LocalDate date) throws SQLException {
        return pricePerKgUsd(JET_A1, date);
    }

    /** Kısayol: SAF'ın o günkü dolar fiyatı. */
    public double safPerKgUsd(LocalDate date) throws SQLException {
        return pricePerKgUsd(SAF, date);
    }

    /**
     * Fiyat satırı ekler/günceller (aynı tarih ve tip varsa üzerine yazar).
     *
     * <p>Fiyat KENDİ para biriminde verilir; dolar karşılığı BURADA, yazma anında
     * hesaplanır ve kullanılan kurla birlikte saklanır. {@link CostDao#addCost}
     * ile aynı gerekçe: çevrimi okuma anında yapsaydık, kur tablosundaki bir
     * düzeltme geçen ayın raporunu sessizce değiştirirdi.
     *
     * @throws IllegalArgumentException tip tanınmıyorsa ya da fiyat pozitif değilse
     * @throws IllegalStateException    o tarihte para biriminin kuru yoksa
     */
    public void save(LocalDate date, String fuelType, double pricePerKg, String currency)
            throws SQLException {

        String type = fuelType == null ? "" : fuelType.trim().toUpperCase();
        if (!JET_A1.equals(type) && !SAF.equals(type)) {
            throw new IllegalArgumentException(
                    "Bilinmeyen yakıt tipi: '" + fuelType + "'. Geçerli tipler: "
                    + JET_A1 + ", " + SAF + ".");
        }
        if (pricePerKg <= 0) {
            throw new IllegalArgumentException("Yakıt fiyatı pozitif olmalı: " + pricePerKg);
        }

        String code = currency == null || currency.isBlank()
                ? BASE : currency.trim().toUpperCase();
        double rate = rates.rate(code, date);
        double usd = pricePerKg * rate;

        String sql = Sql.upsert("fuel_prices",
                "price_date, fuel_type, currency, price_per_kg, rate_to_usd, price_per_kg_usd",
                "price_date", "fuel_type");
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, date.toString());
                ps.setString(2, type);
                ps.setString(3, code);
                ps.setDouble(4, pricePerKg);
                ps.setDouble(5, rate);
                ps.setDouble(6, usd);
                ps.executeUpdate();
            }
            return null;
        });
        cache.clear();
    }

    private Double lookup(String fuelType, LocalDate date) throws SQLException {
        // ORDER BY ... DESC LIMIT 1: tam tarih yoksa ondan önceki EN YAKIN gün.
        String sql = """
                SELECT price_per_kg_usd FROM fuel_prices
                 WHERE fuel_type = ? AND price_date <= ?
                 ORDER BY price_date DESC
                 LIMIT 1""";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fuelType);
            ps.setString(2, date.toString());   // ISO: metin sıralaması = tarih sıralaması
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        }
    }
}

 