# ADR-017: Per-Environment Spring Configuration and Flyway Migrations

**Status:** Accepted
**Date:** 2026-09-30
**Authors:** Engineering Team

---

## Context

ADR-016 decided on two environments: dev (local Kubernetes) and prod (k3s on a free VM). Its
first follow-up slice, (a), is per-environment Spring configuration. The state before this ADR,
verified by reading every `application.yml` and `docker/docker-compose.yml`:

- **Environment overrides already worked**, just implicitly. docker-compose set
  `SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `EVENT_SERVICE_BASE_URL`, and so on,
  and Spring's relaxed binding mapped them onto the properties. There were no profiles.
- **Every service's single `application.yml` hardcoded dev values**:
  - DB credentials `ticketing`/`ticketing`
  - `localhost` for Postgres, Kafka and Redis
  - `DEBUG` logging
  - the gateway's CORS origin, fixed to `http://localhost:4200`

  Nothing stopped a prod deployment from starting with those values if a variable was missing.
- **The schema was owned by Hibernate** (`ddl-auto: update`) in every environment. `update`
  never drops or renames anything, can't express data migrations, and leaves no record of what
  changed — not acceptable once a database holds data worth keeping.
- **Tests**: seven `@DataJpaTest` classes forced `ddl-auto=create-drop`; the integration tests
  relied on `update`. No test checked that the schema a real database gets matches the
  entities.

## Decision

### 1. Three configuration files per service

| File | Holds |
|---|---|
| `application.yml` | Everything that's the same in every environment: ports, Kafka (de)serializers and group ids, JPA settings, actuator, base logging (`INFO`). Also `spring.profiles.default: dev`. |
| `application-dev.yml` | Connection settings as `${VAR:local-default}`, `DEBUG` logging for `com.ticketing`, Flyway `baseline-on-migrate`. |
| `application-prod.yml` | The **same keys as `${VAR}` with no default**. |

- `spring.profiles.default: dev` means local runs, docker compose and the test suites need no
  flag at all — nothing changes for day-to-day work. Prod sets `SPRING_PROFILES_ACTIVE=prod`
  explicitly.
- **Fail fast in prod**: a missing variable must stop startup with `Could not resolve
  placeholder 'DB_PASSWORD'`, instead of silently falling back to `localhost` or the dev
  password.
- **Placeholders without a default are not enough on their own**, which was found by
  testing, not assumed. The first negative test started event-service with the prod profile
  and no `DB_PASSWORD`:
  - It failed with `password authentication failed for user "ticketing"`, not with the
    placeholder error.
  - Cause: Spring Boot's `@ConfigurationProperties` binding **leaves unresolvable
    placeholders in place**, so the literal string `${DB_PASSWORD}` was sent as the password.
  - For `REDIS_HOST` or `KAFKA_BOOTSTRAP_SERVERS`, the same literal would not fail at startup
    at all, only on the first connection.
- **Fix: a `RequiredConfigurationCheck` in every service's `config` package**, active only
  with `@Profile("prod")`.
  - It is a static `BeanFactoryPostProcessor`, so it runs before any bean is created —
    before the DataSource, Flyway, Kafka or Redis.
  - It resolves every property from the application config files strictly: every
    `OriginTrackedMapPropertySource`, via `Environment.getProperty`.
  - It is generic: new prod placeholders are covered automatically, with no list of required
    keys to keep in sync.
  - Re-run of the negative test: startup stops with `Could not resolve placeholder
    'DB_PASSWORD' in value "${DB_PASSWORD}"`.
  - The class is duplicated per service, consistent with ADR-001: there is no shared library
    between services.

### 2. One set of environment variable names, everywhere

| Variable | Used by |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | the five services with a database |
| `KAFKA_BOOTSTRAP_SERVERS` | booking, payment, notification, waitlist |
| `REDIS_HOST`, `REDIS_PORT` | booking |
| `EVENT_SERVICE_BASE_URL`, `BOOKING_SERVICE_BASE_URL`, `BOOKING_SERVICE_WS_BASE_URL`, `PAYMENT_SERVICE_BASE_URL`, `NOTIFICATION_SERVICE_BASE_URL`, `WAITLIST_SERVICE_BASE_URL` | gateway, and the services that call each other |
| `GATEWAY_CORS_ALLOWED_ORIGINS` (comma-separated) | api-gateway |

