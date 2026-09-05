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
2. Wyślij zmiany do GitHub.
3. Utwórz i wyślij tag zgodny z wersją, np. `v0.9.9`.
4. Workflow uruchomi testy, zbuduje podpisane `MagazynMobile.apk` i doda je do GitHub Releases.
5. W telefonie wybierz `Menu → Aktualizacje → Sprawdź aktualizacje → Pobierz → Zainstaluj`.

## Ważne ograniczenia

- Bez roota lub firmowego zarządzania urządzeniem Android zawsze pokaże systemowy ekran potwierdzenia instalacji. Aplikacja nie może legalnie ominąć tego kroku.
- Najprostszy darmowy wariant korzysta z publicznego repozytorium. Prywatne repozytorium wymagałoby przechowywania tokenu GitHub w telefonie, czego ten projekt celowo nie robi.
- Klucza `.jks` i jego haseł nie wolno dodawać do kodu ani archiwum projektu. Są przechowywane jako zaszyfrowane sekrety GitHub Actions.
- Przed pierwszą aktualizacją z GitHub wykonaj kopię `.magazynbackup` i sprawdź instalację na obecnej bazie bez odinstalowywania aplikacji.
