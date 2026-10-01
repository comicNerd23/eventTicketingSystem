# C4 Diagrams — Event Ticketing Platform

Two levels are useful here: **Context** (the system as one box, who/what
talks to it) and **Container** (the six services, the frontend, and the
infrastructure between them). Rendered as GitHub-native Mermaid, not
external images, so they stay viewable and diffable in the repo.

See [`docs/plan.md`](../plan.md) for the narrative history and
[`docs/adr/`](../adr/) for the reasoning behind each architectural
choice referenced below.

## Level 1 — System Context

```mermaid
flowchart TB
    attendee(["Event Attendee<br/>(browses events, books seats)"])

    subgraph platform["Event Ticketing Platform"]
        direction TB
        note["Concerts / sports / theatre / comedy<br/>ticket booking with live seat holds"]
    end

    stripe[["Stripe<br/>(payment provider — stubbed,<br/>see ADR-011)"]]

    attendee -- "HTTPS" --> platform
    platform -- "charge / refund<br/>(currently stubbed)" --> stripe

    style platform fill:#4f46e5,color:#fff,stroke:#333,stroke-width:2px
    style stripe fill:#eee,stroke:#999,color:#333
```

## Level 2 — Containers

```mermaid
flowchart TB
    attendee(["Event Attendee"])

    subgraph browser["Browser"]
        spa["Angular SPA<br/>calls the API same-origin under /api"]
    end

    web["frontend<br/>nginx-unprivileged, port 8080<br/>serves the SPA, proxies /api/** — ADR-019<br/>(dev: ng serve + proxy.conf.json)"]

    gateway["api-gateway<br/>Spring Cloud Gateway, WebFlux<br/>port 8080 — ADR-007"]

    subgraph services["Spring Boot services (one Postgres DB each — ADR-001)"]
        direction LR
        event["event-service<br/>port 8081<br/>DB: ticketing_events"]
        booking["booking-service<br/>port 8082<br/>DB: ticketing_bookings (K8s)<br/>shared ticketing (compose, ADR-018)<br/>+ Redis seat holds — ADR-003"]
        payment["payment-service<br/>port 8083<br/>DB: ticketing_payments"]
        notification["notification-service<br/>port 8084<br/>DB: ticketing_notifications"]
        waitlist["waitlist-service<br/>port 8085<br/>DB: ticketing_waitlist"]
    end

    kafka{{"Kafka<br/>choreographed saga backbone — ADR-002, ADR-004<br/>(seat-hold-expired, payment-initiated,<br/>payment-completed, booking-cancelled,<br/>ticket-issued, waitlist-promoted, ...)"}}

    redis[("Redis<br/>SETNX+TTL seat holds")]

    stripe[["Stripe<br/>(stubbed — ADR-011)"]]

    attendee --> spa
    web -- "static files<br/>(index.html, JS, CSS)" --> spa
    spa -- "REST /api/** (HTTPS)" --> web
    spa -- "WebSocket /api/bookings/ws/**<br/>live seat updates — ADR-009" --> web
    web -- "REST + WebSocket<br/>/api prefix stripped" --> gateway

    gateway --> event
    gateway --> booking
    gateway --> payment
    gateway --> notification
    gateway --> waitlist

    booking -- "GET event/seat<br/>(RestClient)" --> event
    event -- "GET active-seat counts<br/>(batch — ADR-010)" --> booking

    booking <-. "hold / release<br/>TTL keyspace events" .-> redis

    event -. publish/consume .-> kafka
    booking -. publish/consume .-> kafka
    payment -. publish/consume .-> kafka
    notification -. publish/consume .-> kafka
    waitlist -. publish/consume .-> kafka

    payment -- "charge / refund<br/>(stub self-delivers webhook)" --> stripe

    style gateway fill:#4f46e5,color:#fff
    style web fill:#4f46e5,color:#fff
    style kafka fill:#232f3e,color:#fff
    style redis fill:#a41e11,color:#fff
    style stripe fill:#eee,stroke:#999,color:#333
```

**Reading the choreography**: no service calls another to trigger a saga
step — each service reacts to Kafka events published by others (ADR-002).
The two synchronous, request/response exceptions are booking-service
calling event-service to validate a seat/event at hold time, and
event-service calling booking-service's batch endpoint to compose live
seat availability (ADR-010) — both plain reads, not saga steps.
