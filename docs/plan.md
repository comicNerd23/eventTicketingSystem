# Project Plan — Event Ticketing Platform

Portfolio project (concerts/sports/shows) built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack (Spring Boot + Angular) skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 21 (zoneless, Vitest) · Tailwind CSS 4 · Docker/K8s (Rancher Desktop dev, k3s prod) · GitHub Actions · Stripe sandbox · Testcontainers 1.21.4

---

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Specs — OpenAPI 3.1 per service, AsyncAPI 2.x for Kafka, ADRs, C4 diagram | Done |
| 2 | Scaffolding — Spring Boot stubs from specs, Docker Compose infra | Done |
| 3 | Core backend — one service at a time, starting with booking-service | Done |
| 4 | Angular frontend — SVG seat map, countdown timer, WebSocket | Done |
| 5 | DevOps — GitHub Actions CI/CD, K8s manifests, dev (Rancher Desktop) + prod (k3s) environments — see ADR-016 (replaces the original GCP Cloud Run target) | In progress |

Phase order and scope are unchanged from the original plan. What's new is the delivery rule below, which governs how work inside phases 3 and 4 gets broken up and checkpointed.

**Correction, 2026-08-26**: Phase 1's row above has said "Done" — including the C4 diagram — since this table was first written, but no C4 diagram actually existed anywhere in the repo until today. Found during a fresh read-through of the repo aimed at a new-developer onboarding pass (the OpenAPI/AsyncAPI specs and ADRs part of that row were genuinely done). Closed now: see [`docs/diagrams/c4-diagram.md`](diagrams/c4-diagram.md) (Context + Container levels, Mermaid). Also added a root [`README.md`](../README.md) — there previously wasn't one, which was the bigger of the two onboarding gaps.

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
Happy-path slice: hold seat → confirm booking → saga via Kafka. 39 tests passing (unit, controller, repository via Testcontainers Cloud, saga integration). Migrated to Spring Boot 4.1.0 / Java 25 (see `docs/spring-boot-4-migration.md`).

### booking-service ↔ event-service — event-level binding done

`booking-service` now calls `event-service` (`GET /events/{id}`) via a Spring `RestClient` (`EventServiceClient`) when holding a seat: it validates the event exists and fetches its real `title`, replacing the previously client-supplied `eventTitle` field. Returns 404 (`EventNotFoundException`) if the event doesn't exist, 503 (`EventServiceUnavailableException`) if event-service is unreachable.

Seat-level fields (`seatId`, `seatLabel`, `priceGbp`) remain client-supplied — `event-service` has no per-seat model yet (only aggregate `Section` row/seat counts), so full seat-level binding is deferred until seat map generation exists (see below).

Config: `event.service.base-url` (`http://localhost:8081` locally, `http://event-service:8081` in docker-compose via `EVENT_SERVICE_BASE_URL`). Verified against the real containerized event-service via `demo.sh` (not just a mocked integration test) — confirmed `eventTitle` in the booking response is fetched live, not client-supplied.

Also fixed while verifying end-to-end: `payment-simulator`'s `pom.xml` still declared `spring-kafka` instead of `spring-boot-starter-kafka` (a leftover gap from the earlier Spring Boot 4 migration), which meant it had no autoconfigured `KafkaTemplate` bean and crash-looped instead of consuming `payment-initiated` — silently masked before because `BookingSagaIntegrationTest` publishes `payment-completed` directly via its own Testcontainers Kafka rather than exercising the real `payment-simulator`.

### event-service — Slice 1 done

- `POST /venues` — create a venue with sections (capacity computed from rows × seatsPerRow)
- `POST /events` — create an event tied to a venue (denormalizes venueName/city onto Event, status defaults to PUBLISHED)
- `GET /events/{id}` — fetch an event
- `GET /events` — list events, filterable by city/category/dateFrom/dateTo, paginated

Spring Boot 4.1.0 / Java 25, real Postgres persistence (own database `ticketing_events`, database-per-service). 13 tests passing: `VenueControllerTest` (2), `EventControllerTest` (6), `VenueRepositoryTest` (1), `EventRepositoryTest` (4, filter specs via Testcontainers). Wired into `docker/docker-compose.yml` (port 8081) and `demo.sh` (venue/event creation steps, independent of the booking-service flow).

Deferred to later slices: seat map generation (`GET /events/{eventId}/seats`), update/cancel event, venue lookup by ID (`GET /venues/{venueId}`), listing venues, and full seat-level binding for booking-service (blocked on seat map generation existing).

### payment-service — Slice 1 done

- Kafka consumer on `payment-initiated` (consumer group `payment-service-group` — see the retirement slice below for how this settled on the AsyncAPI-spec-literal name) creates a `PENDING` `Payment` via a stubbed `PaymentGateway` (`StubPaymentGateway`, synchronously returns a fake `pi_stub_...` id — real Stripe SDK deferred; swappable later purely via `payment.gateway.provider` config, no caller changes).
- `GET /payments/{paymentId}`, `GET /payments/bookings/{bookingId}`
- `POST /payments/webhook` — simplified trigger payload (`type`, `paymentIntentId`, optional `failureMessage`) standing in for a real Stripe event; `Stripe-Signature` header presence is enforced, cryptographic verification deferred. Transitions `PENDING` → `SUCCEEDED`/`FAILED`, publishes `payment-completed`/`payment-failed` accordingly.

