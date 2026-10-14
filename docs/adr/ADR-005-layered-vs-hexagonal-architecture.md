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

---

---

# ADR-005: Interne Service-Struktur – Schichtenarchitektur statt Hexagonal

**Status:** Akzeptiert  
**Datum:** 2026-07-26  
**Autoren:** Engineering-Team

---

## Kontext

Jeder Microservice braucht eine interne Codestruktur – Regeln dafür, wie Packages
organisiert werden und welche Teile des Codes welche anderen aufrufen dürfen.

Drei Muster wurden ernsthaft bewertet:

---

### Option A: Schichtenarchitektur (unsere Wahl)

Die Anwendung ist in horizontale Schichten unterteilt, die jeweils auf der darunterliegenden
aufsetzen. Abhängigkeiten verlaufen nur nach unten. Obere Schichten kennen untere Schichten;
untere Schichten kennen die oberen nie.

```
┌─────────────────────────────────────┐
│  Controller (HTTP rein / HTTP raus) │  ← kennt Service
├─────────────────────────────────────┤
│  Service (Geschäftslogik)           │  ← kennt Repository, Kafka, Redis
├─────────────────────────────────────┤
│  Repository / Infrastruktur         │  ← kennt JPA, Datenbank
└─────────────────────────────────────┘
```

In der Praxis entspricht das direkt unserer Package-Struktur:

```
com.ticketing.booking/
├── controller/    ← HTTP-Schicht
├── service/       ← Geschäftslogik
├── repository/    ← Datenbankzugriff
├── kafka/         ← Kafka-Producer und -Consumer
├── domain/        ← JPA-Entities und Enums
└── dto/           ← API-Formen (Request-/Response-Objekte)
```

**Vorteile:**
- Äußerst gut verstanden. Jeder Spring-Boot-Entwickler weiß sofort, wo er für welchen
  Code suchen muss.
- Springs eigene Konventionen (Stereotypen wie `@Controller`, `@Service`, `@Repository`)
  sind um dieses Muster herum entworfen. Auto-Konfiguration, Transaktionsverwaltung und
  Dependency Injection passen von Natur aus dazu.
- Geringer Aufwand bei kleinen bis mittleren Services. Ein neuer Endpunkt sind drei Dateien:
  ein DTO, eine Service-Methode und eine Controller-Methode.

**Nachteile:**
- Das Domänenmodell (`Booking.java`) ist eine JPA-Entity – es trägt Framework-Annotationen
  (`@Entity`, `@Column`) direkt am Geschäftsobjekt. Die Domäne ist damit an das
  Persistenz-Framework gekoppelt.
- Wächst der Service, kann das `service/`-Package zur Müllhalde werden, wenn die Disziplin
  nicht gehalten wird.
- Die Geschäftslogik lässt sich schwerer völlig isoliert von der Datenbank unit-testen, weil
  die Entity DAS JPA-Objekt ist. Tests brauchen Mockito oder Testcontainers, um eine echte
  Datenbank zu vermeiden.

---

### Option B: Hexagonale Architektur (Ports and Adapters)

Von Alistair Cockburn vorgeschlagen. Die Idee: Die Domäne steht im Zentrum und wird
vollständig vor der Außenwelt (HTTP, Datenbanken, Kafka usw.) geschützt. Die Domäne definiert
*Ports* (Interfaces, die beschreiben, was sie braucht); *Adapter* (Implementierungen) liegen
außen und werden in diese Ports eingesteckt.

```
                ┌──────────────────────────┐
  HTTP ─────────┤ Driving Adapter          │
  Kafka ────────┤   (Controller, Consumer) │
                │                          │
                │  ┌────────────────────┐  │
                │  │   Domäne / Kern    │  │
                │  │  (reines Java,     │  │
                │  │   keine Framework- │  │
                │  │   Abhängigkeiten)  │  │
                │  └────────────────────┘  │
                │                          │
                │ Driven Adapter           ├──── PostgreSQL
                │   (Repository-Impl.,     ├──── Redis
                │    Kafka-Publisher)      ├──── Kafka
                └──────────────────────────┘
```

