# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A portfolio project: an event ticketing system (concerts, sports, theatre, comedy) built as **six independently deployable Spring Boot microservices** behind an Angular frontend, built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 21 (zoneless, Vitest) · Tailwind CSS 4 · Docker/K8s · Stripe sandbox (stubbed) · Testcontainers 1.21.4

The repo documents itself more than this file can — always check these before assuming behavior:

- `docs/plan.md` — authoritative, dated project history: every slice, every real bug found and how it was fixed, current phase status. Read this to understand *why* the code looks the way it does.
- `docs/adr/` — 23 Architecture Decision Records with the reasoning behind every non-obvious choice.
- `docs/diagrams/c4-diagram.md` — C4 Context + Container diagrams (Mermaid).
- `specs/asyncapi/kafka-events.yaml` — the event catalog: every Kafka topic, publisher, consumer.
- `specs/openapi/` — REST contract per service.

## Commands

```bash
# Bring up all 6 services + the frontend container (-> http://localhost:8000) + infra (Postgres, Redis, Kafka, Prometheus, Grafana)
docker compose -f docker/docker-compose.yml up -d

# Reset to a clean demo catalog (13 events across concerts/sports/theatre/comedy)
node seed-events.js

# Walk the full happy-path saga end-to-end (hold -> confirm -> payment -> CONFIRMED -> cancel -> refund -> waitlist promotion)
node demo.js

# Frontend dev server
cd frontend && npm install && npx ng serve   # -> http://localhost:4200, proxies /api to :8080

# Kubernetes dev cluster (current kube-context must be kind-* or rancher-desktop, ADR-020)
node deploy-dev.js [targets...] [--no-build] [--seed]   # kind -> :8000, Rancher Desktop -> :80
node seed-events.js --k8s [--base-url=http://localhost]
```

Docker Desktop (compose, Testcontainers, kind) and Rancher Desktop share one 4 GB WSL VM on this machine — never run both. Rancher Desktop serves the docker `default` context (`npipe:////./pipe/docker_engine`); the CLI's own context often stays `desktop-linux`. Compose app services use `restart: unless-stopped`. With the old `on-failure`, JVMs stopped with exit code 143 came back after every engine restart and crash-looped.

If containers look broken after a host restart, check `docker compose -f docker/docker-compose.yml ps -a` (the `-a` matters — infra containers can sit `Exited` while app containers crash-loop against them). Bring infra up first, then restart the six app services.

**Backend tests**, per service (standalone Maven module, own Testcontainers-backed integration suite — no root/parent pom, each service depends directly on `spring-boot-starter-parent`):

```bash
cd services/<service-name>
mvn test
mvn test -Dtest=ClassName            # single test class
mvn test -Dtest=ClassName#methodName # single test method
```

**Frontend tests** (Vitest, migrated from Karma/Jasmine — see `docs/adr/ADR-013-angular-21-upgrade-and-vitest-migration.md`):

```bash
cd frontend
npx ng test
```

**Release images** — `.github/workflows/release-images.yml` (manual or `v*` tag) pushes multi-arch (amd64 + arm64, native runners) images to `ghcr.io/comicnerd23/ticketing/<target>:sha-<7>`; immutable tags only, public packages (ADR-021). The repo is public, so Actions minutes are free.

**Prod deploy** — `node deploy-prod.js --context=<ctx> --tag=<sha-1234567|vX.Y.Z> --base-url=https://<ip-with-dashes>.sslip.io [--tls-issuer=letsencrypt-staging|letsencrypt-prod|selfsigned]` with `DB_PASSWORD` and `GATEWAY_CORS_ALLOWED_ORIGINS` in the environment. It needs an explicit context, accepts only immutable tags, checks the tag in GHCR first, and creates the Secret and ConfigMap via stdin. `k8s/overlays/prod` holds no secrets, only `set-by-deploy` placeholders for the tag, host and issuer. The frontend nginx returns 404 for `/api/actuator` (ADR-021). HTTPS (ADR-022): the script installs cert-manager from a pinned, sha256-checked manifest, `--base-url` must be `https://` on a host name (no bare IP), and it waits until the certificate comes from the chosen issuer. Use staging first after any TLS change. Port 80 stays open for HTTP-01, and only the app Ingress redirects to HTTPS. Steps: `docs/runbooks/prod-vm.md`.

