package com.hitit.aviation.core.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.hitit.aviation.core.emission.EmissionCalculator;
import com.hitit.aviation.core.model.Airport;
import com.hitit.aviation.core.model.Flight;

public class ScheduleLoader {
    private final Map<String, Airport> airports = new HashMap<>();
    private final List<Flight> flights = new ArrayList<>();

    /** saveCsv'nin yazdığı kanonik sütun düzeni. Okurken sıra önemli değildir. */
    public static final String CSV_HEADER =
            "flightNo,airlineCode,tailNumber,aircraftType,from,to,schedDep,schedArr,"
            + "actualDep,actualArr,econSeats,busSeats,econPax,busPax,cargoKg,cargoCapacityKg,"
            + "paxRevenueUsd,ancillaryRevenueUsd,cargoRevenueUsd,crewCostUsd,ownershipCostUsd,"
            + "maintenanceCostUsd,overheadCostUsd,navCostUsd,airportCostUsd,fuelKg,distanceKm";

    /** Tarifede bulunması ZORUNLU sütunlar. */
    private static final List<String> FLIGHT_REQUIRED = List.of(
            "flightNo", "airlineCode", "tailNumber", "aircraftType", "from", "to",
            "schedDep", "schedArr", "econSeats", "busSeats", "econPax", "busPax",
            "cargoKg", "cargoCapacityKg", "paxRevenueUsd", "ancillaryRevenueUsd",
            "cargoRevenueUsd", "crewCostUsd", "ownershipCostUsd", "maintenanceCostUsd",
            "overheadCostUsd", "navCostUsd", "airportCostUsd");

    /** Havalimanı dosyasında bulunması zorunlu sütunlar. */
    private static final List<String> AIRPORT_REQUIRED =
            List.of("code", "name", "city", "country", "lat", "lon");

    /**
     * Yüklenen havalimanları — <b>salt okunur görünüm</b>.
     *
     * <p>Eskiden iç {@code Map}'in kendisi dönüyordu; çağıran
     * {@code loader.airports().put(...)} yazarak deponun içeriğini yükleyicinin
     * haberi olmadan değiştirebiliyordu. Değiştirmek isteyen artık
     * {@link #putAirports} çağırmak zorunda: değişikliğin nereden geldiği
     * çağrı yerinden okunuyor.
     */
    public Map<String, Airport> airports() { return Collections.unmodifiableMap(airports); }

    /** Yüklenen uçuşlar — salt okunur görünüm; değiştirmek için {@link #setFlights}. */
    public List<Flight> flights() { return Collections.unmodifiableList(flights); }

    /**
     * Tarifeyi tümüyle değiştirir (verilen liste kopyalanır; sonradan onu
     * değiştirmek depoyu etkilemez).
     *
     * <p>Tek tek {@code add}/{@code remove} yerine "yeni liste ver" biçimi
     * seçildi: çağıran (masaüstü {@code ScheduleStore}) değişiklikten önce zaten
     * anlık kopya alıp geri alma yığınına koyuyor, yani listeyi bütün olarak
     * kurmak onun doğal çalışma biçimi. Böylece burada da tek bir değiştirici
     * yetiyor.
     */
    public void setFlights(List<Flight> newFlights) {
        List<Flight> copy = new ArrayList<>(newFlights);
        flights.clear();
        flights.addAll(copy);
    }

    /**
     * Verilen havalimanlarını mevcutların ÜSTÜNE yazar (aynı koda sahip olanı
     * değiştirir, yenisini ekler; hiçbirini silmez).
     *
     * <p>Tek kullanım yeri {@code ScheduleFile.mergeRealAirports}: dışarıdan
     * yüklenen CSV'de havalimanları koordinatsızdır, gömülü veriden gerçek
     * koordinatlar bindirilir.
     */
    public void putAirports(Map<String, Airport> more) {
        airports.putAll(more);
    }

    /** Core jar'ındaki örnek veri setini yükler. */
    public static ScheduleLoader loadSampleData() throws IOException {
        ScheduleLoader l = new ScheduleLoader();
        l.loadAirports(resource("/data/airports.csv"));
        l.loadSchedule(resource("/data/flights.csv"));
        return l;
    }

    /**
     * CSV'deki zaman hücresini çözer.
     *
     * <p>Önce ISO ayrıştırıcı denenir: {@code 2026-03-14T06:00} da
     * {@code 2026-03-14T06:00:00} da kabul edilir. İkincisi önemli — veritabanına
     * saniyeli yazıyoruz ({@link SqlTime}), dolayısıyla veritabanından dışa aktarılan
     * bir CSV geri yüklenmek istendiğinde hücreler saniyeli gelir. Sabit desenli
     * ayrıştırıcı bunu reddediyor, ardından tarih-only desen de tutmadığı için
     * içe aktarma tümden çöküyordu.
     *
     * <p>Son çare, elle hazırlanmış dosyalarda görülen {@code M/d/yyyy} biçimi.
     */
    private static LocalDateTime parseDt(String s) {

        if (s == null || s.isBlank()) {
            return null;
        }

        s = s.trim();

        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            // ISO değil; aşağıdaki tarih-only biçimi denenecek
        }

