# ADR-004: Kafka as the Event Bus (vs. RabbitMQ)

**Status:** Accepted  
**Date:** 2024-01-01  
**Authors:** Engineering Team

---

## Context

The platform requires asynchronous messaging between services to implement the
Saga choreography pattern, seat hold expiry notifications, and waitlist promotions.
Two mature message brokers were evaluated:

### Option A: RabbitMQ
A traditional message broker implementing AMQP. Messages are pushed to consumers
and acknowledged/deleted once consumed.

**Strengths:**
- Mature, simple to operate.
- Flexible routing with exchanges (fanout, topic, direct, headers).
- Lower resource footprint for simple task queues.
- Messages are consumed and deleted — storage stays bounded without configuration.

**Weaknesses:**
- **No message replay.** Once a message is consumed and acknowledged, it is gone.
  If `notification-service` was down for 2 hours, those messages cannot be
  recovered from RabbitMQ.
- **Single consumer per message** (by default). Fan-out requires explicit fanout
  exchanges and bindings — it's not a first-class concept.
- **No ordered log.** Cannot ask "give me all events for bookingId X in order."
- Not suited for event sourcing or CQRS read-model rebuilding.

### Option B: Apache Kafka
A distributed commit log. Messages are persisted to disk and retained for a
configurable period (7 days by default). Multiple independent consumer groups can
each read the same topic from their own offset.

**Strengths:**
- **Message retention and replay.** If a consumer is down, it reads from its last
  committed offset when it comes back — no messages lost.
- **Native fan-out.** Multiple consumer groups independently consume the same topic.
  `booking-cancelled` is consumed by payment-service, notification-service, AND
  waitlist-service without any broker-side configuration — each group maintains its
  own offset.
- **Ordered partitions.** Messages with the same key (e.g., `bookingId`) always land
  on the same partition, guaranteeing ordering per booking.
- **Kafka Streams** can be added later for real-time analytics (e.g., seats sold per
  minute per event) without introducing a separate stream processing framework.
- Industry standard in enterprise Java ecosystems (Spring Kafka, Confluent ecosystem).

**Weaknesses:**
- Higher operational complexity than RabbitMQ (ZooKeeper/KRaft, partition rebalancing).
- Larger footprint for small message volumes.
- Message acknowledgement semantics are poll-based (consumer commits offsets) rather
  than push-based, which requires careful idempotency handling on the consumer side.

---

## Decision

We adopt **Apache Kafka** as the event bus.

---

## Rationale

The fan-out requirement alone justifies Kafka. The `booking-cancelled` event must be
consumed independently by three services. In RabbitMQ this requires three separate
queues bound to a fanout exchange — adding a fourth consumer requires a broker
configuration change. In Kafka, a new consumer group just starts reading the topic;
no broker changes needed.

Message durability is the second key factor. This platform uses a choreography Saga
where services depend on events to drive state transitions. Losing a `payment-completed`
event because the booking-service was briefly down would leave a booking permanently
stuck in PAYMENT_PENDING state. Kafka's retention prevents this.

---

## Consequences

### Positive
- Zero message loss for consumers that experience brief downtime.
- New consumers (analytics, audit log, future services) subscribe to existing topics
  with no changes to producers or other consumers.
- Event ordering guarantees per partition (keyed on aggregateId).

### Negative / Trade-offs
- **Idempotency required on all consumers.** Because Kafka guarantees at-least-once
  delivery, a consumer may receive the same event twice (e.g., after a rebalance).
  Every consumer must check for duplicate `eventId` before processing. A processed
  event ID table per service handles this.
- **Operational overhead.** Mitigated locally by using Docker Compose with a single
  Kafka broker and KRaft mode (no ZooKeeper). In production, managed Confluent Cloud
  or GCP Pub/Sub (Kafka-compatible) eliminates operational burden.

---

## Kafka Configuration

| Setting | Development | Production |
|---|---|---|
| Partitions per topic | 3 | 3 |
| Replication factor | 1 | 3 |
| Retention | 7 days | 7 days |
| Auto offset reset | `earliest` | `earliest` |
| Acks | `all` | `all` |
| Idempotent producer | `true` | `true` |
