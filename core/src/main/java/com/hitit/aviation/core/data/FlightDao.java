package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;

import com.hitit.aviation.core.model.Airport;
import com.hitit.aviation.core.model.Flight;

/**
 * {@code flights} tablosunun okuma/yazma kapısı. SQL yalnızca burada;
 * dışarısı {@link Flight} nesnesiyle konuşur.
 *
 * <p>Bir {@link Flight} kalkış/varış havalimanını KOD olarak değil, {@link Airport}
 * NESNESİ olarak taşır. Bu yüzden DAO havalimanı tablosunu da bilmek zorundadır:
 * okurken kodları nesneye çevirmek ({@link #findAll()}), yazarken de uçuşun işaret
 * ettiği havalimanının tabloda bulunduğundan emin olmak için. İkincisi bir tercih
 * değil, yabancı anahtarın gereği: havalimanı yoksa uçuş satırı hiç yazılamaz.
 */
public class FlightDao {

    /**
     * {@code flights} tablosunda geriye UÇUŞUN KENDİSİ kaldı; para ve kapasite
     * kendi tablolarına taşındı:
     *
     * <ul>
     *   <li>maliyetler → {@code costs} ({@link CostDao})</li>
     *   <li>gelirler → {@code revenues} ({@link RevenueDao})</li>
     *   <li>uçak tipi, koltuk ve kargo KAPASİTESİ → {@code aircraft}
     *       ({@link AircraftDao}), tarihiyle birlikte</li>
     * </ul>
     *
     * <p>Kalan sayısal sütunlar GERÇEKLEŞENİ ölçüyor: kaç yolcu bindi
     * ({@code econ_pax}, {@code bus_pax}), kaç kilo yük taşındı
     * ({@code cargo_kg}), ne kadar yakıt yakıldı. Kapasite ile doluluk arasındaki
     * ayrım tam olarak bu.
     */
    private static final String COLUMNS = """
            flight_no, airline_code, tail_number, from_code, to_code,
            sched_dep, sched_arr, actual_dep, actual_arr,
            econ_pax, bus_pax, cargo_kg, fuel_kg, distance_km""";

    private final Database db;
    private final AirportDao airports;

    /**
     * Maliyet satırlarının kapısı. {@link AirportDao} gibi dışarıdan
     * ALINMIYOR, burada yaratılıyor: havalimanı tablosunun kendi başına bir
     * anlamı var (arayüz kalkış/varış listesini oradan doldurur), maliyet
     * satırlarının ise yok — uçuşun bir parçası olarak var oluyorlar ve
     * uçuştan bağımsız okunmaları gereken bir yer bulunmuyor. Ayrıca kendi
     * yapılandırması olmadığı için dışarıdan verilmesi çağıranlara sadece
     * fazladan bir parametre olurdu.
     */
    private final CostDao costs;

    /** Gelir satırlarının kapısı; {@link CostDao} ile aynı gerekçeyle burada. */
    private final RevenueDao revenues;

    /**
     * Uçak tablosunun kapısı — tip ve kapasite, uçağın tarihli hâli olarak
     * birlikte. {@link CostDao} ile aynı gerekçeyle burada yaratılıyor: uçak
     * satırlarını bugün uçuşlardan bağımsız okuyan bir yer yok, dolayısıyla
     * dışarıdan verilmesi çağıranlara sadece fazladan bir parametre olurdu.
     */
    private final AircraftDao aircraft;

    /**
     * Upsert ifadesi bir kez kurulup saklanır. Sütun listesi ve yer tutucular
     * {@link Sql} içinde COLUMNS metninden türetilir — elle yazılsaydı yeni bir
     * sütun eklendiğinde soru işareti eklemeyi unutmak derleme hatası vermez,
     * çalışma anında sayı uyuşmazlığı olurdu.
     *
     * <p>İfadenin var olan satırı SİLMEDEN güncellemesi burada kritik:
     * {@code flights.id} silinip yeniden eklenirse değişir ve uçuşun maliyet
     * satırları {@code ON DELETE CASCADE} ile onunla birlikte gider (bkz.
     * {@link Sql}).
     */
    private final String upsert;

    public FlightDao(Database db, AirportDao airports) {
        this.db = db;
        this.airports = airports;
        this.costs = new CostDao(db);
        this.revenues = new RevenueDao(db);
        this.aircraft = new AircraftDao(db);
        this.upsert = Sql.upsert("flights", COLUMNS, "flight_no", "sched_dep");
    }

