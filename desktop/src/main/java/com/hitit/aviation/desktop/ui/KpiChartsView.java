package com.hitit.aviation.desktop.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hitit.aviation.core.model.ScoredFlight;
import com.hitit.aviation.core.model.FlightDecision;
import com.hitit.aviation.core.model.FlightEvaluation;
import com.hitit.aviation.core.model.RouteResult;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.Chart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * KPI'ları ve karar dağılımını grafiklerle gösteren panel.
 */
public class KpiChartsView {

    // KPI grafik renkleri: turuncu yok; sayfayla uyumlu, birbirinden ayrık soğuk tonlar
    private static final String CARGO_BAR  = "#1976d2";   // kargo verimi (mavi)
    private static final String EXFUEL_BAR = "#00897b";   // yakıt-hariç CASK (teal-yeşil; maviden ayrık)
    private static final String AIRLINE_BAR = "#5c6bc0";  // havayolu kırılımı (indigo)
    private static final String UNKNOWN_BAR = "#9e9e9e";  // hesaplanamayan değer (gri)
    private static final String[] PIE_COLORS = { "#1976d2", "#26a69a", "#5c6bc0" };  // yolcu / yan / kargo
    // Karar pastası: tablo/bar renkleriyle aynı trafik ışığı semantiği.
    private static final String[] DECISION_PIE_COLORS =
            { Decisions.FLY_BAR, Decisions.REVIEW_BAR, Decisions.CANCEL_BAR };

    /** Yatay bar grafiklerinde bir satırın (kategorinin) kapladığı yükseklik (px). */
    private static final int ROW_HEIGHT = 24;

    /** Yatay bar grafiğinin başlık/eksen payı (px) — satır yüksekliğine eklenir. */
    private static final int CHART_CHROME = 110;

    /** BELF farkı grafiğinde eksenin kırpıldığı sınır (yüzde puanı). */
    private static final double BELF_CLAMP_POINTS = 100;

    /** CASK ↔ mesafe grafiğindeki beklenen eğrinin çözünürlüğü (nokta sayısı). */
    private static final int CURVE_POINTS = 60;

    /**
     * "Gösterilen uçuş" seçeneği: katkı payına göre en kötü/en iyi N uçuş.
     *
     * @param count kaç satır çizilecek; 0 = tümü
     * @param worst true -> en düşük katkı paylılar, false -> en yüksek olanlar
     */
    private record Slice(String label, int count, boolean worst) {
        @Override public String toString() { return label; }
    }

    private static final List<Slice> SLICE_OPTIONS = List.of(
            new Slice("En kötü 10", 10, true),
            new Slice("En kötü 20", 20, true),
            new Slice("En kötü 30", 30, true),
            new Slice("En iyi 10", 10, false),
            new Slice("En iyi 20", 20, false),
            new Slice("En iyi 30", 30, false),
            new Slice("Tümü", 0, true));

    private final BarChart<String, Number> cmChart;
    private final BarChart<String, Number> cargoYieldChart;
    private final BarChart<String, Number> exFuelChart;
    private final PieChart revenuePie;

    // ── Tarife (tüm CSV) modu grafikleri ──
    // Uçuş/hat bazındakiler YATAY bardır (kategori ekseni dikeyde): 50+ kategoride
    // dikey bar hem etiketleri okunmaz hâle getiriyor hem de sayfayı yatay kaydırmaya
    // zorluyordu. Yatay barda etiketler yatay okunur ve büyüme sayfanın zaten doğal
    // olan DİKEY yönünde olur.
    private final BarChart<Number, String> fleetCmChart;
    private final BarChart<Number, String> belfChart;          // doluluk − başabaş doluluk
    private final StackedBarChart<Number, String> revenueStackChart;  // uçuş bazında gelir kırılımı
    private final BarChart<Number, String> odChart;            // hat (OD çifti) bazında katkı payı
    // Kategori sayısı az olanlar dikey kalır (11 havayolu / 8 tip / 5 gün rahat sığar).
    private final BarChart<String, Number> fleetAirlineChart;
    private final LineChart<Number, Number> aircraftChart;     // CASK ↔ sefer mesafesi (tip bazında)
    private final BarChart<String, Number> dayChart;           // gün bazında kâr
    private final PieChart fleetRevenuePie;
    private final PieChart fleetDecisionPie;
    private final LineChart<Number, Number> paretoChart;       // kümülatif katkı payı (Pareto)
    private final ScatterChart<Number, Number> scoreScatter;   // skor ↔ katkı payı

    private final GridPane routeGrid;
    // Tarife modu üç sekmeye bölünür: 11 grafiği tek akışta dizmek 3500px'lik bir
    // kaydırma yapıyordu ve birbiriyle ilgisiz sorular yan yana düşüyordu. Her sekme
    // tek bir soruya cevap verir.
    private final GridPane flightGrid;   // "hangi uçuş kötü?"
    private final GridPane mixGrid;      // "para nereden geliyor / nerede kaybediliyor?"
    private final GridPane modelGrid;    // "skorlama sağlıklı mı?"
    private final ScrollPane routeScroll;
    private final TabPane fleetTabs;
    private final Label hint;
    private final ComboBox<Slice> limitBox;   // uçuş bazlı grafiklerde hangi dilim gösterilsin
    private final Node fleetToolbar;
    private final VBox box;
    private final Node node;

    // Sınır (limitBox) değişince aynı veriyle yeniden çizebilmek için son durum.
    private List<ScoredFlight> lastFleet;
    private double lastBand;