Spring Boot 4.1.0 / Java 25, real Postgres persistence (own database `ticketing_payments`). 27 tests passing: `PaymentRepositoryTest` (6), `PaymentServiceTest` (11), `PaymentControllerTest` (8), `PaymentSagaIntegrationTest` (2, Kafka+Postgres via Testcontainers, mirrors `BookingSagaIntegrationTest`'s spy-and-await pattern). Wired into `docker/docker-compose.yml` (port 8083).

At this point still not wired into the live saga (`payment-simulator` played that role) — see the retirement slice near the end of this document for how that gap closed.

Deferred: real Stripe SDK integration behind `PaymentGateway` (webhook signature verification, real `PaymentIntent` creation).

Also fixed while verifying end-to-end: a pre-existing Kafka partition-count race in `docker/docker-compose.yml`, unrelated to payment-service itself but only surfaced by adding a second independent consumer. `booking-service`'s `KafkaConfig` declares `NewTopic` beans requesting 3 partitions, but on a fresh broker a consumer (`payment-simulator` or `payment-service`) can auto-create the topic first via `KAFKA_AUTO_CREATE_TOPICS_ENABLE`, getting the broker's default of 1 partition; `KafkaAdmin` then only *increases* it to 3 once `booking-service` starts, and already-subscribed consumers stay pinned to partition 0 until their next metadata refresh (default 5 minutes) — so a booking whose key hashes to partition 1 or 2 would never be seen by a consumer stuck on partition 0. Fixed by setting `KAFKA_NUM_PARTITIONS: 3` as the broker's default, so whoever auto-creates the topic first gets the right partition count immediately — no race window at all.

### payment-service — booking-cancelled consumer (real refunds), done

Closes the last gap the AsyncAPI spec already documented: `booking-cancelled` was specified as consumed by `payment-service`, `notification-service`, and `waitlist-service`, but only the latter two were actually implemented until now. `PaymentStatus.REFUNDED` already existed in the enum (added anticipating this), unused until this slice.

`PaymentGateway` gained a `refund(stripePaymentIntentId, amountGbp)` method (mirrors the existing `createCharge`; `StubPaymentGateway` fakes a `re_stub_...` id, no real Stripe SDK, same as the rest of this gateway). New `PaymentService.refundForCancelledBooking(bookingId)`: looks itself up by `bookingId` (`booking-cancelled`'s payload has no `paymentId` — booking-service doesn't own payment data, as already noted when that event was first built) — no payment found, or found but not `SUCCEEDED` (still `PENDING`, or already `FAILED`/`REFUNDED`), is a silent idempotent no-op; only a `SUCCEEDED` payment actually gets refunded and transitioned to `REFUNDED`. The refund amount comes from payment-service's own stored `Payment.amountGbp`, not the event payload's copy — payment-service is the source of truth for what it actually charged. New `BookingCancelledConsumer` reuses the existing `payment-service-consumer-group` (one consumer group per service, matching `notification-service`'s established pattern) — no collision risk on this topic since `payment-simulator` doesn't consume `booking-cancelled` at all.

19 new/updated tests: `PaymentServiceTest` (+4 — no payment found, still `PENDING`, already `REFUNDED`, `SUCCEEDED` → refunds and transitions), `PaymentSagaIntegrationTest` (+1, real Kafka+Postgres via Testcontainers — seeds a real `SUCCEEDED` payment via the existing webhook flow, publishes a real `booking-cancelled`, awaits `REFUNDED`). Wired into `demo.sh` as a new **Step 16** (appended at the end rather than renumbering 11-15, since it only depends on Step 10's cancel having already happened): confirms the same payment Steps 6-7 drove to `SUCCEEDED` is `REFUNDED` after Step 10's cancel — verified for real against the live Docker stack, not just tests.

Remaining payment-service gap: no real Stripe SDK yet (already tracked above). Still riding on `payment-simulator` for the live saga at this point — see the retirement slice below.

### notification-service — Slice 1 done

- Kafka consumer on `ticket-issued` (own consumer group, `notification-service-consumer-group`) records a confirmation notification when a booking is confirmed.
- "Sending" is stubbed behind a `NotificationSender` interface (`StubNotificationSender`, logs the rendered email; swappable later via `notification.sender.provider` config, mirroring payment-service's `PaymentGateway` pattern) — no real email provider yet.
- `GET /notifications/{id}`, `GET /notifications/bookings/{bookingId}` for verification.
- Dedup guard (`existsByBookingIdAndType`) skips reprocessing if the same booking's `ticket-issued` is redelivered.

Spring Boot 4.1.0 / Java 25, real Postgres persistence (own database `ticketing_notifications`). 16 tests passing: `NotificationRepositoryTest` (4, Testcontainers), `NotificationServiceTest` (7), `NotificationControllerTest` (4), `NotificationSagaIntegrationTest` (1, Kafka+Postgres via Testcontainers, mirrors `PaymentSagaIntegrationTest`'s spy-and-await pattern). Wired into `docker/docker-compose.yml` (port 8084) and `demo.sh` (Step 8, independent verification of the `ticket-issued` event already published in Step 4).

Deferred to later slices: the other three consumed events (`seat-hold-expired`, `booking-cancelled`, `waitlist-promoted`), a real email provider behind `NotificationSender`.

### booking-service — Redis TTL-expiry detection done

ADR-003's keyspace-notification design, previously fully unbuilt, is now real: Redis is configured with `notify-keyspace-events KEA`; `SeatHoldExpiredListener` (extends Spring Data Redis's `KeyExpirationEventMessageListener`) detects real `seat-hold:*` key expiry and calls `BookingService.expireHold(seatId)`, which transitions the HELD booking to `EXPIRED` and publishes `seat-hold-expired` (previously spec'd, never implemented). `SeatHoldService`'s TTL is now configurable (`booking.hold.ttl-seconds`, default 600) so it can be shortened in tests — `SeatHoldExpiryIntegrationTest` overrides it to 2s and asserts on a **real** Redis-fired expiry (not a manually-published Kafka event), proving the mechanism end-to-end.

19 new/updated tests passing (34 unit/controller unaffected + `SeatHoldServiceTest` updated for configurable TTL + 2 new `BookingServiceTest.expireHold` cases + 1 new `SeatHoldExpiryIntegrationTest`).

Not demoed live in `demo.sh` — a 10-minute (or even short-override) real-time wait isn't practical inside the existing flow; the Testcontainers integration test is the real proof.

### booking-service — seat-released fix + cancel endpoint done

`handlePaymentFailed` now publishes `seat-released` (topic was provisioned since the original slice but never actually published to — a real gap, now closed). New `POST /bookings/{bookingId}/cancel` (per the already-speced `specs/openapi/booking-service.yaml:114`): cancels a HELD booking locally (Redis release, no Kafka event — nobody else has a stake in an unconfirmed hold) or a CONFIRMED booking (publishes `booking-cancelled`, previously spec'd but unimplemented); any other state returns 409 via the new `BookingNotCancellableException`, mirroring the existing `BookingNotHeldException` pattern.

`booking-cancelled`'s payload omits `paymentId` — booking-service doesn't own payment data (database-per-service); a future refund consumer in payment-service should look itself up by `bookingId`.

15 new/updated tests passing (`BookingServiceTest` +6 cancel/seat-released cases, `BookingControllerTest` +3 cancel cases, `BookingSagaIntegrationTest` +2 real end-to-end cases: cancel-a-confirmed-booking → `booking-cancelled` published, and payment-failed → `seat-released` published). Wired into `demo.sh` (Step 9: cancels the CONFIRMED demo booking — this becomes the trigger the next two slices verify against).

### waitlist-service — built from spec, done

New service built from the pre-existing `specs/openapi/waitlist-service.yaml`, following the same shape as the other services.

- `POST /waitlist` — join the waitlist for an event (validates the event via `event-service`'s `EventServiceClient`, copied 1:1 from booking-service's — no shared library exists in this repo). 409 if already `WAITING` for that event.
- `GET /waitlist/{entryId}`, `GET /waitlist/events/{eventId}/me` — `position` (1 = next) is computed live on read, not stored, since it shifts as others join/leave/get promoted.
- `DELETE /waitlist/{entryId}` — soft-delete to `LEFT` (a real status value in the spec, not a hard delete).
- Waitlist is per-**event**, not per-seat — seats aren't modeled as a first-class entity anywhere in the system (event-service only tracks aggregate capacity), and `WaitlistPromotedPayload` itself has no `seatId`.
- `SeatAvailabilityConsumer` — three `@KafkaListener`s (`seat-released`, `seat-hold-expired`, `booking-cancelled`; consumer group `waitlist-service-group`, used literally as the AsyncAPI spec documents since there's no dual-consumer collision here) all funnel into one `WaitlistService.promoteNextForEvent(eventId)`. Promotes the oldest `WAITING` entry, sets a 15-minute (configurable) offer window, publishes `waitlist-promoted`.

Spring Boot 4.1.0 / Java 25, real Postgres persistence (own database `ticketing_waitlist`, port 8085). 23 tests passing: `WaitlistEntryRepositoryTest` (5, Testcontainers), `WaitlistServiceTest` (8), `WaitlistControllerTest` (9), `WaitlistSagaIntegrationTest` (1, publishes a real `booking-cancelled` over Kafka+Postgres via Testcontainers and verifies promotion through the real `GET` endpoint). Wired into `docker/docker-compose.yml` (port 8085, depends on event-service) and `demo.sh` (Step 9: join the demo event's waitlist; Step 11: verify `PROMOTED` after Step 10's cancel — the same `booking-cancelled` publish Slice 3 added).

### notification-service — remaining 3 consumers done

`SeatHoldExpiredConsumer`, `BookingCancelledConsumer`, `WaitlistPromotedConsumer` added alongside the existing `TicketIssuedConsumer`, all following the same dedup-guard → stub-send → persist shape.

**Schema change**: `waitlist-promoted` has no `bookingId` at all (it's an offer, not a booking). `Notification.bookingId` is now nullable, with a new nullable `waitlistEntryId` column for the promoted-offer case. New `GET /notifications/waitlist-entries/{waitlistEntryId}` mirrors the existing booking lookup.

**Real bug caught during this slice**: once a booking could have more than one notification (e.g. `TICKET_ISSUED` then later `BOOKING_CANCELLED` for the same `bookingId`), the original `findByBookingId` — a single-result query — would throw `IncorrectResultSizeDataAccessException` the moment a second row existed. Demo.sh's own flow (ticket-issued in Step 8, booking-cancelled in Step 10, both for the same booking) would have hit this immediately. Fixed by renaming it to `findFirstByBookingIdOrderByCreatedAtDesc` — the booking lookup now always returns the most recent notification for that booking, which is a more correct contract regardless of how many notification types accumulate on one booking going forward.

35 tests passing: `NotificationRepositoryTest` (9, Testcontainers, including a new most-recent-wins case), `NotificationServiceTest` (16), `NotificationControllerTest` (6), `NotificationSagaIntegrationTest` (4 — one per event type, Kafka+Postgres via Testcontainers). Wired into `demo.sh`: Step 12 confirms the booking's notification flips to `BOOKING_CANCELLED` after Step 10; Step 13 confirms `WAITLIST_PROMOTED` after Step 11. `seat-hold-expired` isn't demoed live (same 10-minute-wait problem as booking-service's Slice 2) — covered by its saga integration test only.

This closes out the multi-slice arc started to give notification-service real triggers instead of manually-published test events — all four AsyncAPI-documented notification-service events are now wired end-to-end from real producers through to real consumers.

### api-gateway — done

The sixth and last service from ADR-001, built with no pre-existing spec (unlike every other service). Single client-facing entry point (port 8080) using **Spring Cloud Gateway, reactive/WebFlux** — see `docs/adr/ADR-007-api-gateway-choice.md` for the full comparison against Gateway MVC and infra-level alternatives (dedicated API gateway product, service mesh + Ingress), including the explicit caveat that this application-level Java gateway is a portfolio-skill-demonstration choice, not what a genuinely enterprise-scale system would use.

Declarative path-based routing in `application.yml` (`spring.cloud.gateway.server.webflux.routes` — this exact property path, and the artifact split into `spring-cloud-starter-gateway-server-webflux`/`-webmvc`, are new as of the Spring Cloud version resolved here; no prior version was paired with Spring Boot 4.1.0 in this repo). No overlapping path prefixes across services, so no `StripPrefix`/rewriting needed — pure forward-as-is:
- `/venues/**`, `/events/**` → event-service
- `/bookings/**` → booking-service
- `/payments/**` → payment-service
- `/notifications/**` → notification-service
- `/waitlist/**` → waitlist-service

**Versions resolved** (documented here same as the Boot 4 migration doc, since no prior art existed for this pairing): `spring-cloud-dependencies` **2025.1.2** (the latest available train; its own BOM targets Boot 4.0.7, one minor behind this project's 4.1.0, but proved compatible in practice), pulling in `spring-cloud-gateway-dependencies` 5.0.2. The reactive test client needed an explicit `spring-boot-webtestclient` dependency — `@AutoConfigureWebTestClient`/`WebTestClientAutoConfiguration` live in that module (package `org.springframework.boot.webtestclient.autoconfigure`), not bundled with `spring-boot-starter-test` or the `spring-boot-webflux-test` slice-test module (that one is for `@WebFluxTest` only).

Out of scope for this slice, deliberately (see ADR-007 and the plan): CORS (no frontend yet), rate limiting, circuit breakers, auth/JWT logic — headers pass through unchanged.

6 tests passing (`RoutingIntegrationTest`, one per route): each spins up a JDK-native `com.sun.net.httpserver.HttpServer` stub bound to a random port (zero new test dependency), wires its address in via `@DynamicPropertySource` overriding the route's `*.service.base-url` property, and asserts a real request through the gateway reaches the stub and its response comes back — proving an actual network hop, not a mocked route table. (Caught one real test-authoring bug along the way: stopping the stub servers in `@AfterEach` killed them after the *first* test while the cached Spring context — and its already-resolved route URIs — stayed alive for the rest, so every subsequent test got connection-refused; fixed by stopping them once in `@AfterAll` instead.)

Wired into `docker/docker-compose.yml` (port 8080, `depends_on` all five downstream services) and `demo.sh`: Step 14 re-issues Step 0d's `GET /events` through the gateway instead of event-service's own port (proves read-path routing); Step 15 issues a fresh `POST /bookings/hold` through the gateway (proves write-path routing with a request body) — not a full re-run of the whole saga through the gateway, since that would just duplicate Steps 1-13's already-proven business logic.

### event-service — seat generation + composed seat map, Slice A done

Real prerequisite for the frontend's SVG seat map, not the frontend itself: nowhere in the system was a seat a first-class entity before this (`seatId` was an opaque client-supplied UUID everywhere, and `HoldSeatRequest.java`'s own comment said as much). Two checkpointed slices; this is Slice A.

- `Section` gains `priceGbp` (required) — different sections (Floor vs Upper Tier) price differently, and this is what lets `priceGbp` stop being client-supplied in Slice B.
- New `Seat` entity (`domain/Seat.java`, plain `UUID eventId` column, same denormalized-id convention `Event.venueId` already uses — no cross-aggregate JPA relation). `EventService.createEvent` generates `rows × seatsPerRow` seats per section right after saving the event (650 for the demo venue: Floor 10×20 + Upper Tier 15×30).
- Seat status (`AVAILABLE`/`HELD`/`BOOKED`) is **not** stored on `Seat` — that would create a second, driftable source of truth alongside booking-service's `Booking.status`, which already is the truth. Instead, `GET /events/{eventId}/seats` composes it live: a new booking-service endpoint `GET /bookings/events/{eventId}/active-seats` exposes its own `HELD`/`PAYMENT_PENDING`/`CONFIRMED` bookings for the event (pure read of existing data, no new state), and a new event-service `BookingServiceClient` (RestClient, mirrors booking-service's own `EventServiceClient`) calls it and maps the results onto the matching seats (`HELD`/`PAYMENT_PENDING`→`HELD`, `CONFIRMED`→`BOOKED`, everything else→`AVAILABLE`). No silent degrade-to-available on failure — a `BookingServiceUnavailableException` (503) surfaces it, same as the existing `EventServiceUnavailableException` pattern.

**Two real bugs caught by actually running this in Docker** (not by the test suite — both were environment/wiring issues invisible to Testcontainers, which always starts from a blank schema):
1. The `postgres_data` volume from earlier sessions already had `sections` rows before `priceGbp` existed; Hibernate's `ddl-auto: update` can't add a `NOT NULL` column to a table with existing rows (`column "price_gbp" of relation "sections" contains null values`). Fixed by dropping and recreating just the `ticketing_events` database — pure disposable demo data, no real loss.
2. `docker-compose.yml` never got a `BOOKING_SERVICE_BASE_URL` env var for event-service's new outbound call — the property existed in `application.yml` with a `localhost:8082` default, but nothing overrode it for the container network, so every seat-map request 503'd until added. (Also confirmed event-service must **not** `depends_on: [booking-service]` — booking-service already depends on event-service, so that would be a real circular dependency, not just a Compose error to work around.)

18 new tests passing across the two services: event-service's `EventServiceTest` (6, new — this service had no service-layer unit tests before), `SeatRepositoryTest` (2, Testcontainers), `EventControllerTest` (+2), `SeatMapIntegrationTest` (2, new — real HTTP round-trip via Testcontainers Postgres with `BookingServiceClient` mocked, mirrors how booking-service's own integration test stubs `EventServiceClient`); booking-service's `BookingServiceTest`/`BookingControllerTest` (+2 each) for `active-seats`. `demo.sh` Step 0a now sends `priceGbp` per section; new Step 0e fetches the generated seat map, asserts all 650 came back `AVAILABLE`, and picks a **real** seat id — replacing the random-GUID `SEAT_ID` every step downstream used to use.

### booking-service — validates holds against real seats, Slice B done

Closes the loop Slice A opened: `holdSeat` no longer trusts client-supplied `seatLabel`/`priceGbp` (the `HoldSeatRequest` comment that started this whole two-slice arc is gone — those fields are removed from the request entirely). `EventServiceClient` gains `getSeat(eventId, seatId)` (same shape as the existing `getEvent`: 404→`SeatNotFoundException`, other failures→`EventServiceUnavailableException`); `holdSeat` calls it right after validating the event and uses the real label/price it returns — the exact same fix already applied to `eventTitle` in the original booking↔event binding slice, now applied to the two fields that were always meant to follow.

event-service gained the matching lookup: `GET /events/{eventId}/seats/{seatId}` (`SeatRepository.findByEventIdAndId`, new `SeatNotFoundException`) — a plain inventory read, no cross-service composition needed here since booking-service already knows a seat's live hold/booking status from its own DB.

**A second real stale-verification bug, this time in the test suite itself rather than Docker**: after removing `seatLabel`/`priceGbp` from `HoldSeatRequest`, several test files still called the now-deleted setters — but `mvn test-compile` reported no errors, because Maven's staleness check only looks at a source file's own mtime against its compiled `.class`, not at whether a class it depends on changed shape. The dependent test files hadn't been touched, so `javac` skipped them and stale `.class` files from before the removal stayed on disk, making the build look green. Only `mvn clean test-compile` surfaced the five real compile errors. Worth remembering: after removing/renaming a method or field, a plain (non-clean) `test-compile` is not trustworthy proof nothing broke.

25 new/updated tests: booking-service's `BookingServiceTest` (+3: real values from event-service, `SeatNotFoundException`, replaces an obsolete "defaults" test whose premise Slice B removed), `BookingControllerTest`, `EventServiceClientTest` (+3: `getSeat` found/404/503, mirroring the existing `getEvent` coverage), `BookingSagaIntegrationTest` and `SeatHoldExpiryIntegrationTest` updated to stub `getSeat`; event-service's `EventServiceTest`/`EventControllerTest` (+2 each) for the new lookup. `demo.sh` Step 1 drops the now-removed request fields; Step 0e now also captures a *second* real seat id for Step 15's gateway write-path check, since that step's previous random-GUID seat would now be rejected with a real 404.

Also hit — and had to wait out — a transient Docker Desktop/Windows networking hiccup where both containers were fully started per their own logs but unreachable from the host for about a minute; retrying the same curl calls a few seconds later worked with no code or config change. Not a real bug, but a reminder that a single failed connectivity check against a freshly-(re)started container isn't proof of a real problem — worth a retry before assuming the code is at fault.

This closes the two-slice seat-map arc. `demo.sh` now runs the full 15-step flow using only real, event-service-issued seat data end-to-end.

### Remaining services (not yet scoped into slices)
None — all six services from ADR-001 are now built. Remaining backend work is deepening existing services (see "Next up" below) alongside Phase 4 (frontend).

---

## Phase 4 progress

### Angular frontend — Slice 1 (CORS + events list), done

First frontend slice: prove the frontend↔gateway↔backend path works end-to-end with the smallest real feature, not the full SVG seat map yet (that's its own much bigger slice).

**CORS at api-gateway**: `spring.cloud.gateway.server.webflux.globalcors.cors-configurations` in `application.yml`, config-only (no new Java class), scoped to `http://localhost:4200` (Angular's dev-server default) rather than `*` — allows GET/POST/PUT/DELETE/OPTIONS and all headers. Deliberately deferred out of the api-gateway slice itself since no frontend existed yet to need it.

**Angular version pinned to 19** (documented here the same way the Spring Cloud version pairing was — a concrete version pinned because it's what the environment actually supports, not an assumption): the newest Angular CLI (22.x) and even 20/21.x refuse to run on this machine's real Node v22.18.0 (`engines` requires `^20.19.0 || ^22.12.0 || >=24.0.0` or newer). Angular 19.x (`node: ^18.19.1 || ^20.11.1 || >=22.0.0`) is the newest version that actually runs here, and is still within the "Angular 17+" the original stack line named.

**Update, 2026-08-26**: this Node constraint has since resolved on its own — the machine now runs Node v24.19.0 (was v22.18.0 when the pin above was written), which satisfies the `>=24.0.0` branch of the range that previously blocked 20.x. Upgraded via `ng update @angular/core@20 @angular/cli@20` (19.2.25 → 20.3.29, CLI → 20.3.35): `package.json`/`angular.json` updated, `node_modules` reinstalled, the CLI's own migration schematics ran automatically (workspace-generation-defaults, `moduleResolution: bundler`, a few no-op deprecation-usage migrations). Declined the three *optional* migrations offered (`use-application-builder` — already on the esbuild application builder since Slice 4; `control-flow-migration` — the codebase already writes `@for`/`@if`, not `*ngFor`/`*ngIf`; `router-current-navigation` — `Router.getCurrentNavigation` isn't used anywhere in this codebase). `ng build` and all 32 `ng test` cases pass unchanged — pure dependency bump, no application code needed to change.

**Events list feature** (`frontend/src/app/events/`): standalone `EventListComponent` (root route) + `EventsApiService` (`inject(HttpClient)`, one method `listEvents()` → `GET {apiBaseUrl}/events` through the gateway) + TypeScript interfaces matching event-service's real `EventResponse`/`EventPageResponse` JSON shape. `environment.ts`/`environment.development.ts` carry `apiBaseUrl: 'http://localhost:8080'` (the gateway, not event-service directly). Renders via `async` pipe + `@for`, plain CSS card grid, no styling framework yet.

Out of scope for this slice (bigger slices of their own, later in Phase 4): the SVG seat map, the booking flow (hold/confirm/countdown timer), WebSocket/STOMP live updates, any styling framework, containerizing the frontend (Phase 5 territory — runs via `ng serve` for now).

5 tests passing (`EventsApiService` via `HttpTestingController`, `EventListComponent` rendering from a stubbed service). Verified against the real running stack, not just tests: rebuilt api-gateway with the CORS config, started `ng serve`, and confirmed in an actual browser (headless Chrome, real JS execution + screenshot, not just a curl of the static shell) that the events list renders the real events created by `demo.sh` — including live `Access-Control-Allow-Origin` headers on the gateway response.

### Angular frontend — Slice 2 (SVG seat map, view-only), done

No backend or gateway changes needed — `GET /events/{eventId}` and `GET /events/{eventId}/seats` already existed from the earlier backend seat-map arc (event-service Slice A/B) and were already routed through api-gateway's existing `/events/**` prefix. Pure frontend slice.

**New `SeatMapComponent`** (`frontend/src/app/events/seat-map.component.{ts,html,css}`), reachable at `/events/:id` — reads the event id from the route, `forkJoin`s `getEvent`/`getSeatMap` (both new methods added to the existing `EventsApiService`, same pattern as `listEvents()`), and renders one `<svg>` per section (grouped by `sectionName`, order preserved from the backend's generation order). Seats have no stored x/y — each `<rect>` derives its grid position directly from `rowNumber`/`seatNumber`. Color is a pure function of status (`AVAILABLE` green, `HELD` amber, `BOOKED` grey), with a `<title>` per seat for a hover tooltip (label + price) and a legend above the grid. `EventListComponent` cards now link to their seat map via `routerLink`.

**Deliberately view-only** — no click handlers, no booking-service calls. Clicking an available seat to hold it (with a countdown timer) is scoped as its own future slice, matching the small-slice discipline used throughout this project.

**Real verification, not just tests**: after `ng test` (9/9 passing — 5 existing + 4 new), held one real seat through the live stack (`POST /bookings/hold` via api-gateway, not a stub) to produce a genuine non-`AVAILABLE` status, then loaded the real seat-map page in headless Chrome and confirmed via DOM dump + screenshot that exactly one seat rendered amber (`seat-held`, tooltip `Floor-R1-S1 – £89.5 – HELD`) against 649 green ones — proving the live status composition (event-service ← booking-service) actually reaches the rendered SVG, not just that the page loads. The test hold was then cancelled via `POST /bookings/{id}/cancel` to leave demo data clean. Also confirmed the event list's new `routerLink`s produce real `/events/{id}` hrefs in the rendered DOM.

### Angular frontend — Slice 3 (interactive booking flow), done

No backend or gateway changes needed — `POST /bookings/hold`, `GET /bookings/{id}`, `POST /bookings/{id}/confirm`, `POST /bookings/{id}/cancel` all already existed and were already routed through api-gateway's `/bookings/**` prefix. Pure frontend slice.

**New `frontend/src/app/bookings/` folder**: `BookingApiService` (same shape as `EventsApiService`) plus a new `BookingStatusComponent` at `/bookings/:id` — the booking-flow page. `SeatMapComponent`'s `AVAILABLE` seats are now clickable: a click calls `holdSeat`, then navigates to the new booking page on success (or shows an inline error on a real race — someone else took the seat first — without navigating away).

**First use of Angular signals in this codebase**, deliberately scoped to `BookingStatusComponent` only: every other component so far renders a single Observable through the `async` pipe, but this page needs to merge a GET-on-load, a 1-second poll (while `PAYMENT_PENDING`), a live countdown recompute (while `HELD`), and two button-triggered POSTs into one piece of mutable state — signals fit that better than forcing it through one Observable chain. The rest of the app stays Observable-only; this isn't a stack-wide switch.

`confirmBooking` sends a fixed demo `stripePaymentMethodId` (`pm_demo_4242424242424242`, same literal `demo.sh` already uses) — no real Stripe integration exists yet (`ConfirmBookingRequest` only validates `@NotBlank`), and the UI says so explicitly ("Confirm & Pay" is labeled as a stubbed demo charge, no card form). The five `BookingStatus` values all get a distinct rendered state; `CANCELLED` covers both "you released the hold" and "payment failed" since the backend enum can't currently distinguish them.

19 frontend tests passing (up from 9): `booking-api.service.spec.ts` (4, one per method), `booking-status.component.spec.ts` (4 — HELD render, confirm() updates state, CONFIRMED renders the ticket reference, a failed confirm() surfaces an inline error without throwing), `seat-map.component.spec.ts` (+2 — click-to-hold navigates to the booking page, a hold failure shows the inline race-condition message).

**Real end-to-end verification, not just tests**: held a real seat via the same `POST /bookings/hold` call the click handler makes, screenshotted the live `HELD` page showing a real countdown; called the same `confirm()` request the button makes, waited for the real saga (booking-service → Kafka → `payment-simulator` → Kafka → booking-service) to resolve, and screenshotted the same page now showing `CONFIRMED` with a real ticket reference (`TKT-2026-7E7F`); confirmed the seat map for that event now shows the same seat as `BOOKED`. (No browser-automation click tool is wired into this session, so the "click" itself is proven by the Karma test driving a real DOM click event — this verification instead proves the API calls it makes and the resulting rendered states are real, closing the loop the unit test can't reach.)

### Angular frontend — Slice 4 (Tailwind CSS), done

Styling decision documented as `docs/adr/ADR-008-frontend-styling.md`: compared keeping hand-written CSS, Angular Material, and Tailwind CSS: Material's strengths (form fields, data tables, dialogs) don't match this app's shape (mostly custom-rendered — the SVG seat map, status-driven booking flow), and would fight the custom SVG styling; Tailwind styles custom markup directly and is a broadly transferable skill rather than an Angular-specific one. Adopted Tailwind v4.

**Wiring**: `tailwindcss` + `@tailwindcss/postcss` (both real npm-resolved version `4.3.3`, checked rather than assumed — same discipline as the Angular/Node and Spring Cloud version pins). Integration is two files: `frontend/.postcssrc.json` (`{"plugins": {"@tailwindcss/postcss": {}}}`) and `@import "tailwindcss";` in `src/styles.css` — no `angular.json` changes, since Angular's esbuild-based `application` builder (already in use) picks up PostCSS config automatically. Confirmed genuinely wired (not just installed) by watching the compiled `styles.css` grow from a few bytes to 3.79 kB (Tailwind's base layer) on the first build before any utility classes were even used in templates, then to 11.20 kB once the three views actually used them.

**Restyled the three existing views in one pass** (event list, seat map, booking status) — a half-migrated app was judged a worse intermediate state than doing all three together, since these were small mechanical template edits, not new logic. The seat map's status-identifying class names (`seat-available`, `seat-held`, `seat-booked`, `seat-holding`) were kept exactly as-is — both the click-handling logic and the existing Karma tests key off these substrings — with Tailwind utilities (`cursor-pointer`, `hover:opacity-80`, etc.) appended alongside them in `seatClass()`, not replacing them. **No other `.ts` logic changes anywhere in this slice.**

All 19 existing Jasmine tests passed **unmodified** — they assert on rendered text content and the semantic status-class substrings above, never on layout CSS, which is exactly the cross-check this slice's purely-visual nature predicted. Verified live in headless Chrome (same method as the previous three slices): screenshotted the real events list, a real seat map (with an already-`BOOKED` seat from earlier testing still correctly grey), a real `HELD` booking page, and a real `CONFIRMED` booking page — all rendering actual Tailwind spacing/color/typography, not just class names present in markup.

### Angular frontend — Slice 5 (live seat updates via WebSocket), done

Styling/architecture decision documented as `docs/adr/ADR-009-live-seat-updates.md`: compared doing nothing new (poll or reload-to-see), STOMP over WebSocket, and a plain WebSocket. This channel is purely one-directional server push with exactly one destination per connection — STOMP's subscribe/unsubscribe frames and message-broker abstraction solve problems this feature doesn't have, at the cost of a new frontend dependency (`@stomp/stompjs`). Adopted a plain WebSocket: Spring's `TextWebSocketHandler` on the backend, the browser's native `WebSocket` API on the frontend, zero new frontend dependency.

**booking-service owns the broadcast**, not event-service: every seat-status-changing transition already happens inside `BookingService` — `holdSeat` (→HELD), `handlePaymentCompleted` (→BOOKED), `cancelBooking` (→AVAILABLE), `expireHold` (→AVAILABLE), `handlePaymentFailed` (→AVAILABLE). New `websocket/SeatStatusWebSocketHandler` (registered at `/bookings/ws/events/*/seats` via new `config/WebSocketConfig`) keeps a `ConcurrentHashMap<UUID, Set<WebSocketSession>>` per event and broadcasts a small delta (`{seatId, status}`) to every session watching that event — no new Kafka topic or consumer needed purely to re-derive something booking-service already knows synchronously. `spring-boot-starter-websocket` added to `pom.xml` (parent-managed version, confirmed it resolves for Boot 4.1.0 by running `mvn dependency:resolve` before building anything on top of it).

**api-gateway** gained a new route (`Path=/bookings/ws/**` → `booking.service.ws-base-url`, a `ws://` URI) placed *before* the existing `/bookings/**` route, since Spring Cloud Gateway matches routes in declared order and the more specific path must come first. Spring Cloud Gateway auto-detects the `ws://` scheme and proxies the WebSocket upgrade instead of treating it as plain HTTP — proven with a dedicated test using a small Reactor Netty stub server (mirroring `RoutingIntegrationTest`'s JDK `HttpServer` stub for the REST routes) and a real `ReactorNettyWebSocketClient` connecting through the gateway.

**Frontend**: `SeatMapComponent` moved its data source from the async-piped `data$` Observable to a **signal** (`data = signal<SeatMapData | null>(null)`) — same precedent as `BookingStatusComponent` in Slice 3: merging an initial GET with an ongoing stream of live pushes doesn't fit the pure single-Observable/async-pipe pattern the rest of the app uses. On init it still does the same `forkJoin(getEvent, getSeatMap)`, then opens a native `WebSocket` to `${environment.wsBaseUrl}/bookings/ws/events/{eventId}/seats`; `onmessage` immutably patches the one changed seat inside the signal. New `environment.wsBaseUrl` alongside the existing `apiBaseUrl`. `DestroyRef.onDestroy(() => ws.close())` for cleanup on navigation away.

20 frontend tests passing (up from 19): new case stubs the global `WebSocket` constructor, simulates a pushed message, and asserts the corresponding seat's rendered fill/class updates live — while an already-HELD seat from the initial data stays untouched, proving the patch targets only the right seat. Backend: `SeatStatusWebSocketHandlerTest` (3, unit — broadcast only reaches sessions for the matching event, a closed session isn't sent to, an invalid event id in the handshake URI closes the connection) and a new `SeatStatusWebSocketIntegrationTest` (`@SpringBootTest` + a real `StandardWebSocketClient`, asserts a real `POST /bookings/hold` call produces a real received WebSocket message — not a mocked handler call). `BookingServiceTest`'s 26 existing cases needed only a new `@Mock` added, no other changes.

**Real end-to-end verification, not just tests**: rebuilt `booking-service`/`api-gateway` in Docker, then — since no browser-automation tool is wired into this session — used a persistent headless Chrome instance driven directly over the Chrome DevTools Protocol (`--remote-debugging-port`, a small Node script speaking CDP over its own WebSocket) to keep one seat-map page open while issuing a real `POST /bookings/hold` from outside the browser. Queried the *already-open* page's live DOM afterward (no reload, no re-navigation) and confirmed the pushed seat's `<rect>` had genuinely re-rendered — `fill="#ffb300"`, `class="seat seat-held ..."` — proving the push updated an already-rendered page in place, which is the actual feature, not just that a socket connects. Screenshotted the same live session for visual confirmation. Test holds released via `POST /bookings/{id}/cancel` afterward to leave demo data clean.

**Known limitation, documented rather than hidden** (see ADR-009 Consequences): no reconnect/backoff if the WebSocket connection drops — a real production gap accepted at this project's scope.

### Angular frontend — booking countdown timer polish, done

Reading `BookingStatusComponent` before touching it turned up a real bug alongside the cosmetic ask: `tick()` recomputed `remainingSeconds` every second while `HELD` but never noticed when it hit zero — the backend's Redis TTL genuinely expires the hold server-side (`SeatHoldExpiredListener`, built several slices ago), but the frontend just sat on a stale "0:00" HELD view until the user clicked a button and got a confusing error. Fixed: when `updateRemaining` computes `0` while still `HELD`, `tick()` now re-fetches via the same `loadBooking()` already used for the `PAYMENT_PENDING` poll — self-terminating once the real `EXPIRED` status comes back, exactly like that existing poll.

Cosmetic half of the ask: a new `urgency` computed signal (`normal`/`warning`/`critical`, thresholds at 120s/30s remaining) drives both the countdown badge's color (amber → orange → red, with `animate-pulse` in the last 30s) and a new slim progress bar — width driven by a `progressPercent` computed from the booking's *real* `createdAt`/`holdExpiresAt` (not a hardcoded 600s), so it stays accurate even if `booking.hold.ttl-seconds` changes. Tailwind class strings per tier are returned as complete literals from `countdownClasses()`/`progressBarClasses()` (same reason `SeatMapComponent.seatClass()` does this — Tailwind's scanner needs full literal tokens, not concatenated fragments).

22 frontend tests passing (up from 20): a critical-tier render case, and an `fakeAsync`/`tick(1000)`-driven case proving the expiry re-fetch actually happens — the first deliberate use of Angular's fake timers in this codebase, justified here because the 1-second tick under test is the component's *own* internal clock, not external saga timing (the earlier "don't fake-timer the polling" note was about not simulating the backend's saga speed, a different concern). Verified live against the real stack too: held a real seat, screenshotted the booking page showing the real countdown (9:38 remaining) and an accurately ~96%-full amber progress bar against real `holdExpiresAt`/`createdAt` values from the backend.

### Angular frontend — site chrome, 404, error states, done

Picked "site chrome & shared header" from a short menu of polish options. Reading the app before touching it turned up a real bug alongside the cosmetic ask: `EventListComponent`'s `eventPage$` was consumed via the `async` pipe with no error handling, and `SeatMapComponent`'s `forkJoin(...).subscribe(callback)` used the next-only shorthand — both would sit on their "Loading…" text forever if the backend call failed, no error ever surfacing. `BookingStatusComponent` already got this right when it was built. Converted both to the same signal-driven `{ next, error }` pattern already established there (`eventPage`/`loadError` and `data`/`loadError` signals respectively) — this *is* the practically meaningful "error boundary" for an app whose only real failure mode is an HTTP call not coming back; there's no scenario here needing a JS-exception-catching global `ErrorHandler`, so that wasn't added.

Also added: a persistent `<header>` in `app.component.html` rendering the site title ("Event Ticketing", matching this doc's own project name — no invented brand) linked back to `/` — `AppComponent.title` was a leftover CLI-scaffold property that was never actually rendered before now. `index.html`'s `<title>` updated to match. New `NotFoundComponent` + a wildcard `{ path: '**', ... }` route (must trail the real routes — Angular Router matches in declared order) — previously an unknown path just left the router outlet empty, a blank page with no route matched at all.

26 frontend tests passing (up from 22): new `not-found.component.spec.ts`, `app.component.spec.ts` gained a real DOM-rendering check for the header (the old test only checked the property, not that it was ever displayed), and one new error-case test each for `EventListComponent`/`SeatMapComponent` confirming the error message renders instead of an infinite loading state. Verified live: screenshotted the real events list with the header, and a direct navigation to a nonsense path confirming the real 404 page renders (previously verified as a blank screen before this fix).

### Angular frontend — events list polish (badges, skeleton, real pagination), done

Third item from the frontend-polish menu. `Event.category` existed on the model since Slice 1 but was never rendered — added a small pill badge next to each event's title. The plain "Loading events…" text became a 3-card `animate-pulse` skeleton shaped like the real cards, so the layout doesn't jump when data arrives.

Pagination was the substantive part: `event-service`'s `GET /events` already supported `page`/`size` (confirmed by reading `EventController.listEvents`), and `EventPage` already carried `page`/`totalPages`/`totalElements` on every response — the frontend had simply never sent or used any of it, always showing page 0 regardless of how much data existed. `EventsApiService.listEvents(page = 0)` now requests `size=10` (deliberately smaller than the backend's own default of 20 — a card-per-row list is a lot of scrolling at 20, and it makes pagination demonstrable without needing a lot of demo data). `EventListComponent` gained `nextPage()`/`previousPage()`, bounds-guarded against `eventPage().totalPages`; Previous/Next controls only render at all when `totalPages > 1`.

30 frontend tests passing (up from 26): category badge rendering, the skeleton appearing instead of old loading text (stubbed with a never-emitting `Subject`), pagination controls hidden for a single page, and a real click on "Next" asserted to call `listEvents(1)`. **Real verification, not just tests**: the live demo DB only had 5 events (under one page at size 10), so created 9 more real events via the actual API to genuinely cross the page boundary (14 total → 2 real pages) — then, since no browser-automation tool is wired into this session, used a persistent headless Chrome driven over the Chrome DevTools Protocol (same technique as the WebSocket live-updates slice) to actually click the real "Next" button and confirm the page state updated to "Page 2 of 2" only after the real async HTTP call resolved, with genuinely different events (and their category badges) rendered on the second page — not a static markup check.

### payment-service — payment-simulator retired, wired into the live saga, done

Closes the last item on this list. `payment-simulator` (one Kafka consumer, no persistence, no real logic — always a scaffolding device, never a real service from ADR-001's count) is deleted entirely from the repo. `payment-service` is now the sole live-saga participant.

The retirement changed the saga's actual timing, not just which service is "really" running it: `payment-simulator` auto-completed a payment 600ms after `payment-initiated` with no webhook step at all, so a booking reached `CONFIRMED` almost immediately after `confirm`. `payment-service`'s real flow is genuinely two-phase — `payment-initiated` only creates a `PENDING` payment; an explicit `POST /payments/webhook` call (standing in for Stripe's real async callback) is what transitions it to `SUCCEEDED` and publishes `payment-completed`. `demo.sh` already exercised this exact webhook path, just previously as a redundant *second* check (old Steps 5-7) running after the simulator had already finished the saga in Step 4. Retiring the simulator meant restructuring `demo.sh` into one real two-phase flow instead of two parallel ones: hold → confirm → **poll payment-service's PENDING payment** → **webhook call (now the thing that actually finalizes the booking)** → poll booking `CONFIRMED` → ... (16 steps become 15). Verified for real, not assumed: the booking now demonstrably stays `PAYMENT_PENDING` through the payment-lookup step and only flips to `CONFIRMED` after the webhook call — the concrete proof the simulator's shortcut is gone.

`payment-service`'s consumer group for both `PaymentInitiatedConsumer` and `BookingCancelledConsumer` moved from `payment-service-consumer-group` to the AsyncAPI-spec-literal `payment-service-group`, now that `payment-simulator` (which held that name to avoid a partition-split collision while both coexisted) no longer exists — `docs/plan.md` had already committed to this reconciliation "once payment-simulator is retired." `docker/docker-compose.yml`'s `payment-simulator` block is removed; no other service depended on it.

No new backend logic, so no new tests — the existing `payment-service` suite (32 tests: repository/service/controller/2 saga integration tests) already covers `initiatePayment`/`handleWebhook`/`refundForCancelledBooking`, and a full re-run after the groupId rename confirmed nothing broke. Real verification was the actual point of this slice: rebuilt `payment-service`, brought the stack up with `--remove-orphans` (confirmed via `docker compose ps` that no `payment-simulator` container exists anymore), and ran the full restructured `demo.sh` end-to-end — all 15 steps passed, including the real refund check (Step 15) now depending on a payment that only became `SUCCEEDED` through the real webhook path.

**Phase 3 (core backend) is now fully wired end-to-end** — no more "independent side-checks" standing in for real saga participation anywhere in the six services.

### Real bug found via manual testing — stale `Event.availableSeats`, fixed with a batch composition endpoint

**Found by the user manually testing the frontend, not by any automated test**: the events list showed "650/650 seats available" for an event whose own seat map already had a seat `BOOKED`. Worth stating plainly since it's the kind of gap automated coverage alone doesn't catch — every test up to this point exercised each endpoint in isolation, and nothing cross-checked that the events list and the seat map agreed with each other about the same event.

**Root cause**, confirmed by reading the code rather than assumed: `Event.availableSeats` is a plain database column set exactly once — `event.setAvailableSeats(venue.getCapacity())` in `EventService.createEvent` — and never updated anywhere else in the codebase (a full-repo search turned up zero other call sites). `EventResponse.from(event)`, used by both `GET /events/{id}` and `GET /events`, simply echoed this permanently-stale, day-one snapshot regardless of any real booking activity. The seat map never had this problem, because `getSeatMap` already composed live status by calling booking-service's `GET /bookings/events/{eventId}/active-seats` on every request — the events list just never did the equivalent.

**Fix, documented as `docs/adr/ADR-010-event-availability-batch-composition.md`**: rather than calling the existing per-event active-seats endpoint once per event on a list page (a real N+1 pattern), added a **new batch endpoint** on booking-service — `GET /bookings/events/active-seats/counts?eventIds=a,b,c` → one active-seat count per requested event (`BookingRepository.findByEventIdInAndStatusIn`, grouped and counted in `BookingService.getActiveSeatCounts`, defaulting missing events to `0` rather than omitting them). `event-service`'s `EventService` now calls this once per `GET /events` or `GET /events/{id}` request (`applyLiveAvailability`, reusing the batch call even for a single event via a one-element list) and overrides `EventResponse.availableSeats` with `totalSeats - activeCount`. Deliberately **fails hard** rather than silently falling back to the stale number if booking-service is unreachable — the same "no silent degrade to a wrong number" choice `getSeatMap` already made; ADR-010 states plainly that this adds a new failure-mode coupling (a booking-service outage now also breaks the events list, not just the seat map) rather than burying it.

21 new/updated tests: booking-service — `BookingRepositoryTest` (+1, the new `In` query), `BookingServiceTest` (+2, counts across multiple events with one defaulting to zero; empty input), `BookingControllerTest` (+1, real endpoint round trip). event-service — `EventServiceTest` (+4: live composition on `getEvent`/`listEvents`, zero-active-bookings case, `BookingServiceUnavailableException` propagates instead of degrading silently), and a **new** `EventAvailabilityIntegrationTest` (Testcontainers Postgres + a mocked `BookingServiceClient`, real HTTP round trips via `TestRestTemplate`) proving `GET /events` and `GET /events/{id}` both compose real numbers end-to-end — this is the test that turns the user's exact bug report into a permanent regression check.

**Real end-to-end verification, turning the bug report into the literal test case**: rebuilt both services in Docker, confirmed the real demo event's `GET /events` entry already showed the correct `649` (matching its one real `BOOKED` seat from earlier verification sessions — previously this would have read `650`), then held a fresh real seat and watched `availableSeats` drop live from `649` to `648` on both `GET /events` and `GET /events/{id}`, then released the hold and watched it return to `649` — proving the fix tracks real state in both directions, not just at read time.

The stored `Event.availableSeats` database column is now vestigial (still written once at creation, never read for any response) — left in place rather than migrated away, since removing it is an unrelated schema concern out of scope for this fix; noted in ADR-010 so it doesn't read as an oversight later.

### Angular frontend — seat map polish (price display + mobile viewport fix), done

Last item from the frontend-polish menu. Price display needed no new API call: `EventService.generateSeats` already sets `seat.setPriceGbp(section.getPriceGbp())` on the backend, so every seat within a section already shares that section's price — `SeatMapComponent`'s existing `groupBySection` just reads `sectionSeats[0].priceGbp` and a new `SeatSection.pricePerSeat` field renders it next to each section heading.

The mobile-viewport check surfaced a real, confirmed bug, not a hypothetical: each section's `<svg>` had explicit pixel `width`/`height` attributes (440px for "Floor", 660px for "Upper Tier" at this project's real demo venue) which don't shrink to fit a narrower parent — combined with the page's `max-w-3xl px-4` content wrapper having no horizontal-scroll containment on its children, the **whole page** gained horizontal scroll on a real mobile viewport, not just the seat grid. Fixed by wrapping each section's `<svg>` in an `overflow-x-auto` container — the same "wide content scrolls in its own box, the page body never does" idiom used elsewhere — rather than shrinking seats to fit (which would make them illegible/hard to tap on a small screen; real ticketing apps scroll or zoom a seat map rather than shrink it).

32 frontend tests passing (up from 30): section headings render their price, and each section's SVG is structurally wrapped in an `.overflow-x-auto` container. **Real verification, not just tests**: screenshotted the real seat map at desktop width (confirms real prices — "Floor — £89.5", "Upper Tier — £45" — against live backend data), then precisely checked the actual bug at a true 390px mobile viewport via Chrome DevTools Protocol device emulation (`Emulation.setDeviceMetricsOverride`, since the `--window-size` CLI flag doesn't map 1:1 to CSS viewport width in this session's headless Chrome) — confirmed `document.documentElement.scrollWidth (390) === window.innerWidth (390)`, i.e. genuinely zero page-level horizontal overflow, while each section's own scrollable box correctly contains its wider content (440px/660px content in a 358px box) instead of leaking out. Screenshotted the same real mobile view for visual confirmation (title wraps normally, a scrollbar is visible under the Floor section).

**This closes out every item from the frontend-polish menu** — remaining known-open items are the deliberately-deferred backend stubs (real Stripe SDK, real email provider) and Phase 5 (DevOps), not yet started.

### payment-service — stub gateway simulates Stripe's own webhook delivery, fixing a real stuck-forever booking bug

**Found by the user manually testing the frontend, not by any automated test**: clicking "Confirm & Pay" sent a booking to `PAYMENT_PENDING` and it never resolved. Root-caused by reading the code and reproducing live against the running stack: `POST /bookings/{id}/confirm` only publishes `payment-initiated`; nothing else ever calls `POST /payments/webhook` to finalize the payment, and a full-repo search of the frontend for `"webhook"` returned zero matches. Worse than just stuck — `cancelBooking` only accepts `HELD`/`CONFIRMED` bookings and the Redis hold-expiry listener only watches `HELD` bookings, so a `PAYMENT_PENDING` booking had no recovery path at all.

This only surfaced now because the `payment-simulator` retirement slice (directly above) made `payment-service`'s real two-phase, webhook-driven flow the sole saga path for the first time — until then, `payment-simulator` auto-completed the saga without any webhook involved, so nobody noticed the webhook was never wired into the frontend.

**Fix, documented as `docs/adr/ADR-011-stub-gateway-webhook-simulation.md`**: rather than having the frontend call the webhook itself (architecturally backwards — a webhook models an external party calling *your* backend, and it would leak payment-service internals into a frontend that today correctly never talks to payment-service at all), `StubPaymentGateway` now simulates Stripe's own webhook delivery. After `createCharge` returns the `PENDING` PaymentIntent, a new collaborator bean `StripeWebhookSimulator` (kept separate from `StubPaymentGateway` deliberately — Spring's `@Async` proxy cannot intercept a self-invoked call within the same bean, so calling it from `createCharge` on `this` would silently run synchronously and block the Kafka consumer thread) sleeps ~1 second, then makes a genuine HTTP `POST` to this service's own `/payments/webhook` endpoint with `Stripe-Signature: t=stub,v1=stub_signature` and `{"type": "payment_intent.succeeded", "paymentIntentId": ...}` — going through the real controller, validation, and idempotency guard, not a direct Java call. `spring-boot-restclient` moved from test-only to a main-scope dependency for this.

**A real ordering bug caught during implementation, not by the test suite**: the first attempt resolved the target port via `@Value("${server.port}")` at bean-construction time, which works fine at the fixed runtime port (8083) but resolves to literal `0` under `@SpringBootTest(webEnvironment = RANDOM_PORT)`, producing `Cannot assign requested address` in every integration test. Switching to `@Value("${local.server.port}")` (the property Spring Boot publishes with the real bound port) didn't fix it either — `local.server.port` isn't set until `ServletWebServerApplicationContext.finishRefresh()` starts the actual web server, which runs *after* singleton beans are eagerly constructed in `finishBeanFactoryInitialization()`, so the placeholder can't resolve at construction time at all (`PlaceholderResolutionException`) regardless of which port property is named. Fixed by resolving the port lazily inside `simulateWebhookDelivery` itself (via an injected `Environment`, after the 1-second sleep) rather than at construction — by the time any webhook simulation actually runs, a real request has already been processed, so the server is unquestionably up by then.

New test: `PaymentSagaIntegrationTest.paymentInitiated_withNoManualWebhookCall_stillReachesSucceededOnItsOwn` — publishes `payment-initiated` over real Kafka and awaits `SUCCEEDED` with **zero** manual webhook calls anywhere in the test, the exact scenario that was broken. All 4 saga integration cases (this new one plus the 3 pre-existing) still pass, including the two that manually POST a webhook — the stub's own auto-delivery now races with them in the background, but `PaymentService.handleWebhook`'s existing idempotency guard (no-ops on any non-`PENDING` payment) means whichever call lands first wins and the second is a safe no-op, so `publishPaymentCompleted`/`publishPaymentFailed` still fire exactly once per test either way. `demo.sh` Steps 3 and 4 renarrated rather than restructured: Step 3 now notes the payment may already read `SUCCEEDED` by the time it's checked; Step 4's manual webhook call is reframed from "what finalizes the saga" to "proves the endpoint is safe against Stripe's real-world at-least-once redelivery guarantee" (it now almost always hits the idempotency guard instead of being the thing that completes the booking).

**Real end-to-end verification, reproducing the user's exact original bug report**: rebuilt `payment-service` in Docker, held a real seat, confirmed it, and — with zero manual webhook calls — watched the booking resolve on its own from `PAYMENT_PENDING` to `CONFIRMED` with a real ticket reference (`TKT-2026-...`) within about a second. Then ran the full restructured `demo.sh` end-to-end against the live stack; all 15 steps passed, including Step 15's refund check, which now depends on a payment that reached `SUCCEEDED` via the stub's own self-delivered webhook rather than the script's manual call.

### event-service — reusable demo-catalog reseed script, done

**Found by the user looking at the frontend, not by any automated test**: the events list was mostly one event ("Coldplay: Music of the Spheres Tour") repeated many times, plus nine generic "Pagination Test Event N" placeholders left over from the earlier pagination-polish slice — 15 events total, 14 of them not meaningfully distinct. Root cause: `demo.sh` deliberately creates a fresh venue + event on every run (it's exercising the real create flow, not just reading), so repeated verification runs across this project's many sessions had quietly accumulated duplicates in the live dev database.

New `seed-events.sh` at the repo root: truncates event-service's `venues`/`sections`/`events`/`seats` tables directly via `docker exec psql` (event-service has no `DELETE` endpoint — nothing in the real API surface needs one — and this is the same "pure disposable demo data, no real loss" precedent already used earlier in this project for the same database), then creates 10 varied real events through the actual `POST /venues`/`POST /events` APIs — 3 concerts, 3 sports, 2 theatre, 1 comedy, 1 other, spread across London/Manchester/Birmingham with distinct venues, section layouts, and pricing. Booking-service is untouched by the truncate — any historical booking referencing an old event/seat id just becomes orphaned demo history, which is harmless since nothing re-validates a `CONFIRMED`/`CANCELLED` booking's event after the fact.

**Worth remembering**: running `demo.sh` after `seed-events.sh` adds one more "Coldplay" event back (its own create-flow test step) — this is expected, not a regression. Confirmed live: ran `demo.sh` once after seeding for verification, which did exactly this; the resulting duplicate (plus its now-orphaned venue row) was removed with two direct `DELETE`s, and `seed-events.sh` is the documented way to reset back to the clean 10 whenever it recurs.

No new automated tests — this is dev/demo tooling, not application logic. Verified by reading the reseeded catalog back via `GET /events` (10 distinct titles, no duplicates) and by screenshotting the real frontend events list via a headless-Chrome CDP session, confirming genuine variety (titles, category badges, cities, per-event seat counts) rendered from live backend data.

### 2026-08-03 — demo.sh / seed-events.sh ported to Node (demo.js / seed-events.js), done

Surfaced by the user running the project on Windows Command Prompt: `demo.sh` and `seed-events.sh` are bash scripts (`grep -o`, `cut -d`, `sed -n`, `./` execution syntax) with no native cmd.exe equivalent — they only ran via Git Bash/WSL. Rather than just documenting the Git Bash workaround, ported both scripts to plain Node (`demo.js`, `seed-events.js`, no dependencies — Node 18+'s built-in `fetch` covers every HTTP call; `seed-events.js` shells out to `docker exec ... psql` for the truncate via `child_process.execFileSync`, same as the bash version did). Node was already a hard requirement for the frontend, so this removes the Windows-specific tooling gap without adding a new toolchain dependency. The old `.sh` files were deleted rather than kept alongside the `.js` versions, to avoid two implementations drifting out of sync.

Not an architectural change — pure dev-tooling portability, no ADR. No automated tests (same category as the original scripts). Verified for real: ran `node seed-events.js` against the live stack (truncated + recreated all 13 events, `totalElements: 13` confirmed), then `node demo.js` end-to-end (all 15 steps produced the same output shape as the bash version, full saga reached `CONFIRMED` → cancelled → refunded → waitlist promoted), then re-ran `node seed-events.js` to clean up the one extra "Coldplay" event `demo.js` adds by design (same documented behavior as the old `demo.sh` had).

### 2026-07-31 — 3 more seed events, done

Added 3 more events (10 → 13) purely so the frontend's real pagination (added in the events-list-polish slice above, `size=10`) has a second page to actually land on during manual testing — 10 events exactly filled page 1 with nothing on page 2. Extended `seed-events.sh` (bash, pre-Node-port) rather than `demo.sh`, since these are catalog seed data, not saga-flow steps. Along the way, found and fixed a real bug: an accented character ("é") in one seed event's title broke event-service's JSON parsing when submitted via Git Bash/curl, due to encoding handling in that shell path — not an event-service bug itself, worked around in the seed script's invocation.

No new automated tests — dev/demo tooling, same category as the rest of `seed-events.sh`. Verified by reseeding and confirming `GET /events` returned all 13 with two real pages in the frontend.

### 2026-07-31 — Angular frontend visual makeover (violet/fuchsia design system), done

First full visual redesign pass across every view (events list, seat map, booking status, app shell, 404) — purely presentational, no logic changes. Violet/fuchsia gradient design system, Manrope font, a hero band on the events list, hand-authored SVG category icons, and restyled card layouts throughout. All 32 existing frontend tests passed unmodified, consistent with this being a pure-CSS/template pass with no status-class or copy changes.

**Superseded three days later** by the Night Market redesign below — this makeover tested badly with the user ("does not appeal to me... for most of the userbase") once seen live rather than just built. Left as a dated entry here for the historical record rather than erased, since it was a real, deliberately-built (if short-lived) slice.

### 2026-08-03 — Angular frontend "Night Market" redesign, done

Replaces the violet/fuchsia makeover above after the user rejected it on sight. Process change adopted here and going forward for subjective/visual work: before touching real code, three distinct full-mockup directions (Marquee/vintage-theatre, Box Office/functional-ledger, Night Market/festival-poster-wall) were built and published as a Claude Artifact for the user to compare side-by-side; user picked **Night Market**. A follow-up round then mocked three seat-map framing variants specifically (Halftone Stamp, Colour-Blocked Sections, Ticket-Stub Accordion) the same way; user picked **Stub Accordion**. Both rounds were also smoke-tested live in the browser before implementation, not just eyeballed as static mockups.

Implemented across every view: dark aubergine ground, category-colored rotated poster tiles, Impact display font, a stub-accordion seat map (first section open by default, others collapsed, real Angular signal-based toggle state — not just CSS `:hover`/`:checked`), pink stamp badges for booking states that escalate to amber/red as a hold nears expiry, a poster-block treatment for `CONFIRMED`, and a dashed rotated-card 404 page. Design tokens centralized via Tailwind v4's `@theme` in `styles.css` rather than scattered literal color classes, so the palette has one source of truth if it needs to change again.

Also fixed in passing: the events grid renders 3 columns, but page size was still the `size=10` set in the earlier pagination slice — 10 doesn't divide evenly into rows of 3, so the last row of a page was always partially empty. Changed to `size=9` so every full page lands as clean rows.

All 32 existing frontend tests kept passing through the redesign; a handful of tests asserting exact copy/color-class strings tied to the old design were deliberately updated to match the new intentional copy/colors, each flagged individually rather than silently changed. Browser extension automation wasn't connected this session, so verification used headless Chrome CLI screenshots plus raw CDP WebSocket scripts instead (device-metrics override for mobile viewport checks, `Runtime.evaluate` to confirm the accordion's signal-driven toggle actually fires and that Tailwind's `rotate` utility was genuinely applying despite `getComputedStyle(...).transform` reading `'none'`).

**This closes out Phase 4** — no functional or visual work remains queued for the frontend; the phase table above now reflects Done.

---

## Phase 5 progress

### 2026-07-31 — metrics observability stack (Actuator + Micrometer + Prometheus + Grafana), done

First Phase 5 slice, documented as `docs/adr/ADR-012-observability-metrics-stack.md`. Spring Boot Actuator + Micrometer wired into all 6 services, scraped by Prometheus, visualized in an auto-provisioned Grafana dashboard ("Ticketing Platform Overview"). Verified live in-browser against real data produced by a full `demo.sh` run, not synthetic/sample data.

Deliberately deferred as separate future slices rather than bundled in: distributed tracing (Micrometer Tracing + Zipkin, propagating trace context through Kafka headers across the saga — flagged as higher blast radius since it touches all six services, which is why CI/CD is sequenced ahead of it), Kafka broker/consumer-lag metrics, centralized logging, K8s health-probe tuning, alerting.

### 2026-08-04 — seed-events.js waits for event-service health before seeding, done

Small reliability fix, not a new slice: `seed-events.js` (the Node port from the Phase 4 tooling arc above) could hit event-service right as its container started but before the Spring app context was fully up, producing a raw socket-reset error instead of a useful message. Now polls `/actuator/health` first — made possible by the Actuator dependency the metrics slice above already added to every service — and fails with a clear timeout message if the service never comes up, instead of a confusing low-level connection error.

### 2026-08-26 — demo.js Step 5 race condition, fixed

**Found by actually re-running `demo.js` against the live stack, not by reading the code alone**: Step 5's own printed "Result" summary reported `PAYMENT_PENDING` with a WARN, even though the saga had genuinely succeeded — every step after it (Step 7's notification already carrying the real ticket reference, Step 9 successfully cancelling a `CONFIRMED`-only-eligible booking) proved the booking really did reach `CONFIRMED` moments later.

**Root cause**: unlike Steps 3, 7, 10, 11, and 12, Step 5 read the booking with a single one-shot `call()` instead of the script's own `poll()` helper — so it checked before the async chain (webhook → `payment-completed` on Kafka → booking-service's consumer → `CONFIRMED`) had finished, a pure timing race in the script itself, not a backend bug. Fixed by wrapping Step 5's read in `poll()` with `isDone: res => res.json?.status === "CONFIRMED"`, matching the pattern already used everywhere else in the script.

Verified by re-running `node seed-events.js && node demo.js` twice end-to-end post-fix: all 15 steps report `SUCCESS` with zero `WARN`/`ERROR` lines, `Status: CONFIRMED` with a real ticket reference every time.

### 2026-08-28 — Angular frontend upgraded 20 → 21, Karma/Jasmine replaced with Vitest, done

Triggered by a question about whether Karma/Jasmine (still in place since the 19→20 upgrade) should be replaced now that Angular 20 ships newer alternatives. Decision documented as `docs/adr/ADR-013-angular-21-upgrade-and-vitest-migration.md`: rather than hand-migrating the test runner in isolation, took the Angular 21 major (latest stable at the time, `21.2.22`, checked directly against the npm registry rather than assumed — `22.1.4` was also already stable, but the ADR documents why 21 was kept over 22: v22 forces an unrequested TypeScript v6 bump this project's 5.9.3 doesn't meet, and had only ~3 months of real-world mileage versus v21's ~9). v21 ships an official Vitest-based `unit-test` builder, turning the already-desired Karma removal into a first-party migration bundled into the same upgrade instead of two separate ones.

**Peer-dependency chain checked before starting, not assumed**: Node (`^20.19.0 || ^22.12.0 || >=24.0.0`, this machine's v24.19.0 already satisfies it), TypeScript (`>=5.9 <6.1` per `@angular/compiler-cli`, already on 5.9.3), RxJS/zone.js ranges — all already satisfied, so `ng update @angular/cli@21 @angular/core@21` needed no other toolchain changes. Both optional migrations it offered (`use-application-builder`, `router-current-navigation`) were declined again, same reasoning as the 19→20 upgrade (already on the esbuild application builder; `Router.getCurrentNavigation` still unused anywhere — reconfirmed via a fresh grep, not just recalled from last time).

**No automated schematic exists yet for the Karma→Vitest builder swap itself** (a third-party blog claimed `ng generate @angular/core:karma-to-vitest`, which turned out not to exist in the installed collection — verified by listing `@angular/core`'s and `@schematics/angular`'s actual schematic names rather than trusting the claim). Done by hand instead: `angular.json`'s `test` target builder changed from `@angular-devkit/build-angular:karma` to `@angular/build:unit-test` (`runner: "vitest"`, `buildTarget: "::development"`); `package.json` dropped `@types/jasmine`/`jasmine-core`/`karma`/`karma-*` and added `vitest`/`jsdom` (the builder's own error message named jsdom as the missing piece once run); `tsconfig.spec.json`'s `types` changed from `["jasmine"]` to `["vitest/globals"]`. Also switched the `build` target's builder from `@angular-devkit/build-angular:application` to `@angular/build:application` — confirmed by reading `@angular-devkit/build-angular`'s own `builders.json` that the former is a pure string-alias to the latter, but the `unit-test` builder's compatibility check does a literal name match and warned until switched.

**Real Jasmine-specific API usage caught by actually running the tests, not by the schematic** (there being no schematic to catch it): `jasmine.createSpy().and.returnValue(...)` → `vi.fn().mockReturnValue(...)` (5 call sites), `jasmine.createSpy().and.returnValues(a, b)` → `vi.fn().mockReturnValueOnce(a).mockReturnValueOnce(b)` (1), bare `spyOn(...)` → `vi.spyOn(...)` (1), `.toBeTrue()`/`.toBeFalse()` → `.toBe(true)`/`.toBe(false)` (3) — Vitest's `expect` doesn't ship Jasmine's boolean-specific matchers. One test used zone.js's `fakeAsync`/`tick`, which turned out to be a documented, currently-unsupported combination with the Vitest runner (confirmed against `angular/angular#66150`, an open upstream issue, rather than assumed to be a local misconfiguration) — rewritten to use `vi.useFakeTimers()`/`vi.advanceTimersByTime(1000)` instead, since the component's countdown runs on a plain RxJS `interval(1000)` that fake timers can intercept the same way.

All 32 existing frontend tests pass unchanged in substance (updated only for the Jasmine→Vitest API swap above — no test assertions or component code touched). `ng build` and `npm audit` both clean (0 vulnerabilities). Verified live: `ng serve` on a scratch port served `HTTP 200` and a clean production bundle, confirming the new `@angular/build:application`/`dev-server` combination works, not just the test builder.

### 2026-08-28 — Angular frontend migrated to zoneless change detection, done

The zoneless follow-up ADR-013 deliberately deferred. Documented as `docs/adr/ADR-013-angular-21-upgrade-and-vitest-migration.md`'s sibling, `docs/adr/ADR-014-zoneless-change-detection.md`.

**Codebase audit before touching anything**: `EventListComponent` and `BookingStatusComponent` turned out to already be 100% signal-driven (including `BookingStatusComponent`'s RxJS `interval(1000)` countdown — the timer itself doesn't need to be zone-patched, since signal writes notify Angular's scheduler directly regardless of what scheduled the callback). `SeatMapComponent` was almost there too, except for two plain instance fields — `holdingSeatId`/`holdError` — mutated directly inside the async `holdSeat(...).subscribe({ error })` callback and read straight in the template. That only worked before because zone.js patched the HTTP callback's async boundary; zoneless would have silently stopped re-rendering `holdError` on a real seat-hold conflict. Converted both to signals as a required correctness fix, not optional polish — this was a real latent bug the audit surfaced, not a hypothetical.

No automated migration schematic exists for this either (a claimed `ng generate @angular/core:zoneless-migration` was checked against the installed `@angular/core` collection the same way ADR-013 checked and disproved a similarly-claimed Karma→Vitest schematic, and it isn't there) — done by hand: `app.config.ts`'s `provideZoneChangeDetection(...)` → `provideZonelessChangeDetection()`, `zone.js` dropped from `angular.json`'s build polyfills and from `package.json`. `OnPush` was deliberately left off every component (ADR-014's Option B over C) — pure polish with no observable effect at this app's size, kept as a separate possible future slice rather than bundled in.

32/32 existing tests pass unchanged (no test referenced `NgZone` or zone-patched timing beyond the one `fakeAsync` case ADR-013 already replaced with `vi.useFakeTimers()`). Bundle got smaller too, not just structurally cleaner: the zone.js polyfill chunk is gone entirely, dropping the initial production bundle from 327.74 kB to 290.24 kB raw (89.84 kB → 78.05 kB estimated transfer).

**Real end-to-end verification, not just tests — again via headless Chrome + raw CDP** (extension not connected this session): confirmed `window.Zone === undefined` in the live running app, then exercised the two riskiest paths against the real backend stack (which needed `docker compose up -d` first — `postgres`/`redis`/`zookeeper`/`kafka` had exited since the last session and `event-service` was 500ing on the composed-availability call to a not-yet-started `booking-service`, both resolved by bringing the full stack up and letting it finish its ~95s cold-start). A real synthetic click on a genuinely `AVAILABLE` seat drove the actual happy path (`POST /bookings/hold` → success → `router.navigate` → real booking page with a live "9:59" countdown) with zero zone.js involved. For the conflict path specifically — the one behavior the signal conversion above exists to fix — held a real seat via a direct API call first, then used Angular's `window.ng.getComponent()` dev-mode helper to invoke `SeatMapComponent.selectSeat()` directly with a deliberately stale "AVAILABLE" seat object (the real UI can't reproduce this race through a click once the map correctly re-renders the seat as `HELD`, so this reproduces the same concurrent-user race a click normally guards against): the real backend returned a real `409`, and `"That seat was just taken by someone else — please pick another."` rendered correctly — proving the exact zoneless-risk path this ADR called out actually works. Both test bookings were cancelled afterward (`POST /bookings/{id}/cancel`) to leave demo data clean.

### 2026-09-29 — CI pipeline (GitHub Actions) with local parity, plus dev/prod environment decision

Two requirements shaped this slice beyond "have a pipeline": it must be testable **locally and in the cloud**, and it must stay **free**. Documented as `docs/adr/ADR-015-ci-pipeline-and-local-testing.md` (six local-testing options weighed, A–F).

**Chosen: one entry point, `ci.js`** (repo root, dependency-free Node like `seed-events.js`). `node ci.js <service> [--docker]` runs `mvn -B -ntp verify` (+ `docker build` of that service's Dockerfile); `node ci.js frontend` runs `npm ci`, `ng test --watch=false`, `ng build`; `services`/`all` run everything. `.github/workflows/ci.yml` runs *exactly* these commands per job, so local/CI parity holds by construction instead of two definitions kept in sync by hand.

**Cost facts checked, not assumed**: the GitHub repo is private (GitHub Free: 2,000 Actions min/month, Actions stops at quota rather than billing without a payment method). Local Testcontainers runs go through Testcontainers Desktop (`tc.host` in `~/.testcontainers.properties` points at its proxy in either mode). Only its Cloud runtime is subject to the 50 min/month cap noted under Outstanding housekeeping, so ADR-015 option D standardises CI-like local runs on Desktop's local Docker runtime. In the workflow: `dorny/paths-filter` per target (a booking-service-only change runs only booking-service; a change to `ci.js`/the workflow runs everything), `concurrency` with `cancel-in-progress`, Maven/npm caching, no image push, Surefire reports uploaded only on failure. Testcontainers in CI uses the runner's own Docker.

One real issue found by running it: Node 24 emits `DEP0190` when an args array is combined with `shell: true` (needed on Windows for the `mvn`/`npm` `.cmd` shims). Fixed by passing a single joined command string to the shell on Windows only.

**Verified locally** (Windows): `node ci.js api-gateway --docker` → 7/7 tests + Docker image build, PASS; `node ci.js frontend` → 32/32 tests + production build, PASS. **Negative test**: deliberately broke one assertion in `RoutingIntegrationTest` → `ci.js` exited 1 and named `api-gateway` as the failed target; the change was reverted. `node ci.js services` with Testcontainers Desktop on the local Docker runtime, confirmed via `testcontainers/ryuk` and fresh `postgres:15-alpine` containers appearing in local `docker ps`: all six PASS, 201 tests total. Per service:

| Service | Tests | Time |
|---|---|---|
| api-gateway | 7 | 36s |
| event-service | 35 | 116s |
| booking-service | 68 | 202s |
| payment-service | 33 | 120s |
| notification-service | 35 | 97s |
| waitlist-service | 23 | 94s |

**First real GitHub Actions run** (run `36619557282`, push of `49485e1`): green, **3m24s wall-clock**. Every target ran because the workflow file itself was new, as intended. The logs confirm that Testcontainers connected to the runner's own Docker, with no Testcontainers Cloud involved.

| Job | Tests | Duration | Billed |
|---|---|---|---|
| changes | — | 0:08 | 1 min |
| api-gateway | 7 | 1:25 | 2 min |
| event-service | 35 | 2:24 | 3 min |
| booking-service | 68 | 2:59 | 3 min |
| payment-service | 33 | 3:02 | 4 min |
| notification-service | 35 | 3:09 | 4 min |
| waitlist-service | 23 | 2:50 | 3 min |
| frontend | 32 | 0:28 | 1 min |

GitHub rounds each job up to the full minute. A full run therefore costs **~21 of the 2,000 free minutes**, below the ~25 estimated in ADR-015. A typical single-service change costs about 4–5 minutes (the `changes` job plus one service).

**Dev/prod environment decision** (`docs/adr/ADR-016-environments-dev-prod.md`, decision only — no code in this slice): LocalStack and Floci were considered for dev (vendor pages checked 2026-09-29):
- LocalStack's only free plan since 2026.03 is non-commercial *Hobby*, which excludes RDS/ECS/ElastiCache/EKS/MSK.
- Floci (MIT, no token) does emulate all five with real engine containers.
- Neither was chosen: the services call no AWS APIs, and an AWS prod (MSK, EKS control plane, Fargate) isn't free. Floci is kept as the documented fallback if prod ever moves to AWS.
- Oracle's Always Free A1 allowance was halved to 2 OCPU / 12 GB on 2026-06-15. That still fits the stack, but the prod VM is arm64, which means multi-arch images. GCP Cloud Run is also dropped: scale-to-zero breaks Kafka consumers, and Cloud SQL/Memorystore aren't free. **Dev = local Kubernetes on Rancher Desktop, prod = k3s on a free VM**, same images and manifests, differing only in the existing `k8s/overlays/{dev,prod}`. Rancher Manager is an optional later slice.

### 2026-09-30 — per-environment Spring config + Flyway (ADR-016 slice (a)), done

Documented as `docs/adr/ADR-017-per-environment-config-and-flyway.md`.

**State before, read rather than assumed**: env overrides already worked implicitly (docker-compose set `SPRING_DATASOURCE_URL` etc., Spring's relaxed binding mapped them). But there were no profiles, every `application.yml` hardcoded `ticketing`/`ticketing` + `localhost` + `DEBUG`, the gateway's CORS origin was fixed to `localhost:4200`, and Hibernate's `ddl-auto: update` owned the schema everywhere.

**What changed, per service:**
- `application.yml` keeps only what's the same everywhere, plus `spring.profiles.default: dev`, so local runs, compose and tests need no flag.
- `application-dev.yml` holds connection settings as `${VAR:local-default}`.
- `application-prod.yml` holds the same keys as `${VAR}` with no default.
- One set of env names everywhere: `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`/`REDIS_PORT`, the existing `*_SERVICE_BASE_URL`s, and `GATEWAY_CORS_ALLOWED_ORIGINS`. docker-compose now uses these names.

**Flyway** now owns the schema in every environment (`spring-boot-starter-flyway` + `flyway-database-postgresql`, Flyway 12.4.0 from the Boot 4.1.0 BOM), with `ddl-auto: validate` everywhere:
- Each service's `V1__baseline.sql` was generated by **Hibernate's own schema export**, not dumped from the dev DB, which could carry stale columns after months of `update`. The column sets of all five dev DBs were then compared against the export and matched exactly.
- Dev gets `baseline-on-migrate`; prod deliberately doesn't.
- The seven `@DataJpaTest` `create-drop` overrides were removed. Every repository and integration test now runs the real migrations on a fresh Testcontainers Postgres and validates against them, so CI catches entity/migration drift on every change.

**Real bug found by the negative test, not by reading docs**: prod placeholders without defaults were supposed to fail fast. Starting event-service with the prod profile and no `DB_PASSWORD` instead failed with `password authentication failed for user "ticketing"`. Spring Boot's `@ConfigurationProperties` binding leaves unresolvable placeholders in place, so the literal `${DB_PASSWORD}` was sent as the password. For `REDIS_HOST`/`KAFKA_BOOTSTRAP_SERVERS` it wouldn't have failed at startup at all. Fixed with a prod-only `RequiredConfigurationCheck` per service: a static `BeanFactoryPostProcessor` that strictly resolves every config-file property before any bean exists. It's generic, so there is no list of required keys to keep in sync.

**Verified:**
- `node ci.js services`: all six PASS (201 tests), Flyway migrating to "1 - baseline" and `validate` passing in every suite.
- Stack in **dev** against the existing volume: all five DBs logged "Successfully baselined schema with version: 1". `seed-events.js` passed, and `demo.js` ran all steps 0a–15 with all 10 SUCCESS checks and no WARN/ERROR.
- Stack in **prod** via the new `docker/docker-compose.prod-profile.yml`: all six "profile is active: prod", schema "up to date" at version 1, no DEBUG output. `seed-events.js` and `demo.js` passed the same way. CORS allowed `http://localhost:4200` and returned 403 for a foreign origin.
- **Negative tests**: prod without `DB_PASSWORD` stops with `Could not resolve placeholder 'DB_PASSWORD'`; booking-service without `REDIS_HOST` stops with `Could not resolve placeholder 'REDIS_HOST'`.

A brief DNS outage during the image rebuild ("lookup registry-1.docker.io: no such host") failed one build. It was retried unchanged once connectivity returned; it was not a code issue.

**GitHub Actions run** (run `36679585229`, push of `e39a240`): green, about **3m wall-clock**. The local test run above happened *before* `RequiredConfigurationCheck` was added; this CI run is the first full-suite run with it in place. The logs show Flyway applying V1 to a fresh Postgres in every database-backed test class.

| Job | Tests | Flyway V1 applied | Duration | Billed |
|---|---|---|---|---|
| changes | — | — | 0:04 | 1 min |
| api-gateway | 7 | — (no DB) | 0:56 | 1 min |
| event-service | 35 | 5× | 1:59 | 2 min |
| booking-service | 68 | 4× | 2:53 | 3 min |
| payment-service | 33 | 2× | 2:48 | 3 min |
| notification-service | 35 | 2× | 2:03 | 3 min |
| waitlist-service | 23 | 2× | 2:01 | 3 min |
| frontend | — | — | skipped | — |

The frontend job was correctly skipped, since this commit didn't touch `frontend/**`. The run cost **~16 of the 2,000 free minutes**.

**Recorded for later, not changed here**:
- In docker-compose, booking-service uses the shared default DB `ticketing` rather than its own, contradicting ADR-001.
- Kubernetes injects `REDIS_PORT=tcp://…` for a Service named `redis`, which would collide with our `REDIS_PORT`, so slice (b) must set `enableServiceLinks: false`.

### 2026-10-01 — K8s base manifests (ADR-016 slice (b)), done

Documented as `docs/adr/ADR-018-kubernetes-base-manifests-and-ingress.md`.

**Decisions made with the user before writing anything:**
- Verify on **kind** (v0.33.0, installed via winget) in Docker Desktop. The Docker VM stays at **4 GB**.
- Entry point: a plain **Ingress served by Traefik** instead of the Gateway API or no Ingress. ingress-nginx was retired in March 2026, and k3s and Rancher Desktop both bundle Traefik.

**What was added:**
- `k8s/base` (Kustomize, namespace `ticketing`):
  - StatefulSets for Postgres, Redis, ZooKeeper and Kafka. They use the same cp-* 7.5.0 images as compose, with one in-cluster listener `kafka:9092`.
  - Six Deployment + Service pairs.
  - One Ingress, `/` → api-gateway, which already routes `/bookings/ws/**`.
- Every pod has `enableServiceLinks: false`.
- Every service runs the Spring **prod** profile, so a missing variable in a manifest fails at startup.
- booking-service gets its **own `ticketing_bookings`** database, created together with the other four by a `postgres-init` ConfigMap.
- Probes use `/actuator/health/{liveness,readiness}`, with a 3-minute `startupProbe` and a 3 s liveness timeout.
- Resources are sized for 4 GB: 300 Mi request and 512 Mi limit per service, Kafka heap 384 MB.
- `k8s/overlays/dev` sets the `:dev` image tags and generates the `db-credentials` Secret and the `gateway-config` ConfigMap (CORS origin).
- `k8s/kind/` holds `cluster.yaml` (host port 8000 → node port 80) and `traefik.yaml`, a minimal Traefik v3.7.13 with RBAC from Traefik's own reference file and a default IngressClass, as in k3s.

**Verified on kind** (compose stack stopped, existing images from slice (a), confirmed to contain `application-prod.yml`, `RequiredConfigurationCheck` and the V1 migrations):
- `kubectl apply -k k8s/overlays/dev`: all 10 pods Ready after about 5 minutes. The node used 2.8–3.0 of 3.8 GiB.
- `demo.js` against port-forwarded services ran **all steps 0a–15**: HELD → PAYMENT_PENDING → CONFIRMED, payment SUCCEEDED → REFUNDED, notifications SENT, waitlist PROMOTED. It was run twice, the second time after the probe change below.
- **Through the Ingress** (`localhost:8000`, Traefik → api-gateway): `GET /events` and `POST /bookings/hold` (201) worked. A WebSocket on `/bookings/ws/events/{id}/seats` received `{"status":"HELD"}` for the held seat, three times in a row at about 100 ms per hold.
- Databases: `ticketing_bookings` holds `bookings` + `flyway_schema_history`, and the shared `ticketing` database has **no tables**.
- **Negative check for `enableServiceLinks`**: a control pod without it got `REDIS_PORT=tcp://10.96.67.125:6379` and `KAFKA_PORT=tcp://…`. booking-service saw `REDIS_PORT=6379`, and kafka-0 had no `KAFKA_PORT`.
- `kubectl diff -k` after apply showed no drift.

**Found while verifying:**
- On a cold start, pods restart 1–2 times because there is no start ordering: Kafka exited 1 before ZooKeeper was up, and the services couldn't reach Postgres yet. Kubernetes converges on its own. This is accepted and recorded in ADR-018.
- The api-gateway's first startup probe hit the 1 s default timeout ("context deadline exceeded") while six JVMs shared two CPUs, so liveness now uses `timeoutSeconds: 3`.
- The **first** WebSocket check through the Ingress timed out after 15 s, and the hold request never arrived. It did **not reproduce** in three later runs, and the booking-service, gateway and Traefik logs show no error. The cause is not determined; the most likely candidate is a cold first request.
- `seed-events.js` resets data via `docker exec docker-postgres-1`, so it only works against compose. A cluster variant belongs in slice (d).

**Not changed, recorded for later:**
- docker-compose's booking-service still uses the shared `ticketing` DB, because existing dev volumes would need the new database created by hand.
- `k8s/overlays/prod` is still empty (slice (e)).
- Prometheus, Grafana and Kafdrop are not in the cluster.

**Next up** — the rest of the dev/prod roadmap from ADR-016, one slice at a time:
- (c) Containerized frontend — done, see below.
- (d) Dev deploy script against Rancher Desktop.
- (e) Prod deploy: Secrets, registry, deploy job.
- (f) Optional: Rancher Manager.

Distributed tracing still follows once the pipeline is running in GitHub Actions.

### 2026-10-01 — slice (b) pushed, CI result

`807329e` was pushed. [Run 36839032242](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/36839032242) was green, but only the change detection ran (0:13). The commit touched `k8s/` and docs, which match no job's path filter, so the frontend and service jobs were skipped. **The Kubernetes manifests are not validated in CI.** A `kubectl kustomize` or kubeconform check would close that gap and is noted as a possible follow-up.

### 2026-10-01 — containerized frontend (ADR-016 slice (c)), done

Documented as `docs/adr/ADR-019-containerized-frontend-and-same-origin-api.md`.

**Found before writing anything:** the SPA routes `/events/:id` and `/bookings/:id` use the same paths as the gateway's `/events/**` and `/bookings/**`. Served from one origin without a prefix, a reload on `/events/123` would return the gateway's JSON. A plain Ingress can't strip an `/api` prefix without controller-specific annotations, which ADR-018 avoided.

**Decisions made with the user:**
- **nginx in the frontend image proxies `/api/**` to api-gateway**, stripping the prefix. This was chosen over an Ingress split with `/api` routes in the gateway, and over separate hosts with a runtime `config.json` and CORS.
- **`nginxinc/nginx-unprivileged`**: non-root (UID 101), port 8080, amd64 and arm64. Chosen over the official `nginx` (root) and Caddy.

**What was added:**
- `frontend/Dockerfile`: a `node:24-alpine` build stage, then `nginxinc/nginx-unprivileged:1.30-alpine`. Both tags were checked with `docker manifest inspect` and include arm64. The image is 82 MB.
- `frontend/nginx/default.conf.template`:
  - `/api/` is proxied to `${API_GATEWAY_URL}/` with WebSocket upgrade headers.
  - The SPA fallback serves `index.html` with `no-cache`, and hashed assets get a one-year immutable cache.
  - `/healthz` serves the probes.
  - `NGINX_ENVSUBST_FILTER` restricts substitution to `API_GATEWAY_URL`.
- Both environment files use `apiBaseUrl: '/api'`, and `wsBaseUrl` is derived from `location`. `ng serve` gets `proxy.conf.json`, so local development uses the same relative URLs. There is a new `environment.spec.ts`: 34 frontend tests, up from 32.
- `k8s/base/services/frontend.yaml` (Deployment + Service, `runAsNonRoot`, 16 Mi request / 64 Mi limit). The Ingress `/` now points at `frontend`, and the dev overlay sets `ticketing/frontend:dev`.
- docker-compose has a `frontend` service on host port 8000, the same as the kind Ingress.
- `node ci.js frontend --docker` also builds the image, and the workflow's frontend job uses the flag.
- `docs/diagrams/c4-diagram.md` (Level 2) shows the `frontend` nginx container between the browser and api-gateway. booking-service's database now reads `ticketing_bookings` (K8s) / shared `ticketing` (compose), a leftover from slice (b). Both diagrams were rendered with Mermaid 11 in headless Chrome without errors.

**Verified:**
- **Image standalone:**
  - Runs as `uid=101(nginx)`.
  - `/`, `/events/abc` and `/bookings/xyz` return `text/html`.
  - The nginx log shows `/api/events` going upstream as `…/events`, so the prefix is stripped.
- **On kind, through the Ingress (`localhost:8000`):**
  - `/` and `/events/x` return the app, `/api/events` and `/api/actuator/health` return JSON from the gateway.
  - `kubectl diff` was empty after the apply.
- **WebSocket through Ingress → nginx → gateway → booking-service:** the `HELD` push arrived after 117–164 ms in three runs, against 99–105 ms directly at the gateway.
- **In a real browser** (headless Chrome over CDP; the Chrome extension wasn't connected):
  - Opening the deep link `/events/{id}` directly rendered all 650 seats.
  - A hold made outside the browser turned the seat amber live, and the cancel turned it back. The browser's WebSocket was `ws://localhost:8000/api/bookings/ws/…`.
- **`ng serve` with `proxy.conf.json`** against a port-forwarded gateway: `/events/x` returns HTML, `/api/events` returns JSON, and the WebSocket push arrived after 71 ms.
- `docker compose config` is valid, both alone and with the prod-profile file. **The compose stack itself was not started**, because it doesn't fit in 4 GB next to the kind cluster.

**Found while verifying:**
- **The first WebSocket check after the deploy timed out again.** The socket opened and the hold returned 201, but no message arrived in 15 s. This is the same symptom as the first check in slice (b), and again it did not reproduce in eleven later runs.
  - Disproved: a cold api-gateway. After `rollout restart` of the gateway, the first push arrived after 280 ms.
  - Disproved: a cold booking-service. After a restart, the first push arrived after 1,055 ms.
  - Both failures happened within about a minute of the Ingress being changed (slice (b): created; now: repointed to `frontend`). A Traefik reconfiguration window is the remaining candidate. It is **not verified**.
- The hold from that failed run was never cancelled. It stayed `HELD` until its 10-minute TTL and then expired through `SeatHoldExpiredListener`.

**Not changed, recorded for later:**
- The gateway's CORS config is now unused by the app. It stays for direct API clients.
- The gateway's `/actuator/health` is reachable from outside via `/api/actuator/health`. Before this slice it was reachable via `/actuator/health`. This belongs in the prod slice (e).

**CI** for `eb7029b` ([run 36844711566](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/36844711566)): green, all 8 jobs, about 2.5 min wall time. Every service job ran because the commit changed `ci.js` and `ci.yml`; the path filter re-runs all targets on a pipeline change. The frontend job now builds the image too (`naming to docker.io/ticketing/frontend:ci done`).

| Job | Tests | Duration |
|---|---|---|
| changes | — | 0:05 |
| frontend (incl. image build) | 34 | 0:44 |
| api-gateway | 7 | 1:08 |
| event-service | 35 | 1:33 |
| notification-service | 35 | 1:32 |
| waitlist-service | 23 | 1:33 |
| payment-service | 33 | 2:13 |
| booking-service | 68 | 2:21 |

### 2026-10-01 — dev deploy script (ADR-016 slice (d)), done

Documented as `docs/adr/ADR-020-dev-deploy-script.md`.

**Found before writing anything:** on Windows, Rancher Desktop runs as a WSL2 distribution, and all WSL2 distributions share one VM and its `.wslconfig` limits ([Rancher Desktop docs](https://docs.rancherdesktop.io/1.8/ui/preferences/wsl/)). On this machine that is 4 GB, shared with Docker Desktop, so Rancher Desktop and Docker Desktop with kind never run at the same time.

**Decisions made with the user:**
- **One script for both clusters, chosen from the kube-context.** This was chosen over Rancher Desktop only and over kind only.
- **A Node script** (`deploy-dev.js`), chosen over Skaffold and Tilt.
- **`seed-events.js --k8s`**, chosen over a separate script and over a Kubernetes Job.

**What was added:**
- `deploy-dev.js [targets...] [--no-build] [--seed]`:
  - Refuses any context other than `kind-*` and `rancher-desktop`.
  - Builds one image at a time. On kind it runs `kind load`; on Rancher Desktop it builds through Rancher Desktop's docker engine, which k3s shares, so there is no load step.
  - Installs Traefik on kind if there is no IngressClass, then applies the dev overlay.
  - Restarts only rebuilt Deployments that already existed, waits for every rollout, and runs a smoke check through the Ingress (`/` must return the app shell, `/api/events` a 200).
  - `--seed` reseeds the catalog.
- `seed-events.js --k8s [--base-url=…]`: posts the catalog through `/api` and truncates via `kubectl exec postgres-0`. Without the flag it behaves as before.
- Rancher Desktop 1.24.0 was installed (winget, machine-wide) and runs with moby on k3s **1.36.4**, the stable channel. The fresh install had preselected 1.25.16.

**Verified on kind:**
- A prod-like kube-context in a temporary kubeconfig was refused before anything was built. An unknown target exits with code 2.
- `--no-build --seed`: smoke check OK, 13 events seeded across all categories.
- `frontend api-gateway`: built, loaded and restarted, 62 s in total.
- **A code change reaches the pod.** A marker file in `frontend/public` was served after `deploy-dev.js frontend`. After it was removed and redeployed, its path fell back to the SPA. The working tree is clean.

**Verified on Rancher Desktop:**
- **The first run, with a cold build of all seven images, built everything but timed out in the rollout.**
  - Cause: `apply` had just created the Deployments, and the following `rollout restart` started a second ReplicaSet for each one. That meant 12 JVMs, three pods Pending, and restarts.
  - Fixed: only Deployments that existed before the apply are restarted. On kind this had not shown, because the Deployments already existed there.
- After deleting the namespace, `deploy-dev.js --seed` deployed into the empty cluster in **240 s**: all 11 pods Ready with **0 restarts**, smoke check OK on `http://localhost`, 13 events seeded.
- A hold through Rancher Desktop's Traefik pushed `HELD` over the WebSocket in all three runs (678, 176 and 61 ms), and `/events/x` returned the app.

**Found while verifying:**
- **The docker CLI kept Docker Desktop's context** (`desktop-linux`), and Rancher Desktop's diagnostics flagged it. Rancher Desktop serves the `default` context (`npipe:////./pipe/docker_engine`). The script uses that context for its builds and does not change the global setting.
- **On the switch back, Docker Desktop's restart revived the six stopped compose app containers.** With no infrastructure they crash-looped (`restart: on-failure`), and the kind node's load rose to 46 on two CPUs. Every kind JVM was then killed by its 3-minute startup probe (exit 137). After `docker stop` on those containers, all pods were Ready in 90 s.
- **Right after the node restart, `kubectl rollout status` reported success from stale status**, but the smoke check failed (`UND_ERR_SOCKET`). The smoke check is what makes the script's result trustworthy.
- The WebSocket miss from slices (b) and (c) did **not** occur on Rancher Desktop. The first push arrived after 678 ms.
- `rdctl start` stays attached to the app. In Git Bash, `rdctl api /v1/…` paths need `MSYS_NO_PATHCONV=1`.

**Not changed, recorded for later:**
- `demo.js` still calls each service on its own port, so in a cluster it needs `kubectl port-forward`.
- The compose stack and Testcontainers on Rancher Desktop were verified afterwards, see the next entry.

### 2026-10-01 — slice (d) pushed; compose and Testcontainers on Rancher Desktop

`71947b4` was pushed. [Run 36854677167](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/36854677167) was green, but only the change detection ran, because `deploy-dev.js` and `seed-events.js` match no job path filter.

**Can Rancher Desktop replace Docker Desktop entirely?** Both checks were run on Rancher Desktop with Kubernetes disabled (moby engine only), with Docker Desktop stopped:
- **Testcontainers:** `mvn test` in waitlist-service ran with `DOCKER_HOST=npipe:////./pipe/docker_engine` and `TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=…EnvironmentAndSystemPropertyClientProviderStrategy`, set only for that run. That bypasses the Testcontainers Desktop proxy (`tc.host`) without touching `~/.testcontainers.properties`.
  - Testcontainers logged `Resolved dockerHost=npipe:////./pipe/docker_engine` and the engine reported `Rancher Desktop WSL Distribution`.
  - **All 23 tests passed in 76 s.** The ryuk, `postgres:15-alpine` and `cp-kafka:7.5.0` containers ran on Rancher Desktop.
- **docker-compose:** `docker compose` (v5.3.1, bundled with Rancher Desktop) with `DOCKER_CONTEXT=default`. The images were built one at a time, and the build cache from the `deploy-dev.js` run made them take 13 s. All 14 containers ran.
  - `seed-events.js` created 13 events.
  - **`demo.js` ran end to end**: HELD → PAYMENT_PENDING → CONFIRMED, payment SUCCEEDED → REFUNDED after the cancel, waitlist PROMOTED, three notifications SENT.
  - The frontend on :8000 returned the app, and `/api` reached the gateway.
  - The first `seed-events.js` attempt timed out, because event-service needed 73 s for its cold start against a 60 s wait. That is not specific to Rancher Desktop.
- **Result:** for this project Rancher Desktop covers everything Docker Desktop was used for. That is compose, Testcontainers, image builds, and the dev Kubernetes cluster, which replaces kind.
  - Not checked: Testcontainers through the Testcontainers Desktop app pointed at Rancher Desktop, and `ci.js` across all services.
  - A permanent switch also needs `docker context use default` and the doc updates (README prerequisites).
- **Reproduced:** after Docker Desktop started again, the six stopped compose app containers were running again and had to be stopped before kind recovered (all pods Ready after about 120 s).

**Next up:** (e) prod: Secrets, a registry with immutable tags, a deploy job, and multi-arch images (cp-kafka arm64 or KRaft). (f) Rancher Manager is optional.

### 2026-10-01 — multi-arch release images in GHCR (ADR-016 slice (e1)), done

Documented as `docs/adr/ADR-021-prod-images-registry-and-secrets.md`. Slice (e) is split into three parts:
- e1: the images.
- e2: the prod overlay and Secrets, verified locally.
- e3: the Oracle VM, k3s and the deploy job.

**Facts checked before deciding:**
- **GHCR container storage is currently free, including private images** ([GitHub docs](https://docs.github.com/en/billing/concepts/product-billing/github-packages)).
  - ADR-016's 500 MB limit applies only to the other Packages registries. ADR-016 now carries a correction note.
- **The repository is public.** It was believed to be private.
  - On a public repository, standard runners cost no minutes, and `ubuntu-24.04-arm` has 4 vCPUs.
- **Every base image has an arm64 variant**, including `cp-kafka`/`cp-zookeeper:7.5.0`.
  - **KRaft is not needed for the arm64 VM.** That closes the open question from ADR-018.

**Decisions made with the user:**
- The order is e1 → e2 → e3.
- GHCR with **public** images. The first choice was private; it changed once the repository turned out to be public.
- Native runners per architecture.
- GitHub Actions secrets, turned into Kubernetes Secrets by the deploy job. That part comes in e2/e3.

**What was added:** `.github/workflows/release-images.yml`, triggered manually or by a `v*` tag.
- 7 targets × {amd64 on `ubuntu-latest`, arm64 on `ubuntu-24.04-arm`}. Each job pushes `…:sha-<7>-<arch>`.
- One merge job per target runs `imagetools create` to make **`ghcr.io/comicnerd23/ticketing/<target>:sha-<7>`**, plus the Git tag on a tag push. There is no `latest`.
- actionlint (in a container) reports no findings for this workflow or `ci.yml`.

**Verified** ([run 36860189486](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/36860189486), for `9c67867`):
- All 21 jobs passed in **about 3 minutes** of wall time. The builds took 75–153 s each, the merges 13–22 s.
- **Anonymous pulls work.** With an anonymous GHCR token, every `:sha-9c67867` index returned HTTP 200 and lists `linux/amd64` and `linux/arm64`.
  - GHCR created the packages as public, following the repository, so no manual visibility change was needed.
- **The arm64 images really are arm64.** Run locally under emulation:
  - api-gateway reports `aarch64` and Java 25.0.4.1, with the 52 MB jar.
  - The frontend reports `aarch64`, `uid=101(nginx)` and nginx 1.30.5 serving `index.html`.
- In the registry's image config, `org.opencontainers.image.source` and `.revision` point at this repository and commit.

**Found while verifying:**
- **The images inherited the Ubuntu base image's `title` and `description` labels**, which GHCR shows on the package page. The workflow now overrides both. This takes effect on the next release run and is **not verified yet**.
- `docker image inspect` on the multi-platform pull showed no labels. That is a display quirk of Docker Desktop's containerd store; the registry config has them.

The second release run for `37dd842` ([run 36860983885](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/36860983885)) was green in about a minute thanks to the layer cache. The registry config now has `title: ticketing/booking-service` and the project's own description, so **the label fix is verified**.

### 2026-10-01 — prod overlay and deploy-prod.js (ADR-016 slice (e2)), done

Documented in ADR-021, section "Decision (e2)".

**Decisions made with the user:**
- **`deploy-prod.js`, also used by the e3 deploy job.** The alternative was inline workflow steps.
- **The tag is inserted at deploy time.** The alternative was GitOps commits.
- **The actuator is closed in the frontend nginx.** The alternatives were a Traefik middleware or leaving it open.
- **Rancher Desktop as the test cluster**, rather than kind.

**What was added:**
- `k8s/overlays/prod` maps the GHCR images with a `set-by-deploy` tag. It has no Secret and no ConfigMap.
- `deploy-prod.js` requires an explicit context and accepts only immutable tags.
  - It checks the tag in GHCR for all 7 images.
  - It writes the Secret and ConfigMap from environment variables via stdin.
  - It renders and applies the overlay, waits for rollouts, and runs a smoke check that includes `/api/actuator/health` → 404.
- The frontend nginx has `location ^~ /api/actuator { return 404; }`.
- `k8s-helpers.js` holds the code shared with `deploy-dev.js`.

**Found while building:**
- **`deploy-dev.js` split the docker context list on the letter "s"** (`/s+/` instead of `/\s+/`): a heredoc had swallowed the backslash. Rancher Desktop still worked because the script always fell back to `default`. Fixed in the move to `k8s-helpers.js`.
- **My own mistake:** `node -e 'require("./deploy-dev.js")'` ran the script with no arguments.
  - It rebuilt all images from cache and loaded them into kind, then died on the closed output pipe before the apply. The running pods were untouched.
  - `deploy-prod.js` now only runs `main()` when started directly (`require.main === module`).

**Verified offline:**
- The rendered overlay has 7 GHCR images with the placeholder and no Secret or `gateway-config`.
- The guards work:
  - No arguments → exit 2.
  - `--tag=latest` is refused.
  - A missing `DB_PASSWORD` is refused.
  - An unknown context gives "not reachable".
- The tag check passes `sha-37dd842` and stops `sha-0000000` at HTTP 404 before anything is applied.
- A frontend image built locally returns 404 for `/api/actuator`, `/api/actuator/health`, `/api/actuator/prometheus` and `/api/actuatorx`. `/api/events` is still proxied.

**Verified on Rancher Desktop** (k3s 1.36.4), with release images `sha-8b82a63` from [run 36881994733](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/36881994733) and a freshly deleted `ticketing` namespace, using a randomly generated test password:
- **First deploy:** the tag check, Secret, ConfigMap and apply all worked, and the images were pulled from GHCR. **The rollout then timed out.**
  - Cause: the six compose app containers on Rancher Desktop's engine had come back and were crash-looping, pushing the load to 33.
  - See the restart-policy fix below.
- **Second deploy** onto the existing state, after stopping them: **105 s, exit 0.**
  - The smoke check passed: `/` 200, `/api/events` 200, **`/api/actuator/health` 404**.
  - The Deployments stayed unchanged, so a repeat deploy is idempotent. Only the Secret reports "configured", because apply rewrites its own annotation.
- The test password appears **0 times** in the deploy logs, and the Secret holds it, not the dev password.
- `seed-events.js --k8s` created 13 events. A hold pushed `HELD` over the WebSocket after 829 ms and then 84 ms.

**Root cause of the recurring revived compose containers** (three times now: twice on Docker Desktop, once on Rancher Desktop):
- The JVMs exit with **143** (SIGTERM) or **137** on `docker stop`. `restart: on-failure` treats that as a failure, so the engine restarts them when it starts.
- Postgres and Redis exit 0 with no restart policy and stay down, so the apps crash-loop.
- **Fix:** `restart: unless-stopped` for the seven app services in `docker-compose.yml`.
- **Verified on Docker Desktop:** the existing containers were switched with `docker update`, exit code 143 was kept, and Docker Desktop was restarted. **0 compose containers came back**, compared with all six before the change.
- New containers created by compose get the new policy from the file.

**Not changed, recorded for later:**
- Changing `DB_PASSWORD` after the first deploy updates the Secret but not the Postgres user, because the password is set only on volume initialization. A rotation needs an `ALTER USER`.
- The kind node still holds the newer `frontend:dev` image from the accidental run. The pods use the old one until the next `deploy-dev.js frontend`.

### 2026-10-01 — prod VM and deploy job (ADR-016 slice (e3)), prepared, waiting on the VM

Documented in ADR-021, section "Decision (e3)". **Decisions made with the user:**
- An SSH tunnel to the k3s API. The alternatives were Tailscale or a public port 6443.
- HTTP on the IP first, with TLS as its own slice.
- Manual deploys with a tag input, in a `production` environment.
- Ubuntu 24.04.

A self-hosted runner on the VM was ruled out, because GitHub advises against them on public repositories.

**What was added:**
- `k8s/prod/setup-k3s.sh` for the VM: firewall plus k3s `v1.36.4+k3s1`.
- `.github/workflows/deploy-prod.yml`: SSH tunnel, then `deploy-prod.js --context=prod`.
- `docs/runbooks/prod-vm.md`: the user's steps.

**Verified without a VM:**
- `k3s v1.36.4+k3s1` is the current stable channel and has an arm64 binary.
- actionlint and shellcheck report no findings.
- **The firewall part of `setup-k3s.sh` was run twice** in a privileged `ubuntu:24.04` container seeded with the Oracle image's default rules:
  - 80 and 443 were inserted before the REJECT rule.
  - No rule was duplicated on the second run.
  - The FORWARD REJECT rule was removed.

**Not verified yet:**
- The k3s install on the real A1 VM.
- The SSH tunnel from a hosted runner.
- The first real deploy.

These need the user's Oracle account and VM (runbook steps 1, 2 and 4); I can't create accounts.

**Next up:** the user follows `docs/runbooks/prod-vm.md` steps 1–4, then the first `Deploy prod` run verifies e3. After that, TLS, then (f) Rancher Manager, which is optional.

### 2026-10-02 — slice (e3) verified: first prod deploy on the Oracle VM

The user created the Oracle account, the VM, the deploy key and the `production` environment by following the runbook (parts A and B). The runbook was rewritten along the way (`8990f74`) into one walk-through from the account to the rollback.

**The VM:** Ubuntu 24.04.5 aarch64 on an A1 instance with 2 OCPU and 11.6 GB, node `ticketing-prod`, k3s `v1.36.4+k3s1` installed by `setup-k3s.sh`. Port 80 is open in the security list and the host firewall, and 6443 is closed from outside.

**First deploy:** [run 37028620560](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37028620560), tag `sha-8b82a63`. Those are the newest release images, because nothing image-relevant changed after `8b82a63`.
- **Green.** The job took 2m56s, `deploy-prod.js` 165 s.
- The SSH tunnel from the hosted runner, the pinned host key and the renamed `prod` context all worked on the first try.
- All 11 pods are `Running` with 0 restarts.
- The smoke check passed: `/` 200, `/api/events` 200, **`/api/actuator/health` 404**. I repeated it from outside myself.
- Load on the VM after the deploy: 3.4 GB RAM, about 11% CPU.

**Found while setting up:**
- **`gh secret set DB_PASSWORD --env production` set nothing in Git Bash.** `gh` doesn't see an interactive terminal there, so it doesn't prompt, and B7 listed only 3 of 4 secrets. The runbook's B5 now pipes the value from `read -rs`, and the troubleshooting table has a row for it.

**Run annotations, not acted on yet:**
- `actions/setup-node@v4` targets Node 20, which is deprecated, and the runner forces it to Node 24.
- `ubuntu-latest` moves to Ubuntu 26 from 2026-10-19.

**Seeded on 2026-10-04 with runbook part D**, run by the user. Afterwards `GET /api/events` from outside returned all 13 events.
- **First attempt:** `ssh: connect to host  port 22: Connection refused`. `$IP` was empty because it had been set in another shell. Nothing was changed: the main `~/.kube/config` stayed untouched, and the prod kubeconfig was only a stub without credentials.
- **Runbook fixes:**
  - Part D now sets `IP` itself and stops on an empty value (`: "${IP:?}"`).
  - It says to run every step in one window.
  - The troubleshooting table has a row for the two-space error.
- **The old cleanup line was wrong on Windows:**
  - Git Bash has no `pkill`, and the fallback `taskkill //IM ssh.exe` stops every SSH process.
  - Part D now stops only the tunnel process through PowerShell.
  - **My own mistake while testing it:** the first version also matched its own PowerShell command line and killed itself. It now filters on `ssh.exe`.

**Booking flow checked on 2026-10-04**, by the user in the browser on the public IP. Two windows (one normal, one private) had the same event open:
- **Hold:** clicking a free seat in window A opened the booking page with a countdown. Window B showed the seat as held without a reload, so the WebSocket works through nginx, the gateway and booking-service.
- **Confirm:** the booking reached `CONFIRMED`, so the payment saga runs over Kafka with the stub's self-delivered webhook. Window B showed the seat as booked.
- **Cancel:** the booking became `CANCELLED`, and the seat was free again in window B.
- Everything ran without errors.

**Slice (e3) is done.**

**Next up:** TLS as its own slice. It needs a free domain choice first.

### 2026-10-04 — TLS for prod (ADR-022), built and verified locally

Documented in ADR-022, which also has a section on what an enterprise setup would add.

**Decisions made with the user:**
- **An sslip.io name** (`https://<IP with dashes>.sslip.io`). The alternatives were an IP certificate (6-day lifetime), DuckDNS (needs an account and a token) and an own domain (costs money).
- **cert-manager v1.21.2.** The alternative was Traefik's built-in ACME client.
- **HTTP-01**, with staging first after any TLS change.

**What was added:**
- **`deploy-prod.js`:**
  - It installs cert-manager from the pinned official manifest, checked against its sha256.
  - It sets host and issuer from `--base-url` and `--tls-issuer` (`letsencrypt-prod`, `letsencrypt-staging` or `selfsigned`). It refuses `http://` and bare IPs.
  - It waits until the certificate comes from the chosen issuer, using `Ready` plus the Secret's `issuer-name` annotation, and prints the served certificate.
  - The smoke check runs over HTTPS and checks the HTTP → HTTPS redirect.
- **`k8s/overlays/prod`:** three ClusterIssuers, the Ingress host and `tls:` section, and a Traefik redirect Middleware on the app's Ingress only.
- **Deploy workflow:** a `tls_issuer` choice input.
- **Runbook:** port 443, HTTPS variables, staging first, and a new Part E that switches the running HTTP deployment.

**Verified offline:**
- The guards refuse no arguments, `http://`, a bare IP, a path in the URL, an unknown issuer and an unreachable context.
- The overlay renders with no placeholders left.
- actionlint reports no findings.
- Switching off certificate verification at runtime works for `fetch`, tested against `self-signed.badssl.com`.

**Verified on Rancher Desktop** (k3s 1.36.4, Traefik on localhost:80/443), with `sha-8b82a63`, `--base-url=https://localhost` and `--tls-issuer=selfsigned`, after deleting the old `ticketing` namespace:
- **First deploy:** 197 s, exit 0.
  - The manifest checksum matched, and cert-manager rolled out.
  - The overlay applied on the first attempt, so the retry for the webhook never ran.
  - The certificate became `Ready`, signed by `selfsigned`.
  - The smoke check passed: `/` 200, `/api/events` 200, `/api/actuator/health` 404, `http://localhost/api/events` → 301 to the HTTPS URL. Traefik answers HEAD with 308 and GET with 301, and the check accepts both.
- **Repeat deploy:** 11 s, and the certificate was not reissued (the Secret's resourceVersion was unchanged).
- **cert-manager's idle usage:** controller 38 Mi, cainjector 27 Mi, webhook 19 Mi, 1m CPU each.
- **Negative test with `letsencrypt-staging`:** Let's Encrypt can't issue for `localhost`, so the deploy has to fail. The old certificate kept being served throughout, so there was no outage.

**Found while testing:**
- **A failed issuance waited the full 300 s and showed no reason.**
  - Let's Encrypt rejected the order before any challenge existed, so `describe challenges` printed nothing. cert-manager only retries after a backoff of about an hour.
  - The wait now stops on `Issuing=False/Failed` after a 30 s grace period.
  - It prints the reasons from the certificate's conditions and the orders and challenges, e.g. `rejectedIdentifier … Domain name needs at least one dot`.
  - The failing run now ends after 42 s instead of 311 s.
- **The printed certificate line was empty.** cert-manager puts the name only in the SAN, not the CN, and a self-signed certificate has an empty issuer. The line now shows the SAN names.
- **My assumption in the first ADR-022 draft was wrong.** I had written that after an issuer switch the old certificate "stays Ready". In fact cert-manager sets `Ready=False/IncorrectIssuer` as soon as its controller reacts. A probe showed that an issuer switch starts a new issuance at once, even right after a failure. So moving from staging to prod isn't blocked by the backoff.
- **The runbook expected the wrong status code.** It said `https://<IP>` returns 404 before the switch. The user got 200: the HTTP-only Ingress has no host rule, and Traefik serves it on 443 too, with its self-signed `TRAEFIK DEFAULT CERT`. Corrected.

**Not verified yet:**
- A real Let's Encrypt issuance, including HTTP-01 through Traefik next to the redirect.
- The `letsencrypt-prod` trust check.

Both need the VM (runbook Part E). Port 443 is already open in the security list (the user's step).

### 2026-10-04 — TLS live on prod (ADR-022), done

The user opened port 443, switched `PROD_BASE_URL` and `GATEWAY_CORS_ALLOWED_ORIGINS` to `https://<IP with dashes>.sslip.io`, and ran runbook Part E with release `sha-8b82a63` (pushed code `dce95e8`):

- **Staging,** [run 37212540349](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37212540349): **green, 87 s** in `deploy-prod.js`.
  - The certificate went from `Ready=False` to `Ready=True`, signed by `letsencrypt-staging`. Its issuer was `Let's Encrypt / (STAGING) Ersatz Emmer YR2`, and Node did not trust it, as expected.
  - The smoke check passed: `/` 200, `/api/events` 200, actuator 404, `http://…/api/events` 301.
- **Prod,** [run 37212908972](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37212908972): **green, 62 s**.
  - The wait first saw `Ready=False, signed by letsencrypt-staging`, so the check on the Secret's issuer annotation did its job.
  - Then `Ready=True, signed by letsencrypt-prod`, with the issuer `Let's Encrypt / YR2`, valid until 2027-01-02, and **trusted by Node: yes**.
  - The same smoke check passed.
- **HTTP-01 worked next to the redirect,** on the first attempt for both issuers. The solver Ingress won over the app's redirect Middleware, as designed.

**I re-checked from outside:**
- `curl https://<host>/` returned 200 without `-k`.
- The served certificate has `CN` and SAN set to the sslip.io name and the issuer `C=US, O=Let's Encrypt, CN=YR2`. It is valid from 2026-10-04 to 2027-01-02.
- `http://<host>/api/events` → 301 to the HTTPS URL. The actuator returns 404.
- The bare IP now returns 404 over both HTTP and HTTPS, as expected, because the Ingress only answers for its host.
- The catalog is still there (13 events), and **the seat-status WebSocket opens over `wss://`**.

**Slice done.** cert-manager renews the certificate about 30 days before it expires.

**Possible follow-ups, not done:**
- A scheduled check of the certificate's expiry.
- `actions/setup-node` v4 → v6 (Node 24 natively).
- `ubuntu-latest` moves to Ubuntu 26 from 2026-10-19.

### 2026-10-04 — Actions on Node 24, runners pinned to Ubuntu 26.04

This fixes the two run annotations.

**"Node.js 20 is deprecated":** every action now uses its current major version, which runs on `node24` (checked in each action's `action.yml`):

| Action | Before | After |
|---|---|---|
| `actions/checkout` (ci.yml) | v4 | v7 |
| `dorny/paths-filter` | v3 | v4 |
| `actions/setup-java` | v4 | v6 |
| `actions/setup-node` (ci.yml, deploy-prod.yml) | v4 | v7 |
| `actions/upload-artifact` | v4 | v7 |

No breaking change applies:
- setup-node v5 turns on caching by itself only when `package.json` has a `packageManager` field. Ours has none, and the frontend job already sets `cache: npm`.
- upload-artifact v7 switched to ESM internally, and its direct upload is opt-in.

**"ubuntu-latest will migrate to Ubuntu 26":** the rollout runs from 2026-10-19 to 2026-11-19 ([runner-images#14748](https://github.com/actions/runner-images/issues/14748)).
- Every job is now pinned instead: `ubuntu-latest` → `ubuntu-26.04`, and the arm64 release builds `ubuntu-24.04-arm` → `ubuntu-26.04-arm`.
- That way the switch happens at a time we choose, and we can check it.
- The relevant change for us is **Docker 29.4 instead of 28.0** on the runner. Testcontainers 1.21.4's release notes say it supports recent Docker Engine changes.

**Found:** actionlint 1.7.12, the latest release (2026-03-30), reports both labels as unknown. Its support for them is still in open PRs (rhysd/actionlint#743). `.github/actionlint.yaml` declares them until a release includes them, and actionlint then reports no findings.

**Verified:** [CI run 37214924053](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37214924053) (push of `907f344` + `1c46a8e`) is **green in 2m22s on Ubuntu 26.04 with Docker 29**.
- Because `ci.yml` changed, every target ran: all six services and the frontend (8 Vitest files).
- Every Testcontainers suite passed. Testcontainers 1.21.4 works with Docker 29.
- **No annotations are left:** neither the Node 20 warning nor the Ubuntu migration notice.
- The two manual workflows, also green on the new runners, with no annotations:
  - [Release images 37215744589](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37215744589) took 1m53s. All 14 native builds (`ubuntu-26.04` and `ubuntu-26.04-arm`) and 7 multi-arch merges passed, publishing `sha-1c46a8e`.
  - [Deploy prod 37215749525](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37215749525) redeployed `sha-8b82a63` in 58 s. The certificate was unchanged (still signed by `letsencrypt-prod`, trusted), and the smoke check passed.

### 2026-10-04 — Code quality with SonarQube Cloud (ADR-023), built, waiting on the account

**Decisions made with the user:**
- **SonarQube Cloud on the Free plan.** The alternatives were a self-hosted Community Build on the prod VM (4–8 GB RAM next to the app, a public endpoint, and its own operation), the OSS plan, and CodeQL only.
- **Seven projects bound to the repository as a monorepo.**
- **A failed quality gate fails the CI job.**
- **Upgrade path, agreed with the user:** if feature branches ever need checks, move to the OSS plan. It needs an OSI license file (the repository has none yet) and an application to Sonar.

**What was added:**
- JaCoCo 0.8.15 in all six service POMs.
- `@vitest/coverage-v8` 4.1.11 in the frontend, plus `frontend/sonar-project.properties`.
- `ci.js --sonar`, using the pinned Maven scanner and `@sonar/scan`, with the project key `<org>_<target>` and a wait for the quality gate.
- `ci.yml` passes `--sonar`, `SONAR_TOKEN` and `SONAR_ORGANIZATION`, with `fetch-depth: 0`.
- The runbook `docs/runbooks/sonarqube-cloud.md`, and updates to README and CLAUDE.md.

**Verified locally:**
- **Frontend:** `ng test --coverage` passed 34 tests and wrote `coverage/frontend/lcov.info`. Coverage: 88.9 % of lines, 82.6 % of branches.
- **event-service:** JaCoCo 0.8.15 on Java 25 wrote `target/site/jacoco/jacoco.xml`.
- **`node ci.js api-gateway --sonar` with no token:** the 7 tests passed, the JaCoCo report was written, and the run printed `SonarQube: skipped (SONAR_TOKEN or SONAR_ORGANIZATION not set)`.
- **actionlint** reports no findings for `ci.yml`.

**Not verified yet:** a real analysis and the quality gate. Both need the user's SonarQube Cloud organization, projects and token (runbook steps 1–4), then one manual CI run (step 5).

### 2026-10-04 — SonarQube Cloud live, all seven quality gates green

The user created the organization `comicnerd23`, the seven monorepo projects and the token, and stored `SONAR_TOKEN` and `SONAR_ORGANIZATION`. Pushed as `c4529ea`, `2cc7706` and `25e744d`.

**What went wrong on the way, in order:**
1. **The runbook's UI steps were out of date** (the user noticed). Tokens are now under **My account → Access Tokens → Personal Tokens** and have an expiration date. After the organization is created, an import offer for the repository appears, and taking it creates an extra single project. The user did end up with an 8th project, `comicNerd23_eventTicketingSystem`, and deleted it. The project-level **Administration** menu is hidden while a project only shows its setup screen. The runbook was rewritten against SonarQube Cloud's current docs.
2. **[Run 37221049177](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37221049177) (push):** every test passed. Every Sonar step failed: the Maven scanner reported `invalid header value`, the npm scanner a 403.
   - `2cc7706` makes `ci.js` strip whitespace and control characters from the token and log only its length and character set.
   - [Run 37221325305](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37221325305) then showed `token length 129, contains other characters, removed 5 whitespace/control character(s)`. The secret held more than the token. A SonarQube Cloud token is 40 characters, letters, digits and underscores only.
   - The user revoked that token and stored a new one. The runbook now pipes the value through `tr -d '\r\n '` and prints its length first.
   - **My own mistake:** the patch that added the `tr -d` line wrote a real carriage return into the runbook. Fixed in `25e744d`.
3. **[Run 37221988170](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37221988170) (manual, all targets):** the token was right (40 characters) and all seven analyses were uploaded, but **all seven quality gates failed**.
   - The new-code definition "Number of days: 30", which I had recommended in the runbook, counted almost every line of this young codebase as new code.
   - My assumption in ADR-023 that the first analysis wouldn't block was therefore wrong.
   - SonarQube Cloud doesn't show gate details for public projects without logging in (`alert_status` is missing from the public measures API), so the failed conditions themselves weren't visible.
4. **Fix, chosen by the user ("baseline = now"):** the new-code definition is now **Previous version** in all seven projects. The user set it with `api/settings/set`, and the public `api/settings/values` confirmed `previous_version`. The project versions never change, so the first analysis is the baseline.

**Verified:** [run 37224299777](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37224299777) (manual, all targets) is **green in 4m01s**, and **all seven projects report `QUALITY GATE STATUS: PASSED`**. This confirms that "Previous version" uses the first analysis as the baseline when the version never changes, which SonarQube Cloud's docs leave open.

**First measurements** (public measures API):

| Project | Lines of code | Coverage | Bugs | Vulnerabilities | Code smells | Duplication |
|---|---|---|---|---|---|---|
| api-gateway | 121 | 5.9 % | 0 | 0 | 0 | 0 % |
| event-service | 1,071 | 83.5 % | 0 | 0 | 0 | 2.4 % |
| booking-service | 1,129 | 89.6 % | 0 | 1 | 0 | 0 % |
| payment-service | 728 | 88.9 % | 0 | 0 | 0 | 0 % |
| notification-service | 697 | 87.9 % | 0 | 0 | 0 | 0 % |
| waitlist-service | 621 | 73.8 % | 0 | 1 | 0 | 0 % |
| frontend | 800 | 82.1 % | 0 | 0 | 0 | 0 % |

- **api-gateway's 5.9 % is real:** it has 11 lines of Java, and 8 of them are `RequiredConfigurationCheck`, which only loads in the prod profile. The routes are YAML.
- **The two vulnerabilities** are both `javasecurity:S5145` (log injection, MINOR): request values are logged unsanitized in `BookingService.holdSeat()` (`BookingService.java:87`) and `WaitlistService.join()` (`WaitlistService.java:57`). They are existing code, so they don't fail the gate. They are open for a small follow-up.

### 2026-10-04 — the two log-injection findings (S5145)

**Practically not exploitable:** every logged value is a `java.util.UUID`. `HoldSeatRequest.seatId`, the waitlist request's `eventId` and the `userId` header are parsed into UUIDs during request binding. A UUID string holds only hex digits and dashes, so no line break can reach the log. Sonar's taint analysis flags them because the values come from the request.

**Fix:** both log statements now log the values from the entity returned by `repository.save()`, instead of the request parameters. The values are identical, but the request data no longer flows straight into the log.
- `BookingService.holdSeat()`: `booking.getSeatId()` instead of `request.getSeatId()`.
- `WaitlistService.join()`: `entry.getUserId()` and `entry.getEventId()` instead of the method parameters.

**Verified locally:** both services compile, and their `*ServiceTest` suites pass.

**SonarQube Cloud's taint analysis** only runs in the CI analysis, so whether the findings close needs the push and its CI run.

**Verified:** [run 37226319297](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37226319297) (push of `7cb653a`) is green.
- Only booking-service and waitlist-service ran. Both quality gates passed.
- Both S5145 issues are `CLOSED / FIXED` (18:57 UTC).
- Both projects now report `vulnerabilities=0`, and coverage is unchanged (89.6 % and 73.8 %).

**SonarQube token:** the user chose **no expiration**. Such a token still lapses after 60 days without use, which is now noted in the runbook.

### 2026-10-04 — CodeQL and Dependabot on (ADR-024)

**Enabled through the repository API:**
- Dependabot alerts (`PUT /vulnerability-alerts` → 204).
- Dependabot security updates (`PUT /automated-security-fixes` → 204; it reads back `enabled: true`).
- CodeQL default setup, `query_suite=default`.

**`.github/dependabot.yml`:** weekly version updates on Mondays for Maven (`/services/*`), npm (`/frontend`), GitHub Actions and Docker (the seven Dockerfiles). Minor and patch updates are grouped into one PR per ecosystem, major updates come one by one, and nothing auto-merges.

**First CodeQL run** ([37226819849](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37226819849), CodeQL 2.27.1): **green in 3m24s**.

| Language | Rules | Findings |
|---|---|---|
| java-kotlin | 76 | 0 (build mode none, so Java 25 needs no build) |
| javascript-typescript | 87 | 2 |
| actions | 17 | 0 |

**The two findings** are both `js/disabling-certificate-validation` (high), in `deploy-prod.js`. Both are deliberate (ADR-022):
- **Line 234:** `rejectUnauthorized: false` in `inspectServedCertificate()`. The inspection has to read the certificate even when it isn't trusted, so it can print the issuer and expiry. It reports `socket.authorized` separately, and with `letsencrypt-prod` the deploy fails if the certificate isn't trusted.
- **Line 291:** `NODE_TLS_REJECT_UNAUTHORIZED = "0"`, set only for `letsencrypt-staging` and `selfsigned`, whose certificates are untrusted by design. It only affects the deploy script's own smoke-check requests.

Whether to dismiss them in code scanning (alerts #1 and #2) with this reasoning is the user's decision.

**Noted:** CodeQL default setup runs on GitHub's own `ubuntu-latest` runner, so its jobs still show the Ubuntu 26 migration notice. Default setup can't pin the runner; only advanced setup (a workflow file) can.

**Dependabot's first findings:** 24 open alerts, all in `frontend/package-lock.json`: 1 critical, 7 high, 9 medium, 7 low.
- Only `@angular/router` is a direct runtime dependency (GHSA-ff3f-86qr-9cv3, an SSR-only denial of service; this app doesn't use SSR). The rest are transitive dev dependencies of the Angular tooling: `piscina`, `undici`, `hono`, `brace-expansion`, `ip-address`, `fast-uri` and `http-cache-semantics`.
- Dependabot opened security PRs #1–#5 right away. #2–#5 pass CI, and the user reviews them.
- **PR #1 failed:** it bumped only `@angular/router` to 21.2.24, but Angular's packages pin each other exactly (`peer @angular/common@"21.2.24"`), so `npm ci` failed with `ERESOLVE`.

**Fix:**
- `.github/dependabot.yml` groups `@angular/*`, `@angular-devkit/*` and `@schematics/angular` for version updates and for security updates.
- Angular was bumped as a group with `ng update @angular/core@21.2.25 @angular/cli@21.2.24`: the framework packages to 21.2.25, the tooling to 21.2.24.
- **Found:** two plain `npm install`s, and one after editing `package.json`, failed with `ERESOLVE` against the installed tree. `ng update` cleans `node_modules` and resolves the whole group.
- **Verified locally:** all `@angular/*` packages are on one version, with no invalid entries in `npm ls`. 34 tests pass (88.9 % line coverage), `ng build` succeeds, and `npm audit --omit=dev` finds **0 vulnerabilities**. 15 dev-only findings remain, partly covered by #2–#5.
- PR #1 was closed in favour of this change.

**Verified:** [CI run 37228192251](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37228192251) (push of `74ea910`) is green, and the frontend's Sonar gate passed.
- **Dependabot alerts went from 24 to 17.** `@angular/router`, `qs` and part of the `undici` alerts are fixed through the regenerated lockfile, and Dependabot closed PR #2 itself.
- **Not seen:** a CodeQL run on that push. Default setup didn't run on every push here; the weekly scan covers it.

**The first version-update run** (from the first `dependabot.yml`, before the Angular group existed) opened PRs #6–#16. The user reviewed them. Sorted:
- **Closed with a reason:**
  - **#7, #8, #10:** Angular 22, one package per PR. #7 and #10 failed with `ERESOLVE`. → Angular 22 becomes a slice of its own, using `ng update`.
  - **#9:** TypeScript 7 on Angular 21, failed and conflicted.
  - **#11:** the Docker group. It "updated" `maven:3.9-eclipse-temurin-25` to `maven:3-eclipse-temurin-24`, a JDK downgrade, so all six service Docker builds failed. The tests themselves passed.
- **`dependabot.yml` changes:** `typescript` joins both Angular groups, and the Docker updates ignore the `maven` image.
- **Merged** after green CI: the security and minor updates #3, #4, #5, #6, #13, #14, #15 and #16.
- **#12** (Node 24 → 26 in the frontend's build image, a major): merged too, at the user's decision, once CI was green.
- **The two CodeQL alerts** (#1 `deploy-prod.js:291`, #2 `deploy-prod.js:234`) were dismissed as "won't fix" at the user's decision, with the ADR-022 reasoning as the comment. Code scanning now has 0 open alerts.

**Next, a slice of its own:** Angular 21 → 22 with `ng update` (including TypeScript), when the user wants it.

**Verified after all merges:** [CI run 37230284838](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37230284838) (manual, all targets, on the combined state) is green in 5m00s, and all seven Sonar gates passed. The push runs of the individual merges had cancelled each other, so the combined state needed this run. Dependabot went from 24 open alerts to **1**, and code scanning has 0.

**The last alert:** `piscina` 5.2.0 is critical (GHSA-67c8-pqhq-4rmx, fixed in 5.3.2).
- `@angular/build` and `@angular-devkit/build-angular` 21.2.24 pin it exactly, and no Angular 21 release has the fix, so Dependabot can't fix it.
- **The user chose option 3:** an npm `overrides` entry `"piscina": "5.3.2"` now, removed again with Angular 22.
- **Verified locally:** `npm ls` shows piscina 5.3.2 (`overridden`) with no invalid entries. 34 tests pass (88.9 %), and `ng build` succeeds.

**Found along the way:** `npm audit` reports 7 high findings around `braces` (GHSA-vfj7-8cjw-p6xm, stack exhaustion; every `braces` version is affected, so no fix exists yet). They come only through `webpack-dev-server` in `@angular-devkit/build-angular`. That legacy package is still used by just two builders in `angular.json`: `dev-server` and `extract-i18n`. Both exist in `@angular/build`, which already does `application` and `unit-test`. Switching those two builders would drop `@angular-devkit/build-angular`, and with it the whole webpack chain. That's a candidate for the Angular 22 slice.

**Verified:** [CI run 37235209334](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37235209334) (push of `4528ef6`) is green, the frontend's Sonar gate passed, and Dependabot marked the piscina alert as **fixed**. **Dependabot and code scanning now both have 0 open alerts.**

**PR #17 closed:** it moved the frontend's runtime image `nginxinc/nginx-unprivileged` from 1.30 to 1.31.
- nginx's even minor versions are the stable line, and its odd ones mainline. Dependabot only compared the numbers.
- The PR's green CI only proved that the image builds. CI doesn't start the container, so it said nothing about the `/api` proxy or the WebSocket under 1.31.
- The user chose to stay on stable. `dependabot.yml` now ignores minor and major updates of that image.
- **Stable patch releases (1.30.x) don't come through Dependabot.** The tag `1.30-alpine` floats: the next run of **Release images** picks up the newest 1.30.x, and prod gets it only with the following **Deploy prod**. Local builds keep the cached base image unless pulled (`docker build --pull`). The same holds for `node:26-alpine`, `eclipse-temurin:25-jre` and `maven:3.9-eclipse-temurin-25`; ADR-024 has the details. The next stable line (1.32) is a manual change.
- **Correction:** I first told the user that 1.30.x patches would still arrive automatically through Dependabot. They come through the floating tag, and only with a new release plus deploy.

### 2026-10-05 — Angular 21 → 22 (ADR-025), done

**What changed:**
- `ng update @angular/core@22 @angular/cli@22`: Angular **22.2.1**, TypeScript **6.0.3**.
- **The user chose to keep Angular 22's new defaults.** `ng update` had added three compatibility shims, and all three were removed again:
  - `ChangeDetectionStrategy.Eager` in all five components. **OnPush is now the default.** The app is zoneless and keeps its component state in signals, so nothing relied on the old behavior.
  - `withXhr()` in `provideHttpClient()` (app and two specs). `HttpClient` now uses Fetch; the app has no progress events.
  - The suppression of `nullishCoalescingNotNullable` and `optionalChainNotNullable` in both tsconfigs. Without it, the build reports no warning.
- `angular.json`: `dev-server` and `extract-i18n` now run on `@angular/build`. `@angular-devkit/build-angular` is removed, and with it webpack and `webpack-dev-server`.
- **The `piscina` override is removed.** `@angular/build` 22.2.1 depends on `piscina` 5.3.2 itself.

**Verified locally:**
- `npm audit`: **0 vulnerabilities** (the `braces` findings are gone). `npm ls piscina`: 5.3.2 under `@angular/build`.
- `ng build` without warnings, `ng test` 34/34, `node ci.js frontend` PASS.
- In Chrome, on `ng serve` (new builder) against a reduced compose stack (Postgres, Redis, Kafka, event-service, booking-service, api-gateway):
  - Event list with the 13 seeded events.
  - Seat map: a section toggle updates `aria-expanded`, and a hold made from outside the page turns a seat from available to held through the WebSocket, without a reload (650/0 → 649/1).
  - Clicking a seat holds it and opens the booking page, whose countdown keeps ticking (9:51 → 9:47).
  - No console errors.

**Found along the way:**
- **The full compose stack plus `ng serve` ran the machine out of memory.** Docker stopped answering, and Claude Code stopped the background `ng serve`. After a Docker Desktop restart, **kind's `ticketing-control-plane` container was running again**: it restarts with Docker Desktop. It was stopped with `docker stop ticketing-control-plane`. With the reduced stack, the containers used about 1.5 GB.
- After the restart, the six app containers came back by themselves (`restart: unless-stopped`) but the infra containers didn't, as CLAUDE.md describes.

**Pushed as `874c777`:** [CI run 37288101747](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37288101747) is green, the frontend's Sonar gate passed, and Dependabot and code scanning both still have 0 open alerts. [Release images run 37290822544](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37290822544) published `sha-874c777` for all seven images (amd64 + arm64). **Deployed to prod:** Claude Code's auto mode blocked starting `deploy-prod.yml`, so the user started it. [Deploy prod run 37291705915](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37291705915) is green with `sha-874c777`. The checks from runbook part C3 pass: frontend 200, `/api/events` 200, `/api/actuator/health` 404, and HTTP redirects to HTTPS. Prod serves `main-RHE3Z5L7.js`, the same bundle hash as the local Angular 22 build.

### Where things stand on 2026-10-05 (resume here)

**Live:**
- **Prod** runs at `https://<IP with dashes>.sslip.io`, with a Let's Encrypt certificate valid until 2027-01-02 that cert-manager renews by itself.
- **CI** runs on Ubuntu 26.04 with Node 24 actions. It analyzes every target in SonarQube Cloud (seven projects, Free plan, baseline = Previous version, all gates green), next to CodeQL and Dependabot.
- **Open alerts:** 0 Dependabot, 0 code scanning. No Dependabot PRs are open.

**Prod runs `master`:** release images `sha-874c777`, deployed on 2026-10-05, with the log-injection fix, the dependency updates and Angular 22 (ADR-025).

**Next options, the user to pick:**
1. **Secret scanning and push protection**, both repository settings, still off.
2. **A license file** (the user's choice, e.g. MIT). It is also the prerequisite for Sonar's OSS plan (ADR-023).
3. **(f) Rancher Manager**, optional, the last item of ADR-016.

**Keep in mind:**
- **`SONAR_TOKEN` has no expiration**, but it lapses after 60 days without use (runbook `sonarqube-cloud.md`).
- **Locally, cleaned up on 2026-10-05:**
  - In Rancher Desktop, the test deployment (`ticketing` namespace), the ClusterIssuers and cert-manager are deleted. cert-manager went through its pinned manifest, so no CRDs or webhooks remain, and no volumes are left.
  - Rancher Desktop is shut down (machine constraint: never together with Docker Desktop).
  - The local prod kubeconfig `~/.kube/ticketing-prod.yaml` is deleted. Runbook part D, step 1 fetches it again when needed.
- **Docker Desktop restarts kind's control plane.** Check `docker ps` for `ticketing-control-plane` after an engine restart and stop it, unless kind is wanted.
- **The full compose stack doesn't fit next to `ng serve` on this machine.** For frontend checks, run the reduced stack above.

---

## Outstanding housekeeping

- Testcontainers Cloud free plan is capped at 50 min/month — reserve integration test runs for genuine breakage or final pre-commit verification, not speculative re-runs. Since ADR-015, CI-like local runs (`node ci.js …`) should use Testcontainers Desktop's local Docker runtime instead, which is free and unlimited; GitHub Actions never uses Testcontainers Cloud.
