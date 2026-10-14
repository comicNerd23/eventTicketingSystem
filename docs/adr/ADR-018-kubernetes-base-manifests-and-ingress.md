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

---

---

# ADR-018: Kubernetes-Basismanifeste, Traefik-Ingress und kind zur lokalen Verifikation

**Status:** Akzeptiert  
**Datum:** 2026-10-01  
**Autoren:** Engineering-Team

---

## Kontext

ADR-016 Slice (b) verlangt die Basismanifeste:
- Deployments und Services für die sechs Services.
- StatefulSets für Postgres, Redis und Kafka.
- Einen Ingress, der den WebSocket-Pfad zum booking-service einschließt (ADR-009).
- Liveness- und Readiness-Probes auf den Actuator-Health-Gruppen.

ADR-017 fügte zwei Anforderungen hinzu:
- Jeder Pod braucht `enableServiceLinks: false`.
- booking-service braucht eine eigene Datenbank, da er in docker-compose noch die gemeinsame
  Datenbank `ticketing` nutzt (im Widerspruch zu ADR-001).

Vor diesem ADR existierten `k8s/base` und `k8s/overlays/{dev,prod}`, waren aber leer.

Drei Dinge waren zu entscheiden:

1. **Welcher Cluster die Manifeste jetzt verifiziert.** Die Entwicklungsmaschine begrenzt die
   Docker-VM auf 4 GB, und diese Grenze bleibt. Allein der Compose-Stack belegte davon etwa
   2,9 GB.
