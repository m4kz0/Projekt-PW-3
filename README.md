Gas Station Simulator (Java Multithreading & JavaFX)
Zaawansowana symulacja stacji paliw oparta na modelu współbieżnym, zrealizowana w języku Java. Projekt wizualizuje procesy zachodzące na stacji – od przyjazdu pojazdu, przez tankowanie konkretnego rodzaju paliwa, po płatność w kasie i zarządzanie zapasami.
Główne Funkcje
Wielowątkowość (Concurrency): Każdy samochód jest osobnym wątkiem, a zasoby (dystrybutory, kasy) są zarządzane za pomocą semaforów (Semaphores) i bezpiecznych kolekcji (ConcurrentHashMap, AtomicInteger).

Wizualizacja JavaFX: Dynamicznie renderowany interfejs graficzny z wykorzystaniem Canvas API i AnimationTimer.

Logika Paliwowa: Symulacja obsługuje różne rodzaje paliw. Każdy dystrybutor ma przypisany typ paliwa, a system kolejek kieruje auta do odpowiednich stanowisk.

System Rezerw: Stacja monitoruje poziom paliwa w zbiornikach. Po przekroczeniu progu krytycznego następuje automatyczne zamknięcie stacji na czas dostawy (refill).

Zarządzanie Symulacją: Możliwość uruchamiania, wstrzymywania (pauza) oraz płynnego zatrzymywania symulacji.

Statystyki Live: Panel boczny wyświetla aktualną liczbę obsłużonych aut, zajętość kas oraz stan zapasów paliwa w czasie rzeczywistym.

🛠️ Technologie
Język: Java 17+

GUI: JavaFX

Zasoby: SnakeYAML (do ładowania konfiguracji z plików .yaml)

Architektura: Concurrent Programming (Executors, Semaphores, ReentrantLocks)

Konfiguracja
Projekt korzysta z pliku config.yaml, który pozwala na pełne dostosowanie parametrów symulacji:

Liczba stanowisk (M) i dystrybutorów na stanowisko.

Liczba dostępnych kas (K).

Pojemność parkingu/stacji (N).

Czasy tankowania, płatności oraz interwały przyjazdów samochodów.

Próg i czas uzupełniania paliwa.

Podgląd interfejsu
Interfejs podzielony jest na cztery logiczne sekcje:

Panel Kontrolny: Przyciski Start/Pause/Resume/Stop.

Wizualizacja: Graficzny podgląd stanowisk (zielony - wolny, czerwony - zajęty), drogi dojazdowej oraz budynku kas.

Statystyki: Szczegółowe dane o stanie zbiorników i przepustowości.

Logi: Konsola tekstowa rejestrująca każde zdarzenie (np. "Auto-5 rozpoczęło tankowanie").

⚙️ Uruchomienie
Upewnij się, że masz zainstalowane JDK (min. 17) oraz biblioteki JavaFX.

Dodaj zależność SnakeYAML do swojego projektu (Maven/Gradle).

Umieść plik config.yaml w głównym folderze projektu.

Uruchom klasę GUI.java.

🧠 Kluczowe wyzwania programistyczne
Synchronizacja: Rozwiązanie problemu "zakleszczenia" (deadlock) przy nabywaniu wielu pozwoleń (semafor ogólny vs semafor typu paliwa).

Płynność GUI: Zapewnienie responsywności interfejsu przy intensywnej pracy wielu wątków w tle dzięki Platform.runLater().

Skalowalność: Algorytm rysujący stację automatycznie skaluje komponenty na Canvasie w zależności od liczby stanowisk zdefiniowanych w konfiguracji.

Projekt stworzony w celach edukacyjnych, demonstrujący praktyczne zastosowanie programowania współbieżnego w Javie.
