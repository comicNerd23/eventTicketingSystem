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

---

---

# ADR-002: Saga-Muster – Choreografie statt Orchestrierung

**Status:** Akzeptiert  
**Datum:** 2024-01-01  
**Autoren:** Engineering-Team

---

## Kontext

Der zentrale Buchungsablauf erstreckt sich über mehrere Services: booking-service,
payment-service, notification-service und waitlist-service. Wenn ein Nutzer eine Buchung
bestätigt, muss eine verteilte Transaktion Folgendes leisten:

1. Zahlung anstoßen (payment-service)
2. Bei Erfolg: Buchung bestätigen und Ticket ausstellen (booking-service → notification-service)
3. Bei Fehlschlag: Sitzreservierung freigeben und die Warteliste benachrichtigen
   (booking-service → waitlist-service)

Das ist ein klassischer Anwendungsfall für das Saga-Muster. Zwei Umsetzungsstile wurden bewertet:

### Option A: Saga-Orchestrierung
Ein eigener „Booking-Saga-Orchestrator“-Service enthält die vollständige Ablaufdefinition.
Er sendet explizite Befehle an jeden beteiligten Service und wartet auf Antworten.

**Vorteile:**
- Der gesamte Ablauf ist an einer Stelle sichtbar (im Orchestrator).
- Bedingte Verzweigungen sind leicht ergänzbar (z. B. andere Abläufe für VIP-Tickets).
- Retry- und Timeout-Logik lässt sich zentral einfacher umsetzen.

**Nachteile:**
- Der Orchestrator wird zum Single Point of Failure.
- Er ist eng an jeden beteiligten Service gekoppelt – das Hinzufügen oder Entfernen eines
  Schritts erfordert eine Änderung am Orchestrator.
- Ein zusätzlicher Service muss gebaut, deployt und betrieben werden.

### Option B: Saga-Choreografie
Services reagieren auf Events, die sie betreffen, und veröffentlichen daraufhin neue Events.
Es gibt keinen zentralen Koordinator. Jeder Service kennt seine eigene Rolle im Ablauf.

**Vorteile:**
- Kein Single Point of Failure.
- Jeder Service ist unabhängig deploybar – ein neuer Teilnehmer abonniert einfach das
  passende Topic, ohne etwas anderes zu ändern.
- Passt natürlich zur bereits vorhandenen ereignisgesteuerten Architektur.

**Nachteile:**
- Der Gesamtablauf ist implizit – er muss aus der AsyncAPI-Spezifikation und dem
  verteilten Tracing rekonstruiert werden.
- Ohne Observability-Werkzeuge schwerer zu debuggen.

---

## Entscheidung

Wir führen **Saga-Choreografie** mit Kafka als Event-Bus ein.

---

## Begründung

Unsere Saga hat einen linearen, nicht verzweigenden Ablauf (reservieren → bezahlen →
bestätigen oder zurückrollen). Die Komplexität, die Orchestrierung attraktiv macht
(komplexe bedingte Verzweigungen, viele Teilnehmer), trifft hier nicht zu.

Das Kopplungsrisiko des Orchestrator-Musters ist erheblich: Jede Änderung am Buchungsablauf
erfordert eine Anpassung des Orchestrators und erzeugt einen Deployment-Engpass. Bei
Choreografie ist jeder Service eine wirklich autonome Einheit.

Den Nachteil bei der Observability mildern wir wie folgt:
1. Eine Korrelations-ID (`bookingId`) an jedem Kafka-Event ermöglicht die durchgängige
   Rekonstruktion des Ablaufs.
2. Verteiltes Tracing über OpenTelemetry (weitergegeben in den Kafka-Message-Headern).
3. Lokal wird Kafdrop genutzt, um Topic-Nachrichten während der Entwicklung zu prüfen.

---

## Konsequenzen

### Positiv
- Kein Orchestrator-Service, der gebaut oder gewartet werden muss.
- Jedes Service-Team kann seinen Service unabhängig weiterentwickeln.
- Einen neuen Consumer (z. B. einen Analytics-Service) zu einem Topic hinzuzufügen,
  erfordert keinerlei Änderungen an bestehenden Services.

### Negativ / Abwägungen
- Der vollständige Buchungsablauf ist in keiner einzelnen Datei sichtbar. Die AsyncAPI-
  Spezifikation (`specs/asyncapi/kafka-events.yaml`) dient als maßgebliche Dokumentation
  des gesamten Event-Flusses.
- Zyklische Eventketten (Service A löst B aus, B löst A aus) müssen sorgfältig vermieden
  werden. Die Topic-Eigentumsgrenzen in der AsyncAPI-Spezifikation verhindern das.

---

## Kompensierende Transaktionen (Rollback)

| Fehlgeschlagener Schritt | Kompensierende Aktion |
|---|---|
| Zahlung fehlgeschlagen | booking-service gibt den Redis-Lock frei und veröffentlicht `seat-released` |
| Buchung nach Zahlung storniert | booking-service veröffentlicht `booking-cancelled`; payment-service veranlasst die Stripe-Rückerstattung |
| Sitzreservierung abgelaufen (TTL) | booking-service veröffentlicht `seat-hold-expired`; waitlist-service befördert den nächsten Nutzer |
