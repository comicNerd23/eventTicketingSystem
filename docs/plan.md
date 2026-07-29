# Project Plan — Event Ticketing Platform

Portfolio project (concerts/sports/shows) built with Spec-Driven Development to demonstrate Kafka/event-driven architecture, microservices, and full-stack (Spring Boot + Angular) skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache Kafka · PostgreSQL (per service) · Redis · Angular 17+ · Docker/K8s · GCP Cloud Run · Stripe sandbox · Testcontainers 1.21.4

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

**Next up:** retire `payment-simulator` and wire payment-service into the live saga (deferred since payment-service's Slice 1 — see note above), deepen event-service (seat map generation, blocking full seat-level binding), or start `api-gateway`.

### Remaining services (not yet scoped into slices)
api-gateway

---

## Outstanding housekeeping

- Testcontainers Cloud free plan is capped at 50 min/month — reserve integration test runs for genuine breakage or final pre-commit verification, not speculative re-runs.
