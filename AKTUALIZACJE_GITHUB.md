# Darmowe aktualizacje przez GitHub

Docelowy przepływ:

`Codex → GitHub → GitHub Actions → GitHub Release → Aktualizacje w aplikacji → instalator Androida`

## Jednorazowa konfiguracja

1. Utwórz publiczne repozytorium GitHub, np. `twoj-login/MagazynMobile`, i wgraj do niego zawartość katalogu projektu.
2. Używaj dokładnie tego samego pliku `.jks`, którym została podpisana aplikacja już zainstalowana na telefonie. Inny klucz nie zaktualizuje istniejącej instalacji.
3. W repozytorium otwórz `Settings → Secrets and variables → Actions` i dodaj:
   - `ANDROID_KEYSTORE_BASE64` — cały plik `.jks` zapisany jako Base64;
   - `ANDROID_KEYSTORE_PASSWORD` — hasło magazynu kluczy;
   - `ANDROID_KEY_ALIAS` — alias klucza;
   - `ANDROID_KEY_PASSWORD` — hasło klucza.
4. W PowerShell wartość Base64 można skopiować poleceniem:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\sciezka\klucz.jks")) | Set-Clipboard
   ```

5. W aplikacji otwórz `Menu → Aktualizacje` i wpisz `twoj-login/MagazynMobile`.
6. Przy pierwszej instalacji aktualizacji Android poprosi o zgodę na instalowanie aplikacji z tego źródła. To jednorazowa zgoda systemowa.

## Publikowanie następnej wersji

1. Zwiększ `versionCode` i `versionName` w `app/build.gradle.kts`.
2. Wyślij zmiany do gałęzi `main` w GitHub.
3. Workflow automatycznie uruchomi `test` i `assembleRelease` przy każdym pushu do `main`.
4. Jeśli testy lub build nie przejdą, tag i Release nie zostaną utworzone.
5. Jeśli build przejdzie, a Release dla `versionName` jeszcze nie istnieje, workflow sam utworzy tag `v<versionName>`, GitHub Release i dołączy podpisany `MagazynMobile.apk`.
6. Jeśli dana wersja jest już opublikowana, workflow nadal sprawdzi testy i build, ale nie utworzy duplikatu wydania.
7. W telefonie wybierz `Menu → Aktualizacje → Sprawdź aktualizacje → Pobierz → Zainstaluj`.

Nie trzeba ręcznie wykonywać `git tag`, przesuwać tagów ani uruchamiać ponownie workflow dla starego commita. Każda poprawka trafiająca do `main` jest sprawdzana jako nowy build bieżącego kodu.

## Ważne ograniczenia

- Bez roota lub firmowego zarządzania urządzeniem Android zawsze pokaże systemowy ekran potwierdzenia instalacji. Aplikacja nie może legalnie ominąć tego kroku.
- Najprostszy darmowy wariant korzysta z publicznego repozytorium. Prywatne repozytorium wymagałoby przechowywania tokenu GitHub w telefonie, czego ten projekt celowo nie robi.
- Klucza `.jks` i jego haseł nie wolno dodawać do kodu ani archiwum projektu. Są przechowywane jako zaszyfrowane sekrety GitHub Actions.
- Przed pierwszą aktualizacją z GitHub wykonaj kopię `.magazynbackup` i sprawdź instalację na obecnej bazie bez odinstalowywania aplikacji.
