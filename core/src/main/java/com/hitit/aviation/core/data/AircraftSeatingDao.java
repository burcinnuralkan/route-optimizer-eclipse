package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.hitit.aviation.core.model.Flight;

/**
 * {@code aircraft_seating} tablosunun kapısı: (kuyruk numarası, tarih) →
 * o uçağın O TARİHTE sunduğu kapasite.
 *
 * <h2>Neden tarihli</h2>
 * Koltuk sayısı uçağın özelliğidir — uçuş satırında yüzlerce kez tekrar
 * edilmesi 3NF'e aykırıydı. Ama SABİT değildir: kabin yenilenir, koltuk düzeni
 * değişir. Uçak tablosunda tek satır olsaydı bir yenileme GEÇMİŞ dönemlerin
 * ASK'sini ve doluluk oranını geriye dönük değiştirirdi — kimse fark etmeden
 * (ProfitCalculator ASK'yi {@code seats() * distanceKm()} olarak hesaplıyor).
 *
 * <p>Bu yüzden desen {@link FuelPrices} ve {@link ExchangeRates} ile aynı:
 * tarihli referans veri, uçuşun tarihine göre EN YAKIN ÖNCEKİ satırdan okunur.
 * Sonraki tarihe bakmak yanlış olurdu — 14 Mart'taki bir uçuşu Haziran'da
 * yapılan kabin yenilemesinin koltuk sayısıyla ölçmek demek.
 *
 * <p>Bitiş tarihi tutulmaz: bir sonraki satırın {@code valid_from}'u zaten
 * bitişi belirtir. İki yerde tutmak ikisinin çelişmesine kapı açardı.
 */
public class AircraftSeatingDao {

    /**
     * Bir uçağın belirli bir dönemdeki kapasitesi.
     *
     * <p>Kargo kapasitesi de burada: o da uçağın SUNDUĞU kapasite, taşınan yük
     * ({@code cargo_kg}) değil. Taşınan yük ve yolcu uçuş satırında kalır.
     */
    public record Seating(int econSeats, int busSeats, double cargoCapacityKg) {

        /** Kargo kapasitesi sent düzeyinde karşılaştırılır (kayan nokta gürültüsü). */
        boolean sameAs(Seating other) {
            return econSeats == other.econSeats
                    && busSeats == other.busSeats
                    && Math.abs(cargoCapacityKg - other.cargoCapacityKg) < 0.005;
        }
    }

    /**
     * Kuyruğun bilinen ilk düzeninin tarihi. {@link FuelPrices} göçündeki taban
     * satırla aynı işi görür: okuma "o tarih ya da öncesindeki en yakın satır"
     * diye çalıştığı için, bu satır HER uçuş tarihi için bulunur. Aksi hâlde
     * filodaki ilk uçuştan önceye tarihlenmiş bir uçuş kapasitesiz kalırdı.
     */
    static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    private static final String COLUMNS =
            "tail_number, valid_from, econ_seats, bus_seats, cargo_capacity_kg";

    private final Database db;

    /** Upsert ifadesi bir kez kurulup saklanır (bkz. {@link Sql}). */
    private final String upsert;

    public AircraftSeatingDao(Database db) {
        this.db = db;
        this.upsert = Sql.upsert("aircraft_seating", COLUMNS, "tail_number", "valid_from");
    }

    /**
     * Bir uçağın verilen tarihteki kapasitesi.
     *
     * <p>Tam o güne ait satır yoksa ONDAN ÖNCEKİ en yakın tarih kullanılır.
     * Hiç satır yoksa boş döner — {@link FuelPrices} gibi istisna atmıyor,
     * çünkü kapasite eksikliği hesabı sessizce yanlış yapmaz: çağıran
     * ({@link FlightDao}) uçuşun kendi yolcu sayısından bir yer tutucu üretip
     * yüklemeye devam edebiliyor.
     */
    public Optional<Seating> on(String tailNumber, LocalDate date) throws SQLException {
        try (Connection c = db.open()) {
            return Optional.ofNullable(lookup(c, tailNumber, date));
        }
    }

