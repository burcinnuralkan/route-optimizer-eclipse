package com.hitit.aviation.core.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.hitit.aviation.core.finance.ProfitCalculator;
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.FlightEvaluation;
import com.hitit.aviation.core.model.OptimizerParams;
import com.hitit.aviation.core.model.RouteResult;
import com.hitit.aviation.core.model.Score;
import com.hitit.aviation.core.model.ScoredFlight;

public class RouteOptimizer {

    private final ProfitCalculator calc;
    
    public RouteOptimizer(ProfitCalculator calc) {
       this.calc = calc;
    }
    /**
     * Filo istatistikleri: KPI'ları z-skora çevirmek için ortalama+std, bonusu
     * dolara ölçeklemek için de ortalama |kâr|. NaN std -> 0 kabul edilir.
     *
     * <p>CASK istatistikleri SEFER MESAFESİNE GÖRE NORMALİZE edilmiş CASK
     * üzerinden tutulur (bkz. {@link #kpiBonus}).
     */
    public record FleetStats(
            double lfMean, double lfStd,
            double raskMean, double raskStd,
            double stageAdjCaskMean, double stageAdjCaskStd,
            double profitScale) {}

    public Score score(FlightEvaluation e, OptimizerParams p) {
        return score(e, p, null);
    }

    /**
     * Normalize KPI'lı skor. stats null verilirse KPI bonusu uygulanmaz
     * (skor = saf kâr / hedefe göre)
     */
    public Score score(FlightEvaluation e, OptimizerParams p, FleetStats stats) {
        return switch (p.objective) {
            case MAX_PROFIT -> Score.usd(e.profitUsd())
                    .plus(kpiBonus(e, p, stats))
                    .minus(otpPenalty(e, p));
            // Birim kg CO2: dolar cinsinden bonus/ceza terimleri EKLENMEZ.
            case MIN_CO2    -> Score.kgCo2(-e.co2Kg());
            case WEIGHTED   -> Score.usd(e.profitUsd() - p.carbonPricePerTon * e.co2Kg() / 1000.0)
                    .plus(kpiBonus(e, p, stats))
                    .minus(otpPenalty(e, p));
        };
    }

    /** Bir amacın skorunun birimi. */
    public static Score.Unit scoreUnit(OptimizerParams.Objective objective) {
        return objective == OptimizerParams.Objective.MIN_CO2 ? Score.Unit.KG_CO2 : Score.Unit.USD;
    }

    /**
     * Normalize KPI bonusu (dolar). Doluluk, RASK ve CASK filo içinde z-skora
     * çevrilir, [-1,+1]'e kırpılır (±1 std), ağırlık PAYLARIYLA harmanlanıp
     * kpiIndex ∈ [-1,+1] elde edilir. Bonus = kpiStrength · kpiIndex · filo
     * ort.|kâr|. Bonus uçuşun KENDİ geliriyle değil filo ölçeğiyle hesaplandığı
     * için gerçek kârla çakışmaz (double-count yok). CASK'ta düşük iyi olduğundan
     * işareti terslenir.
     *
     * <p>CASK için HAM değil SEFER-MESAFESİ NORMALİZE edilmiş CASK kullanılır.
     * CASK mesafe arttıkça kendiliğinden düşer (sefer başına sabit maliyetler
     * daha çok koltuk-km'ye yayılır); ham CASK ile kıyaslamak verimliliği değil
     * ağ yapısını kıyaslar ve kısa bacakları haksız cezalandırır. Normalizasyon
     * sektör standardıdır (CASK × √(mesafe / referans mesafe)).
     */
    private Score kpiBonus(FlightEvaluation e, OptimizerParams p, FleetStats s) {
        if (s == null || p.kpi.kpiStrength() == 0) return Score.usd(0);

        // Ağırlıklar havayolunun ticari modeline (LCC / full-service) göre
        // farklılaşabilir. Profil tanımlı değilse genel ağırlıklar kullanılır.
        OptimizerParams.KpiWeights weights =
                p.kpi.weightsFor(p.kpi.profileOf(e.flight().airlineCode()));

        double wLf = Math.max(0, weights.loadFactor());
        double wRk = Math.max(0, weights.rask());
        double wCk = Math.max(0, weights.cask());
        double wSum = wLf + wRk + wCk;
        if (wSum <= 0) return Score.usd(0);

        double zLf = clamp(z(e.passengerLoadFactor(),     s.lfMean(),            s.lfStd()));
        double zRk = clamp(z(e.raskCents(),               s.raskMean(),          s.raskStd()));
        double zCk = clamp(z(e.stageAdjustedCaskCents(),  s.stageAdjCaskMean(),  s.stageAdjCaskStd()));

        double kpiIndex = (wLf * zLf + wRk * zRk + wCk * (-zCk)) / wSum; // [-1,+1]
        return Score.usd(p.kpi.kpiStrength() * kpiIndex * s.profitScale());
    }

