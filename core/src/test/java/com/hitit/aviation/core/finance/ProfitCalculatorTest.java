package com.hitit.aviation.core.finance;
 
import static com.hitit.aviation.core.TestFlights.flight;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
import java.time.LocalDateTime;
 
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
 
import com.hitit.aviation.core.TestFlights;
import com.hitit.aviation.core.emission.EmissionCalculator;
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.FlightDecision;
import com.hitit.aviation.core.model.FlightEvaluation;
import com.hitit.aviation.core.model.OptimizerParams;
 
class ProfitCalculatorTest {
 
    private final ProfitCalculator calc = new ProfitCalculator(new EmissionCalculator());
 
    private final OptimizerParams p = OptimizerParams.defaults();
 
    /**
     * Referans bacak: 180 koltuk / 100 yolcu, 1000 km, 2 saat blok,
     * 20.000 $ bilet geliri, 1.000 $ ekip, 1.000 kg yakıt.
     * Varsayılan yakıt fiyatı 0,85 $/kg -> yakıt 850 $, toplam gider 1.850 $.
     */
    private static Flight.Builder base() {
        return Flight.builder()
                .flightNo("T1").airlineCode("XX").tailNumber("TC-TST").aircraftType("A320")
                .from(TestFlights.A).to(TestFlights.B)
                .schedDep(LocalDateTime.parse("2026-03-15T08:00"))
                .schedArr(LocalDateTime.parse("2026-03-15T10:00"))
                .econSeats(180).econPax(100)
                .cargoCapacityKg(20_000)
                .paxRevenueUsd(20_000)
                .crewCostUsd(1_000)
                .fuelKg(1_000)
                .distanceKm(1_000);
    }
 
    @Nested
    @DisplayName("Gelir ve gider kırılımı")
    class RevenueAndCost {
 
        @Test
        void revenueIsSumOfPaxAncillaryAndCargo() {
            FlightEvaluation e = calc.evaluate(base()
                    .ancillaryRevenueUsd(3_000)
                    .cargoRevenueUsd(2_000)
                    .cargoKg(1_000)
                    .build(), p);
 
            assertEquals(20_000, e.paxRevenueUsd(), 1e-9);
            assertEquals(3_000, e.ancillaryRevenueUsd(), 1e-9);
            assertEquals(2_000, e.cargoRevenueUsd(), 1e-9);
            assertEquals(25_000, e.revenueUsd(), 1e-9);
        }
 
        @Test
        void costIsSumOfEveryExpenseLine() {
            FlightEvaluation e = calc.evaluate(base()
                    .ownershipCostUsd(500)
                    .maintenanceCostUsd(300)
                    .overheadCostUsd(200)
                    .navCostUsd(100)
                    .airportCostUsd(50)
                    .build(), p);
 
            assertEquals(850, e.fuelCostUsd(), 1e-9);
            assertEquals(850 + 1_000 + 500 + 300 + 200 + 100 + 50, e.costUsd(), 1e-9);
        }
 
        @Test
        void profitIsRevenueMinusCost() {
            FlightEvaluation e = calc.evaluate(flight(0, 1_000), p);
 
            assertEquals(20_000, e.revenueUsd(), 1e-9);
            assertEquals(1_850, e.costUsd(), 1e-9);
            assertEquals(18_150, e.profitUsd(), 1e-9);
            assertTrue(e.profitable());
        }
 
        @Test
        void fuelCostUsesSafBlendedPrice() {
            OptimizerParams saf = OptimizerParams.builder()
                    .fuel(f -> f.safBlendRatio(0.5))
                    .build();
 
            // 0,85 * 0,5 + 2,60 * 0,5 = 1,725 $/kg
            assertEquals(1_000 * 1.725, calc.evaluate(flight(0, 1_000), saf).fuelCostUsd(), 1e-9);
        }
 
        @Test
        void crewCostFallsBackToBlockHourRateWhenNotRecorded() {
            FlightEvaluation e = calc.evaluate(base().crewCostUsd(0).build(), p);
 
            // 2 saat blok x 1400 $/saat
            assertEquals(2_800, e.crewCostUsd(), 1e-9);
        }
 
        @Test
        void recordedCrewCostWinsOverEstimate() {
            assertEquals(1_000, calc.evaluate(flight(0, 1_000), p).crewCostUsd(), 1e-9);
        }
    }
 
    @Nested
    @DisplayName("KPI'lar - ASK, RPK, RASK, CASK, yield, BELF")
    class Kpis {
 
        private final FlightEvaluation e = calc.evaluate(flight(0, 1_000), p);
 
