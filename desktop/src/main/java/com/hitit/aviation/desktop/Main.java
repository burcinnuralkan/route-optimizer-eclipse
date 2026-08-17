package com.hitit.aviation.desktop;

import com.hitit.aviation.core.data.AirportDao;
import com.hitit.aviation.core.data.CsvImportTool;
import com.hitit.aviation.core.data.Database;
import com.hitit.aviation.core.data.FlightDao;
import com.hitit.aviation.core.data.FuelPrices;
import com.hitit.aviation.core.emission.EmissionCalculator;
import com.hitit.aviation.core.finance.ProfitCalculator;
import com.hitit.aviation.core.graph.RouteOptimizer;
import com.hitit.aviation.desktop.data.ScheduleStore;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Masaüstü uygulamasının giriş noktası ve <b>composition root</b>'u: hangi nesnenin
 * hangisiyle kurulacağı kararının toplandığı tek yer. API tarafındaki
 * {@code CoreConfig}'in karşılığıdır
 */
public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
       RouteOptimizer optimizer = new RouteOptimizer(new ProfitCalculator(new EmissionCalculator()));

       Database db = Database.fromEnvironment();
       new DesktopApp(optimizer, buildStore(db), new FuelPrices(db)).show(stage);
    }

    /**
     * Kalıcılık kurulumu: ortak veritabanı dosyası ortamdan çözülür, DAO'lar onun
     * üstüne kurulur ve depo DAO'ları alır. Veritabanı boşsa (ilk çalıştırma ya da
     * {@code .db} silinmiş) depodaki tohum CSV'lerden doldurulur — bkz.
     * {@link CsvImportTool}. Ortamı okuyan tek yer burasıdır.
     */
    private static ScheduleStore buildStore(Database db) throws Exception {
       int seeded = CsvImportTool.seedIfEmpty(db);
       if (seeded > 0) {
          System.out.println("Boş veritabanı tohum veriyle dolduruldu: "
                + seeded + " uçuş -> " + db.describe());
       }
       AirportDao airportDao = new AirportDao(db);
       return new ScheduleStore(new FlightDao(db, airportDao), airportDao);
    }

    public static void main(String[] args) {
       launch(args);
    }
}
 