    /** OTP cezası ($): yalnız gerçekleşen veri + pozitif varış gecikmesi olduğunda. */
    private Score otpPenalty(FlightEvaluation e, OptimizerParams p) {
        if (p.otpDelayPenaltyPerMinute <= 0 || Double.isNaN(e.arrivalDelayMinutes())) return Score.usd(0);
        return Score.usd(p.otpDelayPenaltyPerMinute * Math.max(0, e.arrivalDelayMinutes()));
    }

    /**
     * Rota aramasında her aktarmadan düşülecek ceza, SKORUN biriminde.
     *
     * <p>{@code connectionPenalty} dolar cinsindendir; MIN_CO2 modunda skorun birimi
     * kg CO2 olduğu için uygulanamaz ve 0 döner.
     */
    private static double connectionPenaltyIn(Score.Unit unit, OptimizerParams p) {
        return unit == Score.Unit.USD ? p.connectionPenalty : 0;
    }
    private static double z(double v, double mean, double std) {
        return std <= 0 ? 0 : (v - mean) / std;
    }
    private static double clamp(double x) {
        return x < -1 ? -1 : (x > 1 ? 1 : x);
    }

    /** Filo istatistiklerini (ortalama+std+kâr ölçeği) değerlendirmelerden üretir. */
    private static FleetStats computeStats(FlightEvaluation[] eval) {
        int n = eval.length;
        if (n == 0) return new FleetStats(0,0,0,0,0,0,0);
        double lfSum = 0, rkSum = 0, ckSum = 0, absProfitSum = 0;
        for (FlightEvaluation e : eval) {
            lfSum += e.passengerLoadFactor();
            rkSum += e.raskCents();
            ckSum += e.stageAdjustedCaskCents();
            absProfitSum += Math.abs(e.profitUsd());
        }
        double lfMean = lfSum / n, rkMean = rkSum / n, ckMean = ckSum / n;
        double lfVar = 0, rkVar = 0, ckVar = 0;
        for (FlightEvaluation e : eval) {
            lfVar += sq(e.passengerLoadFactor()    - lfMean);
            rkVar += sq(e.raskCents()              - rkMean);
            ckVar += sq(e.stageAdjustedCaskCents() - ckMean);
        }
        double profitScale = absProfitSum / n;
        return new FleetStats(
                lfMean, Math.sqrt(lfVar / n),
                rkMean, Math.sqrt(rkVar / n),
                ckMean, Math.sqrt(ckVar / n),
                profitScale);
    }
    private static double sq(double x) { return x * x; }
    public Optional<RouteResult> bestRoute(List<Flight> schedule, String from, String to, OptimizerParams p){
       List<RouteResult> all = bestRoutesPerState(schedule, from, to, p);
       return all.isEmpty() ? Optional.empty(): Optional.of(all.get(0));
    }


    /**
     * Tarifenin TAMAMINI rota araması yapmadan değerlendirir: her uçuş için
     * gelir/gider/KPI değerlendirmesi ve bacak skoru döner. Kalkış/varış
     * seçilmediğinde "tüm veriyi göster" görünümünü besler.
     *
     * <p>Skorlar {@link #bestRoutesPerState} ile AYNI hattan geçer (aynı kohort
     * istatistikleri, aynı KPI bonusu, aynı OTP cezası), dolayısıyla buradaki
     * bacak skoru rota aramasında o bacağa verilen skorla birebir aynıdır.
     * Sonuç kalkış saatine göre sıralıdır.
     */
    public List<ScoredFlight> evaluateFleet(List<Flight> schedule, OptimizerParams p) {
       List<Flight> flights = sortedByDeparture(schedule);
       FlightEvaluation[] eval = evaluateAll(flights, p);
       double[] w = scoreAll(eval, p);
       Score.Unit unit = scoreUnit(p.objective);

       List<ScoredFlight> out = new ArrayList<>(eval.length);
       for (int i = 0; i < eval.length; i++) {
          out.add(new ScoredFlight(eval[i], new Score(w[i], unit)));
       }
       return out;
    }
    /** "Boş" DP hücresi: bu (uçuş, bacak sayısı) durumuna hiç ulaşılamadı. */
    private static final double NEG = Double.NEGATIVE_INFINITY;