- Short, platform-neutral names were chosen over Spring's relaxed-binding names
  (`SPRING_DATASOURCE_URL`). They read naturally in Kubernetes manifests and Secrets and don't
  tie the deployment to Spring's naming rules.
- docker-compose was switched to these names.
- The `*_SERVICE_BASE_URL` names were already in use and stay unchanged.

**Kubernetes caveat, recorded for slice (b):** Kubernetes injects variables such as
`REDIS_PORT=tcp://10.x.x.x:6379` into every pod for each Service in the namespace. That would
collide with our `REDIS_PORT`. The same mechanism, via `KAFKA_PORT`, is also known to break
Confluent's Kafka image. The K8s manifests must set `enableServiceLinks: false`.

### 3. Flyway owns the schema in every environment; Hibernate only validates

- Added `spring-boot-starter-flyway` and `flyway-database-postgresql` (Flyway 12.4.0, managed
  by the Spring Boot 4.1.0 BOM) to the five services with a database.
- `ddl-auto: validate` in the shared `application.yml`, not just prod. If dev kept `update`,
  a missing migration would go unnoticed locally and only fail in prod.
- **`V1__baseline.sql` per service was generated by Hibernate's own schema export**
  (`jakarta.persistence.schema-generation.scripts.action=create`, run once against each
  service), not dumped from the dev database.
  - After months of `update`, a dev database could carry stale columns that no entity
    declares anymore.
  - Before relying on it, the column sets of all five dev databases were compared against the
    export: they matched exactly.
- **Dev only: `spring.flyway.baseline-on-migrate: true`.** Existing dev databases already
  contain the V1 tables, created by `update`. Flyway marks them as being at version 1 instead
  of failing on "non-empty schema without history table".
- **Prod deliberately doesn't baseline.** A prod database is either empty, so V1 runs, or
  already managed by Flyway. Anything else is an error that should stop startup.
- **Tests**: the `create-drop` overrides were removed. Every repository and integration test
  now runs the real migrations against a fresh Testcontainers Postgres, and Hibernate
  validates against the result. **CI therefore checks on every change that migrations and
  entities agree** — the regression check this slice exists to add.

From now on, every entity change needs a matching `V<n>__*.sql` migration.

### 4. Local prod-profile check

`docker/docker-compose.prod-profile.yml` is an override that runs the local stack with
`SPRING_PROFILES_ACTIVE=prod`. It makes prod configuration verifiable before a prod
environment exists. It still uses the local dev password; real secret handling belongs to
ADR-016 slice (e).

## Alternatives considered

- **Liquibase instead of Flyway**
  - Rejected: plain SQL migrations are easier to review.
  - Flyway is also the more common default in Spring Boot projects.
  - Nothing here needs Liquibase's database-agnostic changelogs, since everything is Postgres.
- **Keep `ddl-auto: update` in dev, Flyway in prod only**
  - Rejected: two schema mechanisms would drift.
  - Missing migrations would only surface in prod.
- **Spring's relaxed-binding names (`SPRING_DATASOURCE_URL`, …) instead of neutral names**
  - They work, and would have needed no compose change.
  - Rejected because a prod profile can't enforce "required" on a property that has no
    placeholder: relaxed binding gives no fail-fast.
  - A missing `SPRING_KAFKA_BOOTSTRAP_SERVERS`, for example, silently falls back to
    `localhost:9092`.
- **A hand-maintained list of required keys** (e.g. `@Validated` `@ConfigurationProperties`
  per service) instead of the generic `RequiredConfigurationCheck`
  - Rejected: every new setting would have to be added in two places, and a forgotten entry
    fails silently, which is exactly the failure mode being prevented.

## Consequences

**Positive**
- Prod can't start with dev credentials or localhost connections by accident.
- The schema is versioned, reviewable and reproducible.
- Entity/migration mismatches fail the build instead of production.

**Negative / accepted**
- Every entity change now needs a migration, a small amount of extra work per change.
- `baseline-on-migrate` in dev trusts that existing dev databases match V1. This was verified
  once when V1 was created. A developer database that has drifted since would fail
  `validate` at startup, which is loud and easy to fix by recreating the disposable dev
  database.

## Found during this slice, deliberately not changed