    public KpiChartsView() {
        cmChart = barChart("Katkı payı ($) ve karar — bacak bazında", "Bacak", "Katkı payı ($)");
        cargoYieldChart = barChart("Kargo verimi (yield) — bacak bazında", "Bacak", "$ / ton-km");
        exFuelChart = barChart("Yakıt hariç CASK (yapısal birim maliyet) — düşük = iyi", "Bacak", "cent / ASK");

        revenuePie = pieChart("Gelir dağılımı (yolcu / yan / kargo)", PIE_COLORS);

        fleetCmChart = horizontalBarChart(
                "Uçuş bazında katkı payı ($)", "Katkı payı ($)");
        belfChart = horizontalBarChart(
                "Doluluk − başabaş doluluk (kargo netleştirilmiş BELF) — negatif = başabaşın altında",
                "puan");
        odChart = horizontalBarChart("Hat (OD çifti) bazında toplam katkı payı ($)", "Katkı payı ($)");

        fleetAirlineChart = barChart(
                "Havayolu bazında toplam katkı payı ($)", "Havayolu", "Katkı payı ($)");
        NumberAxis acX = new NumberAxis();
        acX.setLabel("Ortalama sefer mesafesi (km)");
        acX.setForceZeroInRange(false);
        NumberAxis acY = new NumberAxis();
        acY.setLabel("CASK (cent / ASK)");
        acY.setForceZeroInRange(false);
        aircraftChart = new LineChart<>(acX, acY);
        aircraftChart.setTitle("Uçak tipi: CASK ↔ sefer mesafesi — eğrinin ÜSTÜ = mesafesine göre pahalı");
        aircraftChart.setAnimated(false);
        aircraftChart.setCreateSymbols(true);
        dayChart = barChart("Gün bazında kâr ($)", "Gün", "Kâr ($)");

        fleetRevenuePie = pieChart("Gelir dağılımı — tüm tarife", PIE_COLORS);
        fleetDecisionPie = pieChart("Karar dağılımı (uçuş adedi)", DECISION_PIE_COLORS);

        NumberAxis stackX = new NumberAxis();
        stackX.setLabel("Gelir ($)");
        CategoryAxis stackY = new CategoryAxis();
        revenueStackChart = new StackedBarChart<>(stackX, stackY);
        revenueStackChart.setTitle("Uçuş bazında gelir kırılımı (yolcu / yan / kargo)");
        revenueStackChart.setAnimated(false);
        revenueStackChart.setCategoryGap(4);

        NumberAxis paretoX = new NumberAxis();
        paretoX.setLabel("Uçuş sayısı (en kârlıdan başlayarak)");
        NumberAxis paretoY = new NumberAxis(0, 100, 10);
        paretoY.setLabel("Kümülatif katkı payı (%)");
        paretoChart = new LineChart<>(paretoX, paretoY);
        paretoChart.setTitle("Pareto — katkı payının kaç uçuştan geldiği");
        paretoChart.setAnimated(false);
        paretoChart.setCreateSymbols(false);
        paretoChart.setLegendVisible(false);

        NumberAxis scatterX = new NumberAxis();
        scatterX.setLabel("Katkı payı ($)");
        scatterX.setForceZeroInRange(false);
        NumberAxis scatterY = new NumberAxis();
        scatterY.setLabel("Skor");
        scatterY.setForceZeroInRange(false);
        scoreScatter = new ScatterChart<>(scatterX, scatterY);
        scoreScatter.setTitle("Skor ↔ katkı payı — KPI bonusu parayla nerede ayrışıyor?");
        scoreScatter.setAnimated(false);
        scoreScatter.setLegendVisible(false);

        routeGrid = new GridPane();
        flightGrid = new GridPane();
        mixGrid = new GridPane();
        modelGrid = new GridPane();
        routeScroll = new ScrollPane();
        fleetTabs = new TabPane();
        hint = new Label();

        limitBox = new ComboBox<>(FXCollections.observableArrayList(SLICE_OPTIONS));
        limitBox.setValue(
                SLICE_OPTIONS.stream()
                        .filter(s -> s.count() == 0)
                        .findFirst()
                        .orElse(SLICE_OPTIONS.get(0)));

        limitBox.setTooltip(new Tooltip(
                "Uçuş ve hat bazındaki grafiklerde hangi dilim çizilsin.\n"
                + "Katkı payına göre en kötü/en iyi N; \"Tümü\" tarifenin tamamını çizer.\n"
                + "Her iki yönde de en uç satır en ÜSTTE durur."));
        limitBox.valueProperty().addListener((o, was, is) -> {
            if (lastFleet != null) updateFleet(lastFleet, lastBand);
        });

        Label limitLabel = new Label("Gösterilen uçuş:");
        limitLabel.setTooltip(limitBox.getTooltip());
        HBox toolbar = new HBox(8, limitLabel, limitBox);
        toolbar.setPadding(new Insets(6, 10, 0, 10));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        fleetToolbar = toolbar;

        box = new VBox();
        node = build();
    }

    public Node getNode() {
        return node;
    }

