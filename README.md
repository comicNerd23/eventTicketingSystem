# Event Ticketing Platform

A portfolio project: an event ticketing system (concerts, sports,
theatre, comedy) built as six choreographed Spring Boot microservices
behind an Angular frontend, built with Spec-Driven Development to
demonstrate Kafka/event-driven architecture, microservices, and
full-stack skills.

**Stack:** Spring Boot 4.1.0 · Java 25 · Spring Cloud Gateway · Apache
Kafka · PostgreSQL (per service) · Redis · Angular 22 · Tailwind CSS 4 ·
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
| [`docs/adr/`](docs/adr/) | 25 Architecture Decision Records — the reasoning behind every non-obvious choice (choreography vs. orchestration, database-per-service, Redis seat holds, Kafka vs. RabbitMQ, plain WebSocket vs. STOMP, etc.). |
| [`docs/diagrams/c4-diagram.md`](docs/diagrams/c4-diagram.md) | C4 Context + Container diagrams. |
| [`specs/asyncapi/kafka-events.yaml`](specs/asyncapi/kafka-events.yaml) | The event catalog — every Kafka topic, who publishes it, who consumes it. More useful than any single service's code for understanding the whole system. |
| [`specs/openapi/`](specs/openapi/) | The REST contract per service. |

## Quick start

Prerequisites: Docker Desktop, Node 18+ (for the demo scripts and
frontend).

```bash
# 1. Bring up all 6 services, the containerized frontend + infra (Postgres, Redis, Kafka, Prometheus, Grafana)
docker compose -f docker/docker-compose.yml up -d

# 2. Reset to a clean, varied demo catalog (13 events across concerts/sports/theatre/comedy)
node seed-events.js

# 3. Walk the full happy-path saga end-to-end: hold -> confirm -> payment ->
#    CONFIRMED -> cancel -> refund -> waitlist promotion (15 narrated steps)
node demo.js

# 4. See it in a browser: the frontend container from step 1
#    -> http://localhost:8000
#    or, for frontend development with live reload (proxies /api to :8080):
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
| frontend (nginx; serves the app, proxies `/api` to api-gateway) | 8000 |
| api-gateway (API entry point) | 8080 |
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

Frontend (Vitest):

```bash
cd frontend
npx ng test --watch=false
```

### Running CI locally

`ci.js` is the same entry point GitHub Actions (`.github/workflows/ci.yml`)
uses, so a green local run means a green CI job — see
[ADR-015](docs/adr/ADR-015-ci-pipeline-and-local-testing.md):

```bash
node ci.js booking-service --docker   # mvn verify (with JaCoCo coverage) + docker build for one service
node ci.js frontend                   # npm ci, ng test (with coverage), ng build
node ci.js all                        # every service + frontend
```

For free, unlimited local runs, point Testcontainers Desktop at the local
Docker runtime rather than Testcontainers Cloud.

### Code quality (SonarQube Cloud)

CI runs `ci.js <target> --sonar`. Each target is analyzed in SonarQube Cloud with its coverage,
as one project per target, and a failed quality gate fails the job
([ADR-023](docs/adr/ADR-023-code-quality-analysis.md)). Locally, the coverage reports are in
`services/<name>/target/site/jacoco/index.html` and `frontend/coverage/frontend/lcov-report/index.html`.
The one-time setup is in [`docs/runbooks/sonarqube-cloud.md`](docs/runbooks/sonarqube-cloud.md).

GitHub's own checks run alongside it ([ADR-024](docs/adr/ADR-024-codeql-and-dependabot.md)):
CodeQL code scanning (default setup) and Dependabot alerts, security updates and weekly grouped
version updates (`.github/dependabot.yml`).

## Configuration and environments

Each service has `application.yml` (shared), `application-dev.yml` (local
defaults, the default profile) and `application-prod.yml` (every connection
value required from the environment: `DB_URL`, `DB_PASSWORD`,
`KAFKA_BOOTSTRAP_SERVERS`, …). Flyway owns the database schema
(`src/main/resources/db/migration`); Hibernate only validates it, so every
entity change needs a new migration. See
[ADR-017](docs/adr/ADR-017-per-environment-config-and-flyway.md).

To run the local stack with the prod profile:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod-profile.yml up -d
```

