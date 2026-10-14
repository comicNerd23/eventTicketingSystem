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

---

---

# ADR-007: API-Gateway – Spring Cloud Gateway (reaktiv)

**Status:** Akzeptiert  
**Datum:** 2026-07-29  
**Autoren:** Engineering-Team

---

## Kontext

Die Plattform braucht einen einzigen, clientseitigen Einstiegspunkt, der Anfragen an die fünf
Backend-Services (`event-service`, `booking-service`, `payment-service`,
`notification-service`, `waitlist-service`) weiterleitet, statt dass Clients den individuellen
Port jedes Services kennen müssen. Mehrere Optionen wurden bewertet:

### Option A: Spring Cloud Gateway (reaktiv, WebFlux/Project Reactor)
Die klassische, am weitesten verbreitete Implementierung von „Spring Cloud Gateway“ – gebaut
auf Project Reactor statt auf dem Servlet-Stack, den alle anderen Services dieser Plattform
nutzen.

**Stärken:**
- Branchenstandard für Spring-basierte API-Gateways; die Variante, die gemeint ist, wenn
  „Spring Cloud Gateway“ ohne Zusatz genannt wird.
- Nicht blockierendes I/O passt gut zur Aufgabe eines Gateways (viele gleichzeitige
  weitergeleitete Anfragen, die meist auf nachgelagertes I/O warten).
- Zeigt direkt reaktive Spring-Kenntnisse, getrennt von den MVC-Kenntnissen, die alle anderen
  Services bereits zeigen.

**Schwächen:**
- Bringt ein zweites Programmiermodell (reaktiv/Mono·Flux) neben dem überall sonst genutzten
  Servlet-/MVC-Modell in die Codebasis – andere Testwerkzeuge (`WebTestClient` statt `MockMvc`),
  anderes mentales Debugging-Modell.
- Nicht das, was ein wirklich unternehmensweiter Einsatz typischerweise verwenden würde (siehe
  Konsequenzen unten).

### Option B: Spring Cloud Gateway MVC (Servlet-basiert)
Eine neuere, Servlet-basierte Implementierung desselben Routing-Funktionsumfangs
(`spring-cloud-starter-gateway-server-webmvc`).

**Stärken:**
- Dasselbe deklarative Routing-/Predicate-/Filter-Modell wie Option A, bleibt aber auf dem
  Servlet-Stack – konsistent mit allen anderen Services der Plattform (reines Spring MVC,
  Tests im `MockMvc`-Stil).
- Geringere kognitive Last: Es wird kein neues Programmiermodell eingeführt.

**Schwächen:**
- Neuer und weniger bekannt als die reaktive Implementierung; hat in Vorstellungsgesprächen und
  Stellenanzeigen nicht dieselbe Namenserkennung als „Spring Cloud Gateway“.
- Verschenkt die Gelegenheit, reaktives Spring zu zeigen, die die klassische Implementierung
  bietet.

### Option C: Gateway auf Infrastrukturebene (dediziertes Produkt oder Service Mesh)
NGINX/Traefik als Reverse-Proxy, ein verwaltetes API-Gateway-Produkt (Kong, Apigee,
Cloud-verwaltetes API Management) oder ein Service Mesh + Kubernetes Ingress (Envoy/Istio), das
das Routing auf Infrastrukturebene statt im Anwendungscode übernimmt.

**Stärken:**
- Das, was große, reale Produktionssysteme tatsächlich meist nutzen – Routing, Authentifizierung,
  Rate-Limiting, WAF, Observability und mTLS gehören einem Plattform-Team, unabhängig vom
  Release-Zyklus eines einzelnen Services („dumme Leitungen“ am Infrastrukturrand statt von Hand
  gepflegtem Anwendungscode).
- Kein zusätzlicher JVM-Service, der gebaut, getestet und betrieben werden muss.

**Schwächen:**
- Reine Konfiguration, kein Code – zeigt keine Spring-/Java-Kenntnisse, was ein echtes Ziel
  dieses Projekts ist.
- Übertrieben für ein Portfolio-Projekt eines Einzelentwicklers ohne Plattform-Team und ohne
  Kubernetes-Deployment (Phase 5, noch nicht begonnen).

---

## Entscheidung

Wir führen **Spring Cloud Gateway (reaktiv)** ein – Option A.

---

## Begründung

Der erklärte Zweck dieses Projekts ist es, Spring-/Java-Microservice-Kenntnisse gegenüber
Interviewern aus Enterprise und Beratung zu demonstrieren. Unter den beiden Java-basierten
Optionen ist die klassische reaktive Implementierung das, was man unter „Spring Cloud Gateway“
versteht, und zeigt zusätzlich reaktives Spring – eine Fähigkeit, die der Rest der Codebasis
(durchgehend Spring MVC) sonst nicht zeigt. Option C wurde speziell für dieses Projekt
abgelehnt, weil es reine Infrastrukturkonfiguration und kein Anwendungscode ist und nicht zu
einem Portfolio-Projekt eines Einzelentwicklers ohne Plattform-Team oder Kubernetes-Deployment
passt, gegen das man es konfigurieren könnte.

---

## Konsequenzen

### Positiv
- Zeigt ein zweites Spring-Programmiermodell (reaktiv) neben dem überall sonst genutzten
  MVC-Modell und verbreitert damit die vom Projekt gezeigten Fähigkeiten.
- Ein einziger Einstiegspunkt vereinfacht die clientseitige Oberfläche – ein Port statt fünf.

### Negativ / Abwägungen
- **Das ist nicht die Empfehlung für den Unternehmensmaßstab.** Im echten großen Maßstab ist ein
  dediziertes API-Gateway-Produkt oder ein Service Mesh + Ingress (Option C) die in der Praxis
  üblichere Wahl – es verlagert übergreifende Belange (Authentifizierung, Rate-Limiting, WAF,
  Observability, mTLS) auf Infrastruktur, die ein Plattform-Team unabhängig von
  Anwendungs-Deployments besitzt, statt auf einen von Hand gepflegten Java-Service, der an den
  Release-Zyklus dieser Codebasis gekoppelt ist. Spring Cloud Gateway als Anwendungsservice ist
  eine vernünftige Wahl für ein mittelgroßes JVM-Team ohne dediziertes Plattform-Team oder – wie
  hier – um gezielt Spring-/Java-Gateway-Kenntnisse zu zeigen. Dieser Kompromiss wird bewusst
  ausgesprochen statt stillschweigend gelassen: Die Wahl von Option A ist eine gezielte
  Entscheidung zur Demonstration von Fähigkeiten, keine Behauptung, dies sei im
  Unternehmensmaßstab die richtige Architektur.
- **Der reaktive Stack bleibt auf diesen einen Service beschränkt.** Kein anderer Service nutzt
  WebFlux; `api-gateway` ist bewusst die einzige Stelle der Codebasis, die `Mono`/`Flux` und
  `WebTestClient` verwendet, da es der eine Service ist, für den ein reaktives I/O-Modell
  tatsächlich gut geeignet ist (viele gleichzeitige weitergeleitete Anfragen, die meist auf
  nachgelagertes I/O warten) – es wird nicht als plattformweite Richtung eingeführt.
