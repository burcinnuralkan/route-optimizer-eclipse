package com.hitit.aviation.core;

import java.time.LocalDateTime;

import com.hitit.aviation.core.model.Airport;
import com.hitit.aviation.core.model.Flight;

// Testler için hazır {@link Flight} üreten yardımcı ("object mother" deseni).
public final class TestFlights {

    private TestFlights() { }

    public static final Airport A = new Airport("AAA", "A", "A", "TR", 41.0, 28.0);
    public static final Airport B = new Airport("BBB", "B", "B", "TR", 39.0, 32.0);
    public static final Airport C = new Airport("CCC", "C", "C", "TR", 37.0, 30.0);

    /** 180 ekonomi koltuk, 100 ekonomi yolcu; gerçekleşen zaman yok. */
    public static Flight flight(double cargoKg, double fuelKg) {
        return flight(cargoKg, fuelKg, null);
    }

    /** {@code actualArr} "HH:mm" verilirse gerçekleşen varış eklenir (kalkış 08:00). */
    public static Flight flight(double cargoKg, double fuelKg, String actualArr) {
        return build(180, 0, 100, 0, cargoKg, fuelKg, actualArr);
    }

    /** Karma kabin: 180 ekonomi + {@code busSeats} business koltuk/yolcu. */
    public static Flight mixedCabinFlight(int busSeats, double busPax, double cargoKg, double fuelKg) {
        return build(180, busSeats, 100, busPax, cargoKg, fuelKg, null);
    }

    private static Flight build(int econSeats, int busSeats, double econPax, double busPax,
                                double cargoKg, double fuelKg, String actualArr) {
        LocalDateTime dep = LocalDateTime.parse("2026-03-15T08:00");
        LocalDateTime arr = LocalDateTime.parse("2026-03-15T10:00");
        LocalDateTime actualDep = actualArr == null ? null : dep;
        LocalDateTime actualArrDt =
                actualArr == null ? null : LocalDateTime.parse("2026-03-15T" + actualArr);

        return new Flight(
                "T1", "XX", "TC-TST", "A320", A, B,
                dep, arr, actualDep, actualArrDt,
                econSeats, busSeats, econPax, busPax,
                cargoKg, 20_000,
                20_000, 0, 0,
                1_000, 0, 0, 0, 0, 0,
                fuelKg, 1_000, fuelKg);
    }

    /**
     * Rota/aktarma testleri için tam kontrollü bir bacak: uçuş no, havayolu,
     * kalkış/varış havalimanı ve saatler (ISO "yyyy-MM-ddTHH:mm") dışarıdan
     * verilir; ekonomik alanlar makul sabittir. Çok-bacaklı tarife kurmak için.
     */
    public static Flight leg(String flightNo, String airline, Airport from, Airport to,
                             String depIso, String arrIso) {
        return new Flight(
                flightNo, airline, "TC-TST", "A320", from, to,
                LocalDateTime.parse(depIso), LocalDateTime.parse(arrIso), null, null,
                180, 0, 100, 0,
                0, 20_000,
                20_000, 0, 0,
                1_000, 0, 0, 0, 0, 0,
                3_000, 1_000, 0);
    }
}
 