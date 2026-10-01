# ADR-018: Kubernetes Base Manifests, Traefik Ingress and kind for Local Verification

**Status:** Accepted
**Date:** 2026-10-01
**Authors:** Engineering Team

---

## Context

ADR-016 slice (b) asks for the base manifests:
- Deployments and Services for the six services.
- StatefulSets for Postgres, Redis and Kafka.
- An Ingress that includes the WebSocket path to booking-service (ADR-009).
- Liveness and readiness probes on the actuator health groups.

ADR-017 added two requirements:
- Every pod needs `enableServiceLinks: false`.
- booking-service needs its own database, because in docker-compose it still uses the shared
  `ticketing` database (contradicting ADR-001).

Before this ADR, `k8s/base` and `k8s/overlays/{dev,prod}` existed but were empty.

Three things had to be decided:

1. **Which cluster verifies the manifests now.** The development machine caps the Docker VM at
   4 GB, and that cap stays. The compose stack alone used about 2.9 GB of it.
2. **How traffic enters the cluster.** The community **ingress-nginx** controller, long the
   default choice, was retired in March 2026: no more releases or security fixes
   ([CNCF](https://www.cncf.io/blog/2026/04/02/ingress-nginx-retirement-experience-from-end-users/),
   [Fairwinds](https://www.fairwinds.com/blog/kubernetes-ingress-nginx-2026-migration)). The
   Ingress API is feature-frozen, and new work happens in the Gateway API. Both target
   clusters, k3s (prod) and Rancher Desktop (dev, which runs k3s), bundle **Traefik** (v3.7 in
   current k3s releases) as their default ingress controller
   ([k3s release notes](https://newreleases.io/project/github/k3s-io/k3s/release/v1.36.3%2Bk3s1),
   [Rancher Desktop docs](https://docs.rancherdesktop.io/how-to-guides/traefik-ingress-example/)).
   Traefik serves both Ingress and Gateway API resources.
3. **How Kafka runs in the cluster.**

## Options considered

### Cluster for verification
- **kind in Docker Desktop (chosen).** One `winget install`. It reuses the existing Docker VM,
  and the cluster is disposable. ADR-016 already lists kind as a dev alternative.
- **Rancher Desktop now.** This is the real dev target, but it is a second VM next to Docker
  Desktop on a machine with little free memory. It is postponed to slice (d).
- **Offline validation only** (`kubectl kustomize`, dry-run). This uses no memory, but it
  doesn't prove that anything actually runs.

### Entry point
- **A. Ingress resource, served by Traefik (chosen).**
  - Works in k3s and Rancher Desktop with nothing extra to install.
  - kind needs a small Traefik manifest.
- **B. Gateway API (`Gateway` + `HTTPRoute`) with Traefik.**
  - The forward-looking API.
  - But every environment needs the Gateway API CRDs, plus Traefik's Gateway provider enabled.
    In k3s that is a `HelmChartConfig` override.
  - That is more configuration in each environment for the same single route.
- **C. No Ingress.** The api-gateway would be exposed as a NodePort or LoadBalancer Service.
  This is the simplest option, but it contradicts ADR-016, and TLS and host routing would later
  land on the Spring gateway.

### Kafka
- **Same images as docker-compose (chosen):** cp-zookeeper and cp-kafka 7.5.0. Compose and
  Kubernetes behave the same, and nothing new has to be learned in this slice.
- **KRaft mode (no ZooKeeper).** It would save one pod (~100–200 MB). It is a broker change
  that would also have to go into compose, so it is better as a slice of its own.

## Decision

### Layout

```
k8s/
  base/                 environment-neutral, namespace "ticketing"
    infra/              postgres, redis, zookeeper, kafka (StatefulSet + Service each)
    services/           six Deployment + Service pairs
    ingress.yaml        one Ingress: / -> api-gateway:8080
  overlays/dev/         image tags (:dev), db-credentials Secret, gateway-config ConfigMap
  overlays/prod/        still empty, filled in slice (e)
  kind/                 cluster.yaml + traefik.yaml, used only for local verification
```

### Choices in the manifests

- **One Ingress rule, `/` to api-gateway.** The gateway already routes `/bookings/ws/**` to
  booking-service's WebSocket endpoint (ADR-007, ADR-009), and Traefik passes WebSocket
  upgrades through without extra configuration.
  - The Ingress has no `ingressClassName`, so it is served by the cluster's default class. In
    k3s that is Traefik; for kind, `k8s/kind/traefik.yaml` marks its IngressClass as default in
    the same way.
- **The Spring `prod` profile in every cluster, including dev.** In a cluster every value is
  passed explicitly, and the prod profile fails at startup if one is missing (ADR-017's
  `RequiredConfigurationCheck`). That turns the profile into a completeness check for the
  manifests.
- **`enableServiceLinks: false` on every pod.** A control pod without it got
  `REDIS_PORT=tcp://10.96.x.x:6379` and `KAFKA_PORT=tcp://…`. With it, booking-service sees
  `REDIS_PORT=6379` and the Kafka pod has no `KAFKA_PORT` at all.
- **A database per service, including booking-service.** One Postgres StatefulSet. A
  `postgres-init` ConfigMap creates `ticketing_events`, `ticketing_bookings`,
  `ticketing_payments`, `ticketing_notifications` and `ticketing_waitlist`, and Flyway creates
  each schema.
  - docker-compose is **not** changed here. Moving its booking-service to `ticketing_bookings`
    needs that database created by hand in existing dev volumes, so it is a separate small fix.
- **Probes** on Spring Boot's `/actuator/health/liveness` and `/readiness` groups. Boot enables
  them automatically when it detects Kubernetes.
  - A `startupProbe` allows up to 3 minutes, because six JVMs starting at once on two CPUs are
    slow.
  - Liveness uses `timeoutSeconds: 3`. Under that contention the default of 1 s was exceeded at
    startup.
- **Resources** are sized for the 4 GB machine:
  - Each service requests 300 Mi and is limited to 512 Mi. `MaxRAMPercentage=60` keeps the
    heap inside the limit.
  - Kafka is capped at a 384 MB heap (768 Mi limit), ZooKeeper at 128 MB.
  - There are no CPU limits, which would only slow down JVM startup.
- **Credentials** come from a `db-credentials` Secret. The dev overlay generates it with the
  local password; prod gets real Secrets in slice (e).
- **Not in the cluster for now:** Prometheus, Grafana and Kafdrop. They don't fit in 4 GB next
  to the stack, and they belong to the observability slices.

## Consequences

**Positive**
- The same base runs unchanged on kind, Rancher Desktop and k3s; only the overlay differs.
- A missing environment variable in any manifest fails loudly at startup, because of the prod
  profile.
- booking-service finally has its own database in Kubernetes, as ADR-001 requires.

**Negative / accepted**
- **The Ingress API is frozen.** Moving to the Gateway API is a later, contained change: one
  `HTTPRoute` instead of one Ingress, plus enabling Traefik's Gateway provider.
- **No start ordering.** On a cold start Kafka can come up before ZooKeeper, and the services
  before Postgres. They crash and are restarted by Kubernetes until their dependencies are up:
  1–2 restarts per pod, everything Ready after about 5 minutes. Init containers that wait for
  dependencies could remove this. They were left out because restarting is the normal
  Kubernetes way to converge.
- **Memory is tight.** The kind node uses about 3.0 of 3.8 GiB with the full stack. The compose
  stack and the cluster cannot run at the same time.
- `seed-events.js` truncates tables with `docker exec docker-postgres-1`, so it only works
  against compose. A cluster equivalent belongs in the dev deploy script, slice (d).
- `kubectl apply` reports the three StatefulSets with volume claims as "configured" on every
  run, although `kubectl diff` shows no change and no pod is recreated. This is a client-side
  apply quirk with `volumeClaimTemplates`, and it is harmless.
- **Prod still needs:**
  - arm64 or multi-arch images. That includes checking cp-kafka 7.5.0 for arm64, or moving to
    KRaft with an image that has an arm64 build.
  - A registry.
  - Real Secrets.

  This is all slice (e).
