# ADR-006: UUID Primary Keys over Auto-Incrementing Long

**Status:** Accepted
**Date:** 2026-07-28
**Authors:** Engineering Team

---

## Context

Every entity across the services (`Booking`, `Venue`, `Event`, and their foreign-key
references like `eventId`, `seatId`, `venueId`, `organizerId`) needs a primary key type.
`booking-service` (Phase 2) already used `UUID` via `@GeneratedValue(strategy = GenerationType.UUID)`.
When building `event-service` (Phase 3, Slice 1), the same question came up again for
`Venue.id` and `Event.id`: keep using `UUID`, or switch to an auto-incrementing `Long`
(`GenerationType.IDENTITY`/`SEQUENCE`), which is the more common default in single-database
Spring Boot tutorials?

Several options were considered, not just the binary Long-vs-UUID choice:

1. **Auto-incrementing `Long`** (IDENTITY/SEQUENCE) — smallest, sequential, human-readable.
2. **Random UUID v4** — what `booking-service` already uses.
3. **Time-ordered UUID (UUIDv7)** — same 16-byte UUID type, but high bits encode a timestamp
   so values are roughly monotonically increasing.
4. **ULID** — conceptually the same idea as UUIDv7 (timestamp prefix + random suffix), but
   represented as a 26-char base32 string rather than the native UUID binary format.
5. **Snowflake-style ID** (Twitter/Discord/Instagram pattern) — 64-bit long composed of
   timestamp + shard/worker ID + sequence counter.
6. **Natural/business keys** — e.g. an event's own `(venueId, dateTime)` tuple, instead of a
   surrogate key.

---

## Decision

We use **`UUID` (random, v4)** as the primary key type for every entity in every service,
generated via `@GeneratedValue(strategy = GenerationType.UUID)`. This applies to
`event-service`'s `Venue.id` / `Event.id` and all cross-service foreign-key fields
(`Event.venueId`, `Event.organizerId`, `Booking.eventId`, `Booking.seatId`, `Booking.userId`).

**UUIDv7 is explicitly noted as the likely future upgrade** (see Future Consideration) once
write volume justifies it — it requires no schema or API change, only a swapped ID
generation strategy.

---

## Rationale

### Consistency across service boundaries
`booking-service.Booking.eventId` is a `UUID` today (client-supplied) and will eventually
be a real foreign reference to `event-service.Event.id`. If `event-service` used `Long`
while `booking-service` expects `UUID`, the two services would disagree on the type of the
same logical identifier. Keeping the type uniform everywhere avoids a mismatch at the one
place — `eventId` — where the two services' data already needs to line up.

### No cross-service ID collisions
This project follows database-per-service ([[ADR-001]]). Each service's database
independently assigns its own primary keys. With `Long`/auto-increment, `booking-service`
and `event-service` would each generate `1, 2, 3, ...` with no relationship to each other.
Globally unique UUIDs mean IDs never collide across service boundaries, which matters if
data is ever correlated, merged, or replicated (e.g. a Kafka-driven read model joining
bookings with events).

### IDs are known before the row is committed
`GenerationType.UUID` generates the identifier in-memory before the `INSERT`, unlike
`IDENTITY`/`SEQUENCE`, which only assigns a value once the database round-trip completes.
This matters for the choreography saga ([[ADR-002]]): a service can publish a Kafka event
referencing an entity's ID immediately after constructing it, without waiting on a DB
round-trip to learn the assigned key.

### IDs are exposed in public REST APIs and must not be guessable
Every ID in this project (`/events/{id}`, `/venues/{id}`, `/bookings/{id}`) is exposed
directly in the API surface. Sequential Longs let a client enumerate `/events/45`,
`/events/46`, ... to scrape the entire catalog or infer business volume (event/booking
counts). Random UUIDs are not guessable, closing off the cheapest enumeration attack
without relying solely on authorization checks.

