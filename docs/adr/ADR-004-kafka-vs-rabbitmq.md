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

---

---

# ADR-004: Kafka als Event-Bus (statt RabbitMQ)

**Status:** Akzeptiert  
**Datum:** 2024-01-01  
**Autoren:** Engineering-Team

---

## Kontext

Die Plattform benötigt asynchrone Nachrichtenübermittlung zwischen den Services, um das
Saga-Choreografie-Muster, Benachrichtigungen über abgelaufene Sitzreservierungen und
Beförderungen von der Warteliste umzusetzen. Zwei ausgereifte Message-Broker wurden bewertet:

### Option A: RabbitMQ
Ein klassischer Message-Broker, der AMQP implementiert. Nachrichten werden an Consumer
gepusht und nach dem Verbrauch bestätigt bzw. gelöscht.

**Stärken:**
- Ausgereift, einfach zu betreiben.
- Flexibles Routing über Exchanges (fanout, topic, direct, headers).
- Geringerer Ressourcenbedarf bei einfachen Task-Queues.
- Nachrichten werden verbraucht und gelöscht – der Speicherbedarf bleibt ohne Konfiguration
  begrenzt.

**Schwächen:**
- **Kein Replay von Nachrichten.** Ist eine Nachricht verbraucht und bestätigt, ist sie weg.
  War `notification-service` zwei Stunden lang down, lassen sich diese Nachrichten aus
  RabbitMQ nicht wiederherstellen.
- **Standardmäßig ein Consumer pro Nachricht.** Fan-out erfordert explizite Fanout-Exchanges
  und Bindings – es ist kein erstklassiges Konzept.
- **Kein geordnetes Log.** Man kann nicht fragen: „Gib mir alle Events zur bookingId X in
  Reihenfolge.“
- Ungeeignet für Event Sourcing oder den Neuaufbau von CQRS-Read-Models.

### Option B: Apache Kafka
Ein verteiltes Commit-Log. Nachrichten werden auf der Platte persistiert und für einen
konfigurierbaren Zeitraum (standardmäßig 7 Tage) aufbewahrt. Mehrere unabhängige
Consumer-Groups können dasselbe Topic jeweils ab ihrem eigenen Offset lesen.

**Stärken:**
- **Aufbewahrung und Replay von Nachrichten.** Ist ein Consumer ausgefallen, liest er nach
  der Rückkehr ab seinem zuletzt committeten Offset weiter – keine Nachricht geht verloren.
- **Natives Fan-out.** Mehrere Consumer-Groups konsumieren dasselbe Topic unabhängig
  voneinander. `booking-cancelled` wird von payment-service, notification-service UND
  waitlist-service konsumiert, ohne dass der Broker konfiguriert werden muss – jede Gruppe
  führt ihren eigenen Offset.
- **Geordnete Partitionen.** Nachrichten mit demselben Schlüssel (z. B. `bookingId`)
  landen immer in derselben Partition, was die Reihenfolge pro Buchung garantiert.
- **Kafka Streams** kann später für Echtzeit-Analysen ergänzt werden (z. B. verkaufte Plätze
  pro Minute und Event), ohne ein separates Stream-Processing-Framework einzuführen.
- Industriestandard im Enterprise-Java-Umfeld (Spring Kafka, Confluent-Ökosystem).

**Schwächen:**
- Höhere Betriebskomplexität als RabbitMQ (ZooKeeper/KRaft, Partition-Rebalancing).
- Größerer Ressourcenbedarf bei kleinen Nachrichtenmengen.
- Die Bestätigungssemantik ist Poll-basiert (der Consumer committet Offsets) statt
  Push-basiert, was auf Consumer-Seite sorgfältige Idempotenz-Behandlung erfordert.

---

## Entscheidung

Wir führen **Apache Kafka** als Event-Bus ein.

---

## Begründung

Allein die Fan-out-Anforderung rechtfertigt Kafka. Das Event `booking-cancelled` muss von
drei Services unabhängig konsumiert werden. In RabbitMQ braucht das drei separate Queues,
die an einen Fanout-Exchange gebunden sind – ein vierter Consumer erfordert eine
Broker-Konfigurationsänderung. In Kafka beginnt eine neue Consumer-Group einfach, das Topic
zu lesen; Broker-Änderungen sind nicht nötig.

Die Dauerhaftigkeit der Nachrichten ist der zweite Schlüsselfaktor. Diese Plattform nutzt eine
Choreografie-Saga, in der Services auf Events angewiesen sind, um Zustandsübergänge
auszulösen. Ginge ein `payment-completed`-Event verloren, weil der booking-service kurz
ausgefallen war, bliebe eine Buchung dauerhaft im Zustand PAYMENT_PENDING hängen. Kafkas
Aufbewahrung verhindert das.

---

## Konsequenzen

### Positiv
- Kein Nachrichtenverlust für Consumer, die kurz ausfallen.
- Neue Consumer (Analytics, Audit-Log, künftige Services) abonnieren bestehende Topics,
  ohne dass Producer oder andere Consumer geändert werden müssen.
- Reihenfolgegarantien pro Partition (Schlüssel: aggregateId).

### Negativ / Abwägungen
- **Idempotenz bei allen Consumern erforderlich.** Da Kafka At-least-once-Zustellung
  garantiert, kann ein Consumer dasselbe Event zweimal erhalten (z. B. nach einem
  Rebalance). Jeder Consumer muss vor der Verarbeitung auf doppelte `eventId` prüfen. Eine
  Tabelle verarbeiteter Event-IDs pro Service übernimmt das.
- **Betrieblicher Mehraufwand.** Lokal abgemildert durch Docker Compose mit einem einzelnen
  Kafka-Broker im KRaft-Modus (ohne ZooKeeper). In Produktion nimmt ein verwaltetes
  Confluent Cloud oder GCP Pub/Sub (Kafka-kompatibel) den Betriebsaufwand ab.

---

## Kafka-Konfiguration

| Einstellung | Entwicklung | Produktion |
|---|---|---|
| Partitionen pro Topic | 3 | 3 |
| Replikationsfaktor | 1 | 3 |
| Aufbewahrung | 7 Tage | 7 Tage |
| Auto Offset Reset | `earliest` | `earliest` |
| Acks | `all` | `all` |
| Idempotenter Producer | `true` | `true` |
