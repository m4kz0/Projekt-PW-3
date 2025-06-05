package org.example;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.animation.AnimationTimer;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// symulacja stacji paliw - interfejs gui w javafx

public class GUI extends Application {

    // glowna referencja do symulacji
    private Simulation simulation;
    // komponenty gui
    private TextArea logArea;
    private Label statusLabel;
    private ListView<String> statsListView;
    private Canvas stationCanvas;
    private GraphicsContext gc;

    // przyciski kontrolne
    private Button startButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;

    // bufor wiadomosci log
    private final ObservableList<String> logBuffer = FXCollections.observableArrayList();
    private final int MAX_LOG_LINES = 500;

    // punkt wejscia i inicjalizacja javafx
    @Override
    public void start(Stage primaryStage) {
        try {
            // ladowanie konfiguracji
            Config cfg = Config.load("config.yaml");
            simulation = new Simulation(cfg);

            // przekierowanie standardowego wyjscia do gui
            redirectSystemOut();

            // ustawienie glownego okna
            primaryStage.setTitle("Symulacja Stacji Paliw");

            // utworzenie glownego kontenera
            BorderPane root = new BorderPane();

            // dodanie panelow
            root.setTop(createControlPanel());
            root.setCenter(createVisualizationPanel());
            root.setRight(createStatsPanel());
            root.setBottom(createLogPanel());

            // utworzenie sceny
            Scene scene = new Scene(root, 1200, 800);
            try {
                scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
            } catch (Exception e) {
                System.out.println("Nie można załadować arkusza stylów: " + e.getMessage());
            }

            primaryStage.setScene(scene);
            primaryStage.show();

            // dodanie obslugi zamkniecia okna
            primaryStage.setOnCloseRequest(e -> {
                if (simulation != null) {
                    simulation.stop();
                }
                Platform.exit();
            });

            // uruchomienie timera aktualizujacego statystyki
            startStatsUpdateTimer();

        } catch(Exception e) {
            e.printStackTrace();
            showErrorDialog("Błąd inicjalizacji", "Nie można uruchomić symulacji: " + e.getMessage());
        }
    }

    // przekierowanie standardowego wyjscia do gui
    private void redirectSystemOut() {
        PrintStream originalOut = System.out;

        // nowy printstream przekierowujacy do gui
        PrintStream guiOut = new PrintStream(System.out) {
            @Override
            public void println(String x) {
                originalOut.println(x); // zachowanie oryginalnego wyjscia konsoli

                // aktualizacja gui w watku javafx
                Platform.runLater(() -> {
                    // dodanie linii do bufora
                    logBuffer.add(x);

                    // ograniczenie rozmiaru bufora
                    if (logBuffer.size() > MAX_LOG_LINES) {
                        logBuffer.remove(0);
                    }

                    // aktualizacja logarea
                    if (logArea != null) {
                        logArea.clear();
                        for (String line : logBuffer) {
                            logArea.appendText(line + "\n");
                        }
                        // przewiniecie na dol
                        logArea.setScrollTop(Double.MAX_VALUE);
                    }
                });
            }
        };

        System.setOut(guiOut);
    }

    // tworzenie panelu kontrolnego z przyciskami
    private HBox createControlPanel() {
        HBox controlPanel = new HBox(10);
        controlPanel.setPadding(new Insets(10));
        controlPanel.setAlignment(Pos.CENTER_LEFT);
        controlPanel.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");

        // przyciski kontrolne
        startButton = new Button("Rozpocznij symulację");
        pauseButton = new Button("Wstrzymaj symulację");
        resumeButton = new Button("Wznów symulację");
        stopButton = new Button("Zatrzymaj symulację");

        // ustawienie id dla stylow css
        startButton.setId("startButton");
        pauseButton.setId("pauseButton");
        resumeButton.setId("resumeButton");
        stopButton.setId("stopButton");

        // etykieta statusu
        statusLabel = new Label("Gotowy do uruchomienia");
        statusLabel.setId("statusLabel");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        statusLabel.setPadding(new Insets(0, 0, 0, 20));

        // poczatkowy stan przyciskow
        pauseButton.setDisable(true);
        resumeButton.setDisable(true);
        stopButton.setDisable(true);

        // akcje przyciskow
        startButton.setOnAction(e -> startSimulation());
        pauseButton.setOnAction(e -> pauseSimulation());
        resumeButton.setOnAction(e -> resumeSimulation());
        stopButton.setOnAction(e -> stopSimulation());

        // dodanie przyciskow i etykiety do panelu
        controlPanel.getChildren().addAll(startButton, pauseButton, resumeButton, stopButton, statusLabel);

        return controlPanel;
    }

    // tworzenie panelu wizualizacji stacji paliw
    private Pane createVisualizationPanel() {
        StackPane visualizationPanel = new StackPane();
        visualizationPanel.setPadding(new Insets(10));
        visualizationPanel.setStyle("-fx-background-color: white;");

        // utworzenie canvas dla rysowania
        stationCanvas = new Canvas(700, 500);
        gc = stationCanvas.getGraphicsContext2D();

        // dodanie canvas do panelu
        visualizationPanel.getChildren().add(stationCanvas);

        // rysowanie pustej stacji
        drawStation();

        return visualizationPanel;
    }

    // tworzenie panelu statystyk
    private VBox createStatsPanel() {
        VBox statsPanel = new VBox(10);
        statsPanel.setPadding(new Insets(10));
        statsPanel.setPrefWidth(250);
        statsPanel.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-border-width: 0 0 0 1;");

        // naglowek
        Label statsHeader = new Label("Statystyki");
        statsHeader.setFont(Font.font("System", FontWeight.BOLD, 16));

        // lista statystyk
        statsListView = new ListView<>();
        statsListView.setPrefHeight(Integer.MAX_VALUE);

        // przycisk odswiezania
        Button refreshButton = new Button("Odśwież statystyki");
        refreshButton.setMaxWidth(Double.MAX_VALUE);
        refreshButton.setOnAction(e -> updateStats());

        // dodanie komponentow do panelu
        statsPanel.getChildren().addAll(statsHeader, statsListView, refreshButton);
        VBox.setVgrow(statsListView, Priority.ALWAYS);

        return statsPanel;
    }