### Why not switch straight to UUIDv7
UUIDv7 keeps every benefit above (16-byte native `uuid` type, unguessable random component,
generated before insert) while also fixing UUIDv4's write-pattern downside (see Performance
Considerations). It was not adopted immediately because it needs a custom ID generator —
Hibernate 7 / Spring Boot 4.1 does not ship a built-in `GenerationType.UUID_V7` strategy, and
native `uuidv7()` support only landed in Postgres 18. Given the project's current scale, the
extra dependency isn't justified yet; UUIDv4 is the simpler default and the migration path to
UUIDv7 later is a generation-strategy swap, not a schema or API change.

### Why not the other options
- **ULID**: same time-ordering benefit as UUIDv7, but is typically stored as `TEXT`/`CHAR(26)`
  rather than Postgres's native 16-byte `uuid` type — larger on disk and slower to compare,
  so it gives up the storage compactness that UUIDv7 keeps for the same sortability benefit.
- **Snowflake-style ID**: matches `Long`'s 8-byte size and gets time-ordering, but requires a
  worker/shard-ID allocation scheme — a coordination problem that isn't worth the operational
  overhead at this project's scale.
- **Natural/business keys**: rejected as a primary key because business keys (venue name,
  event date/time) can change (rescheduling, renaming), and a mutable PK cascades badly
  through every foreign key referencing it. Can still exist as a separate unique constraint
  alongside the surrogate UUID key if needed later.

---

## Performance Considerations (UUIDv4 vs UUIDv7 vs Long, at scale)

### At small scale (this project — thousands to low millions of rows): no measurable difference
The full table and its indexes comfortably fit in Postgres's `shared_buffers`/OS page cache
regardless of key type. Every lookup and insert is effectively an in-memory operation, and the
16-byte vs 8-byte key size difference, or the extra index page splits from random UUIDv4
insert order, cost microseconds that won't show up in practice. This is why any of the options
above would work fine for `event-service`/`booking-service` today — the choice here is about
architectural correctness and future-proofing, not measured performance.

### At large scale (tens of millions+ rows, sustained high insert rate), the differences become real:

1. **Index bloat / page splits.** A B-tree index is kept in sorted key order. Sequential
   Longs and UUIDv7 always insert at the "right edge" of the index — cheap, no reshuffling.
   Random UUIDv4 inserts land at a random point in the keyspace every time, forcing page
   splits throughout the tree. This measurably slows inserts and leaves the index
   meaningfully larger (fragmentation/dead space) than an equivalent sequential-key or
   UUIDv7 index holding the same rows.

2. **Buffer cache locality.** Sequential/time-ordered keys cluster "hot" recent rows on a
   small number of physical pages, keeping the working set small and cache-friendly. Random
   UUIDv4 scatters inserts across the entire index range, so the effective "hot" set becomes
   the whole table. Once the index outgrows RAM, this means more cache misses and random
   disk reads — for any query touching that index, not just inserts.

3. **WAL and vacuum overhead.** More page splits and scattered writes mean more write-ahead
   log volume per insert, and autovacuum works harder maintaining a more fragmented
   structure — an operational cost in I/O, replication bandwidth, and backup size.

4. **Raw key size compounds.** The 16-byte vs 8-byte difference isn't confined to the primary
   key's own index — every foreign key column referencing it (e.g. `Booking.eventId`) and
   every index on those FK columns pays the same size cost, across every referencing table.

### How each option compares on these points
- **Long/bigint**: best on all four points, but reintroduces the enumerability and
  cross-service collision problems this ADR rejects Long for.
- **UUIDv7**: gets the benefit of points 1 and 2 almost for free (time-ordered, so
  append-friendly and cache-local) while keeping full UUID uniqueness/unguessability and the
  native 16-byte Postgres `uuid` type — loses to Long only on raw byte size, not on
  insert/cache behavior.
- **UUIDv4 (current decision)**: worst case on points 1, 2, and 3; same as UUIDv7 on point 4.
- **ULID**: same time-ordering benefit as UUIDv7 for points 1 and 2, but typically stored as
  text rather than Postgres's native binary `uuid` type, so it's larger on disk and slower to
  compare — it inherits the write-pattern benefit but gives up the compact-storage benefit.
- **Snowflake**: best of both worlds (8 bytes, sequential-ish, cache-local) but reintroduces
  the worker-ID coordination problem, which is real operational overhead not justified at
  this project's scale.

