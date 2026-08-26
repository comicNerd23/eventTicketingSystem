# Event Ticketing Platform

A portfolio project: an event ticketing system (concerts, sports,
theatre, comedy) built as six choreographed Spring Boot microservices
behind an Angular frontend, built with Spec-Driven Development to
demonstrate Kafka/event-driven architecture, microservices, and
full-stack skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache
Kafka · PostgreSQL (per service) · Redis · Angular 20 · Tailwind CSS 4 ·
Docker/K8s · Stripe sandbox (stubbed) · Testcontainers

## Architecture, in one picture

See [`docs/diagrams/c4-diagram.md`](docs/diagrams/c4-diagram.md) for the
C4 Context and Container diagrams — the fastest way to see how the six
services, Kafka, Redis, and the frontend fit together before reading any
code.

## Documentation map

This repo documents itself more than the README can — start here, then
go deeper as needed:

| Where | What's there |
|---|---|
| [`docs/plan.md`](docs/plan.md) | The authoritative project history — every slice, every real bug found (and how it was fixed), current phase status. Read this to understand *why* the code looks the way it does, not just what it does. |
| [`docs/adr/`](docs/adr/) | 12 Architecture Decision Records — the reasoning behind every non-obvious choice (choreography vs. orchestration, database-per-service, Redis seat holds, Kafka vs. RabbitMQ, plain WebSocket vs. STOMP, etc.). |
| [`docs/diagrams/c4-diagram.md`](docs/diagrams/c4-diagram.md) | C4 Context + Container diagrams. |
| [`specs/asyncapi/kafka-events.yaml`](specs/asyncapi/kafka-events.yaml) | The event catalog — every Kafka topic, who publishes it, who consumes it. More useful than any single service's code for understanding the whole system. |
| [`specs/openapi/`](specs/openapi/) | The REST contract per service. |

## Quick start

Prerequisites: Docker Desktop, Node 18+ (for the demo scripts and
frontend).

```bash
# 1. Bring up all 6 services + infra (Postgres, Redis, Kafka, Prometheus, Grafana)
docker compose -f docker/docker-compose.yml up -d

# 2. Reset to a clean, varied demo catalog (12 events across concerts/sports/theatre/comedy)
node seed-events.js

# 3. Walk the full happy-path saga end-to-end: hold -> confirm -> payment ->
#    CONFIRMED -> cancel -> refund -> waitlist promotion (15 narrated steps)
node demo.js

# 4. See it in a browser
cd frontend && npm install && npx ng serve
# -> http://localhost:4200
```

If step 1 was run a while ago and things look broken, check
`docker compose -f docker/docker-compose.yml ps -a` (the `-a` matters —
infra containers can sit `Exited` after a host restart while the app
containers keep crash-looping against them). `docker compose ... up -d`
again, then restart the six app services.

Other useful local endpoints once the stack is up:

| Service | Port |
|---|---|
| api-gateway (client-facing entry point) | 8080 |
| event-service | 8081 |
| booking-service | 8082 |
| payment-service | 8083 |
| notification-service | 8084 |
| waitlist-service | 8085 |
| Kafdrop (Kafka topic browser) | 9000 |
| Prometheus | 9090 |
| Grafana ("Ticketing Platform Overview" dashboard, admin/admin) | 3000 |

## Running the tests

Each service is a standalone Maven module with its own test suite
(unit, controller, and Testcontainers-backed repository/integration
tests):

```bash
cd services/<service-name>
mvn test
```

Frontend:

```bash
cd frontend
npx ng test --watch=false --browsers=ChromeHeadless
```

## Project layout

```
services/           6 independently deployable Spring Boot services
frontend/           Angular 20 SPA
docker/             docker-compose stack + Grafana/Prometheus provisioning
specs/              OpenAPI (REST) + AsyncAPI (Kafka) contracts, written before the code
docs/
  plan.md           Dated, narrative project history (the real source of truth)
  adr/              Architecture Decision Records
  diagrams/          C4 diagrams
k8s/                Kubernetes manifests (not started yet — see docs/plan.md Phase 5)
```

## Current status

Phases 1-4 (specs, scaffolding, core backend, frontend) are done. Phase
5 (DevOps) is in progress — see the phase table and dated entries in
[`docs/plan.md`](docs/plan.md) for exactly what's shipped and what's
next.
