# MagazynMobile: pierwsza konfiguracja aktualizatora

Automatyzacja jest przygotowana w repozytorium, **nie jest zainstalowana ani aktywna na VM**. Nie uruchamiaj pierwszego wdrożenia bez osobnej zgody operatora. Istniejąca maszyna `magazynmobile-agent`, Debian 12, `us-central1-a`, domena `magazynmobile-agent.duckdns.org` pozostaje bez zmian. Poniższe nowe ścieżki aktualizatora są propozycją do zatwierdzenia; istniejących ścieżek i nazwy usługi nie znamy.

## 1. Diagnostyka — tylko odczyt

Na VM użyj kopii skryptów z przejrzanego PR/zatwierdzonego commita. Nie wykonuj `git pull` w katalogu działającego agenta. Skrypt nie pokazuje Environment, ExecStart argv, poświadczeń Codexa ani journala, które mogą zawierać sekrety.

```bash
python3 agent-service/deploy/diagnose.py --domain magazynmobile-agent.duckdns.org
```

Z listy jednostek wybierz rzeczywistą usługę. Jeżeli agent nie jest zarządzany przez systemd, zatrzymaj konfigurację i uzgodnij przejście osobno.

```bash
export SERVICE='WYMAGA_WERYFIKACJI.service'
python3 agent-service/deploy/diagnose.py --service "$SERVICE" --domain magazynmobile-agent.duckdns.org
```

Ustal i potwierdź: User/Group, WorkingDirectory, absolutny Node, rzeczywisty plik EnvironmentFile, PORT, entrypoint, HOME/CODEX_HOME, istniejący sposób uruchamiania i reverse proxy. Skrypt wykrywa executable, ale nie ujawnia argumentów; administrator musi prywatnie sprawdzić, czy ExecStart jest bezpośrednim `node .../dist/src/server.js`, bez dodatkowego wrappera/zmiany HOME. Nie publikuj pełnej jednostki ani pliku env. Wartość tokenu nie jest potrzebna w raporcie.

```bash
systemctl is-enabled "$SERVICE"
ss -ltn
```

Diagnostyka DNS/TLS używa zaufanych CA, SNI i sprawdzania hostname. Podaje ważność certyfikatu i odpowiedź HTTPS. Brak HTTP/TLS należy naprawić osobno po rozpoznaniu nginx/caddy/innego proxy. Nie używaj `curl -k`, nie wyłączaj weryfikacji w Androidzie. Skrypt nie zmienia certyfikatu, DNS, proxy, VM ani jej harmonogramu.

## 2. Przygotowanie — po zatwierdzeniu ścieżek

Wypełnij wartości na podstawie etapu 1. Puste lub przykładowe wartości mają zatrzymać pracę. `BASELINE` ma pozostać nienaruszonym działającym katalogiem i zawierać `dist/src/server.js`. Nie kopiuj jego .env, loginu Codexa czy certyfikatu do katalogu wydania. Jeżeli układ jest inny, nie zgaduj: przygotuj osobny plan migracji adaptera.

```bash
export SERVICE=''       # istniejąca jednostka, np. wynik diagnozy, nie przykład
export SERVICE_USER=''  # istniejący użytkownik; pusty User systemd wymaga potwierdzenia root
export BASELINE=''      # istniejący WorkingDirectory z dist/src/server.js
export NODE=''          # absolutny executable Node >=18 z diagnozy
export ENV_FILE=''      # istniejący absolutny EnvironmentFile z AGENT_CLIENT_TOKEN i AGENT_MODE=codex
export LOCAL_URL=''     # http://127.0.0.1:PORT — rzeczywisty port z diagnozy
: "${SERVICE:?}" "${SERVICE_USER:?}" "${BASELINE:?}" "${NODE:?}" "${ENV_FILE:?}" "${LOCAL_URL:?}"
test -f "$BASELINE/dist/src/server.js"
```

Proponowane nowe katalogi (po zatwierdzeniu operatora): kod narzędzia `/opt/magazyn-agent-updater`, konfiguracja `/etc/magazyn-agent-updater`, wydania `/opt/magazyn-agent-runtime`. Nie wybieraj katalogu wewnątrz BASELINE. Istniejący EnvironmentFile i login pozostają na miejscu.