The performance case for moving off UUIDv4 only kicks in once indexes stop comfortably
fitting in memory — realistically tens of millions of rows under sustained write load, which
is out of scope for this project today but is exactly the trigger condition named in Future
Consideration below.

---

## Consequences

### Positive
- One identifier type across all services and all foreign-key references — no translation
  or ambiguity at service boundaries.
- IDs are safe to expose in public API responses without leaking enumerable business data.
- IDs can be minted before persistence, which fits the Kafka saga's event-publishing flow.

### Negative / Trade-offs
- **Storage and index size**: `UUID` is 16 bytes vs. 8 for `bigint`. Larger primary key
  columns mean larger secondary indexes wherever the ID is stored as a foreign key.
- **Index fragmentation at high insert volume**: random (v4) UUIDs are not insertion-ordered,
  so B-tree indexes on the primary key take more page splits under heavy write load than a
  sequential `Long` (or UUIDv7) would. At this project's scale (portfolio demo, not
  production throughput) this is not a practical concern — see Performance Considerations.
- **Less human-readable**: `UUID`s are harder to read/type/compare by eye than `Long`s
  during manual testing or debugging (mitigated by `demo.sh` scripting the flows rather
  than requiring manual ID entry).

---

## Future Consideration

If a service ever needs to optimize for very high insert throughput or is showing index
cache-locality pressure (per the Performance Considerations above), `GenerationType.UUID`
(random v4) can be swapped for a time-ordered variant (UUIDv7, once Hibernate/Postgres
tooling around it matures further, or a manually implemented generator) without changing the
column type or any API contract — only the generation strategy changes. No service has hit
that scale yet.

---

---

# ADR-006: UUID-Primärschlüssel statt automatisch hochzählendem Long

**Status:** Akzeptiert  
**Datum:** 2026-07-28  
**Autoren:** Engineering-Team

---

## Kontext

Jede Entity in allen Services (`Booking`, `Venue`, `Event` und ihre Fremdschlüssel-Referenzen
wie `eventId`, `seatId`, `venueId`, `organizerId`) braucht einen Primärschlüsseltyp.
`booking-service` (Phase 2) nutzte bereits `UUID` über
`@GeneratedValue(strategy = GenerationType.UUID)`. Beim Bau des `event-service` (Phase 3,
Slice 1) stellte sich dieselbe Frage erneut für `Venue.id` und `Event.id`: weiter `UUID`
verwenden oder auf einen automatisch hochzählenden `Long` (`GenerationType.IDENTITY`/`SEQUENCE`)
wechseln, der in Spring-Boot-Tutorials mit einer einzigen Datenbank der gängigere Standard ist?

Mehrere Optionen wurden betrachtet, nicht nur die binäre Wahl Long vs. UUID:

1. **Automatisch hochzählender `Long`** (IDENTITY/SEQUENCE) – am kleinsten, sequenziell,
   menschenlesbar.
2. **Zufalls-UUID v4** – was `booking-service` bereits nutzt.
3. **Zeitlich geordnete UUID (UUIDv7)** – gleicher 16-Byte-UUID-Typ, aber die oberen Bits
   kodieren einen Zeitstempel, sodass die Werte ungefähr monoton steigen.
4. **ULID** – konzeptionell dieselbe Idee wie UUIDv7 (Zeitstempel-Präfix + zufälliger Suffix),
   aber als 26-stelliger Base32-String statt im nativen binären UUID-Format dargestellt.
5. **Snowflake-artige ID** (Muster von Twitter/Discord/Instagram) – 64-Bit-Long aus
   Zeitstempel + Shard-/Worker-ID + Sequenzzähler.
6. **Natürliche/fachliche Schlüssel** – z. B. das Tupel `(venueId, dateTime)` eines Events
   anstelle eines Surrogatschlüssels.

---

## Entscheidung

Wir verwenden **`UUID` (zufällig, v4)** als Primärschlüsseltyp für jede Entity in jedem Service,
erzeugt über `@GeneratedValue(strategy = GenerationType.UUID)`. Das gilt für `Venue.id` /
`Event.id` im `event-service` und alle serviceübergreifenden Fremdschlüsselfelder
(`Event.venueId`, `Event.organizerId`, `Booking.eventId`, `Booking.seatId`, `Booking.userId`).

