package com.hitit.aviation.core.emission;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static com.hitit.aviation.core.TestFlights.flight;
import static com.hitit.aviation.core.TestFlights.mixedCabinFlight;

import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.OptimizerParams;

class EmissionCalculatorTest {

    private final EmissionCalculator emission = new EmissionCalculator();

    private final OptimizerParams p = OptimizerParams.defaults();

    @Nested
    @DisplayName("correctedDistanceKm (static) - great-circle distance correction")
    class DistanceCorrection {

        @Test
        void below550Km_adds50() {
            assertEquals(350, EmissionCalculator.correctedDistanceKm(300), 1e-9);
        }

        @Test
        void middleBand_adds100() {
            assertEquals(650, EmissionCalculator.correctedDistanceKm(550), 1e-9);
            assertEquals(5600, EmissionCalculator.correctedDistanceKm(5500), 1e-9);
        }

        @Test
        void above5500Km_adds125() {
            assertEquals(9125, EmissionCalculator.correctedDistanceKm(9000), 1e-9);
        }
    }

    @Nested
    @DisplayName("fuelKg - real fuel priority plus parametric estimate")
    class FuelEstimate {

        @Test
        void realFuel_takesPriority() {
            assertEquals(5000, emission.fuelKg(flight(0, 5000), p), 1e-9);
        }

        @Test
        void noFuel_parametricEstimate() {
            assertEquals(180 * 1000 * 0.025,
                    emission.fuelKg(flight(0, 0), p),
                    1e-9);
        }

        @Test
        void ltoTerm_added() {

            OptimizerParams lto = OptimizerParams.builder()
                    .fuel(f -> f.ltoFuelKg(800))
                    .build();

            assertEquals(
                    800 + 180 * 1000 * 0.025,
                    emission.fuelKg(flight(0, 0), lto),
                    1e-9);
        }
    }

    @Nested
    @DisplayName("co2Kg - CORSIA fuel to CO2 (including SAF reduction)")
    class Co2Conversion {

        @Test
        void noSaf_coefficient316() {
            assertEquals(
                    1000 * 3.16,
                    emission.co2Kg(flight(0, 1000), p),
                    1e-6);
        }

        @Test
        void halfSaf_reduces() {

            OptimizerParams saf = OptimizerParams.builder()
                    .fuel(f -> f.safBlendRatio(0.5))
                    .build();

            assertEquals(
                    1000 * 1.896,
                    emission.co2Kg(flight(0, 1000), saf),
                    1e-6);
        }
    }

    @Nested
    @DisplayName("passengerMassShare - split including ICAO equipment term")
    class MassShare {

        @Test
        void includesEquipmentTerm() {
            assertEquals(
                    19_000.0 / 29_000.0,
                    emission.passengerMassShare(flight(10_000, 1000), p),
                    1e-9);
        }

        @Test
        void equipmentOff_legacyBehaviour() {

            OptimizerParams noEq = OptimizerParams.builder()
                    .emission(e -> e.seatEquipmentMassKg(0))
                    .build();

            assertEquals(
                    0.5,
                    emission.passengerMassShare(flight(10_000, 1000), noEq),
                    1e-9);
        }

        @Test
        void noCargo_allToPassengers() {
            assertEquals(
                    1.0,
                    emission.passengerMassShare(flight(0, 1000), p),
                    1e-9);
        }

        @Test
        void splitIsPreserved() {
            Flight f = flight(10_000, 1000);

            assertEquals(
                    emission.co2Kg(f, p),
                    emission.passengerCo2Kg(f, p)
                            + emission.cargoCo2Kg(f, p),
                    1e-6);
        }
    }

    @Nested
    @DisplayName("Per-passenger CO2 and fuel efficiency")
    class PerPassenger {

        @Test
        void co2PerPax_noCargo() {
            assertEquals(
                    31.6,
                    emission.co2PerPaxKg(flight(0, 1000), p),
                    1e-6);
        }

        @Test
        void cabinWeight_ratio() {
            Flight f = mixedCabinFlight(12, 8, 0, 1000);

            double econ = emission.co2PerEconomyPaxKg(f, p);
            double bus = emission.co2PerBusinessPaxKg(f, p);

            assertEquals(2.5 * econ, bus, 1e-9);
        }

        @Test
        void fuelPer100PaxKm() {
            assertEquals(
                    1.25,
                    emission.fuelPer100PaxKm(flight(0, 1000), p),
                    1e-9);
        }
    }
}