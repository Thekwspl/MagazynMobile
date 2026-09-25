# MagazynMobile agent-service POC

Oddzielna usługa agenta zamówień. Nie jest podłączona do APK i nie zapisuje danych magazynowych.

## Uruchomienie

Wymagania: Node.js 24 LTS, `codex` CLI w `PATH`, konto ChatGPT zalogowane przez oficjalny Codex app-server. W katalogu `agent-service`:

```sh
npm ci
npm test
npm run build
AGENT_MODE=codex npm start
```

`AGENT_MODE=local npm start` zachowuje deterministyczny lokalny resolver, także bez konta ChatGPT. Domyślny tryb POC to `local`. Usługa nasłuchuje na `127.0.0.1:8787`. Nie przekazuj jej portu na publiczną sieć: endpoint synchronizacji katalogu nie ma autoryzacji klienta (integracja APK jest późniejszym etapem).

Sprawdź `GET /v1/auth/status`. Konto musi mieć `account.type = "chatgpt"`; logowanie przez klucz API jest odrzucane przez ścieżkę agenta. `POST /v1/auth/chatgpt/start` rozpoczyna oficjalne logowanie w przeglądarce; `POST /v1/auth/chatgpt/device-code` zwraca `verificationUrl` i `userCode`, które trzeba zatwierdzić poza usługą. Żadnych kluczy `OPENAI_API_KEY` ani danych logowania nie zapisujemy.

`PUT /v1/catalog/full-sync` przyjmuje stabilny katalog (`revision`, `people`, `products`, `shipyards`, `taskPlaces`). `POST /v1/sessions/message` przyjmuje `{ "message": "..." }`. Jeśli odpowiedź ma `status: "needs_data"`, klient odczytuje rzeczywisty stan z telefonu i wysyła `POST /v1/sessions/{sessionId}/tool-results` z `{ "results": [{ "requestId": "...", "tool": "get_current_stock", "data": { "stocks": [{ "productId": "...", "available": 10 }] } }] }`. Wynik `proposal` wymaga późniejszego zatwierdzenia w aplikacji.

## Prawdziwy przepływ Codex

W trybie `codex` endpoint wiadomości wywołuje app-server przez stdio: `account/read` → `thread/start` → `turn/start` z instrukcjami agenta i `outputSchema`. Następnie zapisuje mapowanie publicznego `sessionId` na prywatny `threadId` w pamięci. Po `needs_data` klient przesyła tylko żądane wyniki, a usługa wykonuje `thread/resume` i następny `turn/start` w tym samym wątku. Restart usługi usuwa mapowanie; później można je przenieść do trwałego storage.

Katalog pozostaje w `CatalogStore`. Oficjalnie wspierany MCP przez stdio udostępnia wyłącznie `search_people`, `search_products`, `search_shipyards`, `search_task_places`. Serwer MCP dostaje odpowiedź na zapytanie przez chroniony losowym tokenem wewnętrzny odczyt z `CatalogStore` na loopback; limit wynosi 20 wyników. Cały katalog nie trafia do promptu. Codex CLI uruchamia MCP z ustawieniami `-c mcp_servers.warehouse_catalog...`, a `required=true` blokuje turn, gdy narzędzie nie wystartuje. Dynamic Tools są eksperymentalne, więc tu nie są użyte. Dokumentacja: [Codex app-server](https://developers.openai.com/codex/app-server) i [Codex MCP](https://developers.openai.com/codex/mcp).

Dane bieżące (stan, posiadane rzeczy, historia, zamówienia, stany stoczni) nie są częścią katalogu. Jedynym odczytem wykonywanym przez klienta jest `get_current_stock`. Serwer MCP nie ma narzędzi zapisu, a odpowiedź modelu jest walidowana wraz z identyfikatorami katalogu i wynikami odczytu. Wbudowane narzędzia Codexa działają w sandbox `readOnly` z `approvalPolicy=never`; katalog nie jest plikiem w katalogu roboczym Codexa.

## Ręczny smoke test

```sh
npm run smoke:codex
```

Skrypt nie jest częścią CI. Uruchamia app-server, sprawdza konto ChatGPT, synchronizuje mały fixture, wysyła `Kowalski jutro 2 rękawice XL i okulary`, wymaga wykonanego wywołania MCP w rzeczywistym turnie, a następnie sprawdza `needs_data → tool-results → proposal`. Jeśli nie jesteś zalogowany, użyj `codex login --device-auth` albo wywołaj endpoint device-code przy uruchomionej usłudze i wpisz kod pod wskazanym adresem. Smoke test wymaga wyłącznie logowania ChatGPT, bez `OPENAI_API_KEY`.

Testy `npm test` używają atrap app-server i nie wymagają loginu, sekretów ani modelu. Android pozostaje bez zmian. Więcej o kontrakcie: `../docs/agent-codex-architecture.md`.
