# ADR-023: Code Quality Analysis with SonarQube

**Status:** Accepted
**Date:** 2026-10-04
**Authors:** Engineering Team

---

## Context

CI (ADR-015) runs each changed target's tests on push and pull request: the six services' Maven
suites (unit tests plus Testcontainers) and the frontend's Vitest suite plus build. Nothing
measures code quality beyond passing tests. There are no coverage numbers, no static analysis,
no duplication or code-smell reports, and no quality gate. GitHub's own scanners (CodeQL,
Dependabot, secret scanning) are off as well.

The user asked for SonarQube. Constraints carried over: **cost-free**, and **testable locally and
in the cloud** (ADR-015).

Facts checked on 2026-10-04:

- **SonarQube Cloud's Free plan analyzes public projects without a lines-of-code limit**
  (private projects up to 50k LOC)
  ([pricing](https://www.sonarsource.com/products/sonarqube/cloud/new-pricing-plans/)).
  The repository is public.
- **On the Free plan, branch analysis covers only the main branch**, and pull request analysis
  only pull requests into the main branch
  ([branch analysis](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/branch-analysis/branch-analysis.md),
  [pull request analysis](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/pull-request-analysis.md)).
  This project works directly on `master`, so that's no limitation.
- **The OSS plan** (since 2026-01-27) adds feature-branch analysis and Pro features. It requires a
  **public, OSI-licensed repository and an application**
  ([announcement](https://community.sonarsource.com/t/introducing-the-new-free-oss-plan-in-sonarqube-cloud/176923)).
  **The repository has no license file.**
- **Automatic analysis**, where SonarQube Cloud reads the repository with no CI step, supports
  neither monorepos nor coverage
  ([automatic analysis](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/automatic-analysis)).
  Coverage and one project per service therefore need **CI-based analysis**.
- **Monorepo support:** several SonarQube Cloud projects can be bound to one GitHub repository,
  each with its own quality gate
  ([monorepo support](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/monorepo-support)).
- **Tooling versions:** SonarScanner for Maven 5.8.0 (2026-09-07),
  `SonarSource/sonarqube-scan-action` v8.3.0 (2026-09-30), JaCoCo 0.8.15 (2026-06-05, officially
  supports Java 26, so also the services' Java 25). The frontend already uses Vitest 4, which
  reports coverage through `@vitest/coverage-v8`.
- **A self-hosted SonarQube Community Build needs at least 4 GB RAM, 8 GB recommended**, plus
  `vm.max_map_count=524288` for its Elasticsearch
  ([host requirements](https://docs.sonarsource.com/sonarqube-community-build/server-installation/server-host-requirements)).
  This machine's WSL VM is capped at 4 GB in total, so it can't run there. The prod VM has about
  8 GB free.

## Options considered

### Where the analysis runs

| Option | Pros | Cons |
|---|---|---|
| **A. SonarQube Cloud, Free plan** | No server to run. Unlimited for public projects. Main-branch analysis and quality gate. Pull requests into `master` are decorated. | A third-party account and a `SONAR_TOKEN` secret. No analysis of feature branches. |
| **B. SonarQube Cloud, OSS plan** | Like A, plus feature-branch analysis and Pro features. | Needs an OSI license in the repository (a decision of its own) and an application. Nothing we use needs the extra features today. |
| **C. Self-hosted Community Build on the prod VM** | Full control, no external account. | 4–8 GB RAM next to the app on the VM, Elasticsearch tuning, another public endpoint to secure and back up. Community Build analyzes only the main branch, too. |
| **D. GitHub CodeQL only** | Built in, free for public repositories, strong security analysis. | Security only: no coverage, duplication, code smells or maintainability rating. Not what was asked for. It complements Sonar rather than replacing it. |

### How the code is split into projects

| Option | Pros | Cons |
|---|---|---|
| **1. Seven projects** (six services + frontend), bound to the repo as a monorepo | Matches the independently deployable services and the CI matrix. Each job analyzes what it just tested, with its own quality gate and coverage. | Seven projects to create once. |
| **2. One combined project** | One dashboard. | The services have no parent POM, so one analysis would need a custom multi-module setup. Changed-target CI would analyze partial code into one project. |

### What a failed quality gate does

| Option | Pros | Cons |
|---|---|---|
| **Fail the CI job** (`sonar.qualitygate.wait=true`) | A real gate: new issues or low coverage on new code stop the build. | The job waits for SonarQube Cloud's result, about a minute. |
| **Report only** | Never blocks. | Easy to ignore, which defeats the purpose. |

## Decision

Accepted by the user as proposed.

- **A, SonarQube Cloud Free**, with **1, seven projects** bound as a monorepo.
- **Upgrade path, agreed with the user:** if feature branches ever need a quality check (not just
  `master` and pull requests into it), move to the **OSS plan (B)**. It is also free, but it needs an
  OSI license file in the repository (for example MIT, which is the user's choice) and an application
  to Sonar. The projects and the CI setup stay as they are.
- **CI-based analysis in the existing jobs.** Each service job runs `mvn verify` with **JaCoCo**,
  then `sonar:sonar` with that service's project key. The frontend job runs `ng test` with
  coverage (`@vitest/coverage-v8`, LCOV), then `sonarqube-scan-action`. Only changed targets are
  analyzed, the same targets CI already tests.
- **Fail the job on a failed quality gate.** The default "Sonar way" gate judges **new code**.
  Existing issues show up on the dashboard instead of blocking, but only if the new-code definition
  makes the existing code the baseline.
- **New-code definition "Previous version" (baseline = now),** chosen with the user after the first
  runs failed. The project versions never change, so the code at the first analysis is the
  baseline and later changes are new code. A version bump at a release would move the baseline.
  - **Correction:** this ADR first assumed the gate wouldn't block the first analysis, and the
    runbook recommended "Number of days: 30". On this young codebase almost every line had changed
    within 30 days, so all seven gates failed on the first analysis.
  - SonarQube Cloud's docs don't say what "Previous version" does when the version never changes.
    That the first analysis is the baseline was confirmed by
    [run 37224299777](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37224299777):
    all seven gates passed (`docs/plan.md`).
- **Skip the analysis when `SONAR_TOKEN` is absent**, for example in pull requests from forks,
  which get no secrets. Tests still run.
- **Local parity (ADR-015):** `ci.js` runs the same `mvn verify`/`ng test` with coverage, so the
  coverage reports can be checked locally. The upload to SonarQube Cloud happens only in CI, so
  local runs don't add to the main branch's history. In the IDE, the free SonarQube for IDE plugin
  can show the same rules while editing (optional, connected mode).
- **Out of scope, possible small follow-ups:** CodeQL, Dependabot and secret scanning (repository
  settings plus a config file), and a license file.

**User steps** (accounts can't be created for the user):
1. Sign in to SonarQube Cloud with GitHub and create an organization for `comicNerd23` (Free
   plan).
2. Import `eventTicketingSystem` as a monorepo with the seven project keys from the
   implementation, and turn automatic analysis off.
3. Create a token and store it as the repository secret `SONAR_TOKEN`.

How it is built:
- **Every service POM** runs JaCoCo 0.8.15: `prepare-agent`, then `report` in `verify`, which writes
  `target/site/jacoco/jacoco.xml`. The Sonar scanner finds it at that default path.
- **The frontend** runs `ng test --coverage` with `@vitest/coverage-v8` 4.1.11, pinned to the
  installed Vitest. It writes `coverage/frontend/lcov.info`.
  `frontend/sonar-project.properties` declares the sources, the tests (`*.spec.ts`) and the LCOV
  path.
- **`ci.js --sonar`** runs after the tests. For services it calls the pinned SonarScanner for
  Maven 5.8.0.7211; for the frontend it calls the pinned npm scanner `@sonar/scan` 5.0.1. Each
  call gets the host, the organization, the key `<organization>_<target>` and
  `sonar.qualitygate.wait=true`. The scanners read the token from `SONAR_TOKEN` themselves, so it
  never appears on a command line. Without the token or `SONAR_ORGANIZATION`, the step prints that
  it is skipped.
- **`ci.yml`** passes `--sonar`, the secret and the variable to the service and frontend jobs,
  and checks out the full history (`fetch-depth: 0`). SonarQube needs it to tell new code from
  old.
- The user's one-time setup is in `docs/runbooks/sonarqube-cloud.md`.

## Consequences

**Positive**
- Every change to `master` is checked for bugs, vulnerabilities, code smells, duplication and
  coverage, with a gate on new code. Pull requests into `master` get the result too.
- Coverage numbers exist for the first time, locally as well (JaCoCo and V8 HTML reports).
- No cost, no server, nothing to run on the VM or this machine.

**Negative / accepted**
- **A third-party account and a token.** If SonarQube Cloud is down, the `--sonar` step fails the
  job. The fix is to re-run it, or remove the secret to skip the analysis temporarily.
- **Each analyzed job takes longer:** the scanner, plus up to about a minute waiting for the gate.
- **Feature branches aren't analyzed** on the Free plan; the OSS plan is the upgrade path.
- **JaCoCo instruments the Testcontainers suites too.** The coverage reflects integration tests as
  well, which is intended but makes the numbers look better than unit tests alone would.

---

---

# ADR-023: Codequalitätsanalyse mit SonarQube

**Status:** Akzeptiert  
**Datum:** 2026-10-04  
**Autoren:** Engineering-Team

---

## Kontext

CI (ADR-015) führt bei Push und Pull Request die Tests jedes geänderten Ziels aus: die
Maven-Suiten der sechs Services (Unit-Tests plus Testcontainers) und die Vitest-Suite plus Build
des Frontends. Nichts misst die Codequalität über bestandene Tests hinaus. Es gibt keine
Coverage-Zahlen, keine statische Analyse, keine Berichte zu Duplikaten oder Code Smells und kein
Quality Gate. Auch die eigenen Scanner von GitHub (CodeQL, Dependabot, Secret Scanning) sind aus.

Der Nutzer bat um SonarQube. Übernommene Randbedingungen: **kostenfrei** und **lokal wie in der
Cloud testbar** (ADR-015).

Am 2026-10-04 geprüfte Fakten:

- **Der Free-Tarif von SonarQube Cloud analysiert öffentliche Projekte ohne Begrenzung der
  Codezeilen** (private Projekte bis 50k LOC)
  ([Preise](https://www.sonarsource.com/products/sonarqube/cloud/new-pricing-plans/)). Das
  Repository ist öffentlich.
- **Im Free-Tarif umfasst die Branch-Analyse nur den Main-Branch**, und die Pull-Request-Analyse
  nur Pull Requests in den Main-Branch
  ([Branch-Analyse](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/branch-analysis/branch-analysis.md),
  [Pull-Request-Analyse](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/pull-request-analysis.md)).
  Dieses Projekt arbeitet direkt auf `master`, das ist also keine Einschränkung.
- **Der OSS-Tarif** (seit 2026-01-27) ergänzt Feature-Branch-Analyse und Pro-Funktionen. Er
  erfordert ein **öffentliches, OSI-lizenziertes Repository und eine Bewerbung**
  ([Ankündigung](https://community.sonarsource.com/t/introducing-the-new-free-oss-plan-in-sonarqube-cloud/176923)).
  **Das Repository hat keine Lizenzdatei.**
- **Die automatische Analyse**, bei der SonarQube Cloud das Repository ohne CI-Schritt liest,
  unterstützt weder Monorepos noch Coverage
  ([Automatische Analyse](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/automatic-analysis)).
  Coverage und ein Projekt pro Service brauchen daher **CI-basierte Analyse**.
- **Monorepo-Unterstützung:** Mehrere SonarQube-Cloud-Projekte können an ein GitHub-Repository
  gebunden werden, jedes mit eigenem Quality Gate
  ([Monorepo-Unterstützung](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/monorepo-support)).
- **Tooling-Versionen:** SonarScanner for Maven 5.8.0 (2026-09-07),
  `SonarSource/sonarqube-scan-action` v8.3.0 (2026-09-30), JaCoCo 0.8.15 (2026-06-05, unterstützt
  offiziell Java 26, also auch das Java 25 der Services). Das Frontend nutzt bereits Vitest 4, das
  Coverage über `@vitest/coverage-v8` meldet.
- **Ein selbst gehostetes SonarQube Community Build braucht mindestens 4 GB RAM, empfohlen 8 GB**,
  plus `vm.max_map_count=524288` für sein Elasticsearch
  ([Host-Anforderungen](https://docs.sonarsource.com/sonarqube-community-build/server-installation/server-host-requirements)).
  Die WSL-VM dieser Maschine ist insgesamt auf 4 GB begrenzt, es kann dort also nicht laufen. Die
  Prod-VM hat etwa 8 GB frei.

## Betrachtete Optionen

### Wo die Analyse läuft

| Option | Vorteile | Nachteile |
|---|---|---|
| **A. SonarQube Cloud, Free-Tarif** | Kein Server zu betreiben. Unbegrenzt für öffentliche Projekte. Main-Branch-Analyse und Quality Gate. Pull Requests in `master` werden dekoriert. | Ein Konto bei einem Dritten und ein `SONAR_TOKEN`-Secret. Keine Analyse von Feature-Branches. |
| **B. SonarQube Cloud, OSS-Tarif** | Wie A, plus Feature-Branch-Analyse und Pro-Funktionen. | Braucht eine OSI-Lizenz im Repository (eine eigene Entscheidung) und eine Bewerbung. Nichts, was wir nutzen, braucht heute die zusätzlichen Funktionen. |
| **C. Selbst gehostetes Community Build auf der Prod-VM** | Volle Kontrolle, kein externes Konto. | 4–8 GB RAM neben der App auf der VM, Elasticsearch-Tuning, ein weiterer öffentlicher Endpunkt, der abgesichert und gesichert werden muss. Das Community Build analysiert ebenfalls nur den Main-Branch. |
| **D. Nur GitHub CodeQL** | Eingebaut, kostenlos für öffentliche Repositories, starke Sicherheitsanalyse. | Nur Sicherheit: keine Coverage, Duplikate, Code Smells oder Wartbarkeitsbewertung. Nicht das, worum gebeten wurde. Es ergänzt Sonar, statt es zu ersetzen. |

### Wie der Code in Projekte aufgeteilt wird

| Option | Vorteile | Nachteile |
|---|---|---|
| **1. Sieben Projekte** (sechs Services + Frontend), als Monorepo ans Repo gebunden | Passt zu den unabhängig deploybaren Services und zur CI-Matrix. Jeder Job analysiert, was er gerade getestet hat, mit eigenem Quality Gate und eigener Coverage. | Sieben Projekte, die einmal angelegt werden müssen. |
| **2. Ein kombiniertes Projekt** | Ein Dashboard. | Die Services haben kein Parent-POM, eine Analyse bräuchte also ein eigenes Multi-Modul-Setup. CI mit geänderten Zielen würde Teilcode in ein Projekt analysieren. |

### Was ein fehlgeschlagenes Quality Gate bewirkt

| Option | Vorteile | Nachteile |
|---|---|---|
| **Den CI-Job scheitern lassen** (`sonar.qualitygate.wait=true`) | Ein echtes Gate: neue Issues oder niedrige Coverage bei neuem Code stoppen den Build. | Der Job wartet auf das Ergebnis von SonarQube Cloud, etwa eine Minute. |
| **Nur berichten** | Blockiert nie. | Leicht zu ignorieren, was den Zweck verfehlt. |

## Entscheidung

Vom Nutzer wie vorgeschlagen angenommen.

- **A, SonarQube Cloud Free**, mit **1, sieben Projekten**, als Monorepo gebunden.
- **Upgrade-Pfad, mit dem Nutzer vereinbart:** Müssen Feature-Branches je eine Qualitätsprüfung
  bekommen (nicht nur `master` und Pull Requests dorthin), wird auf den **OSS-Tarif (B)**
  gewechselt. Er ist ebenfalls kostenlos, braucht aber eine OSI-Lizenzdatei im Repository (zum
  Beispiel MIT, die Wahl liegt beim Nutzer) und eine Bewerbung bei Sonar. Die Projekte und das
  CI-Setup bleiben wie sie sind.
- **CI-basierte Analyse in den bestehenden Jobs.** Jeder Service-Job führt `mvn verify` mit
  **JaCoCo** aus, dann `sonar:sonar` mit dem Projektschlüssel dieses Services. Der Frontend-Job
  führt `ng test` mit Coverage aus (`@vitest/coverage-v8`, LCOV), dann `sonarqube-scan-action`.
  Analysiert werden nur geänderte Ziele, dieselben, die CI ohnehin testet.
- **Den Job bei fehlgeschlagenem Quality Gate scheitern lassen.** Das Standard-Gate „Sonar way“
  beurteilt **neuen Code**. Bestehende Issues erscheinen stattdessen im Dashboard, statt zu
  blockieren, aber nur, wenn die Definition von neuem Code den bestehenden Code zur Basislinie
  macht.
- **Definition von neuem Code „Previous version“ (Basislinie = jetzt),** mit dem Nutzer gewählt,
  nachdem die ersten Läufe gescheitert waren. Die Projektversionen ändern sich nie, daher ist der
  Code bei der ersten Analyse die Basislinie, und spätere Änderungen sind neuer Code. Eine
  Versionserhöhung bei einem Release würde die Basislinie verschieben.
  - **Korrektur:** Dieses ADR nahm zunächst an, das Gate würde die erste Analyse nicht blockieren,
    und das Runbook empfahl „Number of days: 30“. In dieser jungen Codebasis hatte sich innerhalb
    von 30 Tagen fast jede Zeile geändert, sodass alle sieben Gates bei der ersten Analyse
    scheiterten.
  - Die Doku von SonarQube Cloud sagt nicht, was „Previous version“ tut, wenn sich die Version nie
    ändert. Dass die erste Analyse die Basislinie ist, bestätigte
    [Run 37224299777](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37224299777):
    Alle sieben Gates bestanden (`docs/plan.md`).
- **Die Analyse überspringen, wenn `SONAR_TOKEN` fehlt**, zum Beispiel bei Pull Requests aus
  Forks, die keine Secrets erhalten. Tests laufen weiterhin.
- **Lokale Parität (ADR-015):** `ci.js` führt dasselbe `mvn verify`/`ng test` mit Coverage aus, die
  Coverage-Berichte lassen sich also lokal prüfen. Der Upload zu SonarQube Cloud geschieht nur in
  CI, lokale Läufe fügen der Historie des Main-Branches also nichts hinzu. In der IDE kann das
  kostenlose Plugin SonarQube for IDE dieselben Regeln beim Bearbeiten anzeigen (optional,
  Connected Mode).
- **Außerhalb des Umfangs, mögliche kleine Folgeschritte:** CodeQL, Dependabot und Secret Scanning
  (Repository-Einstellungen plus eine Konfigurationsdatei) sowie eine Lizenzdatei.

**Schritte des Nutzers** (Konten können nicht für den Nutzer angelegt werden):
1. Bei SonarQube Cloud mit GitHub anmelden und eine Organisation für `comicNerd23` anlegen
   (Free-Tarif).
2. `eventTicketingSystem` als Monorepo mit den sieben Projektschlüsseln aus der Umsetzung
   importieren und die automatische Analyse ausschalten.
3. Einen Token erstellen und als Repository-Secret `SONAR_TOKEN` hinterlegen.

Wie es gebaut ist:
- **Jedes Service-POM** führt JaCoCo 0.8.15 aus: `prepare-agent`, dann `report` in `verify`, was
  `target/site/jacoco/jacoco.xml` schreibt. Der Sonar-Scanner findet es unter diesem Standardpfad.
- **Das Frontend** führt `ng test --coverage` mit `@vitest/coverage-v8` 4.1.11 aus, auf das
  installierte Vitest festgelegt. Es schreibt `coverage/frontend/lcov.info`.
  `frontend/sonar-project.properties` deklariert die Quellen, die Tests (`*.spec.ts`) und den
  LCOV-Pfad.
- **`ci.js --sonar`** läuft nach den Tests. Für Services ruft es den festgelegten SonarScanner for
  Maven 5.8.0.7211 auf; für das Frontend den festgelegten npm-Scanner `@sonar/scan` 5.0.1. Jeder
  Aufruf erhält den Host, die Organisation, den Schlüssel `<organization>_<target>` und
  `sonar.qualitygate.wait=true`. Die Scanner lesen den Token selbst aus `SONAR_TOKEN`, er erscheint
  also nie in einer Kommandozeile. Ohne den Token oder `SONAR_ORGANIZATION` gibt der Schritt aus,
  dass er übersprungen wird.
- **`ci.yml`** übergibt `--sonar`, das Secret und die Variable an die Service- und Frontend-Jobs
  und checkt die vollständige Historie aus (`fetch-depth: 0`). SonarQube braucht sie, um neuen von
  altem Code zu unterscheiden.
- Die einmalige Einrichtung durch den Nutzer steht in `docs/runbooks/sonarqube-cloud.md`.

## Konsequenzen

**Positiv**
- Jede Änderung an `master` wird auf Bugs, Schwachstellen, Code Smells, Duplikate und Coverage
  geprüft, mit einem Gate auf neuen Code. Pull Requests in `master` erhalten das Ergebnis ebenfalls.
- Zum ersten Mal gibt es Coverage-Zahlen, auch lokal (HTML-Berichte von JaCoCo und V8).
- Keine Kosten, kein Server, nichts auf der VM oder dieser Maschine zu betreiben.

**Negativ / akzeptiert**
- **Ein Konto bei einem Dritten und ein Token.** Ist SonarQube Cloud down, lässt der
  `--sonar`-Schritt den Job scheitern. Die Abhilfe ist, ihn erneut auszuführen oder das Secret zu
  entfernen, um die Analyse vorübergehend zu überspringen.
- **Jeder analysierte Job dauert länger:** der Scanner, plus bis zu etwa eine Minute Warten auf das
  Gate.
- **Feature-Branches werden im Free-Tarif nicht analysiert;** der OSS-Tarif ist der Upgrade-Pfad.
- **JaCoCo instrumentiert auch die Testcontainers-Suiten.** Die Coverage spiegelt auch
  Integrationstests wider, was beabsichtigt ist, die Zahlen aber besser aussehen lässt, als Unit-Tests
  allein es täten.
