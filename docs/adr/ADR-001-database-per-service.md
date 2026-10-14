# ADR-001: Database-per-Service Pattern

**Status:** Accepted  
**Date:** 2024-01-01  
**Authors:** Engineering Team

---

## Context

The platform is composed of six services: api-gateway, event-service, booking-service,
payment-service, notification-service, and waitlist-service. We need to decide how
database ownership is organised across these services.

Two primary options were considered:

1. **Shared database** — all services read/write to a single PostgreSQL instance with
   separate schemas or tables.
2. **Database-per-service** — each service owns its own schema (or separate DB instance
   in production) and no other service may access it directly.

---

## Decision

We adopt **database-per-service**. Each service owns its data entirely. Cross-service
data access happens only via published APIs (REST or Kafka events), never by direct
database joins.

---

## Rationale

### Independent deployability
If services share a database, a schema migration in one service risks breaking another.
With database-per-service, each service can migrate its own schema independently and
deploy without coordinating with other teams.

### Independent scalability
`booking-service` is write-heavy under load (seat holds, confirmations). `event-service`
is read-heavy (event browsing). Separate databases allow each to be scaled, tuned, and
backed up on its own schedule.

### Failure isolation
A long-running query in one service cannot take down the connection pool for another.
Database failures are isolated to the owning service.

### Technology freedom
While all services currently use PostgreSQL, database-per-service allows us to introduce
Redis as a primary store for session data or a document store for notification templates
later without impacting other services.

---

## Consequences

### Positive
- True service autonomy and independent release cadence.
- No cross-service schema coupling.
- Each service can choose its own migration tooling (Flyway used across all services
  in this project for consistency).

### Negative / Trade-offs
- **No cross-service JOINs.** Queries that would be a single SQL join (e.g., "show me
  all bookings with event details") must be assembled in the application layer by calling
  multiple service APIs or by maintaining local read models built from Kafka events.
- **Eventual consistency.** A booking record in booking-service holds `eventId` but not
  the full event title. The title is fetched from event-service at read time (or cached
  locally). If event-service is unavailable, the booking service falls back to a cached
  version.
- **More infrastructure.** Six separate database schemas to manage. Mitigated by Docker
  Compose for local dev and Flyway for versioned migrations.

---

## Implementation Notes

- Local development: single PostgreSQL container with one schema per service
  (`event_db`, `booking_db`, `payment_db`, `notification_db`, `waitlist_db`).
- Production: one Cloud SQL instance per service on GCP.
- Flyway migration scripts live under each service's `src/main/resources/db/migration/`.

---

---

# ADR-001: Datenbank-pro-Service-Muster

**Status:** Akzeptiert  
**Datum:** 2024-01-01  
**Autoren:** Engineering-Team

---

## Kontext

Die Plattform besteht aus sechs Services: api-gateway, event-service, booking-service,
payment-service, notification-service und waitlist-service. Wir müssen entscheiden, wie
die Datenbankhoheit auf diese Services verteilt wird.

Zwei Hauptoptionen wurden betrachtet:

1. **Gemeinsame Datenbank** – alle Services lesen/schreiben in eine einzige PostgreSQL-Instanz
   mit getrennten Schemas oder Tabellen.
2. **Datenbank pro Service** – jeder Service besitzt sein eigenes Schema (oder in Produktion
   eine eigene DB-Instanz), und kein anderer Service darf direkt darauf zugreifen.

---

## Entscheidung

Wir führen **Datenbank pro Service** ein. Jeder Service besitzt seine Daten vollständig.
Der serviceübergreifende Datenzugriff erfolgt nur über veröffentlichte APIs (REST oder
Kafka-Events), niemals über direkte Datenbank-Joins.

---

## Begründung

### Unabhängige Deploybarkeit
Teilen sich Services eine Datenbank, kann eine Schemamigration in einem Service einen
anderen beschädigen. Bei Datenbank pro Service kann jeder Service sein Schema eigenständig
migrieren und deployen, ohne sich mit anderen Teams abzustimmen.

### Unabhängige Skalierbarkeit
`booking-service` ist unter Last schreiblastig (Sitzreservierungen, Bestätigungen),
`event-service` ist leselastig (Event-Suche). Getrennte Datenbanken erlauben es, beide
unabhängig zu skalieren, zu tunen und nach eigenem Zeitplan zu sichern.

### Fehlerisolation
Eine lang laufende Abfrage in einem Service kann nicht den Connection-Pool eines anderen
lahmlegen. Datenbankausfälle bleiben auf den besitzenden Service begrenzt.

### Technologiefreiheit
Obwohl aktuell alle Services PostgreSQL nutzen, erlaubt Datenbank pro Service, später Redis
als primären Speicher für Sitzungsdaten oder einen Dokumentenspeicher für
Benachrichtigungsvorlagen einzuführen, ohne andere Services zu beeinträchtigen.

---

## Konsequenzen

### Positiv
- Echte Service-Autonomie und unabhängiger Release-Rhythmus.
- Keine serviceübergreifende Schema-Kopplung.
- Jeder Service kann sein eigenes Migrationswerkzeug wählen (in diesem Projekt wird aus
  Konsistenzgründen überall Flyway verwendet).

### Negativ / Abwägungen
- **Keine serviceübergreifenden JOINs.** Abfragen, die sonst ein einzelner SQL-Join wären
  (z. B. „alle Buchungen mit Event-Details anzeigen“), müssen in der Anwendungsschicht
  zusammengesetzt werden – durch Aufrufe mehrerer Service-APIs oder durch lokale Read-Models,
  die aus Kafka-Events aufgebaut werden.
- **Eventual Consistency.** Ein Buchungsdatensatz im booking-service enthält die `eventId`,
  aber nicht den vollständigen Event-Titel. Der Titel wird beim Lesen vom event-service
  geholt (oder lokal gecacht). Ist der event-service nicht erreichbar, greift der
  booking-service auf eine gecachte Version zurück.
- **Mehr Infrastruktur.** Sechs getrennte Datenbankschemas sind zu verwalten. Abgemildert
  durch Docker Compose für die lokale Entwicklung und Flyway für versionierte Migrationen.

---

## Hinweise zur Umsetzung

- Lokale Entwicklung: ein einzelner PostgreSQL-Container mit einem Schema pro Service
  (`event_db`, `booking_db`, `payment_db`, `notification_db`, `waitlist_db`).
- Produktion: eine Cloud-SQL-Instanz pro Service auf GCP.
- Flyway-Migrationsskripte liegen unter `src/main/resources/db/migration/` des jeweiligen Services.
