# MagazynMobile agent-service POC

Izolowany fundament usługi dla przyszłego agenta zamówień. Nie jest połączony z aplikacją Android i nie wykonuje żadnych zapisów magazynowych.

## Uruchomienie lokalne

Wymagania: Node.js 18+ oraz aktualne Codex CLI dostępne w `PATH` (tylko dla endpointów logowania i przyszłego wywołania modelu).

```text
npm install
npm test
npm run build
npm start
```

Serwis nasłuchuje wyłącznie na `127.0.0.1:8787`. Przykładowy scenariusz testowy używa katalogu z fixture'ów i nie wymaga danych użytkownika ani logowania.

## Granice POC

- katalog stabilny jest przekazywany przez pełną lub przyrostową synchronizację,
- dane zmienne są pobierane wyłącznie przez jawne żądanie `needs_data`,
- jedynym dozwolonym narzędziem POC jest odczyt `get_current_stock`,
- wynik `proposal` jest propozycją i nie zapisuje ani nie wydaje towaru,
- nie są przechowywane telefony, tokeny, pliki `auth.json` ani klucze API,
- integracja z Androidem, trwałe sesje i produkcyjne wdrożenie pozostają poza zakresem.

Szczegóły: `../docs/agent-codex-architecture.md`.