```bash
sudo install -d -m 755 /opt/magazyn-agent-updater /etc/magazyn-agent-updater /opt/magazyn-agent-runtime
sudo install -m 644 agent-service/deploy/{updater.py,render_systemd.py,diagnose.py} /opt/magazyn-agent-updater/
sudo env SERVICE="$SERVICE" BASELINE="$BASELINE" NODE="$NODE" ENV_FILE="$ENV_FILE" LOCAL_URL="$LOCAL_URL" SERVICE_USER="$SERVICE_USER" python3 - <<'PY'
import json,os,pwd,secrets
from pathlib import Path
cfg=Path('/etc/magazyn-agent-updater/config.json')
token=Path('/etc/magazyn-agent-updater/update-token')
# Nigdy nie zastępuj konfiguracji ani tokenu przy ponownym wykonaniu.
if cfg.exists() or token.exists(): raise SystemExit('Już przygotowano: sprawdź istniejące pliki zamiast je zastępować')
owner=pwd.getpwnam(os.environ['SERVICE_USER'])
with token.open('x') as f: f.write(secrets.token_urlsafe(48)+'\n')
token.chmod(0o600); os.chown(token,owner.pw_uid,owner.pw_gid)
value=dict(root='/opt/magazyn-agent-runtime',current='/opt/magazyn-agent-runtime/current',
 baseline=os.environ['BASELINE'],node=os.environ['NODE'],service=os.environ['SERVICE'],
 environment_file=os.environ['ENV_FILE'],local_url=os.environ['LOCAL_URL'],
 update_token_file=str(token),repository='Thekwspl/MagazynMobile',service_adapter_confirmed=False)
with cfg.open('x') as f: json.dump(value,f,indent=2)
cfg.chmod(0o600)
current=Path(value['current'])
if current.exists() or current.is_symlink(): raise SystemExit('Wskaźnik już istnieje: sprawdź ręcznie')
current.symlink_to(Path(value['baseline']).resolve())
PY
sudo python3 /opt/magazyn-agent-updater/render_systemd.py \
 --config /etc/magazyn-agent-updater/config.json --output /tmp/mm-update-units \
 --updater-path /opt/magazyn-agent-updater/updater.py --interval-minutes 30
```

Nie włączaj jeszcze timerów ani nie instaluj drop-in. Przejrzyj wygenerowane pliki; adapter zmienia wyłącznie WorkingDirectory i ExecStart oraz dodaje ścieżkę osobnego lokalnego tokenu aktualizatora. User, Group, HOME/CODEX_HOME, EnvironmentFile, PORT, PATH, Restart, reverse proxy i TLS muszą pozostać takie jak w istniejącej jednostce. Dotychczasowy token klienta nie jest zapisywany ani zmieniany. Rozdzielenie konfiguracji jest wymaganiem pierwszego wdrożenia, nie automatycznym zgadywaniem przez aktualizator.

## 3. Weryfikacja bez wdrażania

Po opublikowaniu kompletnego v1.2.2 z Release Gate:

```bash
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json --status
```

Bez `--apply` pakiet jest pobrany do katalogu tymczasowego, zweryfikowany i usunięty. Usługa ani wskaźnik nie są zmieniane. Do czasu publikacji pakietu poprzednie wydanie APK-only daje `DEFERRED / NO_COMPATIBLE_RELEASE`. Manifest wskazuje tag aplikacji, niezależne packageVersion 0.1.0, commit, protokół 2, Node/Linux/x64 i hash. Weryfikowane są opublikowany stabilny Release, commit taga, SHA-256 archiwum, komplet plików i ich hashe; odrzucane są symlinki, traversal, brak plików i obcy protokół. Integralność korzysta z zaufania do konta/repozytorium GitHub i TLS — nie zastępuje kontroli uprawnień publikacji.

## 4. Pierwsze wdrożenie — osobna decyzja użytkownika

Przerwa jest możliwa. Pierwsze przejście ze starego agenta bez API aktualizacji wymaga braku aktywnych analiz uzgodnionego z użytkownikiem; administrator świadomie akceptuje utratę sesji przez `--bootstrap --allow-session-loss`. Nie deklarujemy bezprzerwowego wdrożenia.

```bash
sudo install -d -m 755 "/etc/systemd/system/$SERVICE.d"
test ! -e "/etc/systemd/system/$SERVICE.d/50-magazyn-update.conf"
sudo install -m 644 /tmp/mm-update-units/50-magazyn-update.conf "/etc/systemd/system/$SERVICE.d/50-magazyn-update.conf"
sudo systemctl daemon-reload
sudo python3 - <<'PY'
import json
from pathlib import Path
p=Path('/etc/magazyn-agent-updater/config.json'); data=json.loads(p.read_text())
data['service_adapter_confirmed']=True; p.write_text(json.dumps(data,indent=2)); p.chmod(0o600)
PY
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json --apply --bootstrap --allow-session-loss
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json --status
systemctl is-active "$SERVICE"
```

Kod wybiera nowy katalog atomowo, restartuje tę samą usługę i sprawdza proces, /health, uwierzytelnione /v1/runtime, tryb codex, commit i protokół 2. Nie tworzy nowych zasobów GCP ani nie dotyka harmonogramu start/stop. Nie instaluje zależności ani TypeScript na VM.

