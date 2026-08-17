package com.hitit.aviation.core.model;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Optimizasyon parametreleri — <b>değiştirilemez (immutable)
 *Alanlar sorumluluklarına göre gruplanmıştır
 */
public final class OptimizerParams {

    public enum Objective {
        MAX_PROFIT,   // Proje 1: en kârlı rota
        MIN_CO2,      // Proje 2: karbon ayak izi minimum
        WEIGHTED      // karma model: kâr − karbon vergisi
    }

    /** Havayolunun ticari modeli. CUSTOM = profil ayrımı yok, genel ağırlıklar geçerli. */
    public enum AirlineProfile { CUSTOM, LCC, FULL_SERVICE }

    /** Bir profile ait KPI etki payları. Toplamlarının 1 olması şart değildir. */
    public record KpiWeights(double loadFactor, double rask, double cask) { }

    // ── Genel / operasyonel ──
    public final Objective objective;
    public final int minConnectMinutes;      // min aktarma süresi
    public final int maxConnectMinutes;      // maks aktarma süresi (0 = sınırsız); uzun beklemeleri budar
    public final int maxLegs;                // rota başına en fazla leg
    public final boolean sameAirlineOnly;    // true -> yalnız aynı havayoluyla aktarma (interline yok)
    public final double carbonPricePerTon;   // WEIGHTED modda karbon fiyatı (EUR~USD/ton)

    /**
     * Her AKTARMA için skordan düşülen $ tutarı (leg sayısı − 1 kez). Direkt uçuşu
     * eşit kârlı 2 leg'li rotaya tercih ettirir. 0 (varsayılan) -> kapalı.
     */
    public final double connectionPenalty;

    /**
     * OTP cezası: varış gecikmesinin her dakikası için skordan düşülen $. 0 -> kapalı;
     * gerçekleşen (actual) veri yoksa da uygulanmaz.
     */
    public final double otpDelayPenaltyPerMinute;

    // ── Gruplu alt-config'ler ──
    public final FuelParams fuel;
    public final EmissionParams emission;
    public final CostParams cost;
    public final KpiParams kpi;
    public final CohortParams cohort;
    public final DecisionParams decision;