Die Package-Struktur würde so aussehen:

```
com.ticketing.booking/
├── domain/              ← reines Java: Booking, BookingStatus, BookingRepository (Interface)
├── application/         ← Use Cases: HoldSeatUseCase, ConfirmBookingUseCase
├── adapter/
│   ├── in/
│   │   ├── http/        ← BookingController (Driving Adapter)
│   │   └── kafka/       ← PaymentResultConsumer (Driving Adapter)
│   └── out/
│       ├── persistence/ ← JpaBookingRepository implementiert domain.BookingRepository
│       ├── redis/       ← RedisSeatHoldAdapter
│       └── kafka/       ← KafkaEventPublisher
```

Die Domänenklasse `Booking` wäre ein einfaches Java-Objekt ohne `@Entity`-Annotation. Eine
separate JPA-Entity (`BookingJpaEntity`) bildet auf die Datenbank ab, und ein Adapter
übersetzt zwischen beiden.

**Vorteile:**
- Die Domäne ist vollständig von allen Frameworks isoliert. `ConfirmBookingUseCase` lässt
  sich ohne Spring-Kontext, ohne Mockito, ohne Datenbank unit-testen – nur mit `new`-Objekten.
- Der Austausch der Infrastruktur ist theoretisch einfach: Man ersetzt den JPA-Adapter durch
  einen MongoDB-Adapter, und die Domäne bleibt unverändert.
- Erzwingt saubere Grenzen. Es ist strukturell unmöglich, dass Geschäftslogik versehentlich in
  HTTP-Belange hineingreift.

**Nachteile:**
- Deutlich mehr Boilerplate. Für jedes Konzept schreibt man: das Domänenobjekt, die
  Use-Case-Klasse, das Port-Interface, die Adapter-Implementierung und oft einen Mapper
  zwischen Domänenobjekt und JPA-Entity. Bei einem Service wie `booking-service` mit fünf
  Hauptoperationen ist das viel Zeremonie, bevor echte Logik geschrieben wird.
- Spring Boot arbeitet dem Muster leicht entgegen: `@Transactional` gehört je nach Auslegung
  an den Adapter oder den Use Case, JPA-Lazy-Loading erfordert eine verwaltete Entity, und
  Spring Security setzt auf Adapter-Ebene an. Das richtig hinzubekommen erfordert Erfahrung
  mit Spring und Hexagonal.
- Die Indirektion macht den Code für Entwickler, die das Muster nicht kennen, schwerer
  navigierbar.

---

### Option C: Clean Architecture (Robert Martin)

Eine Variante der hexagonalen Architektur mit strengeren Abhängigkeitsregeln, die durch
konzentrische „Ringe“ durchgesetzt werden: Entities → Use Cases → Interface Adapters →
Frameworks/Drivers. Sie teilt Vorteile und Kosten mit Hexagonal, fügt aber noch mehr
Zeremonie hinzu (Entities, Use Cases, Presenter und Gateways sind eigenständige Konzepte mit
separaten Klassen).

Nicht weiter geprüft; das Abwägungsprofil entspricht dem von Hexagonal, ist aber teurer.

---

## Entscheidung

Wir verwenden in Phase 1–2 für alle Services die **Schichtenarchitektur**.

Das Domänenmodell (`Booking.java`) trägt JPA-Annotationen direkt. Service-Klassen besitzen
die Geschäftslogik. Die Schichtregel (Controller rufen Services auf, Services rufen
Repositories auf; keine Rückwärtsabhängigkeiten) wird per Konvention und Code-Review
durchgesetzt.

---

## Begründung

### Das Komplexitätsbudget rechtfertigt Hexagonal noch nicht

