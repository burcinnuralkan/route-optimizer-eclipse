package com.hitit.aviation.desktop.ui;

import com.hitit.aviation.core.model.FlightDecision;

// İptal/devam kararının ({@link FlightDecision}) görsel karşılıklar
public final class Decisions {

    private Decisions() { }

    // Hücre/liste arka planı ve yazı rengi
    public static final String FLY_BG    = "#c8e6c9", FLY_FG    = "#1b5e20", FLY_BAR    = "#2e7d32";
    public static final String REVIEW_BG = "#fff3c4", REVIEW_FG = "#8d6e00", REVIEW_BAR = "#f9a825";
    public static final String CANCEL_BG = "#ffcdd2", CANCEL_FG = "#b71c1c", CANCEL_BAR = "#c62828";

    public static String label(FlightDecision d) {
        return switch (d) {
            case FLY    -> "UÇUR (FLY)";
            case REVIEW -> "İNCELE (REVIEW)";
            case CANCEL -> "İPTAL (CANCEL)";
        };
    }

    /** Karara göre hücre/liste arka plan + yazı rengi (inline CSS). */
    public static String cellStyle(FlightDecision d) {
        return switch (d) {
            case FLY    -> bg(FLY_BG, FLY_FG);
            case REVIEW -> bg(REVIEW_BG, REVIEW_FG);
            case CANCEL -> bg(CANCEL_BG, CANCEL_FG);
        };
    }

    /** Karara göre bar (çubuk grafik) dolgu rengi. */
    public static String barColor(FlightDecision d) {
        return switch (d) {
            case FLY    -> FLY_BAR;
            case REVIEW -> REVIEW_BAR;
            case CANCEL -> CANCEL_BAR;
        };
    }

    private static String bg(String bg, String fg) {
        return "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + ";";
    }
}
 