**UUIDv7 wird ausdrücklich als wahrscheinliches künftiges Upgrade genannt** (siehe Ausblick),
sobald das Schreibvolumen es rechtfertigt – es erfordert keine Schema- oder API-Änderung,
sondern nur eine andere Strategie zur ID-Erzeugung.

---

## Begründung

### Konsistenz über Servicegrenzen hinweg
`booking-service.Booking.eventId` ist heute eine `UUID` (vom Client geliefert) und wird
irgendwann eine echte Fremdreferenz auf `event-service.Event.id`. Würde `event-service`
`Long` nutzen, während `booking-service` `UUID` erwartet, wären sich die beiden Services über
den Typ desselben logischen Bezeichners uneinig. Ein einheitlicher Typ überall vermeidet eine
Unstimmigkeit an der einen Stelle – `eventId` –, an der die Daten beider Services bereits
zusammenpassen müssen.

### Keine ID-Kollisionen zwischen Services
Dieses Projekt folgt dem Datenbank-pro-Service-Prinzip ([[ADR-001]]). Die Datenbank jedes
Services vergibt ihre Primärschlüssel unabhängig. Mit `Long`/Auto-Increment würden
`booking-service` und `event-service` jeweils `1, 2, 3, ...` erzeugen, ohne Bezug zueinander.
Global eindeutige UUIDs bedeuten, dass IDs über Servicegrenzen hinweg nie kollidieren – wichtig,
falls Daten je korreliert, zusammengeführt oder repliziert werden (z. B. ein Kafka-getriebenes
Read-Model, das Buchungen mit Events verknüpft).

### IDs sind bekannt, bevor die Zeile committet wird
`GenerationType.UUID` erzeugt den Bezeichner im Speicher vor dem `INSERT`, anders als
`IDENTITY`/`SEQUENCE`, die einen Wert erst nach dem Datenbank-Roundtrip vergeben. Das ist für
die Choreografie-Saga ([[ADR-002]]) wichtig: Ein Service kann ein Kafka-Event mit der ID einer
Entity direkt nach deren Erzeugung veröffentlichen, ohne auf einen DB-Roundtrip zu warten, um
den vergebenen Schlüssel zu erfahren.

### IDs liegen in öffentlichen REST-APIs offen und dürfen nicht erratbar sein
Jede ID in diesem Projekt (`/events/{id}`, `/venues/{id}`, `/bookings/{id}`) ist direkt in der
API sichtbar. Mit sequenziellen Longs könnte ein Client `/events/45`, `/events/46`, ...
durchzählen, um den gesamten Katalog abzugreifen oder das Geschäftsvolumen (Anzahl Events/
Buchungen) abzuleiten. Zufällige UUIDs sind nicht erratbar und schließen den billigsten
Enumerationsangriff, ohne sich allein auf Autorisierungsprüfungen zu verlassen.

### Warum nicht gleich UUIDv7
UUIDv7 behält alle obigen Vorteile (nativer 16-Byte-`uuid`-Typ, nicht erratbare Zufallskomponente,
Erzeugung vor dem Insert) und behebt zusätzlich den Schreibmuster-Nachteil von UUIDv4 (siehe
Leistungsüberlegungen). Es wurde nicht sofort übernommen, weil ein eigener ID-Generator nötig
ist – Hibernate 7 / Spring Boot 4.1 liefert keine eingebaute `GenerationType.UUID_V7`-Strategie,
und native `uuidv7()`-Unterstützung kam erst mit Postgres 18. Bei der aktuellen Größe des
Projekts rechtfertigt sich die zusätzliche Abhängigkeit noch nicht; UUIDv4 ist der einfachere
Standard, und der spätere Wechsel auf UUIDv7 ist ein Austausch der Generierungsstrategie, keine
Schema- oder API-Änderung.

### Warum nicht die anderen Optionen
- **ULID**: gleicher Vorteil der zeitlichen Ordnung wie UUIDv7, wird aber typischerweise als
  `TEXT`/`CHAR(26)` statt im nativen 16-Byte-`uuid`-Typ von Postgres gespeichert – größer auf
  der Platte und langsamer zu vergleichen; man gibt also die kompakte Speicherung auf, die
  UUIDv7 bei gleichem Sortiervorteil behält.
