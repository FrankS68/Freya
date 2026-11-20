# Source Scout

Headless Java/Spring-Boot-Dienst (kein Web-UI, läuft z. B. auf einem Raspberry Pi), der:

1. **Sucht**: recherchiert per LLM-Websuche (Mistral `web_search` über die Conversations-API und/oder Gemini `google_search`-Grounding) Quellen zu konfigurierten Themen im Internet.
2. **Bewertet**: lässt ein LLM jede Quelle auf Relevanz und Qualität bewerten (Score 0–100, Zusammenfassung, Begründung).
3. **Veröffentlicht**: schickt die besten Treffer per REST-API in dein Portal (konfigurierbarer Endpunkt, Methode, Auth-Header).

Duplikate werden über normalisierte URLs erkannt (Tracking-Parameter, Anker, `www.` werden entfernt) und laufübergreifend in einer JSON-Datei gemerkt — so wird nichts doppelt gepostet.

## Architektur

```
Scheduler (cron / ApplicationReady)
   └─ ScoutService
        ├─ SearchProvider (mistral, gemini)     → List<SearchHit>
        ├─ UrlNormalizer + ProcessedItemStore   → Dubletten-Filter
        ├─ EvaluationService (LlmClient)        → List<EvaluatedItem> (Score)
        └─ PortalPublisher (RestClient)         → POST zum Portal + Store-Markierung
```

Neue Suchanbieter oder LLM-Clients: einfach `SearchProvider` bzw. `LlmClient` als `@Component` implementieren — sie werden automatisch registriert und über `scout.search.providers` bzw. `scout.evaluation.provider` angesteuert.

## Konfiguration (`src/main/resources/application.yml`, überschreibbar per Umgebungsvariablen)

| Schlüssel | Env-Var | Bedeutung |
|---|---|---|
| `scout.search.topics` | – | Suchthemen (Liste) — nur Fallback: sobald die Portal-Doku einen Themen-Endpunkt liefert, zieht Freya die Themen jedes Mal von dort |
| `scout.search.providers` | – | `mistral`, `gemini`, `openai` oder selbst hinzugefügte IDs |
| `scout.search.strategy` | – | `rotate` (Standard): pro Lauf wird genau EIN Anbieter verwendet, abwechselnd über alle einsatzbereiten. `all`: alle Anbieter in jedem Lauf (altes Verhalten) |
| `scout.search.results-per-provider` / `language` | – | max. Treffer pro Anbieter / Quellensprache |
| `scout.mistral.api-key` | `MISTRAL_API_KEY` | Mistral-API-Key (Recherche, Bewertung und Lauf-Log) |
| `scout.gemini.api-key` | `GEMINI_API_KEY` | Google-Gemini-API-Key |
| `scout.openai.api-key` | `OPENAI_API_KEY` | OpenAI-API-Key (Suche über die Responses-API mit `web_search`) |
| `scout.evaluation.provider` | – | `mistral`, `gemini` oder `openai` |
| `scout.evaluation.min-score` / `max-items-per-run` | – | Veröffentlichung erst ab Score / max. Posts pro Lauf |
| `scout.portal.url` | `PORTAL_API_URL` | REST-Endpunkt des Portals |
| `scout.portal.docs-url` | `PORTAL_DOCS_URL` | Schnittstellen-Doku (Standard `http://spiekeroog.fritz.box:8082/api/docs`) — Freya prueft sie bei jedem Lauf und stuendlich per Hash auf Aenderungen |
| `scout.portal.docs-cron` | – | Cron für die Doku-Prüfung (stündlich) |
| `scout.portal.max-questions-per-run` | – | max. offene Portal-Fragen, die pro Lauf recherchiert werden |
| `scout.portal.method` | – | HTTP-Methode (Standard `POST`) |
| `scout.portal.auth-header-name` / `auth-token` | `PORTAL_API_TOKEN` | z. B. `Authorization` + Wert `Bearer <token>` (Prefix je nach Portal im Token mit angeben) |
| `scout.portal.headers` | – | beliebige zusätzliche Header (`Key: Value`) |
| `scout.schedule.cron` / `run-on-startup` | – | Standard: alle 6 Stunden + einmal beim Start |
| `scout.store.path` | – | JSON-Datei der bereits veröffentlichten Einträge |

