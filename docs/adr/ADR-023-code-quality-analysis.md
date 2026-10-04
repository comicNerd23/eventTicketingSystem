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
