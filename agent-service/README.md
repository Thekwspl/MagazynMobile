# MagazynMobile agent-service POC

Oddzielna usługa analizy Szybkiego Pola (ALL, ORDER, TASK, NOTE; CONTACT w ALL). Klient Android używa jej wyłącznie do rozpoznawania i nie zapisuje danych magazynowych przez HTTP.

## Uruchomienie

Wymagania: Node.js 24 LTS, `codex` CLI w `PATH`, konto ChatGPT zalogowane przez oficjalny Codex app-server. W katalogu `agent-service`:

```sh
npm ci
npm test
npm run build
export AGENT_CLIENT_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("base64url"))')"
AGENT_MODE=codex npm start
```

`AGENT_MODE=local npm start` zachowuje deterministyczny lokalny resolver do testów serwisu. Domyślny tryb POC to `local`; przycisk Codex w Androidzie wymaga `AGENT_MODE=codex` oraz zalogowanego konta ChatGPT. Usługa nasłuchuje wyłącznie na `127.0.0.1:8787`. Nie wystawiaj procesu Node bezpośrednio do sieci.

`AGENT_CLIENT_TOKEN` jest obowiązkowy przy starcie (co najmniej 43 znaki bez białych znaków). Powyższa komenda generuje 32 losowe bajty kryptograficznie, trzyma token w zmiennej środowiskowej bieżącej sesji i nie zapisuje go w repo. Skopiuj wartość bezpiecznie do Ustawienia → Połączenie Codex; nie dodawaj jej do logów, plików projektu ani GitHub Actions. Dla trwałej instalacji użyj sekretów systemu uruchomieniowego z ograniczonym dostępem. Po rotacji zmień zmienną na serwerze i uruchom usługę ponownie, a następnie wpisz nowy token w Androidzie. Poprzedni natychmiast przestaje działać. Restart przerywa sesje agenta zapisane wyłącznie w pamięci.

Każde publiczne `/v1/catalog/*`, `/v1/sessions/*` i `/v1/auth/*` wymaga `Authorization: Bearer <AGENT_CLIENT_TOKEN>`. Brak lub zły token daje 401, niedozwolona operacja po autoryzacji 403. Ochrona kosztownych żądań obejmuje limit 60/minutę na proces. `GET /health` bez tokenu zwraca wyłącznie `{ "status": "ok" }`; wewnętrzny MCP zachowuje osobny, losowy sekret i dostęp tylko przez loopback.

Sprawdź `GET /v1/auth/status`. Konto musi mieć `account.type = "chatgpt"`; logowanie przez klucz API jest odrzucane przez ścieżkę agenta. `POST /v1/auth/chatgpt/start` rozpoczyna oficjalne logowanie w przeglądarce; `POST /v1/auth/chatgpt/device-code` zwraca `verificationUrl` i `userCode`, które trzeba zatwierdzić poza usługą. Żadnych kluczy `OPENAI_API_KEY` ani danych logowania nie zapisujemy.

`PUT /v1/catalog/full-sync` przyjmuje stabilny katalog (`revision`, `people`, `products`, `shipyards`, `taskPlaces`, `recognitionRules`). `POST /v1/sessions/message` przyjmuje `{ "schemaVersion": 2, "mode": "ALL", "message": "..." }`. Jeśli odpowiedź ma `status: "needs_data"`, klient odczytuje potrzebne dane z telefonu i wysyła `POST /v1/sessions/{sessionId}/tool-results` z `{ "results": [{ "requestId": "...", "tool": "get_current_stock", "data": { "stocks": [{ "productId": "...", "available": 10 }] } }] }`. Wynik `proposal` wymaga późniejszego zatwierdzenia w aplikacji.

Jeśli agent zwróci `needs_user_choice`, Android pokazuje wszystkie strukturalne `clarifications` bieżącej rundy. Każde pytanie ma własne `id`, `type`, `required` i — dla `choice` — własnych kandydatów. Po uzupełnieniu wszystkich wymaganych odpowiedzi Android wysyła je razem przez `POST /v1/sessions/{sessionId}/answers` z `{ "answers": [...] }`. Usługa waliduje cały zestaw, wznawia ten sam wątek Codex dokładnie raz i może zwrócić następną rundę pytań. Stary `/choice` pozostaje tylko dla zgodności z pojedynczym pytaniem `choice`.

## Test developerski Androida

