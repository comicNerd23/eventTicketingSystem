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
    Minor and major updates of `nginxinc/nginx-unprivileged` are ignored. The tag `1.30-alpine`
    floats, so stable patch releases come with every image build. The next stable line (1.32) is a
    manual change.
- **Temporary npm override: `piscina` → 5.3.2** in `frontend/package.json`, chosen with the user.
  `@angular/build` and `@angular-devkit/build-angular` 21.2.24 pin `piscina` at exactly 5.2.0,
  which has a critical advisory (GHSA-67c8-pqhq-4rmx, fixed in 5.3.2). No Angular 21 release ships
  the fix, so Dependabot can't open a PR for it. The risk is low, because piscina only runs inside
  the build tool. **Remove the override with the Angular 22 upgrade**, if that version ships a
  fixed piscina.
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