- **Snowflake-artige ID**: entspricht mit 8 Byte der Größe von `Long` und ist zeitlich geordnet,
  erfordert aber ein Schema zur Vergabe von Worker-/Shard-IDs – ein Koordinationsproblem, dessen
  Betriebsaufwand sich im Maßstab dieses Projekts nicht lohnt.
- **Natürliche/fachliche Schlüssel**: als Primärschlüssel abgelehnt, weil sich fachliche
  Schlüssel (Venue-Name, Datum/Uhrzeit eines Events) ändern können (Verschiebung,
  Umbenennung) und ein veränderlicher Primärschlüssel sich schlecht durch alle referenzierenden
  Fremdschlüssel kaskadiert. Bei Bedarf kann er später als separate Unique-Constraint neben dem
  Surrogat-UUID-Schlüssel existieren.

---

## Leistungsüberlegungen (UUIDv4 vs. UUIDv7 vs. Long, im großen Maßstab)

### Im kleinen Maßstab (dieses Projekt – Tausende bis niedrige Millionen Zeilen): kein messbarer Unterschied
Die gesamte Tabelle und ihre Indizes passen unabhängig vom Schlüsseltyp bequem in Postgres'
`shared_buffers`/den OS-Page-Cache. Jede Abfrage und jeder Insert ist praktisch eine
In-Memory-Operation; der Größenunterschied von 16 zu 8 Byte oder die zusätzlichen
Index-Page-Splits durch die zufällige Insert-Reihenfolge bei UUIDv4 kosten Mikrosekunden, die
in der Praxis nicht auffallen. Deshalb würde jede der obigen Optionen für
`event-service`/`booking-service` heute funktionieren – die Wahl betrifft architektonische
Korrektheit und Zukunftssicherheit, nicht gemessene Leistung.

### Im großen Maßstab (zig Millionen+ Zeilen, anhaltend hohe Insert-Rate) werden die Unterschiede real:

1. **Index-Aufblähung / Page-Splits.** Ein B-Tree-Index wird in sortierter Schlüsselreihenfolge
   gehalten. Sequenzielle Longs und UUIDv7 fügen immer am „rechten Rand“ des Index ein – billig,
   ohne Umsortierung. Zufällige UUIDv4 landen jedes Mal an einer zufälligen Stelle im
   Schlüsselraum und erzwingen Page-Splits im gesamten Baum. Das verlangsamt Inserts messbar und
   macht den Index spürbar größer (Fragmentierung/toter Platz) als einen entsprechenden Index mit
   sequenziellem Schlüssel oder UUIDv7 für dieselben Zeilen.

2. **Buffer-Cache-Lokalität.** Sequenzielle/zeitlich geordnete Schlüssel bündeln „heiße“, neue
   Zeilen auf wenigen physischen Seiten, was das Working Set klein und cachefreundlich hält.
   Zufällige UUIDv4 streuen Inserts über den gesamten Indexbereich, sodass das effektive „heiße“
   Set die ganze Tabelle wird. Wächst der Index über den RAM hinaus, bedeutet das mehr
   Cache-Misses und zufällige Plattenzugriffe – für jede Abfrage, die diesen Index berührt, nicht
   nur für Inserts.

3. **WAL- und Vacuum-Aufwand.** Mehr Page-Splits und verstreute Schreibvorgänge bedeuten mehr
   Write-Ahead-Log-Volumen pro Insert, und Autovacuum muss eine stärker fragmentierte Struktur
   pflegen – ein betrieblicher Aufwand bei I/O, Replikationsbandbreite und Backup-Größe.

4. **Die rohe Schlüsselgröße potenziert sich.** Der Unterschied von 16 zu 8 Byte beschränkt sich
   nicht auf den Index des Primärschlüssels selbst – jede Fremdschlüsselspalte, die darauf
   verweist (z. B. `Booking.eventId`), und jeder Index auf diesen FK-Spalten zahlt dieselben
   Größenkosten, in jeder referenzierenden Tabelle.