    /**
     * Rota aramasının ana akışı. Dört aşama, her biri kendi metodunda:
     * değerlendir -> skorla -> DP tablosunu doldur -> sonuçları topla.
     *
     * <p>Ayrı bir doğrulama adımı yoktur: {@link OptimizerParams} değiştirilemez ve
     * yalnızca geçerli hâlde kurulabilir (bkz. {@code OptimizerParams.Builder#build}),
     * dolayısıyla buraya geçersiz bir parametre seti ulaşamaz.
     */
    public List<RouteResult> bestRoutesPerState(List<Flight> schedule, String from, String to, OptimizerParams p){

       String origin = normalizeCode(from);
       String destination = normalizeCode(to);

       List<Flight> flights = sortedByDeparture(schedule);
       FlightEvaluation[] eval = evaluateAll(flights, p);
       double[] w = scoreAll(eval, p);
       Score.Unit unit = scoreUnit(p.objective);
       DpTable table = runDp(flights, w, origin, p, connectionPenaltyIn(unit, p));

       return collectResults(flights, eval, w, table, destination, unit);
    }

    /** Havalimanı kodu: baş/son boşluk atılır, büyük harfe çevrilir. */
    private static String normalizeCode(String code) {
       return code.trim().toUpperCase();
    }

    /**
     * Tarifenin kalkış saatine göre sıralı KOPYASI. Kopya olması önemli:
     * çağıranın listesi yerinde değiştirilmez.
     *
     * <p>Sıralama aynı zamanda grafın topolojik sırasıdır — bağlantı yalnızca
     * ileri zamanda kurulabildiği için DP tek geçişte doğru sonucu verir.
     * TimSort kararlıdır: aynı kalkış saatli uçuşlar girdi sırasını korur,
     * böylece sonuçlar deterministik kalır.
     */
    private static List<Flight> sortedByDeparture(List<Flight> schedule) {
       List<Flight> flights = new ArrayList<>(schedule);
       flights.sort(Comparator.comparing(Flight::schedDep));
       return flights;
    }

    /** Her uçuşun gelir/gider/KPI değerlendirmesi. */
    private FlightEvaluation[] evaluateAll(List<Flight> flights, OptimizerParams p) {
       FlightEvaluation[] eval = new FlightEvaluation[flights.size()];
       for (int i = 0; i < eval.length; i++) {
          eval[i] = calc.evaluate(flights.get(i), p);
       }
       return eval;
    }

    /**
     * Bacak skorları. İKİ AYRI GEÇİŞ gerekir ve birleştirilemez: filo
     * istatistikleri (KPI bonusunun kıyas çizgisi) TÜM değerlendirmeler
     * bittikten sonra hesaplanabilir, skorlama ise o istatistiklere muhtaçtır.
     *
     * <p>İstatistikler KOHORT BAZINDA hesaplanır (bkz. {@link #fleetStatsPerFlight}):
     * bir uçuş yalnızca karşılaştırılabilir uçuşlarla (aynı havayolu profili,
     * sezon ve gün tipi) kıyaslanmalıdır. Aksi halde z-skor sezonsal/haftalık
     * olarak yanlıdır (kış uçuşları haksız düşük, yaz uçuşları haksız yüksek
     * skorlanır). Küçük/homojen tarifelerde tüm uçuşlar tek kohorta düşer ve
     * sonuç eski (kohortsuz) davranışla birebir aynı olur.
     *
     * <p>Ham {@code double} döner: tek bir aramada tüm skorların birimi aynıdır
     * (amaç sabittir), o yüzden birim dizinin her hücresinde tekrar taşınmaz —
     * dışarıda bir kez {@link Score}'a sarılır.
     */
    private double[] scoreAll(FlightEvaluation[] eval, OptimizerParams p) {
       FleetStats[] stats = fleetStatsPerFlight(eval, p);
       double[] w = new double[eval.length];
       for (int i = 0; i < eval.length; i++) {
          w[i] = score(eval[i], p, stats[i]).value();
       }
       return w;
    }