Uruchom usługę na komputerze w trybie `codex`, zaloguj Codex kontem ChatGPT, a następnie podłącz emulator lub telefon przez ADB i wykonaj:

```sh
adb reverse tcp:8787 tcp:8787
```

Debug APK domyślnie łączy się z `http://127.0.0.1:8787` telefonu; ADB reverse przesyła ruch do pętli lokalnej komputera. Również w debug należy zapisać token klienta w Ustawienia → Połączenie Codex. Wyjątek dla HTTP obejmuje tylko `127.0.0.1` w konfiguracji sieciowej **debug**. Release nie ma domyślnego adresu agenta i dopuszcza wyłącznie poprawny HTTPS. Test połączenia odczytuje tylko `/v1/auth/status`; nie tworzy zamówienia. Bez połączenia Offline i Gemini nadal działają niezależnie.

## Późniejszy dostęp bez ADB reverse

Docelowy model: telefon → HTTPS + Bearer → reverse proxy → `127.0.0.1:8787` → agent-service → lokalny Codex app-server. Przykładowy szkic Caddy (host `agent.example.invalid` jest **wyłącznie symbolem**, nie rzeczywistą domeną; certyfikat i DNS należy skonfigurować osobno przy wdrożeniu):

```caddyfile
agent.example.invalid {
    @api path /v1/*
    handle @api {
        reverse_proxy 127.0.0.1:8787
    }
    handle {
        respond 404
    }
}
```

Proxy ma udostępniać tylko `/v1/*`, a nie `/_internal/*` ani `/health`. Nie przekazuj do Internetu portu 8787. HTTPS musi mieć poprawny certyfikat i weryfikację nazwy hosta; Android nie ma trybu ignorowania certyfikatu. To dokumentacja architektury, nie konfiguracja serwera produkcyjnego.

Codex synchronizuje przy analizie bieżące aktywne osoby, produkty, stocznie i miejsca z lokalnych ID, bez telefonów, historii i sekretów. Rewizja rośnie także po ponowieniu. Odczyt stanu używa wyłącznie znanych rekordów `warehouse-main`, dopuszcza stany ujemne; nieznany/nieustalony stan zgłasza błąd. Propozycja trafia do istniejącego podglądu, gdzie dopiero przycisk użytkownika tworzy szkic zamówienia.

## Prawdziwy przepływ Codex

W trybie `codex` endpoint wiadomości wywołuje app-server przez stdio: `account/read` → `thread/start` → `turn/start` z instrukcjami agenta i `outputSchema`. Następnie zapisuje mapowanie publicznego `sessionId` na prywatny `threadId` w pamięci. Po `needs_data` klient przesyła tylko żądane wyniki, a usługa wykonuje `thread/resume` i następny `turn/start` w tym samym wątku. Restart usługi usuwa mapowanie; później można je przenieść do trwałego storage.