### Wie die Optionen bei diesen Punkten abschneiden
- **Long/bigint**: bei allen vier Punkten am besten, führt aber die Erratbarkeit und die
  serviceübergreifenden Kollisionen wieder ein, wegen derer dieses ADR Long ablehnt.
- **UUIDv7**: erhält die Vorteile von Punkt 1 und 2 fast geschenkt (zeitlich geordnet, daher
  append-freundlich und cache-lokal) und behält volle UUID-Eindeutigkeit/Nicht-Erratbarkeit und
  den nativen 16-Byte-`uuid`-Typ von Postgres – verliert gegenüber Long nur bei der rohen
  Bytegröße, nicht beim Insert-/Cache-Verhalten.
- **UUIDv4 (aktuelle Entscheidung)**: schlechtester Fall bei Punkt 1, 2 und 3; bei Punkt 4
  gleich wie UUIDv7.
- **ULID**: gleicher Vorteil der zeitlichen Ordnung wie UUIDv7 bei Punkt 1 und 2, wird aber
  typischerweise als Text statt im nativen binären `uuid`-Typ von Postgres gespeichert, ist also
  größer auf der Platte und langsamer zu vergleichen – es erbt den Schreibmuster-Vorteil, gibt
  aber den Vorteil kompakter Speicherung auf.
- **Snowflake**: das Beste aus beiden Welten (8 Byte, annähernd sequenziell, cache-lokal), führt
  aber das Koordinationsproblem der Worker-IDs wieder ein – echter Betriebsaufwand, der im
  Maßstab dieses Projekts nicht gerechtfertigt ist.

Das Leistungsargument für den Wechsel weg von UUIDv4 greift erst, wenn die Indizes nicht mehr
bequem in den Speicher passen – realistisch bei zig Millionen Zeilen unter anhaltender
Schreiblast. Das liegt heute außerhalb des Projektumfangs, ist aber genau die im Ausblick
unten genannte Auslösebedingung.

---

## Konsequenzen

### Positiv
- Ein Bezeichnertyp über alle Services und alle Fremdschlüsselreferenzen – keine Übersetzung
  oder Mehrdeutigkeit an Servicegrenzen.
- IDs lassen sich bedenkenlos in öffentlichen API-Antworten zeigen, ohne aufzählbare
  Geschäftsdaten preiszugeben.
- IDs können vor der Persistierung erzeugt werden, was zum Event-Publishing-Ablauf der
  Kafka-Saga passt.

### Negativ / Abwägungen
- **Speicher- und Indexgröße**: `UUID` hat 16 Byte gegenüber 8 bei `bigint`. Größere
  Primärschlüsselspalten bedeuten größere Sekundärindizes überall dort, wo die ID als
  Fremdschlüssel gespeichert ist.
- **Index-Fragmentierung bei hohem Insert-Volumen**: Zufällige (v4) UUIDs sind nicht in
  Einfügereihenfolge, daher erzeugen B-Tree-Indizes auf dem Primärschlüssel unter hoher
  Schreiblast mehr Page-Splits als ein sequenzieller `Long` (oder UUIDv7). Im Maßstab dieses
  Projekts (Portfolio-Demo, kein Produktionsdurchsatz) ist das praktisch kein Problem – siehe
  Leistungsüberlegungen.
- **Weniger menschenlesbar**: `UUID`s sind beim manuellen Testen oder Debuggen schwerer mit dem
  Auge zu lesen/einzutippen/zu vergleichen als `Long`s (abgemildert dadurch, dass `demo.sh` die
  Abläufe skriptet, statt manuelle ID-Eingabe zu verlangen).

---

## Ausblick

Muss ein Service je auf sehr hohen Insert-Durchsatz optimiert werden oder zeigt er Druck auf
die Index-Cache-Lokalität (siehe Leistungsüberlegungen oben), kann `GenerationType.UUID`
(zufällig, v4) gegen eine zeitlich geordnete Variante getauscht werden (UUIDv7, sobald die
Hibernate-/Postgres-Unterstützung weiter gereift ist, oder ein selbst implementierter
Generator) – ohne Änderung des Spaltentyps oder eines API-Vertrags; nur die
Generierungsstrategie ändert sich. Bisher hat kein Service diesen Maßstab erreicht.
