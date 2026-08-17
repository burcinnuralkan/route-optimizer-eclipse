package com.hitit.aviation.core.data;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tekrar eden SQL parçalarını üreten yardımcı.
 *
 * <p>Bugün tek bir şey üretiyor: <b>upsert</b> — "varsa güncelle, yoksa ekle".
 * Çağıranın "bu satır zaten var mı?" diye önce sorması ve iki ayrı yol yazması
 * gerekmez:
 *
 * <pre>
 * INSERT INTO t (...) VALUES (...)
 * ON CONFLICT (anahtar) DO UPDATE SET sütun = EXCLUDED.sütun, ...
 * </pre>
 *
 * <h2>Neden {@code INSERT OR REPLACE} kullanılmıyor</h2>
 * Kısa yazımı cazip ama yaptığı iş "güncelle" değil: çakışan satırı SİLER ve
 * yenisini ekler. Maliyetler {@code flights} üzerinde sütunken bunun görünür bir
 * sonucu yoktu. Maliyetler kendi tablosuna taşındıktan sonra ise iki şey birden
 * bozuluyor:
 *
 * <ul>
 *   <li>{@code flights.id} otomatik artan; silinip yeniden eklenen uçuş YENİ bir
 *       kimlik alır ve eski kimliğe bağlı her şey artık başka bir uçuşu
 *       gösterir.</li>
 *   <li>{@code costs.flight_id} üzerinde {@code ON DELETE CASCADE} var ve
 *       {@code PRAGMA foreign_keys} açık; silme gerçek bir silme olduğu için
 *       uçuşun TÜM maliyet satırları onunla gider.</li>
 * </ul>
 *
 * <p>Sonuç sessizdi ve tam da {@link CostDao#replaceChanged} ile korunmak
 * istenen şeyi bozuyordu: 100 EURO olarak girilmiş bir maliyet, masaüstünden bir
 * kez "Kaydet"e basmakla "USD 108" satırına dönüşüyordu — çünkü karşılaştırılacak
 * eski satır o an çoktan silinmiş oluyordu.
 *
 * <p>{@code DO UPDATE} böyle bir silme yapmaz: satır yerinde güncellenir, kimliği
 * ve ona bağlı maliyetler korunur. SQLite bu yazımı 3.24'ten (2018) beri
 * destekliyor.
 *
 * <h2>Liste neden elle yazılmıyor</h2>
 * Sütun listesi, yer tutucular ve {@code DO UPDATE SET} atamaları tek bir
 * metinden TÜRETİLİYOR. 21 sütunlu {@code flights} tablosu için bunları elle
 * yazmak kırılgan olurdu: şemaya bir sütun eklendiğinde yer tutucu eklemeyi
 * unutmak çalışma anında sayı uyuşmazlığı olarak patlar, {@code SET} listesine
 * eklemeyi unutmak ise hiç patlamaz — o sütun sessizce güncellenmez.
 */
final class Sql {

    private Sql() { }

    /**
     * "Varsa güncelle, yoksa ekle" ifadesi.
     *
     * <p>{@code keys} sütunlarının üzerinde bir tekillik kısıtı (PRIMARY KEY ya
     * da UNIQUE) OLMAK ZORUNDA; çakışma hedefi o kısıttan bulunur.
     *
     * @param table   tablo adı
     * @param columns virgülle ayrılmış sütun listesi (satır sonu içerebilir)
     * @param keys    çakışmanın belirlendiği anahtar sütun(lar)
     */
    static String upsert(String table, String columns, String... keys) {
        List<String> cols = split(columns);
        String columnList = String.join(", ", cols);
        String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(", "));

        // Anahtar sütunları güncellenmez: zaten eşleşmelerinin sebebi onlar.
        Set<String> keySet = Set.of(keys);
        String assignments = cols.stream()
                .filter(c -> !keySet.contains(c))
                .map(c -> c + " = EXCLUDED." + c)
                .collect(Collectors.joining(", "));

        return "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")"
                + " ON CONFLICT (" + String.join(", ", keys) + ") DO UPDATE SET " + assignments;
    }

    /** Sütun listesini ayırır; DAO'lardaki liste çok satırlı metin bloğudur. */
    private static List<String> split(String columns) {
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
 