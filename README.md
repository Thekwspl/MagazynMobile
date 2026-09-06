# Magazyn Mobile

Natywna aplikacja Android do lokalnej obsługi magazynu głównego, pracowników i stanów stoczni.

## Obecny zakres — 0.9.12

- historia zamówienia pomija przygotowanie pozycji i nie zapisuje operacji, która nie zmieniła danych;
- zmiana produktu jest zapisywana jako `Poprawiono / Z: stary przedmiot / Na: nowy przedmiot`, a zmiana ilości pokazuje nazwę produktu oraz wartości `Z` i `Na`;
- z widoku historii zmian usunięto zbędny podpis `Użytkownik lokalny`;
- tworzenie i edycja zadania odbywa się na pełnym ekranie z wyraźnym powrotem, anulowaniem i zapisem;
- przycisk `Nowe zadanie` jest widoczny bezpośrednio po wejściu w `Zadania` z menu bocznego;
- podpowiedzi osób w zadaniu są nieznacznie większe;
- zadanie może mieć własne opcjonalne `Miejsce`, niezależne od stoczni, widoczne na karcie i uwzględniane w wyszukiwaniu;
- migracja 18→19 dodaje miejsca do zadań bez utraty dotychczasowych danych;
- aplikacja używa zaakceptowanej ikony MM z symbolem magazynu i czerwonym akcentem;

- wynik tworzenia kopii zapasowej jest pokazany w dużym, kolorowym komunikacie; powodzenie zawiera dokładną datę i godzinę utworzenia;
- w `Znajdź i wydaj` wiersze osób mają tę samą kompaktową wysokość co wiersze stoczni;
- jedno zadanie można powiązać z kilkoma osobami, wyszukując i dodając je kolejno w edytorze;
- migracja 17→18 zachowuje istniejące zadania jednoosobowe i przenosi ich powiązania do modelu wieloosobowego;
- formularz tworzenia nierozpoznanej osoby z zamówienia wstępnie uzupełnia imię i nazwisko odczytane z notatki;
- historia zmian zamówienia jest domyślnie schowana pod przyciskiem obok `Dodaj pozycję`;
- zaznaczanie przygotowania nie jest zapisywane w historii, wcześniejsze takie wpisy są ukryte, a zapis bez faktycznej zmiany nie tworzy wpisu;
- zmiana produktu jest opisywana czytelnym zapisem `stary produkt → nowy produkt`;

- hotfix parsera rozmiarów: oznaczenie `r.` jest rozpoznawane wyłącznie jako osobny znacznik i nie uszkadza już nazw takich jak `Filtry` ani `monterski`;
- wyszukiwanie toleruje brak jednej litery na początku słowa, np. `Dszczówka` nadal odpowiada zapytaniu `des`;

- darmowy kanał aktualizacji `GitHub Actions → GitHub Releases → telefon`, z ekranem sprawdzania, pobierania i uruchamiania systemowego instalatora APK;
- podpisany build wydania uruchamiany tagiem `v*`; prywatny klucz i hasła są pobierane wyłącznie z sekretów GitHub Actions;
- `Nowe zadanie` jest dostępne również pod środkowym przyciskiem `+`;
- popularne skrócone i potoczne imiona są rozwijane lokalnie do pełnej formy, a parser AI nadal pomaga w mniej oczywistych przypadkach;
- stanowiska wybiera się z istniejącej listy, z możliwością dopisania nowego bez opuszczania formularza osoby lub zamówienia;
- zwrot pracownika jest dopisywany do pierwotnego wiersza w historii wydań wraz z ilością i datą;
- zwrot wielu przedmiotów ze stoczni jest dostępny bezpośrednio na ekranie wybranej stoczni;
- `Znajdź i wydaj` ma jedno pole przeszukujące osoby, stocznie i przedmioty;
- dopasowanie deszczówek toleruje uszkodzony znak importu i ma testy wariantów od `50/M` do `64/4XL`;
- w inwentaryzacji usunięto podwójne odsunięcie od klawiatury, a nazwy produktów są większe;
- migracja 16→17 zachowuje dane i dodaje audytowalne powiązanie zwrotu z pierwotnym wydaniem;