    /** Uçuşun maliyetleri, kova adına göre dolar cinsinden. */
    private static Map<String, Double> costBucketsOf(Flight f) {
        return Map.of(
                "crew", f.crewCostUsd(),
                "ownership", f.ownershipCostUsd(),
                "maintenance", f.maintenanceCostUsd(),
                "overhead", f.overheadCostUsd(),
                "nav", f.navCostUsd(),
                "airport", f.airportCostUsd());
    }

    /** Uçuşun gelirleri, kova adına göre dolar cinsinden. */
    private static Map<String, Double> revenueBucketsOf(Flight f) {
        return Map.of(
                "pax", f.paxRevenueUsd(),
                "ancillary", f.ancillaryRevenueUsd(),
                "cargo", f.cargoRevenueUsd());
    }

    /**
     * Doğal anahtar → yapay kimlik. Maliyet satırları {@code flights.id}'ye
     * bağlı; uçuşlar yazıldıktan SONRA kimlikleri okunup maliyetler ona göre
     * yazılır.
     */
    private static Map<String, Long> idsByKey(Connection c) throws SQLException {
        Map<String, Long> ids = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, flight_no, sched_dep FROM flights");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.put(key(rs.getString(2), rs.getString(3)), rs.getLong(1));
        }
        return ids;
    }

    /** Uçuşlar yazıldıktan sonra maliyet ve gelir satırlarını eşitler. */
    private void writeAmounts(Connection c, Collection<Flight> flights) throws SQLException {
        Map<String, Long> ids = idsByKey(c);
        for (Flight f : flights) {
            Long id = ids.get(key(f.flightNo(), SqlTime.toDb(f.schedDep())));
            if (id == null) continue;
            costs.replaceChanged(c, id, costBucketsOf(f));
            revenues.replaceChanged(c, id, revenueBucketsOf(f));
        }
    }

    /**
     * Uçuşlardan ÖNCE yazılması gerekenler: havalimanı yabancı anahtar olduğu,
     * uçağın varlığı ise trigger'la ({@code flights_aircraft_exists_insert})
     * doğrulandığı için.
     */
    private void writeReferences(Connection c, Collection<Flight> flights) throws SQLException {
        airports.saveAll(c, referencedAirports(flights));
        aircraft.syncAll(c, flights);
    }

    /** Tek uçuşu ekler ya da günceller (uçuş numarası aynıysa üzerine yazar). */
    public void save(Flight flight) throws SQLException {
        db.inTransaction(c -> {
            writeReferences(c, List.of(flight));
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                bind(ps, flight);
                ps.executeUpdate();
            }
            writeAmounts(c, List.of(flight));
            return null;
        });
    }

    /**
     * Tarifenin TAMAMINI tek işlemde yazar: verilen listede olmayan uçuşlar silinir,
     * olanlar eklenir/güncellenir.
     *
     * <p><b>Neden "tamamını" ve neden tek işlem?</b> Masaüstündeki model dosya
     * modelinden geliyor: değişiklikler bellekte birikir, kullanıcı "Kaydet"e
     * basınca hepsi birden yazılır — böylece "Geri Al" hâlâ anlamlıdır (henüz
     * hiçbir şey kalıcı olmamıştır). Satır satır yazsaydık her düzenleme anında
     * kalıcı olur, geri alma bellekte kalıp veritabanıyla çelişirdi.
     *
     * <p>İşlem içinde önce havalimanları yazılır: uçuş yeni bir havalimanına
     * işaret ediyorsa (dışarıdan CSV yüklendiğinde olur) yabancı anahtar aksi
     * hâlde satırı reddederdi.
     */
    public void saveAll(Collection<Flight> flights) throws SQLException {
        db.inTransaction(c -> {
            writeReferences(c, flights);
            deleteMissing(c, flights);
            upsertAll(c, flights);
            writeAmounts(c, flights);
            return null;
        });
    }

    /**
     * Verilenleri ekler/günceller ama tabloda olup listede olmayanlara DOKUNMAZ.
     *
     * <p>{@link #saveAll} ile farkı niyettir: {@code saveAll} "tarifenin tamamı
     * budur" der (masaüstündeki Kaydet), bu ise "şunları da ekle" der. İkinci bir
     * CSV'yi içeri aktarırken {@code saveAll} kullanılırsa ilk dosyadaki uçuşlar
     * listede görünmedikleri için silinir — bu metot tam olarak onu önler.
     */
    public void addAll(Collection<Flight> flights) throws SQLException {
        db.inTransaction(c -> {
            writeReferences(c, flights);
            upsertAll(c, flights);
            writeAmounts(c, flights);
            return null;
        });
    }

    /**
     * TEK bir uçuşu siler. Uçuş numarası tek başına yetmez: aynı numara farklı
     * günlerde tekrar eder ({@code TK7101} her sabah kalkar), kalkış zamanıyla
     * birlikte tanımlanır.
     *
     * @return satır silindiyse {@code true}
     */
    public boolean delete(String flightNo, LocalDateTime schedDep) throws SQLException {
        return db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM flights WHERE flight_no = ? AND sched_dep = ?")) {
                ps.setString(1, flightNo);
                ps.setString(2, SqlTime.toDb(schedDep));
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean delete(Flight flight) throws SQLException {
        return delete(flight.flightNo(), flight.schedDep());
    }

    /** Bir uçuş numarasının TÜM günlerini siler. Silinen satır sayısını döner. */
    public int deleteAll(String flightNo) throws SQLException {
        return db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM flights WHERE flight_no = ?")) {
                ps.setString(1, flightNo);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Tarifenin tamamı, kalkış saatine göre sıralı. Havalimanı kodları tek bir
     * okumayla nesneye çevrilir (uçuş başına ayrı sorgu yok).
     */
    public List<Flight> findAll() throws SQLException {
        try (Connection c = db.open()) {
            Lookups lookups = lookups(c);
            List<Flight> flights = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                         "SELECT id, " + COLUMNS + " FROM flights ORDER BY sched_dep, flight_no");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    flights.add(map(rs, lookups));
                }
            }
            return flights;
        }
    }

    /**
     * Bir uçuş numarasının TÜM günleri, kalkışa göre sıralı.
     *
     * <p>Tek bir {@code Optional} dönmüyor: numara artık tek başına bir satırı
     * belirtmiyor. "Bugünkü TK7101" istiyorsan {@link #find(String, LocalDateTime)}
     * kullan.
     */
    public List<Flight> findByFlightNo(String flightNo) throws SQLException {
        try (Connection c = db.open()) {
            Lookups lookups = lookups(c);
            List<Flight> found = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + COLUMNS + " FROM flights WHERE flight_no = ? ORDER BY sched_dep")) {
                ps.setString(1, flightNo);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) found.add(map(rs, lookups));
                }
            }
            return found;
        }
    }

    /** Anahtarın tamamıyla tek uçuş. */
    public Optional<Flight> find(String flightNo, LocalDateTime schedDep) throws SQLException {
        try (Connection c = db.open()) {
            Lookups lookups = lookups(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + COLUMNS + " FROM flights WHERE flight_no = ? AND sched_dep = ?")) {
                ps.setString(1, flightNo);
                ps.setString(2, SqlTime.toDb(schedDep));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs, lookups)) : Optional.empty();
                }
            }
        }
    }

    public int count() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM flights");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Verinin okunduğu/yazıldığı veritabanı dosyası. Arayüzde ve API yanıtında
     * "bu sayılar nereden geliyor?" sorusunu cevaplamak için gösterilir.
     */
    public String source() {
        return db.describe();
    }

    // ── içeriden ────────────────────────────────────────────────────────────

    /** Verilen uçuşları tek toplu işlemde ekler/günceller. */
    private void upsertAll(Connection c, Collection<Flight> flights) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            for (Flight f : flights) {
                bind(ps, f);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Tabloda olup verilen listede olmayan uçuşları siler (kullanıcının sildikleri).
     *
     * <p>Karşılaştırma ANAHTARIN TAMAMIYLA yapılır: yalnızca uçuş numarasına baksaydı,
     * kullanıcı 15 Mart'taki TK7101'i silip 14 Mart'takini bıraktığında numara hâlâ
     * listede göründüğü için silinen satır tabloda kalırdı.
     */
    private static void deleteMissing(Connection c, Collection<Flight> keep) throws SQLException {
        Set<String> keepKeys = new LinkedHashSet<>();
        for (Flight f : keep) keepKeys.add(key(f.flightNo(), SqlTime.toDb(f.schedDep())));

        List<String[]> doomed = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT flight_no, sched_dep FROM flights");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String no = rs.getString(1);
                String dep = rs.getString(2);
                if (!keepKeys.contains(key(no, dep))) doomed.add(new String[] { no, dep });
            }
        }
        if (doomed.isEmpty()) return;

        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM flights WHERE flight_no = ? AND sched_dep = ?")) {
            for (String[] row : doomed) {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Bileşik anahtarın karşılaştırılabilir tek metin hâli. */
    private static String key(String flightNo, String schedDep) {
        return flightNo + "\u0000" + schedDep;
    }

    /** Uçuşların işaret ettiği havalimanları (tekilleştirilmiş). */
    private static Collection<Airport> referencedAirports(Collection<Flight> flights) {
        Map<String, Airport> byCode = new LinkedHashMap<>();
        for (Flight f : flights) {
            byCode.put(f.from().code(), f.from());
            byCode.put(f.to().code(), f.to());
        }
        return byCode.values();
    }

    /**
     * Uçuşların işaret ettiği uçaklar (tekilleştirilmiş).
     *
     * <p>Aynı kuyruk numarası tarifede farklı tiplerle geçiyorsa SON görülen
     * kazanır — tabloda kuyruk başına tek satır var. Bu bir veri hatasıdır ve
     * kaynağında (CSV) düzeltilmelidir; DAO'nun yapabileceği tek şey yazmayı
     * reddetmek olurdu ve o da kullanıcıyı tüm tarifeyi kaydedemez hâle
     * getirirdi.
     */
    private static void bind(PreparedStatement ps, Flight f) throws SQLException {
        ps.setString(1, f.flightNo());
        ps.setString(2, f.airlineCode());
        // Uçak TİPİ burada yok: satırda yalnızca kuyruk numarası duruyor, tip
        // aircraft tablosundan geliyor (bkz. writeReferences → AircraftDao#syncAll).
        ps.setString(3, f.tailNumber());
        ps.setString(4, f.from().code());
        ps.setString(5, f.to().code());
        ps.setString(6, SqlTime.toDb(f.schedDep()));
        ps.setString(7, SqlTime.toDb(f.schedArr()));
        setNullableTime(ps, 8, f.actualDep());
        setNullableTime(ps, 9, f.actualArr());
        // Koltuk ve kargo KAPASİTESİ burada yok: uçağın tarihli hâlinden
        // geliyorlar, aynı tablodan. Kalanlar o uçuşta gerçekleşeni ölçüyor.
        ps.setDouble(10, f.econPax());
        ps.setDouble(11, f.busPax());
        ps.setDouble(12, f.cargoKg());
        // Gelirler ve maliyetler de burada YOK: ayrı tablolara writeAmounts
        // ile yazılıyorlar.
        ps.setDouble(13, f.fuelKg());
        ps.setDouble(14, f.distanceKm());
    }

    /** Gerçekleşen zamanlar boş olabilir; boş hücre {@code NULL} olarak yazılır. */
    private static void setNullableTime(PreparedStatement ps, int index, LocalDateTime t)
            throws SQLException {
        if (t == null) ps.setNull(index, java.sql.Types.VARCHAR);
        else ps.setString(index, SqlTime.toDb(t));
    }

    /**
     * Uçuş satırını {@link Flight}'a çevirmek için gereken YAN TABLOLAR, tarife
     * başına bir kez okunmuş hâlde.
     *
     * <p>Ayrı parametreler yerine tek bir taşıyıcı: uçuş satırı birden çok
     * tabloya yayıldığı için okuma metotlarının her biri aynı grubu kurup
     * {@code map}'e geçiriyordu, ve yeni bir tablo her seferinde üç imzayı
     * birden değiştiriyordu.
     */
    private record Lookups(Map<String, Airport> airports,
                           Map<String, NavigableMap<LocalDate, AircraftDao.State>> aircraft,
                           Map<Long, Map<String, Double>> costs,
                           Map<Long, Map<String, Double>> revenues) { }

    private Lookups lookups(Connection c) throws SQLException {
        return new Lookups(airports.findAll(c), aircraft.findAll(c),
                costs.bucketsByFlight(c), revenues.bucketsByFlight(c));
    }

    /**
     * Satırı {@link Flight}'a çevirir. Yan tablolarda karşılığı bulunamayan
     * değerler için yer tutucu üretilir (yabancı anahtarlar sayesinde normalde
     * olmaz) — tek bozuk satır tüm tarifenin yüklenmesini engellemesin.
     */
    private static Flight map(ResultSet rs, Lookups lookups) throws SQLException {
        long id = rs.getLong("id");
        // Maliyeti/geliri olmayan uçuş için satır yoktur; kovası boş kabul edilir.
        Map<String, Double> costs = lookups.costs().getOrDefault(id, Map.of());
        Map<String, Double> revenues = lookups.revenues().getOrDefault(id, Map.of());

        String tail = rs.getString("tail_number");
        double econPax = rs.getDouble("econ_pax");
        double busPax = rs.getDouble("bus_pax");
        double cargoKg = rs.getDouble("cargo_kg");
        AircraftDao.State aircraft =
                aircraftOn(tail, time(rs.getString("sched_dep")).toLocalDate(), lookups,
                        econPax, busPax, cargoKg);
        AircraftDao.Seating capacity = aircraft.seating();

        return Flight.builder()
                .flightNo(rs.getString("flight_no"))
                .airlineCode(rs.getString("airline_code"))
                .tailNumber(tail)
                .aircraftType(aircraft.aircraftType())
                .from(airport(rs.getString("from_code"), lookups.airports()))
                .to(airport(rs.getString("to_code"), lookups.airports()))
                .schedDep(time(rs.getString("sched_dep")))
                .schedArr(time(rs.getString("sched_arr")))
                .actualDep(time(rs.getString("actual_dep")))
                .actualArr(time(rs.getString("actual_arr")))
                .econSeats(capacity.econSeats())
                .busSeats(capacity.busSeats())
                .econPax(econPax)
                .busPax(busPax)
                .cargoKg(cargoKg)
                .cargoCapacityKg(capacity.cargoCapacityKg())
                .paxRevenueUsd(revenues.getOrDefault("pax", 0.0))
                .ancillaryRevenueUsd(revenues.getOrDefault("ancillary", 0.0))
                .cargoRevenueUsd(revenues.getOrDefault("cargo", 0.0))
                .crewCostUsd(costs.getOrDefault("crew", 0.0))
                .ownershipCostUsd(costs.getOrDefault("ownership", 0.0))
                .maintenanceCostUsd(costs.getOrDefault("maintenance", 0.0))
                .overheadCostUsd(costs.getOrDefault("overhead", 0.0))
                .navCostUsd(costs.getOrDefault("nav", 0.0))
                .airportCostUsd(costs.getOrDefault("airport", 0.0))
                .fuelKg(rs.getDouble("fuel_kg"))
                .distanceKm(rs.getDouble("distance_km"))
                .build();
    }

    /**
     * Uçağın O TARİHTEKİ hâli: geçmişteki en yakın önceki satır
     * ({@code floorEntry}). Haziran'da yapılan bir kabin yenilemesi Mart'taki
     * uçuşun koltuk sayısını değiştirmesin diye tarih üzerinden aranıyor.
     *
     * <p>Hiç satır yoksa {@link #airport} ile aynı gerekçeyle yer tutucuya
     * düşülür, ama burada kapasiteye 0 yazmak olmaz: {@link Flight} sıfır
     * koltuklu ya da koltuğundan fazla yolcusu olan bir uçuşu reddeder, yani
     * tek eksik satır tüm tarifeyi yüklenemez hâle getirirdi. Uçuşun KENDİ
     * yolcu ve yük sayıları kapasite kabul edilir — %100 doluluk görünür, ki bu
     * da eksik verinin fark edilir bir işaretidir.
     */
    private static AircraftDao.State aircraftOn(
            String tail, LocalDate date, Lookups lookups,
            double econPax, double busPax, double cargoKg) {

        NavigableMap<LocalDate, AircraftDao.State> history = lookups.aircraft().get(tail);
        if (history != null) {
            Map.Entry<LocalDate, AircraftDao.State> e = history.floorEntry(date);
            if (e != null) return e.getValue();
        }
        int econ = (int) Math.ceil(econPax);
        int bus = (int) Math.ceil(busPax);
        if (econ + bus <= 0) econ = 1;              // Flight en az bir koltuk ister
        return new AircraftDao.State("Unknown", new AircraftDao.Seating(econ, bus, cargoKg));
    }

    private static Airport airport(String code, Map<String, Airport> byCode) {
        Airport a = byCode.get(code);
        return a != null ? a : new Airport(code, code, "Unknown", "Unknown", 0, 0);
    }

    /**
     * Kuyruk numarasından uçak tipi. {@link #airport} ile aynı gerekçeyle
     * yer tutucuya düşer: yabancı anahtar sayesinde normalde olmaz, ama tek bir
     * eşleşmeyen satır tüm tarifenin yüklenmesini engellememeli.
     */
    private static LocalDateTime time(String value) {
        return SqlTime.fromDb(value);
    }
}