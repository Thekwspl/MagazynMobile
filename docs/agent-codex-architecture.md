# Architektura agenta Codex — proof of concept

## Decyzja i stan wsparcia

Oficjalna dokumentacja OpenAI potwierdza dwa właściwe interfejsy: TypeScript SDK do uruchamiania i wznawiania lokalnych wątków oraz `codex app-server` dla własnego klienta, który obsługuje logowanie, historię, akceptacje i zdarzenia strumieniowe. W tym POC wybrano `app-server`, ponieważ wymagane jest jawne logowanie kontem ChatGPT i kontrola sesji. Wycofany `codex mcp-server` nie jest używany. Źródła: [Codex SDK](https://developers.openai.com/codex/sdk), [Codex app-server](https://developers.openai.com/codex/app-server).

`app-server` oficjalnie obsługuje zarządzany przez siebie OAuth ChatGPT, w tym logowanie przeglądarkowe i device-code (`chatgptDeviceCode`), utrzymanie oraz odświeżanie sesji. `account/read` udostępnia tryb logowania i typ planu, m.in. `plus`. Jest to ścieżka „Sign in with ChatGPT” korzystająca z dostępu subskrypcyjnego. Klucz API jest odrębną ścieżką rozliczaną według standardowych stawek API i w tym projekcie jest zabroniony. Źródła: [Authentication](https://developers.openai.com/codex/auth), [Codex app-server](https://developers.openai.com/codex/app-server).

POC nie kopiuje `auth.json`, ciasteczek ani tokenów. Logowanie rozpoczyna wyłącznie przez metodę `account/login/start`; przechowywanie i odświeżanie poświadczeń pozostaje po stronie Codex. Przy wdrożeniu należy wybrać magazyn systemowy (keyring) lub tryb ulotny, zgodnie z dokumentacją uwierzytelniania.

## Podział danych

```text
Android / backend synchronizacji
  ├─ katalog stabilny: osoby, produkty, stocznie, miejsca pracy
  │    └─ full-sync lub delta-sync z rosnącą rewizją
  └─ dane zmienne: bieżący stan, dostępność
       └─ wyłącznie odczyt na żądanie needs_data

agent-service (Node.js 18+)
  ├─ kontrakt odpowiedzi v1
  ├─ rozstrzyganie jednoznaczności i sesja POC
  ├─ allowlista narzędzi tylko do odczytu
  └─ Codex app-server przez stdio / JSON-RPC
       └─ zarządzane logowanie ChatGPT i wątki Codex
```

Transport `stdio` jest domyślnym stabilnym transportem `app-server`. Dokumentacja określa WebSocket jako eksperymentalny i niezalecany do produkcji, dlatego POC go nie używa.

## Kontrakt i przebieg sesji

Każda odpowiedź zawiera `schemaVersion: 1`, `sessionId`, intencję `ORDER` oraz dokładnie jeden status:

- `needs_data` — agent podaje wersjonowane żądanie danych tylko do odczytu; klient pobiera dane i wznawia tę samą sesję,
- `needs_user_choice` — osoba, produkt albo miejsce są niejednoznaczne lub nieznane; agent zwraca pytanie i stabilne identyfikatory kandydatów,
- `proposal` — kompletna propozycja do wyświetlenia użytkownikowi, nadal bez zapisu,
- `error` — jawny kod i komunikat błędu protokołu.

Przykład „Kowalski jutro 2 rękawice XL i okulary” najpierw rozwiązuje osobę i produkty na podstawie zsynchronizowanego katalogu, a następnie zwraca `needs_data` dla bieżących stanów. Dopiero po dostarczeniu wyników odczytu zwraca `proposal`. Dwóch Kowalskich daje `needs_user_choice`; brak produktu daje ostrzeżenie i pytanie. Żaden etap nie tworzy zamówienia i nie wydaje towaru.

Stabilne osoby, produkty, stocznie (ID, nazwa, aliasy, tagi, ID prowadzących) i miejsca trafiają do katalogu/MCP. Pięć dynamicznych odczytów wykonuje wyłącznie Android na telefonie przez `needs_data → Room → tool-results`: `get_current_stock`, `get_person_current_items`, `get_shipyard_stock`, `get_active_orders`, `get_recent_issues`. Każdy wynik jest związany z żądanym `requestId` i tą samą sesją Codexa; agent nigdy nie otrzymuje bezpośredniego połączenia do Room. Pełny i przyrostowy sync mogą zostać ponowione po okresie offline, a odrzucona rewizja bazowa wymusza naprawczy full sync.

Docelowy adapter Codex powinien uruchamiać `thread/start`, a kolejne wiadomości kierować przez `thread/resume` i `turn/start` z `outputSchema`. Oficjalny protokół przewiduje trwałe identyfikatory wątków i zdarzenie `turn/completed`, co pozwala wznowić rozmowę po dostarczeniu danych. Klient POC zawiera obsługę tych operacji i wymusza `approvalPolicy: never` oraz sandbox tylko do odczytu.

## Prywatność i bezpieczeństwo

- Do katalogu agenta trafiają tylko pola niezbędne do dopasowania; numery telefonów i inne zbędne dane są pomijane.
- Nie ma endpointu zapisu zamówienia, korekty stanu ani wydania towaru.
- Wyniki narzędzi muszą odpowiadać identyfikatorowi wcześniejszego żądania. Nieoczekiwane wywołanie kończy się `TOOL_NOT_ALLOWED`.
- `.gitignore` blokuje lokalne katalogi Codex, `auth.json`, `.env` i pliki tokenów.
- Usługa POC nasłuchuje tylko na localhost. Uwierzytelnienie transportu, szyfrowanie, retencja, audyt i trwały magazyn sesji są obowiązkowe przed integracją sieciową.

## Klient Android — Paczka D

Wersja debug wysyła pełny katalog przed każdą wiadomością, wykonuje żądane odczyty dynamiczne z Room i odsyła wyniki opatrzone `requestId`. Wybór niejednoznacznego kandydata przechodzi przez `/v1/sessions/{sessionId}/choice` w tym samym wątku. `proposal` jest adaptowany do istniejącego ekranu review, bez zapisu do momentu jawnego przycisku tworzącego szkic. Lokalny HTTP jest dozwolony wyłącznie w debug na `127.0.0.1` z `adb reverse`; release nie ma domyślnego endpointu.

## Transport i uwierzytelnienie — Paczka E

Publiczne `/v1/*` wymagają losowego tokenu klienta Bearer w `AGENT_CLIENT_TOKEN`; po stronie Androida token jest szyfrowany przez istniejący Android Keystore i pozostaje poza Auto Backup i kopią `.magazynbackup`. Codex app-server przechowuje swoje osobne logowanie ChatGPT wyłącznie na serwerze. Sekret MCP nie jest tokenem Androida. Anonimowe `/health` zwraca tylko status, a proxy wystawia wyłącznie `/v1/*` po poprawnym TLS. Serwer Node nadal domyślnie nasłuchuje tylko na `127.0.0.1`. Nie włączamy publicznego VPS ani domeny w tej paczce; przykład Caddy i generowanie tokenu znajdują się w `agent-service/README.md`.

## Room 25 i historyczne stocznie

Nowe zamówienia oraz ruchy stoczni zapisują stabilne `shipyardId`. Migracja 24→25 dodaje również aliasy/tagi stoczni. Dawne rekordy otrzymują ID tylko po dokładnej i jednoznacznej nazwie; inne pozostają bez ID i są dostępne do ręcznego przypisania w istniejących ekranach zamówień/historii. Resolver używa nazwy, aliasu, tagu i prowadzącego jako sygnałów, ale nigdy nie uznaje samego prowadzącego za dowód. Odczyty agenta pomijają niejednoznaczne dane historyczne. Propozycja nadal nie zapisuje magazynu, a ostrzeżenie o ponownym wydaniu nie jest blokadą.

## Ograniczenia i dalsze kroki

To nadal POC developerski: mimo uwierzytelniania Bearer i wymagania HTTPS dla zdalnego Androida brak trwałego magazynu sesji wymaga dalszej pracy przed produkcją. Samodzielne pytania informacyjne nie należą do intentu ORDER w protokole v1.

Istniejący parser offline i integracja Gemini pozostają bez zmian. Schemat Room ma wersję 25.
