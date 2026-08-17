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

/**
 * {@code revenues} ve {@code revenue_types} tablolarının kapısı.
 *
 * <p>{@link CostDao}'nun gelir tarafındaki eşi ve bilerek onun aynısı: aynı
 * tablo yapısı, aynı kova mantığı, aynı "kullanılan kuru satırda sakla"
 * kararı. İkisini tek bir "tutarlar" sınıfına indirmek satır kazandırırdı ama
 * okuyanın her çağrıda gelire mi gidere mi baktığını çözmesi gerekirdi.
 *
 * <h2>Neden ayrı tablo</h2>
 * Gelirler eskiden {@code flights} üzerinde üç sütundu ve adları {@code _usd}
 * ile bitiyordu. İki sonucu vardı: yeni bir gelir kalemi ("fazla bagaj",
 * "lounge") şema değişikliği demekti, ve EURO satılmış bir kargo yükünün aslı
 * kaydın hiçbir yerinde durmuyordu — yalnızca dolara çevrilmiş hâli.
 *
 * <h2>Modelin şekli neden değişmedi</h2>
 * {@link com.hitit.aviation.core.model.Flight} hâlâ üç DOLAR alanı taşıyor
 * ({@code paxRevenueUsd}, {@code ancillaryRevenueUsd}, {@code cargoRevenueUsd}).
 * Depolama normalleşti ama hesap motoru tek para biriminde çalışmaya devam
 * ediyor; para birimi çevrimi bu sınırda bitiyor.
 *
 * <h2>Yeni bir gelir tipi nasıl işler</h2>
 * Tabloya {@code ('excess_baggage', bucket='ancillary')} eklemek yeter: gelir
 * kendi adıyla ve kendi para biriminde durur, satır olarak görünür
 * ({@link #linesOf}), hesapta ancillary kovasına toplanır. Koda dokunulmaz.
 */
public class RevenueDao {

    /** Tek bir gelir satırı — kaynağındaki para birimiyle birlikte. */
    public record Line(String type, double amount, String currency,
                       double rateToUsd, double amountUsd) { }

    /**
     * {@code Flight} üzerindeki standart gelir kovaları. Tip sayısı serbest,
     * KOVA sayısı sabit: havacılığın standart gelir kırılımı (bilet, yan gelir,
     * kargo) ve modelin alanlarıyla birebir eşleşiyor.
     */
    public static final List<String> BUCKETS = List.of("pax", "ancillary", "cargo");

    private final Database db;
    private final ExchangeRates rates;

    public RevenueDao(Database db) {
        this.db = db;
        this.rates = new ExchangeRates(db);
    }

    /**
     * Kendi para biriminde bir gelir ekler; dolar karşılığı BURADA hesaplanır.
     *
     * <p>Kur, uçuşun KALKIŞ TARİHİNE göre bulunur ve kullanılan kur satıra
     * yazılır — {@link CostDao#addCost} ile birebir aynı gerekçe: çevrimi okuma
     * anında yapsaydık, kur tablosundaki bir düzeltme geçen ayın raporunu
     * sessizce değiştirirdi.
     *
     * @throws IllegalArgumentException tip {@code revenue_types}'ta tanımlı değilse
     * @throws IllegalStateException    o tarihte ya da öncesinde kur yoksa
     */
    public void addRevenue(long flightId, String type, double amount, String currency,
                           LocalDate flightDate) throws SQLException {
        if (!typeExists(type)) {
            throw new IllegalArgumentException(
                    "revenue_types tablosunda böyle bir tip yok: '" + type + "'."
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

    /** Tip {@code revenue_types} tablosunda tanımlı mı. */
    private boolean typeExists(String type) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM revenue_types WHERE name = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Uçuş kimliği → (KOVA → dolar toplamı).
     *
     * <p>Toplam {@code amount_usd} üzerinden alınır: çevrim yazma anında
     * yapılmış ve kullanılan kur satırda saklanmıştır.
     *
     * @throws IllegalStateException tabloda modelde karşılığı olmayan bir kova varsa
     */
    public Map<Long, Map<String, Double>> bucketsByFlight(Connection c) throws SQLException {
        Map<Long, Map<String, Double>> out = new HashMap<>();
        String sql = """
                SELECT r.flight_id, t.bucket, r.amount_usd
                  FROM revenues r JOIN revenue_types t ON t.id = r.revenue_type_id""";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String bucket = rs.getString(2);
                if (!BUCKETS.contains(bucket)) {
                    throw new IllegalStateException(
                            "Geçersiz gelir kovası: '" + bucket + "'. Geçerli kovalar: " + BUCKETS);
                }
                out.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                   .merge(bucket, rs.getDouble(3), Double::sum);
            }
        }
        return out;
    }

    /** Bir uçuşun gelir satırları (para birimi ayrıntısıyla, gösterim için). */
    public List<Line> linesOf(long flightId) throws SQLException {
        String sql = """
                SELECT t.name, r.amount, r.currency, r.rate_to_usd, r.amount_usd
                  FROM revenues r JOIN revenue_types t ON t.id = r.revenue_type_id
                 WHERE r.flight_id = ?
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
     * Bir uçuşun gelirlerini verilen dolar tutarlarıyla eşitler.
     *
     * <p><b>Değişmeyen kovaya DOKUNULMAZ</b> — {@link CostDao#replaceChanged}
     * ile aynı kural ve aynı sebep: masaüstü kullanıcısı ekranda dolar görüyor
     * ve kaydederken dolar gönderiyor. Her kaydetmede satırları yeniden
     * yazsaydık, 100 EURO olarak girilmiş bir kargo geliri ilk kaydetmede
     * "USD 108" satırına döner ve aslının EURO olduğu bilgisi kaybolurdu.
     *
     * <p>Karşılaştırma da silme de TİPE göre değil KOVAYA göre: gelen değerler
     * modelden geliyor ve model kovaları tanıyor. Tipe göre yapılsaydı standart
     * adı olmayan her tip iki kez sayılırdı.
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
                SELECT t.bucket, r.amount_usd
                  FROM revenues r JOIN revenue_types t ON t.id = r.revenue_type_id
                 WHERE r.flight_id = ?""";
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
                DELETE FROM revenues
                 WHERE flight_id = ?
                   AND revenue_type_id IN (SELECT id FROM revenue_types WHERE bucket = ?)""";
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

    /** Tek gelir satırı: aslı, para birimi, KULLANILAN kur ve dolar karşılığı. */
    private static void insert(Connection c, long flightId, String type, double amount,
                               String currency, double rate, double usd) throws SQLException {
        String sql = """
                INSERT INTO revenues (flight_id, revenue_type_id, amount, currency, rate_to_usd, amount_usd)
                VALUES (?, (SELECT id FROM revenue_types WHERE name = ?), ?, ?, ?, ?)""";
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
 