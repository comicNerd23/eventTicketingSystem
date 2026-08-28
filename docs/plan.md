# Project Plan — Event Ticketing Platform

Portfolio project (concerts/sports/shows) built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack (Spring Boot + Angular) skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 21 (Vitest) · Tailwind CSS 4 · Docker/K8s · GCP Cloud Run · Stripe sandbox · Testcontainers 1.21.4

---

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Specs — OpenAPI 3.1 per service, AsyncAPI 2.x for Kafka, ADRs, C4 diagram | Done |
| 2 | Scaffolding — Spring Boot stubs from specs, Docker Compose infra | Done |
| 3 | Core backend — one service at a time, starting with booking-service | Done |
| 4 | Angular frontend — SVG seat map, countdown timer, WebSocket | Done |
| 5 | DevOps — GitHub Actions CI/CD, K8s manifests, GCP Cloud Run | In progress |

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

**Next up**: CI/CD (GitHub Actions) — build/test pipeline for the 6 Spring Boot services + Angular frontend. Sequenced before distributed tracing so the pipeline can catch regressions from the Kafka-header changes tracing will require. K8s manifests (`k8s/base`, `k8s/overlays/{dev,prod}`) and GCP Cloud Run deploy remain fully unstarted after that.

---

## Outstanding housekeeping

- Testcontainers Cloud free plan is capped at 50 min/month — reserve integration test runs for genuine breakage or final pre-commit verification, not speculative re-runs.
