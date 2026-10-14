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

---

---

# ADR-003: Redis SETNX + TTL für die Sitzreservierung

**Status:** Akzeptiert  
**Datum:** 2024-01-01  
**Autoren:** Engineering-Team

---

## Kontext

Die Ticketing-Plattform muss verhindern, dass zwei Nutzer gleichzeitig denselben Sitzplatz
buchen (das klassische „Double-Booking“-Problem). Bei hoher Nachfrage (beliebte Konzerte)
können Hunderte Nutzer innerhalb weniger Millisekunden versuchen, denselben Platz zu
reservieren.

Drei Ansätze für den Reservierungsmechanismus wurden bewertet:

### Option A: Optimistisches Locking in PostgreSQL
Eine `version`-Spalte wird zur Sitzzeile hinzugefügt. Die Version wird gelesen, dann wird
ein Update mit `version = <gelesene_version>` versucht. Hat eine andere Transaktion zuerst
aktualisiert, schlägt das Update fehl und der Client wiederholt den Versuch.

**Vorteile:** Einfach, keine zusätzliche Infrastruktur.  
**Nachteile:** Bei starker Konkurrenz scheitern viele Transaktionen und wiederholen
gleichzeitig – eine „Thundering Herd“ von Wiederholungen, die die Datenbankleistung
weiter verschlechtert.

### Option B: Pessimistisches Locking in PostgreSQL (`SELECT FOR UPDATE`)
Die Sitzzeile wird beim Lesen gesperrt, sodass keine andere Transaktion sie lesen oder
schreiben kann, bis die Sperre aufgehoben wird.

**Vorteile:** Garantierte Exklusivität, einfache Logik.  
**Nachteile:** Zeilensperren blockieren alle gleichzeitigen Leser. Ein Sitzplan mit 500
Plätzen, den 10.000 Nutzer gleichzeitig ansehen, verursacht starke Lock-Konkurrenz.
PostgreSQL ist für dieses Zugriffsmuster nicht ausgelegt.

### Option C: Verteilter Redis-Lock (SETNX + TTL)
Mit Redis `SET key value NX PX <Millisekunden>` wird ein Schlüssel atomar gesetzt, falls er
nicht existiert, mit automatischem Ablauf. Der Schlüssel ist die seatId. Nur ein Aufrufer
hat Erfolg; alle anderen erhalten sofort eine Null-Antwort (kein Blockieren).

**Vorteile:**
- Atomar: `SET NX` ist ein einzelner Redis-Befehl, O(1), ohne Race Condition.
- Nicht blockierend: Erfolglose Aufrufer scheitern schnell, statt zu warten.
- Selbstreinigend: Die TTL (10 Minuten) gibt die Reservierung automatisch frei – ohne
  Aufräumcode oder geplanten Job.
- Im Arbeitsspeicher: Latenz unter einer Millisekunde gegenüber ca. 1–5 ms bei einem
  PostgreSQL-Schreibvorgang.

**Nachteile:**
- Redis ist eine zusätzliche Infrastrukturabhängigkeit.
- Redis ist standardmäßig kein dauerhafter Speicher. Stürzt Redis ab, bevor die Reservierung
  in PostgreSQL festgeschrieben wurde, geht sie verloren. (Akzeptabel: Der Platz wird
  einfach wieder frei und der Nutzer wird gebeten, es erneut zu versuchen – keine
  Datenkorruption.)

---

## Entscheidung

Wir führen **Redis SETNX + TTL** für Sitzreservierungen ein.

---

## Umsetzungsdetails

**Redis-Schlüsselformat:** `seat-hold:{seatId}`  
**Redis-Wert:** JSON `{ "bookingId": "...", "userId": "...", "heldAt": "..." }`  
**TTL:** 600.000 ms (10 Minuten)

**Ablauf der Reservierung:**
```
SET seat-hold:{seatId} {json} NX PX 600000
→ OK   : Reservierung erhalten, HELD-Buchungsdatensatz in PostgreSQL anlegen
→ null : Platz bereits reserviert, HTTP 409 Conflict zurückgeben
```

**Erkennung des Ablaufs:**  
Redis-Keyspace-Benachrichtigungen sind aktiviert (`notify-keyspace-events KEA`).
`booking-service` abonniert `__keyevent@0__:expired` und veröffentlicht ein
`seat-hold-expired`-Kafka-Event, wenn die TTL einer Reservierung abläuft.

**Explizite Freigabe:**  
Wird eine Reservierung bestätigt oder storniert, ruft `booking-service` `DEL seat-hold:{seatId}`
auf, um den Redis-Schlüssel sofort freizugeben (statt auf die TTL zu warten).

---

## Konsequenzen

### Positiv
- Bewältigt Parallelität im Ticketmaster-Maßstab ohne Leistungseinbruch der Datenbank.
- Der 10-Minuten-Countdown in der UI ist schlicht die Redis-TTL – keine zusätzliche
  Timer-Infrastruktur nötig.
- Die nicht blockierende Fehlerantwort (HTTP 409) erlaubt dem Frontend, sofort
  „Platz vergeben – bitte anderen wählen“ anzuzeigen.

### Negativ / Abwägungen
- Fällt Redis während des Checkouts aus, gehen laufende Reservierungen verloren.
  Gegenmaßnahme: Redis-Persistenz (AOF) ist in Produktion aktiviert. Das Risiko ist
  angesichts des nicht finanziellen Charakters einer Reservierung akzeptabel.
- Der Sitzstatus in PostgreSQL (`HELD`) und der Redis-Lock können kurzzeitig auseinanderlaufen,
  wenn zwischen dem Redis-SET und dem PostgreSQL-INSERT ein Absturz auftritt. Ein geplanter
  Abgleichjob (alle 5 Minuten) löscht PostgreSQL-HELD-Datensätze, deren Redis-Schlüssel
  nicht mehr existiert.
