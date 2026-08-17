package com.hitit.aviation.core;

import java.time.LocalDate;
import java.util.Locale;

import com.hitit.aviation.core.data.Database;
import com.hitit.aviation.core.data.FuelPrices;

// Yakıt fiyatını konsoldan okur — {@link FuelPrices}'ın elle denenebilir hâli.
 
public class FuelPriceDemo {

    public static void main(String[] args) throws Exception {
        Database db = Database.fromEnvironment();
        FuelPrices prices = new FuelPrices(db);

        System.out.println("Veritabanı : " + db.describe());

        if (args.length > 0 && "set".equalsIgnoreCase(args[0])) {
            if (args.length < 4) {
                System.out.println("Kullanım: set <tarih> <JET_A1|SAF> <fiyat> [para birimi]");
                return;
            }
            LocalDate date = LocalDate.parse(args[1]);
            String type = args[2];
            double amount = Double.parseDouble(args[3]);
            String currency = args.length > 4 ? args[4] : FuelPrices.BASE;

            prices.save(date, type, amount, currency);
            System.out.printf(Locale.US, "Yazıldı: %s %s = %.4f %s%n", date, type, amount, currency);
            System.out.println();
        }

        LocalDate date = args.length > 0 && !"set".equalsIgnoreCase(args[0])
                ? LocalDate.parse(args[0])
                : LocalDate.now();

        System.out.println("Tarih      : " + date);
        System.out.println();

        // Fiyat bulunamazsa istisna atılır ve bu doğru: sessizce 0 kullanmak
        // yakıt maliyetini yok eder, uçuş olduğundan kârlı görünür.
        System.out.printf(Locale.US, "%-7s | 1 kg = %.4f USD%n",
                FuelPrices.JET_A1, prices.jetA1PerKgUsd(date));
        System.out.printf(Locale.US, "%-7s | 1 kg = %.4f USD%n",
                FuelPrices.SAF, prices.safPerKgUsd(date));

        // Örnek: 5000 kg yakıt yakan bir sefer, %30 SAF harmanıyla.
        double fuelKg = 5000;
        double blend = 0.30;
        double effective = prices.jetA1PerKgUsd(date) * (1 - blend)
                + prices.safPerKgUsd(date) * blend;
        System.out.printf(Locale.US,
                "%n%.0f kg yakıt, %%%.0f SAF harmanı -> etkin %.4f $/kg -> %.2f USD%n",
                fuelKg, blend * 100, effective, fuelKg * effective);
    }
}
 
