package com.hitit.aviation.core.finance;

import com.hitit.aviation.core.emission.EmissionCalculator;
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.FlightEvaluation;
import com.hitit.aviation.core.model.OptimizerParams;

public class ProfitCalculator {

    private final EmissionCalculator emission;

    public ProfitCalculator(EmissionCalculator emission) {
        this.emission = emission;
    }

    public FlightEvaluation evaluate(Flight f, OptimizerParams p) {

        double econPax = f.econPax();
        double busPax = f.busPax();
        double pax = econPax + busPax;

        //gelir
        double paxRevenue = f.paxRevenueUsd();
        double ancillaryRevenue = f.ancillaryRevenueUsd();
        double cargoRevenue = f.cargoRevenueUsd();
        double revenue = paxRevenue + ancillaryRevenue + cargoRevenue;

        //gider
        double fuelKg = emission.fuelKg(f, p);
        double fuelCost = fuelKg * p.fuel.effectiveFuelPricePerKg();
        double crewCost = f.crewCostUsd() > 0 ? f.crewCostUsd() : f.blockHours()*p.cost.crewCostPerBlockHour();
        double ownershipCost = f.ownershipCostUsd();
        double maintenanceCost = f.maintenanceCostUsd();
        double overheadCost = f.overheadCostUsd();
        double navCost = f.navCostUsd();
        double airportCost = f.airportCostUsd();
        double cost = fuelCost + crewCost + ownershipCost + maintenanceCost
                + overheadCost + navCost + airportCost;

        double profit = revenue - cost;

        // --- Emisyon (yolcu/kargo bölüşümü + kabin ağırlıklandırma) ---
        double co2 = emission.co2Kg(f, p);
        double paxCo2 = emission.passengerCo2Kg(f, p);
        double cargoCo2 = emission.cargoCo2Kg(f, p);
        double co2PerPax = emission.co2PerPaxKg(f, p);
        double co2PerEcon = emission.co2PerEconomyPaxKg(f, p);
        double co2PerBus = emission.co2PerBusinessPaxKg(f, p);

        // --- KPI ---
        double ask = f.seats() * f.distanceKm();
        double rpk = pax * f.distanceKm();
        double rask = ask <= 0 ? 0 : revenue / ask * 100;   // cent
        double cask = ask <= 0 ? 0 : cost / ask * 100;      // cent
        double paxYieldRevenue = paxRevenue + ancillaryRevenue;
        double yield = rpk <= 0 ? 0 : paxYieldRevenue / rpk * 100;  // cent
        double belf = yield <= 0 ? 0 : cask / yield;        // başabaş doluluk
        double caskNetCargo = ask <= 0 ? 0 :(cost-cargoRevenue)/ask*100;
        double belfNetCargo = yield <= 0 ? 0: caskNetCargo/yield;
        double fuelEff = emission.fuelPer100PaxKm(f, p);
        
        double paxLoadFactor = f.seats() > 0 ? pax / f.seats() : 0;
        double cargoLoadFactor = f.cargoLoadFactor();
        double blockHours = f.blockHours();
        
        double pask = rask - cask; //birim kar
        double exFuelCask = ask <= 0 ? 0 : (cost-fuelCost)/ask*100; //yakıt hariç cask
        double stageAdjCask = (ask <= 0 || p.kpi.refStageLengthKm() <= 0 || f.distanceKm() <= 0) ? cask : cask*Math.sqrt(f.distanceKm()/p.kpi.refStageLengthKm());
        //sefer mesafesine göre normalize cask
        
        
        double avgFare = f.avgFareUsd(); //ort bilet ücreti
        
        double cargoTonneKm = f.cargoKg() / 1000.0 * f.distanceKm();
        double cargoYield = cargoTonneKm <= 0 ? 0 : cargoRevenue / cargoTonneKm;

        // Kargo gelirinin toplam gelire payı (0..1).
        double cargoRevShare = revenue <= 0 ? 0 : cargoRevenue / revenue;

        // Katkı payı = gelir − değişken maliyetler (yakıt+ekip+bakım+nav+havaalanı).
        // Sahiplik ve overhead sabit kabul edilip hariç tutulur.
        double contributionMargin = revenue
                - (fuelCost + crewCost + maintenanceCost + navCost + airportCost);
        double contributionMarginRatio = revenue <= 0 ? 0 : contributionMargin / revenue;

        // Zamanındalık (OTP): gerçekleşen (actual) veri varsa gecikme dakikaları,
        // yoksa NaN. Zamanında varış = varış gecikmesi ≤ 15 dk (endüstri A15 tanımı).
        double depDelay = f.depDelayMinutes().isPresent() ? f.depDelayMinutes().getAsLong() : Double.NaN;
        double arrDelay = f.arrDelayMinutes().isPresent() ? f.arrDelayMinutes().getAsLong() : Double.NaN;
        boolean onTime = !Double.isNaN(arrDelay) && arrDelay <= 15;

        return new FlightEvaluation(
                f, revenue, cost, profit,
                paxRevenue, ancillaryRevenue, cargoRevenue,
                fuelCost, crewCost, ownershipCost, maintenanceCost,
                overheadCost, navCost, airportCost,
                fuelKg, co2, paxCo2, cargoCo2, co2PerPax, co2PerEcon, co2PerBus,
                ask, rpk, rask, cask, yield, belf, belfNetCargo, fuelEff,
                paxLoadFactor, cargoLoadFactor, blockHours, avgFare, pask, exFuelCask, stageAdjCask, cargoYield, cargoRevShare,
                contributionMargin, contributionMarginRatio, depDelay, arrDelay, onTime);
    }
     
}