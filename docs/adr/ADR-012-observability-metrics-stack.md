# ADR-012: Observability Slice 1 — Metrics via Actuator + Micrometer + Prometheus + Grafana

**Status:** Accepted
**Date:** 2026-07-31
**Authors:** Engineering Team

---

## Context

The project has no observability tooling at all today — a search of every service's
`pom.xml` and of `docker-compose.yml` for actuator/micrometer/prometheus/grafana returns
zero hits. Phase 5 (DevOps) hasn't produced anything yet either (`k8s/base` and
`k8s/overlays/{dev,prod}` exist as empty scaffold folders). This ADR covers the first
Phase 5 slice: metrics.

### Observability background

"Observability" is usually described as three complementary pillars, each answering a
different question and with different cost/granularity trade-offs:

- **Metrics** — numeric measurements aggregated over time (request rate, latency
  percentiles, error rate, resource usage). Cheap to store and query even at high volume,
  because they're pre-aggregated numbers rather than raw text. Best suited to "is the
  system healthy right now / how has it trended" dashboards and to alerting thresholds.
  **This is what this slice adds.**
- **Logs** — discrete, timestamped, arbitrarily detailed event records. Best suited to
  "what exactly happened during this one request." Expensive to centralize and search
  once volume grows, because nothing about a log line is pre-aggregated. **Deferred.**
- **Traces** — the path a single request or transaction takes across service boundaries,
  with timing recorded at each hop. Best suited to "which of the N services (and which
  part of it) is where the time actually went for this one operation." **Deferred** —
  and notably more relevant here than a typical CRUD app, since the booking saga crosses
  five services over *asynchronous* Kafka events (`payment-initiated` →
  `payment-completed` → `ticket-issued`, etc.), not a simple synchronous call chain.

**Why this matters for this architecture specifically:** with 6 independently deployable
services communicating through a choreographed Kafka saga (ADR-002), there is no single
process to attach a debugger to and no single log file that tells the whole story. A
booking that's stuck could be caused by any one of the 6 services, or by the broker
itself. Observability tooling is what replaces "SSH into the one box and tail the one log
file" — a technique that stops working the moment there's more than one service.

### Option A: Spring Boot Admin
A lightweight dashboard purpose-built to sit on top of Spring Boot Actuator endpoints.

**Strengths:**
- Near-zero setup — mostly auto-configuration once Actuator is present.
- Good enough to eyeball whether a service is up and its basic health.

**Weaknesses:**
- Not a real metrics *store* — no history, no PromQL-style querying, no alerting story.
- Weaker signal for a portfolio project: doesn't demonstrate familiarity with the
  industry-standard metrics stack that most real engineering orgs actually run.

### Option B: Hosted APM (Datadog / New Relic)
A commercial, cloud-hosted metrics and APM platform with agents shipping data out.

**Strengths:**
- Very polished dashboards and alerting out of the box, minimal operational burden.

**Weaknesses:**
- Requires an external account and (beyond free tiers) real cost, and ships this
  project's data to a third party — inappropriate for a project that's meant to be
  cloned and run entirely locally via `docker compose up`.
- Adds an external dependency the project has deliberately avoided everywhere else (same
  reasoning that led to a self-hosted Kafka broker over a managed one, ADR-004).

### Option C: Self-hosted Prometheus + Grafana, fed by Spring Boot Actuator + Micrometer
Actuator exposes the management endpoints; Micrometer (the JVM ecosystem's vendor-neutral
metrics facade — the same architectural role SLF4J plays for logging) auto-instruments
HTTP requests, JVM memory, CPU, and connection pools with no code changes; the
`micrometer-registry-prometheus` adapter formats that data as Prometheus expects it, at
`/actuator/prometheus`. Prometheus itself pulls (scrapes) that endpoint on each service on
an interval and stores it as time series; Grafana queries Prometheus and renders it.

**Strengths:**
- Prometheus + Grafana is the de facto standard combination for exactly this kind of
  service-oriented architecture — pull-based scraping fits docker-compose's static,
  known-ports topology naturally, with no push/agent infrastructure needed.
