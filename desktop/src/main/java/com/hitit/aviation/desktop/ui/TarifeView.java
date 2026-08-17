package com.hitit.aviation.desktop.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Function;
import java.io.IOException;
import java.net.URL;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;

import com.hitit.aviation.core.model.Airport;
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.desktop.data.ScheduleStore;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;

/**
 * "Tarife" sekmesi: tüm uçuşların tablosu + Ekle/Düzenle/Sil araç çubuğu.
 * Değişiklikleri {@link ScheduleStore}'a uygular (otomatik kalıcı) ve ardından
 * {@code onDataChanged} ile uygulamanın geri kalanını (kombolar/rota listesi) tazeler.
 */
public class TarifeView {
	

    private static final DateTimeFormatter DTS = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ScheduleStore store;
    private final Runnable onDataChanged;
    private final TableView<Flight> table;
    private final Node node;

    @FXML private Button addBtn;
    @FXML private Button editBtn;
    @FXML private Button deleteBtn;
    @FXML private Button saveBtn;
    @FXML private Button undoBtn;

    @FXML private Label titleLabel;
    @FXML private Label countLabel;

    @FXML private StackPane tableHolder;

    @FXML
    private Label dirtyLabel;

    public TarifeView(ScheduleStore store, Runnable onDataChanged) {
        this.store = store;
        this.onDataChanged = onDataChanged;

        this.table = buildFlightsTable();

        try {
            URL url = getClass().getResource("TarifeView.fxml");

            FXMLLoader loader = new FXMLLoader(url);
            loader.setControllerFactory(cls -> this);

            Parent root = loader.load();

            this.node = root;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        refreshItems();
        countLabel.setText(table.getItems().size() + " uçuş");
    }

    public Node getNode() {
        return node;
    }

    /** Tabloyu güncel tarifeyle doldurur ve Kaydet/Geri Al durumunu tazeler. */
    public void refreshItems() {
        table.setItems(FXCollections.observableArrayList(store.flights()));
        updateEditState();
    }


    /** Kaydet/Geri Al düğmelerini ve "kaydedilmemiş" göstergesini güncelle. */
    private void updateEditState() {
        if (saveBtn == null) return;
        saveBtn.setDisable(!store.isDirty());
        undoBtn.setDisable(!store.canUndo());
        dirtyLabel.setText(store.isDirty() ? "● kaydedilmemiş değişiklik" : "");
    }

    @FXML
    private void save() {
        try {
            store.save();
            updateEditState();
        } catch (Exception ex) {
            error(ex);
        }
    }

    @FXML
    private void undo() {
        if (!store.undo()) return;
        refreshItems();
        onDataChanged.run();
        updateEditState();
    }

    /** "Tarife" tablosu — tüm uçuşların özet sütunları (salt görüntü; düzenleme diyalogla). */
    private TableView<Flight> buildFlightsTable() {
        TableView<Flight> t = new TableView<>();
        t.setPlaceholder(new Label("Tarife boş — \"Uçuş Ekle\" ile ekleyin."));
        t.getColumns().addAll(java.util.List.of(
                strCol("No", Flight::flightNo),
                strCol("Havayolu", Flight::airlineCode),
                strCol("Rota", f -> f.from().code() + " → " + f.to().code()),
                strCol("Kalkış", f -> f.schedDep().format(DTS)),
                strCol("Varış", f -> f.schedArr().format(DTS)),
                strCol("Koltuk (E/B)", f -> f.econSeats() + "/" + f.busSeats()),
                strCol("Yolcu (E/B)", f -> n(f.econPax()) + "/" + n(f.busPax())),
                strCol("Kargo (kg)", f -> String.format("%,.0f", f.cargoKg())),
                strCol("Gelir ($)", f -> String.format("%,.0f",
                        f.paxRevenueUsd() + f.ancillaryRevenueUsd() + f.cargoRevenueUsd()))));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        return t;
    }

    private static TableColumn<Flight, String> strCol(String title, Function<Flight, String> value) {
        TableColumn<Flight, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return col;
    }

    //Yeni uçuş ekleme diyaloğu
    @FXML
    public void promptAdd() {
        TextInputDialog dlg = new TextInputDialog("TK001,ESB,JFK,2026-07-16T08:00,2026-07-16T17:00");
        dlg.setTitle("Uçuş Ekle");
        dlg.setHeaderText("No, From, To, Kalkış, Varış");
        Optional<String> result = dlg.showAndWait();
        if (result.isEmpty()) return;
        try {
            String[] p = result.get().split(",");
            Airport from = requireAirport(p[1].trim());
            Airport to = requireAirport(p[2].trim());
            Flight flight = Flight.of(p[0].trim(), from, to,
                    LocalDateTime.parse(p[3].trim()), LocalDateTime.parse(p[4].trim()));
            store.add(flight);
            onDataChanged.run();
            table.getSelectionModel().select(flight);
            updateEditState();
        } catch (Exception ex) {
            error(ex);
        }
    }

    @FXML
    private void promptEdit() {
        Flight sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        TextInputDialog dlg = new TextInputDialog(String.join(",",
                sel.flightNo(), sel.from().code(), sel.to().code(),
                sel.schedDep().toString(), sel.schedArr().toString()));
        dlg.setTitle("Uçuş Düzenle");
        dlg.setHeaderText("No, From, To, Kalkış, Varış  (diğer alanlar korunur)");
        Optional<String> result = dlg.showAndWait();
        if (result.isEmpty()) return;
        try {
            String[] p = result.get().split(",");
            Airport from = requireAirport(p[1].trim());
            Airport to = requireAirport(p[2].trim());
            Flight updated = copyWith(sel, p[0].trim(), from, to,
                    LocalDateTime.parse(p[3].trim()), LocalDateTime.parse(p[4].trim()));
            store.replace(sel, updated);
            onDataChanged.run();
            table.getSelectionModel().select(updated);
            updateEditState();
        } catch (Exception ex) {
            error(ex);
        }
    }

    @FXML
    private void deleteSelected() {
        Flight sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                sel.flightNo() + "  (" + sel.from().code() + " → " + sel.to().code() + ")  silinsin mi?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        store.remove(sel);
        onDataChanged.run();
        updateEditState();
    }

    private Airport requireAirport(String code) {
        Airport a = store.airport(code);
        if (a == null) throw new IllegalArgumentException("Tanımsız havalimanı kodu: " + code);
        return a;
    }

    /** Orijinal uçuşun kopyasını üretir; yalnızca 5 çekirdek alanı değiştirir (kayıpsız). */
    private static Flight copyWith(Flight o, String no, Airport from, Airport to,
                                   LocalDateTime dep, LocalDateTime arr) {
        return Flight.builder()
                .flightNo(no).airlineCode(o.airlineCode())
                .tailNumber(o.tailNumber()).aircraftType(o.aircraftType())
                .from(from).to(to).schedDep(dep).schedArr(arr)
                .actualDep(o.actualDep()).actualArr(o.actualArr())
                .econSeats(o.econSeats()).busSeats(o.busSeats())
                .econPax(o.econPax()).busPax(o.busPax())
                .cargoKg(o.cargoKg()).cargoCapacityKg(o.cargoCapacityKg())
                .paxRevenueUsd(o.paxRevenueUsd()).ancillaryRevenueUsd(o.ancillaryRevenueUsd())
                .cargoRevenueUsd(o.cargoRevenueUsd())
                .crewCostUsd(o.crewCostUsd()).ownershipCostUsd(o.ownershipCostUsd())
                .maintenanceCostUsd(o.maintenanceCostUsd()).overheadCostUsd(o.overheadCostUsd())
                .navCostUsd(o.navCostUsd()).airportCostUsd(o.airportCostUsd())
                .fuelKg(o.fuelKg()).distanceKm(o.distanceKm())
                .build();
    }

    private static void error(Exception ex) {
        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
    }

    /** Tam sayıysa ondalıksız gösterir (150.0 → "150"). */
    private static String n(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
    @FXML
    private void initialize() {

        tableHolder.getChildren().add(table);

        editBtn.disableProperty()
                .bind(table.getSelectionModel()
                        .selectedItemProperty()
                        .isNull());

        deleteBtn.disableProperty()
                .bind(table.getSelectionModel()
                        .selectedItemProperty()
                        .isNull());

        updateEditState();
    }
}
 