# ADR-024: CodeQL and Dependabot

**Status:** Accepted
**Date:** 2026-10-04
**Authors:** Engineering Team

---

## Context

ADR-023 added SonarQube Cloud for code quality and coverage. ADR-023 left two GitHub-native checks
as follow-ups, both free for public repositories, and the user asked to turn them on:

- **CodeQL code scanning.** GitHub's semantic security analysis. Its default setup needs no
  workflow file: GitHub detects the languages and runs the analysis on pushes and pull requests to
  the default branch, plus on a weekly schedule. For Java without Kotlin, default setup analyzes
  **without a build** (`build-mode: none`), so it doesn't depend on the services' Maven builds or
  on Testcontainers
  ([CodeQL for compiled languages](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages)).
  Before this change GitHub listed it as `not-configured`, and detected `actions`, `java-kotlin` and
  `javascript-typescript`.
- **Dependabot.** **Alerts** report dependencies with known vulnerabilities. **Security updates**
  open pull requests that fix them. **Version updates** (`.github/dependabot.yml`) keep
  dependencies current on a schedule. Its `directories` key accepts globs such as `/services/*`,
  and version updates get a default 3-day cooldown after a release
  ([options reference](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference)).

## Decision

- **CodeQL default setup** with the `default` query suite, enabled through the repository API.
  The alternative was advanced setup with a workflow file: more control (query packs, a build for
  Java), but more to maintain, and default setup covers what this repository needs.