Hexagonale Architektur zahlt sich aus, wenn die Fachdomäne so groß und komplex ist, dass
ihr Schutz vor Framework-Belangen echten Wert hat. Die Domäne des `booking-service` ist eine
Zustandsmaschine mit fünf Zuständen und drei Übergängen. Die Domänenlogik passt in eine
einzige `BookingService`-Klasse. Use-Case-Klassen, Port-Interfaces und Adapter-Mapper
einzuführen, kostet pro Feature drei bis vier zusätzliche Dateien – ohne spürbaren Gewinn
bei Lesbarkeit oder Testbarkeit.

### Spring Boot ist auf Schichten ausgelegt

Springs Kernabstraktionen (`@Controller`, `@Service`, `@Repository`, `@Transactional`,
JPA-Entity-Verwaltung) setzen alle ein Schichtenmodell voraus. Hexagonal in Spring erfordert
Workarounds (detached JPA-Entities, manuelle Transaktionsabgrenzung an Use Cases, Kampf gegen
die Controller-Annahmen von Spring Security). Das sind lösbare Probleme, aber sie erhöhen die
kognitive Last in einer Phase, in der wir schnell vorankommen wollen.

### Der Portfolio-Kontext bevorzugt Lesbarkeit vor architektonischer Reinheit

Dieses Projekt wird in Vorstellungsgesprächen von Entwicklern begutachtet. Die
Schichtenarchitektur ist die Lingua franca der Enterprise-Java-Entwicklung. Ein Reviewer
findet sich in unter einer Minute zurecht: „Controller nehmen Requests entgegen, Services
enthalten die Logik, Repositories sprechen mit der Datenbank.“ Hexagonal ist angesehen, setzt
aber voraus, dass der Reviewer das Muster kennt, um den Code flüssig zu lesen. Das Risiko,
dass der Code überentwickelt oder verwirrend wirkt, lohnt sich nicht.

### Der Nachteil (Domäne an JPA gekoppelt) wird bewusst akzeptiert, nicht ignoriert

Die Kopplung der Entity an JPA ist ein echter Kompromiss. Wir mildern sie ab, indem
`@Entity`-Klassen schlank bleiben – sie enthalten nur Felder und Getter/Setter, keine
Geschäftslogik. Die gesamte Logik liegt in der `service/`-Schicht, sodass die JPA-Kopplung
die Geschäftsregeln nicht verschmutzt. Wird ein Service so groß, dass das schmerzhaft wird,
kann er dann – Service für Service, ohne die anderen anzufassen – auf Hexagonal umgebaut
werden.

---

## Konsequenzen

### Positiv
- Jeder Spring-Boot-Entwickler ist vom ersten Tag an in der Codebasis produktiv.
- Weniger Dateien pro Feature beschleunigen die Lieferung in Phase 2 und 3.
- Framework-Features (Testcontainers, `@DataJpaTest`, Spring-Kafka-Testwerkzeuge) funktionieren
  ohne Adapter oder besondere Verdrahtung.

### Negativ / Abwägungen
- Unit-Tests der Service-Schicht in echter Isolation erfordern Mockito, um das Repository zu
  mocken. Integrationstests mit Testcontainers sind daher die bevorzugte Teststrategie.
- Geschäftslogik und Persistenzbelange können mit der Zeit verschwimmen, wenn die Disziplin
  nachlässt. Gegenmaßnahme ist die Regel: Entities sind dumme Datenhalter; `service/` besitzt
  die gesamte Logik.

---

## Ausblick

Wächst die Domäne eines Services in Phase 3 oder 4 erheblich – etwa wenn `booking-service`
komplexe Preisregeln, Rabattberechnungen oder einen anspruchsvollen Algorithmus zur
Sitzauswahl ansammelt –, kann dieser Service unabhängig auf Hexagonal migriert werden. Die
Microservice-Grenze sorgt dafür, dass diese Migration begrenzt bleibt und andere Services
nicht betrifft. Die AsyncAPI- und OpenAPI-Verträge bleiben unverändert; nur die interne
Struktur des Services entwickelt sich weiter.
