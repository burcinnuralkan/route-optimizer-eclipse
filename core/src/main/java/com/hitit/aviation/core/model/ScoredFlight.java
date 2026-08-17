package com.hitit.aviation.core.model;

/**
 * Bir uçuşun değerlendirmesi ve ona verilen sıralama skoru — <b>birlikte</b>.
 * <p>Skoru değerlendirmenin YANINDA taşımak bu sınıf hatayı imkânsız kılar:
 * skor artık kaybolabilecek ayrı bir yerde değil, satırın kendisinde.
 */
public record ScoredFlight(FlightEvaluation evaluation, Score score) {

    public Flight flight() {
        return evaluation.flight();
    }

    /** Bu bacağın iptal/devam kararı (skoru etkilemez; bkz. {@link FlightDecision}). */
    public FlightDecision decision(double reviewBandUsd) {
        return evaluation.decision(reviewBandUsd);
    }
}