        @Test
        void askIsSeatsTimesDistance() {
            assertEquals(180 * 1_000, e.ask(), 1e-9);
        }
 
        @Test
        void rpkIsPassengersTimesDistance() {
            assertEquals(100 * 1_000, e.rpk(), 1e-9);
        }
 
        @Test
        void raskAndCaskAreCentsPerAsk() {
            assertEquals(20_000 / 180_000.0 * 100, e.raskCents(), 1e-9);
            assertEquals(1_850 / 180_000.0 * 100, e.caskCents(), 1e-9);
        }
 
        @Test
        void yieldExcludesCargoRevenue() {
            FlightEvaluation withCargo = calc.evaluate(base()
                    .cargoKg(2_000)
                    .cargoRevenueUsd(4_000)
                    .ancillaryRevenueUsd(1_000)
                    .build(), p);
 
            // yield = (bilet + yan gelir) / RPK; kargo geliri dahil DEĞİL
            assertEquals((20_000 + 1_000) / 100_000.0 * 100, withCargo.yieldCents(), 1e-9);
        }
 
        @Test
        void breakEvenLoadFactorIsCaskOverYield() {
            assertEquals(e.caskCents() / e.yieldCents(), e.breakEvenLoadFactor(), 1e-12);
        }
 
        @Test
        void cargoRevenueLowersTheBreakEvenLoadFactor() {
            FlightEvaluation withCargo = calc.evaluate(base()
                    .cargoKg(2_000)
                    .cargoRevenueUsd(4_000)
                    .build(), p);
 
            assertTrue(withCargo.breakEvenLoadFactorNetCargo() < withCargo.breakEvenLoadFactor(),
                    "kargo geliri gideri karşıladıkça başabaş doluluk düşmeli");
        }
 
        @Test
        void paskIsRaskMinusCask() {
            assertEquals(e.raskCents() - e.caskCents(), e.paskCents(), 1e-12);
        }
 
        @Test
        void exFuelCaskLeavesFuelOut() {
            assertEquals((1_850 - 850) / 180_000.0 * 100, e.exFuelCaskCents(), 1e-9);
        }
 
        @Test
        void stageAdjustedCaskScalesBySqrtOfStageLength() {
            // referans mesafe 1500 km, uçuş 1000 km
            assertEquals(e.caskCents() * Math.sqrt(1_000 / 1_500.0),
                    e.stageAdjustedCaskCents(), 1e-12);
        }
 
        @Test
        void unitKpisAreZeroWhenThereIsNoAsk() {
            FlightEvaluation zeroDistance = calc.evaluate(base().distanceKm(0).build(), p);
 
            assertEquals(0, zeroDistance.ask(), 1e-9);
            assertEquals(0, zeroDistance.raskCents(), 1e-9);
            assertEquals(0, zeroDistance.caskCents(), 1e-9);
            assertEquals(0, zeroDistance.yieldCents(), 1e-9);
            assertEquals(0, zeroDistance.breakEvenLoadFactor(), 1e-9);
            assertEquals(0, zeroDistance.stageAdjustedCaskCents(), 1e-9);
        }
 
        @Test
        void loadFactorsComeFromSeatsAndCargoCapacity() {
            FlightEvaluation loaded = calc.evaluate(base().cargoKg(5_000).build(), p);
 
            assertEquals(100 / 180.0, loaded.passengerLoadFactor(), 1e-12);
            assertEquals(5_000 / 20_000.0, loaded.cargoLoadFactor(), 1e-12);
        }
 
        @Test
        void blockHoursComeFromTheSchedule() {
            assertEquals(2.0, e.blockHours(), 1e-9);
        }
    }
 
    @Nested
    @DisplayName("Kargo ve katkı payı")
    class CargoAndContribution {
 
        @Test
        void averageFareIsPaxRevenuePerPassenger() {
            assertEquals(200, calc.evaluate(flight(0, 1_000), p).avgFareUsd(), 1e-9);
        }
 
        @Test
        void averageFareIsZeroWithoutPassengers() {
            assertEquals(0, calc.evaluate(base().econPax(0).build(), p).avgFareUsd(), 1e-9);
        }
 
        @Test
        void cargoYieldIsRevenuePerTonneKm() {
            FlightEvaluation e = calc.evaluate(base()
                    .cargoKg(2_000)
                    .cargoRevenueUsd(4_000)
                    .build(), p);
 
            // 2 ton x 1000 km = 2000 ton-km -> 4000 $ / 2000 = 2 $/ton-km
            assertEquals(2.0, e.cargoYieldPerTonneKm(), 1e-9);
        }
 
