package com.hitit.aviation.api.dto;

import java.util.List;

import com.hitit.aviation.core.model.RouteResult;

/**
 * Bir rotanın API gösterimi: bacaklar + toplamlar + skor.
 *
 * @param path        okunabilir güzergâh, ör. {@code "ESB -> IST -> JFK"}
 * @param score       sıralama skorunun sayısal değeri
 * @param scoreUnit   skorun BİRİMİ ({@code "USD"} veya {@code "KG_CO2"}). Amaç
 *                    MIN_CO2 iken skor kilogram CO2'dir ve dolar cinsinden
 *                    skorlarla kıyaslanamaz (bkz. {@code core.model.Score})
 * @param exFuelCaskCents rota geneli ASK-ağırlıklı yakıt-hariç CASK. Skora girmez;
 *                    eşit skorlu rotalar arasında sıralama kırıcı olarak kullanılır
 */
public record RouteDto(
        String path,
        List<FlightEvaluationDto> legs,
        double totalRevenueUsd,
        double totalPaxRevenueUsd,
        double totalAncillaryRevenueUsd,
        double totalCargoRevenueUsd,
        double totalCostUsd,
        double totalProfitUsd,
        double totalFuelKg,
        double totalCo2Kg,
        double totalPassengerCo2Kg,
        double totalCargoCo2Kg,
        double totalCo2PerPaxKg,
        double totalContributionMarginUsd,
        double exFuelCaskCents,
        double score,
        String scoreUnit) {

    public static RouteDto of(RouteResult r) {
        return new RouteDto(
                r.pathString(),
                r.legs().stream().map(s -> FlightEvaluationDto.of(s.evaluation())).toList(),
                r.totalRevenueUsd(), r.totalPaxRevenueUsd(),
                r.totalAncillaryRevenueUsd(), r.totalCargoRevenueUsd(),
                r.totalCostUsd(), r.totalProfitUsd(),
                r.totalFuelKg(), r.totalCo2Kg(),
                r.totalPassengerCo2Kg(), r.totalCargoCo2Kg(), r.totalCo2PerPaxKg(),
                r.totalContributionMarginUsd(), r.exFuelCaskCents(),
                r.score().value(), r.score().unit().name());
    }
}
 