## Kubernetes

`k8s/base` holds environment-neutral manifests: the six services, the frontend, Postgres, Redis,
Kafka and one Ingress to the frontend, whose nginx proxies `/api` to the api-gateway (ADR-019). `k8s/overlays/dev` adds image tags and dev credentials.
`deploy-dev.js` builds the images, deploys the dev overlay and smoke-tests it through the Ingress.
The cluster is the current kube-context: `kind-*` or `rancher-desktop`. Any other context is
refused (ADR-020).

```bash
node deploy-dev.js --seed                    # build all 7 images, deploy, seed the catalog
node deploy-dev.js booking-service           # rebuild and restart one service
node deploy-dev.js --no-build                # redeploy the existing :dev images
node seed-events.js --k8s                    # reseed only (kind; add --base-url=http://localhost for Rancher Desktop)
```

Two dev clusters work. Neither runs next to the compose stack in a 4 GB Docker/WSL VM, so stop
compose first.

- **kind in Docker Desktop**. The app is at http://localhost:8000.

  ```bash
  kind create cluster --name ticketing --config k8s/kind/cluster.yaml
  ```

  The script installs Traefik (`k8s/kind/traefik.yaml`) on first use.
- **Rancher Desktop** (k3s with Traefik, like prod). The app is at http://localhost. On
  Windows it shares the WSL VM with Docker Desktop, so quit Docker Desktop first. Use the moby
  runtime, so that k3s sees locally built images, and the stable k3s channel:

  ```bash
  rdctl start --container-engine.name=moby --kubernetes.enabled=true
  rdctl set --kubernetes.version=1.36.4
  ```

See [ADR-018](docs/adr/ADR-018-kubernetes-base-manifests-and-ingress.md),
[ADR-019](docs/adr/ADR-019-containerized-frontend-and-same-origin-api.md) and
[ADR-020](docs/adr/ADR-020-dev-deploy-script.md).

## Prod deploy

`deploy-prod.js` deploys `k8s/overlays/prod` with a release tag. The secrets come from the
environment and are never written to the repository (ADR-021):

```bash
DB_PASSWORD=… GATEWAY_CORS_ALLOWED_ORIGINS=https://…   node deploy-prod.js --context=<kube-context> --tag=sha-1234567 --base-url=https://203-0-113-10.sslip.io [--tls-issuer=letsencrypt-staging]
```

Prod is served over HTTPS on an sslip.io name. The script installs a pinned, checksum-verified
cert-manager, which gets a Let's Encrypt certificate (ADR-022). The full walk-through from an empty
Oracle account to a rollback is in [`docs/runbooks/prod-vm.md`](docs/runbooks/prod-vm.md).

## Release images

`.github/workflows/release-images.yml` (manual or on a `v*` tag) builds all seven images natively
for amd64 and arm64 and publishes them as public multi-arch images:
`ghcr.io/comicnerd23/ticketing/<target>:sha-<7-char commit>` (plus `:vX.Y.Z` on a tag). There is no
`latest` tag. See [ADR-021](docs/adr/ADR-021-prod-images-registry-and-secrets.md).

```bash
gh workflow run release-images.yml           # or: git tag v1.0.0 && git push origin v1.0.0
```

## Project layout

```
services/           6 independently deployable Spring Boot services
frontend/           Angular 22 SPA + Dockerfile (nginx, ADR-019)
docker/             docker-compose stack + Grafana/Prometheus provisioning
specs/              OpenAPI (REST) + AsyncAPI (Kafka) contracts, written before the code
docs/
  plan.md           Dated, narrative project history (the real source of truth)
  adr/              Architecture Decision Records
  diagrams/          C4 diagrams
k8s/                Kustomize base + dev overlay, kind config for local verification (ADR-018)
```

## Current status

Phases 1-4 (specs, scaffolding, core backend, frontend) are done. Phase
5 (DevOps) is in progress — see the phase table and dated entries in
[`docs/plan.md`](docs/plan.md) for exactly what's shipped and what's
next.
