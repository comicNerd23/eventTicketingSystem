# Project Plan — Event Ticketing Platform

Portfolio project (concerts/sports/shows) built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack (Spring Boot + Angular) skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 19 · Tailwind CSS 4 · Docker/K8s · GCP Cloud Run · Stripe sandbox · Testcontainers 1.21.4

---

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Specs — OpenAPI 3.1 per service, AsyncAPI 2.x for Kafka, ADRs, C4 diagram | Done |
| 2 | Scaffolding — Spring Boot stubs from specs, Docker Compose infra | Done |
| 3 | Core backend — one service at a time, starting with booking-service | In progress |
| 4 | Angular frontend — SVG seat map, countdown timer, WebSocket | In progress |
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

- Kafka consumer on `payment-initiated` (own consumer group, `payment-service-consumer-group` — see note below) creates a `PENDING` `Payment` via a stubbed `PaymentGateway` (`StubPaymentGateway`, synchronously returns a fake `pi_stub_...` id — real Stripe SDK deferred; swappable later purely via `payment.gateway.provider` config, no caller changes).
- `GET /payments/{paymentId}`, `GET /payments/bookings/{bookingId}`
- `POST /payments/webhook` — simplified trigger payload (`type`, `paymentIntentId`, optional `failureMessage`) standing in for a real Stripe event; `Stripe-Signature` header presence is enforced, cryptographic verification deferred. Transitions `PENDING` → `SUCCEEDED`/`FAILED`, publishes `payment-completed`/`payment-failed` accordingly.

Spring Boot 4.1.0 / Java 25, real Postgres persistence (own database `ticketing_payments`). 27 tests passing: `PaymentRepositoryTest` (6), `PaymentServiceTest` (11), `PaymentControllerTest` (8), `PaymentSagaIntegrationTest` (2, Kafka+Postgres via Testcontainers, mirrors `BookingSagaIntegrationTest`'s spy-and-await pattern). Wired into `docker/docker-compose.yml` (port 8083) and `demo.sh` (steps 5-7, independent of and appended after the existing booking-service saga flow).

**Not wired into the live saga yet**: `payment-simulator` still plays that role (unchanged, per explicit decision). Both now consume `payment-initiated` under distinct consumer groups — `payment-simulator` kept its existing groupId (`payment-service-group`, the literal the AsyncAPI spec documents for the real service), and `payment-service` was given `payment-service-consumer-group` instead, since giving both the same literal string would have made Kafka split partitions between them rather than deliver to both (a real collision, not just a naming nit). This means `specs/asyncapi/kafka-events.yaml`'s documented groupId for `payment-service` is temporarily held by `payment-simulator` — a known, deliberate inaccuracy while both coexist; reconcile once `payment-simulator` is retired.

Deferred to later slices: wiring payment-service into the real booking saga (replacing payment-simulator — must also stop payment-simulator at that point, or booking-service will receive `payment-completed`/`payment-failed` from both), real Stripe SDK integration behind `PaymentGateway` (webhook signature verification, real `PaymentIntent` creation), `charge.refunded`/`REFUNDED` status and the `booking-cancelled` consumer.

Also fixed while verifying end-to-end: a pre-existing Kafka partition-count race in `docker/docker-compose.yml`, unrelated to payment-service itself but only surfaced by adding a second independent consumer. `booking-service`'s `KafkaConfig` declares `NewTopic` beans requesting 3 partitions, but on a fresh broker a consumer (`payment-simulator` or `payment-service`) can auto-create the topic first via `KAFKA_AUTO_CREATE_TOPICS_ENABLE`, getting the broker's default of 1 partition; `KafkaAdmin` then only *increases* it to 3 once `booking-service` starts, and already-subscribed consumers stay pinned to partition 0 until their next metadata refresh (default 5 minutes) — so a booking whose key hashes to partition 1 or 2 would never be seen by a consumer stuck on partition 0. Fixed by setting `KAFKA_NUM_PARTITIONS: 3` as the broker's default, so whoever auto-creates the topic first gets the right partition count immediately — no race window at all.

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

**Next up:** retire `payment-simulator` and wire payment-service into the live saga, add a `booking-cancelled` consumer to payment-service for real refunds, WebSocket/live seat updates.

---

## Outstanding housekeeping

- Testcontainers Cloud free plan is capped at 50 min/month — reserve integration test runs for genuine breakage or final pre-commit verification, not speculative re-runs.
