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

---

---

# ADR-010: Live-Verfügbarkeit von Events – Batch-Komposition gegen booking-service

**Status:** Akzeptiert  
**Datum:** 2026-07-30  
**Autoren:** Engineering-Team

---

## Kontext

Beim manuellen Testen des Frontends durch den Nutzer entdeckt: Die Event-Liste zeigte „650/650
Plätze verfügbar“ für ein Event, dessen eigener Sitzplan bereits einen Sitz mit Status `BOOKED`
hatte. Die Ursache wurde durch Lesen des Codes ermittelt, nicht angenommen:

- `Event.availableSeats` ist eine einfache Datenbankspalte, die genau einmal gesetzt wird –
  `event.setAvailableSeats(venue.getCapacity())` in `EventService.createEvent` – und danach
  nirgends in der Codebasis mehr aktualisiert wird (bestätigt per Suche im gesamten Repo: null
  weitere Aufrufstellen). `EventResponse.from(event)`, genutzt von `GET /events/{id}` und
  `GET /events`, gab schlicht diesen dauerhaft veralteten Stand vom ersten Tag wieder.
- Der Sitzplan (`EventService.getSeatMap`) hatte dieses Problem nie, weil er bereits live
  komponiert: Er ruft `GET /bookings/events/{eventId}/active-seats` des booking-service auf und
  führt bei jeder Anfrage den echten Status pro Sitz zusammen. Die Event-Liste tat nie das
  Entsprechende – sie zeigte immer die ursprüngliche Kapazität, unabhängig von realer
  Buchungsaktivität.

`getEvent`/`listEvents` so zu korrigieren, dass sie Live-Daten wie `getSeatMap` komponieren,
erfordert pro Event einen Aufruf an den booking-service. `listEvents` liefert eine Seite von
Events (bis zu `size`, z. B. 10–20) – die Frage ist, ob daraus ein serviceübergreifender Aufruf
pro Event der Seite oder ein gebündelter Aufruf für die ganze Seite wird.

### Option A: Den bestehenden Endpunkt pro Event wiederverwenden, N-mal
`GET /bookings/events/{eventId}/active-seats` (den Endpunkt, den `getSeatMap` bereits nutzt)
einmal pro Event aufrufen, das eine Live-Zahl braucht.

**Stärken:**
- Kein neuer Backend-Endpunkt – nutzt genau das, was schon existiert.
- Einfach: Nirgends ist Aggregationslogik nötig.

**Schwächen:**
- Ein echtes N+1-Muster: Eine Seite mit 10 Events bedeutet 10 sequenzielle
  serviceübergreifende Aufrufe, nur um eine Listenseite darzustellen. Im Maßstab dieses
  Projekts begrenzt und tolerierbar, aber ein Muster, das mit wachsender Seitengröße oder
  Eventzahl schlechter wird – die falsche Form, um sie bewusst zu bauen, wenn die Lösung ohnehin
  neu entworfen wird.

### Option B: Neuer Batch-Endpunkt im booking-service
`GET /bookings/events/active-seats/counts?eventIds=a,b,c` hinzufügen, der pro angefragtem
Event eine Zahl aktiver Sitze in einer einzigen Antwort liefert.

**Stärken:**
- Auf genau einen zusätzlichen serviceübergreifenden Aufruf begrenzt, unabhängig von der
  Seitengröße – eine Seite mit 10 Events kostet denselben einen Aufruf wie eine mit 2.
- Zeigt ein Batch-Auflösungsmuster (erst aggregieren, dann auffächern), statt eine N+1-Form
  standardmäßig hinzunehmen.

**Schwächen:**
- Mehr Arbeit: eine neue Repository-Abfrage, ein DTO, eine Service-Methode und ein
  Controller-Endpunkt im booking-service sowie eine neue Client-Methode im event-service – statt
  Bestehendes wiederzuverwenden.

---

## Entscheidung

Wir führen **Option B** ein – einen neuen Batch-Endpunkt im booking-service.

---

## Begründung

Da dieser Fix unabhängig von der Option den Lesepfad des booking-service berührt (in beiden
Fällen ein neuer Client-Aufruf vom event-service), ist der Batch-Endpunkt nicht wesentlich
invasiver als Option A – er ist eine ähnlich kleine, klar umrissene Ergänzung (eine
Repository-Abfrage, ein DTO, eine Service-Methode, ein Controller-Endpunkt), die vermeidet, von
Anfang an ein bekanntermaßen schlechtes Anfragemuster in den Fix einzubauen. `EventService`
ruft nun `bookingServiceClient.getActiveSeatCounts(eventIds)` genau einmal pro `GET /events`
oder `GET /events/{id}` auf, egal ob die Seite 1 oder 20 Events hat.

---

## Konsequenzen

### Positiv
- Behebt den echten Fehler: `GET /events` und `GET /events/{id}` melden nun tatsächlich
  Live-Verfügbarkeit, passend zu dem, was der Sitzplan schon immer zeigte.
- Begrenzte Kosten: ein zusätzlicher serviceübergreifender Aufruf pro Request, unabhängig von
  der Seitengröße.
- `BookingService.getActiveSeatCounts` liefert einen Eintrag pro *angefragter* Event-ID
  (Standardwert `0` für Events ohne aktive Buchungen), sodass der Aufrufer nie raten muss, ob
  ein fehlender Eintrag „null“ bedeutet oder „der Server hat dieses Event vergessen“.

### Negativ / Abwägungen
- **Neue Kopplung im Fehlerfall.** Das Komponieren von Live-Daten bedeutet, dass die
  `BookingServiceUnavailableException` nun auch aus `getEvent`/`listEvents` herauspropagiert,
  nicht nur aus `getSeatMap` – ein Ausfall des booking-service legt nun auch die Event-Liste und
  die Event-Detail-Endpunkte lahm, nicht nur den Sitzplan. Das ist eine bewusste Entscheidung
  (entsprechend der Entscheidung „kein stilles Zurückfallen auf eine falsche Zahl“, die
  `getSeatMap` bereits getroffen hat) und kein Versehen: Stillschweigend auf den veralteten Wert
  `Event.availableSeats` zurückzufallen, würde den ursprünglichen Fehler unter anderem Auslöser
  (kurzzeitige Nichtverfügbarkeit des booking-service) wieder einführen, statt dass ein
  Netzwerkfehler ehrlich sichtbar wird.
- **Die gespeicherte Datenbankspalte `Event.availableSeats` ist nun vestigial.** Sie wird bei der
  Event-Erstellung weiterhin einmal geschrieben (wo sie korrekt ist, da noch keine Buchungen
  existieren), aber für keine API-Antwort mehr gelesen – jeder Lesepfad berechnet live neu. Die
  Spalte ganz zu entfernen wäre eine echte Schemamigration, außerhalb des Umfangs dieses
  Bugfixes; sie bleibt bestehen und wird hier ausdrücklich vermerkt, damit sie später nicht wie
  ein Versehen wirkt.