        @Test
        void cargoYieldIsZeroWithoutCargo() {
            assertEquals(0, calc.evaluate(flight(0, 1_000), p).cargoYieldPerTonneKm(), 1e-9);
        }
 
        @Test
        void cargoRevenueShareIsPartOfTotalRevenue() {
            FlightEvaluation e = calc.evaluate(base()
                    .cargoKg(2_000)
                    .cargoRevenueUsd(5_000)
                    .build(), p);
 
            assertEquals(5_000 / 25_000.0, e.cargoRevenueShare(), 1e-12);
        }
 
        @Test
        void contributionMarginExcludesOwnershipAndOverhead() {
            FlightEvaluation e = calc.evaluate(base()
                    .ownershipCostUsd(4_000)
                    .overheadCostUsd(1_000)
                    .maintenanceCostUsd(300)
                    .navCostUsd(100)
                    .airportCostUsd(50)
                    .build(), p);
 
            double variableCost = 850 + 1_000 + 300 + 100 + 50;
            assertEquals(20_000 - variableCost, e.contributionMarginUsd(), 1e-9);
            assertEquals(e.contributionMarginUsd() / 20_000.0, e.contributionMarginRatio(), 1e-12);
        }
 
        @Test
        void positiveContributionMarginMeansFly() {
            assertEquals(FlightDecision.FLY, calc.evaluate(flight(0, 1_000), p).decision());
        }
 
        @Test
        void negativeContributionMarginMeansCancel() {
            FlightEvaluation e = calc.evaluate(base()
                    .paxRevenueUsd(500)
                    .econPax(5)
                    .build(), p);
 
            assertEquals(FlightDecision.CANCEL, e.decision());
        }
 
        @Test
        void reviewBandKeepsMarginalFlightsUndecided() {
            FlightEvaluation e = calc.evaluate(base()
                    .paxRevenueUsd(500)
                    .econPax(5)
                    .build(), p);
 
            // Katkı payı -1350 $; 2000 $'lık bant içinde kaldığı için karar "incele".
            assertEquals(FlightDecision.REVIEW, e.decision(2_000));
        }
    }
 
    @Nested
    @DisplayName("Emisyon kırılımı ve zamanındalık")
    class EmissionAndOtp {
 
        @Test
        void passengerAndCargoCo2AddUpToTotal() {
            FlightEvaluation e = calc.evaluate(base().cargoKg(5_000).build(), p);
 
            assertEquals(e.co2Kg(), e.passengerCo2Kg() + e.cargoCo2Kg(), 1e-6);
            assertTrue(e.cargoCo2Kg() > 0, "kargo varken kargoya pay düşmeli");
        }
 
        @Test
        void cargoFreeFlightAttributesAllCo2ToPassengers() {
            FlightEvaluation e = calc.evaluate(flight(0, 1_000), p);
 
            assertEquals(e.co2Kg(), e.passengerCo2Kg(), 1e-6);
            assertEquals(0, e.cargoCo2Kg(), 1e-6);
        }
 
        @Test
        void delaysAreNotANumberWithoutActualTimes() {
            FlightEvaluation e = calc.evaluate(flight(0, 1_000), p);
 
            assertTrue(Double.isNaN(e.departureDelayMinutes()));
            assertTrue(Double.isNaN(e.arrivalDelayMinutes()));
            assertFalse(e.onTimeArrival(), "gerçekleşen veri yoksa zamanında sayılmaz");
        }
 
        @Test
        void arrivalUpToFifteenMinutesLateIsStillOnTime() {
            FlightEvaluation e = calc.evaluate(flight(0, 1_000, "10:15"), p);
 
            assertEquals(15, e.arrivalDelayMinutes(), 1e-9);
            assertEquals(0, e.departureDelayMinutes(), 1e-9);
            assertTrue(e.onTimeArrival(), "A15 tanımı sınırı dahil kabul eder");
        }
 
        @Test
        void sixteenMinutesLateBreaksTheA15Rule() {
            FlightEvaluation e = calc.evaluate(flight(0, 1_000, "10:16"), p);
 
            assertEquals(16, e.arrivalDelayMinutes(), 1e-9);
            assertFalse(e.onTimeArrival());
        }
 
        @Test
        void earlyArrivalIsOnTimeWithNegativeDelay() {
            FlightEvaluation e = calc.evaluate(flight(0, 1_000, "09:50"), p);
 
            assertEquals(-10, e.arrivalDelayMinutes(), 1e-9);
            assertTrue(e.onTimeArrival());
        }
    }
}