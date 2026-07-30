# ADR-010: Live Event Availability — Batch Composition Against booking-service

**Status:** Accepted
**Date:** 2026-07-30
**Authors:** Engineering Team

---

## Context

Found by the user manually testing the frontend: the events list showed "650/650 seats
available" for an event whose own seat map already had a seat `BOOKED`. Root-caused by
reading the code, not assumed:

- `Event.availableSeats` is a plain database column set exactly once —
  `event.setAvailableSeats(venue.getCapacity())` in `EventService.createEvent` — and never
  updated anywhere else in the codebase (confirmed with a full-repo search: zero other call
  sites). `EventResponse.from(event)`, used by both `GET /events/{id}` and `GET /events`,
  simply echoed this permanently-stale, day-one snapshot.
- The seat map (`EventService.getSeatMap`) never had this problem, because it already
  composes live: it calls booking-service's `GET /bookings/events/{eventId}/active-seats`
  and merges real per-seat status on every request. The events list never did the
  equivalent — it was always showing original capacity, regardless of any real booking
  activity.

Fixing `getEvent`/`listEvents` to compose live data the same way `getSeatMap` does needs a
call to booking-service per event. `listEvents` returns a page of events (up to `size`, e.g.
10-20) — the question is whether that becomes one cross-service call per event on the page,
or one batched call for the whole page.

### Option A: Reuse the existing per-event endpoint, N times
Call `GET /bookings/events/{eventId}/active-seats` (the endpoint `getSeatMap` already uses)
once per event needing a live count.

**Strengths:**
- No new backend endpoint — reuses exactly what already exists.
- Simple: no aggregation logic needed anywhere.

**Weaknesses:**
- A real N+1 pattern: a page of 10 events means 10 sequential cross-service calls just to
  render one list page. Bounded and tolerable at this project's scale, but a pattern that
  gets worse, not better, as the page size or event count grows — the wrong shape to build
  deliberately when the fix is being designed from scratch anyway.

### Option B: New batch endpoint on booking-service
Add `GET /bookings/events/active-seats/counts?eventIds=a,b,c`, returning one active-seat
count per requested event in a single response.

**Strengths:**
- Bounded to exactly one extra cross-service call regardless of page size — a page of 10
  events costs the same one call as a page of 2.
- Demonstrates a batch-resolution pattern (aggregate-then-fan-out) rather than accepting an
  N+1 shape by default.

**Weaknesses:**
- More work: a new repository query, DTO, service method, and controller endpoint on
  booking-service, plus a new client method on event-service — versus reusing something
  that already exists.

---

## Decision

We adopt **Option B** — a new batch endpoint on booking-service.

---

## Rationale

Since this fix requires touching booking-service's read path regardless of which option is
chosen (a new client call from event-service either way), the batch endpoint is not
meaningfully more invasive than Option A — it's a similarly small, well-scoped addition
(one repository query, one DTO, one service method, one controller endpoint) that avoids
building a known-bad request pattern into the fix from day one. `EventService` now calls
`bookingServiceClient.getActiveSeatCounts(eventIds)` exactly once per `GET /events` or
`GET /events/{id}` request, whether the page has 1 event or 20.

---

## Consequences

### Positive
- Fixes the real bug: `GET /events` and `GET /events/{id}` now report genuinely live
  availability, matching what the seat map has always shown.
- Bounded cost: one extra cross-service call per request, independent of page size.
- `BookingService.getActiveSeatCounts` returns one entry per *requested* event id (defaulting
  to `0` for events with no active bookings), so the caller never has to guess whether a
  missing entry means "zero" or "the server forgot about this event."

### Negative / Trade-offs
- **New failure-mode coupling.** Composing live data means `BookingServiceUnavailableException`
  now propagates out of `getEvent`/`listEvents`, not just `getSeatMap` — a booking-service
  outage now also breaks the events list and event detail endpoints, not only the seat map.
  This is a deliberate choice (matching the "no silent degrade to a wrong number" decision
  `getSeatMap` already made) rather than an oversight: silently falling back to the stale
  `Event.availableSeats` value would just reintroduce the original bug under a different
  trigger (booking-service being briefly unavailable) instead of a network error surfacing
  honestly.
- **The stored `Event.availableSeats` database column is now vestigial.** It's still written
  once at event creation (where it's accurate, since zero bookings exist yet) but is never
  read for any API response anymore — every read path recomputes live. Removing the column
  entirely would be a genuine schema migration, out of scope for this bug fix; left in place
  and noted here explicitly so it doesn't read as an oversight later.