    /** z-skor normalizasyonu için kohort anahtarı. dayType null -> yalnız (profil,sezon). */
    private record CohortKey(OptimizerParams.AirlineProfile profile, Season season, DayType dayType) {}

    private enum Season {
       WINTER, SPRING, SUMMER, AUTUMN;
       static Season of(java.time.LocalDate d) {
          return switch (d.getMonth()) {
             case DECEMBER, JANUARY, FEBRUARY -> WINTER;
             case MARCH, APRIL, MAY           -> SPRING;
             case JUNE, JULY, AUGUST          -> SUMMER;
             default                          -> AUTUMN;
          };
       }
    }

    private enum DayType {
       WEEKDAY, WEEKEND;
       static DayType of(java.time.LocalDate d) {
          return switch (d.getDayOfWeek()) {
             case SATURDAY, SUNDAY -> WEEKEND;
             default               -> WEEKDAY;
          };
       }
    }

    /**
     * Her uçuşa, kendi kohortundan hesaplanmış filo istatistiklerini eşler.
     *
     * <p><b>Hiyerarşik daraltma:</b> uçuşun en dar kohortu (profil,sezon,gün)
     * yeterli örnekleme sahipse (n ≥ {@code minCohortSize}) o kullanılır; değilse
     * sırayla (profil,sezon) ve (profil) seviyelerine çıkılır. En geniş seviye
     * profildir; kohort segmentasyonu kapalıyken (veya tarife küçükken) tüm
     * uçuşlar profil seviyesine düşer ve sonuç eski davranışla aynıdır.
     */
    private static FleetStats[] fleetStatsPerFlight(FlightEvaluation[] eval, OptimizerParams p) {

       // En geniş seviye (profil) her zaman gerekli: hem kohortsuz mod hem de
       // hiyerarşik daraltmanın son durağı.
       Map<OptimizerParams.AirlineProfile, List<FlightEvaluation>> byProfile = new HashMap<>();
       for (FlightEvaluation e : eval) {
          byProfile.computeIfAbsent(p.kpi.profileOf(e.flight().airlineCode()), k -> new ArrayList<>()).add(e);
       }

       FleetStats[] out = new FleetStats[eval.length];
       Map<List<FlightEvaluation>, FleetStats> cache = new java.util.IdentityHashMap<>();

       if (!p.cohort.cohortNormalization()) {
          for (int i = 0; i < eval.length; i++) {
             List<FlightEvaluation> cohort = byProfile.get(p.kpi.profileOf(eval[i].flight().airlineCode()));
             out[i] = cache.computeIfAbsent(cohort, c -> computeStats(c.toArray(new FlightEvaluation[0])));
          }
          return out;
       }

       // Dar kohortlar: (profil,sezon,gün) ve ara seviye (profil,sezon).
       Map<CohortKey, List<FlightEvaluation>> byFull = new HashMap<>();
       Map<CohortKey, List<FlightEvaluation>> bySeason = new HashMap<>();
       for (FlightEvaluation e : eval) {
          OptimizerParams.AirlineProfile pr = p.kpi.profileOf(e.flight().airlineCode());
          Season s = Season.of(e.flight().flightDate());
          DayType d = DayType.of(e.flight().flightDate());
          byFull.computeIfAbsent(new CohortKey(pr, s, d), k -> new ArrayList<>()).add(e);
          bySeason.computeIfAbsent(new CohortKey(pr, s, null), k -> new ArrayList<>()).add(e);
       }

       for (int i = 0; i < eval.length; i++) {
          FlightEvaluation e = eval[i];
          OptimizerParams.AirlineProfile pr = p.kpi.profileOf(e.flight().airlineCode());
          Season s = Season.of(e.flight().flightDate());
          DayType d = DayType.of(e.flight().flightDate());

          List<FlightEvaluation> cohort = byFull.get(new CohortKey(pr, s, d));
          if (cohort == null || cohort.size() < p.cohort.minCohortSize()) {
             cohort = bySeason.get(new CohortKey(pr, s, null));
          }
          if (cohort == null || cohort.size() < p.cohort.minCohortSize()) {
             cohort = byProfile.get(pr);
          }
          out[i] = cache.computeIfAbsent(cohort, c -> computeStats(c.toArray(new FlightEvaluation[0])));
       }
       return out;
    }

