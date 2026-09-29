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
