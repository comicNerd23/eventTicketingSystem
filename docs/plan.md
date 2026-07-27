# Project Plan — Event Ticketing Platform

Portfolio project (concerts/sports/shows) built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack (Spring Boot + Angular) skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 17+ · Docker/K8s · GCP Cloud Run · Stripe sandbox · Testcontainers 1.21.3

---

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Specs — OpenAPI 3.1 per service, AsyncAPI 2.x for Kafka, ADRs, C4 diagram | Done |
| 2 | Scaffolding — Spring Boot stubs from specs, Docker Compose infra | Done |
| 3 | Core backend — one service at a time, starting with booking-service | In progress |
| 4 | Angular frontend — SVG seat map, countdown timer, WebSocket | Not started |
| 5 | DevOps — GitHub Actions CI/CD, K8s manifests, GCP Cloud Run | Not started |

Phase order and scope are unchanged from the original plan. What's new is the delivery rule below, which governs how work inside phases 3 and 4 gets broken up and checkpointed.

---

## Delivery rule: testable slices

Starting now, every unit of work inside a phase (a service in Phase 3, a feature in Phase 4) is broken into small, independent, **end-to-end vertical slices** rather than built as one large implementation.

Each slice must:
- Produce an observable, checkable result — a real request/response, not just code + unit tests (e.g. "`POST /events` creates an event and `GET /events/{id}` returns it," not the full CRUD + validation + edge cases in one go).
- Include a way to verify it: a `demo.sh` addition, a curl example, or tests that exercise the real path.
- Be small enough to stay fully comprehensible on its own.

**Checkpoint:** work pauses after each slice for review before the next slice starts. Slices are not chained together without a checkpoint in between.

---

## Phase 3 progress

### booking-service — done
Happy-path slice: hold seat → confirm booking → saga via Kafka. 35 tests passing (unit, controller, repository via Testcontainers Cloud, saga integration). Migrated to Spring Boot 4.1.0 / Java 25 (see `docs/spring-boot-4-migration.md`).

Note: booking-service currently accepts event/seat data directly in the request body (client-supplied `eventId`, `eventTitle`, `seatId`, `seatLabel`, `priceGbp`) — it does not yet call a real event-service. Wiring booking-service to real event data is a future slice, after event-service exists.

### event-service — next up

**Slice 1 (proposed):**
- `POST /venues` — create a venue with sections
- `POST /events` — create an event tied to a venue
- `GET /events/{id}` — fetch an event
- `GET /events` — list events

Backed by real Postgres persistence, controller/service tests (same pattern as `BookingControllerTest`), and a `demo.sh` addition: create venue → create event → fetch it → see it in the list.

Deferred to later slices: seat map generation (`GET /events/{eventId}/seats`), update/cancel event, venue lookup by ID, and hooking booking-service up to real event data instead of client-supplied values.

### Remaining services (not yet scoped into slices)
payment-service, notification-service, waitlist-service, api-gateway

---

## Outstanding housekeeping

- No git remote configured yet — local commits (through `2657353`, Spring Boot 4 migration) need a remote once ready to push.
- Testcontainers Cloud free plan is capped at 50 min/month — reserve integration test runs for genuine breakage or final pre-commit verification, not speculative re-runs.