    /**
     * Doldurulmuş DP tablosu.
     *
     * @param score  score[i][k] = i. uçuşla biten, k bacaklı en iyi rotanın skoru
     * @param parent parent[i][k] = o rotada i'den bir önceki uçuşun indeksi (-1 = yok)
     */
    private record DpTable(double[][] score, int[][] parent, int maxLegs) {}

    /**
     * Zaman-genişletilmiş DAG üzerinde en yüksek skorlu yol (dinamik programlama).
     * Graf çevrimsizdir çünkü bağlantı yalnızca ileri zamanda kurulabilir; bu
     * sayede genelde NP-zor olan "en uzun yol" problemi polinom zamanda çözülür.
     *
     * <p>Tablo başarım için çıplak {@code double} tutar; birim, tüm hücrelerde aynı
     * olduğu için tek seferde dışarıda ({@link Score}) taşınır.
     *
     * @param connectionPenalty aktarma başına düşülecek ceza, SKORUN biriminde
     *        (bkz. {@link #connectionPenaltyIn})
     */
    private static DpTable runDp(List<Flight> flights, double[] w, String origin,
          OptimizerParams p, double connectionPenalty) {

       int n = flights.size();
       int maxLegs = p.maxLegs;              // validate() maxLegs >= 1 garantiler

       double[][] dp = new double[n][maxLegs + 1];
       int[][] parent = new int[n][maxLegs + 1];
       for (int i = 0; i < n; i++) {
          java.util.Arrays.fill(dp[i], NEG);
          java.util.Arrays.fill(parent[i], -1);
       }

       // Havalimanı kodu -> oraya İNEN uçuşların indeksleri. i işlendikten SONRA
       // eklendiği için kova, i'ye geldiğimizde tam olarak j < i olanları içerir;
       // böylece hem j < i kontrolü hem de tüm çiftleri tarama ihtiyacı kalkar.
       // Tarama O(n²) yerine gerçek bağlantı sayısı O(E) kadar olur.
       Map<String, List<Integer>> arrivalsAt = new HashMap<>();

       for (int i = 0; i < n; i++) {
          Flight fi = flights.get(i);

          // Başlangıç: kalkış havalimanından hareket eden uçuş tek başına 1 bacaklı rotadır.
          if (fi.from().code().equals(origin)) {
             dp[i][1] = w[i];
          }

          for (int j : arrivalsAt.getOrDefault(fi.from().code(), List.of())) {
             if (!canConnect(flights.get(j), fi, p)) continue;

             for (int k = 1; k < maxLegs; k++) {
                if (dp[j][k] == NEG) continue;
                // Her aktarma sabit bir ceza yer: eşit kârda direkt rota kazanır.
                double cand = dp[j][k] + w[i] - connectionPenalty;
                if (cand > dp[i][k + 1]) {
                   dp[i][k + 1] = cand;
                   parent[i][k + 1] = j;
                }
             }
          }

          // i artık "geçmiş" sayılır; vardığı havalimanının kovasına eklenir.
          arrivalsAt.computeIfAbsent(fi.to().code(), key -> new ArrayList<>()).add(i);
       }

       return new DpTable(dp, parent, maxLegs);
    }

    /**
     * {@code before} bacağından {@code after} bacağına aktarma yapılabilir mi?
     * Şartlar: mekân sürekliliği, (varsa) aynı havayolu, ve aktarma boşluğunun
     * [minConnectMinutes, maxConnectMinutes] aralığında olması. Süre sınırları
     * DAHİL geçerlidir; maxConnectMinutes = 0 üst sınır yok demektir.
     */
    private static boolean canConnect(Flight before, Flight after, OptimizerParams p) {
       if (!before.to().code().equals(after.from().code())) return false;
       if (p.sameAirlineOnly && !before.airlineCode().equals(after.airlineCode())) return false;
       long gap = java.time.Duration.between(before.schedArr(), after.schedDep()).toMinutes();
       if (gap < p.minConnectMinutes) return false;
       return p.maxConnectMinutes <= 0 || gap <= p.maxConnectMinutes;
    }

