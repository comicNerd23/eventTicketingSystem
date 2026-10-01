# ADR-016: Dev and Prod Environments

**Status:** Accepted (decision only — implementation follows in separate slices)
**Date:** 2026-09-29
**Authors:** Engineering Team

---

## Context

The application should run in two environments: **dev**, which can be deployed, run and
tested locally, and **prod**. Two further constraints apply:

- It must stay **free**.
- The dev environment should resemble how the system is deployed, not only how it's
  developed. Tools like LocalStack or Floci were raised as candidates for the dev side.

The original Phase 5 plan named **GCP Cloud Run** as the deployment target, and the repo already
contains empty Kustomize scaffolding: `k8s/base`, `k8s/overlays/dev`, `k8s/overlays/prod`.

### What the services actually depend on
None of the six services calls a cloud provider API. They talk directly to **Kafka**,
**PostgreSQL** (one database per service, ADR-001) and **Redis** (ADR-003). Configuration today
lives in one `application.yml` per service, with hardcoded credentials
(`ticketing`/`ticketing`), `ddl-auto: update` and `DEBUG` logging — values that are fine for
local development but not for prod.

## Options

### Option A: AWS + LocalStack / Floci for dev
LocalStack and Floci emulate **AWS APIs**. They only add value if prod runs on AWS-managed
services (RDS, ElastiCache, MSK, ECS/EKS) and deployment is infrastructure-as-code (e.g.
Terraform) applied against the emulator in dev and against real AWS in prod.

Checked on 2026-09-29 against the vendors' own pages:

