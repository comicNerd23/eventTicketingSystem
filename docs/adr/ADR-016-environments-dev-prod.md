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

---

---

# ADR-016: Dev- und Prod-Umgebungen

**Status:** Akzeptiert (nur Entscheidung – die Umsetzung folgt in separaten Slices)  
**Datum:** 2026-09-29  
**Autoren:** Engineering-Team

---

## Kontext

Die Anwendung soll in zwei Umgebungen laufen: **Dev**, die lokal deployt, betrieben und getestet
werden kann, und **Prod**. Zwei weitere Randbedingungen gelten:

- Sie muss **kostenlos** bleiben.
- Die Dev-Umgebung soll dem tatsächlichen Deployment ähneln, nicht nur der Entwicklung. Werkzeuge
  wie LocalStack oder Floci wurden als Kandidaten für die Dev-Seite genannt.

Der ursprüngliche Phase-5-Plan nannte **GCP Cloud Run** als Deployment-Ziel, und das Repo enthält
bereits leeres Kustomize-Gerüst: `k8s/base`, `k8s/overlays/dev`, `k8s/overlays/prod`.

### Wovon die Services tatsächlich abhängen
Keiner der sechs Services ruft eine Cloud-Provider-API auf. Sie sprechen direkt mit **Kafka**,
**PostgreSQL** (eine Datenbank pro Service, ADR-001) und **Redis** (ADR-003). Die Konfiguration
liegt heute in einer `application.yml` pro Service, mit fest eincodierten Zugangsdaten
(`ticketing`/`ticketing`), `ddl-auto: update` und `DEBUG`-Logging – Werte, die für die lokale
Entwicklung in Ordnung, für Prod aber ungeeignet sind.

## Optionen

### Option A: AWS + LocalStack / Floci für Dev
LocalStack und Floci emulieren **AWS-APIs**. Sie bringen nur Nutzen, wenn Prod auf
AWS-verwalteten Diensten läuft (RDS, ElastiCache, MSK, ECS/EKS) und das Deployment
Infrastructure-as-Code ist (z. B. Terraform), in Dev gegen den Emulator und in Prod gegen echtes
AWS angewendet.

Am 2026-09-29 gegen die eigenen Seiten der Anbieter geprüft:

