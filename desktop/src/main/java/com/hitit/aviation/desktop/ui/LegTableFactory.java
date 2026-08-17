package com.hitit.aviation.desktop.ui;

import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

import com.hitit.aviation.core.model.FlightDecision;
import com.hitit.aviation.core.model.FlightEvaluation;
import com.hitit.aviation.core.model.ScoredFlight;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;

/**
 * Seçili rotanın bacaklarını gösteren tabloyu kurar. Karar sütunu, verilen
 * {@code band} tedarikçisiyle her yeniden çizimde güncel karar bandına göre
 * renklenir (bkz. {@link Decisions}).
 */
public final class LegTableFactory {

    private LegTableFactory() { }

    /**
     * @param band güncel karar bandını döndüren tedarikçi (canlı okunur — hücre
     *             her çizildiğinde o anki değer sorulur)
     */
    public static TableView<ScoredFlight> build(DoubleSupplier band) {

        TableView<ScoredFlight> table = new TableView<>();

        TableColumn<ScoredFlight, String> flightCol = strCol("Flight No", e -> e.flight().flightNo());
        TableColumn<ScoredFlight, String> airlineCol = strCol("Havayolu", e -> e.flight().airlineCode());
        TableColumn<ScoredFlight, String> routeCol = strCol("Route",
                e -> e.flight().from().code() + " → " + e.flight().to().code());
        TableColumn<ScoredFlight, String> timeCol = strCol("Saat", e -> e.flight().timeString());

        // Skor: rota aramasında bu bacağa verilen ağırlık (kâr + KPI bonusu − OTP cezası).
        TableColumn<ScoredFlight, Number> scoreCol = scoreCol();

        TableColumn<ScoredFlight, Number> revenueCol = numCol("Gelir ($)", FlightEvaluation::revenueUsd, "%,.1f");
        TableColumn<ScoredFlight, Number> costCol = numCol("Maliyet ($)", FlightEvaluation::costUsd, "%,.1f");
        TableColumn<ScoredFlight, Number> profitCol = numCol("Kâr ($)", FlightEvaluation::profitUsd, "%,.1f");
        // Katkı payı (contribution margin): karar bandıyla kıyaslanan değer.
        TableColumn<ScoredFlight, Number> cmCol =
                numCol("Katkı payı ($)", FlightEvaluation::contributionMarginUsd, "%,.1f");

        // Karar sütunu: katkı payı ↔ karar bandı → FLY/REVIEW/CANCEL (renkli).
        TableColumn<ScoredFlight, FlightDecision> decisionCol = new TableColumn<>("Karar");
        decisionCol.setCellValueFactory(cell ->
                new SimpleObjectProperty<>(cell.getValue().decision(band.getAsDouble())));
        decisionCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(FlightDecision d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(Decisions.label(d));
                setStyle(Decisions.cellStyle(d) + "-fx-alignment: CENTER; -fx-font-weight: bold;");
            }
        });

        TableColumn<ScoredFlight, Number> loadFactorCol =
                numCol("Doluluk (%)", e -> e.passengerLoadFactor() * 100, "%.1f");
        TableColumn<ScoredFlight, Number> blockHoursCol =
                numCol("Uçuş (saat)", FlightEvaluation::blockHours, "%.1f");
        TableColumn<ScoredFlight, Number> fuelCol = numCol("Yakıt (kg)", FlightEvaluation::fuelKg, "%,.1f");
        TableColumn<ScoredFlight, Number> co2Col = numCol("CO₂ (kg)", FlightEvaluation::co2Kg, "%,.1f");
        TableColumn<ScoredFlight, Number> raskCol = numCol("RASK (c)", FlightEvaluation::raskCents, "%.2f");
        TableColumn<ScoredFlight, Number> caskCol = numCol("CASK (c)", FlightEvaluation::caskCents, "%.2f");
        // PASK = RASK − CASK : koltuk-km başına birim KÂR (cent). Pozitifse birim bazında para kazanıyor.
        TableColumn<ScoredFlight, Number> paskCol = numCol("PASK (c)", FlightEvaluation::paskCents, "%.2f");
        // BELF: masrafı çıkarmak için gereken doluluk. KARGO NETLEŞTİRİLMİŞ sürüm
        // kullanılır — BELF'in paydasındaki yield yalnızca bilet+yan gelirden gelir,
        // kargo geliri orada yoktur; kargoyu maliyetten düşmezsek kargo taşıyan uçuş
        // haksız yere "başabaşın altında" görünür.
        TableColumn<ScoredFlight, Number> belfCol =
                numCol("BELF (%)", e -> belfNetCargo(e) * 100, "%.1f");
        // Doluluk − BELF: pozitifse uçuş başabaşın üstünde doluyor.
        TableColumn<ScoredFlight, Number> belfGapCol =
                numCol("Doluluk−BELF (puan)",
                        e -> (e.passengerLoadFactor() - belfNetCargo(e)) * 100, "%+.1f");
        // Hangi büyüklüğün gösterildiği başlıktan anlaşılsın (sütun adı kısa kalmalı).
        headerTip(belfCol, "Başabaş doluluk — kargo geliri maliyetten düşülmüş hâli.\n"
                + "\"Bu uçuşun masrafını çıkarması için koltukların yüzde kaçı dolmalıydı?\"\n"
                + "yield ≤ 0 ise hesaplanamaz ve hücre boş kalır.");
        headerTip(belfGapCol, "Doluluk − BELF (yüzde puanı). Negatif = uçuş başabaş\n"
                + "doluluğunun altında; mutlak zarardan daha erken uyarı verir.");

        table.getColumns().addAll(flightCol, airlineCol, routeCol, timeCol,
                revenueCol, costCol, profitCol, cmCol, decisionCol, scoreCol,
                loadFactorCol, belfCol, belfGapCol, raskCol, caskCol, paskCol,
                blockHoursCol, fuelCol, co2Col);

        // Sütunlar başlıkları okunmaz hale gelecek kadar daralmasın: asgari genişlik
        // aşılırsa CONSTRAINED politikası yatay kaydırma çubuğu çıkarır. Tüm tarife
        // görünümünde 17 sütun aynı anda görünebildiği için bu şart.
        for (TableColumn<ScoredFlight, ?> col : table.getColumns()) {
            col.setMinWidth(78);
        }
        decisionCol.setMinWidth(120);   // "İNCELE (REVIEW)" sığmalı
        routeCol.setMinWidth(96);
        timeCol.setMinWidth(120);
        belfGapCol.setMinWidth(140);

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        return table;
    }

    /** Sonsuz/NaN değerleri NaN'a indirger; {@link #numCol} bunları boş hücre olarak gösterir. */
    private static double finiteOrNaN(double v) {
        return Double.isFinite(v) ? v : Double.NaN;
    }

    /**
     * Kargo netleştirilmiş başabaş doluluk. yield ≤ 0 ise BELF TANIMSIZDIR; çekirdek
     * bu durumda 0 döndürdüğü için (sıfır, "mükemmel başabaş" gibi okunurdu) burada
     * NaN'a çevrilir ve hücre boş bırakılır.
     */
    private static double belfNetCargo(FlightEvaluation e) {
        return e.yieldCents() > 0 ? finiteOrNaN(e.breakEvenLoadFactorNetCargo()) : Double.NaN;
    }

    /** Sütun başlığına açıklama balonu ekler (başlık metni kısa kalsın diye). */
    private static void headerTip(TableColumn<ScoredFlight, ?> col, String text) {
        Label header = new Label(col.getText());
        header.setTooltip(new Tooltip(text));
        col.setGraphic(header);
        col.setText(null);
    }

    /** Bacağın skoru — artık ayrı bir tablodan aranmaz, satırın kendisinde taşınır. */
    private static TableColumn<ScoredFlight, Number> scoreCol() {
        TableColumn<ScoredFlight, Number> col = new TableColumn<>("Skor");
        col.setCellValueFactory(cell -> new SimpleDoubleProperty(cell.getValue().score().value()));
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Number v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("%,.0f", v.doubleValue()));
            }
        });
        return col;
    }

    /**
     * Metin sütunu. Değer çıkarıcı {@link FlightEvaluation} üzerinde yazılır;
     * satır tipine çevirmeyi bu sarmalayıcı yapar.
     */
    private static TableColumn<ScoredFlight, String> strCol(
            String title, java.util.function.Function<FlightEvaluation, String> value) {
        TableColumn<ScoredFlight, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell ->
                new SimpleStringProperty(value.apply(cell.getValue().evaluation())));
        return col;
    }

    /** Değeri verilen biçimle gösteren sayısal sütun. NaN = "bilinmiyor" -> boş hücre. */
    private static TableColumn<ScoredFlight, Number> numCol(
            String title, ToDoubleFunction<FlightEvaluation> value, String fmt) {
        TableColumn<ScoredFlight, Number> col = new TableColumn<>(title);
        col.setCellValueFactory(cell ->
                new SimpleDoubleProperty(value.applyAsDouble(cell.getValue().evaluation())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Number v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null || Double.isNaN(v.doubleValue())
                        ? null
                        : String.format(fmt, v.doubleValue()));
            }
        });
        return col;
    }
}
 