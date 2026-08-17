package com.hitit.aviation.core.model;
// rota zincirinin toplamları
import java.util.List;

public record RouteResult(
        List<ScoredFlight> legs,
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
        Score score
) {
	
	public FlightDecision decision(double reviewBandUsd) { return FlightDecision.of(totalContributionMarginUsd, reviewBandUsd);}
	public FlightDecision decision() {return decision(0);}
	
    public String pathString() {
        if (legs.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder(legs.get(0).flight().from().code());
        for (ScoredFlight e : legs) {
            sb.append(" -> ").append(e.flight().to().code());
        }
        return sb.toString();
    }
}
