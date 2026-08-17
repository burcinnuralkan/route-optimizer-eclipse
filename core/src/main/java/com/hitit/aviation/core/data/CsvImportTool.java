package com.hitit.aviation.core.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Jar içindeki TOHUM VERİYİ ({@code /data/airports.csv}, {@code /data/flights.csv})
 * veritabanına aktarır.
 *
 * <p><b>Neden hâlâ CSV var?</b> Veritabanı dosyası ({@code .db}) sürüm kontrolüne
 * girmez — ikili dosyadır, diff'i okunmaz ve her kullanıcının kendi verisi olur.
 * Dolayısıyla depoyu klonlayan birinde veri YOKTUR. Kaynaklardaki CSV'ler bu boşluğu
 * doldurur: projenin başlangıç verisi metin olarak depoda durur, veritabanı ondan
 * kurulur. {@code .db} bozulursa da geri dönüş yolu budur.
 
 */
public final class CsvImportTool {

    private CsvImportTool() { }

    /**
     * Veritabanı boşsa tohum veriyi aktarır; doluysa hiçbir şey yapmaz.
     * Aktarılan uçuş sayısını döner (zaten doluysa 0).
     *
     * <p>Uygulama açılışlarında çağrılır: ilk çalıştırmada boş bir ekran yerine
     * örnek tarife gelir, sonraki çalıştırmalarda kullanıcının verisine dokunulmaz.
     */
    public static int seedIfEmpty(Database db) throws IOException, SQLException {
        AirportDao airportDao = new AirportDao(db);
        FlightDao flightDao = new FlightDao(db, airportDao);
        if (flightDao.count() > 0 || airportDao.count() > 0) return 0;
        return importSampleData(db);
    }

    /**
     * Tohum veriyi aktarır. Var olan satırlar aynı anahtarla ÜZERİNE YAZILIR,
     * tohumda olmayan uçuşlar silinir (bkz. {@link FlightDao#saveAll}) — yani
     * veritabanı tohumun aynısı hâline gelir.
     */
    public static int importSampleData(Database db) throws IOException, SQLException {
        ScheduleLoader sample = ScheduleLoader.loadSampleData();
        return write(db, sample, true);
    }

    public static int importCsv(Database db, Path flightsCsv) throws IOException, SQLException {
        return write(db, readCsv(db, flightsCsv), true);
    }

    /**
     * CSV'deki uçuşları var olanların ÜSTÜNE ekler; hiçbir şey silinmez. Aynı uçuş
     * numarası varsa güncellenir. Elinde parça parça veri varken ("bir dosya daha
     * aktarayım") istediğin budur.
     */
    public static int appendCsv(Database db, Path flightsCsv) throws IOException, SQLException {
        return write(db, readCsv(db, flightsCsv), false);
    }

    
    private static ScheduleLoader readCsv(Database db, Path flightsCsv) throws IOException, SQLException {
        ScheduleLoader loader = new ScheduleLoader();
        loader.putAirports(new AirportDao(db).findAll());
        try (InputStream in = Files.newInputStream(flightsCsv)) {
            loader.loadScheduleLenient(in);
        }
        return loader;
    }

    private static int write(Database db, ScheduleLoader loader, boolean replaceAll) throws SQLException {
        AirportDao airportDao = new AirportDao(db);
        FlightDao flightDao = new FlightDao(db, airportDao);

        // Havalimanları önce ve TAMAMI: uçuşu olmayan havalimanları da tabloda
        // dursun ki arayüzdeki kalkış/varış listesi eksilmesin. (Havalimanı yazma
        // hiçbir zaman silmez, bu yüzden iki kipte de aynı.)
        airportDao.saveAll(loader.airports().values());

        if (replaceAll) flightDao.saveAll(loader.flights());
        else flightDao.addAll(loader.flights());

        return loader.flights().size();
    }

    /**
     * Elle kurulum. Hedef veritabanı {@link Database#fromEnvironment()} ile çözülür.
     * <pre>
     * (argümansız)        jar içindeki tohum veri  -> veritabanı tohumun aynısı olur
     * veri.csv            dosyayı aktarır          -> dosyada olmayan uçuşlar SİLİNİR
     * --append veri.csv   dosyayı ekler            -> hiçbir şey silinmez
     * </pre>
     * Maven'dan:
     * <pre>
     * mvn -q -pl core exec:java \
     *   -Dexec.mainClass=com.hitit.aviation.core.data.CsvImportTool \
     *   -Dexec.args="--append veri.csv"
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        Database db = Database.fromEnvironment();

        boolean append = args.length > 0 && args[0].equals("--append");
        Path csv = null;
        if (append) {
            if (args.length < 2) {
                System.err.println("--append için bir CSV yolu gerekli");
                System.exit(2);
            }
            csv = Path.of(args[1]);
        } else if (args.length > 0) {
            csv = Path.of(args[0]);
        }

        int before = new FlightDao(db, new AirportDao(db)).count();
        int n;
        String source;
        if (csv == null) {
            n = importSampleData(db);
            source = "gömülü tohum veri";
        } else {
            n = append ? appendCsv(db, csv) : importCsv(db, csv);
            source = csv.toAbsolutePath() + (append ? " (ekleme)" : " (değiştirme)");
        }

        int after = new FlightDao(db, new AirportDao(db)).count();
        System.out.printf("%s -> %s%n  dosyadaki uçuş: %d | tablodaki uçuş: %d -> %d | havalimanı: %d%n",
                source, db.describe(), n, before, after, new AirportDao(db).count());
    }
}
 