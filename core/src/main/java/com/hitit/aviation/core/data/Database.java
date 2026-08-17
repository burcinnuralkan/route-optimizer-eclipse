package com.hitit.aviation.core.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;

/**
 * Uygulamanın veritabanı bağlantısı. Veritabanı SQLite: tek dosya, kurulum yok.
 * Masaüstü ve API aynı dosyayı açar.
 *
 * <p>SQL yalnızca DAO'larda (bkz. {@link FlightDao}, {@link AirportDao}); bu
 * sınıfın işi bağlantıyı açmak, işlemi yönetmek ve şemayı güncel tutmaktır.
 *
 * <h2>Dosyanın yeri</h2>
 * Yol çözümü {@link DatabaseLocation}'a bırakılmıştır — sistem özelliği
 * {@code routeoptimizer.db.path} verilmişse o, yoksa proje kökündeki
 * {@code database/route-optimizer.db}. Burada ikinci bir çözüm yazılsaydı iki
 * ayrı "veritabanı nerede?" cevabı olur ve biri diğerinden habersiz
 * değişebilirdi.
 *
 * <h2>Bağlantı neden havuzlanmıyor</h2>
 * SQLite'ta bağlanmak yalnızca bir dosya açmaktır, pahalı değildir. Buna
 * karşılık uzun ömürlü bir bağlantı dosya kilidini elde tutar ve aynı dosyayı
 * açan diğer süreci (masaüstü ↔ API) bekletir. Burada doğru olan kısa ömürlü
 * bağlantıdır: {@link #open()} her çağrıda yenisini açar, çağıran kapatır.
 *
 * <h2>Şema</h2>
 * Şemayı {@link Flyway} kurar ve sürümler; SQL dosyaları
 * {@code src/main/resources/db/migration/sqlite} altında. Eskiden şema Java
 * içinde {@code CREATE TABLE IF NOT EXISTS} ile kuruluyordu; o yaklaşımın
 * sessiz hatası şuydu: ifade var olan bir tabloda hiçbir şey YAPMAZ, yani şema
 * değiştiğinde eski dosya yeni kodla açılır ve yanlış davranırdı. Flyway hangi
 * göçün çalıştığını dosyanın kendi içinde ({@code flyway_schema_history})
 * tutar. Geçmiş dosyalarla uyuşmazsa açılış durur; çıkış yolu
 * {@link SchemaRepairTool}.
 *
 * <h2>Bu sınıf neden statik değil?</h2>
 * Hedef nesnenin ALANIDIR; yol yalnızca {@link #fromEnvironment()} içinde,
 * yalnızca uygulamanın giriş noktasında çözülür. Metotlar statik olsaydı ve
 * yol her çağrıda yeniden okunsaydı sınıf gizli global duruma bağlanır,
 * testler birbirinin veritabanını kirletirdi. Test
 * {@code new Database(tmp.resolve("t.db"))} diyerek kendi dosyasıyla çalışır.
 */
public final class Database {

    /** Başka bir süreç yazarken bu kadar beklenir, hemen "database is locked" atılmaz. */
    private static final int BUSY_TIMEOUT_MS = 5_000;

    /**
     * Göç dosyalarının yeri. {@link SchemaRepairTool} de buradan okur — onarım
     * ile doğrulama farklı klasörlere bakarsa onarım "düzelttim" der, açılış
     * aynı hatayı vermeye devam ederdi.
     */
    static final String MIGRATIONS_LOCATION = "classpath:db/migration/sqlite";

    private final String url;
    private final String description;

    /** Verilen dosyayı açar, yoksa oluşturur ve şemasını göç ettirir. */
    public Database(Path path) {
        Path file = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.url = jdbcUrl(file);
        this.description = file.toString();
        createParentDirectory(file);
        migrate();
    }

    /**
     * Ortak veritabanı — yolu {@link DatabaseLocation} çözer.
     * Yalnızca uygulama girişinde çağrılmalı.
     */
    public static Database fromEnvironment() {
        return new Database(DatabaseLocation.resolve());
    }

    /**
     * Verinin okunduğu/yazıldığı dosyanın yolu. Arayüzde ve API cevabında
     * "bu sayılar nereden geliyor?" sorusunu cevaplar.
     */
    public String describe() {
        return description;
    }

    /** Yeni bir bağlantı açar. Çağıran KAPATMAKLA yükümlüdür (try-with-resources). */
    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            // WAL: okuyanlar yazanı, yazan okuyanları bloklamaz. Masaüstü kaydederken
            // API'nin aynı dosyadan okuması bu sayede beklemeye takılmaz.
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /**
     * Verilen işi TEK bir işlem içinde çalıştırır: iş hatasız biterse commit,
     * istisna atarsa rollback. Yarım kalmış kaydetme diye bir durum olmaz.
     */
    public <T> T inTransaction(Work<T> work) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** {@link #inTransaction} içinde çalıştırılacak iş. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @Override
    public String toString() {
        return description;
    }

    // ── içeriden ────────────────────────────────────────────────────────────

    /**
     * Şemayı göç ettirir.
     *
     * <p>{@code baselineOnMigrate}: Flyway'den önce oluşmuş, tabloları olan ama
     * geçmiş tablosu olmayan dosyalar için — senin mevcut
     * {@code route-optimizer.db} dosyan tam olarak böyle. Onlarda V1'i
     * çalıştırmak "tablo zaten var" hatası verirdi; baseline "bu dosya V1'de
     * kabul edilsin" der, V2/V3/V4 normal işler ve veri korunur. Dosyanın
     * gerçekten güncel şemada olduğu {@link SqliteLegacyCheck} ile ayrıca
     * doğrulanır — baseline dosyanın içine bakmaz.
     */
    private void migrate() {
        try (Connection c = open()) {
            SqliteLegacyCheck.verify(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Veritabanı açılamadı: " + description, e);
        }

        try {
            flyway(url).migrate();
        } catch (FlywayValidateException e) {
            // Genel mesaj burada işe yaramaz: hatayı gören kişinin bilmesi
            // gereken tek şey bunun nasıl çözüleceğidir, yığın izinin dibindeki
            // Flyway cümlesi ise onu "revert or repair" diye ortada bırakıyor.
            throw new IllegalStateException(
                    "Göç geçmişi migration dosyalarıyla uyuşmuyor: " + description
                            + " — teşhis ve onarım için " + SchemaRepairTool.class.getName()
                            + " çalıştırın. (" + e.getMessage().strip() + ")",
                    e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Veritabanı şeması hazırlanamadı: " + description, e);
        }
    }

    /** JDBC URL'i tek yerden üretir; {@link SchemaRepairTool} de bunu kullanır. */
    static String jdbcUrl(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath().normalize();
    }

    /**
     * Uygulamanın ve onarım aracının ORTAK Flyway yapılandırması.
     *
     * <p>Flyway kendi kısa ömürlü bağlantısını açar — göç yalnızca DDL
     * çalıştırır, {@link #open()}'daki PRAGMA'lara ihtiyacı yoktur.
     */
    static Flyway flyway(String url) {
        return Flyway.configure()
                .locations(MIGRATIONS_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .dataSource(url, null, null)
                .load();
    }

    private static void createParentDirectory(Path file) {
        Path parent = file.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Veritabanı dizini oluşturulamadı: " + parent, e);
        }
    }
}
 