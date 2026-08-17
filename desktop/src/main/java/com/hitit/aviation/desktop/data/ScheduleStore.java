package com.hitit.aviation.desktop.data;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hitit.aviation.core.data.AirportDao;
import com.hitit.aviation.core.data.FlightDao;
import com.hitit.aviation.core.data.ScheduleLoader;
import com.hitit.aviation.core.model.Airport;
import com.hitit.aviation.core.model.Flight;

/**
 * Masaüstünün tarife verisi. UI'dan bağımsız: yalnızca veri + kalıcılık.
 * Veri artık CSV'de değil, ortak SQLite veritabanında; okuma/yazma DAO'lar
 * üzerinden yapılır — bu sınıfta tek satır SQL yoktur.
 */
public class ScheduleStore {

    private static final int UNDO_CAP = 50;

    /**
     * AirportDao da gerekli: kalkış/varış kutuları hiç uçuşu olmayan havalimanlarını
     * da gösterir, bu liste uçuşlardan türetilemez.
     */
    private final FlightDao flightDao;
    private final AirportDao airportDao;

    /** Bellekteki tarife — ekranda görünen, henüz kaydedilmemiş olabilen hâl. */
    private final List<Flight> flights = new ArrayList<>();
    private final Map<String, Airport> airports = new LinkedHashMap<>();

    private final Deque<List<Flight>> undoStack = new ArrayDeque<>();

    //"Kaydedilmemiş değişiklik var mı?" 
    private long editSeq = 0;
    private long savedSeq = 0;

    /** Kaydedilen noktaya artık geri dönülemez (undo dalı koptu / yeni dosya yüklendi). */
    private static final long UNREACHABLE = -1;

    public ScheduleStore(FlightDao flightDao, AirportDao airportDao) throws Exception {
        this.flightDao = flightDao;
        this.airportDao = airportDao;
        reload();
    }

    // ── Okuma ──

    /** Tarifenin DEĞİŞTİRİLEMEZ kopyası: değişiklikler add/remove/replace'ten gitmeli. */
    public List<Flight> flights() { return List.copyOf(flights); }

    public Map<String, Airport> airports() { return Map.copyOf(airports); }
    public Airport airport(String code) { return airports.get(code); }
    public int size() { return flights.size(); }
    public boolean canUndo() { return !undoStack.isEmpty(); }

    /** Verinin kaydedildiği yer (arayüzde gösterilir). */
    public String source() { return flightDao.source(); }

    public boolean isDirty() { return editSeq != savedSeq; }

    // ── Değişiklik (bellekte; veritabanına yazmaz — kullanıcı "Kaydet" demeli) ──

    public void add(Flight f) {
        snapshot();
        flights.add(f);
    }

    public void remove(Flight f) {
        snapshot();
        flights.remove(f);
    }

    public void replace(Flight oldFlight, Flight newFlight) {
        snapshot();
        int i = flights.indexOf(oldFlight);
        if (i >= 0) flights.set(i, newFlight);
        else flights.add(newFlight);
    }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        List<Flight> prev = undoStack.pop();
        flights.clear();
        flights.addAll(prev);
        editSeq--;
        return true;
    }

    /**
     * Bellekteki tarifeyi ortak veritabanına yazar. saveAll TAM LİSTE anlamına gelir:
     * kullanıcının sildiği uçuşlar veritabanından da silinir. Tek işlemdir.
     */
    public void save() throws Exception {
        flightDao.saveAll(flights);
        savedSeq = editSeq;
    }

    private void snapshot() {
        undoStack.push(new ArrayList<>(flights));
        while (undoStack.size() > UNDO_CAP) undoStack.removeLast();

        // Kaydedilen noktanın GERİSİNDEyken yeni değişiklik yapılırsa undo dalı kopar:
        // o içeriğe geri alarak dönülemez, kaydedilmiş konum geçersizleşir.
        if (editSeq < savedSeq) savedSeq = UNREACHABLE;
        editSeq++;
    }

    /**
     * Dışarıdan CSV yükler (kaydedilmemiş; kalıcı olması için Kaydet). Uçuş sayısını döner.
     *
     * <p>Havalimanları ÖNCE veritabanından okunur; yer tutucu yalnızca tanınmayan
     * kodlar için üretilir. Böylece yüklenen uçuşlar gerçek koordinatlara bağlanır ve
     * "Kaydet" bilinen bir havalimanının koordinatını sıfırla ezmez.
     */
    public int loadFrom(Path path) throws Exception {
        ScheduleLoader loader = new ScheduleLoader();
        loader.airports().putAll(airportDao.findAll());
        try (InputStream in = Files.newInputStream(path)) {
            loader.loadScheduleLenient(in);
        }

        replaceMemory(loader.airports(), loader.flights());
        undoStack.clear();
        editSeq = 0;
        savedSeq = UNREACHABLE;
        return flights.size();
    }

    /** Tarifeyi bir dosyaya dışa aktarır (veritabanını değiştirmez). */
    public void export(Path path) throws Exception {
        writeCsv(path);
    }

    // ── Kalıcılık ──

    /** Kayıtlı tarifeyi veritabanından belleğe alır; kaydedilmemiş değişiklik kalmaz. */
    private void reload() throws Exception {
        replaceMemory(airportDao.findAll(), flightDao.findAll());
        undoStack.clear();
        editSeq = 0;
        savedSeq = 0;
    }

    private void replaceMemory(Map<String, Airport> newAirports, List<Flight> newFlights) {
        airports.clear();
        airports.putAll(newAirports);
        flights.clear();
        flights.addAll(newFlights);
    }

    /** Kalıcılık artık veritabanında; CSV yazma yalnızca DIŞA AKTARMA içindir. */
    private void writeCsv(Path path) throws Exception {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        try (PrintWriter pw = new PrintWriter(path.toFile(), StandardCharsets.UTF_8)) {
            pw.println(ScheduleLoader.CSV_HEADER);
            for (Flight f : flights) {
                pw.println(flightToCsv(f));
            }
        }
    }

    private static String flightToCsv(Flight f) {
        return String.join(",",
                csv(f.flightNo()), csv(f.airlineCode()), csv(f.tailNumber()), csv(f.aircraftType()),
                csv(f.from().code()), csv(f.to().code()),
                dt(f.schedDep()), dt(f.schedArr()), dt(f.actualDep()), dt(f.actualArr()),
                String.valueOf(f.econSeats()), String.valueOf(f.busSeats()),
                num(f.econPax()), num(f.busPax()), num(f.cargoKg()), num(f.cargoCapacityKg()),
                num(f.paxRevenueUsd()), num(f.ancillaryRevenueUsd()), num(f.cargoRevenueUsd()),
                num(f.crewCostUsd()), num(f.ownershipCostUsd()), num(f.maintenanceCostUsd()),
                num(f.overheadCostUsd()), num(f.navCostUsd()), num(f.airportCostUsd()),
                num(f.fuelKg()), num(f.distanceKm()));
    }

    private static String csv(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private static String dt(LocalDateTime t) {
        return t == null ? "" : t.toString();
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%s", v);
    }
}