2. **Wie Traffic in den Cluster gelangt.** Der Community-Controller **ingress-nginx**, lange die
   Standardwahl, wurde im März 2026 eingestellt: keine Releases und Sicherheitsfixes mehr
   ([CNCF](https://www.cncf.io/blog/2026/04/02/ingress-nginx-retirement-experience-from-end-users/),
   [Fairwinds](https://www.fairwinds.com/blog/kubernetes-ingress-nginx-2026-migration)). Die
   Ingress-API ist feature-eingefroren, neue Arbeit geschieht in der Gateway API. Beide
   Zielcluster, k3s (Prod) und Rancher Desktop (Dev, das k3s betreibt), bündeln **Traefik** (v3.7
   in aktuellen k3s-Releases) als Standard-Ingress-Controller
   ([k3s-Release-Notes](https://newreleases.io/project/github/k3s-io/k3s/release/v1.36.3%2Bk3s1),
   [Rancher-Desktop-Doku](https://docs.rancherdesktop.io/how-to-guides/traefik-ingress-example/)).
   Traefik bedient sowohl Ingress- als auch Gateway-API-Ressourcen.
3. **Wie Kafka im Cluster läuft.**

## Betrachtete Optionen

### Cluster zur Verifikation
- **kind in Docker Desktop (gewählt).** Ein `winget install`. Es nutzt die vorhandene Docker-VM
  wieder, und der Cluster ist wegwerfbar. ADR-016 nennt kind bereits als Dev-Alternative.
- **Rancher Desktop jetzt.** Das ist das echte Dev-Ziel, aber eine zweite VM neben Docker
  Desktop auf einer Maschine mit wenig freiem Speicher. Auf Slice (d) verschoben.
- **Nur Offline-Validierung** (`kubectl kustomize`, Dry-Run). Braucht keinen Speicher, beweist
  aber nicht, dass tatsächlich etwas läuft.

### Einstiegspunkt
- **A. Ingress-Ressource, bedient von Traefik (gewählt).**
  - Funktioniert in k3s und Rancher Desktop, ohne dass etwas Zusätzliches zu installieren ist.
  - kind braucht ein kleines Traefik-Manifest.
- **B. Gateway API (`Gateway` + `HTTPRoute`) mit Traefik.**
  - Die zukunftsweisende API.
  - Aber jede Umgebung braucht die Gateway-API-CRDs und zusätzlich den aktivierten
    Gateway-Provider von Traefik. In k3s ist das ein `HelmChartConfig`-Override.
  - Das ist in jeder Umgebung mehr Konfiguration für dieselbe einzelne Route.
- **C. Kein Ingress.** Das api-gateway würde als NodePort- oder LoadBalancer-Service
  veröffentlicht. Das ist die einfachste Option, widerspricht aber ADR-016, und TLS und
  Host-Routing landeten später im Spring-Gateway.

### Kafka
- **Dieselben Images wie in docker-compose (gewählt):** cp-zookeeper und cp-kafka 7.5.0. Compose
  und Kubernetes verhalten sich gleich, und in diesem Slice muss nichts Neues gelernt werden.
- **KRaft-Modus (ohne ZooKeeper).** Würde einen Pod sparen (~100–200 MB). Es ist eine
  Broker-Änderung, die auch in Compose einfließen müsste, daher besser als eigener Slice.

## Entscheidung

### Aufbau

```
k8s/
  base/                 umgebungsneutral, Namespace "ticketing"
    infra/              postgres, redis, zookeeper, kafka (je StatefulSet + Service)
    services/           sechs Paare aus Deployment + Service
    ingress.yaml        ein Ingress: / -> api-gateway:8080
  overlays/dev/         Image-Tags (:dev), db-credentials-Secret, gateway-config-ConfigMap
  overlays/prod/        noch leer, wird in Slice (e) gefüllt
  kind/                 cluster.yaml + traefik.yaml, nur zur lokalen Verifikation
```

### Entscheidungen in den Manifesten

- **Eine Ingress-Regel, `/` auf api-gateway.** Das Gateway routet `/bookings/ws/**` bereits auf
  den WebSocket-Endpunkt des booking-service (ADR-007, ADR-009), und Traefik reicht
  WebSocket-Upgrades ohne zusätzliche Konfiguration durch.
  - Der Ingress hat keine `ingressClassName`, wird also von der Standardklasse des Clusters
    bedient. In k3s ist das Traefik; bei kind markiert `k8s/kind/traefik.yaml` dessen
    IngressClass auf dieselbe Weise als Standard.
- **Das Spring-Profil `prod` in jedem Cluster, auch in Dev.** In einem Cluster wird jeder Wert
  explizit übergeben, und das Prod-Profil scheitert beim Start, wenn einer fehlt (ADR-017s
  `RequiredConfigurationCheck`). Das macht das Profil zu einer Vollständigkeitsprüfung der
  Manifeste.
- **`enableServiceLinks: false` auf jedem Pod.** Ein Kontroll-Pod ohne diese Einstellung bekam
  `REDIS_PORT=tcp://10.96.x.x:6379` und `KAFKA_PORT=tcp://…`. Mit ihr sieht booking-service
  `REDIS_PORT=6379`, und der Kafka-Pod hat gar kein `KAFKA_PORT`.
- **Eine Datenbank pro Service, auch für booking-service.** Ein Postgres-StatefulSet. Eine
  `postgres-init`-ConfigMap legt `ticketing_events`, `ticketing_bookings`, `ticketing_payments`,
  `ticketing_notifications` und `ticketing_waitlist` an, und Flyway erzeugt jedes Schema.
  - docker-compose wird hier **nicht** geändert. Den booking-service dort auf
    `ticketing_bookings` umzustellen, erfordert, dass diese Datenbank in bestehenden Dev-Volumes
    von Hand angelegt wird, das ist also ein separater kleiner Fix.
- **Probes** auf den Gruppen `/actuator/health/liveness` und `/readiness` von Spring Boot. Boot
  aktiviert sie automatisch, wenn es Kubernetes erkennt.
  - Ein `startupProbe` erlaubt bis zu 3 Minuten, da sechs gleichzeitig startende JVMs auf zwei
    CPUs langsam sind.
  - Liveness nutzt `timeoutSeconds: 3`. Unter dieser Konkurrenz wurde der Standard von 1 s beim
    Start überschritten.
- **Ressourcen** sind für die 4-GB-Maschine bemessen:
  - Jeder Service fordert 300 Mi an und ist auf 512 Mi begrenzt. `MaxRAMPercentage=60` hält den
    Heap innerhalb des Limits.
  - Kafka ist auf 384 MB Heap begrenzt (768 Mi Limit), ZooKeeper auf 128 MB.
  - Es gibt keine CPU-Limits, die nur den JVM-Start verlangsamen würden.
- **Zugangsdaten** kommen aus einem `db-credentials`-Secret. Das Dev-Overlay erzeugt es mit dem
  lokalen Passwort; Prod bekommt echte Secrets in Slice (e).
- **Vorerst nicht im Cluster:** Prometheus, Grafana und Kafdrop. Sie passen neben dem Stack nicht
  in 4 GB und gehören zu den Observability-Slices.

## Konsequenzen

**Positiv**
- Dieselbe Basis läuft unverändert auf kind, Rancher Desktop und k3s; nur das Overlay
  unterscheidet sich.
- Eine fehlende Umgebungsvariable in einem Manifest scheitert dank des Prod-Profils laut beim
  Start.
- booking-service hat in Kubernetes endlich seine eigene Datenbank, wie ADR-001 es verlangt.

**Negativ / akzeptiert**
- **Die Ingress-API ist eingefroren.** Der Wechsel zur Gateway API ist eine spätere, begrenzte
  Änderung: eine `HTTPRoute` statt eines Ingress, plus Aktivierung von Traefiks Gateway-Provider.
- **Keine Startreihenfolge.** Bei einem Kaltstart kann Kafka vor ZooKeeper hochkommen und die
  Services vor Postgres. Sie stürzen ab und werden von Kubernetes neu gestartet, bis ihre
  Abhängigkeiten laufen: 1–2 Neustarts pro Pod, alles nach etwa 5 Minuten Ready. Init-Container,
  die auf Abhängigkeiten warten, könnten das beseitigen. Sie wurden weggelassen, weil Neustarten
  der normale Kubernetes-Weg zur Konvergenz ist.
- **Der Speicher ist knapp.** Der kind-Knoten belegt mit dem vollen Stack etwa 3,0 von 3,8 GiB.
  Der Compose-Stack und der Cluster können nicht gleichzeitig laufen.
- `seed-events.js` leert Tabellen mit `docker exec docker-postgres-1` und funktioniert daher nur
  gegen Compose. Ein Cluster-Äquivalent gehört in das Dev-Deploy-Skript, Slice (d).
- `kubectl apply` meldet die drei StatefulSets mit Volume Claims bei jedem Lauf als „configured“,
  obwohl `kubectl diff` keine Änderung zeigt und kein Pod neu erzeugt wird. Das ist eine
  Eigenheit des clientseitigen Apply mit `volumeClaimTemplates` und harmlos.
- **Prod braucht noch:**
  - arm64- oder Multi-Arch-Images. Dazu gehört, cp-kafka 7.5.0 auf arm64 zu prüfen oder auf KRaft
    mit einem Image umzusteigen, das einen arm64-Build hat.
  - Eine Registry.
  - Echte Secrets.

  Das ist alles Slice (e).