    private OptimizerParams(Builder b) {
        this.objective = b.objective;
        this.minConnectMinutes = b.minConnectMinutes;
        this.maxConnectMinutes = b.maxConnectMinutes;
        this.maxLegs = b.maxLegs;
        this.sameAirlineOnly = b.sameAirlineOnly;
        this.carbonPricePerTon = b.carbonPricePerTon;
        this.connectionPenalty = b.connectionPenalty;
        this.otpDelayPenaltyPerMinute = b.otpDelayPenaltyPerMinute;

        // Grup İÇİ kurallar her alt-config'in kendi kurucusunda doğrulanır.
        this.fuel = b.fuel.build();
        this.emission = b.emission.build();
        this.cost = b.cost.build();
        this.kpi = b.kpi.build();
        this.cohort = b.cohort.build();
        this.decision = b.decision.build();

        // Yalnızca genel / gruplar ARASI kurallar burada.
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Tüm alanları varsayılan olan, geçerli bir parametre seti. */
    public static OptimizerParams defaults() {
        return builder().build();
    }

    /**
     * Genel ve gruplar arası kurallar. {@code private}: nesne zaten geçerli doğduğu
     * için dışarıdan çağrılmasına gerek yoktur.
     */
    private void validate() {
        if (maxLegs < 1) {
            throw new IllegalArgumentException("maxLegs must be greater than 0");
        }
        if (minConnectMinutes < 0) {
            throw new IllegalArgumentException("minConnectMinutes cannot be negative");
        }
        if (maxConnectMinutes < 0) {
            throw new IllegalArgumentException("maxConnectMinutes cannot be negative");
        }
        if (maxConnectMinutes > 0 && maxConnectMinutes < minConnectMinutes) {
            throw new IllegalArgumentException("maxConnectMinutes must be >= minConnectMinutes (or 0 for unlimited)");
        }
        if (carbonPricePerTon < 0) {
            throw new IllegalArgumentException("carbonPricePerTon cannot be negative");
        }
        if (connectionPenalty < 0) {
            throw new IllegalArgumentException("connectionPenalty cannot be negative");
        }
        if (otpDelayPenaltyPerMinute < 0) {
            throw new IllegalArgumentException("otpDelayPenaltyPerMinute cannot be negative");
        }
    }


    // Yakıt fiyatı, SAF harmanı ve yakıt-tahmin katsayıları + türetilmiş etkin değerler.
    public record FuelParams(
            double jetFuelPricePerKg,
            double safPricePerKg,
            double safBlendRatio,
            double safCo2Reduction,
            double co2PerKgFuel,
            double fuelKgPerSeatKm,
            double ltoFuelKg) {

        public FuelParams {
            if (safBlendRatio < 0 || safBlendRatio > 0.5) {
                throw new IllegalArgumentException("safBlendRatio must be between 0.0 and 0.5");
            }
            if (jetFuelPricePerKg < 0) {
                throw new IllegalArgumentException("jetFuelPricePerKg cannot be negative");
            }
            if (safPricePerKg < 0) {
                throw new IllegalArgumentException("safPricePerKg cannot be negative");
            }
            if (safCo2Reduction < 0 || safCo2Reduction > 1) {
                throw new IllegalArgumentException("safCo2Reduction must be between 0.0 and 1.0");
            }
            if (co2PerKgFuel < 0 || fuelKgPerSeatKm < 0 || ltoFuelKg < 0) {
                throw new IllegalArgumentException("fuel coefficients cannot be negative");
            }
        }

        /** Harmandaki SAF oranına göre etkin 1 kg yakıt maliyeti. */
        public double effectiveFuelPricePerKg() {
            return jetFuelPricePerKg * (1 - safBlendRatio) + safPricePerKg * safBlendRatio;
        }

        /** Harmandaki SAF oranına göre 1 kg yakıt başına etkin CO2. */
        public double effectiveCo2PerKgFuel() {
            return co2PerKgFuel * (1 - safBlendRatio * safCo2Reduction);
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private double jetFuelPricePerKg = 0.85;
            private double safPricePerKg     = 2.60;
            private double safBlendRatio     = 0.0;
            private double safCo2Reduction   = 0.80;
            private double co2PerKgFuel      = 3.16;
            private double fuelKgPerSeatKm   = 0.025;
            private double ltoFuelKg         = 0;

            public Builder jetFuelPricePerKg(double v) { this.jetFuelPricePerKg = v; return this; }
            public Builder safPricePerKg(double v)     { this.safPricePerKg = v; return this; }
            public Builder safBlendRatio(double v)     { this.safBlendRatio = v; return this; }
            public Builder safCo2Reduction(double v)   { this.safCo2Reduction = v; return this; }
            public Builder co2PerKgFuel(double v)      { this.co2PerKgFuel = v; return this; }
            public Builder fuelKgPerSeatKm(double v)   { this.fuelKgPerSeatKm = v; return this; }
            public Builder ltoFuelKg(double v)         { this.ltoFuelKg = v; return this; }

            public FuelParams build() {
                return new FuelParams(jetFuelPricePerKg, safPricePerKg, safBlendRatio,
                        safCo2Reduction, co2PerKgFuel, fuelKgPerSeatKm, ltoFuelKg);
            }
        }
    }

    // ICAO ICEC emisyon bölüşümü katsayıları (yolcu/kargo kütle payı).
    public record EmissionParams(
            double businessCabinWeight,
            double paxMassKg,
            double seatEquipmentMassKg) {

        public EmissionParams {
            if (businessCabinWeight < 0) {
                throw new IllegalArgumentException("businessCabinWeight cannot be negative");
            }
            if (paxMassKg < 0 || seatEquipmentMassKg < 0) {
                throw new IllegalArgumentException("mass parameters cannot be negative");
            }
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private double businessCabinWeight = 2.5;
            private double paxMassKg           = 100;
            private double seatEquipmentMassKg = 50;

            public Builder businessCabinWeight(double v) { this.businessCabinWeight = v; return this; }
            public Builder paxMassKg(double v)           { this.paxMassKg = v; return this; }
            public Builder seatEquipmentMassKg(double v) { this.seatEquipmentMassKg = v; return this; }

            public EmissionParams build() {
                return new EmissionParams(businessCabinWeight, paxMassKg, seatEquipmentMassKg);
            }
        }
    }

    // Maliyet varsayımları.
    public record CostParams(double crewCostPerBlockHour) {

        public CostParams {
            if (crewCostPerBlockHour < 0) {
                throw new IllegalArgumentException("crewCostPerBlockHour cannot be negative");
            }
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private double crewCostPerBlockHour = 1400;

            public Builder crewCostPerBlockHour(double v) { this.crewCostPerBlockHour = v; return this; }

            public CostParams build() { return new CostParams(crewCostPerBlockHour); }
        }
    }

    /**
     * Normalize KPI modeli: doluluk/RASK/CASK z-skora çevrilip [-1,+1]'e kırpılır,
     * ağırlık paylarıyla harmanlanır; bonus = kpiStrength × kpiIndex × filo ort.|kâr|.
     * Ağırlıkların toplamı 1 olmak zorunda değildir (kod paya çevirir).
     */
    public record KpiParams(
            double loadFactorWeight,
            double raskWeight,
            double caskWeight,
            double kpiStrength,
            double refStageLengthKm,
            Map<String, AirlineProfile> airlineProfiles,
            Map<AirlineProfile, KpiWeights> profileWeights) {

        public KpiParams {
            if (kpiStrength < 0) {
                throw new IllegalArgumentException("kpiStrength cannot be negative");
            }
            if (refStageLengthKm < 0) {
                throw new IllegalArgumentException("refStageLengthKm cannot be negative");
            }
            // Savunmacı kopya: çağıran elindeki Map'i sonradan değiştirse bile buradaki
            // kopya etkilenmez. Immutable'lık, içerideki koleksiyonlar da kopyalanmazsa
            // sızar — record tek başına bunu garanti etmez.
            airlineProfiles = airlineProfiles == null ? Map.of() : Map.copyOf(airlineProfiles);
            profileWeights = profileWeights == null ? Map.of() : Map.copyOf(profileWeights);
        }

        /** Uçuşun havayolu koduna karşılık gelen profil; tanımsızsa CUSTOM. */
        public AirlineProfile profileOf(String airlineCode) {
            if (airlineCode == null) return AirlineProfile.CUSTOM;
            return airlineProfiles.getOrDefault(airlineCode.trim().toUpperCase(), AirlineProfile.CUSTOM);
        }

        /** Profilin ağırlık seti; tanımlı değilse genel ağırlıklar. */
        public KpiWeights weightsFor(AirlineProfile profile) {
            KpiWeights w = profileWeights.get(profile);
            return w != null ? w : new KpiWeights(loadFactorWeight, raskWeight, caskWeight);
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private double loadFactorWeight = 0.34;
            private double raskWeight       = 0.33;
            private double caskWeight       = 0.33;
            private double kpiStrength      = 0.25;
            private double refStageLengthKm = 1500;
            private Map<String, AirlineProfile> airlineProfiles = Map.of();
            private Map<AirlineProfile, KpiWeights> profileWeights = Map.of();

            public Builder loadFactorWeight(double v) { this.loadFactorWeight = v; return this; }
            public Builder raskWeight(double v)       { this.raskWeight = v; return this; }
            public Builder caskWeight(double v)       { this.caskWeight = v; return this; }
            public Builder kpiStrength(double v)      { this.kpiStrength = v; return this; }
            public Builder refStageLengthKm(double v) { this.refStageLengthKm = v; return this; }

            public Builder airlineProfiles(Map<String, AirlineProfile> v) {
                this.airlineProfiles = v; return this;
            }

            public Builder profileWeights(Map<AirlineProfile, KpiWeights> v) {
                this.profileWeights = v; return this;
            }

            public KpiParams build() {
                return new KpiParams(loadFactorWeight, raskWeight, caskWeight, kpiStrength,
                        refStageLengthKm, airlineProfiles, profileWeights);
            }
        }
    }

    /**
     * Kohort segmentasyonu: z-skor istatistikleri (havayolu profili, sezon, gün tipi)
     * kohortlarında hesaplanır; dar kohortta örneklem yetersizse üst seviyeye çıkılır.
     * Kapalıyken yalnız profile göre gruplanır (eski davranış).
     *
     * @param minCohortSize bir kohortun istatistikleri için gereken asgari uçuş sayısı
     *                      (sektörde ~30)
     */
    public record CohortParams(boolean cohortNormalization, int minCohortSize) {

        public CohortParams {
            if (minCohortSize < 1) {
                throw new IllegalArgumentException("minCohortSize must be greater than 0");
            }
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private boolean cohortNormalization = true;
            private int minCohortSize = 30;

            public Builder cohortNormalization(boolean v) { this.cohortNormalization = v; return this; }
            public Builder minCohortSize(int v)           { this.minCohortSize = v; return this; }

            public CohortParams build() { return new CohortParams(cohortNormalization, minCohortSize); }
        }
    }

    /**
     * İptal/devam kararı (bkz. {@link FlightDecision}) için netCM ≈ 0 tolerans bandı ($).
     * Skoru ETKİLEMEZ. (Karar bandı API/UI'da ayrıca parametre olarak da verilebilir.)
     */
    public record DecisionParams(double reviewBandUsd) {

        public DecisionParams {
            if (reviewBandUsd < 0) {
                throw new IllegalArgumentException("reviewBandUsd cannot be negative");
            }
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private double reviewBandUsd = 0.0;

            public Builder reviewBandUsd(double v) { this.reviewBandUsd = v; return this; }

            public DecisionParams build() { return new DecisionParams(reviewBandUsd); }
        }
    }


    /**
     * {@link OptimizerParams} kurucusu. Alt-config'ler "yapılandırıcı lambda" ile
     * verilir
     */
    public static final class Builder {

        private Objective objective = Objective.MAX_PROFIT;
        private int minConnectMinutes = 45;
        private int maxConnectMinutes = 0;
        private int maxLegs = 3;
        private boolean sameAirlineOnly = false;
        private double carbonPricePerTon = 90;
        private double connectionPenalty = 0;
        private double otpDelayPenaltyPerMinute = 0.0;

        private final FuelParams.Builder fuel = FuelParams.builder();
        private final EmissionParams.Builder emission = EmissionParams.builder();
        private final CostParams.Builder cost = CostParams.builder();
        private final KpiParams.Builder kpi = KpiParams.builder();
        private final CohortParams.Builder cohort = CohortParams.builder();
        private final DecisionParams.Builder decision = DecisionParams.builder();

        private Builder() { }

        public Builder objective(Objective v)             { this.objective = v; return this; }
        public Builder minConnectMinutes(int v)           { this.minConnectMinutes = v; return this; }
        public Builder maxConnectMinutes(int v)           { this.maxConnectMinutes = v; return this; }
        public Builder maxLegs(int v)                     { this.maxLegs = v; return this; }
        public Builder sameAirlineOnly(boolean v)         { this.sameAirlineOnly = v; return this; }
        public Builder carbonPricePerTon(double v)        { this.carbonPricePerTon = v; return this; }
        public Builder connectionPenalty(double v)        { this.connectionPenalty = v; return this; }
        public Builder otpDelayPenaltyPerMinute(double v) { this.otpDelayPenaltyPerMinute = v; return this; }

        public Builder fuel(Consumer<FuelParams.Builder> c)         { c.accept(fuel); return this; }
        public Builder emission(Consumer<EmissionParams.Builder> c) { c.accept(emission); return this; }
        public Builder cost(Consumer<CostParams.Builder> c)         { c.accept(cost); return this; }
        public Builder kpi(Consumer<KpiParams.Builder> c)           { c.accept(kpi); return this; }
        public Builder cohort(Consumer<CohortParams.Builder> c)     { c.accept(cohort); return this; }
        public Builder decision(Consumer<DecisionParams.Builder> c) { c.accept(decision); return this; }

        /**
         * Doğrulanmış, değiştirilemez parametre setini üretir.
         *
         * @throws IllegalArgumentException herhangi bir ayar geçersizse
         */
        public OptimizerParams build() {
            return new OptimizerParams(this);
        }
    }
}
 