- osobny ekran `Zadania` z wyszukiwaniem oraz filtrami: otwarte, zakończone i wszystkie;
- tworzenie, edycja i usuwanie zadań oraz szybkie odznaczanie checkboxem;
- opcjonalny termin i priorytet zadania (`niski`, `normalny`, `wysoki`, `pilny`), z oznaczeniem terminów zaległych;
- opcjonalne powiązanie zadania z osobą, stocznią, przedmiotem i aktywnym zamówieniem;
- sekcja `Do zrobienia` na Start prowadzi do pełnego rejestru zadań, a terminy są widoczne także w skróconej liście;
- migracja 15→16 zachowuje istniejące zadania i dodaje im nowe pola bez kasowania danych;

- zakładka `Kopia` tworzy zaszyfrowany hasłem plik `.magazynbackup` z całą bazą oraz przywraca go po kontroli hasła, formatu, wersji i spójności SQLite;
- po bezpiecznym przywróceniu aplikacja automatycznie uruchamia się ponownie; klucz Gemini nie jest zapisywany w kopii;
- nierozpoznane pozycje importu są grupowane według nazwy, można je wyszukiwać, a pojedyncze mapowanie naprawia całą grupę identycznych wpisów;
- `Wymaga uwagi` wykrywa również możliwe duplikaty osób i przedmiotów oraz otwiera właściwą kartę do porównania, poprawy albo usunięcia;
- Historia ma rozszerzone filtry zakresu dat, magazynu, kategorii i tagu oraz wspólne czyszczenie filtrów;
- zapis `DD.MM` w notatce jest rozpoznawany jako proponowana data wydania w bieżącym roku, pokazywana na ekranie weryfikacji i możliwa do zmiany przed utworzeniem szkicu;

- poprawa rozpoznanej pozycji ma liniowy układ `odbiorca → przedmiot → szczegóły`, z propozycjami osób i stoczni bezpośrednio pod odbiorcą oraz propozycjami produktów pod polem przedmiotu;
- przypisanie osoby do aktywnego zamówienia otwiera wyszukiwarkę po nazwisku, imieniu, ksywce, tagach, stanowisku i telefonie, a nową osobę można dodać bez wychodzenia z zamówienia;
- z menu pod środkowym `+` usunięto `Popraw stan` oraz `Wydaj dla stoczni`;
- kafelki historii są niższe: mają mniejsze odstępy, jednoliniowe podsumowanie i liczbę pozycji w wierszu odbiorcy;

- ogólne określenia `kombinezon`, `ciuchy` i `ubranie` są rozwijane na dwie pozycje: spodnie i bluzę;
- skróty `mXX` i `sXX` od rozmiaru 48 oznaczają odpowiednio komplet monterski albo spawalniczy, chyba że wskazano wyłącznie spodnie lub bluzę; poniżej 48 oznaczają buty;
- firmowe reguły odzieży są stosowane zarówno po analizie Gemini, jak i przez parser offline;
- menu boczne nie powiela Startu, wyszukiwania/wydawania ani Historii dostępnych na dolnej belce;
- import oraz eksport znajdują się na wspólnym ekranie z dwiema zakładkami;
- `Ustawienia AI` znajdują się osobno na samym dole menu bocznego;
- menu pod środkowym `+` nie zawiera już importu ani ustawień AI, a pozostałe skróty wykonują przypisane operacje;

- model Gemini został zmieniony na `gemini-3.6-flash`, zgodnie z komunikatem zwróconym przez usługę dla tego klucza;
- AI ma określoną, ograniczoną rolę parsera: klasyfikuje notatkę, rozpoznaje odbiorców, stocznię, telefony, zadania i pozycje, ale nie zapisuje operacji bez zatwierdzenia;
- jedna wspólna normalizacja wyszukiwania obejmuje teraz również podpowiedzi wydania osobie, stoczni, operacje, zamówienia, historię i inwentaryzację;
- wyszukiwanie ignoruje znaki diakrytyczne, ukośniki, odstępy oraz niewidoczne znaki po imporcie, dlatego warianty `50/M` nie rozbijają dopasowania nazwy deszczówki;

