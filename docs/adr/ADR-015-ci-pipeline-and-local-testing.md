# ADR-015: CI Pipeline and Local Testing

**Status:** Accepted
**Date:** 2026-09-29
**Authors:** Engineering Team

---

## Context

Phase 5's next slice per `docs/plan.md` is CI: a build/test pipeline for the six Spring Boot
services and the Angular frontend, sequenced ahead of distributed tracing so it can catch
regressions from the Kafka-header changes tracing will need.

Two constraints shaped this decision beyond "have a pipeline":

1. **It must be testable locally as well as in the cloud**, and a local green run should mean
   the same thing as a CI green run.
2. **It must stay free.**

### Facts verified before deciding
- Six standalone Maven modules under `services/`, no parent POM, no Maven wrapper; Spring
  Boot 4.1.0 / Java 25. Every service has a multi-stage `Dockerfile`.
- Five services run Testcontainers suites (`postgres:15-alpine`, `confluentinc/cp-kafka:7.5.0`);
  `api-gateway` has none.
- **Local Testcontainers runs go through Testcontainers Desktop**: `tc.host` in
  `~/.testcontainers.properties` points at its local proxy.
  - The proxy forwards either to local Docker or to Testcontainers Cloud, depending on the
    runtime chosen in the app. The properties file looks the same in both modes.
  - Testcontainers Cloud's free plan is capped at 50 min/month (`docs/plan.md`, Outstanding
    housekeeping). Whenever Cloud was selected, that cap, not compute, was the real cost
    bottleneck for local testing.
- Frontend tests are Vitest on jsdom (ADR-013) — no browser needed in CI.
- The GitHub repository is **private**. GitHub Free includes 2,000 Actions minutes/month for
  private repos; with no payment method on file, Actions stops at the quota instead of billing.
- Precedent for tooling: `seed-events.js` / `demo.js` are dependency-free Node scripts that
  replaced bash scripts so they run on Windows without Git Bash/WSL.

## Options for local testing

| # | Option | What it verifies | Cost |
|---|---|---|---|
| A | Per-service commands as before (`mvn test`, `npx ng test`) | Code | Free, but Testcontainers Cloud minutes count |
| B | One shared entry point, `ci.js`, called identically locally and by the workflow | Code, with command parity to CI | Free |
| C | `act` (nektos/act) — runs the actual workflow YAML locally in Docker | The workflow file itself (triggers, matrix, steps) | Free, multi-GB runner images |
| D | Testcontainers on local Docker Desktop instead of Testcontainers Cloud | Same as A/B | Free and unlimited |
| E | Git pre-push hook running `ci.js` for changed targets | Code, before every push | Free, slows every push |
| F | Local end-to-end smoke: `docker compose up`, `seed-events.js`, `demo.js` | Whole saga | Free (already exists, manual) |

**A** works today but lets local and CI commands drift apart. **C** gives the highest fidelity
to the YAML, but Testcontainers inside act needs the host Docker socket mounted and is fiddly
on Windows, so it can't be the default path. **E** and **F** are useful but separate concerns.

## Options for the cloud side

- **GitHub Actions** — the repo already lives on GitHub; 2,000 free minutes/month is enough
  with the measures below; Ubuntu runners have Docker, so Testcontainers works with no cloud
  service.
- **GitLab CI** (400 free min/month) and **CircleCI Free** — would mean mirroring the repo to a
  second platform for less or comparable free capacity. Rejected.

## Decision

**B + D as the standard path, C as an optional debugging tool, GitHub Actions in the cloud.**

