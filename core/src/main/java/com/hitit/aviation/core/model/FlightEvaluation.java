package com.hitit.aviation.core.model;
// flight ham girdi flightevaluation optimizerparamsa bağlı hesaplanmış sonuç olarak planladım
public record FlightEvaluation(
        Flight flight,
        double revenueUsd,
        double costUsd,
        double profitUsd,
        // gelir
        double paxRevenueUsd,        // bilet geliri
        double ancillaryRevenueUsd,  // yan gelir
        double cargoRevenueUsd,      // kargo geliri
        // gider
        double fuelCostUsd,
        double crewCostUsd,
        double ownershipCostUsd,     // sahiplik
        double maintenanceCostUsd,   // bakım
        double overheadCostUsd,      // genel gider idari istasyon 
        double navCostUsd,           // navigasyon ücreti
        double airportCostUsd,       // iniş , yer hizmeti
        // yakıt
        double fuelKg,
        double co2Kg,                // toplam
        double passengerCo2Kg,       // yolcuların
        double cargoCo2Kg,           // kargoların
        double co2PerPaxKg,          // yolcu başı 
        double co2PerEconomyPaxKg,
        double co2PerBusinessPaxKg,
        // kpi lar
        double ask,          // koltuk-km
        double rpk,          // yolcu-km
        double raskCents,    // gelir ASK
        double caskCents,    // maliyet
        double yieldCents,   // gelir RPK
        double breakEvenLoadFactor,   // BELF = CASK / Yield
        double breakEvenLoadFactorNetCargo,
        double fuelLitersPer100Pkm,  // yakıt verimliliği
        double passengerLoadFactor, //yolcu doluluk
        double cargoLoadFactor, //kargo doluluk
        double blockHours, //uçuş saati
        double avgFareUsd,
        
        double paskCents, //rask-cask, birim kar
        double exFuelCaskCents, // yakıt hariç maliyet (yapısal)
        double stageAdjustedCaskCents, //sefer mesafesine göre normalize cask, cask kısa uçuşlarda doğal olarak yüksektir, 
        // bu alan caskı referans mesafeye taşıyarak kısa/uzun legleri adil kıyaslar
        
        
       
        double cargoYieldPerTonneKm, //kargo verimi=kargo geliri/(taşınan ton*km)
        double cargoRevenueShare, //kargo gelirinin toplam gelire oranı
        double contributionMarginUsd, //katkı payı = gelir-değişken maliyetler (owner-overhead hariç, bir legin sabit maliyetlere ne kadar katkı bıraktığı
        double contributionMarginRatio, //katkı payı/gelir
        double departureDelayMinutes, //kalkış gecikmesi
        double arrivalDelayMinutes, //varış gecikmesi
        boolean onTimeArrival //zamanında varış: varış gecikmesi<=15
) {
	public FlightEvaluation {
		double revSum = paxRevenueUsd +ancillaryRevenueUsd + cargoRevenueUsd;
		double costSum = fuelCostUsd + crewCostUsd + ownershipCostUsd + maintenanceCostUsd + overheadCostUsd + navCostUsd + airportCostUsd;
		check(revenueUsd, revSum, "rev kırılımı toplamı tutmuyor");
		check(costUsd, costSum, "cost kırılımı toplamı tutmuyor");
		check(profitUsd, revenueUsd-costUsd, "profit=rev-cost değil");
		check(co2Kg, passengerCo2Kg + cargoCo2Kg, "co2 kırılımı tutmuyor");
	}
	private static void check(double actual, double expected, String msg) {
		if(Math.abs(actual-expected)>0.01) {
			throw new IllegalStateException(msg);
		}
	}
    public boolean profitable() {
        return profitUsd > 0;
    }
    public double netContributionMarginUsd() {return contributionMarginUsd;}
    public FlightDecision decision(double reviewBandUsd) {
    	return FlightDecision.of(netContributionMarginUsd(), reviewBandUsd);
    }
    public FlightDecision decision() {return decision(0);}

}
