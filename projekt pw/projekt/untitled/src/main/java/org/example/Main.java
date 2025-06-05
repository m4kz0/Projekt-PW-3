import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


public class Main {

    //wejscie i interfejs uzytkownika

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load("config.yaml");
        Simulation simulation = new Simulation(cfg);
        Tui tui = new Tui(simulation);
        tui.run();
    }

    // interfejs tekstowy
    private static class Tui {
        private final Simulation simulation;
        private final Scanner scanner = new Scanner(System.in);
        private boolean running = true;

        Tui(Simulation simulation) { this.simulation = simulation; }

        void run() {
            println("\n=== Symulacja Stacji Paliw ===");

            // glowna petla interfejsu
            while (running) {
                // sprawdzamy status symulacji
                boolean isSimulationRunning = simulation.worldClock != null && !simulation.worldClock.isShutdown();
                boolean isSimulationPaused = simulation.simulationPaused;

                // zawsze pokazuje sie menu wyboru
                showMenu();

                // obsługa wyboru użytkownika
                handleUserChoice(isSimulationRunning, isSimulationPaused);
            }

            println("Do widzenia!");
        }

        // wyświetlanie menu
        private void showMenu() {
            println("\nMenu:");
            println(" 1. Rozpocznij symulację");
            println(" 2. Wstrzymaj symulację");
            println(" 3. Wznów symulację");
            println(" 4. Pokaż statystyki");
            println(" 5. Wyjście");
            print("Wybierz: ");
        }

        // obsługa wyboru użytkownika
        private void handleUserChoice(boolean isSimulationRunning, boolean isSimulationPaused) {
            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1" -> { // rozpoczyna symulację
                    if (isSimulationRunning && !isSimulationPaused) {
                        println("Symulacja już działa.");
                    } else if (isSimulationRunning && isSimulationPaused) {
                        println("Symulacja jest wstrzymana. Użyj opcji 3, aby ją wznowić.");
                    } else {
                        simulation.start();
                    }
                }
                case "2" -> { // wstrzymuje symulację
                    if (!isSimulationRunning) {
                        println("Symulacja nie jest uruchomiona. Najpierw uruchom symulację opcją 1.");
                    } else if (isSimulationPaused) {
                        println("Symulacja jest już wstrzymana.");
                    } else {
                        simulation.pause();
                    }
                }
                case "3" -> { // wznawia symulację
                    if (!isSimulationRunning) {
                        println("Symulacja nie jest uruchomiona. Najpierw uruchom symulację opcją 1.");
                    } else if (!isSimulationPaused) {
                        println("Symulacja nie jest wstrzymana.");
                    } else {
                        simulation.resume();
                    }
                }
                case "4" -> { // pokazuje statystyki
                    // wstrzymuje symulację tylko tymczasowo na czas wyświetlania statystyk
                    boolean wasPaused = isSimulationPaused;
                    if (isSimulationRunning && !wasPaused) {
                        simulation.pause();
                    }

                    simulation.printStats();

                    // wznawia symulację tylko jeśli była uruchomiona i nie była wstrzymana wcześniej
                    if (isSimulationRunning && !wasPaused) {
                        simulation.resume();
                    }
                }
                case "5" -> { // wyjście
                    if (isSimulationRunning) {
                        simulation.stop();
                    }
                    running = false;
                }
                default -> println("Nieznana opcja");
            }
        }

        private void println(String s) { System.out.println(s); }
        private void print(String s) { System.out.print(s); }
    }

    //ladowanie sie konfiguracji
    private static class Config {
        public List<FuelType> fuelTypes;
        public int M;               // stanowiska dystrybutorów
        public int pumpsPerSpot;    // dystrybutorów na stanowisko
        public int K;               // kasy
        public int N;               // maksymalna liczba samochodów
        public SimTimes simulation; //parametry czasowe
        public FuelRefill fuelRefill; //uzupelnienie paliwa

        //klasy dla configa.yaml
        public static class FuelType {
            public String name;
            public double flowRateLpm; // litry na minutę
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
            public int threshold;       // próg uzupełnienia paliwa
            public int durationMillis;  // czas na uzupełnienie
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

    //rdzen dzialanie symulacji
    private static class Simulation {
        private final Config cfg;
        private final Random rnd = new Random();

        // semafory bezpieczne wątkowo do modelowania ograniczonych zasobów
        private final Semaphore pumpSemaphore;    // M x pumpsPerSpot pozwoleń na wjazd
        private final Semaphore cashierSemaphore; // K pozwoleń na wejscie
        private final AtomicInteger carsOnSite = new AtomicInteger();
        private final AtomicInteger servicedCars = new AtomicInteger();

        // liczniki używania konkretnych typów paliw i ich zapasów
        private final Map<String, AtomicInteger> fuelTypeUsage = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> fuelReserves = new ConcurrentHashMap<>();

        // mechanizmy wątków
        private ScheduledExecutorService worldClock; // przyjazdy samochodów i zamknięcia stacji
        private ExecutorService carThreads;          // każdy samochód ma własny Runnable
        private volatile boolean stationClosed = false;
        private volatile boolean simulationPaused = false; // flaga pauzy symulacji

        // zamek do kontroli logowania podczas pauzy
        private final ReentrantLock logLock = new ReentrantLock();

        Simulation(Config cfg) {
            this.cfg = cfg;

            pumpSemaphore = new Semaphore(cfg.M * cfg.pumpsPerSpot, true);
            cashierSemaphore = new Semaphore(cfg.K, true);

            // inicjalizacja liczników dla każdego typu paliwa
            for (Config.FuelType fuelType : cfg.fuelTypes) {
                fuelTypeUsage.put(fuelType.name, new AtomicInteger(0));
                // inicjalizujemy zapasy paliwa (np. 1000 litrów każdego)
                fuelReserves.put(fuelType.name, new AtomicInteger(1000));
            }
        }

        //uruchom jak nie jest uruchomione
        synchronized void start() {
            if (worldClock != null && !worldClock.isShutdown()) {
                // jeśli symulacja jest tylko wstrzymana to wznawia ja
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

            // resetujemy zapasy paliwa
            for (String fuelType : fuelReserves.keySet()) {
                fuelReserves.get(fuelType).set(1000);
            }

            // resetujemy liczniki
            servicedCars.set(0);
            carsOnSite.set(0);
            simulationPaused = false;

            // planujemy przyjazdy samochodów
            worldClock.schedule(this::spawnCar, 0, TimeUnit.MILLISECONDS);

            log("Symulacja uruchomiona. Samochody będą przyjeżdżać w losowych odstępach czasu.");
        }

        // wstrzymaj symulację
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

        // wznów wstrzymaną symulację
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

            // planuje następny przyjazd samochodu jeśli nie jest zaplanowany
            worldClock.schedule(this::spawnCar, rnd(cfg.simulation.arrivalIntervalMin, cfg.simulation.arrivalIntervalMax), TimeUnit.MILLISECONDS);
        }

        // zatrzymaj łagodnie
        synchronized void stop() {
            if (worldClock == null) return;
            log("Zatrzymywanie symulacji...");

            // zatrzymujemy planowanie nowych samochodów
            worldClock.shutdownNow();

            // przerywamy wszystkie wątki samochodów
            carThreads.shutdownNow();

            // zzekamy na zakończenie przetwarzania (max 2 sekundy)
            try {
                if (!carThreads.awaitTermination(2, TimeUnit.SECONDS)) {
                    log("Niektóre samochody nie zakończyły działania w czasie oczekiwania.");
                }
            } catch (InterruptedException ignored) {}

            // upewniamy się że wszystkie struktury są zresetowane
            worldClock = null;
            simulationPaused = false;
            log("Zatrzymano. Łącznie obsłużonych samochodów: " + servicedCars.get());
        }

        // wywoływane za każdym razem, gdy powinien pojawić się nowy samochód
        private void spawnCar() {
            // jeśli symulacja jest wstrzymana nie planuj nowych przyjazdów
            if (simulationPaused) {
                return;
            }

            // zaplanuj następny przyjazd od razu dla stochastycznych przyjazdów
            int nextArrival = rnd(cfg.simulation.arrivalIntervalMin, cfg.simulation.arrivalIntervalMax);
            if (worldClock != null && !worldClock.isShutdown()) {
                worldClock.schedule(this::spawnCar, nextArrival, TimeUnit.MILLISECONDS);
            }

            if (stationClosed) return; // brak przyjazdów gdy zamknięte
            if (carsOnSite.get() >= cfg.N) {
                log("Stacja pełna - samochód odjeżdża bez wjazdu na stację");
                return; // pełno wiec samochód odjeżdża
            }

            Car car = new Car();
            carsOnSite.incrementAndGet();
            carThreads.submit(car);
        }

        private void closeStation(int durationMs, String fuelType) {
            stationClosed = true;
            log(">>> Stacja ZAMKNIĘTA na uzupełnienie zapasów paliwa " + fuelType + " (" + durationMs/1000 + " s) <<<");

            // uzupełniamy zapasy paliwa które się skończyło
            fuelReserves.get(fuelType).set(1000);

            try {
                Thread.sleep(durationMs);
                stationClosed = false;
                log("<<< Stacja PONOWNIE OTWARTA po uzupełnieniu paliwa " + fuelType + " >>>");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // proces samochodu
        private class Car implements Runnable {
            private final int id = carIdSeq.getAndIncrement();
            @Override public void run() {
                Config.FuelType fuel = cfg.fuelTypes.get(rnd.nextInt(cfg.fuelTypes.size()));
                // losowa ilość paliwa w zakresie minLitresPerCar do maxLitresPerCar
                int litres = rnd(cfg.simulation.minLitresPerCar, cfg.simulation.maxLitresPerCar);
                try {
                    // 1. Auto zajeżdża na stację
                    // informacja o przybyciu z danymi o zajętości dystrybutorów
                    int totalPumps = cfg.M * cfg.pumpsPerSpot;
                    int usedPumps = totalPumps - pumpSemaphore.availablePermits();
                    int usedFuelType = fuelTypeUsage.get(fuel.name).get();
                    int remainingFuel = fuelReserves.get(fuel.name).get();

                    logIfNotPaused(id(), "przybył, chce " + litres + " L paliwa " + fuel.name +
                            " (zajęte dystrybutory: " + usedPumps + "/" + totalPumps +
                            ", z tego typu " + fuel.name + ": " + usedFuelType +
                            ", pozostało: " + remainingFuel + " L)");

                    // sprawdzanie czy mamy wystarczającą ilość paliwa
                    if (remainingFuel < litres) {
                        logIfNotPaused(id(), "odjeżdża - brak wystarczającej ilości paliwa " + fuel.name);
                        return;
                    }

                    // 2. Samochód czeka na wolny dystrybutor
                    logIfNotPaused(id(), "czeka na wolny dystrybutor...");

                    // sprawdza czy symulacja została zatrzymana podczas oczekiwania
                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                        return;
                    }

                    pumpSemaphore.acquire();

                    // sprawdza czy symulacja została zatrzymana po nabyciu dystrybutora
                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        pumpSemaphore.release();
                        logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                        return;
                    }

                    // zwiększamy licznik używania tego typu paliwa
                    fuelTypeUsage.get(fuel.name).incrementAndGet();

                    // aktualizujemy informację o zajętych dystrybutorach
                    usedPumps = totalPumps - pumpSemaphore.availablePermits();
                    logIfNotPaused(id(), "zajął dystrybutor - tankowanie... (zajęte dystrybutory: " + usedPumps + "/" + totalPumps + ")");

                    try {
                        // tankowanie - czas zależny od ilości paliwa
                        fuel(litres, fuel);

                        // zmniejszamy zapas paliwa
                        int newAmount = fuelReserves.get(fuel.name).addAndGet(-litres);
                        logIfNotPaused(id(), "zatankował " + litres + " L paliwa " + fuel.name + " (pozostało: " + newAmount + " L)");

                        // jeśli zapas paliwa spadł poniżej progu stacja jest zamykana
                        if (newAmount <= cfg.fuelRefill.threshold && !stationClosed) {
                            // Uruchamiamy zamknięcie stacji w osobnym wątku
                            new Thread(() -> closeStation(cfg.fuelRefill.durationMillis, fuel.name)).start();
                        }

                        // sprawdza czy symulacja została zatrzymana po tankowaniu
                        if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                            fuelTypeUsage.get(fuel.name).decrementAndGet();
                            pumpSemaphore.release();
                            logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                            return;
                        }

                        // 3. Kierowca idzie do kasy
                        logIfNotPaused(id(), "zakończył tankowanie i zmierza do kasy (pozostało " + newAmount + " L paliwa " + fuel.name + ")");
                        int walkToCashierTime = rnd(cfg.simulation.walkToCashierTimeMin, cfg.simulation.walkToCashierTimeMax);

                        // czeka jeśli symulacja jest wstrzymana
                        waitIfPaused();
                        Thread.sleep(walkToCashierTime);

                        // sprawdz czy symulacja została zatrzymana podczas ruchu do kasy
                        if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                            fuelTypeUsage.get(fuel.name).decrementAndGet();
                            pumpSemaphore.release();
                            logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                            return;
                        }

                        if (!stationClosed) {
                            // 4. Kierowca czeka w kolejce do kasy jeśli wszystkie zajęte
                            logIfNotPaused(id(), "czeka w kolejce do kasy...");

                            // sprawdza, czy symulacja została zatrzymana podczas czekania w kolejce
                            if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                                fuelTypeUsage.get(fuel.name).decrementAndGet();
                                pumpSemaphore.release();
                                logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                                return;
                            }

                            // czeka jeśli symulacja jest wstrzymana
                            waitIfPaused();
                            cashierSemaphore.acquire();

                            // sprawdza czy symulacja została zatrzymana po nabyciu kasy
                            if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                                cashierSemaphore.release();
                                fuelTypeUsage.get(fuel.name).decrementAndGet();
                                pumpSemaphore.release();
                                logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                                return;
                            }

                            // informacja o zajętych kasach
                            int usedCashiers = cfg.K - cashierSemaphore.availablePermits();
                            logIfNotPaused(id(), "przy kasie... (zajęte kasy: " + usedCashiers + "/" + cfg.K + ")");

                            // platnosc ma stały czas dla wszystkich klientów
                            waitIfPaused();
                            Thread.sleep(rnd(cfg.simulation.paymentTimeMin, cfg.simulation.paymentTimeMax));
                            logIfNotPaused(id(), "zakończył płatność");
                            cashierSemaphore.release();

                            // 5. Kierowca wraca do samochodu
                            logIfNotPaused(id(), "wraca do samochodu...");

                            waitIfPaused();
                            int walkToCarTime = rnd(cfg.simulation.walkToCarTimeMin, cfg.simulation.walkToCarTimeMax);
                            Thread.sleep(walkToCarTime);

                            // 6. Samochód odjeżdża, zwalniając dystrybutor
                            // Zmniejszamy licznik używania tego typu paliwa
                            fuelTypeUsage.get(fuel.name).decrementAndGet();
                            pumpSemaphore.release();

                            servicedCars.incrementAndGet();
                            logIfNotPaused(id(), "gotowe i odjeżdża");
                        } else {
                            logIfNotPaused(id(), "musi poczekać na otwarcie stacji, aby zapłacić");

                            // oczekiwanie na otwarcie stacji
                            while (stationClosed) {
                                if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                                    fuelTypeUsage.get(fuel.name).decrementAndGet();
                                    pumpSemaphore.release();
                                    logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                                    return;
                                }
                                waitIfPaused();
                                Thread.onSpinWait();
                            }

                            logIfNotPaused(id(), "czeka w kolejce do kasy po ponownym otwarciu...");

                            // sprawdza czy symulacja została zatrzymana podczas czekania
                            if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                                fuelTypeUsage.get(fuel.name).decrementAndGet();
                                pumpSemaphore.release();
                                logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                                return;
                            }

                            waitIfPaused();
                            cashierSemaphore.acquire();

                            // sprawdza czy symulacja została zatrzymana
                            if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                                cashierSemaphore.release();
                                fuelTypeUsage.get(fuel.name).decrementAndGet();
                                pumpSemaphore.release();
                                logIfNotPaused(id(), "odjeżdża - symulacja została zatrzymana");
                                return;
                            }

                            int usedCashiers = cfg.K - cashierSemaphore.availablePermits();
                            logIfNotPaused(id(), "przy kasie po ponownym otwarciu... (zajęte kasy: " + usedCashiers + "/" + cfg.K + ")");

                            waitIfPaused();
                            Thread.sleep(rnd(cfg.simulation.paymentTimeMin, cfg.simulation.paymentTimeMax));
                            logIfNotPaused(id(), "zakończył płatność");
                            cashierSemaphore.release();

                            // kierowca wraca do samochodu
                            logIfNotPaused(id(), "wraca do samochodu...");

                            waitIfPaused();
                            int walkToCarTime = rnd(cfg.simulation.walkToCarTimeMin, cfg.simulation.walkToCarTimeMax);
                            Thread.sleep(walkToCarTime);

                            // auto odjezdza zwalniając dystrybutor
                            fuelTypeUsage.get(fuel.name).decrementAndGet();
                            pumpSemaphore.release();

                            servicedCars.incrementAndGet();
                            logIfNotPaused(id(), "gotowe i odjeżdża");
                        }
                    } finally {
                        // upewnia sie że zasoby są zwolnione w przypadku przerwania
                        if (Thread.currentThread().isInterrupted()) {
                            fuelTypeUsage.get(fuel.name).decrementAndGet();
                            pumpSemaphore.release();
                        }
                    }

                } catch (InterruptedException e) {
                    // obsługa przerwania wątku
                    Thread.currentThread().interrupt();
                    logIfNotPaused(id(), "przerwano obsługę - samochód odjeżdża");

                    // zwalnia zasoby jeśli były nabyte
                    try {
                        fuelTypeUsage.get(fuel.name).decrementAndGet();
                        pumpSemaphore.release();
                    } catch (Exception ignored) {
                    }
                } finally {
                    // zawsze zmniejsza liczbę samochodów na stacji
                    carsOnSite.decrementAndGet();
                }
            }

            // oczekuje jeśli symulacja jest wstrzymana

            private void waitIfPaused() throws InterruptedException {
                while (simulationPaused) {
                    if (Thread.currentThread().isInterrupted() || worldClock == null || worldClock.isShutdown()) {
                        throw new InterruptedException("Symulacja została zatrzymana");
                    }
                    Thread.onSpinWait();
                }
            }

            // tankowanie

            private void fuel(int litres, Config.FuelType fuel) throws InterruptedException {
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

            private String id() { return "Auto-" + id; }
        }

        // wiadomość tylko jeśli symulacja nie jest wstrzymana

        private void logIfNotPaused(String who, String s) {
            // uzywa blokady do synchronizacji - wiadomości nie będą pomieszane
            if (!simulationPaused) {
                logLock.lock();
                try {
                    if (!simulationPaused) { // sprawdza jeszcze raz bo status mógł się zmienić podczas oczekiwania na blokadę
                        System.out.println("[" + who + "] " + s);
                    }
                } finally {
                    logLock.unlock();
                }
            }
        }

        // STATYSTYKI I LOGOWANIE
        void printStats() {
            StringBuilder fuelStats = new StringBuilder();
            for (Map.Entry<String, AtomicInteger> entry : fuelTypeUsage.entrySet()) {
                String fuelType = entry.getKey();
                int usedPumps = entry.getValue().get();
                int reserves = fuelReserves.get(fuelType).get();

                fuelStats.append("\n dystrybutory paliwa ").append(fuelType)
                        .append(": ").append(usedPumps).append(" zajętych")
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

        private void log(String s) { System.out.println(s); }
        private void log(String who, String s) {
            // standardowe logowanie używane tylko przez główny wątek symulacji
            System.out.println("[" + who + "] " + s);
        }

        // uzywane narzedzia
        private final AtomicInteger carIdSeq = new AtomicInteger(1);
        private int rnd(int min, int max) { return rnd.nextInt(max - min + 1) + min; }
        private double rndGaussian(double mean, double sd) { return mean + rnd.nextGaussian() * sd; }
    }
}