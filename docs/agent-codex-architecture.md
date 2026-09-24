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

Kontrakt przewiduje docelowe odczyty `search_people`, `search_products`, `search_shipyards`, `get_current_stock`, `get_person_current_items`, `get_recent_issues`, `get_shipyard_stock` i `get_active_orders`. W wykonywalnym POC allowlista obejmuje wyłącznie potrzebne w scenariuszu `get_current_stock`; pozostałe odczyty wymagają osobnego dodania wraz z walidacją. Pełny i przyrostowy sync mogą zostać ponowione po okresie offline, a odrzucona rewizja bazowa wymusza naprawczy full sync.

Docelowy adapter Codex powinien uruchamiać `thread/start`, a kolejne wiadomości kierować przez `thread/resume` i `turn/start` z `outputSchema`. Oficjalny protokół przewiduje trwałe identyfikatory wątków i zdarzenie `turn/completed`, co pozwala wznowić rozmowę po dostarczeniu danych. Klient POC zawiera obsługę tych operacji i wymusza `approvalPolicy: never` oraz sandbox tylko do odczytu.

## Prywatność i bezpieczeństwo

- Do katalogu agenta trafiają tylko pola niezbędne do dopasowania; numery telefonów i inne zbędne dane są pomijane.
- Nie ma endpointu zapisu zamówienia, korekty stanu ani wydania towaru.
- Wyniki narzędzi muszą odpowiadać identyfikatorowi wcześniejszego żądania. Nieoczekiwane wywołanie kończy się `TOOL_NOT_ALLOWED`.
- `.gitignore` blokuje lokalne katalogi Codex, `auth.json`, `.env` i pliki tokenów.
- Usługa POC nasłuchuje tylko na localhost. Uwierzytelnienie transportu, szyfrowanie, retencja, audyt i trwały magazyn sesji są obowiązkowe przed integracją sieciową.

## Ograniczenia i dalsze kroki

To fundament, nie funkcja produkcyjna. Fixture'owy resolver zapewnia powtarzalne testy bez danych użytkownika; klient `app-server` pokazuje wspieraną ścieżkę autoryzacji i ustrukturyzowanego wyniku, ale nie jest jeszcze podłączony do Androida. Przed produkcją trzeba przeprowadzić przegląd bezpieczeństwa, polityk organizacji, warunków i licencji właściwych dla wdrażanej wersji komponentów OpenAI, dodać uwierzytelnienie samej usługi, trwałe i szyfrowane sesje, limity, obserwowalność oraz testy integracyjne na osobnym koncie testowym.

Istniejący parser offline i integracja Gemini pozostają bez zmian. Schemat Room pozostaje w wersji 24.
