package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Kur tablosunun okuma kapısı: (tarih, para birimi) → 1 birimin kaç ABD doları ettiği.
public class ExchangeRates {

    /** Hesabın yapıldığı para birimi. Bu para birimi için kur sorgulanmaz. */
    public static final String BASE = "USD";

    private final Database db;

    /** Aynı (tarih, birim) tekrar tekrar sorulur; tarife başına bir okuma yeter. */
    private final Map<String, Double> cache = new HashMap<>();

    public ExchangeRates(Database db) {
        this.db = db;
    }

    /**
     * Verilen tutarı ABD dolarına çevirir.
     *
     * @throws IllegalStateException kur bulunamazsa
     */
    public double toUsd(double amount, String currency, LocalDate date) throws SQLException {
        return amount * rate(currency, date);
    }

    /**
     * 1 birim {@code currency}'nin kaç dolar ettiği.
     *
     * @throws IllegalStateException o tarihte ya da öncesinde kur yoksa
     */
    public double rate(String currency, LocalDate date) throws SQLException {
        String code = currency == null ? BASE : currency.trim().toUpperCase();
        if (code.isEmpty() || BASE.equals(code)) return 1.0;

        String key = code + "@" + date;
        Double cached = cache.get(key);
        if (cached != null) return cached;

        Double found = lookup(code, date);
        if (found == null) {
            throw new IllegalStateException(
                    "Kur bulunamadı: " + code + " için " + date + " ya da öncesine ait bir satır yok"
                    + " (exchange_rates tablosu). Kur girilmeden bu maliyet dolara çevrilemez.");
        }
        cache.put(key, found);
        return found;
    }

    //1 birim {@code from}, verilen tarihte kaç birim {@code to} eder.
    public double crossRate(String from, String to, LocalDate date) throws SQLException {
        return rate(from, date) / rate(to, date);
    }

    /**
     * Verilen tutarı {@code from} biriminden {@code to} birimine çevirir.
     *
     * <p>{@link #toUsd} bunun özel hâli ({@code to = "USD"}); ayrı durmasının
     * sebebi maliyet hesabının HER ZAMAN dolara çevirmesi — çağıranların oraya
     * her seferinde {@code "USD"} yazması gereksiz gürültü olurdu.
     */
    public double convert(double amount, String from, String to, LocalDate date)
            throws SQLException {
        return amount * crossRate(from, to, date);
    }

    /**
     * Tabloda kuru bulunan para birimleri, alfabetik.
     *
     * <p>USD listede YOKTUR ve olmaması doğrudur: tabloda satırı yok, çünkü
     * 1 USD tanım gereği 1 USD (bkz. sınıf açıklaması). Arayüzde seçim kutusu
     * dolduracaksan {@code BASE}'i listeye kendin eklemelisin.
     */
    public List<String> availableCurrencies() throws SQLException {
        // DISTINCT: aynı para biriminin her tarihi ayrı satır; kod listesi isteniyor.
        String sql = "SELECT DISTINCT currency FROM exchange_rates ORDER BY currency";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> codes = new ArrayList<>();
            while (rs.next()) codes.add(rs.getString(1));
            return codes;
        }
    }

    private Double lookup(String currency, LocalDate date) throws SQLException {
        // ORDER BY ... DESC LIMIT 1: tam tarih yoksa ondan önceki EN YAKIN gün.
        String sql = """
                SELECT rate_to_usd FROM exchange_rates
                 WHERE currency = ? AND rate_date <= ?
                 ORDER BY rate_date DESC
                 LIMIT 1""";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, currency);
            ps.setString(2, date.toString());   // ISO: metin sıralaması = tarih sıralaması
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        }
    }

    /** Kur satırı ekler/günceller (aynı tarih ve birim varsa üzerine yazar). */
    public void save(LocalDate date, String currency, double rateToUsd) throws SQLException {
        String code = currency.trim().toUpperCase();
        if (BASE.equals(code)) {
            throw new IllegalArgumentException(
                    "USD için kur girilmez: 1 USD tanım gereği 1 USD'dir.");
        }
        if (rateToUsd <= 0) {
            throw new IllegalArgumentException("Kur pozitif olmalı: " + rateToUsd);
        }
        String sql = Sql.upsert("exchange_rates",
                "rate_date, currency, rate_to_usd", "rate_date", "currency");
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, date.toString());
                ps.setString(2, code);
                ps.setDouble(3, rateToUsd);
                ps.executeUpdate();
            }
            return null;
        });
        cache.clear();
    }
}
 