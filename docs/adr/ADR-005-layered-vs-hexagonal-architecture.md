# ADR-005: Internal Service Structure — Layered Architecture over Hexagonal

**Status:** Accepted  
**Date:** 2026-07-26  
**Authors:** Engineering Team

---

## Context

Each microservice needs an internal code structure — a set of rules for how to organise
packages and which parts of the code are allowed to call which other parts.

Three patterns were seriously evaluated:

---

### Option A: Layered Architecture (what we chose)

The application is divided into horizontal layers, each sitting on top of the one below it.
Dependencies only flow downward. Upper layers know about lower layers; lower layers never
know about upper layers.

```
┌─────────────────────────────────────┐
│  Controller (HTTP in / HTTP out)    │  ← knows about Service
├─────────────────────────────────────┤
│  Service (business logic)           │  ← knows about Repository, Kafka, Redis
├─────────────────────────────────────┤
│  Repository / Infrastructure        │  ← knows about JPA, database
└─────────────────────────────────────┘
```

In practice, this maps directly to our package structure:

```
com.ticketing.booking/
├── controller/    ← HTTP layer
├── service/       ← business logic
├── repository/    ← database access
├── kafka/         ← Kafka producers and consumers
├── domain/        ← JPA entities and enums
└── dto/           ← API shapes (request/response objects)
```

**Pros:**
- Extremely well-understood. Any Spring Boot developer will instantly know where to look
  for any piece of code.
- Spring Boot's own conventions (stereotypes like `@Controller`, `@Service`, `@Repository`)
  are designed around this pattern. Auto-configuration, transaction management, and
  dependency injection all align with it naturally.
- Low ceremony for small-to-medium services. Adding a new endpoint is three files:
  a DTO, a service method, and a controller method.

**Cons:**
- The domain model (`Booking.java`) is a JPA entity — it carries framework annotations
  (`@Entity`, `@Column`) directly on the business object. The domain is therefore coupled
  to the persistence framework.
- As the service grows, the `service/` package can become a dumping ground if discipline
  is not maintained.
- Harder to unit-test the business logic in complete isolation from the database, because
  the entity IS the JPA object. Tests need Mockito or Testcontainers to avoid a real DB.

---

### Option B: Hexagonal Architecture (Ports and Adapters)

Proposed by Alistair Cockburn. The idea is to place the domain at the centre and protect
it completely from the outside world (HTTP, databases, Kafka, etc.). The domain defines
*ports* (interfaces describing what it needs), and *adapters* (implementations) live at
the outside and plug into those ports.

```
                ┌──────────────────────────┐
  HTTP ─────────┤ Driving Adapter          │
  Kafka ────────┤   (controller, consumer) │
                │                          │
                │  ┌────────────────────┐  │
                │  │   Domain / Core    │  │
                │  │  (pure Java, no    │  │
                │  │   framework deps)  │  │
                │  └────────────────────┘  │
                │                          │
                │ Driven Adapter           ├──── PostgreSQL
                │   (repository impl,      ├──── Redis
                │    Kafka publisher)      ├──── Kafka
                └──────────────────────────┘
```

The package structure would look like:

```
com.ticketing.booking/
├── domain/              ← pure Java: Booking, BookingStatus, BookingRepository (interface)
├── application/         ← use cases: HoldSeatUseCase, ConfirmBookingUseCase
├── adapter/
│   ├── in/
│   │   ├── http/        ← BookingController (driving adapter)
│   │   └── kafka/       ← PaymentResultConsumer (driving adapter)
│   └── out/
│       ├── persistence/ ← JpaBookingRepository implements domain.BookingRepository
│       ├── redis/       ← RedisSeatHoldAdapter
│       └── kafka/       ← KafkaEventPublisher
```

The domain `Booking` class would be a plain Java object with no `@Entity` annotation.
A separate JPA entity (`BookingJpaEntity`) maps to the database, and an adapter translates
between them.

**Pros:**
- The domain is completely isolated from all frameworks. You can unit-test `ConfirmBookingUseCase`
  with no Spring context, no Mockito, no database — just `new` objects.
- Swapping infrastructure is easy in theory: replace the JPA adapter with a MongoDB adapter
  and the domain never changes.