- `ci.js` (repo root) is the single source of truth for what "CI passes" means:
  - `node ci.js <service> [--docker]` → `mvn -B -ntp verify` (+ `docker build` of the
    service's Dockerfile).
  - `node ci.js frontend` → `npm ci`, `ng test --watch=false`, `ng build`.
  - `node ci.js services` / `node ci.js all` for everything.
  - Exits non-zero and names the failed targets; warns (without blocking) when local
    Testcontainers is routed to Testcontainers Cloud.
- `.github/workflows/ci.yml` runs **exactly those commands** per job, so parity is by
  construction rather than by keeping two definitions in sync.
- Locally, Testcontainers Desktop should use the **local Docker runtime** (option D) for
  CI-like runs; Testcontainers Cloud remains available but is no longer needed.
- `act` (option C) is documented as optional, e.g. `act -j frontend`, for debugging workflow
  syntax — not required for normal work.

### Staying inside the free quota
- **Path filters** (`dorny/paths-filter`): a change under `services/booking-service/**` only
  runs booking-service's job. A change to `ci.js` or the workflow itself re-runs everything.
  `workflow_dispatch` always runs everything.
- **`concurrency` + `cancel-in-progress`**: superseded runs on the same ref are cancelled.
- **Caching**: `setup-java` Maven cache, `setup-node` npm cache.
- **No image push**: `docker build` only verifies the Dockerfiles. Pushing images would need a
  registry, and private GHCR storage is limited on the free plan (see ADR-016).
- **Surefire reports** are uploaded only on failure, with 7-day retention.
- **Testcontainers** in CI uses the runner's Docker daemon — Testcontainers Cloud is not used.
- If the repo is made public (it's a portfolio project), Actions minutes become unlimited; the
  filters remain worthwhile for fast feedback anyway.

## Consequences

**Positive**
- One command locally reproduces a CI job; no "works on my machine, fails in CI" from diverging
  command lines.
- Zero cost in both places, with the Testcontainers Cloud cap removed from the critical path.
- Every Dockerfile is now built on each relevant change — previously they were only exercised
  by a manual `docker compose up --build`.

**Negative / accepted**
- `--docker` builds each service twice (once by `mvn verify`, once inside the Dockerfile's own
  build stage). Accepted: the second build *is* what's being verified.
- Parity is on commands, not environments: the CI runner is Linux, the main dev machine is
  Windows. `act` (option C) closes that gap when it matters.

## Deferred (separate future slices)
- End-to-end smoke job in CI (`docker compose up` + `seed-events.js` + `demo.js`), manually or
  PR-triggered because of its runtime.
- Pre-push hook (option E).
- Image push to a registry and CD/deployment — see ADR-016 for the environment decision.

---

---

# ADR-015: CI-Pipeline und lokales Testen

**Status:** Akzeptiert  
**Datum:** 2026-09-29  
**Autoren:** Engineering-Team

---

## Kontext

Der nächste Slice von Phase 5 laut `docs/plan.md` ist CI: eine Build-/Test-Pipeline für die sechs
Spring-Boot-Services und das Angular-Frontend, zeitlich vor das verteilte Tracing gelegt, damit
sie Regressionen durch die Kafka-Header-Änderungen abfangen kann, die Tracing braucht.

Zwei Randbedingungen prägten diese Entscheidung über „eine Pipeline haben“ hinaus:

1. **Sie muss lokal ebenso testbar sein wie in der Cloud**, und ein lokaler grüner Lauf soll
   dasselbe bedeuten wie ein grüner CI-Lauf.
2. **Sie muss kostenlos bleiben.**

### Vor der Entscheidung geprüfte Fakten
- Sechs eigenständige Maven-Module unter `services/`, kein Parent-POM, kein Maven Wrapper;
  Spring Boot 4.1.0 / Java 25. Jeder Service hat ein mehrstufiges `Dockerfile`.
- Fünf Services führen Testcontainers-Suiten aus (`postgres:15-alpine`,
  `confluentinc/cp-kafka:7.5.0`); `api-gateway` hat keine.
- **Lokale Testcontainers-Läufe gehen über Testcontainers Desktop**: `tc.host` in
  `~/.testcontainers.properties` zeigt auf dessen lokalen Proxy.
  - Der Proxy leitet je nach in der App gewählter Laufzeitumgebung entweder an das lokale Docker
    oder an Testcontainers Cloud weiter. Die Properties-Datei sieht in beiden Modi gleich aus.
  - Der kostenlose Tarif von Testcontainers Cloud ist auf 50 Min./Monat begrenzt
    (`docs/plan.md`, Offene Haushaltsarbeiten). Immer wenn Cloud gewählt war, war diese Grenze,
    nicht die Rechenleistung, der eigentliche Kostenengpass beim lokalen Testen.
- Frontend-Tests sind Vitest auf jsdom (ADR-013) – in CI ist kein Browser nötig.
- Das GitHub-Repository ist **privat**. GitHub Free enthält 2.000 Actions-Minuten/Monat für
  private Repos; ohne hinterlegte Zahlungsmethode stoppt Actions am Kontingent, statt abzurechnen.
- Präzedenzfall für Tooling: `seed-events.js` / `demo.js` sind abhängigkeitsfreie Node-Skripte,
  die Bash-Skripte ersetzten, damit sie unter Windows ohne Git Bash/WSL laufen.

## Optionen für lokales Testen

| # | Option | Was sie verifiziert | Kosten |
|---|---|---|---|
| A | Befehle pro Service wie bisher (`mvn test`, `npx ng test`) | Code | Kostenlos, aber Testcontainers-Cloud-Minuten zählen |
| B | Ein gemeinsamer Einstiegspunkt, `ci.js`, lokal und vom Workflow identisch aufgerufen | Code, mit Befehlsparität zu CI | Kostenlos |
| C | `act` (nektos/act) – führt die echte Workflow-YAML lokal in Docker aus | Die Workflow-Datei selbst (Trigger, Matrix, Schritte) | Kostenlos, mehrere GB große Runner-Images |
| D | Testcontainers auf lokalem Docker Desktop statt Testcontainers Cloud | Wie A/B | Kostenlos und unbegrenzt |
| E | Git-Pre-Push-Hook, der `ci.js` für geänderte Ziele ausführt | Code, vor jedem Push | Kostenlos, verlangsamt jeden Push |
| F | Lokaler End-to-End-Smoke-Test: `docker compose up`, `seed-events.js`, `demo.js` | Die ganze Saga | Kostenlos (existiert bereits, manuell) |

**A** funktioniert heute, lässt aber lokale und CI-Befehle auseinanderdriften. **C** bietet die
höchste Treue zur YAML, aber Testcontainers innerhalb von act braucht den eingebundenen
Docker-Socket des Hosts und ist unter Windows knifflig, kann also nicht der Standardweg sein.
**E** und **F** sind nützlich, aber separate Themen.

## Optionen für die Cloud-Seite

- **GitHub Actions** – das Repo liegt bereits auf GitHub; 2.000 kostenlose Minuten/Monat reichen
  mit den untenstehenden Maßnahmen; Ubuntu-Runner haben Docker, Testcontainers funktioniert also
  ohne Cloud-Dienst.
- **GitLab CI** (400 kostenlose Min./Monat) und **CircleCI Free** – würden bedeuten, das Repo auf
  eine zweite Plattform zu spiegeln, für weniger oder vergleichbare kostenlose Kapazität.
  Abgelehnt.

## Entscheidung

**B + D als Standardweg, C als optionales Debugging-Werkzeug, GitHub Actions in der Cloud.**

- `ci.js` (Repo-Wurzel) ist die einzige Quelle der Wahrheit dafür, was „CI besteht“ bedeutet:
  - `node ci.js <service> [--docker]` → `mvn -B -ntp verify` (+ `docker build` des Dockerfiles
    des Services).
  - `node ci.js frontend` → `npm ci`, `ng test --watch=false`, `ng build`.
  - `node ci.js services` / `node ci.js all` für alles.
  - Endet mit einem Wert ungleich null und nennt die fehlgeschlagenen Ziele; warnt (ohne zu
    blockieren), wenn lokale Testcontainers an Testcontainers Cloud geleitet werden.
- `.github/workflows/ci.yml` führt pro Job **genau diese Befehle** aus, sodass Parität per
  Konstruktion entsteht, statt zwei Definitionen synchron zu halten.
- Lokal sollte Testcontainers Desktop für CI-ähnliche Läufe die **lokale Docker-Laufzeit**
  nutzen (Option D); Testcontainers Cloud bleibt verfügbar, wird aber nicht mehr gebraucht.
- `act` (Option C) ist als optional dokumentiert, z. B. `act -j frontend`, zum Debuggen der
  Workflow-Syntax – für die normale Arbeit nicht erforderlich.

### Im kostenlosen Kontingent bleiben
- **Pfadfilter** (`dorny/paths-filter`): Eine Änderung unter `services/booking-service/**` führt
  nur den Job des booking-service aus. Eine Änderung an `ci.js` oder am Workflow selbst führt
  alles erneut aus. `workflow_dispatch` führt immer alles aus.
- **`concurrency` + `cancel-in-progress`**: Überholte Läufe auf derselben Ref werden abgebrochen.
- **Caching**: Maven-Cache von `setup-java`, npm-Cache von `setup-node`.
- **Kein Image-Push**: `docker build` verifiziert nur die Dockerfiles. Ein Push bräuchte eine
  Registry, und der Speicher für private GHCR-Images ist im kostenlosen Tarif begrenzt (siehe
  ADR-016).
- **Surefire-Berichte** werden nur bei Fehlschlag hochgeladen, mit 7 Tagen Aufbewahrung.
- **Testcontainers** in CI nutzt den Docker-Daemon des Runners – Testcontainers Cloud wird nicht
  verwendet.
- Wird das Repo öffentlich gemacht (es ist ein Portfolio-Projekt), werden die Actions-Minuten
  unbegrenzt; die Filter bleiben wegen des schnellen Feedbacks trotzdem sinnvoll.

## Konsequenzen

**Positiv**
- Ein Befehl reproduziert lokal einen CI-Job; kein „läuft auf meiner Maschine, scheitert in CI“
  durch auseinanderdriftende Kommandozeilen.
- Null Kosten an beiden Orten, die Grenze von Testcontainers Cloud ist aus dem kritischen Pfad
  entfernt.
- Jedes Dockerfile wird nun bei jeder relevanten Änderung gebaut – zuvor wurden sie nur durch ein
  manuelles `docker compose up --build` geübt.

**Negativ / akzeptiert**
- `--docker` baut jeden Service zweimal (einmal durch `mvn verify`, einmal in der eigenen
  Build-Stufe des Dockerfiles). Akzeptiert: Der zweite Build *ist* das, was verifiziert wird.
- Parität besteht bei den Befehlen, nicht bei den Umgebungen: Der CI-Runner ist Linux, die
  Hauptentwicklungsmaschine ist Windows. `act` (Option C) schließt diese Lücke, wenn es darauf
  ankommt.

## Zurückgestellt (separate künftige Slices)
- End-to-End-Smoke-Job in CI (`docker compose up` + `seed-events.js` + `demo.js`), wegen der
  Laufzeit manuell oder durch PRs ausgelöst.
- Pre-Push-Hook (Option E).
- Image-Push in eine Registry und CD/Deployment – siehe ADR-016 für die Umgebungsentscheidung.
