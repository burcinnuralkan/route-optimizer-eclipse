package com.hitit.aviation.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.hitit.aviation.core.data.Database;
import com.hitit.aviation.core.data.ExchangeRates;

//Kur çevrimini konsoldan çalıştırır — {@link ExchangeRates}'in elle denenebilir hâli.
public class ExchangeDemo {

    /** Örnek kurlar — yalnızca {@code seed} argümanıyla yazılır. */
    private static final LocalDate SEED_DATE = LocalDate.of(2026, 1, 1);

    public static void main(String[] args) throws Exception {
        // Ortamdan çözülen ORTAK veritabanı; burada yol yazılmaz.
        Database db = Database.fromEnvironment();
        ExchangeRates rates = new ExchangeRates(db);

        System.out.println("Veritabanı : " + db.describe());

        if (args.length > 0 && "seed".equalsIgnoreCase(args[0])) {
            // save() aynı (tarih, birim) varsa üzerine yazar; tekrar çalıştırmak zararsız.
            rates.save(SEED_DATE, "EUR", 1.09);
            rates.save(SEED_DATE, "TRY", 0.031);
            System.out.println("Örnek kurlar yazıldı (" + SEED_DATE + "): EUR=1.09, TRY=0.031");
        }

        List<String> currencies = rates.availableCurrencies();
        System.out.println("Para birimleri : " + (currencies.isEmpty() ? "(tablo boş)" : currencies));

        if (currencies.isEmpty()) {
            // Boş tablo bir çökme sebebi değil, bir veri eksiğidir: ne yapılacağını söyle.
            System.out.println("""

                    exchange_rates tablosunda hiç kur yok, çevrim yapılamaz.
                    Örnek kurlarla doldurmak için bu sınıfı 'seed' argümanıyla çalıştırın.""");
            return;
        }

        // Tarih önemli: kur, uçuşun/işlemin GÜNÜNE göre bulunur (bkz. ExchangeRates).
        LocalDate date = LocalDate.now();
        System.out.println("Tarih          : " + date);
        System.out.println();

        for (String code : currencies) {
            try {
                double oneUnitInUsd = rates.rate(code, date);            // 1 code kaç USD
                double oneUsdInCode = rates.crossRate("USD", code, date); // 1 USD kaç code
                double hundred = rates.convert(100, "USD", code, date);

                System.out.printf(Locale.US,
                        "%-4s | 1 %s = %.4f USD | 1 USD = %.4f %s | 100 USD = %.2f %s%n",
                        code, code, oneUnitInUsd, oneUsdInCode, code, hundred, code);

            } catch (IllegalStateException e) {
                // O tarihte ya da öncesinde kur yok. Sessizce 1.0 varsaymak yerine
                // hangi birimin eksik olduğu yazılır (bkz. ExchangeRates açıklaması).
                System.out.println(code + " : " + e.getMessage());
            }
        }
    }
}
 