        return LocalDate.parse(
                s,
                DateTimeFormatter.ofPattern("M/d/yyyy"))
                .atStartOfDay();
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = ScheduleLoader.class.getResourceAsStream(path);
        if (in == null) throw new IOException("Kaynak bulunamadı: " + path);
        return in;
    }

    public void loadAirports(InputStream in) throws IOException {
        CsvTable t = CsvTable.read(in, AIRPORT_REQUIRED);
        for (String[] r : t.rows()) {
            Airport a = new Airport(
                    t.str(r, "code"),
                    t.str(r, "name"),
                    t.str(r, "city"),
                    t.str(r, "country"),
                    t.dbl(r, "lat"),
                    t.dbl(r, "lon"));
            airports.put(a.code(), a);
        }
    }

    /** Havalimanları önceden yüklenmiş olmalı; tanımsız kod hata verir. */
    public void loadSchedule(InputStream in) throws IOException {
        CsvTable t = CsvTable.read(in, FLIGHT_REQUIRED);
        for (String[] r : t.rows()) {
            flights.add(parseFlight(t, r, code -> require(airports, code)));
        }
    }

    /**
     * Dışarıdan gelen bir tarifeyi yükler. {@link #loadSchedule} tanımsız havalimanı
     * kodunda hata verir; bu sürüm BİLİNMEYEN kod için koddan ibaret bir yer tutucu
     * üretir (koordinat 0, ad/şehir "Unknown").
     *
     * <p>Önce {@link #airports()} haritasına gerçek havalimanları konursa (ör.
     * veritabanından) uçuşlar o nesnelere bağlanır: yer tutucu yalnızca gerçekten
     * tanımadığımız kodlar için üretilir. Bu önemlidir, çünkü uçuş kalkış/varışı
     * KOD değil NESNE olarak taşır — yer tutucuya bağlanan bir uçuş kaydedilirse
     * havalimanının koordinatı sıfırlanmış olur.
     */
    public void loadScheduleLenient(InputStream in) throws IOException {
        CsvTable t = CsvTable.read(in, FLIGHT_REQUIRED);
        for (String[] r : t.rows()) {
            flights.add(parseFlight(t, r, code ->
                    airports.computeIfAbsent(
                            code,
                            k -> new Airport(k, k, "Unknown", "Unknown", 0, 0))));
        }
    }

    /** Bilinen havalimanı olmadan, dosyadaki kodlardan yer tutucu üreterek yükler. */
    public static ScheduleLoader loadFromCsv(Path path) throws IOException {
        ScheduleLoader loader = new ScheduleLoader();
        try (InputStream in = java.nio.file.Files.newInputStream(path)) {
            loader.loadScheduleLenient(in);
        }
        return loader;
    }

    /** Tek bir CSV satırını Flight'a çevirir (sütunlar isimle okunur). */
    private static Flight parseFlight(CsvTable t, String[] r, Function<String, Airport> airportLookup) {

        Airport from = airportLookup.apply(t.str(r, "from"));
        Airport to = airportLookup.apply(t.str(r, "to"));

        LocalDateTime schedDep = parseDt(t.str(r, "schedDep"));
        LocalDateTime schedArr = endAfter(schedDep, parseDt(t.str(r, "schedArr")));
        LocalDateTime actualDep = parseDt(t.opt(r, "actualDep"));
        LocalDateTime actualArr = endAfter(actualDep, parseDt(t.opt(r, "actualArr")));

        return Flight.builder()
                .flightNo(t.str(r, "flightNo"))
                .airlineCode(t.str(r, "airlineCode"))
                .tailNumber(t.str(r, "tailNumber"))
                .aircraftType(t.str(r, "aircraftType"))
                .from(from)
                .to(to)
                .schedDep(schedDep)
                .schedArr(schedArr)
                .actualDep(actualDep)
                .actualArr(actualArr)
                .econSeats(t.integer(r, "econSeats"))
                .busSeats(t.integer(r, "busSeats"))
                .econPax(t.dbl(r, "econPax"))
                .busPax(t.dbl(r, "busPax"))
                .cargoKg(t.dbl(r, "cargoKg"))
                .cargoCapacityKg(t.dbl(r, "cargoCapacityKg"))
                .paxRevenueUsd(t.dbl(r, "paxRevenueUsd"))
                .ancillaryRevenueUsd(t.dbl(r, "ancillaryRevenueUsd"))
                .cargoRevenueUsd(t.dbl(r, "cargoRevenueUsd"))
                .crewCostUsd(t.dbl(r, "crewCostUsd"))
                .ownershipCostUsd(t.dbl(r, "ownershipCostUsd"))
                .maintenanceCostUsd(t.dbl(r, "maintenanceCostUsd"))
                .overheadCostUsd(t.dbl(r, "overheadCostUsd"))
                .navCostUsd(t.dbl(r, "navCostUsd"))
                .airportCostUsd(t.dbl(r, "airportCostUsd"))
                .fuelKg(t.dblOr(r, "fuelKg", 0))       // yoksa parametrik tahmin edilir
                .distanceKm(distanceKm(t.opt(r, "distanceKm"), from, to))
                .build();
    }

    /**
     * Mesafe sütunu yoksa/boşsa/0 ise havalimanı koordinatlarından büyük daire
     * mesafesi hesaplanıp ICAO rota düzeltmesi eklenir. Koordinat da yoksa 0.
     */
    private static double distanceKm(String cell, Airport from, Airport to) {

        if (cell != null && !cell.isBlank()) {
            double given = Double.parseDouble(cell.trim());
            if (given > 0) return given;
        }

        double gcd = from.distanceTo(to);
        return gcd > 0 ? EmissionCalculator.correctedDistanceKm(gcd) : 0;
    }

    /** Varış kalkıştan sonra değilse ertesi güne taşır (gece yarısını geçen uçuş). */
    private static LocalDateTime endAfter(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return end;
        return end.isAfter(start) ? end : end.plusDays(1);
    }

    private static <T> T require(Map<String, T> map, String what) {
        T value = map.get(what);
        if (value == null) throw new IllegalArgumentException("Tanımsız " + what);
        return value;
    }

    /**
     * Başlık satırıyla eşlenmiş CSV tablosu. Sütunlar İSİMLE okunur; dosyadaki
     * sıra, fazladan sütunlar ve büyük/küçük harf ile alt tire/boşluk farkları
     * (fuel_kg = fuelKg) önemsizdir.
     */
    static final class CsvTable {

        private final Map<String, Integer> index;   // normalize ad -> sütun no
        private final List<String[]> rows;

        private CsvTable(Map<String, Integer> index, List<String[]> rows) {
            this.index = index;
            this.rows = rows;
        }

        List<String[]> rows() { return rows; }

        static CsvTable read(InputStream in, List<String> required) throws IOException {

            Map<String, Integer> index = new LinkedHashMap<>();
            List<String[]> rows = new ArrayList<>();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                String line;
                boolean headerSeen = false;

                while ((line = r.readLine()) != null) {

                    if (!headerSeen && line.startsWith("﻿")) {   // BOM
                        line = line.substring(1);
                    }

                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] cells = splitCsvLine(line);

                    if (!headerSeen) {
                        for (int i = 0; i < cells.length; i++) {
                            index.putIfAbsent(key(cells[i]), i);
                        }
                        headerSeen = true;
                        continue;
                    }

                    rows.add(cells);
                }

                if (!headerSeen) {
                    throw new IOException("CSV boş: başlık satırı bulunamadı");
                }
            }

            CsvTable table = new CsvTable(index, rows);
            table.requireColumns(required);
            return table;
        }

        /** Eksik zorunlu sütunları tek seferde, isimleriyle bildirir. */
        private void requireColumns(List<String> required) throws IOException {
            Set<String> missing = new LinkedHashSet<>();
            for (String name : required) {
                if (!index.containsKey(key(name))) missing.add(name);
            }
            if (!missing.isEmpty()) {
                throw new IOException("CSV'de eksik sütun(lar): " + String.join(", ", missing));
            }
        }

        /** Başlık adlarını normalize eder: "Fuel_Kg" ve "fuelKg" aynı sütundur. */
        private static String key(String name) {
            return name == null ? "" : name.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
        }

        /** Zorunlu sütun; satırda hücre yoksa (kısa satır) hata verir. */
        String str(String[] row, String column) {
            Integer i = index.get(key(column));
            if (i == null) {
                throw new IllegalArgumentException("Bilinmeyen sütun: " + column);
            }
            if (i >= row.length) {
                throw new IllegalArgumentException(
                        "Satırda '" + column + "' sütunu eksik: " + String.join(",", row));
            }
            return row[i];
        }

        /** İsteğe bağlı sütun; yoksa "" döner. */
        String opt(String[] row, String column) {
            Integer i = index.get(key(column));
            return (i == null || i >= row.length) ? "" : row[i];
        }

        double dbl(String[] row, String column) {
            return parseDouble(str(row, column), column);
        }

        /** İsteğe bağlı sayısal sütun; yoksa/boşsa varsayılan döner. */
        double dblOr(String[] row, String column, double fallback) {
            String cell = opt(row, column);
            return cell.isBlank() ? fallback : parseDouble(cell, column);
        }

        int integer(String[] row, String column) {
            String cell = str(row, column).trim();
            try {
                return Integer.parseInt(cell);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "'" + column + "' sayı olmalı, bulunan: '" + cell + "'");
            }
        }

        private static double parseDouble(String cell, String column) {
            try {
                return Double.parseDouble(cell.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "'" + column + "' sayı olmalı, bulunan: '" + cell + "'");
            }
        }
    }

    /**
     * RFC 4180 usulü satır ayrıştırma: tırnak içindeki virgüller alanı bölmez,
     * "" kaçışı tek tırnağa çevrilir, sondaki boş alanlar korunur (String.split
     * bunları düşürüyordu).
     */
    static String[] splitCsvLine(String line) {

        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;                 // kaçışlı tırnak
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString().trim());

        return cells.toArray(new String[0]);
    }
}
 