- In `docker/docker-compose.yml`, booking-service connects to the shared default database
  `ticketing` instead of a dedicated `ticketing_bookings`. That contradicts ADR-001
  (database-per-service): the other four services each have their own database, created by
  `docker/postgres-init/init-databases.sh`.
- This is out of scope for a configuration slice. It should be fixed when the K8s manifests
  (ADR-016 (b)) define the databases anyway.

---

---

# ADR-017: Spring-Konfiguration pro Umgebung und Flyway-Migrationen

**Status:** Akzeptiert  
**Datum:** 2026-09-30  
**Autoren:** Engineering-Team

---

## Kontext

ADR-016 hat zwei Umgebungen beschlossen: Dev (lokales Kubernetes) und Prod (k3s auf einer
kostenlosen VM). Sein erster Folge-Slice, (a), ist die Spring-Konfiguration pro Umgebung. Der
Zustand vor diesem ADR, geprüft durch Lesen jeder `application.yml` und der
`docker/docker-compose.yml`:

- **Umgebungs-Overrides funktionierten bereits**, nur implizit. docker-compose setzte
  `SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `EVENT_SERVICE_BASE_URL` usw., und
  Springs Relaxed Binding bildete sie auf die Properties ab. Es gab keine Profile.
- **Die einzelne `application.yml` jedes Services enthielt fest eincodierte Dev-Werte**:
  - DB-Zugangsdaten `ticketing`/`ticketing`
  - `localhost` für Postgres, Kafka und Redis
  - `DEBUG`-Logging
  - den CORS-Origin des Gateways, fest auf `http://localhost:4200`

  Nichts hinderte ein Prod-Deployment daran, mit diesen Werten zu starten, wenn eine Variable
  fehlte.
- **Das Schema gehörte Hibernate** (`ddl-auto: update`) in jeder Umgebung. `update` löscht oder
  benennt nie etwas um, kann keine Datenmigrationen ausdrücken und hinterlässt keine Spur
  dessen, was sich geändert hat – nicht akzeptabel, sobald eine Datenbank erhaltenswerte Daten
  enthält.
- **Tests**: Sieben `@DataJpaTest`-Klassen erzwangen `ddl-auto=create-drop`; die
  Integrationstests verließen sich auf `update`. Kein Test prüfte, ob das Schema, das eine echte
  Datenbank bekommt, zu den Entities passt.

## Entscheidung

### 1. Drei Konfigurationsdateien pro Service

| Datei | Enthält |
|---|---|
| `application.yml` | Alles, was in jeder Umgebung gleich ist: Ports, Kafka-(De-)Serializer und Group-IDs, JPA-Einstellungen, Actuator, Basis-Logging (`INFO`). Außerdem `spring.profiles.default: dev`. |
| `application-dev.yml` | Verbindungseinstellungen als `${VAR:lokaler-Standardwert}`, `DEBUG`-Logging für `com.ticketing`, Flyway `baseline-on-migrate`. |
| `application-prod.yml` | Dieselben Schlüssel als `${VAR}` **ohne Standardwert**. |

- `spring.profiles.default: dev` bedeutet, dass lokale Läufe, docker compose und die Testsuiten
  überhaupt kein Flag brauchen – für die tägliche Arbeit ändert sich nichts. Prod setzt
  `SPRING_PROFILES_ACTIVE=prod` ausdrücklich.
- **Schnell scheitern in Prod**: Eine fehlende Variable muss den Start mit `Could not resolve
  placeholder 'DB_PASSWORD'` stoppen, statt stillschweigend auf `localhost` oder das Dev-Passwort
  zurückzufallen.
- **Platzhalter ohne Standardwert reichen allein nicht**, was durch Testen herausgefunden wurde,
  nicht angenommen. Der erste Negativtest startete den event-service mit Prod-Profil und ohne
  `DB_PASSWORD`:
  - Er scheiterte mit `password authentication failed for user "ticketing"`, nicht mit dem
    Platzhalterfehler.
  - Ursache: Das `@ConfigurationProperties`-Binding von Spring Boot **lässt nicht auflösbare
    Platzhalter stehen**, sodass der wörtliche String `${DB_PASSWORD}` als Passwort gesendet
    wurde.
  - Bei `REDIS_HOST` oder `KAFKA_BOOTSTRAP_SERVERS` würde derselbe wörtliche String beim Start
    gar nicht scheitern, sondern erst bei der ersten Verbindung.
