# AGENTS.md — MagazynMobile

## Cel

Te instrukcje obowiązują przy pracy Codexa nad repozytorium MagazynMobile.
Priorytety: bezpieczeństwo danych użytkownika, mały zakres zmian, szybka praca i wykorzystanie GitHub Actions jako głównego gate'u jakości.

## Sposób pracy

- Najpierw przeczytaj aktualny kod związany bezpośrednio z zadaniem. Nie zakładaj nazw klas, DAO, ekranów ani funkcji.
- Wprowadzaj najmniejszą zmianę, która poprawnie realizuje zadanie. Nie wykonuj dużych refaktorów ani zmian poza zakresem bez wyraźnej potrzeby.
- Nie zmieniaj istniejącego zachowania niezwiązanego z zadaniem.
- Nie generuj ZIP-ów, paczek z plikami ani APK jako sposobu przekazania zmian.
- Każde zadanie wykonuj na osobnym branchu utworzonym z aktualnego `main`, chyba że prompt wskazuje istniejący branch do kontynuacji.
- Po zakończeniu zacommituj wszystkie wymagane pliki, wypchnij branch do GitHub i otwórz Pull Request do `main`.
- Nigdy nie pushuj zmian bezpośrednio do `main`.
- Nigdy samodzielnie nie merge'uj Pull Requesta.
- Przy poprawce do istniejącego PR kontynuuj pracę na jego branchu zamiast tworzyć kolejny PR, chyba że prompt mówi inaczej.

## Testy i oszczędzanie czasu

- Lokalnie uruchamiaj przede wszystkim testy bezpośrednio związane ze zmienianym kodem.
- Nie powtarzaj całego zestawu testów, lintów, emulatorów i release buildów tylko po to, aby dublować GitHub Actions, chyba że prompt wyraźnie tego wymaga albo jest to potrzebne do zdiagnozowania błędu.
- Pełne kontrole repozytorium pozostaw GitHub Actions.
- Dla zmian bazy danych, migracji, importu/eksportu, backupu/restore oraz operacji zmieniających stan magazynu uruchom odpowiednie dostępne testy regresyjne/integracyjne związane z daną zmianą.
- Jeśli test nie może zostać uruchomiony, nie przedstawiaj go jako zaliczonego. W raporcie podaj krótko przyczynę.

## Room i dane

- Nie używaj destructive migration.
- Nie zwiększaj `DATABASE_SCHEMA_VERSION`, jeśli struktura Room faktycznie się nie zmienia.
- Jeśli struktura bazy musi się zmienić, zwiększ wersję schematu dokładnie zgodnie z wymaganiami zadania, dodaj poprawną migrację i test migracji.
- Nie twórz ręcznie fikcyjnego Room schema JSON. Pozwól Room/KSP wygenerować prawdziwy schema; repozytorium ma automatyzację obsługi nowego pliku schema.
- Zachowuj kompatybilność z istniejącymi danymi użytkownika.
- Ujemny stan magazynowy jest dozwolony.
- Ostrzeżenie o zbyt szybkim ponownym wydaniu jest ostrzeżeniem, a nie twardą blokadą.

## Zakres i istniejące reguły

- Nie usuwaj ani nie upraszczaj istniejących zabezpieczeń danych tylko po to, aby test przeszedł.
- Nie dodawaj automatycznego scalania osób po numerze telefonu.
- Ręcznie zapisanych danych użytkownika nie wolno usuwać wskutek synchronizacji HRappka, jeśli wymagania zadania nie mówią tego jednoznacznie.
- AI i rozpoznawanie Offline pozostają osobnymi mechanizmami.
- Jeśli wymaganie jest niejednoznaczne i wybór może spowodować utratę danych lub zmianę ważnej reguły biznesowej, zatrzymaj się i opisz problem zamiast zgadywać.

## Wersjonowanie

- Numeru `versionName`, `versionCode` ani Room schema nie wymyślaj samodzielnie.
- Użyj numerów podanych w bieżącym zadaniu. Jeśli prompt ich nie podaje i zmiana ich wymaga, zgłoś to zamiast zgadywać.

## Raport końcowy

Raport ma być krótki. Podaj tylko:

1. link/numer Pull Requesta i nazwę brancha,
2. najważniejsze wykonane zmiany,
3. testy faktycznie uruchomione lokalnie i ich wynik,
4. informację, czy zmieniono Room schema / wersję aplikacji,
5. znane ryzyka lub element wymagający ręcznego sprawdzenia.

Nie kopiuj dużych diffów ani całych logów do raportu, jeśli są dostępne na GitHubie.