- **LocalStack**: since release 2026.03.0, every image needs an auth token. The only free plan,
  *Hobby*, is **non-commercial only** and does not include any of the services this app would
  need. RDS, ECS and ElastiCache start at the paid *Base* plan; EKS and MSK need *Ultimate*
  ([plans](https://docs.localstack.cloud/aws/licensing/)). **Not free for this app.**
- **Floci** ([floci-io/floci](https://github.com/floci-io/floci)): MIT-licensed, no account, no
  auth token, no feature gates. It emulates all five services this app would need, each backed
  by real engine containers:

  | AWS service | What Floci runs |
  |---|---|
  | RDS | `postgres:16-alpine` |
  | ElastiCache | `valkey/valkey:8` |
  | MSK | `redpandadata/redpanda` — Kafka-API compatible, but not Apache Kafka itself |
  | ECS | real Docker containers |
  | EKS | `rancher/k3s` |

  **Free for the dev side.**
- Floci removes the emulator-cost objection, but not the core one: **prod on real AWS is not
  free.**
  - MSK has no free tier.
  - EKS bills for its control plane.
  - Fargate has no free tier.
  - Dev on Floci would rehearse provisioning for an AWS prod this project cannot afford to run.
- The services still call no AWS APIs at runtime, so Floci would only emulate *infrastructure*
  (e.g. Terraform applied to Floci instead of AWS), not anything the application code does.
- It would also move prod from GCP to AWS.

### Option B: GCP Cloud Run (original plan)
- There is no Cloud Run emulator; dev would stay on docker compose or a local cluster.
- Cloud Run scales to zero, which breaks long-running **Kafka consumers**. Every service except
  api-gateway consumes Kafka. Keeping instances warm exceeds the free tier.
- Cloud SQL and Memorystore have no free tier.

### Option C: Kubernetes in both environments — local cluster for dev, k3s on a free VM for prod
- The same container images and the same `k8s/base` manifests run in both environments; the
  differences live in the `k8s/overlays/{dev,prod}` Kustomize overlays that already exist.
- Dev runs on a local cluster: **Rancher Desktop** (k3s, free, open source), with kind or k3d as
  lighter alternatives.
- Prod runs **k3s** on a free VM, e.g. Oracle Cloud Always Free. Kafka, Postgres and Redis run
  in the cluster.
  - **Checked 2026-09-29:** Oracle cut the Ampere A1 allowance from 4 OCPU / 24 GB to
    **2 OCPU / 12 GB** on 2026-06-15, without a public announcement
    ([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)).
  - That is still enough for k3s plus this stack. Rough estimate:
    - six Spring Boot JVMs: about 3 GB
    - Kafka and ZooKeeper: about 1.5 GB
    - Postgres, Redis and k3s itself: about 1–2 GB

    That leaves little headroom for Rancher Manager.
  - A1 is **arm64**, so every image, our own and third-party, must be available for arm64.
    Our images are built on `eclipse-temurin`; the Confluent Kafka image still needs checking
    in the prod slice. CI would then build multi-arch images with `docker buildx`.
  - Free tiers change without notice, as this cut shows. Re-check the limits at the start of
    the prod slice.
- **Rancher Manager** (Apache 2.0, free; only Rancher Prime support is paid) can be added on
  top of k3s for the UI, RBAC and multi-cluster management that many companies use on private
  cloud platforms. It needs several GB of RAM on its own.

## Decision

**Option C.**

- **Dev = local Kubernetes on Rancher Desktop** (kind/k3d as documented alternatives).
- **Prod = k3s on a free VM.**
- **Rancher Manager is optional**, in its own slice after the first successful prod deploy — it
  adds to the operational story more than it's technically required for one cluster.
- **RKE2** (Rancher's hardened Kubernetes distribution) is noted as a possible later
  hardening step instead of k3s.
- "GCP Cloud Run" is dropped from the roadmap.

No cloud emulator is needed: the dev environment deploys the real stack onto a real Kubernetes
API, which is exactly what prod runs.

**Floci stays a documented fallback.** If prod ever moves to AWS, for example with credits or a
paid account, Floci is the free dev counterpart. LocalStack is not: its free plan excludes every
service this app needs. Floci's EKS emulation is itself k3s, so the Kubernetes manifests from
this decision would carry over.

## Required changes (follow-up slices, in order)

- **(a) Spring configuration per environment.** Replace hardcoded values with environment
  variables (with local defaults), add `application-dev.yml` / `application-prod.yml`. Prod:
  `ddl-auto: validate` with **Flyway** migrations, `INFO` logging, credentials only from
  Secrets.
- **(b) K8s base manifests.** Deployments and Services for the six services; StatefulSets for
  Postgres, Redis and Kafka; an Ingress, including the WebSocket path to booking-service
  (ADR-009); liveness/readiness probes on the existing actuator health groups.
- **(c) Containerized frontend** (e.g. nginx), with API and WebSocket URLs set per
  environment.
- **(d) Dev deploy script** against Rancher Desktop — images loaded locally, no registry.
- **(e) Prod.**
  - Secrets via Sealed Secrets or GitHub Actions secrets.
  - An image registry: private GHCR storage on the free plan is small (about 500 MB), which is
    too little for six JVM images. That means either a public repository or a different free
    registry.
    *Corrected in ADR-021:* the 500 MB limit applies to the other GitHub Packages registries; GHCR
    container storage is currently free, including private images.
  - A manually or tag-triggered deploy job.
- **(f) Optional:** Rancher Manager.

## Consequences

**Positive**
- Free in both environments.
- Dev and prod differ only in overlays, not in deployment mechanics.
- It uses scaffolding that already exists.
- Kubernetes plus Rancher is the setup commonly found on private-cloud platforms.

**Negative / accepted**
- Self-hosting Kafka, Postgres and Redis in prod means backups and upgrades are our
  responsibility — acceptable for a portfolio system with disposable demo data.
- A single free VM is a single point of failure; there is no high availability.
- Free-tier offerings change over time; both LocalStack (March 2026) and Oracle (June 2026) cut
  theirs this year. Re-verify them at the start of the corresponding slice.
- The prod VM is arm64 while development happens on x86, so prod needs multi-arch images.