- panel `Stocznie` pokazuje wyszukiwarkę z ikoną zarządzania i responsywne kafelki zamiast formularza oraz listy radiowej;
- dodawanie, zmiana nazwy i bezpieczne usuwanie stoczni znajdują się w jednym oknie zarządzania;
- wybrana stocznia otwiera osobny ekran: wielopozycyjne wydanie jest na górze, dalej znajduje się stan i prowadzący, a eksport pozostaje na dole;
- `Szybkie pole` rośnie wraz z treścią i nie ogranicza wklejonej wiadomości do czterech wierszy;
- przyciski `Nowe zamówienie`, `Szybkie wydanie` i dzwonek na pulpicie wykonują teraz odpowiednio: otwarcie ręcznego zamówienia, przejście do wydania oraz pokazanie podsumowania powiadomień;
- ekran rozpoznania pozwala dodać dowolną liczbę pozycji przed zapisaniem szkicu;
- AI korzysta z `gemini-3.6-flash` i pokazuje dokładną przyczynę błędu połączenia lub odpowiedzi;
- parser rozdziela zestawy typu `spodnie + bluza`, a wspólny alias przypisany do kilku produktów może rozwinąć jedno określenie na kilka pozycji;
- wyszukiwanie produktów ma wspólną normalizację nazwy, wariantu, aliasów i tagów; naprawiono przypadek `Deszczówka Góra` z wariantem `50/M` oraz niewidoczne znaki po imporcie;

- „Rozpoznane zamówienie” jest osobnym ekranem bez możliwości przypadkowego zsunięcia;
- oryginalna wiadomość pozostaje widoczna podczas sprawdzania i poprawiania pozycji;
- osoba jest oznaczana jako rozpoznana wyłącznie przy dokładnym dopasowaniu pełnego imienia i nazwiska albo aliasu;
- odbiorcą pozycji może być osoba albo stocznia, a przypisana stocznia jest odbiorcą domyślnym pozycji bez własnego odbiorcy;
- migracja 14→15 scala „Ulstein - elektro” z „Ulstein - Elektro” oraz normalizuje „Ulstein - Rura”, zachowując stany, prowadzących, zamówienia i historię;

- stabilny, w pełni rozwinięty i przewijalny panel weryfikacji zamówienia, który utrzymuje aktywne pole nad klawiaturą;
- poprawne wyświetlanie również ostatnich pozycji długiego zamówienia;
- rozpoznawanie stoczni w „Szybkim polu” po nazwie, skrócie oraz po przypisanym prowadzącym lub jego ksywce;
- ręczne zatwierdzenie albo zmiana rozpoznanej stoczni przed utworzeniem szkicu;
- realizacja zamówienia bez przypisanej osoby bezpośrednio na stan rozpoznanej stoczni;
- przypisywanie jednej lub wielu osób prowadzących do każdej stoczni;
- sekcja „Wymaga uwagi” nie pokazuje zerowych ostrzeżeń, a przy braku problemów wyświetla krótki komunikat;
- usunięty komunikat „Zmiany zapisują się lokalnie” z ekranu startowego;

- „Znajdź i wydaj” wyszukuje osoby, przedmioty oraz stocznie; wybrana stocznia otwiera swój panel wydania;
- jeden panel „Stocznie” łączy wyszukiwanie stoczni, jej stan, wielopozycyjne wydanie z magazynu głównego oraz eksport XLSX, CSV i PDF;
- data wydania stoczni i przycisk dodania kolejnego przedmiotu są obok siebie;
- zwrot pracownika jest dostępny przy konkretnym wpisie w historii jego wydań;
- dodanie osoby lub produktu jest możliwe bez zamykania edycji zamówienia;
- każda zmiana pozycji, odbiorcy, daty, przygotowania, usunięcia i realizacji zamówienia trafia do chronologicznego rejestru zmian;
- uczenie offline obsługuje reguły produktów, osób/ksywek, stanowisk oraz całych schematów wiadomości;
- ustawienia AI pokazują ostatnią próbę, model i wynik; aplikacja używa modelu zapasowego przy niedostępności głównego;
- klucz AI po ponownej instalacji musi zostać zapisany ponownie w ustawieniach;

