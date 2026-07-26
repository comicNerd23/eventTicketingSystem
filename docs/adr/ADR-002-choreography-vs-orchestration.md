# ADR-002: Saga Pattern — Choreography over Orchestration

**Status:** Accepted  
**Date:** 2024-01-01  
**Authors:** Engineering Team

---

## Context

The core booking flow spans multiple services: booking-service, payment-service,
notification-service, and waitlist-service. When a user confirms a booking,
a distributed transaction must:

1. Initiate payment (payment-service)
2. On success: confirm the booking and issue a ticket (booking-service → notification-service)
3. On failure: release the seat hold and notify the waitlist (booking-service → waitlist-service)

This is a classic Saga pattern use case. Two implementation styles were evaluated:

### Option A: Saga Orchestration
A dedicated "Booking Saga Orchestrator" service holds the full flow definition.
It sends explicit commands to each participant service and waits for replies.

**Pros:**
- The entire flow is visible in one place (the orchestrator).
- Easy to add conditional branching (e.g., different flows for VIP tickets).
- Simpler to implement retry and timeout logic centrally.

**Cons:**
- The orchestrator becomes a single point of failure.
- It is tightly coupled to every participant service — adding or removing a step
  requires changing the orchestrator.
- Introduces an additional service to build, deploy, and operate.

### Option B: Saga Choreography
Services react to events they care about and publish new events as a result.
No central coordinator. Each service knows its own role in the flow.

**Pros:**
- No single point of failure.
- Each service is independently deployable — a new participant just subscribes
  to the relevant topic without modifying anything else.
- Aligns naturally with the event-driven architecture already in place.

**Cons:**
- The overall flow is implicit — it must be reconstructed from the AsyncAPI spec
  and distributed tracing.
- Harder to debug without observability tooling.

---

## Decision

We adopt **Saga Choreography** using Kafka as the event bus.

---

## Rationale

Our Saga has a linear, non-branching flow (hold → pay → confirm or rollback). The
complexity that makes orchestration attractive (complex conditional branching, many
participants) does not apply here.

The coupling risk of the orchestrator pattern is significant: every change to the
booking flow requires modifying the orchestrator, creating a deployment bottleneck.
With choreography, each service is a true autonomous unit.

We mitigate the observability downside by:
1. Including a correlation ID (`bookingId`) on every Kafka event, enabling end-to-end
   trace reconstruction.
2. Adding distributed tracing via OpenTelemetry (propagated through Kafka message headers).
3. Using Kafdrop locally to inspect topic messages during development.

---

## Consequences

### Positive
- No orchestrator service to build or maintain.
- Each service team can evolve their service independently.
- Adding a new consumer (e.g., an analytics service) to any topic requires zero changes
  to existing services.

### Negative / Trade-offs
- The complete booking flow is not visible in any single file. The AsyncAPI spec
  (`specs/asyncapi/kafka-events.yaml`) serves as the authoritative documentation of
  the overall event flow.
- Cyclic event chains (service A triggers B which triggers A) must be carefully
  avoided. Topic ownership boundaries in the AsyncAPI spec prevent this.

---

## Compensating Transactions (Rollback)

| Step failed | Compensating action |
|---|---|
| Payment failed | booking-service releases Redis lock, publishes `seat-released` |
| Booking cancelled post-payment | booking-service publishes `booking-cancelled`; payment-service issues Stripe refund |
| Seat hold expired (TTL) | booking-service publishes `seat-hold-expired`; waitlist-service promotes next user |
