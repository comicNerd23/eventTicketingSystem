# ADR-009: Live Seat Updates — Plain WebSocket (not STOMP)

**Status:** Accepted
**Date:** 2026-07-29
**Authors:** Engineering Team

---

## Context

Today, if two people have the same event's seat map open, only the one who
clicks a seat sees it change — everyone else's view is stale until they reload.
Several options were considered for pushing seat status changes to already-open
seat map pages:

### Option A: Do nothing new (poll, or leave it as reload-to-see)
Keep the current behavior, or have the frontend periodically re-fetch
`GET /events/{id}/seats` on a timer.

**Strengths:**
- Zero new backend surface — no new protocol, no new connection lifecycle to
  manage.
- Simple to reason about.

**Weaknesses:**
- Not actually live — either stale until reload, or live only up to the poll
  interval, with wasted requests when nothing changed.
- Doesn't demonstrate real-time push, which the original project plan explicitly
  named as a Phase 4 goal ("SVG seat map, countdown timer, WebSocket").

### Option B: STOMP over WebSocket
Spring's `@EnableWebSocketMessageBroker` + `SimpMessagingTemplate`, topic-based
(`/topic/events/{id}/seats`), consumed on the frontend via a STOMP client
library (`@stomp/stompjs`).

**Strengths:**
- The more full-featured, "idiomatic Spring WebSocket" answer — subscribe/
  unsubscribe frames, a message-broker abstraction, multiple destinations
  multiplexed over one connection.
- Would be the natural choice if this channel needed to grow into something
  bidirectional or multi-topic later (chat, multi-channel notifications).

**Weaknesses:**
- Solves problems this feature doesn't have: the client never sends anything
  over this channel, and there's exactly one destination (seat status for one
  event) per connection, not many.
- Needs a new frontend dependency (`@stomp/stompjs`) purely to speak a protocol
  the backend doesn't need to speak either.

### Option C: Plain WebSocket
Spring's `TextWebSocketHandler` on the backend; the browser's native
`WebSocket` API on the frontend — no framework on either side beyond what's
already there.

**Strengths:**
- Zero new frontend dependency — the browser already has `WebSocket` built in.
- Matches the actual shape of this feature exactly: one-directional server
  push of a small delta (`{seatId, status}`), nothing more.
- Consistent with a pattern already in this codebase: `api-gateway`'s
  `RoutingIntegrationTest` uses a JDK-native `HttpServer` stub instead of a
  mocking library, specifically because the simpler built-in tool was
  sufficient for what that test needed — the same reasoning applies here.

**Weaknesses:**
- More hand-rolled plumbing than STOMP would give for free (session registry
  per event, connection lifecycle, no built-in reconnect) — acceptable at this
  feature's actual scope (see Consequences).

---

## Decision

We adopt a **plain WebSocket** — Option C.

---

## Rationale

This channel is purely one-directional server push — "this seat's status
changed" — the client never sends anything over it, and there's only one
destination per connection. STOMP's value (subscribe/unsubscribe frames, a
message-broker abstraction, multi-destination multiplexing) solves problems
this feature doesn't have, at the cost of a new frontend dependency and more
backend configuration for the same end result. Plain WebSocket matches the
feature's actual shape and this project's existing preference for not reaching
for heavier tooling than a slice actually needs.

---

## Consequences

### Positive
- Real-time UX (seat status changes appear on other open seat maps without a
  reload) with no new frontend dependency.
- The broadcast is driven directly from `BookingService`'s existing
  status-transition code paths (`holdSeat`, `handlePaymentCompleted`,
  `cancelBooking`, `expireHold`, `handlePaymentFailed`) — no new Kafka topic or
  consumer needed purely to re-derive something `booking-service` already
  knows synchronously.

### Negative / Trade-offs
- `booking-service` now owns a second communication protocol (WebSocket)
  alongside REST and Kafka — a real increase in that service's surface area,
  accepted because it's the one service with synchronous knowledge of every
  seat-status transition.
- **No reconnect/backoff logic.** If a client's WebSocket connection drops
  (network blip, server restart), the seat map silently stops receiving live
  updates until the page is reloaded — there's no automatic reconnect attempt.
  This is a real limitation, not a hidden one: acceptable for this portfolio
  project's scope, but a production system would need reconnect-with-backoff
  and likely a missed-update reconciliation strategy (e.g. re-fetch on
  reconnect) that this slice doesn't implement.

---

---

# ADR-009: Live-Sitzplatz-Updates – einfacher WebSocket (nicht STOMP)

**Status:** Akzeptiert  
**Datum:** 2026-07-29  
**Autoren:** Engineering-Team

---

## Kontext

Haben heute zwei Personen den Sitzplan desselben Events geöffnet, sieht nur die Person, die
einen Sitz anklickt, die Änderung – bei allen anderen ist die Ansicht veraltet, bis sie neu
laden. Für das Pushen von Sitzstatusänderungen an bereits geöffnete Sitzplanseiten wurden
mehrere Optionen betrachtet:

### Option A: Nichts Neues (Polling oder Neuladen zum Aktualisieren)
Das aktuelle Verhalten beibehalten oder das Frontend `GET /events/{id}/seats` per Timer
regelmäßig neu abrufen lassen.

**Stärken:**
- Keine neue Backend-Oberfläche – kein neues Protokoll, kein neuer Verbindungslebenszyklus.
- Einfach nachzuvollziehen.

