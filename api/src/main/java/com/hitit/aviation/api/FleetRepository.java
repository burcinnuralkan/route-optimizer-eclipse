package com.hitit.aviation.api;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hitit.aviation.core.data.AirportDao;
import com.hitit.aviation.core.data.FlightDao;
import com.hitit.aviation.core.model.Airport;
import com.hitit.aviation.core.model.Flight;

/**
 * API'nin tarife kaynağı.
 */
@Component
public class FleetRepository {

    private static final Logger log = LoggerFactory.getLogger(FleetRepository.class);

    /**
     * Veritabanı kapıları DIŞARIDAN verilir (bkz. {@code CoreConfig}); sınıf ortam
     * değişkeni okumaz. Test, geçici bir veritabanına bakan DAO'lar geçerek bu
     * depoyu tek başına çalıştırabilir.
     */
    private final FlightDao flights;
    private final AirportDao airports;

    public FleetRepository(FlightDao flights, AirportDao airports) throws SQLException {
       this.flights = flights;
       this.airports = airports;
       log.info("Tarife veritabanı: {} ({} uçuş)", flights.source(), flights.count());
    }

    /** Tarifenin okunduğu kaynak (tanı/doğrulama için). */
    public String source() {
       return flights.source();
    }

    public List<Airport> airports() {
       return new ArrayList<>(read(airports::findAll).values());
    }

    public Optional<Airport> airport(String code) {
       return read(() -> airports.find(code));
    }

    /**
     * Tarifenin tamamı. Dönen liste çağıranın kendi kopyasıdır; kimse deponun
     * verisini arkadan değiştiremez ({@link Flight} zaten değiştirilemez).
     */
    public List<Flight> flights() {
       return read(flights::findAll);
    }

    /**
     * Uçuş numarasının TÜM günleri. Numara tek bir uçuşu belirtmez: {@code TK7101}
     * her sabah kalkar ve her biri ayrı bir kayıttır.
     */
    public List<Flight> flights(String flightNo) {
       return read(() -> flights.findByFlightNo(flightNo));
    }

    /** Numaranın en erken günü; "herhangi bir TK7101" yeterliyse. */
    public Optional<Flight> flight(String flightNo) {
       return flights(flightNo).stream().findFirst();
    }

    /**
     * Veritabanı okumalarının ortak sarmalayıcısı: {@link SQLException} kontrol
     * edilen bir istisnadır, ama HTTP katmanında yapılabilecek bir şey yoktur —
     * çalışma zamanı istisnasına çevrilip {@code GlobalExceptionHandler}'a bırakılır.
     */
    private <T> T read(Query<T> query) {
       try {
          return query.run();
       } catch (SQLException e) {
          log.error("Tarife okunamadı: {}", e.getMessage());
          throw new IllegalStateException("Tarife veritabanı okunamadı: " + e.getMessage(), e);
       }
    }

    @FunctionalInterface
    private interface Query<T> {
       T run() throws SQLException;
    }
}
 