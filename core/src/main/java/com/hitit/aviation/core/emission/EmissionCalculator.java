package com.hitit.aviation.core.emission;
//project 2 karbon ayak izi
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.OptimizerParams;

public class EmissionCalculator {

    private static final double JET_FUEL_DENSITY_KG_PER_L = 0.8;
// ICAO GCD düzeltmesi rota sapmaları için mesafeye ekleme yapılır
    public static double correctedDistanceKm(double gcdKm) {
        if (gcdKm < 550) return gcdKm + 50;
        if (gcdKm <= 5500) return gcdKm + 100;
        return gcdKm + 125;
    }

    public double fuelKg(Flight f, OptimizerParams p) {
        if(f.fuelKg() > 0) return f.fuelKg();
        return p.fuel.ltoFuelKg() + f.seats()*f.distanceKm()*p.fuel.fuelKgPerSeatKm();
    }

    // Uçuşun toplam CO2'si (yolcu + kargo, tüm uçak)
    public double co2Kg(Flight f, OptimizerParams p) {
        return fuelKg(f,p) * p.fuel.effectiveCo2PerKgFuel();
    }

    //yolcu / kargo kütle bölüşümü

    public double economyPax(Flight f, OptimizerParams p) {
        return f.econPax();
    }

    public double businessPax(Flight f, OptimizerParams p) {
        return f.busPax();
    }

    public double totalPax(Flight f, OptimizerParams p) {
        return f.totalPax();
    }

    // Taşınan kargo kütlesi
    public double cargoKg(Flight f) {
        return f.cargoKg();
    }

    // Toplam emisyondan yolcuya düşen pay (0..1); geri kalanı kargonun
    public double passengerMassShare(Flight f, OptimizerParams p) {
        double paxMass = f.totalPax() * p.emission.paxMassKg() + f.seats() * p.emission.seatEquipmentMassKg();
        double cargoMass = f.cargoKg();
        double denom = paxMass + cargoMass;
        return denom <= 0 ? 1.0 : paxMass / denom;
    }

    // yolculara atfedilen CO2 (kg) — kargo payı düşülmüş
    public double passengerCo2Kg(Flight f, OptimizerParams p) {
        return co2Kg(f, p) * passengerMassShare(f, p);
    }

    // Kargoya atfedilen CO2 (kg)
    public double cargoCo2Kg(Flight f, OptimizerParams p) {
        return co2Kg(f, p) * (1 - passengerMassShare(f, p));
    }

    //Kabin sınıfı ağırlıklandırması 

    private double weightedPaxUnits(Flight f, OptimizerParams p) {
        return f.econPax() + f.busPax() * p.emission.businessCabinWeight();
    }

    /** Ekonomi yolcu başına CO2 (kg). */
    public double co2PerEconomyPaxKg(Flight f, OptimizerParams p) {
        double units = weightedPaxUnits(f, p);
        return units <= 0 ? 0 : passengerCo2Kg(f, p) / units;   // ekonomi ağırlığı = 1.0
    }

    /** Business yolcu başına CO2 (kg). */
    public double co2PerBusinessPaxKg(Flight f, OptimizerParams p) {
        double units = weightedPaxUnits(f, p);
        return units <= 0 ? 0 : passengerCo2Kg(f, p) * p.emission.businessCabinWeight() / units;
    }

    /**
     * Geriye dönük uyumluluk: kabin/kargo ayrımı gözetmeksizin "ortalama" yolcu
     * başı CO2 (yolcuya atfedilen CO2 / toplam yolcu)
     */
    public double co2PerPaxKg(Flight f, OptimizerParams p) {
        double pax = f.totalPax();
        return pax <= 0 ? 0 : passengerCo2Kg(f, p) / pax;
    }

    /** Yakıt verimliliği: litre / 100 yolcu-km. Modern uçaklarda ~2-3 L. */
    public double fuelPer100PaxKm(Flight f, OptimizerParams p) {
        double fuelLiters = fuelKg(f,p) / JET_FUEL_DENSITY_KG_PER_L;
        double paxKm = f.totalPax() * f.distanceKm();
        return paxKm <= 0 ? 0 : fuelLiters / paxKm * 100.0;
    }
}
