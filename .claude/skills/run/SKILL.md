---
name: run
description: Launch, seed, demo, or test the Event Ticketing Platform (6 Spring Boot microservices + Angular frontend + Docker infra). Use whenever asked to start/run/stop the stack, seed events, run the demo saga, serve the frontend, run backend or frontend tests, or check service health — always re-read current project state first since services/docs evolve.
---

# Running the Event Ticketing Platform

Before acting, re-read the current state of the project — this repo
documents itself and the details below can drift:

1. `README.md` — quick start, ports, project layout (source of truth for commands below).
2. `docs/plan.md` — current phase status ("Current status" in README points here); tells you what's actually finished vs. in progress.
3. `docker/docker-compose.yml` — actual service/port/dependency wiring.
4. `frontend/package.json` — actual npm scripts (these have changed recently: Karma/Jasmine → Vitest, see git log — don't trust README test command literally, check the `scripts` block).
5. `services/<name>/pom.xml` if touching a specific service — Java/Spring Boot version, module deps.

## Stack components

| Component | Where | Notes |
|---|---|---|
| api-gateway | services/api-gateway | client-facing entry, port 8080 |
| event-service | services/event-service | port 8081 |
| booking-service | services/booking-service | port 8082 |
| payment-service | services/payment-service | port 8083 |
| notification-service | services/notification-service | port 8084 |
| waitlist-service | services/waitlist-service | port 8085 |
| frontend | frontend/ | Angular, Vitest, `ng serve` → localhost:4200 |
| infra | docker/docker-compose.yml | Postgres, Redis, Kafka, Zookeeper, Kafdrop (9000), Prometheus (9090), Grafana (3000, admin/admin) |

## Standard startup sequence

```bash
# 1. Bring up all 6 services + infra
docker compose -f docker/docker-compose.yml up -d

# 2. Reset to a clean demo catalog
node seed-events.js

# 3. (optional) Walk the full happy-path saga end-to-end
node demo.js

# 4. Frontend dev server
cd frontend && npm install && npx ng serve
# -> http://localhost:4200
```

Services default to the `dev` Spring profile. To run the stack with the
`prod` profile locally (ADR-017), add the override file:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod-profile.yml up -d
```

Prod refuses to baseline a pre-Flyway database, so start once in dev first
against an old volume. Switch back by running the plain `up -d` again with
`--force-recreate` for the six app services.

If containers look broken after a host restart, check
`docker compose -f docker/docker-compose.yml ps -a` — infra containers
can sit `Exited` while app containers crash-loop against them. Bring
infra back up first, then restart the six app services.

## Tests

Backend, per service (standalone Maven module, own Testcontainers-backed suite):

```bash
cd services/<service-name>
mvn test
```

Frontend — verify the actual script before running, README may be stale:

```bash
cd frontend
cat package.json   # confirm test runner (Vitest as of the Karma migration)
npx ng test
```

CI locally — same commands the GitHub Actions workflow runs (ADR-015):

```bash
node ci.js <service-name> --docker   # mvn verify + docker build
node ci.js frontend                  # npm ci + ng test + ng build (stop ng serve first:
                                     # npm ci wipes node_modules, locked files on Windows)
node ci.js all
```

Testcontainers: prefer the local Docker runtime over Testcontainers Cloud
(free plan capped at 50 min/month) — `ci.js` warns when `tc.host` is set.

## Notes

- This is a choreographed (Kafka event-driven), not orchestrated, saga architecture — booking/payment/waitlist/notification services react to events rather than being called directly. Keep that in mind when debugging a flow that "isn't working" — check Kafdrop (localhost:9000) for the actual event trail before assuming a service bug.
- `docs/adr/` has the reasoning behind non-obvious choices (Redis seat holds, DB-per-service, etc.) — check there before "fixing" something that's actually a deliberate design decision.