- Free, self-hostable, no external account — consistent with the project's existing
  "everything runs via docker-compose, no external cloud dependency" posture.
- Micrometer being vendor-neutral means the instrumentation code itself isn't coupled to
  Prometheus — a future export target would only change the registry dependency, not any
  application code.
- A Grafana dashboard is a far stronger, more concrete demo/portfolio artifact than a
  Spring Boot Admin health page or an ad hoc PromQL query in Prometheus's own browser.

**Weaknesses:**
- More moving parts than Option A (two new containers instead of zero) for this first
  slice.
- Self-hosted means no built-in alerting delivery (Slack/PagerDuty/etc.) without adding
  Alertmanager separately — not needed yet, since there's no on-call to page.

---

## Decision

We adopt **Option C** — Spring Boot Actuator + Micrometer (`micrometer-registry-prometheus`)
in all 6 services, scraped by a self-hosted Prometheus, visualized in a self-hosted
Grafana, both added to `docker/docker-compose.yml`.

This slice is **metrics only**. The following are explicitly out of scope here and noted
as future work:

- **Distributed tracing** (Micrometer Tracing + Zipkin, with trace-context propagated
  through Kafka headers across the saga) — a separate, larger slice, since propagating
  trace context across an asynchronous Kafka boundary is a materially different problem
  from adding a dependency, and deserves its own ADR.
- **Kafka broker-level metrics** (consumer lag, broker throughput) — Micrometer only
  instruments *inside* the Spring services; visibility into the broker itself would need
  a separate `kafka-exporter` or JMX exporter scraping Kafka directly.
- **Centralized logging** (the third pillar — e.g. Loki/ELK plus a log shipper) — distinct
  infrastructure from metrics scraping, and most valuable once tracing exists to give
  log lines a correlation id to search by.
- **K8s health-probe tuning** (`management.endpoint.health.probes.enabled`, liveness vs.
  readiness groups) — Actuator's `/actuator/health` is enabled now, but probe-group
  wiring only matters once there's an actual K8s manifest to consume it, which doesn't
  exist yet (`k8s/base` is still empty).
- **Alerting** (Prometheus Alertmanager) — only meaningful once there's a baseline of
  "normal" metrics to define thresholds against.

---

## Rationale

Actuator is close to zero-risk to add and is a hard prerequisite for everything else, so
there was no real alternative to consider there. The real decision was the metrics
backend, and Prometheus + Grafana was chosen over Spring Boot Admin (Option A) because a
portfolio project benefits more from demonstrating the industry-standard stack than from
the marginal setup savings, and over a hosted APM (Option B) because it would contradict
the project's established no-external-dependency, fully-local-via-docker-compose posture
that every other infra decision in this project (Kafka, Redis, Postgres, Kafdrop) already
follows.

Scoping this slice to metrics only, rather than bundling in tracing or logging, follows
the project's testable-slices convention: each slice should be small and independently
demonstrable. A working Grafana dashboard with live numbers is a complete, checkable
result on its own; tracing across Kafka is substantial enough to deserve being its own
checkpoint rather than risking a half-finished bundle of three pillars at once.

---

## Consequences

### Positive
- All 6 services get request-rate, latency, JVM, and resource metrics with no
  application code changes — purely dependency + config additions.
- A live, provisioned Grafana dashboard becomes a concrete, checkable, screenshot-able
  artifact — the same "produce an observable, checkable result" bar the project already
  holds itself to for every other slice (per `docs/plan.md`).
- Establishes the Micrometer/Actuator foundation that the deferred tracing slice will
  build directly on top of (Micrometer Tracing shares the same instrumentation model).

### Negative / Trade-offs
- Two new containers (`prometheus`, `grafana`) added to an already-11-container
  docker-compose stack, increasing local resource usage and startup time.
- No visibility yet into the Kafka broker itself, or into any single request's
  cross-service journey — this slice only shows *that* something is slow, not *why*
  across service boundaries. That gap is precisely what the deferred tracing slice
  addresses.