Osobno wykonaj rzeczywisty test Codexa w aplikacji (ORDER i TASK, pytania i kontynuacja) oraz prywatnie sprawdź `/v1/auth/status`. Nie pokazuj całego accountRead w raporcie. /health i testy z atrapą nie potwierdzają działania modelu ani loginu. Brak połączenia z ChatGPT nie jest sygnałem do niekontrolowanych restartów/rollbacków; health aktualizatora sprawdza uruchomienie i zgodność kontraktu.

## 5. Włączenie automatyzacji — po zweryfikowanym pierwszym wdrożeniu

```bash
sudo install -m 644 /tmp/mm-update-units/magazyn-agent-update.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now magazyn-agent-update.timer
systemctl list-timers magazyn-agent-update.timer
# ręczne sprawdzenie/aktualizacja bez zgody na utratę sesji
sudo systemctl start magazyn-agent-update.service
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json --status
# wyłączenie przyszłych automatycznych aktualizacji, bez zatrzymania agenta
sudo systemctl disable --now magazyn-agent-update.timer
```

Częstotliwość zmień przez ponowne renderowanie timerów z `--interval-minutes`, przejrzenie i instalację wyłącznie timera. OnBootSec i OnUnitInactiveSec działają względem rzeczywistego uruchomienia systemu, niezależnie od godzin pracy VM. Wyłączona VM nie wykonuje niczego i nie jest uruchamiana przez updater. Po restarcie sprawdza oczekujące wydanie.

Aktualizator odracza przełączenie przy aktywnych żądaniach albo sesjach needs_data/needs_user_choice. Tryb drain blokuje nowe analizy, ale pozwala dokończyć istniejące. Zawieszona/niedokończona sesja może odraczać aktualizację; nie zakładamy bezczynności na podstawie wieku sesji. Ręczne `--allow-session-loss` wymaga decyzji administratora i uprzedzenia użytkownika.

## Odzyskiwanie

Błąd procesu/health automatycznie przywraca poprzedni katalog i usługę. Przy rollbacku do pierwotnego BASELINE bez metadanych można sprawdzić tylko aktywny proces i HTTP /health; nowy pakiet wymaga także zgodności runtime/tag/commit/protokołu. Journal transakcji zapisuje fazę przed zmianą wskaźnika. Sam powrót wskaźnika na starą ścieżkę nie dowodzi, że restart rollbacku się udał: przerwane odzyskiwanie ponawia restart i health. Journal pozwala wykryć przerwanie; przed przełączeniem ruch jest wznowiony bez restartu, a po przełączeniu odzyskiwanie respektuje aktywne sesje. Jeśli nowe API nie uruchomiło się po przerwaniu transakcji, automatyczny rollback jest dopuszczony dopiero po potwierdzeniu przez systemd stanu inactive/failed i MainPID=0; samo niedostępne HTTP nie oznacza bezczynności. Poprzednie wydania i pierwotny BASELINE nie są automatycznie usuwane. `--status` porównuje metadane katalogu z uwierzytelnionym `/v1/runtime` działającego procesu (starszy agent może nie udostępniać diagnostyki). `previous.json` zawiera poprzednią ścieżkę, `last-result.json` etap i status bez sekretów. Po udanym rollbacku błędny hash jest odraczany na kolejnych tickach timera, aby nie restartować wciąż tej samej wadliwej wersji. Po diagnozie administrator może jawnie ponowić próbę przez `--apply --retry-failed`. W przypadku podwójnego błędu aktualizacja pozostawia journal i FAILED — wymagana jest diagnostyka administratora.

```bash
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json --status
sudo python3 /opt/magazyn-agent-updater/updater.py --config /etc/magazyn-agent-updater/config.json --apply
```

Przed ręcznym odtworzeniem starego adaptera zatrzymaj timer i uzgodnij zakończenie sesji. Wskaźnika ani kodu nie zmieniaj podczas analizy. Kopia pierwotnej jednostki/drop-in jest częścią administratorowej weryfikacji etapu 2; narzędzie nie ujawnia ani nie kopiuje sekretów z jednostki.

## Kompatybilność i testy

Kontrakt AgentResponse pozostaje v2. Android 1.2.1 i 1.2.2 współpracują z agentem v2 przed i po aktualizacji; nowe endpointy runtime/update są dodatkiem, nie zmieniają JSON propozycji. Android 1.2.2 działa z wcześniejszym agentem v2; autoaktualizacja wcześniejszego serwera bez endpointów jest odroczona do pierwszej kontrolowanej konfiguracji. Serwer v1 wymaga przejścia na v2; nie osłabiono walidacji.

PR/Release Gate sprawdza Androida i API 26/35, agent-service, testy izolowanego updatera/publikacji, sumy kontrolne oraz faktycznie uruchomiony HTTP serwer z gotowego pakietu. Testy te nie są wdrożeniem Google Cloud ani testem prawdziwego modelu.