- poprawione importy Compose blokujące kompilację wersji 0.6.0 (`dp` oraz ikona edycji);
- automatyczne ponowienie zapytania AI przy chwilowych błędach 429/5xx oraz czytelna propozycja użycia analizy offline przy błędzie 503;
- możliwość poprawienia ujemnego stanu bezpośrednio z listy „Wymaga uwagi”;
- nierozpoznane pozycje importu nadal można przypisać do istniejącego albo nowego produktu;
- bezpieczne usuwanie osoby przez archiwizację z zachowaniem historii wydań;
- ujednolicony model: mini-magazyn jest stanem stoczni, a jedynym magazynem źródłowym pozostaje magazyn główny;
- migracja bazy 11→12 przenosząca stany dawnych mini-magazynów do odpowiadających im stoczni bez zmiany magazynu głównego;
- przekazanie stoczni odejmuje towar z magazynu głównego, a zwrot ze stoczni przyjmuje go z powrotem;
- eksport stanu również do czytelnego, wielostronicowego PDF;
- rozdzielanie wielowierszowych i wielopozycyjnych notatek na osobne pozycje zamówienia;
- podpowiadanie osób według nazwiska, aliasów, stanowisk i tagów oraz produktów według nazwy, wariantu, aliasów, klasyfikacji i tagów;
- wyraźne oznaczanie nierozpoznanej osoby albo produktu przed utworzeniem i podczas kompletowania zamówienia;
- możliwość dopisania tagów do produktu bezpośrednio podczas poprawiania rozpoznanej pozycji;
- panel `Zamówienia` z listą szkiców, postępem kompletacji, edycją odbiorcy, daty, produktów i ilości;
- dodawanie i usuwanie pozycji zamówienia oraz checkbox przygotowania każdej pozycji;
- realizacja kompletnego zamówienia jako jednego wydania z magazynu głównego, także po świadomym potwierdzeniu stanu ujemnego;
- panel `Uczenie offline` do dodawania, edytowania i usuwania lokalnych reguł parsera;
- bezpieczne usuwanie produktów przez archiwizację z zachowaniem historii ruchów;
- projekt Kotlin + Jetpack Compose;
- lokalna baza Room/SQLite;
- encje magazynów, osób, stanowisk, produktów, stanów, notatników i zamówień;
- ekran startowy łączący zaakceptowane warianty UI B i D;
- wysuwane menu boczne mieszczące rosnącą liczbę funkcji;
- połączony ekran „Znajdź i wydaj” z profilem osoby, bezpośrednim wydaniem oraz wyszukiwaniem przedmiotów;
- osobny panel „Stocznie” ze stanem, wydaniem i eksportem;
- duże akcje do zamówienia i szybkiego wydania;
- uniwersalne pole do wyszukiwania lub wklejania treści zamówienia;
- pierwszy parser offline oraz obowiązkowy ekran weryfikacji;
- opcjonalna analiza notatki przez Gemini 3.6 Flash bez własnego serwera;
- klucz API wpisywany wyłącznie na telefonie, szyfrowany przez Android Keystore i wyłączony z kopii zapasowych;
- test połączenia oraz możliwość usunięcia lub podmiany klucza w „Ustawieniach AI”;
- domyślne ukrywanie numerów telefonów przed wysłaniem notatki do usługi AI;
- osobne checkboxy i edycja każdej pozycji rozpoznanej przez AI;
- utworzenie szkicu zamówienia wyłącznie z zatwierdzonych pozycji, z oznaczeniem niedopasowanych produktów do dalszego mapowania;
- dolna nawigacja do przedmiotów, osób i historii;
- testy jednostkowe parsera.
- niższy nagłówek ekranu startowego z bieżącą datą;
- centralny przycisk „+” osadzony w dolnej belce;
- działająca lista i wyszukiwanie osób po nazwisku, stanowisku, aliasie lub tagu;
- dodawanie osoby z wymaganymi oddzielnymi polami imienia i nazwiska; pozostałe dane są opcjonalne;
- automatyczny zapis imienia i nazwiska wielką literą oraz blokowanie cyfr;
- działająca lista i wyszukiwanie przedmiotów;
- dodawanie produktów, wariantów, jednostek, kategorii, aliasów i tagów;
- stan początkowy podawany od razu podczas tworzenia produktu;
- wartości zerowe w formularzu pokazane jako szare podpowiedzi, więc nie trzeba ich kasować przed wpisaniem;
- jednostka wybierana z gotowej listy rozszerzanej o jednostki istniejących produktów;
- opcjonalna grupa i podgrupa oraz własne słowniki grup, podgrup i kategorii;
- automatyczny zapis nazwy produktu od wielkiej litery;
- ustawiany dla produktu odstęp ponownego wydania w tygodniach;
- informacyjne ostrzeżenie o zbyt wczesnym ponownym wydaniu bez blokowania operacji;
- oznaczenie produktu jako sprzętu powierzonego.
- aktualny stan widoczny bezpośrednio na liście przedmiotów;
- pełna edycja danych produktu i szybka korekta stanu bez pola powodu;
- stan i korekta na górze karty produktu oraz wyłącznie całkowite ilości;
- aliasy i tagi rozdzielane przecinkami;
- opcjonalne zdjęcie produktu wybierane z galerii telefonu;
- profil osoby z edycją danych, aktywnie powierzonym sprzętem i historią wydań;
- dwukolumnowa historia wydań: przedmiot po lewej, data po prawej, bez zbędnej ilości;
- historia osoby uporządkowana od najnowszych wydań do najstarszych;
- edycja wcześniejszego wydania: przedmiot, ilość i data, z automatycznym przeliczeniem stanu;
- bezpieczne usunięcie błędnego wydania jako audytowalna korekta zamiast kasowania oryginału;
- lokalne zapamiętywanie zatwierdzonych poprawek nazw, wariantów i jednostek oraz używanie ich bez AI;
- pełna lista ruchów magazynowych uporządkowana od najnowszych;
- wyszukiwanie historii po osobie, stoczni, przedmiocie, opisie i typie operacji;
- filtry historii: wydania, przyjęcia, korekty, stocznie oraz importy;
- karta szczegółów ruchu z każdą pozycją i dokładną zmianą stanu `+/-`;
- najnowsze wydanie produktu w historii osoby jest wyróżnione kolorem, jeśli nie minął jeszcze zalecany okres ponownego wydania;
- przy wyróżnionej pozycji widoczna jest dokładna data, od której ponowne wydanie jest zgodne z ustawieniem produktu;
- bezpośrednie wydanie z profilu osoby z wyszukiwaniem i podpowiedziami produktów;
- wielopozycyjne wydanie osobie z przyciskiem „Dodaj kolejny przedmiot”;
- wydanie wielu produktów dla stoczni oraz dodawanie i bezpieczne usuwanie stoczni z zachowaniem historii;
- osobny podgląd aktualnego stanu każdej wybranej stoczni;
- wydanie przesuwa ilość z magazynu głównego na stan konkretnej stoczni;
- wspólny ekran „Wyszukaj” dla osób i przedmiotów zamiast dolnej zakładki „Przedmioty”;
- zakładka „Wydaj” zamiast „Osoby”, z wyborem wydania osobie albo stoczni;
- jeden lub wiele numerów telefonu w profilu osoby, widocznych przy imieniu i nazwisku;
- kliknięcie numeru otwiera systemowy ekran wybierania numeru bez ręcznego kopiowania;
- listy osób są sortowane według nazwiska i pokazują nazwisko przed imieniem;
- komunikaty „Wymaga uwagi” otwierają listę konkretnych ujemnych stanów albo nierozpoznanych pozycji importu;
- każdą nierozpoznaną pozycję można przypisać do istniejącego produktu albo od razu utworzyć jako nowy produkt;
- zatwierdzenie mapowania uzupełnia historyczne wydanie osobie lub stan stoczni bez zmiany aktualnego magazynu głównego;
- jedno przypisanie rozwiązuje wszystkie oczekujące wpisy o tej samej nazwie i zapisuje tę nazwę jako alias produktu dla przyszłych importów;
- wyszukiwanie osoby również po numerze telefonu;
- rozpoznawanie numeru telefonu w „Szybkim polu” i zapis do znalezionego profilu dopiero po zatwierdzeniu;
- import `Wydanie Stocznie.xlsx` automatycznie tworzący stocznie i brakujące produkty;
- importowane wydania zwiększają stan właściwej stoczni bez zmiany magazynu głównego;
- ponowne wskazanie pliku stoczni naprawia brakujące lub wcześniej usunięte stocznie bez podwójnego naliczania poprawnych pozycji;
- inteligentne rozpoznawanie treści jako zamówienie, zadanie, kontakt albo zwykła notatka;
- zapisywanie rozpoznanych zadań jako checklisty widocznej na ekranie startowym;
- podpowiedzi produktów wyświetlane wewnątrz formularza, poza klawiaturą, ze stanem wyrównanym do prawej;
- przejrzysta tabela aktualnie posiadanych rzeczy wraz z datami wydania;
- wybór daty wydania oraz świadome potwierdzenie stanu ujemnego;
- rejestrowanie korekt i wydań jako niezmiennych ruchów historycznych;
- zachowanie miejsca przewijania po wejściu w profil osoby lub kartę przedmiotu;
- daty prezentowane w formacie `DD MMM RRRR`;
- formularze utrzymujące aktywne pole nad klawiaturą ekranową;
- osobny panel „Operacje” do dostaw, zwrotów ze stoczni i znalezionych przedmiotów; zwrot pracownika jest przy jego historii, a wydanie stoczni w panelu „Stocznie”;
- wielopozycyjne operacje z wyborem daty i świadomym potwierdzeniem stanu ujemnego;
- zwrot pracownika rozliczający kolejno jego aktywnie posiadane egzemplarze;
- pełna inwentaryzacja wybranego magazynu z wyszukiwaniem, porównaniem stanu i ekranem potwierdzenia;
- puste pola inwentaryzacji pozostawiają stan bez zmian, a korekty nie wymagają powodu;
- eksport stanu wybranego magazynu do pliku XLSX albo CSV i udostępnienie go z telefonu;
- arkusz XLSX ma zamrożony nagłówek, filtr i kolumny magazynu, produktu, wariantu, grupy, podgrupy, kategorii, stanu oraz jednostki;
- migracje bazy 1→2→3→4→5→6→7→8→9→10→11→12→13→14→15→16→17→18→19 zachowujące dane z wcześniejszej instalacji;
- import plików XLSX wybieranych z pamięci telefonu;
- automatyczne rozpoznawanie arkuszy `Stan Magazynowy`, `Osoby` i `Wydanie Stocznie`;
- podgląd liczby rekordów, błędów, powtórzeń i nierozpoznanych produktów przed zatwierdzeniem;
- rozróżnienie potwierdzonego zera (`Brak`) od pustego, nieustalonego stanu;
- transakcyjny zapis importu oraz ochrona przed ponownym importem tego samego pliku;
- historia z arkuszy nie zmienia ponownie bieżącego stanu magazynu;
- nierozpoznane nazwy są zachowywane w kolejce mapowania;
- migracja bazy 4→5 dodająca rejestr importów i stany nieustalone.

## Otwarcie projektu

1. Zainstaluj aktualne Android Studio.
2. Otwórz katalog `MagazynMobile`.
3. Pozwól Android Studio pobrać Gradle i Android SDK 35.
4. Uruchom konfigurację `app` na telefonie lub emulatorze z Androidem 8.0+.

Projekt używa Javy 17. Pierwsza synchronizacja wymaga internetu do pobrania zależności.

## Następny etap

- pierwsze uruchomienie workflow GitHub na docelowym repozytorium i test aktualizacji istniejącej instalacji bez odinstalowywania;
- automatyczne proponowanie terminów, priorytetów i powiązań z treści zadania;
- powiadomienia Android o zbliżających się i zaległych terminach;
- ekran pełnego rejestru zamówień z filtrem także dla zrealizowanych i anulowanych;
- edycja albo korekta pojedynczego historycznego wydania stoczni;
- testy na rzeczywistych danych i dopracowanie reguł po pierwszych poprawkach użytkownika;
- opcjonalna synchronizacja dopiero wtedy, gdy pojawi się drugie urządzenie lub panel komputerowy.