    // tworzenie panelu logow
    private VBox createLogPanel() {
        VBox logPanel = new VBox(5);
        logPanel.setPadding(new Insets(10));
        logPanel.setPrefHeight(200);
        logPanel.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");

        // naglowek
        Label logHeader = new Label("Logi");
        logHeader.setFont(Font.font("System", FontWeight.BOLD, 14));

        // obszar logow
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setFont(Font.font("Monospaced", 12));

        // przyciski kontrolne logow
        HBox logControls = new HBox(10);
        Button clearButton = new Button("Wyczyść logi");
        clearButton.setOnAction(e -> {
            logBuffer.clear();
            logArea.clear();
        });

        // checkbox autoscroll
        CheckBox autoScrollCheck = new CheckBox("Auto-przewijanie");
        autoScrollCheck.setSelected(true);
        autoScrollCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });

        // dodanie kontrolek do panelu kontrolnego logow
        logControls.getChildren().addAll(clearButton, autoScrollCheck);

        // dodanie komponentow do panelu logow
        logPanel.getChildren().addAll(logHeader, logArea, logControls);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        return logPanel;
    }

    // timer aktualizujacy statystyki i wizualizacje
    private void startStatsUpdateTimer() {
        AnimationTimer timer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                // aktualizuj co okolo 1 sekunde
                if (now - lastUpdate >= 1_000_000_000) {
                    updateStats();
                    drawStation();
                    lastUpdate = now;
                }
            }
        };
        timer.start();
    }

    // rysowanie stanu stacji paliw - wersja z wizualizacja dystrybrutorow
    private void drawStation() {
        if (simulation == null || gc == null) return;

        // czyszczenie canvas
        gc.clearRect(0, 0, stationCanvas.getWidth(), stationCanvas.getHeight());

        // tlo biale
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, stationCanvas.getWidth(), stationCanvas.getHeight());

        // pobranie danych z symulacji i konfiguracji
        Config cfg = simulation.getConfig();
        int M = cfg.M; // liczba stanowisk
        int pumpsPerSpot = cfg.pumpsPerSpot; // dystrybutory na stanowisko
        int K = cfg.K; // liczba kas

        int totalPumps = simulation.getTotalPumps();
        int usedPumps = simulation.getUsedPumps();
        int totalCashiers = simulation.getTotalCashiers();
        int usedCashiers = simulation.getUsedCashiers();
        boolean isStationClosed = simulation.isStationClosed();

        // pobierz mape typow paliw dla dystrybutoorow
        Map<Integer, String> pumpFuelTypes = simulation.getPumpFuelTypes();
        // nowe: pobierz liste aktualnie uzywanych dystrybutoorow
        Set<Integer> currentlyUsedPumps = simulation.getCurrentlyUsedPumps();

        // kolory
        Color freeColor = Color.GREEN;
        Color usedColor = Color.RED;
        Color closedColor = Color.LIGHTGRAY;

        // wymiary canvas
        double canvasWidth = stationCanvas.getWidth();
        double canvasHeight = stationCanvas.getHeight();
        double margin = 20;

        // dynamiczne obliczanie rozmiarow dystrybutoorow
        double basePumpWidth = Math.max(35, 80 - M * 3); // min 35 max 80
        double basePumpHeight = Math.max(45, 100 - M * 6); // min 45 max 100
        double pumpSpacing = Math.max(8, 15 - M * 2); // min 8 max 15

        // obliczanie ukladu stanowisk
        int stationsPerRow = Math.min(M, 4); // maksymalnie 4 stanowiska w rzedzie
        int rows = (M + stationsPerRow - 1) / stationsPerRow; // liczba rzedow

        // obliczanie rozmiaru pojedynczego stanowiska
        int maxPumpsInRow = (pumpsPerSpot <= 4) ? 2 : (int) Math.ceil(Math.sqrt(pumpsPerSpot));
        int maxPumpsInCol = (pumpsPerSpot + maxPumpsInRow - 1) / maxPumpsInRow;

        double stationWidth = maxPumpsInRow * (basePumpWidth + pumpSpacing) - pumpSpacing + 20;
        double stationHeight = maxPumpsInCol * (basePumpHeight + pumpSpacing) - pumpSpacing + 40;

        // dostepna przestrzen dla stanowisk
        double availableWidth = canvasWidth - 2 * margin - 300; // 300 na budynek kas
        double availableHeight = canvasHeight * 0.6;

        // sprawdzenie czy stanowiska zmieszcza sie i skalowanie jesli potrzeba
        double requiredWidth = stationsPerRow * stationWidth + (stationsPerRow - 1) * 30;
        double requiredHeight = rows * stationHeight + (rows - 1) * 20;

        double scaleX = Math.min(1.0, availableWidth / requiredWidth);
        double scaleY = Math.min(1.0, availableHeight / requiredHeight);
        double scale = Math.min(scaleX, scaleY);

        // zastosowanie skali
        double pumpWidth = basePumpWidth * scale;
        double pumpHeight = basePumpHeight * scale;
        double scaledPumpSpacing = pumpSpacing * scale;
        double scaledStationWidth = stationWidth * scale;
        double scaledStationHeight = stationHeight * scale;

        // obliczanie pozycji startowej (wysrodkowanie)
        double totalStationsWidth = stationsPerRow * scaledStationWidth + (stationsPerRow - 1) * 30 * scale;
        double startX = (availableWidth - totalStationsWidth) / 2 + margin;
        double startY = 50;

        // rysowanie stanowisk
        int pumpIndex = 0;
        for (int stationIdx = 0; stationIdx < M; stationIdx++) {
            int row = stationIdx / stationsPerRow;
            int col = stationIdx % stationsPerRow;

            double stationX = startX + col * (scaledStationWidth + 30 * scale);
            double stationY = startY + row * (scaledStationHeight + 20 * scale);

            // naglowek stanowiska (bez typu paliwa)
            gc.setFill(Color.BLACK);
            gc.setFont(Font.font("System", FontWeight.BOLD, Math.max(10, 14 * scale)));
            String stationName = "STANOWISKO " + (stationIdx + 1);
            gc.fillText(stationName, stationX, stationY - 5);

            // rysowanie dystrybutoorow w tym stanowisku
            for (int pumpInStation = 0; pumpInStation < pumpsPerSpot; pumpInStation++) {
                int pumpRow = pumpInStation / maxPumpsInRow;
                int pumpCol = pumpInStation % maxPumpsInRow;

                double pumpX = stationX + pumpCol * (pumpWidth + scaledPumpSpacing);
                double pumpY = stationY + 10 + pumpRow * (pumpHeight + scaledPumpSpacing);

                // logika kolorowania - sprawdza czy konkretny dystrybutor jest uzywany
                if (isStationClosed) {
                    gc.setFill(closedColor);
                } else {
                    // sprawdza czy ten konkretny dystrybutor jest uzywany
                    boolean isPumpUsed = currentlyUsedPumps.contains(pumpIndex);
                    gc.setFill(isPumpUsed ? usedColor : freeColor);
                }

                // rysowanie dystrybutora
                gc.fillRect(pumpX, pumpY, pumpWidth, pumpHeight);
                gc.setStroke(Color.BLACK);
                gc.setLineWidth(1.5);
                gc.strokeRect(pumpX, pumpY, pumpWidth, pumpHeight);

                // numer dystrybutora
                gc.setFill(Color.BLACK);
                gc.setFont(Font.font("System", FontWeight.BOLD, Math.max(8, 11 * scale)));
                String pumpNumber = String.valueOf(pumpIndex + 1);
                double numberTextWidth = pumpNumber.length() * 5 * scale;
                gc.fillText(pumpNumber, pumpX + (pumpWidth - numberTextWidth) / 2, pumpY + pumpHeight * 0.35);

                // losowy typ paliwa dla tego dystrybutora
                String fuelType = pumpFuelTypes.getOrDefault(pumpIndex, "BRAK");
                gc.setFont(Font.font("System", FontWeight.BOLD, Math.max(7, 10 * scale)));
                double fuelTextWidth = fuelType.length() * 4.5 * scale;
                gc.fillText(fuelType, pumpX + (pumpWidth - fuelTextWidth) / 2, pumpY + pumpHeight * 0.75);

                pumpIndex++;
            }
        }

        // rysowanie budynku z kasami
        double buildingWidth = Math.min(280, canvasWidth * 0.25);
        double buildingHeight = Math.max(80, Math.min(150, canvasHeight * 0.15));
        double buildingX = canvasWidth - buildingWidth - margin;
        double buildingY = startY + 20;

        gc.setFill(Color.WHITE);
        gc.fillRect(buildingX, buildingY, buildingWidth, buildingHeight);
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(2);
        gc.strokeRect(buildingX, buildingY, buildingWidth, buildingHeight);

        // napis kasy
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", FontWeight.BOLD, Math.max(12, 16)));
        gc.fillText("KASY", buildingX + buildingWidth / 2 - 20, buildingY + 25);

        // dynamiczne rozmieszczenie kas
        double cashierSize = Math.min(25, (buildingWidth - 40) / K - 5);
        double cashierSpacing = 5;

        // obliczanie pozycji kas
        if (K <= 8) {
            // kasy w jednym rzedzie
            double totalCashierWidth = K * cashierSize + (K - 1) * cashierSpacing;
            double cashiersStartX = buildingX + (buildingWidth - totalCashierWidth) / 2;
            double cashiersY = buildingY + buildingHeight / 2;

            for (int i = 0; i < K; i++) {
                double cashierX = cashiersStartX + i * (cashierSize + cashierSpacing);
                gc.setFill(i < usedCashiers ? usedColor : freeColor);
                gc.fillRect(cashierX, cashiersY, cashierSize, cashierSize);
                gc.setStroke(Color.BLACK);
                gc.setLineWidth(1);
                gc.strokeRect(cashierX, cashiersY, cashierSize, cashierSize);
            }
        } else {
            // kasy w dwoch rzedach
            int cashiersPerRow = (K + 1) / 2;
            double totalCashierWidth = cashiersPerRow * cashierSize + (cashiersPerRow - 1) * cashierSpacing;
            double cashiersStartX = buildingX + (buildingWidth - totalCashierWidth) / 2;

            for (int i = 0; i < K; i++) {
                int row = i / cashiersPerRow;
                int col = i % cashiersPerRow;
                double cashierX = cashiersStartX + col * (cashierSize + cashierSpacing);
                double cashierY = buildingY + 35 + row * (cashierSize + 10);

                gc.setFill(i < usedCashiers ? usedColor : freeColor);
                gc.fillRect(cashierX, cashierY, cashierSize, cashierSize);
                gc.setStroke(Color.BLACK);
                gc.setLineWidth(1);
                gc.strokeRect(cashierX, cashierY, cashierSize, cashierSize);
            }
        }

        // legenda droga kolejka i status
        drawLegend(canvasWidth, freeColor, usedColor, closedColor);
        drawRoad(canvasWidth, canvasHeight);
        drawCarQueue(canvasWidth, canvasHeight, margin, totalPumps, usedPumps, isStationClosed);
        drawStationStatus(canvasHeight, margin, isStationClosed, totalPumps, usedPumps);
    }

    // rysowanie legendy
    private void drawLegend(double canvasWidth, Color freeColor, Color usedColor, Color closedColor) {
        double legendX = canvasWidth - 150;
        double legendY = 250;
        double legendItemHeight = 20;

        gc.setFont(Font.font("System", FontWeight.BOLD, 12));
        gc.setFill(Color.BLACK);
        gc.fillText("Legenda:", legendX, legendY);

        // wolny
        gc.setFill(freeColor);
        gc.fillRect(legendX, legendY + 5, 15, 10);
        gc.setStroke(Color.BLACK);
        gc.strokeRect(legendX, legendY + 5, 15, 10);
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", 10));
        gc.fillText("- Wolny", legendX + 20, legendY + 14);

        // zajety
        gc.setFill(usedColor);
        gc.fillRect(legendX, legendY + 5 + legendItemHeight, 15, 10);
        gc.setStroke(Color.BLACK);
        gc.strokeRect(legendX, legendY + 5 + legendItemHeight, 15, 10);
        gc.setFill(Color.BLACK);
        gc.fillText("- Zajęty", legendX + 20, legendY + 14 + legendItemHeight);

        // zamkniety
        gc.setFill(closedColor);
        gc.fillRect(legendX, legendY + 5 + 2 * legendItemHeight, 15, 10);
        gc.setStroke(Color.BLACK);
        gc.strokeRect(legendX, legendY + 5 + 2 * legendItemHeight, 15, 10);
        gc.setFill(Color.BLACK);
        gc.fillText("- Zamknięty", legendX + 20, legendY + 14 + 2 * legendItemHeight);
    }

    // rysowanie drogi
    private void drawRoad(double canvasWidth, double canvasHeight) {
        gc.setFill(Color.DARKGRAY);
        gc.fillRect(0, canvasHeight * 0.75, canvasWidth, canvasHeight * 0.25);
        gc.setStroke(Color.YELLOW);
        gc.setLineDashes(8, 8);
        gc.strokeLine(0, canvasHeight * 0.85, canvasWidth, canvasHeight * 0.85);
        gc.setLineDashes();
    }

    // rysowanie kolejki samochodow na drodze
    private void drawCarQueue(double canvasWidth, double canvasHeight, double margin,
                              int totalPumps, int usedPumps, boolean isStationClosed) {
        // pobierz kolejki z symulacji
        Map<String, Queue<Simulation.CarInfo>> fuelQueues = simulation.getFuelQueues();

        if (fuelQueues.isEmpty()) return;

        double carWidth = 25;
        double carHeight = 12;
        double carSpacing = 30;

        // pozycjonowanie samochodow na drodze (szara czesc na dole)
        double roadCenterY = canvasHeight * 0.825; // srodek szarej drogi
        double carY = roadCenterY - carHeight / 2;

        // rozpocznij od prawej strony i idz w lewo
        double startX = canvasWidth - margin - carWidth - 10;

        Color[] carColors = {
                Color.BLUE, Color.DARKGREEN, Color.PURPLE, Color.ORANGE,
                Color.BROWN, Color.DARKRED, Color.NAVY, Color.DARKSLATEGRAY,
                Color.MAROON, Color.TEAL, Color.OLIVE, Color.INDIGO
        };

        // licznik pozycji na drodze
        int positionIndex = 0;
        int totalCarsInQueue = 0;

        // oblicz laczna liczbe samochodow w kolejkach
        for (Queue<Simulation.CarInfo> queue : fuelQueues.values()) {
            totalCarsInQueue += queue.size();
        }

        // rysuj samochody z kolejek dla kazdego typu paliwa
        for (Map.Entry<String, Queue<Simulation.CarInfo>> entry : fuelQueues.entrySet()) {
            String fuelType = entry.getKey();
            Queue<Simulation.CarInfo> queue = entry.getValue();

            for (Simulation.CarInfo carInfo : queue) {
                double carX = startX - (positionIndex * carSpacing);

                // jesli samochod wyszedlby poza lewa strone nie rysuj go
                if (carX < margin) {
                    break;
                }

                // rysowanie samochodu z czerwonym obramowaniem (oczekuje na dystrybutor)
                gc.setFill(carColors[positionIndex % carColors.length]);
                gc.fillRect(carX, carY, carWidth, carHeight);
                gc.setStroke(Color.RED); // czerwone obramowanie dla samochodow w kolejce
                gc.setLineWidth(2);
                gc.strokeRect(carX, carY, carWidth, carHeight);

                // numer samochodu w kolejce
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font("System", FontWeight.BOLD, 8));
                String carNumber = String.valueOf(carInfo.id);
                double textWidth = carNumber.length() * 4;
                gc.fillText(carNumber, carX + (carWidth - textWidth) / 2, carY + carHeight / 2 + 2);

                // typ paliwa nad samochodem
                gc.setFill(Color.RED);
                gc.setFont(Font.font("System", FontWeight.BOLD, 8));
                gc.fillText(fuelType, carX, carY - 3);

                positionIndex++;
            }
        }

        // informacja o kolejce - wyswietl nad droga
        if (totalCarsInQueue > 0) {
            gc.setFill(Color.BLACK);
            gc.setFont(Font.font("System", FontWeight.BOLD, 12));
            String queueInfo = "Kolejka: " + totalCarsInQueue + " " + (totalCarsInQueue == 1 ? "samochód" : "samochodów");

            // dodaj informacje o kolejkach dla poszczegolnych typow paliw
            StringBuilder detailInfo = new StringBuilder();
            for (Map.Entry<String, Queue<Simulation.CarInfo>> entry : fuelQueues.entrySet()) {
                if (entry.getValue().size() > 0) {
                    if (detailInfo.length() > 0) detailInfo.append(", ");
                    detailInfo.append(entry.getKey()).append(": ").append(entry.getValue().size());
                }
            }

            if (detailInfo.length() > 0) {
                queueInfo += " (" + detailInfo.toString() + ")";
            }

            gc.fillText(queueInfo, margin, canvasHeight * 0.72);
        }
    }

    // rysowanie informacji o stanie stacji
    private void drawStationStatus(double canvasHeight, double margin,
                                   boolean isStationClosed, int totalPumps, int usedPumps) {
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font("System", FontWeight.BOLD, 12));

        if (isStationClosed) {
            gc.fillText("STACJA ZAMKNIĘTA - UZUPEŁNIANIE PALIWA", margin, canvasHeight - 20);
        } else if (usedPumps >= totalPumps) {
            gc.fillText("WSZYSTKIE DYSTRYBUTORY ZAJĘTE", margin, canvasHeight - 20);
        } else {
            gc.fillText("Wolne dystrybutory: " + (totalPumps - usedPumps), margin, canvasHeight - 20);
        }
    }

    // aktualizacja statystyk
    private void updateStats() {
        if (simulation == null) return;

        ObservableList<String> stats = FXCollections.observableArrayList();

        boolean isRunning = simulation.isRunning();
        boolean isPaused = simulation.isPaused();

        // status symulacji
        stats.add("Status: " + (isRunning ? (isPaused ? "Wstrzymana" : "Uruchomiona") : "Zatrzymana"));

        // podstawowe statystyki
        stats.add("Samochody na stacji: " + simulation.getCarsOnSite());

        // statystyki kolejek
        Map<String, Queue<Simulation.CarInfo>> fuelQueues = simulation.getFuelQueues();
        int totalInQueues = 0;
        for (Queue<Simulation.CarInfo> queue : fuelQueues.values()) {
            totalInQueues += queue.size();
        }
        stats.add("Samochody w kolejkach: " + totalInQueues);

        // szczegoly kolejek
        for (Map.Entry<String, Queue<Simulation.CarInfo>> entry : fuelQueues.entrySet()) {
            if (entry.getValue().size() > 0) {
                stats.add("  " + entry.getKey() + ": " + entry.getValue().size() + " aut");
            }
        }

        stats.add("Obsłużone samochody: " + simulation.getServicedCars());

        // informacje o dystrybutorach
        int totalPumps = simulation.getTotalPumps();
        int usedPumps = simulation.getUsedPumps();
        stats.add("Dystrybutory: " + usedPumps + "/" + totalPumps + " zajętych");

        // informacje o kasach
        int totalCashiers = simulation.getTotalCashiers();
        int usedCashiers = simulation.getUsedCashiers();
        stats.add("Kasy: " + usedCashiers + "/" + totalCashiers + " zajętych");

        // informacje o typach paliw
        stats.add("-----------------------------------");
        stats.add("Zapasy paliw:");
        for (Map.Entry<String, AtomicInteger> entry : simulation.getFuelReserves().entrySet()) {
            String fuelType = entry.getKey();
            int reserves = entry.getValue().get();
            int usedFuelPumps = simulation.getFuelTypeUsage().get(fuelType).get();
            stats.add(fuelType + ": " + reserves + " L pozostało");
            stats.add("  " + usedFuelPumps + " dystrybutorów w użyciu");
        }

        // informacja o zamknieciu stacji
        if (simulation.isStationClosed()) {
            stats.add("-----------------------------------");
            stats.add("!!! STACJA ZAMKNIĘTA !!!");
            stats.add("Trwa uzupełnianie paliwa");
        }

        // aktualizacja listy w watku javafx
        Platform.runLater(() -> {
            statsListView.setItems(stats);
        });
    }

    // rozpoczecie symulacji
    private void startSimulation() {
        simulation.start();
        updateButtonsState(true, false);
        statusLabel.setText("Symulacja uruchomiona");
        statusLabel.getStyleClass().removeAll("statusPaused", "statusStopped");
        statusLabel.getStyleClass().add("statusRunning");
    }

    // wstrzymanie symulacji
    private void pauseSimulation() {
        simulation.pause();
        updateButtonsState(true, true);
        statusLabel.setText("Symulacja wstrzymana");
        statusLabel.getStyleClass().removeAll("statusRunning", "statusStopped");
        statusLabel.getStyleClass().add("statusPaused");
    }

    // wznowienie symulacji
    private void resumeSimulation() {
        simulation.resume();
        updateButtonsState(true, false);
        statusLabel.setText("Symulacja wznowiona");
        statusLabel.getStyleClass().removeAll("statusPaused", "statusStopped");
        statusLabel.getStyleClass().add("statusRunning");
    }

    // zatrzymanie symulacji
    private void stopSimulation() {
        simulation.stop();
        updateButtonsState(false, false);
        statusLabel.setText("Symulacja zatrzymana");
        statusLabel.getStyleClass().removeAll("statusRunning", "statusPaused");
        statusLabel.getStyleClass().add("statusStopped");
    }

    // aktualizacja stanu przyciskow
    private void updateButtonsState(boolean isRunning, boolean isPaused) {
        startButton.setDisable(isRunning);
        pauseButton.setDisable(!isRunning || isPaused);
        resumeButton.setDisable(!isRunning || !isPaused);
        stopButton.setDisable(!isRunning);
    }

    // wyswietlenie okna dialogowego z bledem
    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }

    // ladowanie konfiguracji
    private static class Config {
        public List<FuelType> fuelTypes;
        public int M;               // stanowiska dystrybutoorow
        public int pumpsPerSpot;    // dystrybutoorow na stanowisko
        public int K;               // kasy
        public int N;               // maksymalna liczba samochodow
        public SimTimes simulation;
        public FuelRefill fuelRefill;

        // klasy dla yaml
        public static class FuelType {
            public String name;
            public double flowRateLpm; // litry na minute
        }
        public static class SimTimes {
            public int minLitresPerCar;
            public int maxLitresPerCar;
            public int arrivalIntervalMin;
            public int arrivalIntervalMax;
            public int walkToCashierTimeMin;
            public int walkToCashierTimeMax;
            public int paymentTimeMin;
            public int paymentTimeMax;
            public int walkToCarTimeMin;
            public int walkToCarTimeMax;
        }
        public static class FuelRefill {
            public int threshold;       // prog uzupelnienia paliwa
            public int durationMillis;  // czas na uzupelnienie
        }

        static Config load(String path) throws Exception {
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                LoaderOptions opts = new LoaderOptions();
                Constructor ctor = new Constructor(Config.class, opts);
                Yaml yaml = new Yaml(ctor);
                return (Config) yaml.load(in);
            }
        }
    }

    // rdzen symulacji
    private static class Simulation {
        private final Config cfg;
        private final Random rnd = new Random();

        // semafory bezpieczne watkowo do modelowania ograniczonych zasobow
        private final Semaphore pumpSemaphore;    // m * pumpsperspot pozwolen
        private final Semaphore cashierSemaphore; // k pozwolen
        private final AtomicInteger carsOnSite = new AtomicInteger();
        private final AtomicInteger servicedCars = new AtomicInteger();

        // kolejki dla kazdego typu paliwa
        private final Map<String, Queue<CarInfo>> fuelQueues = new ConcurrentHashMap<>();

        // liczniki uzywania konkretnych typow paliw i ich zapasaw
        private final Map<String, AtomicInteger> fuelTypeUsage = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> fuelReserves = new ConcurrentHashMap<>();

        // mapa typow paliw dla kazdego dystrybutora
        private final Map<Integer, String> pumpFuelTypes = new ConcurrentHashMap<>();
        // semafory dla kazdego typu paliwa
        private final Map<String, Semaphore> fuelSpecificSemaphores = new ConcurrentHashMap<>();
        // zbior aktualnie uzywanych dystrybutoorow
        private final Set<Integer> currentlyUsedPumps = ConcurrentHashMap.newKeySet();

        // mechanizmy watkow
        private ScheduledExecutorService worldClock; // przyjazdy samochodow i zamkniecia stacji
        private ExecutorService carThreads;          // kazdy samochod ma wlasny runnable
        private ExecutorService queueProcessor;      // przetwarzanie kolejek
        private volatile boolean stationClosed = false;
        private volatile boolean simulationPaused = false; // flaga pauzy symulacji

        // zamek do kontroli logowania podczas pauzy
        private final ReentrantLock logLock = new ReentrantLock();

        // klasa pomocnicza do przechowywania informacji o samochodzie w kolejce
        public static class CarInfo {
            public final int id;
            public final String fuelType;
            public final int litres;

            public CarInfo(int id, String fuelType, int litres) {
                this.id = id;
                this.fuelType = fuelType;
                this.litres = litres;
            }
        }

        Simulation(Config cfg) {
            this.cfg = cfg;

            pumpSemaphore = new Semaphore(cfg.M * cfg.pumpsPerSpot, true);
            cashierSemaphore = new Semaphore(cfg.K, true);

            // inicjalizacja licznikow i kolejek dla kazdego typu paliwa
            for (Config.FuelType fuelType : cfg.fuelTypes) {
                fuelTypeUsage.put(fuelType.name, new AtomicInteger(0));
                // inicjalizujemy zapasy paliwa (np 1000 litrow kazdego)
                fuelReserves.put(fuelType.name, new AtomicInteger(1000));
                // inicjalizacja semaforow dla kazdego typu paliwa
                fuelSpecificSemaphores.put(fuelType.name, new Semaphore(0, true));
                // inicjalizacja kolejek dla kazdego typu paliwa
                fuelQueues.put(fuelType.name, new ConcurrentLinkedQueue<>());
            }
        }

        // inicjalizacja losowych typow paliw dla dystrybutoorow
        private void initializePumpFuelTypes() {
            pumpFuelTypes.clear();
            currentlyUsedPumps.clear();

            // reset semaforow dla kazdego typu paliwa
            for (Semaphore semaphore : fuelSpecificSemaphores.values()) {
                semaphore.drainPermits();
            }

            int totalPumps = cfg.M * cfg.pumpsPerSpot;

            // jesli mamy min 3 dystrybutory zagwarantuj min 1 z kazdego typu
            if (totalPumps >= 3 && cfg.fuelTypes.size() <= totalPumps) {
                // najpierw przypisz po jednym dystrybutorze kazdego typu paliwa
                for (int i = 0; i < cfg.fuelTypes.size(); i++) {
                    String fuelType = cfg.fuelTypes.get(i).name;
                    pumpFuelTypes.put(i, fuelType);
                    fuelSpecificSemaphores.get(fuelType).release();
                }

                // pozostale dystrybutory przypisz losowo
                for (int pumpId = cfg.fuelTypes.size(); pumpId < totalPumps; pumpId++) {
                    String fuelType = cfg.fuelTypes.get(rnd.nextInt(cfg.fuelTypes.size())).name;
                    pumpFuelTypes.put(pumpId, fuelType);
                    fuelSpecificSemaphores.get(fuelType).release();
                }
            } else {
                for (int pumpId = 0; pumpId < totalPumps; pumpId++) {
                    String fuelType = cfg.fuelTypes.get(rnd.nextInt(cfg.fuelTypes.size())).name;
                    pumpFuelTypes.put(pumpId, fuelType);
                    fuelSpecificSemaphores.get(fuelType).release();
                }
            }

            log("Zainicjalizowano typy paliw dla " + totalPumps + " dystrybutorów:");
            for (Map.Entry<String, Semaphore> entry : fuelSpecificSemaphores.entrySet()) {
                log("  " + entry.getKey() + ": " + entry.getValue().availablePermits() + " dystrybutorów");
            }
        }

        // uruchom calosc (jesli nie jest uruchomiony)
        synchronized void start() {
            if (worldClock != null && !worldClock.isShutdown()) {
                // jesli symulacja jest tylko wstrzymana wznow ja
                if (simulationPaused) {
                    resume();
                    return;
                }
                log("Symulacja już uruchomiona.");
                return;
            }
            log("Uruchamianie symulacji...");
            worldClock = Executors.newScheduledThreadPool(2);
            carThreads = Executors.newCachedThreadPool();
            queueProcessor = Executors.newSingleThreadExecutor();

            // resetujemy zapasy paliwa
            for (String fuelType : fuelReserves.keySet()) {
                fuelReserves.get(fuelType).set(1000);
            }

            // resetujemy liczniki i kolejki
            servicedCars.set(0);
            carsOnSite.set(0);
            for (Queue<CarInfo> queue : fuelQueues.values()) {
                queue.clear();
            }
            simulationPaused = false;

            // resetujemy licznik id samochodow na 1
            carIdSeq.set(1);

            // inicjalizacja losowych typow paliw dla dystrybutoorow
            initializePumpFuelTypes();

            // uruchom procesor kolejek
            queueProcessor.submit(this::processQueues);

            // planuj przyjazdy samochodow
            worldClock.schedule(this::spawnCar, 0, TimeUnit.MILLISECONDS);

            log("Symulacja uruchomiona. Samochody będą przyjeżdżać w losowych odstępach czasu.");
        }

        // wstrzymaj symulacje
        synchronized void pause() {
            if (worldClock == null || worldClock.isShutdown()) {
                log("Symulacja nie jest uruchomiona, nie można jej wstrzymać.");
                return;
            }
            if (simulationPaused) {
                log("Symulacja jest już wstrzymana.");
                return;
            }
            log("Wstrzymywanie symulacji...");
            simulationPaused = true;
        }

        // wznow wstrzymana symulacje
        synchronized void resume() {
            if (worldClock == null || worldClock.isShutdown()) {
                log("Symulacja nie jest uruchomiona, nie można jej wznowić.");
                return;
            }
            if (!simulationPaused) {
                log("Symulacja nie jest wstrzymana.");
                return;
            }
            log("Wznawianie symulacji...");
            simulationPaused = false;

            // planujemy nastepny przyjazd samochodu jesli nie jest juz zaplanowany
            worldClock.schedule(this::spawnCar, rnd(cfg.simulation.arrivalIntervalMin, cfg.simulation.arrivalIntervalMax), TimeUnit.MILLISECONDS);
        }

        // zatrzymaj lagodnie
        synchronized void stop() {
            if (worldClock == null) return;
            log("Zatrzymywanie symulacji...");

            // zatrzymujemy planowanie nowych samochodow
            worldClock.shutdownNow();

            // zatrzymujemy procesor kolejek
            if (queueProcessor != null) {
                queueProcessor.shutdownNow();
            }

            // przerywamy wszystkie watki samochodow
            carThreads.shutdownNow();

            // czekamy na zakonczenie przetwarzania (max 2 sekundy)
            try {
                if (!carThreads.awaitTermination(2, TimeUnit.SECONDS)) {
                    log("Niektóre samochody nie zakończyły działania w czasie oczekiwania.");
                }
            } catch (InterruptedException ignored) {}

            // wyczysc kolejki
            for (Queue<CarInfo> queue : fuelQueues.values()) {
                queue.clear();
            }

            // upewniamy sie ze wszystkie struktury sa zresetowane
            worldClock = null;
            queueProcessor = null;
            simulationPaused = false;
            log("Zatrzymano. Łącznie obsłużonych samochodów: " + servicedCars.get());
        }

        // procesor kolejek - obsluguje samochody oczekujace na dystrybutory
        private void processQueues() {
            while (!Thread.currentThread().isInterrupted()) {
                // sprawdz kazdy typ paliwa
                for (Map.Entry<String, Queue<CarInfo>> entry : fuelQueues.entrySet()) {
                    String fuelType = entry.getKey();
                    Queue<CarInfo> queue = entry.getValue();

                    // jesli kolejka nie jest pusta i sa dostepne dystrybutory tego typu
                    if (!queue.isEmpty() && fuelSpecificSemaphores.get(fuelType).availablePermits() > 0) {
                        CarInfo carInfo = queue.poll();
                        if (carInfo != null) {
                            // uruchom samochod z kolejki
                            Car car = new Car(carInfo);
                            carThreads.submit(car);
                            logIfNotPaused("Kolejka-" + fuelType, "Auto-" + carInfo.id + " wyjęło z kolejki i jedzie do dystrybutora");
                        }
                    }
                }

                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                Thread.onSpinWait();
            }
        }

        // wywolywane za kazdym razem gdy powinien pojawic sie nowy samochod
        private void spawnCar() {
            // jesli symulacja jest wstrzymana nie planuj nowych przyjazdow
            if (simulationPaused) {
                return;
            }

            // zaplanuj nastepny przyjazd od razu dla stochastycznych przyjazdow
            int nextArrival = rnd(cfg.simulation.arrivalIntervalMin, cfg.simulation.arrivalIntervalMax);
            if (worldClock != null && !worldClock.isShutdown()) {
                worldClock.schedule(this::spawnCar, nextArrival, TimeUnit.MILLISECONDS);
            }

            if (stationClosed) return; // brak przyjazdow gdy zamkniete
            if (carsOnSite.get() >= cfg.N) {
                log("Stacja pełna - samochód odjeżdża bez wjazdu na stację");
                return; // pelno - samochod odjezdza
            }

            // utworzenie informacji o samochodzie
            Config.FuelType fuel = cfg.fuelTypes.get(rnd.nextInt(cfg.fuelTypes.size()));
            String neededFuelType = fuel.name;
            int litres = rnd(cfg.simulation.minLitresPerCar, cfg.simulation.maxLitresPerCar);
            int carId = carIdSeq.getAndIncrement();

            CarInfo carInfo = new CarInfo(carId, neededFuelType, litres);

            carsOnSite.incrementAndGet();

            // sprawdza czy sa dostepne dystrybutory dla tego typu paliwa
            if (fuelSpecificSemaphores.get(neededFuelType).availablePermits() > 0) {
                // jesli sa dostepne uruchom samochod bezposrednio
                Car car = new Car(carInfo);
                carThreads.submit(car);
                logIfNotPaused("Auto-" + carId, "przybył i jedzie bezpośrednio do dystrybutora " + neededFuelType);
            } else {
                // jesli nie ma dostepnych dystrybutoorow dodaj do kolejki
                fuelQueues.get(neededFuelType).offer(carInfo);
                logIfNotPaused("Auto-" + carId, "przybył, brak dystrybutorów " + neededFuelType + " - ustawia się do kolejki (pozycja: " + fuelQueues.get(neededFuelType).size() + ")");
            }
        }

        private void closeStation(int durationMs, String fuelType) {
            stationClosed = true;
            log(">>> Stacja ZAMKNIĘTA na uzupełnienie zapasów paliwa " + fuelType + " (" + durationMs/1000 + " s) <<<");

            // uzupelniamy zapasy paliwa ktore sie skonczylo
            fuelReserves.get(fuelType).set(1000);

            try {
                Thread.sleep(durationMs);
                stationClosed = false;
                log("<<< Stacja PONOWNIE OTWARTA po uzupełnieniu paliwa " + fuelType + " >>>");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // proces samochodu z dopasowaniem typu paliwa i sledzeniem dystrybutoorow
        private class Car implements Runnable {
            private final CarInfo carInfo;
            private int assignedPumpId = -1; // przechowuje id przydzielonego dystrybutora

            public Car(CarInfo carInfo) {
                this.carInfo = carInfo;
            }

            @Override
            public void run() {
                try {
                    // sprawdzanie czy mamy wystarczajaca ilosc paliwa
                    int remainingFuel = fuelReserves.get(carInfo.fuelType).get();
                    if (remainingFuel < carInfo.litres) {
                        logIfNotPaused(id(), "odjeżdża - brak wystarczającej ilości paliwa " + carInfo.fuelType);
                        return;
                    }

                    // nabywamy pozwolenie na konkretny typ paliwa
                    fuelSpecificSemaphores.get(carInfo.fuelType).acquire();
                    pumpSemaphore.acquire(); // nadal potrzebujemy ogolnego semafora

                    // sprawdz czy symulacja zostala zatrzymana po nabyciu dystrybutora
                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        fuelSpecificSemaphores.get(carInfo.fuelType).release();
                        pumpSemaphore.release();
                        logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                        return;
                    }

                    // znajdz i zarezerwuj konkretny dystrybutor z odpowiednim paliwem
                    assignedPumpId = findAndReservePump(carInfo.fuelType);

                    // zwieksza licznik uzywania tego typu paliwa
                    fuelTypeUsage.get(carInfo.fuelType).incrementAndGet();

                    int totalPumps = cfg.M * cfg.pumpsPerSpot;
                    int usedPumps = totalPumps - pumpSemaphore.availablePermits();
                    logIfNotPaused(id(), "zajął dystrybutor #" + (assignedPumpId + 1) + " z paliwem " + carInfo.fuelType +
                            " - tankowanie " + carInfo.litres + "L... (zajęte dystrybutory: " + usedPumps + "/" + totalPumps + ")");

                    try {
                        // tankowanie - czas zalezny od ilosci paliwa
                        fuel(carInfo.litres, carInfo.fuelType);

                        // zmniejsza zapas paliwa
                        int newAmount = fuelReserves.get(carInfo.fuelType).addAndGet(-carInfo.litres);
                        logIfNotPaused(id(), "zatankował " + carInfo.litres + " L paliwa " + carInfo.fuelType +
                                " (pozostało: " + newAmount + " L)");

                        // jesli zapas paliwa spadl ponizej progu zamykamy stacje
                        if (newAmount <= cfg.fuelRefill.threshold && !stationClosed) {
                            new Thread(() -> closeStation(cfg.fuelRefill.durationMillis, carInfo.fuelType)).start();
                        }

                        // sprawdz czy symulacja zostala zatrzymana po tankowaniu
                        if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                            releasePumpResources(carInfo.fuelType);
                            logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                            return;
                        }

                        // kierowca idzie do kasy
                        logIfNotPaused(id(), "zakończył tankowanie i zmierza do kasy (pozostało " + newAmount + " L paliwa " + carInfo.fuelType + ")");
                        int walkToCashierTime = rnd(cfg.simulation.walkToCashierTimeMin, cfg.simulation.walkToCashierTimeMax);

                        waitIfPaused();
                        Thread.sleep(walkToCashierTime);

                        if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                            releasePumpResources(carInfo.fuelType);
                            logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                            return;
                        }

                        // kontynuacja procesu platnosci
                        processPayment(carInfo.fuelType);

                    } finally {
                        // upewnia sie ze zasoby sa zwolnione w przypadku przerwania
                        if (Thread.currentThread().isInterrupted()) {
                            releasePumpResources(carInfo.fuelType);
                        }
                    }

                } catch (InterruptedException e) {
                    // obsluguje przerwania watku
                    Thread.currentThread().interrupt();
                    logIfNotPaused(id(), "przerwano obsługę - samochód odjeżdża");

                    // zwalnia zasoby jesli byly nabyte
                    releasePumpResources(carInfo.fuelType);
                } finally {
                    // zawsze zmniejsza liczbe samochodow na stacji
                    carsOnSite.decrementAndGet();
                }
            }

            // znajduje i rezerwuje dostepny dystrybutor z odpowiednim typem paliwa
            private int findAndReservePump(String fuelType) {
                // znajduje pierwszy wolny dystrybutor z odpowiednim paliwem
                for (Map.Entry<Integer, String> entry : pumpFuelTypes.entrySet()) {
                    int pumpId = entry.getKey();
                    String pumpFuelType = entry.getValue();

                    if (pumpFuelType.equals(fuelType) && !currentlyUsedPumps.contains(pumpId)) {
                        // rezerwuje ten dystrybutor
                        currentlyUsedPumps.add(pumpId);
                        return pumpId;
                    }
                }
                return -1; // nie powinno wystapic jesli semafory dzialaja prawidlowo
            }

            // zwalnia zasoby dystrybutora
            private void releasePumpResources(String neededFuelType) {
                try {
                    if (assignedPumpId != -1) {
                        currentlyUsedPumps.remove(assignedPumpId);
                    }
                    fuelTypeUsage.get(neededFuelType).decrementAndGet();
                    fuelSpecificSemaphores.get(neededFuelType).release();
                    pumpSemaphore.release();
                } catch (Exception ignored) {
                    // ignoruje bledy
                }
            }

            // proces platnosci
            private void processPayment(String neededFuelType) throws InterruptedException {
                if (!stationClosed) {
                    // normalna platnosc
                    logIfNotPaused(id(), "czeka w kolejce do kasy...");

                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        releasePumpResources(neededFuelType);
                        logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                        return;
                    }

                    waitIfPaused();
                    cashierSemaphore.acquire();

                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        cashierSemaphore.release();
                        releasePumpResources(neededFuelType);
                        logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                        return;
                    }

                    int usedCashiers = cfg.K - cashierSemaphore.availablePermits();
                    logIfNotPaused(id(), "przy kasie... (zajęte kasy: " + usedCashiers + "/" + cfg.K + ")");

                    waitIfPaused();
                    Thread.sleep(rnd(cfg.simulation.paymentTimeMin, cfg.simulation.paymentTimeMax));
                    logIfNotPaused(id(), "zakończył płatność");
                    cashierSemaphore.release();

                    // powrot do samochodu
                    logIfNotPaused(id(), "wraca do samochodu...");
                    waitIfPaused();
                    int walkToCarTime = rnd(cfg.simulation.walkToCarTimeMin, cfg.simulation.walkToCarTimeMax);
                    Thread.sleep(walkToCarTime);

                    // odjezdza - zwolnij dystrybutor
                    currentlyUsedPumps.remove(assignedPumpId);
                    fuelTypeUsage.get(neededFuelType).decrementAndGet();
                    fuelSpecificSemaphores.get(neededFuelType).release();
                    pumpSemaphore.release();

                    servicedCars.incrementAndGet();
                    logIfNotPaused(id(), "gotowe i odjeżdża");
                } else {
                    // platnosc po ponownym otwarciu stacji
                    handlePaymentAfterReopening(neededFuelType);
                }
            }

            // obsluga platnosci po ponownym otwarciu stacji
            private void handlePaymentAfterReopening(String neededFuelType) throws InterruptedException {
                logIfNotPaused(id(), "musi poczekać na otwarcie stacji, aby zapłacić");

                int checkInterval = 100;
                while (stationClosed) {
                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        releasePumpResources(neededFuelType);
                        logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                        return;
                    }
                    waitIfPaused();
                    Thread.onSpinWait();
                }

                logIfNotPaused(id(), "czeka w kolejce do kasy po ponownym otwarciu...");

                if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                    releasePumpResources(neededFuelType);
                    logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                    return;
                }

                waitIfPaused();
                cashierSemaphore.acquire();

                if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                    cashierSemaphore.release();
                    releasePumpResources(neededFuelType);
                    logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                    return;
                }

                int usedCashiers = cfg.K - cashierSemaphore.availablePermits();
                logIfNotPaused(id(), "przy kasie po ponownym otwarciu... (zajęte kasy: " + usedCashiers + "/" + cfg.K + ")");

                waitIfPaused();
                Thread.sleep(rnd(cfg.simulation.paymentTimeMin, cfg.simulation.paymentTimeMax));
                logIfNotPaused(id(), "zakończył płatność");
                cashierSemaphore.release();

                logIfNotPaused(id(), "wraca do samochodu...");
                waitIfPaused();
                int walkToCarTime = rnd(cfg.simulation.walkToCarTimeMin, cfg.simulation.walkToCarTimeMax);
                Thread.sleep(walkToCarTime);

                // odjezdza - zwolnij dystrybutor
                currentlyUsedPumps.remove(assignedPumpId);
                fuelTypeUsage.get(neededFuelType).decrementAndGet();
                fuelSpecificSemaphores.get(neededFuelType).release();
                pumpSemaphore.release();

                servicedCars.incrementAndGet();
                logIfNotPaused(id(), "gotowe i odjeżdża");
            }

            // oczekuje jesli symulacja jest wstrzymana az zostanie wznowiona
            private void waitIfPaused() throws InterruptedException {
                while (simulationPaused) {
                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        throw new InterruptedException("Symulacja została zatrzymana");
                    }
                    Thread.onSpinWait();
                }
            }

            private void fuel(int litres, String fuelType) throws InterruptedException {
                // znajduje odpowiedni typ paliwa z konfiguracji
                Config.FuelType fuel = null;
                for (Config.FuelType ft : cfg.fuelTypes) {
                    if (ft.name.equals(fuelType)) {
                        fuel = ft;
                        break;
                    }
                }

                if (fuel == null) return; // nie powinno sie zdarzyc

                double minutes = litres / fuel.flowRateLpm;
                long totalMilliseconds = (long) (minutes * 60 * 1000);

                long startTime = System.currentTimeMillis();
                long endTime = startTime + totalMilliseconds;

                while (System.currentTimeMillis() < endTime) {
                    waitIfPaused();

                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Tankowanie przerwane");
                    }

                    Thread.onSpinWait();
                }
            }

            private String id() { return "Auto-" + carInfo.id; }
        }

        // wiadomosc jest w logu tylko jesli symulacja nie jest wstrzymana
        private void logIfNotPaused(String who, String s) {
            // uzywamy blokady do synchronizacji czyli wiadomosci nie beda pomieszane
            if (!simulationPaused) {
                logLock.lock();
                try {
                    if (!simulationPaused) { // sprawdza bo status mogl sie zmienic podczas oczekiwania na blokade
                        System.out.println("[" + who + "] " + s);
                    }
                } finally {
                    logLock.unlock();
                }
            }
        }

        // statystyki
        void printStats() {
            StringBuilder fuelStats = new StringBuilder();
            for (Map.Entry<String, AtomicInteger> entry : fuelTypeUsage.entrySet()) {
                String fuelType = entry.getKey();
                int usedPumps = entry.getValue().get();
                int reserves = fuelReserves.get(fuelType).get();
                int queueSize = fuelQueues.get(fuelType).size();

                fuelStats.append("\n dystrybutory paliwa ").append(fuelType)
                        .append(": ").append(usedPumps).append(" zajętych")
                        .append(", kolejka: ").append(queueSize)
                        .append(", pozostało: ").append(reserves).append(" L");
            }

            log("-- statystyki na żywo --" +
                    "\n zaparkowane samochody: " + carsOnSite.get() +
                    "\n łącznie obsłużonych: " + servicedCars.get() +
                    "\n dystrybutory: zajęte " + (cfg.M * cfg.pumpsPerSpot - pumpSemaphore.availablePermits()) +
                    " z " + (cfg.M * cfg.pumpsPerSpot) +
                    fuelStats.toString() +
                    "\n kasy: zajęte " + (cfg.K - cashierSemaphore.availablePermits()) +
                    " z " + cfg.K +
                    (stationClosed ? "\n !!! stacja ZAMKNIĘTA !!!" : ""));
        }

        // metody pomocnicze do pobierania danych dla gui
        public boolean isRunning() {
            return worldClock != null && !worldClock.isShutdown();
        }

        public boolean isPaused() {
            return simulationPaused;
        }

        public boolean isStationClosed() {
            return stationClosed;
        }

        public int getCarsOnSite() {
            return carsOnSite.get();
        }

        public int getServicedCars() {
            return servicedCars.get();
        }

        public int getTotalPumps() {
            return cfg.M * cfg.pumpsPerSpot;
        }

        public int getUsedPumps() {
            return getTotalPumps() - pumpSemaphore.availablePermits();
        }

        public int getTotalCashiers() {
            return cfg.K;
        }

        public int getUsedCashiers() {
            return getTotalCashiers() - cashierSemaphore.availablePermits();
        }

        public Map<String, AtomicInteger> getFuelTypeUsage() {
            return fuelTypeUsage;
        }

        public Map<String, AtomicInteger> getFuelReserves() {
            return fuelReserves;
        }

        public Map<Integer, String> getPumpFuelTypes() {
            return pumpFuelTypes;
        }

        public Set<Integer> getCurrentlyUsedPumps() {
            return new HashSet<>(currentlyUsedPumps); // zwracana jest kopia dla bezpieczenstwa
        }

        public Map<String, Queue<CarInfo>> getFuelQueues() {
            return new HashMap<>(fuelQueues); // zwracana jest kopia dla bezpieczenstwa
        }

        public Config getConfig() {
            return cfg;
        }

        private void log(String s) { System.out.println(s); }

        private void log(String who, String s) {
            // logowanie uzywane tylko przez glowny watek symulacji
            System.out.println("[" + who + "] " + s);
        }

        // narzedzia
        private final AtomicInteger carIdSeq = new AtomicInteger(1);
        private int rnd(int min, int max) { return rnd.nextInt(max - min + 1) + min; }
        private double rndGaussian(double mean, double sd) { return mean + rnd.nextGaussian() * sd; }
    }
}