- Forces clean boundaries. It is structurally impossible for business logic to accidentally
  reach into HTTP concerns.

**Cons:**
- Significantly more boilerplate. For every concept you write: the domain object, the use
  case class, the port interface, the adapter implementation, and often a mapper between
  the domain object and the JPA entity. For a service like `booking-service` with five main
  operations, that is a lot of ceremony before any real logic is written.
- Spring Boot fights this pattern slightly: `@Transactional` belongs on the adapter or use
  case depending on your interpretation, JPA lazy-loading requires the entity to be managed,
  and Spring Security integrates at the adapter level. Getting these right requires experience
  with both Spring and hexagonal.
- The indirection makes the code harder to navigate for developers unfamiliar with the pattern.

---

### Option C: Clean Architecture (Robert Martin)

A variant of hexagonal with stricter dependency rules enforced by concentric "rings":
Entities → Use Cases → Interface Adapters → Frameworks/Drivers. Shares the same benefits
and costs as hexagonal but adds even more ceremony (entities, use cases, presenters,
gateways are all distinct concepts with separate classes).

Not evaluated further; the trade-off profile is the same as hexagonal but more expensive.

---

## Decision

We use **Layered Architecture** for all services in Phase 1–2.

The domain model (`Booking.java`) carries JPA annotations directly. Service classes own
business logic. The layering rule (controllers call services, services call repositories;
no reverse dependencies) is enforced by convention and code review.

---

## Rationale

### The complexity budget does not justify hexagonal yet

Hexagonal architecture earns its overhead when the business domain is large and complex
enough that protecting it from framework concerns has real value. The `booking-service`
domain is a state machine with five states and three transitions. The domain logic fits in
a single `BookingService` class. Introducing use case classes, port interfaces, and
adapter mappers at this stage adds three or four extra files per feature for no tangible
gain in readability or testability.

### Spring Boot is shaped around layers

Spring's core abstractions (`@Controller`, `@Service`, `@Repository`, `@Transactional`,
JPA entity management) all assume a layered mental model. Working with hexagonal in Spring
requires workarounds (detached JPA entities, manual transaction demarcation on use cases,
fighting Spring Security's controller-level assumptions). These are solvable problems, but
they add cognitive load during a phase where we are moving fast.

### The portfolio context favours legibility over architectural purity

This project will be reviewed by engineers in interviews. Layered architecture is the
lingua franca of enterprise Java development. A reviewer can orient themselves in under
a minute: "controllers receive requests, services have the logic, repositories talk to the
database." Hexagonal is well-regarded but requires the reviewer to be familiar with the
pattern to read the code fluently. The risk of the code appearing over-engineered or
confusing is not worth taking.

### The downside (domain coupled to JPA) is accepted, not ignored

The entity coupling to JPA is a real trade-off. We mitigate it by keeping `@Entity` classes
thin — they contain fields and getters/setters only, no business logic. All logic lives in
the `service/` layer, which means the JPA coupling does not pollute the business rules.
If a service grows large enough that this becomes painful, it can be refactored to hexagonal
at that point, service by service, without touching the others.

---

## Consequences

### Positive
- Any Spring Boot developer can be productive in the codebase from day one.
- Lower file count per feature accelerates Phase 2 and 3 delivery.
- Framework features (Testcontainers, `@DataJpaTest`, Spring Kafka test utilities) work
  without adapters or special wiring.

### Negative / Trade-offs
- Unit-testing the service layer in true isolation requires Mockito to mock the repository.
  Integration tests using Testcontainers are the preferred testing strategy as a result.
- Business logic and persistence concerns can blur over time if discipline lapses. Addressed
  by the rule: entities are dumb data holders; `service/` owns all logic.

---

## Future Consideration

If any service's domain grows substantially in Phase 3 or 4 — for example, if
`booking-service` accumulates complex pricing rules, discount calculations, or a rich seat
selection algorithm — that service can be migrated to hexagonal independently. The
microservice boundary means this migration is contained and does not affect other services.
The AsyncAPI and OpenAPI contracts remain unchanged; only the internal structure of the
service evolves.
