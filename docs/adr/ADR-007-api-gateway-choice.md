# ADR-007: API Gateway — Spring Cloud Gateway (Reactive)

**Status:** Accepted
**Date:** 2026-07-29
**Authors:** Engineering Team

---

## Context

The platform needs a single client-facing entry point that routes requests to the
five backend services (`event-service`, `booking-service`, `payment-service`,
`notification-service`, `waitlist-service`) instead of requiring clients to know
each service's individual port. Several options were evaluated:

### Option A: Spring Cloud Gateway (reactive, WebFlux/Project Reactor)
The classic, most widely recognised implementation of "Spring Cloud Gateway" —
built on Project Reactor rather than the servlet stack every other service in this
platform uses.

**Strengths:**
- Industry-standard technology for Spring-based API gateways; the one meant when
  "Spring Cloud Gateway" is named without qualification.
- Non-blocking I/O suits a gateway's job well (many concurrent in-flight proxied
  requests, mostly waiting on downstream I/O).
- Directly demonstrates reactive Spring skills, distinct from the MVC skills every
  other service already shows.

**Weaknesses:**
- Introduces a second programming model into the codebase (reactive/Mono·Flux)
  alongside the servlet/MVC model used everywhere else — different testing tools
  (`WebTestClient` instead of `MockMvc`), different debugging mental model.
- Not what a genuinely enterprise-scale deployment would typically use (see
  Consequences below).

### Option B: Spring Cloud Gateway MVC (servlet-based)
A newer, servlet-based implementation of the same routing feature set
(`spring-cloud-starter-gateway-server-webmvc`).

**Strengths:**
- Same declarative routing/predicate/filter model as Option A, but stays on the
  servlet stack — consistent with every other service in this platform (plain
  Spring MVC, `MockMvc`-style testing).
- Lower cognitive overhead: no new programming model to introduce.

**Weaknesses:**
- Newer and less widely recognised than the reactive implementation; doesn't carry
  the same "Spring Cloud Gateway" name recognition in interviews/job postings.
- Loses the opportunity to demonstrate reactive Spring, which the classic
  implementation offers.

### Option C: Infrastructure-level gateway (dedicated product or service mesh)
NGINX/Traefik as a reverse proxy, a managed API gateway product (Kong, Apigee,
cloud-managed API Management), or a service mesh + Kubernetes Ingress
(Envoy/Istio) handling routing at the infrastructure layer instead of in
application code.

**Strengths:**
- What large-scale, real production systems actually tend to use — routing, auth,
  rate limiting, WAF, observability, and mTLS are owned by a platform team
  independently of any single service's release cycle ("dumb pipes" at the
  infrastructure edge, not hand-maintained application code).
- No additional JVM service to build, test, and operate.

**Weaknesses:**
- Pure configuration, not code — doesn't demonstrate any Spring/Java skill, which
  is a real goal of this project.
- Overkill for a single-developer portfolio project with no platform team and no
  Kubernetes deployment yet (Phase 5, not started).

---

## Decision

We adopt **Spring Cloud Gateway (reactive)** — Option A.

---

## Rationale

This project's explicit purpose is to demonstrate Spring/Java microservices skills
to enterprise/consulting interviewers. Between the two Java-based options, the
classic reactive implementation is what "Spring Cloud Gateway" is understood to
mean, and additionally demonstrates reactive Spring — a skill the rest of the
codebase (uniformly Spring MVC) doesn't otherwise showcase. Option C was rejected
for this project specifically because it's pure infrastructure configuration, not
application code, and doesn't fit a single-developer portfolio project without a
platform team or Kubernetes deployment to configure it against.

---

## Consequences

### Positive
- Demonstrates a second Spring programming model (reactive) alongside the MVC
  model used everywhere else, broadening the skills the project shows.
- Single entry point simplifies the client-facing surface — one port instead of
  five.

### Negative / Trade-offs
- **This is not the enterprise-scale recommendation.** At genuine scale, a
  dedicated API gateway product or a service mesh + Ingress (Option C) is the more
  common real-world choice — it moves cross-cutting concerns (auth, rate
  limiting, WAF, observability, mTLS) onto infrastructure a platform team owns
  independently of application deploys, rather than a hand-maintained Java
  service coupled to this codebase's release cycle. Spring Cloud Gateway as an
  application service is a reasonable choice for a mid-size JVM shop without a
  dedicated platform team, or — as here — for demonstrating Spring/Java gateway
  skills specifically. This trade-off is made explicit rather than left implicit:
  choosing Option A here is a deliberate skill-demonstration decision, not a
  claim that it's the right architecture at enterprise scale.
- **Reactive stack is isolated to this one service.** No other service uses
  WebFlux; `api-gateway` is deliberately the only place in the codebase using
  `Mono`/`Flux` and `WebTestClient`, since it's the one service where a reactive
  I/O model is actually well-suited (many concurrent proxied requests, mostly
  waiting on downstream I/O) — this isn't introduced as a platform-wide
  direction.
