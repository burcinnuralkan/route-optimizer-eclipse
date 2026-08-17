package com.hitit.aviation.api.dto;

import com.hitit.aviation.core.model.Airport;

/**
 * Havalimanının API gösterimi.
 *
 * <p>Bu paketteki tipler {@code core} modelinin JSON'a çıkan yüzüdür ve bilinçli
 * olarak <b>ayrı</b> tutulur: {@code core}'daki bir alan adı değişirse burada
 * <b>derleme hatası</b> alınır, sessizce bozulan bir API sözleşmesi değil.
 */
public record AirportDto(
        String code,
        String name,
        String city,
        String country,
        double lat,
        double lon) {

    public static AirportDto of(Airport a) {
        return new AirportDto(a.code(), a.name(), a.city(), a.country(), a.lat(), a.lon());
    }
}
 