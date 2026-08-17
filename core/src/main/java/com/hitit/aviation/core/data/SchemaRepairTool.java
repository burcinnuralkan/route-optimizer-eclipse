package com.hitit.aviation.core.data;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateResult;

/**
 * "Migration checksum mismatch" ile açılamayan bir veritabanını ONARIR:
 * {@code flyway_schema_history} içindeki sağlamaları göç dosyalarındakilerle
 * yeniden hizalar.
 *
 * <p><b>Sorun ne?</b> Flyway her göçün METNİNDEN bir sağlama üretip geçmişe
 * yazar. Sonraki açılışta dosyanın sağlaması tutmuyorsa "checksum mismatch"
 * der ve HİÇBİR göçü çalıştırmadan durur. Bu üretimde doğru davranıştır —
 * çalışmış bir göçün metni değiştiyse veritabanının şeması, kodun beklediği
 * şema olmayabilir. Geliştirirken ise çoğu zaman yanlış alarmdır: göç dosyası
 * üzerinde çalışılırken uygulama bir kez açılır, taslak metin veritabanına
 * işlenir, dosya sonra son hâlini alır. Şema aynıdır, sağlama tutmaz.
 *
 * <p><b>Onarım ne YAPMAZ:</b> göçleri yeniden çalıştırmaz, tablolara ve veriye
 * dokunmaz. Yalnızca geçmişteki sağlamayı dosyadakiyle değiştirir. Bu yüzden
 * uygulama bunu kendiliğinden yapmaz, elle çalıştırılır: "bu iki metin aynı
 * şemayı kuruyor" tespiti koda değil insana aittir. Kod bunu kendi başına
 * varsayarsa gerçek bir şema kaymasını da sessizce onaylar.
 *
 * <p><b>Önce raporu oku.</b> Araç, onarımdan önce her göç için Flyway'in
 * DİSKTE hangi dosyayı okuduğunu yazdırır. Uyuşmazlığın sık görülen ve
 * onarımla ÇÖZÜLMEYEN bir sebebi budur: uygulama, kaynak ağacındaki dosyayı
 * değil derlenmiş KOPYASINI okur ({@code target/classes} — Eclipse'te projenin
 * çıktı klasörü). Orada eski bir kopya kalmışsa onarım o eski metnin
 * sağlamasını yazar; temiz bir derlemeden sonra aynı hata geri gelir. Rapordaki
 * yol beklediğin dosya değilse önce projeyi temizleyip yeniden derle
 * ({@code mvn clean}, Eclipse'te Project ▸ Clean), sonra bu aracı çalıştır.
 *
 * <h2>Çalıştırma</h2>
 * Eclipse'te: bu sınıfa sağ tık ▸ Run As ▸ Java Application. Terminalde:
 * <pre>{@code mvn -q -pl core exec:java -Dexec.mainClass=com.hitit.aviation.core.data.SchemaRepairTool}</pre>
 * Veritabanı yolu {@link DatabaseLocation}'dan gelir — başka bir dosyayı
 * onarmak için {@code -Drouteoptimizer.db.path=...}.
 */
public final class SchemaRepairTool {

    private SchemaRepairTool() { }

    public static void main(String[] args) {
        Path file = DatabaseLocation.resolve();
        System.out.println("Veritabanı  : " + file);
        System.out.println("Göç kaynağı : " + Database.MIGRATIONS_LOCATION);

        Flyway flyway = Database.flyway(Database.jdbcUrl(file));

        System.out.println();
        System.out.println("Onarımdan önce:");
        report(flyway);

        flyway.repair();

        System.out.println();
        System.out.println("Onarımdan sonra:");
        report(flyway);

        System.out.println();
        System.out.println("Geçmiş dosyalarla hizalandı. Uygulamayı normal şekilde başlatabilirsin;");
        System.out.println("bekleyen göçler ilk açılışta çalışır.");
    }

    /**
     * Her göç için sürüm, durum ve Flyway'in DİSKTE okuduğu dosya; uyuşmayanlar
     * işaretli. Son sütun raporun asıl sebebi: "dosyayı düzelttim ama hata
     * sürüyor" durumunda okunanın hangi kopya olduğunu gösterir.
     *
     * <p>Uyuşmazlık {@code getState()} ile ayırt edilemiyor — orada göç
     * "SUCCESS" görünür, çünkü gerçekten çalışmıştır. Sağlama kontrolü ayrı bir
     * adımdır, o yüzden {@code validateWithResult} ile soruluyor.
     */
    private static void report(Flyway flyway) {
        ValidateResult validation = flyway.validateWithResult();
        Set<String> invalid = validation.invalidMigrations.stream()
                .map(output -> String.valueOf(output.version))
                .collect(Collectors.toSet());

        System.out.printf(Locale.ROOT, "  %-7s %-10s %-14s %s%n", "Sürüm", "Durum", "Sağlama", "Okunan dosya");
        for (MigrationInfo info : flyway.info().all()) {
            String version = info.getVersion() == null ? "-" : info.getVersion().toString();
            System.out.printf(Locale.ROOT, "  %-7s %-10s %-14s %s%n",
                    version,
                    info.getState(),
                    invalid.contains(version) ? "UYUŞMUYOR" : "tamam",
                    info.getPhysicalLocation() == null ? "(yalnızca geçmişte)" : info.getPhysicalLocation());
        }

        if (!validation.validationSuccessful) {
            System.out.println();
            System.out.println("  " + validation.getAllErrorMessages().strip().replace("\n", "\n  "));
        }
    }
}
 