- **LocalStack**: Seit Release 2026.03.0 braucht jedes Image einen Auth-Token. Der einzige
  kostenlose Tarif, *Hobby*, ist **nur nichtkommerziell** und enthält keinen der Dienste, die
  diese App bräuchte. RDS, ECS und ElastiCache beginnen im kostenpflichtigen Tarif *Base*; EKS und
  MSK brauchen *Ultimate* ([Tarife](https://docs.localstack.cloud/aws/licensing/)). **Für diese
  App nicht kostenlos.**
- **Floci** ([floci-io/floci](https://github.com/floci-io/floci)): MIT-lizenziert, kein Konto,
  kein Auth-Token, keine Feature-Sperren. Es emuliert alle fünf Dienste, die diese App bräuchte,
  jeweils gestützt auf echte Engine-Container:

  | AWS-Dienst | Was Floci betreibt |
  |---|---|
  | RDS | `postgres:16-alpine` |
  | ElastiCache | `valkey/valkey:8` |
  | MSK | `redpandadata/redpanda` – Kafka-API-kompatibel, aber nicht Apache Kafka selbst |
  | ECS | echte Docker-Container |
  | EKS | `rancher/k3s` |

  **Für die Dev-Seite kostenlos.**
- Floci räumt den Einwand der Emulator-Kosten aus, aber nicht den Kerneinwand: **Prod auf
  echtem AWS ist nicht kostenlos.**
  - MSK hat kein kostenloses Kontingent.
  - EKS berechnet die Control Plane.
  - Fargate hat kein kostenloses Kontingent.
  - Dev auf Floci würde die Bereitstellung für ein AWS-Prod einüben, das sich dieses Projekt nicht
    leisten kann.
- Die Services rufen zur Laufzeit weiterhin keine AWS-APIs auf, Floci würde also nur
  *Infrastruktur* emulieren (z. B. Terraform, das auf Floci statt auf AWS angewendet wird), nichts,
  was der Anwendungscode tut.
- Es würde Prod außerdem von GCP nach AWS verlegen.

### Option B: GCP Cloud Run (ursprünglicher Plan)
- Es gibt keinen Cloud-Run-Emulator; Dev bliebe bei docker compose oder einem lokalen Cluster.
- Cloud Run skaliert auf null, was langlebige **Kafka-Consumer** bricht. Jeder Service außer
  api-gateway konsumiert Kafka. Instanzen warm zu halten übersteigt das kostenlose Kontingent.
- Cloud SQL und Memorystore haben kein kostenloses Kontingent.

### Option C: Kubernetes in beiden Umgebungen – lokaler Cluster für Dev, k3s auf einer kostenlosen VM für Prod
- Dieselben Container-Images und dieselben `k8s/base`-Manifeste laufen in beiden Umgebungen; die
  Unterschiede liegen in den bereits vorhandenen Kustomize-Overlays `k8s/overlays/{dev,prod}`.
- Dev läuft auf einem lokalen Cluster: **Rancher Desktop** (k3s, kostenlos, Open Source), mit kind
  oder k3d als leichtgewichtigeren Alternativen.
- Prod läuft auf **k3s** auf einer kostenlosen VM, z. B. Oracle Cloud Always Free. Kafka, Postgres
  und Redis laufen im Cluster.
  - **Geprüft am 2026-09-29:** Oracle hat das Ampere-A1-Kontingent am 2026-06-15 ohne öffentliche
    Ankündigung von 4 OCPU / 24 GB auf **2 OCPU / 12 GB** gekürzt
    ([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)).
  - Das genügt weiterhin für k3s plus diesen Stack. Grobe Schätzung:
    - sechs Spring-Boot-JVMs: etwa 3 GB
    - Kafka und ZooKeeper: etwa 1,5 GB
    - Postgres, Redis und k3s selbst: etwa 1–2 GB

    Das lässt wenig Spielraum für Rancher Manager.
  - A1 ist **arm64**, daher muss jedes Image, eigene wie Drittanbieter, für arm64 verfügbar sein.
    Unsere Images basieren auf `eclipse-temurin`; beim Confluent-Kafka-Image ist das im
    Prod-Slice noch zu prüfen. CI würde dann Multi-Arch-Images mit `docker buildx` bauen.
  - Kostenlose Kontingente ändern sich ohne Vorankündigung, wie diese Kürzung zeigt. Die Grenzen
    zu Beginn des Prod-Slices erneut prüfen.
- **Rancher Manager** (Apache 2.0, kostenlos; nur der Rancher-Prime-Support ist kostenpflichtig)
  kann auf k3s aufgesetzt werden für die UI, RBAC und Multi-Cluster-Verwaltung, die viele
  Unternehmen auf Private-Cloud-Plattformen nutzen. Er braucht allein mehrere GB RAM.

## Entscheidung

**Option C.**

- **Dev = lokales Kubernetes auf Rancher Desktop** (kind/k3d als dokumentierte Alternativen).
- **Prod = k3s auf einer kostenlosen VM.**
- **Rancher Manager ist optional**, in einem eigenen Slice nach dem ersten erfolgreichen
  Prod-Deployment – er trägt mehr zur Betriebsgeschichte bei, als er für einen einzelnen Cluster
  technisch erforderlich ist.
- **RKE2** (Ranchers gehärtete Kubernetes-Distribution) wird als möglicher späterer
  Härtungsschritt anstelle von k3s vermerkt.
- „GCP Cloud Run“ wird von der Roadmap gestrichen.

Es wird kein Cloud-Emulator benötigt: Die Dev-Umgebung deployt den echten Stack auf eine echte
Kubernetes-API, genau das, was Prod betreibt.

**Floci bleibt ein dokumentierter Fallback.** Zieht Prod je nach AWS um, etwa mit Guthaben oder
einem bezahlten Konto, ist Floci das kostenlose Dev-Gegenstück. LocalStack ist es nicht: Sein
kostenloser Tarif schließt jeden Dienst aus, den diese App braucht. Flocis EKS-Emulation ist
selbst k3s, die Kubernetes-Manifeste aus dieser Entscheidung ließen sich also übernehmen.

## Erforderliche Änderungen (Folge-Slices, in Reihenfolge)

- **(a) Spring-Konfiguration pro Umgebung.** Fest eincodierte Werte durch Umgebungsvariablen
  (mit lokalen Standardwerten) ersetzen, `application-dev.yml` / `application-prod.yml`
  ergänzen. Prod: `ddl-auto: validate` mit **Flyway**-Migrationen, `INFO`-Logging,
  Zugangsdaten nur aus Secrets.
- **(b) K8s-Basismanifeste.** Deployments und Services für die sechs Services; StatefulSets für
  Postgres, Redis und Kafka; ein Ingress einschließlich des WebSocket-Pfads zum booking-service
  (ADR-009); Liveness-/Readiness-Probes auf den vorhandenen Actuator-Health-Gruppen.
- **(c) Containerisiertes Frontend** (z. B. nginx), mit pro Umgebung gesetzten API- und
  WebSocket-URLs.
- **(d) Dev-Deploy-Skript** gegen Rancher Desktop – Images lokal geladen, keine Registry.
- **(e) Prod.**
  - Secrets über Sealed Secrets oder GitHub-Actions-Secrets.
  - Eine Image-Registry: Der Speicher für private GHCR-Images ist im kostenlosen Tarif klein
    (etwa 500 MB), zu wenig für sechs JVM-Images. Das bedeutet entweder ein öffentliches Repository
    oder eine andere kostenlose Registry.
    *Korrigiert in ADR-021:* Die 500-MB-Grenze gilt für die anderen GitHub-Packages-Registries;
    der GHCR-Container-Speicher ist derzeit kostenlos, auch für private Images.
  - Ein manuell oder per Tag ausgelöster Deploy-Job.
- **(f) Optional:** Rancher Manager.

## Konsequenzen

**Positiv**
- In beiden Umgebungen kostenlos.
- Dev und Prod unterscheiden sich nur in Overlays, nicht in der Deployment-Mechanik.
- Nutzt bereits vorhandenes Gerüst.
- Kubernetes plus Rancher ist das Setup, das man häufig auf Private-Cloud-Plattformen findet.

**Negativ / akzeptiert**
- Kafka, Postgres und Redis in Prod selbst zu hosten bedeutet, dass Backups und Upgrades unsere
  Verantwortung sind – für ein Portfolio-System mit wegwerfbaren Demodaten akzeptabel.
- Eine einzelne kostenlose VM ist ein Single Point of Failure; es gibt keine Hochverfügbarkeit.
- Kostenlose Angebote ändern sich mit der Zeit; sowohl LocalStack (März 2026) als auch Oracle
  (Juni 2026) haben ihre dieses Jahr gekürzt. Zu Beginn des jeweiligen Slices erneut prüfen.
- Die Prod-VM ist arm64, während die Entwicklung auf x86 stattfindet, daher braucht Prod
  Multi-Arch-Images.
