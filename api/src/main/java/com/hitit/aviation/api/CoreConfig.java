package com.hitit.aviation.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hitit.aviation.core.data.AirportDao;
import com.hitit.aviation.core.data.CsvImportTool;
import com.hitit.aviation.core.data.Database;
import com.hitit.aviation.core.data.FlightDao;
import com.hitit.aviation.core.data.FuelPrices;
import com.hitit.aviation.core.emission.EmissionCalculator;
import com.hitit.aviation.core.finance.ProfitCalculator;
import com.hitit.aviation.core.graph.RouteOptimizer;

//Composition root</b> — nesnelerin nasıl birleştirileceği kararının toplandığı tek yer. 
@Configuration
public class CoreConfig {

    /**
     * Masaüstüyle ORTAK veritabanı; yol yalnızca burada ortamdan çözülür.
     * Veritabanı boşsa depodaki tohum CSV'lerden doldurulur, böylece API tek
     * başına da (masaüstü hiç açılmadan) çalışır.
     */
    @Bean
    Database database() throws Exception {
        Database db = Database.fromEnvironment();
        CsvImportTool.seedIfEmpty(db);
        return db;
    }

    @Bean
    AirportDao airportDao(Database db) {
        return new AirportDao(db);
    }

    @Bean
    FlightDao flightDao(Database db, AirportDao airportDao) {
        return new FlightDao(db, airportDao);
    }

    /**
     * Yakıt fiyatı tablosunun kapısı. Fiyat eskiden {@code OptimizerParams}
     * içinde sabitti; tarihli tabloya taşındı ki geçmiş raporlar fiyat
     * güncellendiğinde değişmesin (bkz. {@link FuelPrices}).
     */
    @Bean
    FuelPrices fuelPrices(Database db) {
        return new FuelPrices(db);
    }

    @Bean
    EmissionCalculator emissionCalculator() {
        return new EmissionCalculator();
    }

    @Bean
    ProfitCalculator profitCalculator(EmissionCalculator emission) {
        return new ProfitCalculator(emission);
    }

    @Bean
    RouteOptimizer routeOptimizer(ProfitCalculator calculator) {
        return new RouteOptimizer(calculator);
    }
}
 