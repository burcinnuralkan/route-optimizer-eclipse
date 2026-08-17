package com.hitit.aviation.api.dto;

import java.util.List;

import com.hitit.aviation.core.model.ScoredFlight;
import com.hitit.aviation.core.model.FlightDecision;

/**
 * Rota aramadan, TARİFENİN TAMAMININ değerlendirmesi — masaüstündeki
 * "kalkış/varış seçilmedi" görünümünün API karşılığı.
 *
 * <p>Aynı ortak CSV'den beslendiği için masaüstünde kaydedilen bir düzeltme
 * burada da görünür; {@code source} hangi dosyanın okunduğunu söyler.
 *
 * @param source   tarifenin okunduğu dosya (veya gömülü örnek veri)
 * @param count    değerlendirilen uçuş sayısı
 * @param summary  karar sayaçları ve para/CO2 toplamları
 * @param flights  uçuş bazında değerlendirme + skor + karar
 */
public record FleetResponse(
        String source,
        int count,
        Summary summary,
        List<Item> flights
) {

    /**
     * Tek bir uçuşun değerlendirmesi, bacak skoru ve kararı.
     *
     * @param scoreUnit skorun birimi ({@code "USD"} / {@code "KG_CO2"}) — amaç
     *        MIN_CO2 iken skor kilogram CO2'dir ve dolar skorlarla kıyaslanamaz
     */
    public record Item(
            FlightEvaluationDto evaluation,
            double score,
            String scoreUnit,
            FlightDecision decision) { }

    /** Tarife geneli toplamlar ve karar dağılımı. */
    public record Summary(
            int fly, int review, int cancel,
            double revenueUsd, double costUsd, double profitUsd,
            double contributionMarginUsd, double co2Kg,
            double reviewBandUsd) { }

    public static FleetResponse of(String source, List<ScoredFlight> fleet, double reviewBandUsd) {

        List<Item> items = fleet.stream()
                .map(s -> new Item(
                        FlightEvaluationDto.of(s.evaluation()),
                        s.score().value(),
                        s.score().unit().name(),
                        s.evaluation().decision(reviewBandUsd)))
                .toList();

        int fly = 0, review = 0, cancel = 0;
        double revenue = 0, cost = 0, cm = 0, co2 = 0;
        for (Item i : items) {
            revenue += i.evaluation().revenueUsd();
            cost += i.evaluation().costUsd();
            cm += i.evaluation().contributionMarginUsd();
            co2 += i.evaluation().co2Kg();
            switch (i.decision()) {
                case FLY -> fly++;
                case REVIEW -> review++;
                case CANCEL -> cancel++;
            }
        }

        Summary summary = new Summary(fly, review, cancel,
                revenue, cost, revenue - cost, cm, co2, reviewBandUsd);
        return new FleetResponse(source, items.size(), summary, items);
    }
}
 