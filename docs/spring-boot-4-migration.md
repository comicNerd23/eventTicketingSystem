# Spring Boot 3 → 4 Migration Guide

This document records every breaking change encountered and the fix applied when upgrading the event ticketing platform from **Spring Boot 3.2.3** to **Spring Boot 4.1.0**, Java 21 to Java 25, and Testcontainers 1.20.4 to 1.21.3.

Affected services at time of migration: `booking-service`, `payment-simulator`.

---

## Version Bumps

| Component | Before | After |
|---|---|---|
| Spring Boot | 3.2.3 | 4.1.0 |
| Java | 21 | 25 |
| Testcontainers | 1.20.4 | 1.21.3 |

In each service's `pom.xml`:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>   <!-- was 3.2.3 -->
    <relativePath/>
</parent>

<properties>
    <java.version>25</java.version>                <!-- was 21 -->
    <testcontainers.version>1.21.3</testcontainers.version>  <!-- was 1.20.4 -->
</properties>
```

---

## 1. Jackson 2.x → Jackson 3.x

Spring Boot 4.x ships with **Jackson 3.x** (`tools.jackson` package) instead of Jackson 2.x (`com.fasterxml.jackson`).

### 1a. Package rename in all source files

Every import of a Jackson class must change package:

| Jackson 2.x (old) | Jackson 3.x (new) |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `com.fasterxml.jackson.databind.JsonNode` | `tools.jackson.databind.JsonNode` |
| `com.fasterxml.jackson.core.JsonProcessingException` | `tools.jackson.core.JacksonException` |
| `com.fasterxml.jackson.databind.SerializationFeature` | `tools.jackson.databind.SerializationFeature` |

Files changed in `booking-service`:
- `kafka/producer/BookingEventPublisher.java`
- `kafka/consumer/PaymentResultConsumer.java`
- `service/BookingService.java` (unused import — removed)
- `controller/BookingControllerTest.java`
- `integration/BookingSagaIntegrationTest.java`

Files changed in `payment-simulator`:
- `PaymentSimulatorConsumer.java`

### 1b. Remove `jackson-datatype-jsr310`

Jackson 3.x includes JSR-310 (Java date/time) support natively. The separate module is gone.

**`booking-service/pom.xml`** — remove:
```xml
<!-- REMOVE: conflicts with Jackson 3.x -->
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

**`payment-simulator/pom.xml`** — remove both explicit Jackson dependencies (Spring Boot now manages them):
```xml
<!-- REMOVE both -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

### 1c. Remove manual `JavaTimeModule` registration

Any code that did `new ObjectMapper().registerModule(new JavaTimeModule())` should become just `new ObjectMapper()`.

**`payment-simulator/PaymentSimulatorConsumer.java`** — before:
```java
private final ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```
After:
```java
private final ObjectMapper objectMapper = new ObjectMapper();
```

### 1d. Remove `spring.jackson.serialization.write-dates-as-timestamps` from config

This `application.yml` property cannot bind to Spring Boot 4.x's `JacksonProperties` (backed by Jackson 3.x). ISO-8601 is now the default format so the property is redundant.

**Both services' `application.yml`** — remove:
```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false   # REMOVE
```

### 1e. Add `spring-boot-starter-json` for non-web services

Services that use `spring-boot-starter` (not `spring-boot-starter-web`) no longer get Jackson transitively. Add the dedicated starter:

**`payment-simulator/pom.xml`** — add:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-json</artifactId>
</dependency>
```

---

## 2. Test Slice Annotations Moved to Separate Artifacts

Spring Boot 4.x split test slices out of `spring-boot-starter-test` into dedicated modules, each with a new root package.

### New dependencies in `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-data-jpa-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-resttestclient</artifactId>
    <scope>test</scope>
