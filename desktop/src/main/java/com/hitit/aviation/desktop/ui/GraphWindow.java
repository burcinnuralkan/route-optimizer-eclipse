package com.hitit.aviation.desktop.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Graphviz DOT metnini PNG'ye çevirip ayrı bir pencerede gösterir.
 *
 * <p>Bu sınıf {@code DesktopApp}'ten AYRILDI (Extract Class). Orada dış süreç
 * çalıştırma, geçici dosya yönetimi, {@code dot} ikilisini bulma ve pencere
 * kurulumu ana sınıfın içine karışmıştı; oysa hiçbiri "bileşenleri birbirine
 * bağlama" işiyle ilgili değil. Ana sınıf aracı (mediator) rolünde kalmalı,
 * her işi kendisi yapan bir "tanrı sınıf" olmamalı.
 *
 * <p>Dışa açık tek şey {@link #show(String, String, String)}: DOT metnini ver,
 * pencereyi açsın. Graphviz'in nerede olduğu, PNG'nin nasıl üretildiği çağıranı
 * ilgilendirmez.
 */
public final class GraphWindow {

    private GraphWindow() { }

    /**
     * DOT metnini çizip yeni bir pencerede gösterir.
     *
     * @param dot   Graphviz DOT kaynağı
     * @param title pencere başlığı
     * @param hint  görselin altında/üstünde gösterilecek açıklama
     * @throws Exception Graphviz bulunamazsa veya çizim başarısız olursa
     */
    public static void show(String dot, String title, String hint) throws Exception {

        Image image = render(dot);

        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        ScrollPane scrollPane = new ScrollPane(imageView);
        scrollPane.setPannable(true);

        CheckBox fitBox = new CheckBox("Pencereye sığdır");
        fitBox.setSelected(true);
        Runnable applyFit = () -> applyFit(fitBox.isSelected(), scrollPane, imageView, image);
        fitBox.selectedProperty().addListener((o, was, is) -> applyFit.run());
        applyFit.run();

        Label hintLabel = new Label(hint);
        hintLabel.setPadding(new Insets(6, 10, 6, 10));
        hintLabel.setWrapText(true);

        HBox bar = new HBox(12, fitBox, hintLabel);
        bar.setPadding(new Insets(4, 10, 4, 10));

        VBox box = new VBox(bar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(new Scene(box, 1100, 700));
        stage.show();
    }

    /** DOT metnini geçici bir PNG'ye çizer ve görüntü olarak yükler. */
    private static Image render(String dot) throws Exception {
        Path dotFile = Files.createTempFile("route", ".dot");
        Path pngFile = Files.createTempFile("route", ".png");
        Files.writeString(dotFile, dot);

        Process process = new ProcessBuilder(
                resolveDotExecutable(), "-Tpng", "-Gdpi=140",
                dotFile.toString(), "-o", pngFile.toString()).start();

        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "Graphviz 'dot' çalıştırılamadı. Kurulu mu? (brew install graphviz)");
        }
        return new Image(pngFile.toUri().toString());
    }

    /**
     * "Pencereye sığdır" açıkken görsel genişliğe kilitlenir ve yalnızca dikey
     * kaydırma olur; kapalıyken gerçek boyutunda gösterilip iki yönde kaydırılır.
     */
    private static void applyFit(boolean fit, ScrollPane scrollPane, ImageView imageView, Image image) {
        if (fit) {
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
            scrollPane.setFitToWidth(true);
            imageView.fitWidthProperty().bind(
                    scrollPane.viewportBoundsProperty().map(b -> b.getWidth() - 4));
        } else {
            imageView.fitWidthProperty().unbind();
            imageView.setFitWidth(image.getWidth());
            scrollPane.setFitToWidth(false);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        }
    }

    /**
     * Graphviz "dot" çalıştırılabilir dosyasını bulur. Önce GRAPHVIZ_DOT ortam
     * değişkenine, sonra işletim sistemine göre bilinen yollara bakar; bulamazsa PATH.
     */
    private static String resolveDotExecutable() {
        String env = System.getenv("GRAPHVIZ_DOT");
        if (env != null && !env.isBlank() && new File(env).canExecute()) return env;

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates = os.contains("win")
                ? List.of("C:\\Program Files\\Graphviz\\bin\\dot.exe",
                        "C:\\Tools\\Graphviz-15.1.0-win64\\bin\\dot.exe")
                : List.of("/opt/homebrew/bin/dot", "/usr/local/bin/dot", "/usr/bin/dot");
        for (String c : candidates) {
            if (new File(c).canExecute()) return c;
        }
        return "dot"; // PATH'e güven
    }
}
 