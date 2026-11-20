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
| `scout.search.topics` | – | Suchthemen (Liste) |
| `scout.search.providers` | – | `mistral`, `gemini` oder selbst hinzugefügte IDs |
| `scout.search.results-per-provider` / `language` | – | max. Treffer pro Anbieter / Quellensprache |
| `scout.mistral.api-key` | `MISTRAL_API_KEY` | Mistral-API-Key (Pflicht für Mistral-Suche/Bewertung) |
| `scout.gemini.api-key` | `GEMINI_API_KEY` | Google-Gemini-API-Key |
| `scout.evaluation.provider` | – | `mistral` oder `gemini` |
| `scout.evaluation.min-score` / `max-items-per-run` | – | Veröffentlichung erst ab Score / max. Posts pro Lauf |
| `scout.portal.url` | `PORTAL_API_URL` | REST-Endpunkt des Portals |
| `scout.portal.method` | – | HTTP-Methode (Standard `POST`) |
| `scout.portal.auth-header-name` / `auth-token` | `PORTAL_API_TOKEN` | z. B. `Authorization` + Wert `Bearer <token>` (Prefix je nach Portal im Token mit angeben) |
| `scout.portal.headers` | – | beliebige zusätzliche Header (`Key: Value`) |
| `scout.schedule.cron` / `run-on-startup` | – | Standard: alle 6 Stunden + einmal beim Start |
| `scout.store.path` | – | JSON-Datei der bereits veröffentlichten Einträge |

### Portal-Payload

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

# Start (API-Keys als Env-Variablen)
MISTRAL_API_KEY=... GEMINI_API_KEY=... \
PORTAL_API_URL=https://portal.example/api/entries \
PORTAL_API_TOKEN="Bearer geheim" \
java -jar target/source-scout-0.1.0.jar
```

Raspberry Pi (Pi 3 oder neuer, 64-bit OS empfohlen):

```bash
sudo apt install -y openjdk-17-jdk-headless maven
git clone <dein-repo> /opt/source-scout && cd /opt/source-scout
mvn -B -DskipTests package
```

### Als Dienst (systemd)

Vorlage: `deploy/source-scout.service`. Secrets gehören in eine separate Datei, die nur vom Dienst-User lesbar ist:

```ini
# /opt/source-scout/.env   (chmod 600)
MISTRAL_API_KEY=...
GEMINI_API_KEY=...
PORTAL_API_URL=https://portal.example/api/entries
PORTAL_API_TOKEN=Bearer geheim
```

```bash
sudo cp deploy/source-scout.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now source-scout
journalctl -u source-scout -f
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
