package com.hitit.aviation.core.model;

import java.util.Objects;

/**
 * Bir uçuşun/rotanın sıralama skoru — <b>değeriyle birlikte BİRİMİNİ de taşır</b>.
 *
 * <h2>Neden çıplak {@code double} yetmiyordu?</h2>
 * Skorun birimi seçilen amaca göre değişir:
* birim denetimi ÇALIŞMA ZAMANINDADIR. Derleme zamanında yakalamak
 * için her birime ayrı tip (UsdScore / Co2Score) gerekirdi; bu ölçekte maliyeti
 * faydasını aşar. Sessiz yanlış sonuç yerine net bir exception almak, aradaki
 * farkın büyük kısmını zaten kapatır.
 */
public record Score(double value, Unit unit) {

    /** Skorun ölçü birimi. */
    public enum Unit {
        /** ABD doları — kâr temelli amaçlar (MAX_PROFIT, WEIGHTED). */
        USD("$"),
        /** Kilogram CO2 — emisyon temelli amaç (MIN_CO2). */
        KG_CO2("kg CO2");

        private final String symbol;

        Unit(String symbol) { this.symbol = symbol; }

        public String symbol() { return symbol; }
    }

    public Score {
        Objects.requireNonNull(unit, "unit");
    }

    public static Score usd(double value) { return new Score(value, Unit.USD); }

    public static Score kgCo2(double value) { return new Score(value, Unit.KG_CO2); }

    public static Score zero(Unit unit) { return new Score(0, unit); }

    /** @throws IllegalArgumentException birimler farklıysa */
    public Score plus(Score other) {
        requireSameUnit(other, "toplanamaz");
        return new Score(value + other.value, unit);
    }

    /** @throws IllegalArgumentException birimler farklıysa */
    public Score minus(Score other) {
        requireSameUnit(other, "çıkarılamaz");
        return new Score(value - other.value, unit);
    }

    /** Birimden bağımsız ölçekleme (birim korunur). */
    public Score times(double factor) {
        return new Score(value * factor, unit);
    }

    private void requireSameUnit(Score other, String what) {
        Objects.requireNonNull(other, "other");
        if (unit != other.unit) {
            throw new IllegalArgumentException(
                    "Farklı birimli skorlar " + what + ": " + unit + " ile " + other.unit
                    + ". Kâr ile emisyonu aynı ölçekte tartmak için WEIGHTED amacını kullanın "
                    + "(orada CO2, karbon fiyatıyla dolara çevrilir).");
        }
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "%,.2f %s", value, unit.symbol());
    }
}

 