- **Dependabot alerts and security updates** on, both repository settings.
- **Dependabot version updates** in `.github/dependabot.yml` for all four ecosystems: Maven (the six
  services through `directories: ["/services/*"]`), npm (frontend), GitHub Actions, and Docker (the
  base images in the seven Dockerfiles).
  - **Weekly, on Mondays.**
  - **Minor and patch updates grouped** into one PR per ecosystem, so one library doesn't produce
    six PRs. Major updates come one by one, because Spring Boot, Angular or Java majors need their
    own review.
  - **At most five open PRs** per ecosystem for Maven and npm (Dependabot's default).
  - **Angular moves as one group,** for version and for security updates (`@angular/*`,
    `@angular-devkit/*`, `@schematics/angular`). Its packages pin each other to the exact same
    version, so the first security PR, which bumped only `@angular/router`, failed `npm ci` with
    `ERESOLVE`. Angular is bumped with `ng update`, which updates the whole group.
    **TypeScript belongs to the group** too, because Angular decides which TypeScript versions it
    supports. A separate PR proposed TypeScript 7 for Angular 21.
  - **Angular majors are a slice of their own.** The first version run opened one PR per package
    for Angular 22. Those were closed: a major upgrade runs through `ng update` with its
    migrations and gets its own review, not a Dependabot merge.
  - **The `maven` build image is excluded** from Docker updates. Its tags carry the JDK in the
    suffix (`3.9-eclipse-temurin-25`), and Dependabot proposed `3-eclipse-temurin-24`, a JDK
    downgrade that broke every service's Docker build. It is updated by hand together with the JDK.
    The runtime image `eclipse-temurin:25-jre` stays covered.
  - **nginx stays on the stable line.** nginx's even minor versions are stable (1.30) and its odd
    ones mainline (1.31). Dependabot only compares numbers, so it proposed mainline (PR #17, closed).
    Minor and major updates of `nginxinc/nginx-unprivileged` are ignored. Stable patch releases
    (1.30.x) don't come through Dependabot; they arrive through the floating tag (next bullet).
    The next stable line (1.32) is a manual change.
- **Patch releases of base images come through floating tags, not Dependabot.** The Dockerfiles
  use tags that the image publishers move in place: `nginx-unprivileged:1.30-alpine`,
  `node:26-alpine`, `eclipse-temurin:25-jre` and `maven:3.9-eclipse-temurin-25`. Dependabot only
  proposes a different tag (1.30 → 1.31). A new patch under the same tag is picked up like this:
  - **Release images** (`release-images.yml`, GitHub's BuildKit) look up the tag's current digest
    on every build. A new patch therefore misses the GHA layer cache and lands in the next
    `sha-…` image.
  - **Prod** only runs it after that new release is deployed (`Deploy prod`). A running cluster
    never updates itself.
  - **Local builds** (`docker build` in `ci.js`, `deploy-dev.js`) reuse the base image already on
    the machine. `docker pull <image>` or `docker build --pull` gets the newest patch.
  - So, after a security fix in a base image: run **Release images**, then **Deploy prod** with the
    new tag.
- **Temporary npm override: `piscina` → 5.3.2** in `frontend/package.json`, chosen with the user.
  `@angular/build` and `@angular-devkit/build-angular` 21.2.24 pin `piscina` at exactly 5.2.0,
  which has a critical advisory (GHSA-67c8-pqhq-4rmx, fixed in 5.3.2). No Angular 21 release ships
  the fix, so Dependabot can't open a PR for it. The risk is low, because piscina only runs inside
  the build tool. **Remove the override with the Angular 22 upgrade**, if that version ships a
  fixed piscina. **Removed on 2026-10-05** with Angular 22.2.1, which ships piscina 5.3.2 (ADR-025).
- **No auto-merge.** Every update PR runs CI with the full test suite and is merged by hand.

How it fits with the existing checks:
- **Dependabot PRs get no Actions secrets**, so `SONAR_TOKEN` is missing there and `ci.js` skips
  the SonarQube step. The tests still run. The SonarQube analysis happens after merge to `master`.
- **CodeQL and SonarQube overlap on security rules.** CodeQL's data-flow queries are the
  established standard for security alerts, and SonarQube covers maintainability and coverage.
  Duplicate findings are accepted.

## Consequences

**Positive**
- Known-vulnerable dependencies show up as alerts, and fixes arrive as pull requests.
- Security analysis on every push to `master`, on pull requests and weekly, without a workflow file
  and without a build.
- Dependencies, Actions and base images stay current in small, reviewable steps.

**Negative / accepted**
- **More pull requests,** up to a few per week. Each runs CI. On a public repository that costs no
  minutes, but it's review effort.
- **`docker/docker-compose.yml` images aren't covered** (Postgres, Redis, Kafka, Prometheus,
  Grafana). The Dockerfiles are what ships to prod; the compose file is local only.
- **Default setup has fewer knobs** than advanced setup (no custom queries, no build for Java). If
  that ever matters, switching to advanced setup is a workflow file.
- **Secret scanning isn't part of this ADR.** It is a separate repository setting, still off.

---

---

# ADR-024: CodeQL und Dependabot

**Status:** Akzeptiert  
**Datum:** 2026-10-04  
**Autoren:** Engineering-Team

---

## Kontext

ADR-023 ergänzte SonarQube Cloud für Codequalität und Coverage. ADR-023 ließ zwei GitHub-native
Prüfungen als Folgeschritte offen, beide kostenlos für öffentliche Repositories, und der Nutzer bat
darum, sie einzuschalten:

- **CodeQL Code Scanning.** GitHubs semantische Sicherheitsanalyse. Ihr Default-Setup braucht keine
  Workflow-Datei: GitHub erkennt die Sprachen und führt die Analyse bei Pushes und Pull Requests
  auf den Standard-Branch sowie wöchentlich nach Zeitplan aus. Für Java ohne Kotlin analysiert das
  Default-Setup **ohne Build** (`build-mode: none`), hängt also weder von den Maven-Builds der
  Services noch von Testcontainers ab
  ([CodeQL für kompilierte Sprachen](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages)).
  Vor dieser Änderung führte GitHub es als `not-configured` und erkannte `actions`, `java-kotlin`
  und `javascript-typescript`.
- **Dependabot.** **Alerts** melden Abhängigkeiten mit bekannten Schwachstellen. **Security
  Updates** öffnen Pull Requests, die sie beheben. **Version Updates** (`.github/dependabot.yml`)
  halten Abhängigkeiten nach Zeitplan aktuell. Sein Schlüssel `directories` akzeptiert Globs wie
  `/services/*`, und Version Updates erhalten nach einem Release standardmäßig 3 Tage Cooldown
  ([Optionen-Referenz](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference)).

## Entscheidung

- **CodeQL Default-Setup** mit der Query-Suite `default`, über die Repository-API aktiviert. Die
  Alternative war das Advanced Setup mit Workflow-Datei: mehr Kontrolle (Query-Packs, ein Build für
  Java), aber mehr zu pflegen, und das Default-Setup deckt ab, was dieses Repository braucht.
- **Dependabot Alerts und Security Updates** an, beides Repository-Einstellungen.
- **Dependabot Version Updates** in `.github/dependabot.yml` für alle vier Ökosysteme: Maven (die
  sechs Services über `directories: ["/services/*"]`), npm (Frontend), GitHub Actions und Docker
  (die Basis-Images in den sieben Dockerfiles).
  - **Wöchentlich, montags.**
  - **Minor- und Patch-Updates gruppiert** in einen PR pro Ökosystem, damit eine Bibliothek nicht
    sechs PRs erzeugt. Major-Updates kommen einzeln, weil Major-Versionen von Spring Boot, Angular
    oder Java eigene Reviews brauchen.
  - **Höchstens fünf offene PRs** pro Ökosystem für Maven und npm (Dependabots Standard).
  - **Angular bewegt sich als eine Gruppe,** bei Version Updates und Security Updates
    (`@angular/*`, `@angular-devkit/*`, `@schematics/angular`). Seine Pakete pinnen einander auf
    exakt dieselbe Version, daher scheiterte der erste Security-PR, der nur `@angular/router`
    anhob, bei `npm ci` mit `ERESOLVE`. Angular wird mit `ng update` angehoben, was die ganze
    Gruppe aktualisiert. **TypeScript gehört ebenfalls zur Gruppe,** weil Angular entscheidet,
    welche TypeScript-Versionen es unterstützt. Ein separater PR schlug TypeScript 7 für Angular 21
    vor.
  - **Angular-Major-Versionen sind ein eigener Slice.** Der erste Version-Update-Lauf öffnete einen
    PR pro Paket für Angular 22. Diese wurden geschlossen: Ein Major-Upgrade läuft über `ng update`
    mit seinen Migrationen und bekommt ein eigenes Review, keinen Dependabot-Merge.
  - **Das `maven`-Build-Image ist von Docker-Updates ausgenommen.** Seine Tags tragen das JDK im
    Suffix (`3.9-eclipse-temurin-25`), und Dependabot schlug `3-eclipse-temurin-24` vor, ein
    JDK-Downgrade, das den Docker-Build jedes Services brach. Es wird von Hand zusammen mit dem JDK
    aktualisiert. Das Laufzeit-Image `eclipse-temurin:25-jre` bleibt abgedeckt.
  - **nginx bleibt auf der stabilen Linie.** Gerade Minor-Versionen von nginx sind stabil (1.30),
    ungerade Mainline (1.31). Dependabot vergleicht nur Zahlen und schlug daher Mainline vor (PR
    #17, geschlossen). Minor- und Major-Updates von `nginxinc/nginx-unprivileged` werden ignoriert.
    Stabile Patch-Releases (1.30.x) kommen nicht über Dependabot; sie kommen über das Floating Tag
    (nächster Punkt). Die nächste stabile Linie (1.32) ist eine manuelle Änderung.
- **Patch-Releases von Basis-Images kommen über Floating Tags, nicht über Dependabot.** Die
  Dockerfiles nutzen Tags, die die Image-Herausgeber an Ort und Stelle verschieben:
  `nginx-unprivileged:1.30-alpine`, `node:26-alpine`, `eclipse-temurin:25-jre` und
  `maven:3.9-eclipse-temurin-25`. Dependabot schlägt nur ein anderes Tag vor (1.30 → 1.31). Ein
  neuer Patch unter demselben Tag wird so aufgenommen:
  - **Release-Images** (`release-images.yml`, GitHubs BuildKit) schlagen bei jedem Build den
    aktuellen Digest des Tags nach. Ein neuer Patch verfehlt daher den GHA-Layer-Cache und landet
    im nächsten `sha-…`-Image.
  - **Prod** führt ihn erst aus, nachdem dieses neue Release deployt wurde (`Deploy prod`). Ein
    laufender Cluster aktualisiert sich nie selbst.
  - **Lokale Builds** (`docker build` in `ci.js`, `deploy-dev.js`) verwenden das bereits auf der
    Maschine vorhandene Basis-Image. `docker pull <image>` oder `docker build --pull` holt den
    neuesten Patch.
  - Also, nach einem Sicherheitsfix in einem Basis-Image: **Release images** ausführen, dann
    **Deploy prod** mit dem neuen Tag.
- **Temporäres npm-Override: `piscina` → 5.3.2** in `frontend/package.json`, mit dem Nutzer gewählt.
  `@angular/build` und `@angular-devkit/build-angular` 21.2.24 pinnen `piscina` auf exakt 5.2.0,
  das einen kritischen Advisory hat (GHSA-67c8-pqhq-4rmx, behoben in 5.3.2). Kein Angular-21-Release
  liefert den Fix, daher kann Dependabot keinen PR dafür öffnen. Das Risiko ist gering, da piscina
  nur innerhalb des Build-Werkzeugs läuft. **Das Override mit dem Angular-22-Upgrade entfernen,**
  falls diese Version ein repariertes piscina liefert. **Am 2026-10-05 entfernt,** mit Angular
  22.2.1, das piscina 5.3.2 liefert (ADR-025).
- **Kein Auto-Merge.** Jeder Update-PR führt CI mit der vollständigen Testsuite aus und wird von
  Hand gemergt.

Wie es zu den bestehenden Prüfungen passt:
- **Dependabot-PRs erhalten keine Actions-Secrets,** daher fehlt dort `SONAR_TOKEN`, und `ci.js`
  überspringt den SonarQube-Schritt. Die Tests laufen weiterhin. Die SonarQube-Analyse geschieht
  nach dem Merge in `master`.
- **CodeQL und SonarQube überschneiden sich bei Sicherheitsregeln.** Die Data-Flow-Queries von
  CodeQL sind der etablierte Standard für Sicherheitsmeldungen, und SonarQube deckt Wartbarkeit und
  Coverage ab. Doppelte Funde werden akzeptiert.

## Konsequenzen

**Positiv**
- Abhängigkeiten mit bekannten Schwachstellen erscheinen als Alerts, und Fixes kommen als Pull
  Requests.
- Sicherheitsanalyse bei jedem Push auf `master`, bei Pull Requests und wöchentlich, ohne
  Workflow-Datei und ohne Build.
- Abhängigkeiten, Actions und Basis-Images bleiben in kleinen, reviewbaren Schritten aktuell.

**Negativ / akzeptiert**
- **Mehr Pull Requests,** bis zu einigen pro Woche. Jeder führt CI aus. In einem öffentlichen
  Repository kostet das keine Minuten, aber Review-Aufwand.
- **Die Images in `docker/docker-compose.yml` sind nicht abgedeckt** (Postgres, Redis, Kafka,
  Prometheus, Grafana). Die Dockerfiles sind es, was in Prod ausgeliefert wird; die Compose-Datei
  ist nur lokal.
- **Das Default-Setup hat weniger Stellschrauben** als das Advanced Setup (keine eigenen Queries,
  kein Build für Java). Falls das je wichtig wird, ist der Wechsel zum Advanced Setup eine
  Workflow-Datei.
- **Secret Scanning ist nicht Teil dieses ADR.** Es ist eine separate Repository-Einstellung, noch
  aus.
