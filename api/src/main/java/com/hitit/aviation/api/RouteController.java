package com.hitit.aviation.api;
import com.hitit.aviation.core.graph.RouteOptimizer;
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.OptimizerParams;
import com.hitit.aviation.core.model.RouteResult;
import com.hitit.aviation.api.dto.FleetResponse;
import com.hitit.aviation.api.dto.RouteResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;


import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "rota optimizasyonu", description = "kar co2 amaçlı")
public class RouteController {
    private final FleetRepository fleet;
    private final RouteOptimizer optimizer;

    // İki bağımlılık da DIŞARIDAN gelir (bkz. CoreConfig); controller kendi
    // motorunu yaratmaz, bu yüzden testte sahte bir optimizer verilebilir.
    public RouteController(FleetRepository fleet, RouteOptimizer optimizer) {
       this.fleet = fleet;
       this.optimizer = optimizer;
    }


    @GetMapping("/routes/best")
    @Operation(summary = "en iyi rota", description= " dag ile")
    public List<RouteResponse> best(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "MAX_PROFIT") OptimizerParams.Objective objective,
            @RequestParam(defaultValue = "0.0") double safBlend,
            @RequestParam(defaultValue = "90") double carbonPrice,
            @RequestParam(defaultValue = "3") int maxLegs,
            @RequestParam(defaultValue = "0") double connectionPenalty,
            @RequestParam(defaultValue = "5") int limit,
            // ── Tarih aralığı filtresi ──
            @RequestParam(required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            // ── KPI / doluluk etkisi ──
            @RequestParam(defaultValue = "0.34") double loadFactorWeight,
            @RequestParam(defaultValue = "0.33") double raskWeight,
            @RequestParam(defaultValue = "0.33") double caskWeight,
            @RequestParam(defaultValue = "0.25") double kpiStrength,
            @RequestParam(defaultValue = "1500") double refStageLengthKm,
            @RequestParam(defaultValue = "0.0") double otpDelayPenaltyPerMinute,
            // ── Karar filtresi: katkı payı (netCM) ile kıyaslanacak inceleme bandı ──
            //   netCM > +band → FLY, netCM < −band → CANCEL, |netCM| ≤ band → REVIEW.
            //   Cevaptaki "decision" / "legDecisions" alanları bu banda göre üretilir.
            @RequestParam(defaultValue = "0.0") double reviewBandUsd,
            // ── Aktarma kısıtları ──
            //   maxConnectMinutes: en fazla aktarma boşluğu (dk); 0 = sınırsız.
            //   sameAirlineOnly: true -> yalnız aynı havayoluyla aktarma (interline yok).
            @RequestParam(defaultValue = "0") int maxConnectMinutes,
            @RequestParam(defaultValue = "false") boolean sameAirlineOnly) {

        // Geçersiz bir query param burada IllegalArgumentException fırlatır;
        // GlobalExceptionHandler bunu 400 Bad Request'e çevirir.
        OptimizerParams p = OptimizerParams.builder()
                .objective(objective)
                .carbonPricePerTon(carbonPrice)
                .maxLegs(maxLegs)
                .connectionPenalty(connectionPenalty)
                .maxConnectMinutes(maxConnectMinutes)
                .sameAirlineOnly(sameAirlineOnly)
                .otpDelayPenaltyPerMinute(otpDelayPenaltyPerMinute)
                .fuel(f -> f.safBlendRatio(safBlend))
                .kpi(k -> k.loadFactorWeight(loadFactorWeight)
                          .raskWeight(raskWeight)
                          .caskWeight(caskWeight)
                          .kpiStrength(kpiStrength)
                          .refStageLengthKm(refStageLengthKm))
                .build();

        List<Flight> flights = fleet.flights().stream()
                .filter(f -> dateFrom == null || !f.flightDate().isBefore(dateFrom))
                .filter(f -> dateTo == null || !f.flightDate().isAfter(dateTo))
                .toList();

        List<RouteResult> all = optimizer.bestRoutesPerState(flights, from, to, p);
        return all.subList(0, Math.min(limit, all.size())).stream()
                .map(r -> RouteResponse.of(r, reviewBandUsd))
                .toList();
    }

    /**
     * Rota aramadan tarifenin tamamını değerlendirir — masaüstündeki
     * "kalkış/varış seçilmedi" görünümünün API karşılığı. Ortak CSV'den okur,
     * dolayısıyla masaüstünde kaydedilen düzeltme burada da görünür.
     */
    @GetMapping("/flights")
    @Operation(summary = "tüm tarife", description = "rota aramadan uçuş bazında değerlendirme + skor + karar")
    public FleetResponse fleet(
            @RequestParam(defaultValue = "MAX_PROFIT") OptimizerParams.Objective objective,
            @RequestParam(defaultValue = "0.0") double safBlend,
            @RequestParam(defaultValue = "90") double carbonPrice,
            @RequestParam(defaultValue = "0.34") double loadFactorWeight,
            @RequestParam(defaultValue = "0.33") double raskWeight,
            @RequestParam(defaultValue = "0.33") double caskWeight,
            @RequestParam(defaultValue = "0.25") double kpiStrength,
            @RequestParam(defaultValue = "1500") double refStageLengthKm,
            @RequestParam(defaultValue = "0.0") double otpDelayPenaltyPerMinute,
            @RequestParam(defaultValue = "0.0") double reviewBandUsd,
            @RequestParam(required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String airline) {

        OptimizerParams p = OptimizerParams.builder()
                .objective(objective)
                .carbonPricePerTon(carbonPrice)
                .otpDelayPenaltyPerMinute(otpDelayPenaltyPerMinute)
                .fuel(f -> f.safBlendRatio(safBlend))
                .kpi(k -> k.loadFactorWeight(loadFactorWeight)
                          .raskWeight(raskWeight)
                          .caskWeight(caskWeight)
                          .kpiStrength(kpiStrength)
                          .refStageLengthKm(refStageLengthKm))
                .build();

        List<Flight> flights = fleet.flights().stream()
                .filter(f -> dateFrom == null || !f.flightDate().isBefore(dateFrom))
                .filter(f -> dateTo == null || !f.flightDate().isAfter(dateTo))
                .filter(f -> airline == null || airline.isBlank()
                        || f.airlineCode().equalsIgnoreCase(airline.trim()))
                .toList();

        return FleetResponse.of(fleet.source(), optimizer.evaluateFleet(flights, p), reviewBandUsd);
    }
    // Hata dönüşümü artık exception.GlobalExceptionHandler'da (controller dışında).
}