</dependency>
```

### Import changes

| Annotation / Class | Old import (Spring Boot 3.x) | New import (Spring Boot 4.x) |
|---|---|---|
| `@WebMvcTest` | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@DataJpaTest` | `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |
| `TestRestTemplate` | `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate` |

Files changed: `BookingControllerTest.java`, `BookingRepositoryTest.java`, `BookingSagaIntegrationTest.java`.

---

## 3. `@MockBean` / `@SpyBean` Removed

`@MockBean` and `@SpyBean` (from `org.springframework.boot.test.mock.mockito`) were removed. The replacement annotations from Spring Framework itself are `@MockitoBean` and `@MockitoSpyBean`.

| Old | New |
|---|---|
| `@MockBean` | `@MockitoBean` |
| `@SpyBean` | `@MockitoSpyBean` |
| `import org.springframework.boot.test.mock.mockito.MockBean` | `import org.springframework.test.context.bean.override.mockito.MockitoBean` |
| `import org.springframework.boot.test.mock.mockito.SpyBean` | `import org.springframework.test.context.bean.override.mockito.MockitoSpyBean` |

Files changed: `BookingControllerTest.java`, `BookingSagaIntegrationTest.java`.

---

## 4. `@AutoConfigureTestDatabase` Removed

`@AutoConfigureTestDatabase` (and its `Replace` enum) no longer exists in Spring Boot 4.x. With `@ServiceConnection` wiring Testcontainers directly to the datasource, the annotation was already redundant.

**`BookingRepositoryTest.java`** — remove:
```java
// REMOVE these two lines
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
```

---

## 5. JAVA_HOME Must Point to JDK 25

Maven resolves the compiler via `JAVA_HOME`, not `PATH`. If the system `JAVA_HOME` still points to an older JDK, compilation fails with:

```
release version 25 not supported
```

Permanent fix — set User environment variable (takes priority over System):
```
JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot
```

---

## 6. Kafka Starter and `@ServiceConnection`

In Spring Boot 4.x, Kafka `@ServiceConnection` support was moved to a dedicated module. Using the old `spring-kafka` artifact leaves `@ServiceConnection` on `KafkaContainer` without a factory, producing:

```
No ConnectionDetails found for '@ServiceConnection source for ...'
```

**Fix — `pom.xml`**: replace `spring-kafka` with the Spring Boot starter:

```xml
<!-- was spring-kafka / spring-kafka-test -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 7. Testcontainers: `KafkaContainer` Class Change

The old `org.testcontainers.containers.KafkaContainer` no longer works with Spring Boot 4.x `@ServiceConnection`. Use the new module-specific class instead:

```java
// was: import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Container
@ServiceConnection
static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
    DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
```

---

## 8. `@AutoConfigureTestRestTemplate` Required in `@SpringBootTest`

In Spring Boot 3.x, `TestRestTemplate` was automatically registered as a bean when using `@SpringBootTest(webEnvironment = RANDOM_PORT)`. In Spring Boot 4.x it is not — the bean must be explicitly requested:

```java
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate   // <-- required in Spring Boot 4.x
class MyIntegrationTest { ... }
```

---

## 9. `spring-boot-restclient` Missing Transitive Dependency (Maven)

`spring-boot-resttestclient` references `org.springframework.boot.restclient.RestTemplateBuilder` at runtime, but declares this dependency only in its **Gradle module metadata** (`.module` file), not in its Maven POM. Maven users must add it explicitly or the context will fail to load with:

```
Failed to introspect Class [TestRestTemplateTestAutoConfiguration]
@ConditionalOnMissingBean did not specify a bean using type, name or annotation
```

**Fix — `pom.xml`**: add alongside `spring-boot-resttestclient`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-restclient</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 10. Testcontainers / Docker Note

Testcontainers 1.21.3 on Windows with Docker Desktop 4.83.0 (Engine 29.6.2) fails to connect via the Windows named pipe due to authentication hardening in the Docker API gateway. All socket strategies return HTTP 400.

**Workaround**: Install **Testcontainers Desktop** and enable **"Use Testcontainers Cloud"** — it creates a local proxy at port 56318 that routes container requests to the cloud, bypassing the local Docker socket entirely. Confirmed working with `~/.testcontainers.properties` written by the app (`tc.host = tcp://127.0.0.1:56318`).

---

## Summary of Files Changed

| File | Change |
|---|---|
| `booking-service/pom.xml` | Spring Boot 4.1.0, Java 25, TC 1.21.3; added 3 test slice artifacts; removed `jackson-datatype-jsr310` |
| `payment-simulator/pom.xml` | Spring Boot 4.1.0, Java 25; removed explicit Jackson deps; added `spring-boot-starter-json` |
| `booking-service/src/main/resources/application.yml` | Removed `spring.jackson.serialization.write-dates-as-timestamps` |
| `payment-simulator/src/main/resources/application.yml` | Removed `spring.jackson.serialization.write-dates-as-timestamps` |
| `BookingEventPublisher.java` | `com.fasterxml.jackson` → `tools.jackson`; `JsonProcessingException` → `JacksonException` |
| `PaymentResultConsumer.java` | `com.fasterxml.jackson` → `tools.jackson` |
| `BookingService.java` | Removed unused Jackson import |
| `PaymentSimulatorConsumer.java` | `com.fasterxml.jackson` → `tools.jackson`; removed `JavaTimeModule` setup |
| `BookingControllerTest.java` | New `@WebMvcTest` import; `@MockBean` → `@MockitoBean`; Jackson import |
| `BookingRepositoryTest.java` | New `@DataJpaTest` import; removed `@AutoConfigureTestDatabase` |
| `BookingSagaIntegrationTest.java` | New `TestRestTemplate` import; `@SpyBean` → `@MockitoSpyBean`; Jackson import; `ConfluentKafkaContainer`; `@AutoConfigureTestRestTemplate` |