**CI locally** — `ci.js` runs exactly what `.github/workflows/ci.yml` runs per job (ADR-015): `node ci.js <service> [--docker]`, `node ci.js frontend [--docker]`, `node ci.js all`. Both produce coverage (JaCoCo in every service POM, Vitest V8 in the frontend). CI adds `--sonar`, which sends each target to its own SonarQube Cloud project (`<SONAR_ORGANIZATION>_<target>`) and fails on a failed quality gate. It is skipped without `SONAR_TOKEN`/`SONAR_ORGANIZATION` (ADR-023, `docs/runbooks/sonarqube-cloud.md`). Testcontainers should use the local Docker runtime rather than Testcontainers Cloud (free plan capped at 50 min/month).

Local service ports: frontend 8000 (compose; kind's Ingress uses the same port) · api-gateway 8080 · event-service 8081 · booking-service 8082 · payment-service 8083 · notification-service 8084 · waitlist-service 8085 · Kafdrop 9000 · Prometheus 9090 · Grafana 3000 (admin/admin).

## Architecture

**Saga choreography, not orchestration** (ADR-002): services react to Kafka events rather than being called directly by a central coordinator. When a flow "isn't working," check Kafdrop (localhost:9000) for the actual event trail before assuming a service-level bug — `specs/asyncapi/kafka-events.yaml` is the ground truth for who publishes/consumes what.

**Database-per-service** (ADR-001): each service owns its own Postgres database (`ticketing_events`, `ticketing_payments`, etc.) with no cross-service joins or shared schema. Cross-service reads go through synchronous REST calls (e.g. `booking-service`'s `EventServiceClient`, a Spring `RestClient`, calls `event-service`'s `GET /events/{id}`) or through the Kafka event stream — never direct DB access.

**Redis SETNX + TTL for seat holds** (ADR-003): `booking-service`'s `SeatHoldService` uses Redis as a distributed lock with expiry to hold a seat during checkout, backstopped by `SeatHoldExpiredListener` reacting to Redis key-expiry events. This is the mechanism to understand before touching anything in the hold → confirm → expire path.

**Layered architecture per service** (ADR-005): controllers → services → repositories, no reverse dependencies, enforced by convention/review rather than tooling. JPA entities carry annotations directly (no hexagonal port/adapter split). Each service follows the same package shape: `controller/`, `domain/`, `dto/`, `exception/` (with a `GlobalExceptionHandler`), `kafka/consumer/` + `kafka/producer/`, `repository/`, `service/`, plus service-specific packages (`redis/`, `websocket/`, `client/` for cross-service REST calls).

**Per-environment config + Flyway** (ADR-017): each service has `application.yml` (shared), `application-dev.yml` (default profile, local defaults) and `application-prod.yml` (connection values as `${VAR}` with no default; a prod-only `config/RequiredConfigurationCheck` makes a missing variable fail at startup, because Boot's binding otherwise leaves unresolved placeholders in place). Flyway owns the schema (`src/main/resources/db/migration`) and `ddl-auto` is `validate` everywhere — **any entity change needs a new `V<n>__*.sql` migration**, or every test that touches the database fails. Local prod-profile check: add `-f docker/docker-compose.prod-profile.yml`.

**UUIDv4 primary keys everywhere** (ADR-006), including cross-service foreign-key fields (`Booking.eventId`, `Booking.userId`, etc.) — there is no auto-increment ID anywhere in the domain model.

**Frontend**: Angular 21, zoneless change detection, Vitest for tests, Tailwind 4 for styling. `frontend/src/app/` is organized by feature (`events/`, `bookings/`), talking to the backend only same-origin under `/api` (ADR-019): in the container nginx proxies it to `api-gateway` (prefix stripped), under `ng serve` `proxy.conf.json` does. That includes the seat-status WebSocket, which the gateway routes on to `booking-service` (ADR-009). The `/api` prefix exists because the SPA routes `/events/:id` and `/bookings/:id` collide with the gateway's own paths.

Before "fixing" something that looks wrong, check `docs/adr/` — several apparently odd choices (stubbed Stripe gateway, simplified webhook payload instead of real signature verification, denormalized `venueName`/`city` on `Event`, seat-level fields still client-supplied in bookings) are deliberate, scoped-down decisions documented with their deferral conditions, not bugs.

## Current status

Phases 1–4 (specs, scaffolding, core backend, frontend) are done. Phase 5 (DevOps: CI/CD, K8s, dev on Rancher Desktop + prod on k3s per ADR-016) is in progress — check `docs/plan.md` for exactly what's shipped vs. next; the phase table there is more current than anything else.

Work is delivered in small, independently checkable end-to-end vertical slices (see "Delivery rule: testable slices" in `docs/plan.md`) — each slice must produce an observable, verifiable result (a real request/response or demo step), not just code + unit tests, and pauses for review before the next slice starts.
