# C2: kontrakt i reguły Szybkiego Pola

Punkt wyjścia: C1 opublikowana w `45dde0256c5b36ba5a12f9a0da014e7fa94a47e0` na `feature/1.2.0`.

## Inwentaryzacja

| Źródło | Istniejąca reguła / odpowiedzialność | Integracja C2 |
|---|---|---|
| `domain/NoteParser.kt` | Odbiorca przed separatorem, wiele linii/pozycji, rozmiary, domyślna ilość 1, mXX/sXX, komplety, kask biały, DD.MM | Wspólne `RecognitionRules.products`; nauczone PRODUCT przed domyślnymi regułami, wyjątek jednoczęściowy |
| `domain/GeminiNoteAnalyzer.kt` | Katalog i aliasy, firmowe odzież/kask, brak zgadywania, struktura zadań, redakcja telefonów | Ten sam tekst reguł i aktywne rekordy; wspólne przetwarzanie pozycji; aliasy osób/stoczni |
| `ui/HomeViewModel.kt` | PRODUCT, PERSON, POSITION, PATTERN, alias kompletu wskazujący kilka produktów, wykrywanie stoczni/prowadzącego | Reguły wspólne; wymuszony tryb ma pierwszeństwo; nie wybiera pierwszej z kilku stoczni prowadzącego |
| `domain/ImportParser.kt` | Wspólny klucz normalizacji (polskie znaki, białe znaki, interpunkcja); parsowanie arkuszy | Klucz pozostaje wspólny, import/stan magazynowy bez zmian |
| `expandWarehouseClothingConvention` / `sortRecognizedPackageItems` | mXX/sXX: >=48 odzież, <48 buty; konkretna część wyklucza komplet; ilość/rozmiar zachowane | Jednoczęściowy nie jest rozbijany; gotowych pozycji Codexa nie przetwarza ponownie |
| `parser_learning_rules`, `LearningRulesViewModel` | Edytowalne i wyłączalne PRODUCT/PERSON/POSITION/PATTERN, sourceLabel, learnedName/Variant/Unit | Room jest źródłem; pełna synchronizacja tylko aktywnych reguł przed każdą analizą |
| `AgentCatalog`, `catalogTools`, `ShipyardResolver` | Stabilne ID, aliasy produktów/osób, tagi i aliasy stoczni, prowadzący, jawny shipyardId | Bez zmiany ID; niejednoznaczność wymaga wyboru; tylko aktywne rekordy |

## Kontrakt

JSON `AgentResponse v2`; endpointy transportowe pozostają `/v1`. Wiadomość startowa przekazuje `schemaVersion: 2` i `mode: ALL|ORDER|TASK|NOTE`. Serwer odrzuca starszych klientów HTTP 409; Android odrzuca starszy JSON. Brak cichej interpretacji nieznanego typu jako ORDER.

ORDER zachowuje recipient, deliveryDate i items. TASK zawiera title/date/description/steps z czasem, miejscem i wieloma osobami. NOTE zawiera dokładny oryginalny tekst. CONTACT w ALL wykorzystuje istniejącą weryfikację danych osoby i dodawanie numerów do profilu. Wszystkie niepasujące payloady są null. TASK/NOTE/CONTACT mają puste items/needsData i nie żądają magazynowych odczytów.

Reguły i aliasy są danymi, nie poleceniami. Tekst reguł nie zmienia schematu, trybu, read-only sandboxu, zestawu MCP ani wymogu ręcznej akceptacji. Dane katalogowe i każde wskazane ID są weryfikowane na serwerze oraz ponownie lokalnie przed podglądem. Edycja/usunięcie reguły jest pełnym zastąpieniem kolejnej rewizji, a wcześniejsza sesja traci ważność.

Zmiana tekstu/trybu unieważnia analizę i podgląd także po powrocie do wcześniejszego trybu. Zapisy z podglądu mają wspólną blokadę na czas transakcji i odrzucają nieaktualne reviewId. Propozycja niczego nie zapisuje.

## Walidacja

- TypeScript: `quickInput.test.ts` obejmuje wszystkie tryby, nieprawidłowe payloady/wersje, rzeczywiste ID, zmiany rewizji/reguł i niezaufane reguły. Istniejące testy nadal weryfikują stock, read-only, kilka pytań, kontynuację wątku i obce/stare odpowiedzi.
- Kotlin: `RecognitionRulesTest` obejmuje kask/kolor/3M, ilość, komplet, mXX/sXX, konkretną część, jednoczęściowy, brak podwójnego rozwinięcia i aktywne/wyłączone/edytowane reguły. `AgentQuickInputTest` obejmuje tryby i istniejące modele, synchronizację reguł, błędne ID/wersje/typy i oryginał NOTE.
- Instrumentacyjne: `NotesIntegrationTest` obejmuje brak samoczynnego zapisu/stanu, nieaktualny podgląd i wielokrotne kliknięcie TASK/NOTE. CI kompiluje zestaw; wykonanie na emulatorze/telefonie jest osobnym sprawdzeniem.
- Workflow PR uruchamia testy JVM, assembleDebug, assembleDebugAndroidTest i lintDebug.

Testy używają atrap odpowiedzi Codexa; nie potwierdzają skuteczności interpretacji przez rzeczywisty model. Rzeczywiste sprawdzenie wszystkich trybów i reguł można wykonać osobno z uruchomionym `agent-service` i zalogowanym kontem ChatGPT. Usługa może działać na komputerze, VPS, w chmurze albo na opcjonalnym serwerze domowym; serwer domowy nie jest wymaganiem aplikacji ani wydania. Offline i Gemini działają niezależnie od tej usługi. Instrukcja uruchomienia i aktualizacji jest w `agent-service/README.md`. W paczce C2 nie zmieniono Room schema, wersji aplikacji ani applicationId; nie wdrażano usługi ani produkcyjnej aplikacji. Paczka D podnosi wyłącznie wersję aplikacji do 1.2.0 (69), zachowując Room 25 i identyfikator produkcyjny.
