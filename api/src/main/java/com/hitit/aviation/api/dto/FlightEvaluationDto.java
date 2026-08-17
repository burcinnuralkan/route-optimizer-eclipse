package com.hitit.aviation.api.dto;

import com.hitit.aviation.core.model.FlightEvaluation;

/**
 * Bir uçuşun değerlendirmesinin API gösterimi: gelir/gider kırılımı, emisyon
 * bölüşümü ve KPI'lar.
 *
 * <p>Alanlar {@code core}'daki {@link FlightEvaluation} ile bire bir aynıdır ama
 * BURADA yeniden tanımlanmıştır. Bunun bedeli bir kez yazılan eşleme kodudur;
 * karşılığı, {@code core} bir alanı yeniden adlandırdığında {@link #of} metodunun
 * <b>derlenmemesi</b> — yani API sözleşmesinin sessizce değişememesidir.
 */
public record FlightEvaluationDto(
        FlightDto flight,
        double revenueUsd,
        double costUsd,
        double profitUsd,
        // gelir
        double paxRevenueUsd,
        double ancillaryRevenueUsd,
        double cargoRevenueUsd,
        // gider
        double fuelCostUsd,
        double crewCostUsd,
        double ownershipCostUsd,
        double maintenanceCostUsd,
        double overheadCostUsd,
        double navCostUsd,
        double airportCostUsd,
        // yakıt & emisyon
        double fuelKg,
        double co2Kg,
        double passengerCo2Kg,
        double cargoCo2Kg,
        double co2PerPaxKg,
        double co2PerEconomyPaxKg,
        double co2PerBusinessPaxKg,
        // KPI
        double ask,
        double rpk,
        double raskCents,
        double caskCents,
        double yieldCents,
        double breakEvenLoadFactor,
        double breakEvenLoadFactorNetCargo,
        double fuelLitersPer100Pkm,
        double passengerLoadFactor,
        double cargoLoadFactor,
        double blockHours,
        double paskCents,
        double exFuelCaskCents,
        double stageAdjustedCaskCents,
        double avgFareUsd,
        double cargoYieldPerTonneKm,
        double cargoRevenueShare,
        double contributionMarginUsd,
        double contributionMarginRatio,
        // zamanındalık — veri yoksa NaN, JSON'da null (bkz. JacksonConfig)
        double departureDelayMinutes,
        double arrivalDelayMinutes,
        boolean onTimeArrival) {

    public static FlightEvaluationDto of(FlightEvaluation e) {
        return new FlightEvaluationDto(
                FlightDto.of(e.flight()),
                e.revenueUsd(), e.costUsd(), e.profitUsd(),
                e.paxRevenueUsd(), e.ancillaryRevenueUsd(), e.cargoRevenueUsd(),
                e.fuelCostUsd(), e.crewCostUsd(), e.ownershipCostUsd(), e.maintenanceCostUsd(),
                e.overheadCostUsd(), e.navCostUsd(), e.airportCostUsd(),
                e.fuelKg(), e.co2Kg(), e.passengerCo2Kg(), e.cargoCo2Kg(),
                e.co2PerPaxKg(), e.co2PerEconomyPaxKg(), e.co2PerBusinessPaxKg(),
                e.ask(), e.rpk(), e.raskCents(), e.caskCents(), e.yieldCents(),
                e.breakEvenLoadFactor(), e.breakEvenLoadFactorNetCargo(), e.fuelLitersPer100Pkm(),
                e.passengerLoadFactor(), e.cargoLoadFactor(), e.blockHours(),
                e.paskCents(), e.exFuelCaskCents(), e.stageAdjustedCaskCents(),
                e.avgFareUsd(), e.cargoYieldPerTonneKm(), e.cargoRevenueShare(),
                e.contributionMarginUsd(), e.contributionMarginRatio(),
                e.departureDelayMinutes(), e.arrivalDelayMinutes(), e.onTimeArrival());
    }
}
 