    /**
     * Bir düzeni kaydeder: verilen tarihten İTİBAREN geçerli olur.
     *
     * <p>Dikkat — bu ileriye dönük bir kayıttır. 1 Haziran'a yazılan bir düzen,
     * 1 Haziran'dan sonraki (bir sonraki satıra kadar olan) TÜM uçuşların
     * kapasitesini belirler; yalnızca o günü değil.
     */
    public void save(String tailNumber, LocalDate validFrom, Seating seating) throws SQLException {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                bind(ps, tailNumber, validFrom, seating);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Bir uçağın tüm kapasite geçmişi, tarihe göre sıralı. */
    public NavigableMap<LocalDate, Seating> historyOf(String tailNumber) throws SQLException {
        try (Connection c = db.open()) {
            return findAll(c).getOrDefault(tailNumber, new TreeMap<>());
        }
    }

    // ── Var olan bir işleme katılan sürümler ────────────────────────────────

    /**
     * Tüm filonun kapasite geçmişi: kuyruk → (geçerlilik başlangıcı → düzen).
     *
     * <p>Tarife yüklenirken uçuş başına ayrı sorgu atmamak için tablonun tamamı
     * tek okumada belleğe alınır; {@link NavigableMap#floorEntry} ile her uçuşun
     * tarihine düşen satır bulunur. Tablo kuyruk sayısı kadar (yenileme başına
     * bir satır) büyüklükte, yani filo boyutunda.
     */
    Map<String, NavigableMap<LocalDate, Seating>> findAll(Connection c) throws SQLException {
        Map<String, NavigableMap<LocalDate, Seating>> byTail = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT " + COLUMNS + " FROM aircraft_seating ORDER BY tail_number, valid_from");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byTail.computeIfAbsent(rs.getString("tail_number"), k -> new TreeMap<>())
                      .put(LocalDate.parse(rs.getString("valid_from")), map(rs));
            }
        }
        return byTail;
    }

    /**
     * Verilen uçuşların taşıdığı kapasiteyi tabloya işler.
     *
     * <p><b>Değişmeyen düzene DOKUNULMAZ:</b> uçuşun tarihinde geçerli olan
     * satır zaten aynı kapasiteyi söylüyorsa hiçbir şey yazılmaz. Bu kural
     * olmasaydı her kaydetme filodaki her kuyruk için yeni bir satır üretir ve
     * tablo, hiçbir şey değişmediği hâlde tarife boyutunda büyürdü.
     *
     * <p>Kuyruğun hiç satırı yoksa düzen {@link #EPOCH}'a yazılır ("başından
     * beri böyleydi"); satırı var ama uçuşun tarihinde FARKLI bir kapasite
     * söylüyorsa uçuşun tarihine yeni bir satır yazılır ("o gün değişmiş").
     *
     * <p>Uçuşlar KALKIŞ SIRASINA göre işlenir ve sıra önemlidir: sonraki bir
     * uçuş önce işlenseydi, onun düzeni {@code EPOCH}'a yazılır ve daha eski
     * uçuş kendi tarihine bir satır ekleyerek sonrakini de ezerdi.
     */
    void syncAll(Connection c, Collection<Flight> flights) throws SQLException {
        if (flights.isEmpty()) return;

        Map<String, NavigableMap<LocalDate, Seating>> known = findAll(c);
        Map<String, NavigableMap<LocalDate, Seating>> pending = new LinkedHashMap<>();

        List<Flight> ordered = new ArrayList<>(flights);
        ordered.sort(Comparator.comparing(Flight::schedDep).thenComparing(Flight::flightNo));

        for (Flight f : ordered) {
            NavigableMap<LocalDate, Seating> history =
                    known.computeIfAbsent(f.tailNumber(), k -> new TreeMap<>());
            Seating incoming = new Seating(f.econSeats(), f.busSeats(), f.cargoCapacityKg());

            Map.Entry<LocalDate, Seating> effective = history.floorEntry(f.flightDate());
            LocalDate validFrom;
            if (effective == null) {
                validFrom = EPOCH;                       // kuyruğun ilk (ya da en eski) düzeni
            } else if (effective.getValue().sameAs(incoming)) {
                continue;                                // değişmemiş: yazma yok
            } else {
                validFrom = f.flightDate();              // o gün değişmiş
            }

            history.put(validFrom, incoming);
            pending.computeIfAbsent(f.tailNumber(), k -> new TreeMap<>()).put(validFrom, incoming);
        }

        if (pending.isEmpty()) return;

        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            for (Map.Entry<String, NavigableMap<LocalDate, Seating>> tail : pending.entrySet()) {
                for (Map.Entry<LocalDate, Seating> row : tail.getValue().entrySet()) {
                    bind(ps, tail.getKey(), row.getKey(), row.getValue());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    // ── içeriden ────────────────────────────────────────────────────────────

    private Seating lookup(Connection c, String tailNumber, LocalDate date) throws SQLException {
        // ORDER BY ... DESC LIMIT 1: tam tarih yoksa ondan önceki EN YAKIN gün.
        String sql = "SELECT " + COLUMNS + " FROM aircraft_seating"
                + " WHERE tail_number = ? AND valid_from <= ?"
                + " ORDER BY valid_from DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tailNumber);
            ps.setString(2, date.toString());   // ISO: metin sıralaması = tarih sıralaması
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private static void bind(PreparedStatement ps, String tailNumber, LocalDate validFrom,
                             Seating s) throws SQLException {
        ps.setString(1, tailNumber);
        ps.setString(2, validFrom.toString());
        ps.setInt(3, s.econSeats());
        ps.setInt(4, s.busSeats());
        ps.setDouble(5, s.cargoCapacityKg());
    }

    private static Seating map(ResultSet rs) throws SQLException {
        return new Seating(rs.getInt("econ_seats"), rs.getInt("bus_seats"),
                rs.getDouble("cargo_capacity_kg"));
    }
}
 