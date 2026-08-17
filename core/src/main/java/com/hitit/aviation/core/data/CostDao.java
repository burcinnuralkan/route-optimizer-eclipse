package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CostDao {

    /** Tek bir maliyet satırı — kaynağındaki para birimiyle birlikte. */
    public record Line(String type, double amount, String currency,
                       double rateToUsd, double amountUsd) { }

    /**
     * {@code Flight} üzerindeki standart maliyet kovaları. Tip sayısı serbest,
     * KOVA sayısı sabit: bunlar havacılığın standart maliyet kırılımıdır ve
     * modelin alanlarıyla birebir eşleşir.
     *
     * <p>{@code fuel} listede YOK: yakıt maliyeti saklanmaz, yakıt kilosundan
     * ve o günkü yakıt fiyatından HESAPLANIR (bkz. {@code ProfitCalculator}).
     * Saklanan bir yakıt satırı da hesaba katılsaydı yakıt iki kez sayılırdı.
     */
    public static final List<String> BUCKETS =
            List.of("crew", "ownership", "maintenance", "overhead", "nav", "airport");

    private final Database db;
    private final ExchangeRates rates;

    public CostDao(Database db) {
        this.db = db;
        this.rates = new ExchangeRates(db);
    }

    //Kendi para biriminde bir maliyet ekler; dolar karşılığı BURADA hesaplanır.
     public void addCost(long flightId, String type, double amount, String currency,
                        LocalDate flightDate) throws SQLException {
        // Tip adı serbest ama tabloda TANIMLI olmalı; kovası oradan gelir.
        // Tanımsız bir ada yazmaya çalışmak sessizce kaybolmaktansa hata versin.
        if (!typeExists(type)) {
            throw new IllegalArgumentException(
                    "cost_types tablosunda böyle bir tip yok: '" + type + "'."
                    + " Önce tipi kovasıyla birlikte ekleyin.");
        }
        double rate = rates.rate(currency, flightDate);
        double usd = amount * rate;
        String code = currency == null || currency.isBlank()
                ? ExchangeRates.BASE : currency.trim().toUpperCase();

        db.inTransaction(c -> {
            insert(c, flightId, type, amount, code, rate, usd);
            return null;
        });
    }

    /** Tip {@code cost_types} tablosunda tanımlı mı. */
    private boolean typeExists(String type) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM cost_types WHERE name = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Uçuş kimliği → (KOVA → dolar toplamı).
     *
     * <p>Toplam {@code amount_usd} üzerinden alınır, {@code amount} üzerinden
     * değil: çevrim zaten yazma anında yapılmış ve KULLANILAN KUR satırda
     * saklanmıştır. Burada yeniden çevirseydik, kur tablosundaki bir düzeltme
     * geçmiş raporları sessizce değiştirirdi.
     *
     * @throws IllegalStateException tabloda modelde karşılığı olmayan bir tip varsa
     */
    public Map<Long, Map<String, Double>> bucketsByFlight(Connection c) throws SQLException {
        Map<Long, Map<String, Double>> out = new HashMap<>();
        // Tipe göre değil KOVAYA göre toplanır: 'insurance' ve 'legal' ayrı
        // tiplerse ve ikisi de overhead kovasındaysa, model tarafında tek bir
        // overhead değeri görünür. Tip ayrıntısı linesOf ile okunur.
        String sql = """
                SELECT c.flight_id, t.bucket, c.amount_usd
                  FROM costs c JOIN cost_types t ON t.id = c.cost_type_id""";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String bucket = rs.getString(2);
                if ("fuel".equals(bucket)) {
                    throw new IllegalStateException(
                            "Saklanmış yakıt maliyeti bulundu. Yakıt maliyeti yakıt kilosundan"
                            + " HESAPLANIYOR; saklanan satır da eklenseydi yakıt iki kez"
                            + " sayılır ve kâr sessizce düşük çıkardı.");
                }
                if (!BUCKETS.contains(bucket)) {
                    throw new IllegalStateException(
                            "Geçersiz maliyet kovası: '" + bucket + "'. Geçerli kovalar: " + BUCKETS);
                }
                out.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                   .merge(bucket, rs.getDouble(3), Double::sum);
            }
        }
        return out;
    }

    /** Bir uçuşun maliyet satırları (para birimi ayrıntısıyla, gösterim için). */
    public List<Line> linesOf(long flightId) throws SQLException {
        String sql = """
                SELECT t.name, c.amount, c.currency, c.rate_to_usd, c.amount_usd
                  FROM costs c JOIN cost_types t ON t.id = c.cost_type_id
                 WHERE c.flight_id = ?
                 ORDER BY t.name""";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, flightId);
            List<Line> lines = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new Line(rs.getString(1), rs.getDouble(2), rs.getString(3),
                            rs.getDouble(4), rs.getDouble(5)));
                }
            }
            return lines;
        }
    }

    /**
     * Bir uçuşun maliyetlerini verilen dolar tutarlarıyla eşitler.
     *
     * <p><b>Değişmeyen tipe DOKUNULMAZ.</b> Bir tipin mevcut satırlarının dolar
     * toplamı gelen değere eşitse satırlar olduğu gibi bırakılır. Sebebi veri
     * kaybını önlemek: masaüstü kullanıcısı ekranda dolar görüyor ve kaydederken
     * dolar gönderiyor; her kaydetmede satırları yeniden yazsaydık, 100 EURO
     * olarak girilmiş bir maliyet ilk kaydetmede "USD 108" satırına dönüşür ve
     * aslının EURO olduğu bilgisi kaybolurdu.
     */
    void replaceChanged(Connection c, long flightId, Map<String, Double> newBuckets)
            throws SQLException {

        Map<String, Double> current = currentBuckets(c, flightId);

        for (String bucket : BUCKETS) {
            double incoming = newBuckets.getOrDefault(bucket, 0.0);
            double existing = current.getOrDefault(bucket, 0.0);
            if (Math.abs(incoming - existing) < 0.005) continue;   // sent yuvarlaması

            deleteBucket(c, flightId, bucket);
            if (incoming != 0) insertUsd(c, flightId, bucket, incoming);
        }
    }

    private Map<String, Double> currentBuckets(Connection c, long flightId) throws SQLException {
        Map<String, Double> sums = new LinkedHashMap<>();
        String sql = """
                SELECT t.bucket, c.amount_usd
                  FROM costs c JOIN cost_types t ON t.id = c.cost_type_id
                 WHERE c.flight_id = ?""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, flightId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sums.merge(rs.getString(1), rs.getDouble(2), Double::sum);
            }
        }
        return sums;
    }

    /**
     * Kovanın TÜM satırlarını siler — yalnızca kovayla aynı adı taşıyanı değil.
     * {@code IN} kullanılmasının sebebi bu: bir kovaya birden çok tip düşebilir.
     */
    private static void deleteBucket(Connection c, long flightId, String bucket)
            throws SQLException {
        String sql = """
                DELETE FROM costs
                 WHERE flight_id = ?
                   AND cost_type_id IN (SELECT id FROM cost_types WHERE bucket = ?)""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, flightId);
            ps.setString(2, bucket);
            ps.executeUpdate();
        }
    }

    private static void insertUsd(Connection c, long flightId, String type, double usd)
            throws SQLException {
        insert(c, flightId, type, usd, ExchangeRates.BASE, 1.0, usd);
    }

    /** Tek maliyet satırı: aslı, para birimi, KULLANILAN kur ve dolar karşılığı. */
    private static void insert(Connection c, long flightId, String type, double amount,
                               String currency, double rate, double usd) throws SQLException {
        String sql = """
                INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
                VALUES (?, (SELECT id FROM cost_types WHERE name = ?), ?, ?, ?, ?)""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, flightId);
            ps.setString(2, type);
            ps.setDouble(3, amount);
            ps.setString(4, currency);
            ps.setDouble(5, rate);
            ps.setDouble(6, usd);
            ps.executeUpdate();
        }
    }
}
 