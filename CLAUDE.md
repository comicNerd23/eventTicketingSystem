# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A portfolio project: an event ticketing system (concerts, sports, theatre, comedy) built as **six independently deployable Spring Boot microservices** behind an Angular frontend, built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 21 (zoneless, Vitest) · Tailwind CSS 4 · Docker/K8s · Stripe sandbox (stubbed) · Testcontainers 1.21.4

The repo documents itself more than this file can — always check these before assuming behavior:

- `docs/plan.md` — authoritative, dated project history: every slice, every real bug found and how it was fixed, current phase status. Read this to understand *why* the code looks the way it does.
- `docs/adr/` — 14 Architecture Decision Records with the reasoning behind every non-obvious choice.
- `docs/diagrams/c4-diagram.md` — C4 Context + Container diagrams (Mermaid).
- `specs/asyncapi/kafka-events.yaml` — the event catalog: every Kafka topic, publisher, consumer.
- `specs/openapi/` — REST contract per service.

## Commands

```bash
# Bring up all 6 services + infra (Postgres, Redis, Kafka, Prometheus, Grafana)
docker compose -f docker/docker-compose.yml up -d

# Reset to a clean demo catalog (13 events across concerts/sports/theatre/comedy)
node seed-events.js

# Walk the full happy-path saga end-to-end (hold -> confirm -> payment -> CONFIRMED -> cancel -> refund -> waitlist promotion)
node demo.js

# Frontend dev server
cd frontend && npm install && npx ng serve   # -> http://localhost:4200
```

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

Local service ports: api-gateway 8080 · event-service 8081 · booking-service 8082 · payment-service 8083 · notification-service 8084 · waitlist-service 8085 · Kafdrop 9000 · Prometheus 9090 · Grafana 3000 (admin/admin).

## Architecture

**Saga choreography, not orchestration** (ADR-002): services react to Kafka events rather than being called directly by a central coordinator. When a flow "isn't working," check Kafdrop (localhost:9000) for the actual event trail before assuming a service-level bug — `specs/asyncapi/kafka-events.yaml` is the ground truth for who publishes/consumes what.

**Database-per-service** (ADR-001): each service owns its own Postgres database (`ticketing_events`, `ticketing_payments`, etc.) with no cross-service joins or shared schema. Cross-service reads go through synchronous REST calls (e.g. `booking-service`'s `EventServiceClient`, a Spring `RestClient`, calls `event-service`'s `GET /events/{id}`) or through the Kafka event stream — never direct DB access.

**Redis SETNX + TTL for seat holds** (ADR-003): `booking-service`'s `SeatHoldService` uses Redis as a distributed lock with expiry to hold a seat during checkout, backstopped by `SeatHoldExpiredListener` reacting to Redis key-expiry events. This is the mechanism to understand before touching anything in the hold → confirm → expire path.

**Layered architecture per service** (ADR-005): controllers → services → repositories, no reverse dependencies, enforced by convention/review rather than tooling. JPA entities carry annotations directly (no hexagonal port/adapter split). Each service follows the same package shape: `controller/`, `domain/`, `dto/`, `exception/` (with a `GlobalExceptionHandler`), `kafka/consumer/` + `kafka/producer/`, `repository/`, `service/`, plus service-specific packages (`redis/`, `websocket/`, `client/` for cross-service REST calls).

**UUIDv4 primary keys everywhere** (ADR-006), including cross-service foreign-key fields (`Booking.eventId`, `Booking.userId`, etc.) — there is no auto-increment ID anywhere in the domain model.

**Frontend**: Angular 21, zoneless change detection, Vitest for tests, Tailwind 4 for styling. `frontend/src/app/` is organized by feature (`events/`, `bookings/`), talking to the backend via `api-gateway` and to `booking-service` directly via WebSocket for live seat-status updates (ADR-009).

Before "fixing" something that looks wrong, check `docs/adr/` — several apparently odd choices (stubbed Stripe gateway, simplified webhook payload instead of real signature verification, denormalized `venueName`/`city` on `Event`, seat-level fields still client-supplied in bookings) are deliberate, scoped-down decisions documented with their deferral conditions, not bugs.

## Current status

Phases 1–4 (specs, scaffolding, core backend, frontend) are done. Phase 5 (DevOps: CI/CD, K8s, Cloud Run) is in progress — check `docs/plan.md` for exactly what's shipped vs. next; the phase table there is more current than anything else.

Work is delivered in small, independently checkable end-to-end vertical slices (see "Delivery rule: testable slices" in `docs/plan.md`) — each slice must produce an observable, verifiable result (a real request/response or demo step), not just code + unit tests, and pauses for review before the next slice starts.
