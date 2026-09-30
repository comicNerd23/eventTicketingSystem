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
