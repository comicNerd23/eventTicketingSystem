# ADR-003: Redis SETNX + TTL for Seat Reservation

**Status:** Accepted  
**Date:** 2024-01-01  
**Authors:** Engineering Team

---

## Context

The ticketing platform must prevent two users from simultaneously booking the same seat
(the classic "double-booking" problem). Under high demand (popular concerts), hundreds
of users may attempt to hold the same seat within milliseconds of each other.

Three approaches were evaluated for the seat reservation mechanism:

### Option A: PostgreSQL optimistic locking
Add a `version` column to the seat row. Read the version, attempt to update where
`version = <read_version>`. If another transaction updated it first, the update fails
and the client retries.

**Pros:** Simple, no additional infrastructure.  
**Cons:** Under high contention, many transactions fail and retry simultaneously —
creating a "thundering herd" of retries that degrades database performance further.

### Option B: PostgreSQL pessimistic locking (`SELECT FOR UPDATE`)
Lock the seat row when reading it, preventing any other transaction from reading or
writing until the lock is released.

**Pros:** Guaranteed exclusivity, simple logic.  
**Cons:** Row-level locks block all concurrent readers. A seat map with 500 seats
being viewed by 10,000 users simultaneously will cause severe lock contention.
PostgreSQL is not designed for this access pattern.

### Option C: Redis distributed lock (SETNX + TTL)
Use Redis `SET key value NX PX <milliseconds>` to atomically set a key if it does not
exist, with an automatic expiry. The key is the seatId. Only one caller succeeds;
all others get a null response immediately (no blocking).

**Pros:**
- Atomic: `SET NX` is a single Redis command, O(1), no race condition.
- Non-blocking: unsuccessful callers fail fast instead of waiting.
- Self-cleaning: the TTL (10 minutes) automatically releases the hold with no
  cleanup code or scheduled job.
- In-memory: sub-millisecond latency vs. ~1–5ms for a PostgreSQL write.

**Cons:**
- Requires Redis as an additional infrastructure dependency.
- Redis is not a durable store by default. If Redis crashes before the hold is
  committed to PostgreSQL, the hold is lost. (Acceptable: the seat simply becomes
  available again and the user is asked to retry — no data corruption.)

---

## Decision

We adopt **Redis SETNX + TTL** for seat holds.

---

## Implementation Details

**Redis key format:** `seat-hold:{seatId}`  
**Redis value:** JSON `{ "bookingId": "...", "userId": "...", "heldAt": "..." }`  
**TTL:** 600,000ms (10 minutes)

**Hold flow:**
```
SET seat-hold:{seatId} {json} NX PX 600000
→ OK   : hold acquired, create HELD booking record in PostgreSQL
→ null : seat already held, return HTTP 409 Conflict
```

**Expiry detection:**
Redis keyspace notifications are enabled (`notify-keyspace-events KEA`).
`booking-service` subscribes to `__keyevent@0__:expired` and publishes a
`seat-hold-expired` Kafka event when a seat hold TTL fires.

**Explicit release:**
When a hold is confirmed or cancelled, `booking-service` calls `DEL seat-hold:{seatId}`
to release the Redis key immediately (rather than waiting for TTL).

---

## Consequences

### Positive
- Handles Ticketmaster-scale concurrency without database degradation.
- The 10-minute countdown timer in the UI is simply the Redis TTL — no additional
  timer infrastructure needed.
- Non-blocking failure response (HTTP 409) allows the frontend to immediately show
  "Seat taken — choose another."

### Negative / Trade-offs
- Redis failure during checkout loses in-flight holds. Mitigation: Redis persistence
  (AOF) enabled in production. Acceptable risk given the non-financial nature of a hold.
- The seat status in PostgreSQL (`HELD`) and the Redis lock can briefly diverge if a
  crash occurs between the Redis SET and the PostgreSQL INSERT. A scheduled reconciliation
  job (runs every 5 minutes) clears PostgreSQL HELD records whose Redis key no longer exists.