### Auto-Update

Standardmäßig prüft Freya bei jedem Lauf (auch beim Start), ob es ein neueres GitHub-Release gibt (`scout.update.repo`, abgefragt über `/releases/latest`). Ist die dort angehängte `freya.jar` neuer als die laufende Version, wird sie nach `/opt/freya/freya.jar` geladen, atomar ersetzt und der Dienst beendet sich selbst — systemd (`Restart=always`) startet ihn direkt mit der neuen Version. Deaktivieren mit `scout.update.enabled: false`. Bedingung: das laufende JAR muss unter `/opt/freya/` liegen und über das Manifest die Version melden (wie beim Release-Build).

## Lauf-Log in der Mistral-Bibliothek

Nach jedem Lauf legt Freya die letzten Log-Zeilen (Standard 100, `scout.logreport.tail-lines`) als Markdown-Dokument (`freya-lauf-<Zeitstempel>.md`) in einer Mistral-Dokumentenbibliothek ab — ein Dokument pro Lauf. Genutzt wird dafür der Mistral-API-Key (`MISTRAL_API_KEY`), unabhängig davon, welcher Anbieter recherchiert hat. Die Bibliothek heißt standardmäßig `Freya-Logs` und wird beim ersten Lauf automatisch erstellt; ihre ID wird in `data/log-library.json` gespeichert (vorbelegen mit `scout.logreport.library-id` bzw. Env-Var `MISTRAL_LOG_LIBRARY_ID`). Die Bibliothek kann im Mistral-Studio (oder per API) gelesen werden; sie wächst mit jedem Lauf und sollte gelegentlich geleert werden. Deaktivieren mit `scout.logreport.enabled: false`.

## Schnittstellen-Doku und Portal-Fragen

Vorausgesetzt `scout.portal.docs-url` ist gesetzt, macht Freya bei jedem Lauf (und stuendlich per `scout.portal.docs-cron`) Folgendes:

1. **Doku holen und auf Aenderungen pruefen** (Hash-Vergleich; bei Aenderung wird dies mit `SCHNITTSTELLE GEENDERT` geloggt und die neue Fassung unter `data/portal-docs-latest.txt` abgelegt).
2. **Endpunkte selbst ermitteln**: das konfigurierte LLM analysiert die Doku und liefert den Endpunkt zum Abrufen offener Fragen und zum Einreichen von Antworten (Fallback: Heuristik über `/api/...`-Pfade mit "question/frage" bzw. "answer/antwort/result"). Das Mapping liegt in `data/portal-docs-state.json`.
3. **Fragen recherchieren**: offene Fragen holen (Felder wie `id`/`question`/`frage`/`text`/`title` werden erkannt), jede Frage wird wie ein Suchthema recherchiert und bewertet; die beste Quelle geht als JSON (`questionId`, `question`, `title`, `url`, `snippet`, `summary`, `relevance`, `reason`, `source`, `publishedAt`) an den ermittelten Antwort-Endpunkt. Jede bearbeitete Frage wird als Duplette markiert und nicht erneut bearbeitet.

## Portal-Payload

Gesendet wird JSON an `scout.portal.url`:

```json
{
  "title": "…",
  "url": "https://…",
  "snippet": "…",
  "summary": "1-2 Sätze aus der KI-Bewertung",
  "relevance": 87,
  "reason": "kurze Begründung",
  "source": "mistral|gemini",
  "publishedAt": "2025-07-01"
}
```

Wenn dein Portal ein anderes Format erwartet, passe `PortalPublisher#publish` an (oder implementiere dein eigenes Mapping dort).

## Build und Start (lokal / am Pi)

```bash
# Build + Tests
mvn -B verify

# Start (API-Keys als Env-Variablen), Ergebnis: target/freya.jar
MISTRAL_API_KEY=... GEMINI_API_KEY=... \
PORTAL_API_URL=https://portal.example/api/entries \
PORTAL_API_TOKEN="Bearer geheim" \
java -jar target/freya.jar
```

