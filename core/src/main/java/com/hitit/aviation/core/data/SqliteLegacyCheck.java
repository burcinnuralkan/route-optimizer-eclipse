package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Flyway'den ÖNCE yazılmış SQLite dosyalarını denetler.
 *
 * <p>Şema artık Flyway'in işi, ama var olan {@code .db} dosyalarının Flyway'in
 * geçmiş tablosu yok. Onlar için {@code baselineOnMigrate} devrede: Flyway
 * "bu dosya zaten V1'de" deyip hiçbir şey çalıştırmadan devam eder.
 *
 * <p>Sorun şu: baseline dosyanın İÇİNE bakmaz. Eski şemayla (uçuş anahtarı
 * yalnızca {@code flight_no}) yazılmış bir dosya da sessizce "V1" damgası alır
 * ve uygulama yanlış anahtarlı bir tabloyla çalışmaya devam eder — haftalık
 * tarife aktarıldığında günler birbirinin üstüne yazılır, hiçbir hata çıkmaz.
 *
 * <p>Bu sınıf tam olarak o sessizliği bozar: baseline'dan önce dosyanın
 * gerçekten güncel şemada olduğunu doğrular, değilse ne yapılacağını söyleyen
 * bir hata atar. Tüm kullanıcı dosyaları yenilendiğinde bu sınıf silinebilir.
 */
final class SqliteLegacyCheck {

    /** Flyway'e geçmeden önceki son şema sürümü ({@code PRAGMA user_version}). */
    private static final int LAST_PRE_FLYWAY_VERSION = 2;

    private SqliteLegacyCheck() { }

    static void verify(Connection c) throws SQLException {
        // Yeni (boş) dosya: Flyway V1'i baştan çalıştıracak, denetlenecek bir şey yok.
        if (!tableExists(c, "flights")) return;

        // Zaten Flyway yönetiminde: sürümü Flyway'in kendi geçmişi biliyor.
        if (tableExists(c, "flyway_schema_history")) return;

        if (!hasColumn(c, "flights", "from_code")) {
            throw new IllegalStateException(
                    "Tanınmayan uçuş tablosu (beklenen 'from_code' sütunu yok). Bu dosya "
                    + "projenin başka bir sürümüyle oluşturulmuş. Verini CSV'ye aktarıp "
                    + "dosyayı silin, sonra CsvImportTool ile yeniden kurun.");
        }

        int version = userVersion(c);
        if (version != LAST_PRE_FLYWAY_VERSION) {
            throw new IllegalStateException(
                    "Veritabanı dosyası eski şemada (v" + version + ", beklenen v"
                    + LAST_PRE_FLYWAY_VERSION + "). Uçuş anahtarı (flight_no, sched_dep)"
                    + " olmadan aynı uçuş numarasının farklı günleri birbirinin üstüne"
                    + " yazılır. Dosyayı silip CsvImportTool ile yeniden kurun.");
        }
    }

    private static int userVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
 