    private Node build() {
        // Grafiklere makul bir yükseklik ver ve hepsini bir ScrollPane'e al: aksi
        // hâlde grafiklerin büyük varsayılan min yüksekliği sekmeyi (dolayısıyla üstteki
        // rota listesini) ezer. ScrollPane'in min yüksekliği küçüktür → liste korunur.
        for (Chart c : List.of(cmChart, cargoYieldChart, exFuelChart, revenuePie,
                fleetCmChart, fleetAirlineChart, fleetRevenuePie, fleetDecisionPie,
                belfChart, revenueStackChart, odChart, aircraftChart, dayChart,
                paretoChart, scoreScatter)) {
            c.setPrefHeight(260);
            c.setMinHeight(200);
        }

        // Rota modu: 2×2.
        layoutGrid(routeGrid);
        routeGrid.add(cmChart, 0, 0);
        routeGrid.add(revenuePie, 1, 0);
        routeGrid.add(cargoYieldChart, 0, 1);
        routeGrid.add(exFuelChart, 1, 1);

        // ── Tarife modu · Sekme 1: uçuş bazında ("hangi uçuş kötü?") ──
        layoutGrid(flightGrid);
        flightGrid.add(fleetCmChart, 0, 0, 2, 1);
        flightGrid.add(belfChart, 0, 1, 2, 1);
        flightGrid.add(revenueStackChart, 0, 2, 2, 1);

        // ── Sekme 2: dağılım & kırılım ("para nereden geliyor, nerede kaybediliyor?") ──
        layoutGrid(mixGrid);
        mixGrid.add(fleetRevenuePie, 0, 0);
        mixGrid.add(fleetDecisionPie, 1, 0);
        mixGrid.add(odChart, 0, 1, 2, 1);
        mixGrid.add(fleetAirlineChart, 0, 2);
        mixGrid.add(dayChart, 1, 2);
        // CASK ↔ mesafe grafiği iki eksenli ve legend'lı; yarım sütunda başlığı
        // kesiliyor ve noktalar üst üste biniyordu -> tam genişlik.
        mixGrid.add(aircraftChart, 0, 3, 2, 1);

        // ── Sekme 3: model kontrolü ("skorlama ve ağ yapısı sağlıklı mı?") ──
        layoutGrid(modelGrid);
        modelGrid.add(paretoChart, 0, 0);
        modelGrid.add(scoreScatter, 1, 0);

        fleetTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        fleetTabs.getTabs().addAll(
                chartTab("Uçuş bazında", flightGrid,
                        "Hangi uçuş kötü? Katkı payı, başabaş doluluk farkı ve gelir kırılımı — "
                        + "yukarıdaki \"Gösterilen uçuş\" kutusu bu sekmeyi (ve hat grafiğini) süzer."),
                chartTab("Dağılım & kırılım", mixGrid,
                        "Para nereden geliyor, nerede kaybediliyor? Gelir/karar dağılımı, "
                        + "hat · havayolu · uçak tipi · gün kırılımları."),
                chartTab("Model kontrolü", modelGrid,
                        "Skorlama sağlıklı mı? Pareto katkı payının kaç uçuşta toplandığını, "
                        + "saçılım ise KPI bonusunun paradan nerede ayrıştığını gösterir."));

        routeScroll.setContent(routeGrid);
        chartScroll(routeScroll);

        hint.setWrapText(true);
        hint.setPadding(new Insets(4, 8, 4, 8));
        setRouteHint();

        box.getChildren().setAll(routeScroll, hint);
        VBox.setVgrow(routeScroll, Priority.ALWAYS);
        return box;
    }

    /** Grafik sekmesi: kendi kaydırma alanı + ne işe yaradığını söyleyen ipucu. */
    private static Tab chartTab(String title, GridPane grid, String tip) {
        ScrollPane sp = new ScrollPane(grid);
        chartScroll(sp);
        Tab tab = new Tab(title, sp);
        tab.setClosable(false);
        tab.setTooltip(new Tooltip(tip));
        return tab;
    }

    /**
     * Yatayda ASLA kaydırma olmasın: içerik pencere genişliğine sığdırılır, grafikler
     * dikeyde uzar. Yatay kaydırma çubuğu tüm sayfayı birlikte kaydırdığı için
     * kullanımı konforsuzdu; artık tek kaydırma yönü var.
     */
    private static void chartScroll(ScrollPane sp) {
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    }

    /** Panelin ortasını (rota tek sayfası ↔ tarife sekmeleri) ve araç çubuğunu ayarlar. */
    private void setCenter(Node center, boolean withToolbar) {
        if (withToolbar) box.getChildren().setAll(fleetToolbar, center, hint);
        else box.getChildren().setAll(center, hint);
        VBox.setVgrow(center, Priority.ALWAYS);
    }

