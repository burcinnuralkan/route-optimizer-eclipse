package com.hitit.aviation.desktop;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import com.hitit.aviation.core.graph.RouteOptimizer;
import com.hitit.aviation.core.model.FlightDecision;
import com.hitit.aviation.core.model.OptimizerParams;
import com.hitit.aviation.core.model.RouteResult;
import com.hitit.aviation.core.model.ScoredFlight;
import com.hitit.aviation.core.data.FuelPrices;
import com.hitit.aviation.desktop.data.ScheduleStore;
import com.hitit.aviation.desktop.graph.RouteGraphExporter;
import com.hitit.aviation.desktop.ui.Decisions;
import com.hitit.aviation.desktop.ui.GraphWindow;
import com.hitit.aviation.desktop.ui.KpiChartsView;
import com.hitit.aviation.desktop.ui.LegTableFactory;
import com.hitit.aviation.desktop.ui.TarifeView;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class DesktopApp {

    /** Kalkış/varış kutularında "seçim yok" anlamına gelen öğe (tüm veri görünümü). */
    private static final String ALL_CODES = "(hepsi)";

    private final ScheduleStore store;
    private final RouteOptimizer optimizer;

    /**
     * Yakıt fiyatı tablosu. Fiyat eskiden {@code OptimizerParams} içinde sabitti;
     * tarihli tabloya taşındı ki masaüstü ile API aynı sayıyı üretsin ve fiyat
     * güncellendiğinde geçmiş raporlar değişmesin (bkz. {@code FuelPrices}).
     */
    private final FuelPrices fuelPrices;

    private ComboBox<String> fromBox, toBox;
    private ComboBox<OptimizerParams.Objective> objectiveBox;
    private Slider safSlider;
    private Slider kpiSlider;
    private TextField carbonField;
    private TextField reviewBandField;
    private Label bandInfoLabel;          // "= ±X $" etkin band göstergesi
    private Spinner<Integer> maxLegsSpinner;
    private CheckBox onlyRouteBox;
    private TableView<ScoredFlight> legTable;
    private ListView<RouteResult> routeList;
    private Label summaryLabel;

    private KpiChartsView kpiView;        // KPI grafik sekmesi
    private TarifeView tarifeView;        // Tarife (uçuş CRUD) sekmesi
    private TabPane bottomTabs;           // alt sekme grubu (tüm veri modunda "Bacaklar"a geçmek için)
    private Tab legTab;

    /**
     * @param optimizer  rota/filo değerlendirme motoru
     * @param store      tarife verisi ve kalıcılığı
     * @param fuelPrices yakıt fiyatı tablosu
     */
    public DesktopApp(RouteOptimizer optimizer, ScheduleStore store, FuelPrices fuelPrices) {
       this.optimizer = optimizer;
       this.store = store;
       this.fuelPrices = fuelPrices;
    }

    /** Pencereyi kurar ve gösterir. (Eskiden {@code Application.start}.) */
    public void show(Stage stage) throws Exception {
       tarifeView = new TarifeView(store, this::refreshAfterDataChange);

       List<String> codes = selectableCodes();
       fromBox = new ComboBox<>(FXCollections.observableArrayList(codes));
       toBox = new ComboBox<>(FXCollections.observableArrayList(codes));
       // Açılış: kalkış/varış seçili DEĞİL -> tarifenin tamamı gösterilir.
       fromBox.setValue(ALL_CODES);
       toBox.setValue(ALL_CODES);
       {
          String tip = "\"" + ALL_CODES + "\" seçiliyken rota aranmaz; CSV'deki TÜM uçuşlar "
                + "\"Bacaklar\" sekmesinde ve KPI grafiklerinde listelenir.";
          fromBox.setTooltip(new Tooltip(tip));
          toBox.setTooltip(new Tooltip(tip));
       }

       objectiveBox = new ComboBox<>(FXCollections.observableArrayList(OptimizerParams.Objective.values()));
       objectiveBox.setValue(OptimizerParams.Objective.MAX_PROFIT);

       safSlider = new Slider(0, 0.5, 0);
       safSlider.setShowTickLabels(true);
       safSlider.setMajorTickUnit(0.1);

       // KPI etkisi: 0 = salt gelir-gider, 1 = tam KPI etkisi (doluluk/RASK/CASK)
       kpiSlider = new Slider(0, 1, 0.5);
       kpiSlider.setShowTickLabels(true);
       kpiSlider.setMajorTickUnit(0.25);

       carbonField = new TextField("90");
       carbonField.setPrefWidth(70);

       // Karar bandı ($): katkı payı (netCM) ile kıyaslanan tolerans.
       reviewBandField = new TextField("0");
       reviewBandField.setPrefWidth(80);
       reviewBandField.setTooltip(new Tooltip(
             "Girilen değer, rotanın/bacağın KATKI PAYI (contribution margin) ile kıyaslanır:\n"
             + "  katkı payı >  +band  → UÇUR  (FLY, yeşil)\n"
             + "  katkı payı <  −band  → İPTAL (CANCEL, kırmızı)\n"
             + "  |katkı payı| ≤ band  → İNCELE (REVIEW, sarı)\n"
             + "Band bir TOLERANS genişliğidir (≥0). Negatif girersen mutlak değeri alınır.\n"
             + "Kararı yalnızca renk/etiket etkiler; skoru ve sıralamayı DEĞİŞTİRMEZ. 0 = kesin ayrım."));

       bandInfoLabel = new Label();
       bandInfoLabel.setStyle("-fx-text-fill: #555;");
       // Odak alandan çıkınca negatif değeri mutlak değerine sabitle (band ≥ 0).
       reviewBandField.focusedProperty().addListener((o, was, is) -> {
          if (!is) {
             double b = reviewBand();
             String norm = b == Math.rint(b) ? String.valueOf((long) b) : String.valueOf(b);
             if (!norm.equals(reviewBandField.getText().trim())) reviewBandField.setText(norm);
          }
       });

       maxLegsSpinner = new Spinner<>(1, 4, 3);
       maxLegsSpinner.setPrefWidth(70);

       Button searchBtn = new Button("Rotayı bul");
       searchBtn.setDefaultButton(true);
       searchBtn.setOnAction(e -> search());
       Button graphBtn = new Button("Grafiği göster");
       graphBtn.setOnAction(e -> showGraph());

       onlyRouteBox = new CheckBox("sadece rota");
       onlyRouteBox.setSelected(false);
       Button csvBtn = new Button("CSV Aç…");
       csvBtn.setOnAction(e -> openCsv(stage));
       Button addFlightBtn = new Button("Uçuş Ekle…");
       addFlightBtn.setOnAction(e -> tarifeView.promptAdd());
       Button saveCsvBtn = new Button("CSV'ye Kaydet…");
       saveCsvBtn.setOnAction(e -> saveCsv(stage));

       // Üst kontrol çubuğu.
       List<Node> controlItems = new java.util.ArrayList<>(List.of(
             new Label("Kalkış:"), fromBox,
             new Label("Varış:"), toBox,
             new Label("Amaç:"), objectiveBox));
       controlItems.addAll(List.of(new Label("Karbon $/t:"), carbonField));
       controlItems.addAll(List.of(new Label("Max bacak:"), maxLegsSpinner,
             searchBtn, graphBtn, onlyRouteBox));
       HBox controls = new HBox(10, controlItems.toArray(Node[]::new));
       controls.setPadding(new Insets(10));

       Label kpiLabel = new Label("KPI etkisi (skor):");
       kpiLabel.setTooltip(new Tooltip(
             "Doluluk, RASK ve CASK sütunlarını filo ortalamasına göre puanlayıp\n"
             + "rota SKORUNA sınırlı bir bonus/ceza ekler. Gelir/gider/kâr sütunları\n"
             + "değişmez — yalnızca rota listesindeki sıralama değişir. 0 = kapalı."));

       // İkinci çubuk — SAF, KPI, Karar bandı ve veri G/Ç düğmeleri.
       List<Node> safItems = new java.util.ArrayList<>();
       {
          safItems.add(new Label("SAF harman oranı:")); safItems.add(safSlider);
          HBox.setHgrow(safSlider, Priority.ALWAYS);
       }
       {
          safItems.add(kpiLabel); safItems.add(kpiSlider);
          HBox.setHgrow(kpiSlider, Priority.ALWAYS);
       }
       {
          Label bandLabel = new Label("Karar bandı ($):");
          bandLabel.setTooltip(reviewBandField.getTooltip());
          safItems.add(bandLabel);
          safItems.add(reviewBandField);
          safItems.add(bandInfoLabel);
       }
       safItems.addAll(List.of(csvBtn, addFlightBtn, saveCsvBtn));
       HBox safBox = new HBox(10, safItems.toArray(Node[]::new));
       safBox.setPadding(new Insets(0, 10, 10, 10));

       // Karar bandı değişince liste/tablo/grafik/özet yeniden çizilir (skor değişmez).
       {
          reviewBandField.textProperty().addListener((o, was, is) -> {
             updateBandInfo();
             routeList.refresh();
             legTable.refresh();
             if (fleetMode()) {
                // Tüm veri görünümünde özet sayaçları ve grafik renkleri banda bağlı.
                if (!legTable.getItems().isEmpty()) showFleet();
             } else {
                RouteResult sel = routeList.getSelectionModel().getSelectedItem();
                if (sel != null) showRoute(sel);
             }
          });
          updateBandInfo();
       }

       // Kutulardan biri "(hepsi)" olunca düğme etiketi işlevi anlatsın.
       {
          Runnable syncSearchLabel = () ->
                searchBtn.setText(fleetMode() ? "Tüm veriyi göster" : "Rotayı bul");
          fromBox.valueProperty().addListener((o, was, is) -> syncSearchLabel.run());
          toBox.valueProperty().addListener((o, was, is) -> syncSearchLabel.run());
          syncSearchLabel.run();
       }

       // MIN_CO2 modunda KPI bonusu (USD) skora eklenemez; kontrolü kapat.
       Runnable kpiAvailability = () -> {
          boolean minCo2 = objectiveBox.getValue() == OptimizerParams.Objective.MIN_CO2;
          kpiSlider.setDisable(minCo2);
          kpiLabel.setDisable(minCo2);
          kpiLabel.setText(minCo2 ? "KPI etkisi (MIN_CO2'de geçersiz):" : "KPI etkisi (skor):");
          carbonField.setDisable(objectiveBox.getValue() != OptimizerParams.Objective.WEIGHTED);
       };
       objectiveBox.valueProperty().addListener((o, was, is) -> kpiAvailability.run());
       kpiAvailability.run();

       routeList = new ListView<>();
       routeList.setPrefHeight(140);
       routeList.setMinHeight(120);   // KPI grafik sekmesi büyüse bile liste ezilmesin
       routeList.setCellFactory(lv -> new ListCell<>() {
          @Override protected void updateItem(RouteResult r, boolean empty) {
             super.updateItem(r, empty);
             if (empty || r == null) {
                setText(null);
                setStyle("");
                return;
             }
             FlightDecision d = r.decision(reviewBand());
             setText(String.format(
                   "%s  |  %s  |  katkı payı $%,.0f  |  kâr $%,.0f  |  CO2 %.1f t  |  skor %,.0f",
                   r.pathString(), Decisions.label(d), r.totalContributionMarginUsd(),
                   r.totalProfitUsd(), r.totalCo2Kg() / 1000, r.score().value()));
             setStyle(Decisions.cellStyle(d) + "-fx-font-weight: bold;");
          }
       });
       routeList.getSelectionModel().selectedItemProperty()
             .addListener((obs, old, sel) -> showRoute(sel));

       legTable = LegTableFactory.build(this::reviewBand);
       summaryLabel = new Label("Kalkış/varış seçip \"Rotayı Bul\"a basın.");
       summaryLabel.setPadding(new Insets(8));
       summaryLabel.setStyle("-fx-font-weight: bold");

       // Alt bölüm: "Tarife" · "Bacaklar" · "KPI Grafikleri" sekmeleri.
       Node bottom = buildBottom();

       // Rota listesi ile alt sekmeler, sürüklenebilir bir bölme çizgisiyle ayrılır:
       // kullanıcı üst/alt alanların yüksekliğini fareyle ayarlayabilir.
       VBox topPane = new VBox(4, new Label("  Bulunan rotalar (skora göre sıralı):"), routeList);
       VBox.setVgrow(routeList, Priority.ALWAYS);
       VBox botPane = new VBox(4, new Label("  Seçili rota:"), bottom);
       VBox.setVgrow(bottom, Priority.ALWAYS);

       SplitPane split = new SplitPane(topPane, botPane);
       split.setOrientation(javafx.geometry.Orientation.VERTICAL);
       split.setDividerPositions(0.32);

       VBox root = new VBox(controls, safBox, split, summaryLabel);
       VBox.setVgrow(split, Priority.ALWAYS);

       stage.setTitle("Rota Optimizasyonu — Kâr & Karbon");
       stage.setScene(new Scene(root, 1050, 640));
       stage.show();

       // Açılışta tarifenin tamamını göster: uygulama boş bir tabloyla değil, veriyle açılsın.
       search();
    }

    /** Alt sekme grubunu (Tarife/Bacaklar/KPI) kurar. */
    private Node buildBottom() {
       bottomTabs = new TabPane();
       bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
       {
          Tab tarifeTab = new Tab("Tarife (uçuşlar)", tarifeView.getNode());
          tarifeTab.setClosable(false);
          bottomTabs.getTabs().add(tarifeTab);
       }
       legTab = new Tab("Bacaklar", legTable);
       legTab.setClosable(false);
       bottomTabs.getTabs().add(legTab);
       {
          kpiView = new KpiChartsView();
          Tab kpiTab = new Tab("KPI Grafikleri", kpiView.getNode());
          kpiTab.setClosable(false);
          bottomTabs.getTabs().add(kpiTab);
       }
       return bottomTabs;
    }

    private List<String> sortedCodes() {
       return store.airports().keySet().stream().sorted().collect(Collectors.toList());
    }

    /** Kombo kutularının içeriği: tüm veri modu açıksa başa "(hepsi)" eklenir. */
    private List<String> selectableCodes() {
       List<String> codes = new java.util.ArrayList<>();
       codes.add(ALL_CODES);
       codes.addAll(sortedCodes());
       return codes;
    }

    /** Kalkış veya varış seçilmemiş (= "(hepsi)") mi? Öyleyse rota aranmaz, tüm veri gösterilir. */
    private boolean fleetMode() {
       return ALL_CODES.equals(fromBox.getValue()) || ALL_CODES.equals(toBox.getValue())
             || fromBox.getValue() == null || toBox.getValue() == null;
    }

    private void openCsv(Stage stage) {
       FileChooser chooser = new FileChooser();
       chooser.setTitle("CSV Aç");
       chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
       File file = chooser.showOpenDialog(stage);
       if (file == null) return;
       try {
          int n = store.loadFrom(file.toPath());
          refreshAfterDataChange();
          Alert alert = new Alert(Alert.AlertType.INFORMATION);
          alert.setContentText(n + " uçuş yüklendi.");
          alert.showAndWait();
       } catch (Exception ex) {
          new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
       }
    }

    private void saveCsv(Stage stage) {
       FileChooser chooser = new FileChooser();
       chooser.setTitle("CSV Kaydet");
       chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
       File file = chooser.showSaveDialog(stage);
       if (file == null) return;
       try {
          store.export(file.toPath());
          new Alert(Alert.AlertType.INFORMATION, "CSV kaydedildi").showAndWait();
       } catch (Exception ex) {
          new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
       }
    }

    /**
     * Veri (CSV yükleme / uçuş ekle-sil-düzenle) değiştiğinde çağrılır: havalimanı
     * kombolarını tazeler, bayat rota/bacak sonuçlarını temizler, tarife tablosunu yeniler.
     */
    private void refreshAfterDataChange() {
       List<String> codes = selectableCodes();
       String prevFrom = fromBox.getValue();
       String prevTo = toBox.getValue();

       fromBox.setItems(FXCollections.observableArrayList(codes));
       toBox.setItems(FXCollections.observableArrayList(codes));
       fromBox.setValue(codes.contains(prevFrom) ? prevFrom : (codes.isEmpty() ? null : codes.get(0)));
       toBox.setValue(codes.contains(prevTo) ? prevTo : (codes.isEmpty() ? null : codes.get(codes.size() - 1)));

       routeList.getItems().clear();
       legTable.getItems().clear();
       tarifeView.refreshItems();

       // Tüm veri modundaysak yeni CSV'yi hemen göster; rota modunda bayat sonuç
       // bırakmamak için kullanıcıdan tekrar arama istenir.
       if (fleetMode()) {
          showFleet();
       } else {
          summaryLabel.setText("Veri güncellendi — \"Rotayı bul\"a tekrar basın.");
       }
    }

    /**
     * Üst çubuktaki kontrollerden optimizasyon parametrelerini kurar.
     *
     * <p>{@link OptimizerParams} geçersiz kurulamaz; kontrollerin aralıkları
     * (slider 0..0.5, spinner 1..4) zaten geçerli değer üretir. Serbest metin olan
     * tek alan karbon fiyatıdır: geçersiz/negatif girdi sessizce yok sayılır ve
     * varsayılan korunur ({@link #carbonPrice()}).
     */
    private OptimizerParams currentParams() {
       OptimizerParams.Builder b = OptimizerParams.builder()
             .objective(objectiveBox.getValue())
             .maxLegs(maxLegsSpinner.getValue())
             .fuel(fuel(safSlider.getValue()))
             // KPI etkisi: slider kpiStrength'e bağlanır; oran (1 : 0.6 : 0.6) sabit.
             .kpi(k -> k.kpiStrength(kpiSlider.getValue())
                   .loadFactorWeight(1.0)
                   .raskWeight(0.6)
                   .caskWeight(0.6));

       carbonPrice().ifPresent(b::carbonPricePerTon);
       return b.build();
    }

    /**
     * Yakıt ayarları: SAF harmanı slider'dan, FİYATLAR bugünün tablo satırından.
     *
     * <p>Fiyat okunamazsa hesap durdurulmaz: kodun eski varsayılanlarına düşülür
     * ve kullanıcıya bir uyarı gösterilir. API'de aynı durumda istisna atılıyor
     * (bkz. {@code FleetParams#fuel}) ve fark bilinçli: orada çağıran bir program,
     * yanlış sayı yerine hata alması doğru. Burada çağıran bir insan ve elindeki
     * tarifeyi hiç göremez hâle getirmek, yaklaşık bir fiyatla görmesinden kötü.
     */
    private java.util.function.Consumer<OptimizerParams.FuelParams.Builder> fuel(double safBlend) {
       try {
          java.time.LocalDate today = java.time.LocalDate.now();
          double jet = fuelPrices.jetA1PerKgUsd(today);
          double saf = fuelPrices.safPerKgUsd(today);
          return f -> f.safBlendRatio(safBlend).jetFuelPricePerKg(jet).safPricePerKg(saf);
       } catch (Exception e) {
          summaryLabel.setText("Yakıt fiyatı okunamadı, varsayılan fiyat kullanıldı: "
                + e.getMessage());
          return f -> f.safBlendRatio(safBlend);
       }
    }

    /** Karbon $/t alanındaki geçerli (sayısal ve negatif olmayan) değer; yoksa boş. */
    private java.util.OptionalDouble carbonPrice() {
       try {
          double v = Double.parseDouble(carbonField.getText().trim());
          return v >= 0 ? java.util.OptionalDouble.of(v) : java.util.OptionalDouble.empty();
       } catch (NumberFormatException e) {
          return java.util.OptionalDouble.empty();
       }
    }

    private void search() {
       if (fleetMode()) {
          showFleet();
          return;
       }

       OptimizerParams p = currentParams();

       List<RouteResult> routes;
       try {
          routes = optimizer.bestRoutesPerState(store.flights(), fromBox.getValue(), toBox.getValue(), p);
       } catch (RuntimeException ex) {
          routeList.getItems().clear();
          legTable.getItems().clear();
          summaryLabel.setText("Rota hesaplanamadı: " + ex.getMessage());
          new Alert(Alert.AlertType.ERROR, "Rota hesaplanamadı.\n\n" + ex.getMessage()).showAndWait();
          return;
       }

       routeList.setItems(FXCollections.observableArrayList(
             routes.subList(0, Math.min(10, routes.size()))));
       if (routes.isEmpty()) {
          legTable.getItems().clear();
          summaryLabel.setText("Rota bulunamadı — tarifede uygun bağlantı yok.");
       } else {
          routeList.getSelectionModel().selectFirst();
       }
    }

    /**
     * Kalkış/varış seçilmediğinde çalışan "tüm veri" görünümü: rota aranmaz,
     * CSV'deki her uçuş değerlendirilip bacak tablosuna ve KPI grafiklerine dökülür.
     * Böylece hangi uçuşun İPTAL adayı olduğu tek ekranda görülür.
     */
    private void showFleet() {
       OptimizerParams p = currentParams();

       List<ScoredFlight> fleet;
       try {
          fleet = evaluateFleet(p);
       } catch (RuntimeException ex) {
          legTable.getItems().clear();
          summaryLabel.setText("Tarife değerlendirilemedi: " + ex.getMessage());
          new Alert(Alert.AlertType.ERROR, "Tarife değerlendirilemedi.\n\n" + ex.getMessage()).showAndWait();
          return;
       }

       routeList.getItems().clear();
       legTable.setItems(FXCollections.observableArrayList(fleet));

       double band = reviewBand();
       double revenue = 0, cost = 0, cm = 0, co2 = 0;
       int fly = 0, review = 0, cancel = 0;
       for (ScoredFlight s : fleet) {
          var e = s.evaluation();
          revenue += e.revenueUsd();
          cost += e.costUsd();
          cm += e.contributionMarginUsd();
          co2 += e.co2Kg();
          switch (e.decision(band)) {
             case FLY -> fly++;
             case REVIEW -> review++;
             case CANCEL -> cancel++;
          }
       }

       summaryLabel.setText(String.format(
             "TÜM TARİFE (%d uçuş)   |   UÇUR %d · İNCELE %d · İPTAL %d  (band ±$%,.0f)"
             + "   |   Gelir $%,.0f   Gider $%,.0f   Kâr $%,.0f   Katkı payı $%,.0f   CO2 %.1f t",
             fleet.size(), fly, review, cancel, band,
             revenue, cost, revenue - cost, cm, co2 / 1000));

       if (kpiView != null) kpiView.updateFleet(fleet, band);
       if (bottomTabs != null && legTab != null) bottomTabs.getSelectionModel().select(legTab);
    }

    /** Tarifenin tamamını değerlendirir; skorlar satırların İÇİNDE taşınır. */
    private List<ScoredFlight> evaluateFleet(OptimizerParams p) {
       return optimizer.evaluateFleet(store.flights(), p);
    }

    private void showRoute(RouteResult r) {
       if (r == null) return;
       legTable.setItems(FXCollections.observableArrayList(r.legs()));

       FlightDecision d = r.decision(reviewBand());
       String decisionStr = String.format("KARAR: %s  (katkı payı $%,.0f, band ±$%,.0f)   |   ",
             Decisions.label(d), r.totalContributionMarginUsd(), reviewBand());
       summaryLabel.setText(String.format(
             "%sTOPLAM  %s   |   Gelir $%,.0f (yolcu $%,.0f + yan $%,.0f + kargo $%,.0f)   Gider $%,.0f   Kâr $%,.0f"
             + "   |   Yakıt %.1f t   CO2 %.1f t (yolcu %.1f / kargo %.1f)   Ort. yolcu başı CO2 %.0f kg",
             decisionStr,
             r.pathString(), r.totalRevenueUsd(), r.totalPaxRevenueUsd(),
             r.totalAncillaryRevenueUsd(), r.totalCargoRevenueUsd(),
             r.totalCostUsd(), r.totalProfitUsd(),
             r.totalFuelKg() / 1000, r.totalCo2Kg() / 1000,
             r.totalPassengerCo2Kg() / 1000, r.totalCargoCo2Kg() / 1000,
             r.totalCo2PerPaxKg()));

       if (kpiView != null) kpiView.update(r, reviewBand());
    }

    /**
     * Grafiği üretip {@link GraphWindow}'a devreder. Bu metodun tek işi DOT metnini
     * SEÇMEK; çizim, dış süreç yönetimi ve pencere kurulumu GraphWindow'un işi.
     */
    private void showGraph() {
       RouteResult selected = routeList.getSelectionModel().getSelectedItem();
       boolean wholeNetwork = selected == null;
       if (wholeNetwork && !fleetMode()) {
          new Alert(Alert.AlertType.WARNING, "Önce rota seçin").showAndWait();
          return;
       }
       try {
          RouteGraphExporter exporter = new RouteGraphExporter();
          String dot = wholeNetwork
                // Rota seçilmedi: tüm ağı hat bazında, karar renkleriyle çiz.
                ? exporter.toDotFleet(evaluateFleet(currentParams()), reviewBand())
                : exporter.toDot(store.flights(), selected, onlyRouteBox.isSelected());

          String title = wholeNetwork
                ? "Ağ grafiği — tüm tarife"
                : "Rota grafiği — " + selected.pathString();
          String hint = wholeNetwork
                ? "Tüm ağ: her ok bir HAT (kalkış-varış çifti), etiketi \"kaç uçuş · toplam katkı payı\". "
                + "Ok rengi hattın kararı: yeşil=UÇUR, sarı=İNCELE, kırmızı=İPTAL. "
                + "Mor çerçeveli düğümler yoğun (aktarma merkezi) havalimanlarıdır."
                : "Kırmızı = seçili rota (sıra · uçuş no · saat · mesafe).  "
                + "Gri = tarifenin geri kalanı; aynı çift arasındaki uçuşlar tek okta toplanır.";

          GraphWindow.show(dot, title, hint);
       } catch (Exception ex) {
          new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
          ex.printStackTrace();
       }
    }

    /** "Karar bandı ($)" alanındaki değeri okur; geçersizse 0 (kesin >0/<0 ayrımı). */
    private double reviewBand() {
       if (reviewBandField == null) return 0;
       try {
          return Math.abs(Double.parseDouble(reviewBandField.getText().trim()));
       } catch (NumberFormatException e) {
          return 0;
       }
    }

    /** "= ±X $" etkin band göstergesini günceller (band bir tolerans genişliğidir). */
    private void updateBandInfo() {
       if (bandInfoLabel == null) return;
       bandInfoLabel.setText(String.format("= ±$%,.0f tolerans", reviewBand()));
    }

}
 