package com.hitit.aviation.core.model;

import java.util.Objects;

/**
 * Filodaki tek bir fiziksel uçak: kuyruk numarası ve tipi.
 *
 * <p><b>Neden {@link Flight}'ın içinde değil?</b> {@link Airport} uçuşun bir
 * alanıdır çünkü davranışı vardır — {@code lat}/{@code lon} taşır ve
 * {@code distanceTo} ile mesafe hesabına girer. Uçak ise bugün hesaba girmiyor;
 * yalnızca bir etiket (grafiklerde tipe göre gruplama anahtarı). Bu yüzden
 * {@code Flight} kuyruk numarasını ve tipi düz alan olarak taşımaya devam
 * ediyor, tabloyu {@link com.hitit.aviation.core.data.AircraftDao} yönetiyor.
 *
 * <p>Uçağın hesaba giren özellikleri eklenirse (azami kalkış ağırlığı, yaş,
 * motor tipi) o zaman {@code Flight} bir {@code Aircraft} taşımalıdır; değişim
 * bu sınıf hazır olduğu için sınırlı kalır.
 *
 * <p><b>Koltuk sayısı burada YOK</b> ve bu bilinçli: {@code aircraft} tablosu
 * tarihlidir (kuyruk + geçerlilik başlangıcı), bu kayıt ise değil — koltuk
 * sayısı zamanla değişir, kuyruk numarası ve tip değişmez. Tarihe bağlı olan
 * her şey {@link com.hitit.aviation.core.data.AircraftDao.State} içinde
 * duruyor. Ayrıntılı gerekçe
 * {@code db/migration/sqlite/V10__aircraft_merge.sql} başında.
 */
public record Aircraft(String tailNumber, String type) {

    public Aircraft {
        Objects.requireNonNull(tailNumber, "tailNumber");
        Objects.requireNonNull(type, "type");
        if (tailNumber.isBlank()) {
            throw new IllegalArgumentException("kuyruk numarası boş olamaz");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("uçak tipi boş olamaz: " + tailNumber);
        }
    }
}