    /** İki eşit sütunlu, boşluklu grid düzeni (her iki mod için ortak). */
    private static void layoutGrid(GridPane grid) {
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(10));
        for (int i = 0; i < 2; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }
    }

    private void setRouteHint() {
        hint.setText("  Bar renkleri katkı payı grafiğinde karara göredir: yeşil=UÇUR, sarı=İNCELE, kırmızı=İPTAL. "
                + "Kargo verimi ve yakıt-hariç CASK yapısal göstergelerdir; gelir pastası kargo gelir payını gösterir.");
    }

    /** Seçili rotanın KPI grafiklerini verilen karar bandıyla doldurur/yeniler. */
    public void update(RouteResult r, double band) {
        setCenter(routeScroll, false);
        setRouteHint();

        XYChart.Series<String, Number> cmSeries = new XYChart.Series<>();
        XYChart.Series<String, Number> cargoSeries = new XYChart.Series<>();
        XYChart.Series<String, Number> exFuelSeries = new XYChart.Series<>();

        int i = 1;
        for (ScoredFlight leg : r.legs()) {
            FlightEvaluation e = leg.evaluation();
            String label = i + ". " + e.flight().from().code() + "→" + e.flight().to().code();

            XYChart.Data<String, Number> cmData =
                    new XYChart.Data<>(label, round2(e.contributionMarginUsd()));
            cmSeries.getData().add(cmData);
            colorBar(cmData, Decisions.barColor(e.decision(band)));   // bar rengi = karar

            XYChart.Data<String, Number> cargoData =
                    new XYChart.Data<>(label, round2(e.cargoYieldPerTonneKm()));
            cargoSeries.getData().add(cargoData);
            colorBar(cargoData, CARGO_BAR);       // mavi (varsayılan turuncu yerine)

            XYChart.Data<String, Number> exFuelData =
                    new XYChart.Data<>(label, round2(e.exFuelCaskCents()));
            exFuelSeries.getData().add(exFuelData);
            colorBar(exFuelData, EXFUEL_BAR);     // deniz mavisi
            i++;
        }

        cmChart.getData().setAll(List.of(cmSeries));
        cargoYieldChart.getData().setAll(List.of(cargoSeries));
        exFuelChart.getData().setAll(List.of(exFuelSeries));
        // Bar node'larını HEMEN oluştur ki renkleri (colorBar) çizimden ÖNCE otursun;
        // aksi hâlde her aramada bir kare varsayılan turuncu görünüp maviye "sıçrar".
        for (BarChart<String, Number> c : List.of(cmChart, cargoYieldChart, exFuelChart)) {
            c.applyCss();
            c.layout();
        }

        // Gelir dağılımı pastası: rota toplamları. Kargo dilimi kargo gelir payını verir.
        double total = r.totalRevenueUsd();
        double cargoSharePct = total > 0 ? r.totalCargoRevenueUsd() / total * 100 : 0;
        setPieData(revenuePie, PIE_COLORS,
                new PieChart.Data(String.format("Yolcu $%,.0f", r.totalPaxRevenueUsd()), r.totalPaxRevenueUsd()),
                new PieChart.Data(String.format("Yan $%,.0f", r.totalAncillaryRevenueUsd()), r.totalAncillaryRevenueUsd()),
                new PieChart.Data(String.format("Kargo $%,.0f (%%%.0f)", r.totalCargoRevenueUsd(), cargoSharePct),
                        r.totalCargoRevenueUsd()));
    }

    /**
     * Tarifenin TAMAMINI (kalkış/varış seçilmemiş "tüm veri" görünümü) grafikler.
     * Uçuşlar katkı payına göre ARTAN sıralanır: zarar eden/iptal adayı uçuşlar
     * grafiğin solunda toplanır, tek bakışta görülür.
     */
    public void updateFleet(List<ScoredFlight> fleet, double band) {
        lastFleet = fleet;
        lastBand = band;
        setCenter(fleetTabs, true);

        // Uçuş bazlı grafiklerde gösterilecek dilim: katkı payına göre en kötü/en iyi N.
        // Sıralama HER İKİ yönde de "en uç önce" olacak şekilde kurulur; çizim sırasında
        // ters çevrildiği için en uç satır grafiğin en üstünde çıkar.
        Slice slice = limitBox.getValue() == null ? SLICE_OPTIONS.get(1) : limitBox.getValue();
        List<ScoredFlight> ordered = new ArrayList<>(fleet);
        Comparator<ScoredFlight> byCm =
                Comparator.comparingDouble(s -> s.evaluation().contributionMarginUsd());
        ordered.sort(slice.worst() ? byCm : byCm.reversed());
        int limit = slice.count() == 0 ? ordered.size() : Math.min(slice.count(), ordered.size());
        List<ScoredFlight> activeFleet = ordered.subList(0, limit);

        hint.setText(String.format(
                "  Tüm tarife görünümü — %d uçuşun tamamı özet grafiklerde; uçuş ve hat bazlı grafiklerde "
                + "katkı payı en %s %d tanesi çizili (yukarıdaki kutudan değiştirilebilir). "
                + "Yatay barlarda en uç satır en üsttedir; barın üstüne gelince rota, saat, karar ve skor çıkar. "
                + "BELF = başabaş doluluk; negatif fark, uçuşun masrafını çıkaracak doluluğa ulaşamadığı anlamına gelir.",
                fleet.size(), slice.worst() ? "düşük" : "yüksek", activeFleet.size()));

        // ── Uçuş bazında katkı payı (yatay) ──
        // Kategori ekseninde ilk eklenen ALTTA durur; en kötüyü en ÜSTTE göstermek
        // için iyiden kötüye (azalan) eklenir.
        XYChart.Series<Number, String> cmSeries = new XYChart.Series<>();
        for (ScoredFlight s : reversed(activeFleet)) {
            FlightEvaluation e = s.evaluation();
            XYChart.Data<Number, String> d =
                    new XYChart.Data<>(round2(e.contributionMarginUsd()), rowLabel(e));
            cmSeries.getData().add(d);
            FlightDecision decision = e.decision(band);
            styleBar(d, Decisions.barColor(decision), String.format(
                    "%s  %s → %s%n%s%nKatkı payı: $%,.0f%nKâr: $%,.0f%nSkor: %,.0f%nKarar: %s",
                    e.flight().flightNo(), e.flight().from().code(), e.flight().to().code(),
                    e.flight().timeString(), e.contributionMarginUsd(), e.profitUsd(),
                    s.score().value(), Decisions.label(decision)));
        }
        fleetCmChart.getData().setAll(List.of(cmSeries));
        fleetCmChart.setTitle(String.format("Uçuş bazında katkı payı ($) — %d uçuşun en %s %d tanesi",
                fleet.size(), slice.worst() ? "kötü" : "iyi", activeFleet.size()));
        sizeRows(fleetCmChart, activeFleet.size());

        // ── Havayolu bazında toplam katkı payı ──
        Map<String, Double> cmByAirline = new LinkedHashMap<>();
        for (ScoredFlight s : activeFleet) {
            cmByAirline.merge(s.flight().airlineCode(),
                    s.evaluation().contributionMarginUsd(), Double::sum);
        }
        List<Map.Entry<String, Double>> airlines = new ArrayList<>(cmByAirline.entrySet());
        airlines.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        XYChart.Series<String, Number> airlineSeries = new XYChart.Series<>();
        for (Map.Entry<String, Double> en : airlines) {
            XYChart.Data<String, Number> d = new XYChart.Data<>(en.getKey(), round2(en.getValue()));
            airlineSeries.getData().add(d);
            long legs = fleet.stream().filter(s -> s.flight().airlineCode().equals(en.getKey())).count();
            styleBar(d, en.getValue() < 0 ? Decisions.CANCEL_BAR : AIRLINE_BAR,
                    String.format("%s%n%d uçuş%nToplam katkı payı: $%,.0f", en.getKey(), legs, en.getValue()));
        }
        fleetAirlineChart.getData().setAll(List.of(airlineSeries));
        forceLayout(fleetCmChart);
        forceLayout(fleetAirlineChart);

        // ── Gelir dağılımı (tarife toplamı) ──
        double pax = 0, ancillary = 0, cargo = 0;
        int fly = 0, review = 0, cancel = 0;
        for (ScoredFlight s : activeFleet) {
            FlightEvaluation e = s.evaluation();
            pax += e.paxRevenueUsd();
            ancillary += e.ancillaryRevenueUsd();
            cargo += e.cargoRevenueUsd();
            switch (e.decision(band)) {
                case FLY    -> fly++;
                case REVIEW -> review++;
                case CANCEL -> cancel++;
            }
        }
        double total = pax + ancillary + cargo;
        double cargoSharePct = total > 0 ? cargo / total * 100 : 0;
        setPieData(fleetRevenuePie, PIE_COLORS,
                new PieChart.Data(String.format("Yolcu $%,.0f", pax), pax),
                new PieChart.Data(String.format("Yan $%,.0f", ancillary), ancillary),
                new PieChart.Data(String.format("Kargo $%,.0f (%%%.0f)", cargo, cargoSharePct), cargo));

        fillBelfChart(activeFleet, band);
        fillRevenueStack(activeFleet);
        fillOdChart(fleet, band, limit, slice.worst());
        fillAircraftChart(activeFleet);
        fillDayChart(activeFleet);
        fillParetoChart(activeFleet);
        fillScoreScatter(activeFleet, band);

        // ── Karar dağılımı (adet) ──
        // Sıfır adetli dilim eklenmez: PieChart boş dilimi legend'da gösterip kafa karıştırır.
        List<PieChart.Data> decisions = new ArrayList<>();
        int flights = fleet.size();
        if (fly > 0)    decisions.add(new PieChart.Data(pieLabel("UÇUR", fly, flights), fly));
        if (review > 0) decisions.add(new PieChart.Data(pieLabel("İNCELE", review, flights), review));
        if (cancel > 0) decisions.add(new PieChart.Data(pieLabel("İPTAL", cancel, flights), cancel));
        // Renk sırası karar sırasına sabitlenmeli; atlanan dilim rengi kaydırmasın.
        String[] colors = new String[decisions.size()];
        int ci = 0;
        if (fly > 0)    colors[ci++] = DECISION_PIE_COLORS[0];
        if (review > 0) colors[ci++] = DECISION_PIE_COLORS[1];
        if (cancel > 0) colors[ci++] = DECISION_PIE_COLORS[2];
        setPieData(fleetDecisionPie, colors, decisions.toArray(PieChart.Data[]::new));
    }
    /**
     * Doluluk − başabaş doluluk (BELF), yüzde PUANI olarak.
     *
     * <p><b>KARGO NETLEŞTİRİLMİŞ BELF kullanılır</b> ({@code (maliyet − kargo geliri) / yield}),
     * ham BELF değil. Sebebi: BELF'in paydasındaki yield yalnızca bilet + yan gelirden
     * hesaplanır ({@code ProfitCalculator}), yani kargo geliri paydada YOK. Ham BELF ile
     * ölçünce kargo taşıyan bir uçuş, kargonun masrafın bir kısmını zaten karşılamış
     * olmasına rağmen "başabaşın altında" görünür. Kargo gelirini maliyetten düşmek
     * doğru kıyası verir: "kargo payını aldıktan sonra koltukların yüzde kaçı dolmalı?"
     */
    private void fillBelfChart(List<ScoredFlight> shown, double band) {
        XYChart.Series<Number, String> series = new XYChart.Series<>();
        for (ScoredFlight s : reversed(shown)) {
            FlightEvaluation e = s.evaluation();
            boolean computable = e.yieldCents() > 0
                    && Double.isFinite(e.breakEvenLoadFactorNetCargo());
            double belf = e.breakEvenLoadFactorNetCargo();
            double gapPoints = computable
                    ? clampPoints((e.passengerLoadFactor() - belf) * 100)
                    : 0;

            XYChart.Data<Number, String> d = new XYChart.Data<>(round2(gapPoints), rowLabel(e));
            series.getData().add(d);

            String color = !computable ? UNKNOWN_BAR
                    : gapPoints < 0 ? Decisions.CANCEL_BAR : Decisions.FLY_BAR;
            styleBar(d, color, String.format(
                    "%s  %s → %s%nDoluluk: %%%.1f%n"
                    + "Başabaş doluluk (kargo netli): %s%nHam BELF (kargosuz): %s%n"
                    + "Fark: %s%nKargo geliri: $%,.0f%nKarar: %s",
                    e.flight().flightNo(), e.flight().from().code(), e.flight().to().code(),
                    e.passengerLoadFactor() * 100,
                    computable ? String.format("%%%.1f", belf * 100) : "hesaplanamadı (yield ≤ 0)",
                    e.yieldCents() > 0 ? String.format("%%%.1f", e.breakEvenLoadFactor() * 100) : "—",
                    computable ? String.format("%+.1f puan", gapPoints) : "—",
                    e.cargoRevenueUsd(), Decisions.label(e.decision(band))));
        }
        belfChart.getData().setAll(List.of(series));
        sizeRows(belfChart, shown.size());
        forceLayout(belfChart);
    }

    /**
     * Uçuş bazında gelir kırılımı (yığılmış bar). Pasta grafiği TOPLAM için
     * doğrudur ama 50 uçuş için 50 pasta çizilemez; kompozisyonu uçuş bazında
     * göstermenin doğru biçimi yığılmış bardır.
     */
    private void fillRevenueStack(List<ScoredFlight> shown) {
        XYChart.Series<Number, String> pax = new XYChart.Series<>();
        pax.setName("Yolcu");
        XYChart.Series<Number, String> anc = new XYChart.Series<>();
        anc.setName("Yan");
        XYChart.Series<Number, String> cargo = new XYChart.Series<>();
        cargo.setName("Kargo");

        for (ScoredFlight s : reversed(shown)) {
            FlightEvaluation e = s.evaluation();
            String label = rowLabel(e);
            double total = e.revenueUsd();

            XYChart.Data<Number, String> p = new XYChart.Data<>(round2(e.paxRevenueUsd()), label);
            XYChart.Data<Number, String> a = new XYChart.Data<>(round2(e.ancillaryRevenueUsd()), label);
            XYChart.Data<Number, String> c = new XYChart.Data<>(round2(e.cargoRevenueUsd()), label);
            pax.getData().add(p);
            anc.getData().add(a);
            cargo.getData().add(c);

            styleBar(p, PIE_COLORS[0], revenueTooltip(e, "Yolcu", e.paxRevenueUsd(), total));
            styleBar(a, PIE_COLORS[1], revenueTooltip(e, "Yan", e.ancillaryRevenueUsd(), total));
            styleBar(c, PIE_COLORS[2], revenueTooltip(e, "Kargo", e.cargoRevenueUsd(), total));
        }

        revenueStackChart.getData().setAll(List.of(pax, anc, cargo));
        sizeRows(revenueStackChart, shown.size());
        forceLayout(revenueStackChart);
    }

    private static String revenueTooltip(FlightEvaluation e, String part, double value, double total) {
        return String.format("%s  %s → %s%n%s geliri: $%,.0f (%%%.0f)%nToplam gelir: $%,.0f",
                e.flight().flightNo(), e.flight().from().code(), e.flight().to().code(),
                part, value, total > 0 ? value / total * 100 : 0, total);
    }

    /**
     * Hat (kalkış-varış çifti) bazında toplam katkı payı. Karar birimi çoğu zaman
     * tek uçuş değil hattır: "IST→JFK'i kapatalım mı?" sorusunun cevabı burada.
     */
    private void fillOdChart(List<ScoredFlight> fleet, double band, int limit, boolean worst) {
        Map<String, double[]> byOd = new LinkedHashMap<>();   // hat -> {katkı payı, uçuş sayısı}
        for (ScoredFlight s : fleet) {
            String key = s.flight().from().code() + " → " + s.flight().to().code();
            double[] acc = byOd.computeIfAbsent(key, k -> new double[2]);
            acc[0] += s.evaluation().contributionMarginUsd();
            acc[1]++;
        }
        List<Map.Entry<String, double[]>> ods = new ArrayList<>(byOd.entrySet());
        Comparator<Map.Entry<String, double[]>> byCm =
                Comparator.comparingDouble(en -> en.getValue()[0]);
        ods.sort(worst ? byCm : byCm.reversed());
        List<Map.Entry<String, double[]>> shown = ods.subList(0, Math.min(limit, ods.size()));

        XYChart.Series<Number, String> series = new XYChart.Series<>();
        for (int i = shown.size() - 1; i >= 0; i--) {        // en uç hat en üstte
            Map.Entry<String, double[]> en = shown.get(i);
            XYChart.Data<Number, String> d = new XYChart.Data<>(round2(en.getValue()[0]), en.getKey());
            series.getData().add(d);
            styleBar(d, Decisions.barColor(FlightDecision.of(en.getValue()[0], band)),
                    String.format("%s%n%.0f uçuş%nToplam katkı payı: $%,.0f%nUçuş başına: $%,.0f",
                            en.getKey(), en.getValue()[1], en.getValue()[0],
                            en.getValue()[0] / en.getValue()[1]));
        }
        odChart.getData().setAll(List.of(series));
        odChart.setTitle(String.format(
                "Hat (OD çifti) bazında toplam katkı payı ($) — %d hattın en %s %d tanesi",
                ods.size(), worst ? "kötü" : "iyi", shown.size()));
        sizeRows(odChart, shown.size());
        forceLayout(odChart);
    }

    /**
     * Uçak tipi birim maliyeti, SEKTÖRDEKİ standart biçimiyle: CASK ↔ sefer mesafesi
     * saçılımı + beklenen eğri. Her uçak tipi kendi ortalama sefer mesafesinde bir
     * nokta; kesikli gri çizgi filodan türetilen beklenen CASK eğrisidir.
     * <p><b>Beklenen eğri:</b> sektörde "karekök kuralı" denen bağıntı —
     * {@code CASK ≈ k / √mesafe}. Katsayı {@code k}, filonun kendi verisinden
     * ASK-ağırlıklı olarak kestirilir ({@code k = ortalama(CASK × √mesafe)}), yani
     * eğri dışarıdan dayatılmaz, bu tarifenin kendi maliyet seviyesini temsil eder.
     * Çekirdekteki {@code stageAdjustedCaskCents} de aynı bağıntıyı kullanır.
     *
     * <p>Eğrinin ÜSTÜNDEKİ tip, kendi menzil sınıfı için pahalı; ALTINDAKİ ucuzdur.
     */
    private void fillAircraftChart(List<ScoredFlight> fleet) {
        // tip -> {ΣASK, Σkoltuk, Σyolcu, uçuş, Σmaliyet, Σ(mesafe×ASK)}
        Map<String, double[]> byType = new LinkedHashMap<>();
        double fleetAsk = 0, fleetK = 0;
        for (ScoredFlight s : fleet) {
            FlightEvaluation e = s.evaluation();
            double[] acc = byType.computeIfAbsent(e.flight().aircraftType(), k -> new double[6]);
            acc[0] += e.ask();
            acc[1] += e.flight().econSeats() + e.flight().busSeats();
            acc[2] += e.flight().econPax() + e.flight().busPax();
            acc[3]++;
            acc[4] += e.costUsd();
            acc[5] += e.flight().distanceKm() * e.ask();   // ASK-ağırlıklı mesafe

            // k = CASK × √mesafe, ASK ile ağırlıklandırılır (büyük uçuş daha çok söz sahibi).
            if (e.ask() > 0 && e.flight().distanceKm() > 0) {
                fleetK += e.caskCents() * Math.sqrt(e.flight().distanceKm()) * e.ask();
                fleetAsk += e.ask();
            }
        }
        if (byType.isEmpty() || fleetAsk <= 0) {
            aircraftChart.getData().clear();
            return;
        }
        double k = fleetK / fleetAsk;

        List<XYChart.Series<Number, Number>> all = new ArrayList<>();

        // Beklenen eğri önce eklenir ki tip noktaları üstünde kalsın.
        XYChart.Series<Number, Number> curve = new XYChart.Series<>();
        curve.setName("Beklenen CASK (k/√mesafe)");
        double minKm = Double.MAX_VALUE, maxKm = 0;
        for (double[] v : byType.values()) {
            double km = stageKm(v);
            minKm = Math.min(minKm, km);
            maxKm = Math.max(maxKm, km);
        }
        double from = Math.max(100, minKm * 0.75), to = maxKm * 1.15;
        for (int i = 0; i <= CURVE_POINTS; i++) {
            double km = from + (to - from) * i / CURVE_POINTS;
            curve.getData().add(new XYChart.Data<>(round2(km), round2(k / Math.sqrt(km))));
        }
        all.add(curve);

        // Her tip tek noktalı bir seri: legend doğrudan tip adını gösterir.
        List<Map.Entry<String, double[]>> types = new ArrayList<>(byType.entrySet());
        types.sort(Comparator.comparingDouble(en -> stageKm(en.getValue())));
        for (Map.Entry<String, double[]> en : types) {
            double[] v = en.getValue();
            double km = stageKm(v);
            double cask = rawCask(v);
            double expected = k / Math.sqrt(km);
            double deviationPct = expected > 0 ? (cask / expected - 1) * 100 : 0;

            XYChart.Series<Number, Number> point = new XYChart.Series<>();
            point.setName(en.getKey());
            XYChart.Data<Number, Number> d = new XYChart.Data<>(round2(km), round2(cask));
            point.getData().add(d);
            all.add(point);

            String tip = String.format(
                    "%s%n%.0f uçuş%nCASK: %.2f cent/ASK%nOrt. sefer mesafesi: %,.0f km%n"
                    + "Bu mesafede beklenen: %.2f cent%nSapma: %+.0f%%  (%s)%nOrt. doluluk: %%%.1f",
                    en.getKey(), v[3], cask, km, expected, deviationPct,
                    deviationPct > 0 ? "mesafesine göre PAHALI" : "mesafesine göre ucuz",
                    v[1] > 0 ? v[2] / v[1] * 100 : 0);
            d.nodeProperty().addListener((o, was, is) -> {
                if (is != null) Tooltip.install(is, new Tooltip(tip));
            });
            if (d.getNode() != null) Tooltip.install(d.getNode(), new Tooltip(tip));
        }

        aircraftChart.getData().setAll(all);
        forceLayout(aircraftChart);

        // Eğri: kesikli gri çizgi, nokta işaretleri gizli (yoksa 60 sembol çizilir).
        if (curve.getNode() != null) {
            curve.getNode().setStyle(
                    "-fx-stroke: #9e9e9e; -fx-stroke-width: 1.5; -fx-stroke-dash-array: 5 5;");
        }
        for (XYChart.Data<Number, Number> d : curve.getData()) {
            if (d.getNode() != null) d.getNode().setVisible(false);
        }
    }

    /** Tipin ASK-ağırlıklı ortalama sefer mesafesi (km). */
    private static double stageKm(double[] acc) {
        return acc[0] > 0 ? acc[5] / acc[0] : 0;
    }

    /** ASK-ağırlıklı CASK (cent/ASK): Σmaliyet / ΣASK × 100. */
    private static double rawCask(double[] acc) {
        return acc[0] > 0 ? acc[4] / acc[0] * 100 : 0;
    }

    /** Gün bazında kâr. Tarife birden çok güne yayıldığında hangi günün taşıdığını gösterir. */
    private void fillDayChart(List<ScoredFlight> fleet) {
        Map<java.time.LocalDate, double[]> byDay = new java.util.TreeMap<>();  // gün -> {kâr, CO2, uçuş}
        for (ScoredFlight s : fleet) {
            double[] acc = byDay.computeIfAbsent(s.flight().flightDate(), k -> new double[3]);
            acc[0] += s.evaluation().profitUsd();
            acc[1] += s.evaluation().co2Kg();
            acc[2]++;
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Map.Entry<java.time.LocalDate, double[]> en : byDay.entrySet()) {
            double[] v = en.getValue();
            XYChart.Data<String, Number> d = new XYChart.Data<>(en.getKey().toString(), round2(v[0]));
            series.getData().add(d);
            styleBar(d, v[0] < 0 ? Decisions.CANCEL_BAR : CARGO_BAR, String.format(
                    "%s%n%.0f uçuş%nKâr: $%,.0f%nCO2: %.1f t", en.getKey(), v[2], v[0], v[1] / 1000));
        }
        dayChart.getData().setAll(List.of(series));
        forceLayout(dayChart);
    }

    /**
     * Pareto: uçuşları katkı payına göre AZALAN sıralayıp kümülatif payı çizer.
     * Eğri erken doyuyorsa katkı payı az sayıda uçuşta toplanmış demektir — ağın
     * ne kadar kırılgan olduğunun ölçüsü.
     *
     * <p>Toplam katkı payı ≤ 0 ise yüzde anlamsız olur; o durumda grafik boşaltılır.
     */
    private void fillParetoChart(List<ScoredFlight> fleet) {
        List<ScoredFlight> desc = new ArrayList<>(fleet);
        desc.sort(Comparator.comparingDouble((ScoredFlight s) ->
                s.evaluation().contributionMarginUsd()).reversed());

        double total = desc.stream().mapToDouble(s -> s.evaluation().contributionMarginUsd()).sum();
        if (total <= 0) {
            paretoChart.getData().clear();
            return;
        }

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.getData().add(new XYChart.Data<>(0, 0));
        double cum = 0;
        int i = 0;
        for (ScoredFlight s : desc) {
            cum += s.evaluation().contributionMarginUsd();
            i++;
            series.getData().add(new XYChart.Data<>(i, round2(cum / total * 100)));
        }
        paretoChart.getData().setAll(List.of(series));
    }

    /**
     * Skor ↔ katkı payı saçılımı. Skor = kâr + KPI bonusu − OTP cezası olduğundan
     * noktalar kabaca bir doğru üstünde durur; doğrudan SAPAN noktalar KPI bonusunun
     * parayı ittiği uçuşlardır. Sağ altta (yüksek katkı payı, düşük skor) veya sol
     * üstte (düşük katkı payı, yüksek skor) küme varsa kpiStrength fazla demektir.
     */
    private void fillScoreScatter(List<ScoredFlight> fleet, double band) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        for (ScoredFlight s : fleet) {
            FlightEvaluation e = s.evaluation();
            XYChart.Data<Number, Number> d =
                    new XYChart.Data<>(round2(e.contributionMarginUsd()), round2(s.score().value()));
            series.getData().add(d);
            String color = Decisions.barColor(e.decision(band));
            String style = "-fx-background-color: " + color + ";";
            String tip = String.format("%s  %s → %s%nKatkı payı: $%,.0f%nSkor: %,.0f%nKâr: $%,.0f",
                    e.flight().flightNo(), e.flight().from().code(), e.flight().to().code(),
                    e.contributionMarginUsd(), s.score().value(), e.profitUsd());
            d.nodeProperty().addListener((o, was, is) -> {
                if (is != null) {
                    is.setStyle(style);
                    Tooltip.install(is, new Tooltip(tip));
                }
            });
        }
        scoreScatter.getData().setAll(List.of(series));
        forceLayout(scoreScatter);
    }

    /** Bar node'larını hemen oluşturur ki renkler çizimden ÖNCE otursun (turuncu sıçraması olmasın). */
    private static void forceLayout(Chart chart) {
        chart.applyCss();
        chart.layout();
    }

    /** BELF farkını okunur bir aralığa kırpar (uç değerler ekseni ezmesin). */
    private static double clampPoints(double points) {
        return Math.max(-BELF_CLAMP_POINTS, Math.min(BELF_CLAMP_POINTS, points));
    }

    private static String pieLabel(String name, int count, int total) {
        return String.format("%s: %d (%%%.0f)", name, count, total > 0 ? count * 100.0 / total : 0);
    }

    private static BarChart<Number, String> horizontalBarChart(String title, String valueLabel) {
        NumberAxis x = new NumberAxis();
        x.setLabel(valueLabel);
        CategoryAxis y = new CategoryAxis();
        BarChart<Number, String> chart = new BarChart<>(x, y);
        chart.setTitle(title);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(3);
        chart.setBarGap(1);
        return chart;
    }

    /** Satır etiketi: uçuş no + rota + tarih + saat. Kategori ekseninde benzersiz olmalı. */
    private static String rowLabel(FlightEvaluation e) {
        return String.format("%s  %s→%s  %s %s",
                e.flight().flightNo(),
                e.flight().from().code(),
                e.flight().to().code(),
                e.flight().flightDate(),
                e.flight().timeString());
    }


    private static void sizeRows(Chart chart, int rows) {
        double h = CHART_CHROME + Math.max(1, rows) * ROW_HEIGHT;
        chart.setMinHeight(h);
        chart.setPrefHeight(h);
    }

    /** Listenin ters sıralı KOPYASI (kategori ekseninde ilk eklenen altta durur). */
    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** Kategori-eksenli boş bir bar chart üretir (ortak ayarlar). */
    private static BarChart<String, Number> barChart(String title, String xLabel, String yLabel) {
        CategoryAxis x = new CategoryAxis();
        x.setLabel(xLabel);
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setTitle(title);
        chart.setLegendVisible(false);
        chart.setAnimated(false);   // veri değişince "uçuşan" bar animasyonunu kapat
        chart.setCategoryGap(6);
        return chart;
    }

    private static <X, Y> void colorBar(XYChart.Data<X, Y> data, String color) {
        String style = "-fx-bar-fill: " + color + ";";
        if (data.getNode() != null) data.getNode().setStyle(style);
        data.nodeProperty().addListener((o, was, is) -> {
            if (is != null) is.setStyle(style);
        });
    }

    /**
     * Bar'ı boyar ve üstüne bir tooltip takar. Tarife modunda eksen etiketine
     * yalnızca uçuş no sığdığı için ayrıntı (rota/saat/karar/skor) tooltip'e taşınır.
     */
    private static <X, Y> void styleBar(XYChart.Data<X, Y> data, String color, String tooltip) {
        colorBar(data, color);
        if (data.getNode() != null) Tooltip.install(data.getNode(), new Tooltip(tooltip));
        data.nodeProperty().addListener((o, was, is) -> {
            if (is != null) Tooltip.install(is, new Tooltip(tooltip));
        });
    }

    /** Ortak ayarlarla boş bir pasta grafiği üretir. */
    private static PieChart pieChart(String title, String[] colors) {
        PieChart pie = new PieChart();
        pie.setTitle(title);
        // Dilim etiketlerini KAPAT: küçük dilimlerde üst üste biniyorlar. Aynı bilgi
        // (isim + değer) alttaki legend'da zaten okunaklı duruyor.
        pie.setLabelsVisible(false);
        pie.setAnimated(false);   // her aramada "büyüyen dilim" animasyonu = yanlış "değişti" izlenimi
        applyPieColors(pie, colors);
        return pie;
    }
    private static void setPieData(PieChart pie, String[] colors, PieChart.Data... data) {
        pie.getData().setAll(data);
        // Dilim sayısı/sırası değişmiş olabilir; legend renklerini veren stylesheet'i tazele.
        applyPieColors(pie, colors);
        pie.applyCss();
        pie.layout();
        for (int i = 0; i < data.length && i < colors.length; i++) {
            colorPie(data[i], colors[i]);
        }
    }
    private static void colorPie(PieChart.Data data, String color) {
        String style = "-fx-pie-color: " + color + ";";
        if (data.getNode() != null) data.getNode().setStyle(style);
        data.nodeProperty().addListener((o, was, is) -> {
            if (is != null) is.setStyle(style);
        });
    }

    private static void applyPieColors(PieChart pie, String[] colors) {
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < colors.length; i++) {
            css.append(".chart-pie.default-color").append(i)
               .append("{-fx-pie-color:").append(colors[i]).append(";} ");
        }
        String sheet = "data:text/css;base64," + java.util.Base64.getEncoder()
                .encodeToString(css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        pie.getStylesheets().setAll(sheet);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
    public void clear() {

        cmChart.getData().clear();
        cargoYieldChart.getData().clear();
        exFuelChart.getData().clear();
        revenuePie.getData().clear();

        fleetCmChart.getData().clear();
        belfChart.getData().clear();
        revenueStackChart.getData().clear();
        odChart.getData().clear();
        fleetAirlineChart.getData().clear();
        dayChart.getData().clear();
        aircraftChart.getData().clear();
        paretoChart.getData().clear();
        scoreScatter.getData().clear();

        fleetRevenuePie.getData().clear();
        fleetDecisionPie.getData().clear();

        hint.setText("Seçilen kriterlere uygun rota bulunamadı.");
    }
}
 