- **Lösung: ein `RequiredConfigurationCheck` im `config`-Package jedes Services**, nur aktiv mit
  `@Profile("prod")`.
  - Es ist ein statischer `BeanFactoryPostProcessor`, läuft also, bevor irgendeine Bean erzeugt
    wird – vor DataSource, Flyway, Kafka oder Redis.
  - Es löst jede Property aus den Anwendungs-Konfigurationsdateien strikt auf: jede
    `OriginTrackedMapPropertySource`, über `Environment.getProperty`.
  - Es ist generisch: Neue Prod-Platzhalter werden automatisch abgedeckt, ohne Liste
    erforderlicher Schlüssel, die synchron gehalten werden müsste.
  - Neuer Lauf des Negativtests: Der Start stoppt mit `Could not resolve placeholder
    'DB_PASSWORD' in value "${DB_PASSWORD}"`.
  - Die Klasse ist pro Service dupliziert, konsistent mit ADR-001: Es gibt keine gemeinsame
    Bibliothek zwischen Services.

### 2. Ein Satz Umgebungsvariablennamen, überall

| Variable | Verwendet von |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | den fünf Services mit Datenbank |
| `KAFKA_BOOTSTRAP_SERVERS` | booking, payment, notification, waitlist |
| `REDIS_HOST`, `REDIS_PORT` | booking |
| `EVENT_SERVICE_BASE_URL`, `BOOKING_SERVICE_BASE_URL`, `BOOKING_SERVICE_WS_BASE_URL`, `PAYMENT_SERVICE_BASE_URL`, `NOTIFICATION_SERVICE_BASE_URL`, `WAITLIST_SERVICE_BASE_URL` | Gateway und die Services, die einander aufrufen |
| `GATEWAY_CORS_ALLOWED_ORIGINS` (kommagetrennt) | api-gateway |

- Kurze, plattformneutrale Namen wurden den Relaxed-Binding-Namen von Spring
  (`SPRING_DATASOURCE_URL`) vorgezogen. Sie lesen sich natürlich in Kubernetes-Manifesten und
  Secrets und binden das Deployment nicht an Springs Namensregeln.
- docker-compose wurde auf diese Namen umgestellt.
- Die `*_SERVICE_BASE_URL`-Namen waren bereits in Gebrauch und bleiben unverändert.

**Kubernetes-Hinweis, für Slice (b) festgehalten:** Kubernetes injiziert in jeden Pod für jeden
Service im Namespace Variablen wie `REDIS_PORT=tcp://10.x.x.x:6379`. Das würde mit unserem
`REDIS_PORT` kollidieren. Derselbe Mechanismus über `KAFKA_PORT` ist zudem bekannt dafür, das
Kafka-Image von Confluent zu brechen. Die K8s-Manifeste müssen `enableServiceLinks: false`
setzen.

### 3. Flyway besitzt das Schema in jeder Umgebung; Hibernate validiert nur

- `spring-boot-starter-flyway` und `flyway-database-postgresql` (Flyway 12.4.0, verwaltet über
  die BOM von Spring Boot 4.1.0) wurden den fünf Services mit Datenbank hinzugefügt.
- `ddl-auto: validate` in der gemeinsamen `application.yml`, nicht nur in Prod. Bliebe in Dev
  `update`, würde eine fehlende Migration lokal unbemerkt bleiben und erst in Prod scheitern.
- **`V1__baseline.sql` pro Service wurde über Hibernates eigenen Schema-Export erzeugt**
  (`jakarta.persistence.schema-generation.scripts.action=create`, einmal gegen jeden Service
  ausgeführt), nicht aus der Dev-Datenbank gedumpt.
  - Nach Monaten mit `update` könnte eine Dev-Datenbank veraltete Spalten tragen, die keine
    Entity mehr deklariert.
  - Bevor darauf aufgebaut wurde, wurden die Spaltenmengen aller fünf Dev-Datenbanken mit dem
    Export verglichen: Sie stimmten exakt überein.
- **Nur Dev: `spring.flyway.baseline-on-migrate: true`.** Bestehende Dev-Datenbanken enthalten
  die V1-Tabellen bereits, durch `update` erzeugt. Flyway markiert sie als auf Version 1 stehend,
  statt an „nicht leeres Schema ohne History-Tabelle“ zu scheitern.