**Schwächen:**
- Nicht wirklich live – entweder veraltet bis zum Neuladen oder nur so aktuell wie das
  Polling-Intervall, mit verschwendeten Requests, wenn sich nichts geändert hat.
- Zeigt keinen Echtzeit-Push, den der ursprüngliche Projektplan ausdrücklich als Ziel von
  Phase 4 nannte („SVG-Sitzplan, Countdown-Timer, WebSocket“).

### Option B: STOMP über WebSocket
Springs `@EnableWebSocketMessageBroker` + `SimpMessagingTemplate`, themenbasiert
(`/topic/events/{id}/seats`), im Frontend über eine STOMP-Client-Bibliothek (`@stomp/stompjs`)
konsumiert.

**Stärken:**
- Die umfangreichere, „idiomatische Spring-WebSocket“-Antwort – Subscribe-/Unsubscribe-Frames,
  eine Message-Broker-Abstraktion, mehrere Ziele über eine Verbindung gebündelt.
- Wäre die natürliche Wahl, wenn dieser Kanal später zu etwas Bidirektionalem oder
  Multi-Topic-Fähigem wachsen müsste (Chat, Benachrichtigungen über mehrere Kanäle).

**Schwächen:**
- Löst Probleme, die dieses Feature nicht hat: Der Client sendet über diesen Kanal nie etwas,
  und pro Verbindung gibt es genau ein Ziel (Sitzstatus für ein Event), nicht viele.
- Braucht eine neue Frontend-Abhängigkeit (`@stomp/stompjs`) allein für ein Protokoll, das auch
  das Backend nicht sprechen müsste.

### Option C: Einfacher WebSocket
Springs `TextWebSocketHandler` im Backend; die native `WebSocket`-API des Browsers im
Frontend – auf keiner Seite ein Framework über das Vorhandene hinaus.

**Stärken:**
- Keine neue Frontend-Abhängigkeit – der Browser hat `WebSocket` bereits eingebaut.
- Entspricht genau der tatsächlichen Form dieses Features: einseitiger Server-Push eines
  kleinen Deltas (`{seatId, status}`), nicht mehr.
- Konsistent mit einem bereits vorhandenen Muster in dieser Codebasis: Der
  `RoutingIntegrationTest` des `api-gateway` nutzt einen JDK-nativen `HttpServer`-Stub statt
  einer Mocking-Bibliothek, gerade weil das einfachere eingebaute Werkzeug für diesen Test
  ausreichte – dieselbe Überlegung gilt hier.

**Schwächen:**
- Mehr handgestrickte Infrastruktur, als STOMP gratis mitbrächte (Session-Registry pro Event,
  Verbindungslebenszyklus, kein eingebautes Reconnect) – im tatsächlichen Umfang dieses
  Features akzeptabel (siehe Konsequenzen).

---

## Entscheidung

Wir führen einen **einfachen WebSocket** ein – Option C.

---

## Begründung

Dieser Kanal ist reiner einseitiger Server-Push – „der Status dieses Sitzes hat sich
geändert“ –, der Client sendet darüber nie etwas, und pro Verbindung gibt es nur ein Ziel.
STOMPs Nutzen (Subscribe-/Unsubscribe-Frames, Message-Broker-Abstraktion, Bündelung mehrerer
Ziele) löst Probleme, die dieses Feature nicht hat, und kostet dafür eine neue
Frontend-Abhängigkeit und mehr Backend-Konfiguration für dasselbe Ergebnis. Der einfache
WebSocket entspricht der tatsächlichen Form des Features und der Präferenz dieses Projekts,
nicht zu schwererem Werkzeug zu greifen, als ein Slice tatsächlich braucht.

---

## Konsequenzen

### Positiv
- Echtzeit-UX (Sitzstatusänderungen erscheinen ohne Neuladen auf anderen geöffneten
  Sitzplänen) ohne neue Frontend-Abhängigkeit.
- Der Broadcast wird direkt aus den bestehenden Statusübergangs-Codepfaden des
  `BookingService` ausgelöst (`holdSeat`, `handlePaymentCompleted`, `cancelBooking`,
  `expireHold`, `handlePaymentFailed`) – kein neues Kafka-Topic und kein neuer Consumer nur
  dafür, etwas neu abzuleiten, was `booking-service` bereits synchron weiß.

### Negativ / Abwägungen
- `booking-service` besitzt nun ein zweites Kommunikationsprotokoll (WebSocket) neben REST und
  Kafka – eine echte Vergrößerung der Oberfläche dieses Services, akzeptiert, weil er der eine
  Service mit synchroner Kenntnis jedes Sitzstatusübergangs ist.
- **Keine Reconnect-/Backoff-Logik.** Bricht die WebSocket-Verbindung eines Clients ab
  (Netzwerkaussetzer, Server-Neustart), erhält der Sitzplan stillschweigend keine Live-Updates
  mehr, bis die Seite neu geladen wird – es gibt keinen automatischen Wiederverbindungsversuch.
  Das ist eine echte, nicht versteckte Einschränkung: Für den Umfang dieses Portfolio-Projekts
  akzeptabel, aber ein Produktivsystem bräuchte Reconnect mit Backoff und vermutlich eine
  Strategie zum Abgleich verpasster Updates (z. B. Neuabruf beim Reconnect), die dieser Slice
  nicht umsetzt.