Katalog pozostaje w `CatalogStore`. Oficjalnie wspierany MCP przez stdio udostępnia wyłącznie `search_people`, `search_products`, `search_shipyards`, `search_task_places`. Serwer MCP dostaje odpowiedź na zapytanie przez chroniony losowym tokenem wewnętrzny odczyt z `CatalogStore` na loopback; limit wynosi 20 wyników. Cały katalog nie trafia do promptu. Codex CLI uruchamia MCP z ustawieniami `-c mcp_servers.warehouse_catalog...`, a `required=true` blokuje turn, gdy narzędzie nie wystartuje. Dynamic Tools są eksperymentalne, więc tu nie są użyte. Dokumentacja: [Codex app-server](https://developers.openai.com/codex/app-server) i [Codex MCP](https://developers.openai.com/codex/mcp).

Dane bieżące nie są częścią katalogu. Klient Android obsługuje pięć odczytów przez `needs_data` → lokalny Room → `tool-results` tej samej sesji: `get_current_stock({productIds})`, `get_person_current_items({personId})`, `get_shipyard_stock({shipyardId})`, `get_active_orders({recipientKind,recipientId})`, `get_recent_issues({recipientKind,recipientId,limit})`. Odbiorcą jest `person` lub `shipyard`; limit historii to 1–20, a jednej odpowiedzi `needs_data` to najwyżej cztery odczyty. Wyniki zawierają tylko ID produktu, ilość, jednostkę, odpowiednią datę/status i ID powiązania; serwer sprawdza strukturę, zakres, typ, `requestId` i zamówiony tool. Ujemny stan jest prawidłowy. Brak bezpośredniego dostępu agenta do bazy na telefonie. Serwer MCP nie ma narzędzi zapisu, a wbudowane narzędzia Codexa działają w sandbox `readOnly` z `approvalPolicy=never`.

Katalog stoczni zawiera stabilne `id`, nazwę, aliasy, tagi i ID prowadzących; nigdy telefony. Nowe zamówienia i ruchy stoczni zapisują `shipyardId`. Migracja Room 24→25 kojarzy stare wiersze tylko po dokładnej, jednoznacznej nazwie; niepewne powiązania pozostają puste. W ekranach zamówień i historii użytkownik może sprawdzić sugestię, wskazać stocznię i zapisać ID bez zmiany historycznej etykiety. Dynamiczny odczyt uwzględnia powiązane ID i tylko bezpieczne, jednoznaczne starsze nazwy. Niejednoznaczne starsze wpisy wymagają ręcznej korekty. Samodzielne pytania informacyjne wymagają w przyszłości osobnego intentu: obecny protokół v1 przygotowuje tylko proposal ORDER.

## Ręczny smoke test

```sh
npm run smoke:codex
```

Skrypt nie jest częścią CI. Uruchamia app-server, sprawdza konto ChatGPT, synchronizuje mały fixture, wysyła `Kowalski jutro 2 rękawice XL i okulary`, wymaga wykonanego wywołania MCP w rzeczywistym turnie, a następnie sprawdza `needs_data → tool-results → proposal`. Jeśli nie jesteś zalogowany, użyj `codex login --device-auth` albo wywołaj endpoint device-code przy uruchomionej usłudze i wpisz kod pod wskazanym adresem. Smoke test wymaga wyłącznie logowania ChatGPT, bez `OPENAI_API_KEY`.

Testy `npm test` używają atrap app-server i nie wymagają loginu, sekretów ani modelu. Więcej o kontrakcie: `../docs/agent-codex-architecture.md`.

## Aktualizacja do C2 / protokołu v2

Wdróż kod `agent-service` z tej samej gałęzi co Android: `npm ci`, `npm test`, `npm run build`, restart istniejącej usługi z `AGENT_MODE=codex`. Zachowaj HTTPS, token i konfigurację logowania ChatGPT. Te zmiany nie wdrażają usługi automatycznie. Endpointy nadal mają prefiks `/v1`, ale wersja kontraktu JSON to 2. Stary klient bez `schemaVersion: 2` otrzyma HTTP 409 przed rozpoczęciem analizy. Nowy Android odrzuca odpowiedź starego serwera z wersją 1. Nie ma automatycznego fallbacku do ORDER.

Każda analiza Androida synchronizuje pełny katalog i aktywne reguły z Room. `recognitionRules.instructions` pochodzi ze wspólnej reprezentacji Kotlin, `learned` zawiera tylko włączone reguły i kandydatów `targetIds` wynikających z aktualnych rekordów; nie tworzy ID. Wyłączenie/usunięcie reguły znika przy następnej synchronizacji. Zmiana rewizji unieważnia starszą sesję zamiast używać nieaktualnych reguł. Tryb `local` jest resolverem testowym wyłącznie ORDER, nie zastępuje Codexa.

TASK używa `task`, NOTE `note.text`, CONTACT `contact`; pozostałe payloady są null. Odpowiedzi TASK/NOTE/CONTACT nie wymagają stanu, odbiorcy ani pozycji zamówienia. ORDER zachowuje dotychczasowy odczyt stanu z telefonu. Każdy wynik jest propozycją do istniejącego ekranu weryfikacji, bez automatycznego zapisu. CONTACT zachowuje istniejącą weryfikację osoby i ręczne dodanie numeru do profilu; nie tworzy sam pracowników.

Testy automatyczne używają atrap Codexa. Przed użyciem na telefonie sprawdź z prawdziwym kontem: wszystkie tryby, kolor/markę kasku, komplety i jednoczęściowe ubrania, aktywną/edytowaną/wyłączoną regułę, wspólnego prowadzącego dwóch stoczni i rundę kilku pytań. `npm run smoke:codex` sprawdza istniejący przepływ ORDER, wymaga osobno skonfigurowanej usługi i logowania; nie potwierdza pozostałych scenariuszy.