    /**
     * Varış havalimanına ulaşan tüm durumlardan rotaları kurar, aynı
     * havalimanından iki kez geçenleri eler, skora göre azalan sıralar.
     */
    private List<RouteResult> collectResults(List<Flight> flights, FlightEvaluation[] eval,
          double[] w, DpTable table, String destination, Score.Unit unit) {

       List<RouteResult> results = new ArrayList<>();

       for (int i = 0; i < flights.size(); i++) {
          if (!flights.get(i).to().code().equals(destination)) continue;

          for (int k = 1; k <= table.maxLegs(); k++) {
             if (table.score()[i][k] == NEG) continue;
             RouteResult r = buildResult(eval, w, unit, table.parent(), i, k,
                   new Score(table.score()[i][k], unit));
             if (hasRepeatedAirport(r.legs())) continue;
             results.add(r);
          }
       }

       // Birincil: skor azalan. İkincil (tie-break): eşit skorda yapısal birim
       // maliyeti (yakıt-hariç CASK) DÜŞÜK olan rota önce gelir — yakıt fiyatı
       // oynaklığından arındırılmış, daha verimli maliyet yapısı yeğlenir.
       //
       // Tek aramadaki tüm rotaların birimi aynıdır (amaç sabittir), bu yüzden
       // ham değere göre kıyaslamak güvenlidir.
       results.sort(Comparator.comparingDouble((RouteResult r) -> r.score().value()).reversed()
             .thenComparingDouble(RouteResult::exFuelCaskCents));
       return results;
    }
    private static boolean hasRepeatedAirport(List<ScoredFlight> chain) {
       Set<String> seen = new HashSet<>();
       seen.add(chain.get(0).flight().from().code());
       for (ScoredFlight s : chain) {
          if(!seen.add(s.flight().to().code())) return true;
       }
       return false;
    }

    /**
     * DP tablosundan geriye yürüyerek rotayı kurar. Her bacak, kendi skoruyla
     * BİRLİKTE taşınır ({@link ScoredFlight}) — böylece görüntüleme katmanının
     * skoru ayrı bir tablodan araması gerekmez.
     */
    private RouteResult buildResult(FlightEvaluation[] eval, double[] w, Score.Unit unit,
          int[][] parent, int last, int legs, Score score) {
       List<ScoredFlight> chain = new ArrayList<>();
       int i = last, k = legs;
       while(i != -1) {
          chain.add(0, new ScoredFlight(eval[i], new Score(w[i], unit)));
          i = parent[i][k];
          k--;
       }
       double rev = 0, paxRev=0, ancRev=0, cargoRev=0, cost=0, profit=0, fuel=0, co2=0, paxCo2=0, cargoCo2=0, co2Pax=0, contribMargin=0;
       double askSum = 0, exFuelCostSum = 0;   // rota geneli yakıt-hariç CASK için
       for(ScoredFlight s : chain) {
          FlightEvaluation e = s.evaluation();
          rev += e.revenueUsd();
          paxRev += e.paxRevenueUsd();
          ancRev += e.ancillaryRevenueUsd();
          cargoRev += e.cargoRevenueUsd();
          cost += e.costUsd();
          profit += e.profitUsd();
          fuel += e.fuelKg();
          co2 += e.co2Kg();
          paxCo2 += e.passengerCo2Kg();
          cargoCo2 += e.cargoCo2Kg();
          co2Pax += e.co2PerPaxKg();
          contribMargin += e.contributionMarginUsd();
          askSum += e.ask();
          exFuelCostSum += e.costUsd() - e.fuelCostUsd();
       }
       // ASK-ağırlıklı yakıt-hariç CASK (cent): tek tek bacakların ortalaması değil,
       // rotanın toplam yapısal maliyeti / toplam koltuk-km. Skora girmez; tie-break.
       double exFuelCask = askSum > 0 ? exFuelCostSum / askSum * 100 : 0;
       return new RouteResult(chain, rev, paxRev, ancRev, cargoRev, cost, profit, fuel, co2, paxCo2, cargoCo2, co2Pax, contribMargin, exFuelCask, score);
    }
}
 