- **Prod führt bewusst kein Baselining aus.** Eine Prod-Datenbank ist entweder leer, dann läuft
  V1, oder bereits von Flyway verwaltet. Alles andere ist ein Fehler, der den Start stoppen soll.
- **Tests**: Die `create-drop`-Overrides wurden entfernt. Jeder Repository- und
  Integrationstest führt nun die echten Migrationen gegen ein frisches Testcontainers-Postgres
  aus, und Hibernate validiert gegen das Ergebnis. **CI prüft daher bei jeder Änderung, dass
  Migrationen und Entities übereinstimmen** – die Regressionsprüfung, die dieser Slice
  hinzufügen soll.

Von nun an braucht jede Entity-Änderung eine passende `V<n>__*.sql`-Migration.

### 4. Lokale Prüfung des Prod-Profils

`docker/docker-compose.prod-profile.yml` ist ein Override, der den lokalen Stack mit
`SPRING_PROFILES_ACTIVE=prod` ausführt. Damit ist die Prod-Konfiguration überprüfbar, bevor eine
Prod-Umgebung existiert. Er nutzt weiterhin das lokale Dev-Passwort; der echte Umgang mit
Secrets gehört zu Slice (e) von ADR-016.

## Betrachtete Alternativen

- **Liquibase statt Flyway**
  - Abgelehnt: Reine SQL-Migrationen sind leichter zu reviewen.
  - Flyway ist zudem der gängigere Standard in Spring-Boot-Projekten.
  - Nichts hier braucht die datenbankunabhängigen Changelogs von Liquibase, da alles Postgres
    ist.
- **`ddl-auto: update` in Dev behalten, Flyway nur in Prod**
  - Abgelehnt: Zwei Schema-Mechanismen würden auseinanderdriften.
  - Fehlende Migrationen würden erst in Prod auffallen.
- **Springs Relaxed-Binding-Namen (`SPRING_DATASOURCE_URL`, …) statt neutraler Namen**
  - Sie funktionieren und hätten keine Compose-Änderung gebraucht.
  - Abgelehnt, weil ein Prod-Profil „erforderlich“ nicht für eine Property erzwingen kann, die
    keinen Platzhalter hat: Relaxed Binding bietet kein Fail-fast.
  - Ein fehlendes `SPRING_KAFKA_BOOTSTRAP_SERVERS` fällt beispielsweise stillschweigend auf
    `localhost:9092` zurück.
- **Eine von Hand gepflegte Liste erforderlicher Schlüssel** (z. B. `@Validated`
  `@ConfigurationProperties` pro Service) statt des generischen `RequiredConfigurationCheck`
  - Abgelehnt: Jede neue Einstellung müsste an zwei Stellen ergänzt werden, und ein
    vergessener Eintrag scheitert stillschweigend, genau die Fehlerart, die verhindert werden
    soll.

## Konsequenzen

**Positiv**
- Prod kann nicht versehentlich mit Dev-Zugangsdaten oder localhost-Verbindungen starten.
- Das Schema ist versioniert, reviewbar und reproduzierbar.
- Abweichungen zwischen Entity und Migration lassen den Build scheitern statt der Produktion.

**Negativ / akzeptiert**
- Jede Entity-Änderung braucht nun eine Migration, ein kleiner Mehraufwand pro Änderung.
- `baseline-on-migrate` in Dev vertraut darauf, dass bestehende Dev-Datenbanken zu V1 passen.
  Das wurde einmal bei der Erstellung von V1 verifiziert. Eine Entwicklerdatenbank, die seither
  abgedriftet ist, würde beim Start an `validate` scheitern, was laut und leicht zu beheben ist,
  indem man die wegwerfbare Dev-Datenbank neu anlegt.

## Bei diesem Slice gefunden, bewusst nicht geändert

- In `docker/docker-compose.yml` verbindet sich booking-service mit der gemeinsamen
  Standarddatenbank `ticketing` statt mit einer eigenen `ticketing_bookings`. Das widerspricht
  ADR-001 (Datenbank pro Service): Die anderen vier Services haben jeweils eine eigene
  Datenbank, erzeugt durch `docker/postgres-init/init-databases.sh`.
- Das liegt außerhalb des Umfangs eines Konfigurations-Slices. Es sollte behoben werden, wenn
  die K8s-Manifeste (ADR-016 (b)) die Datenbanken ohnehin definieren.