Raspberry Pi (Pi 3 oder neuer, 64-bit OS empfohlen). Der Dienst läuft unter einem eigenen, rechtearmigen Benutzer `freya` (Home `/opt/freya`):

```bash
sudo apt install -y openjdk-17-jdk-headless maven

sudo useradd --system --create-home --home-dir /opt/freya --shell /usr/sbin/nologin freya
sudo git clone https://github.com/FrankS68/Freya.git /opt/freya
sudo chown -R freya:freya /opt/freya

sudo -u freya sh -c 'cd /opt/freya && mvn -B -DskipTests package'
```

Ohne Maven auf dem Pi: Releases werden bei jedem Git-Tag (`v*`, z. B. `v0.1.0`) automatisch gebaut und als `freya.jar` an den GitHub-Release angehängt. Update am Pi:

```bash
cd /opt/freya
sudo -u freya wget -O freya.jar https://github.com/FrankS68/Freya/releases/latest/download/freya.jar
sudo systemctl restart source-scout
```

Falls der Pi selbst baut, liegt das Ergebnis ab sofort unter `target/freya.jar` — dann `sudo cp target/freya.jar /opt/freya/freya.jar && sudo systemctl restart source-scout`.
### Als Dienst (systemd)

Vorlage: `deploy/source-scout.service` (läuft als Benutzer `freya`, mit `ProtectSystem`, `ReadWritePaths` und `PrivateTmp` abgeschottet). Die Daten der Dupletten-Datei landen unter `/opt/freya/data/`.

Secrets gehören in eine separate Datei, die nur vom Benutzer `freya` lesbar ist:

```ini
# /opt/freya/.env   (chmod 600, Eigentümer freya)
MISTRAL_API_KEY=...
GEMINI_API_KEY=...
OPENAI_API_KEY=...
MISTRAL_LOG_LIBRARY_ID=
PORTAL_API_URL=https://portal.example/api/entries
PORTAL_API_TOKEN=Bearer geheim
```

Nur einen Key setzen? Kein Problem: `scout.search.strategy: rotate` rotiert automatisch nur über die Anbieter, deren Key gesetzt ist. Weitere Anbieter werden ohne Key mit einer Warnung übersprungen.

```bash
sudo touch /opt/freya/.env && sudo chown freya:freya /opt/freya/.env && sudo chmod 600 /opt/freya/.env

sudo cp deploy/source-scout.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now source-scout
journalctl -u source-scout -f
```

Anpassungen am Code oder an der Konfiguration machst du als `freya` (z. B. `sudo -u freya vi /opt/freya/src/main/resources/application.yml`), danach neu bauen und starten:

```bash
sudo -u freya sh -c 'cd /opt/freya && mvn -B -DskipTests package'
sudo systemctl restart source-scout
```

### Docker (optional, Multi-Arch inkl. ARM)

```bash
docker buildx build --platform linux/arm64,linux/arm/v7 -t <user>/source-scout:latest --push .
docker run -d --name source-scout --restart unless-stopped \
  -e MISTRAL_API_KEY=... -e GEMINI_API_KEY=... \
  -e PORTAL_API_URL=... -e PORTAL_API_TOKEN=... \
  <user>/source-scout:latest
```

## Kosten- und Betriebshinweise

- Die Suchen und Bewertungen laufen über kostenpflichtige LLM-APIs; `search.results-per-provider`, `evaluation.max-candidates` und `max-items-per-run` begrenzen die Kosten pro Lauf.
- Mistral `web_search` ist ein Konnektor der Conversations/Agents-API (nicht Chat Completions) und wird gesondert abgerechnet.
- Ohne Portal-API-URL läuft der Scout im „Trockenlauf": Treffer werden mit Score im Log ausgegeben, aber nichts gepostet.
- Die JSON-Datei der verarbeiteten Einträge ist klein; auf SD-Karten trotzdem gelegentlich sichern.

## CI

GitHub Actions (`.github/workflows/ci.yml`) baut das Projekt bei jedem Push/PR mit JDK 17 und Maven (`mvn -B verify`).
