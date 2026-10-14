# ADR-021: Prod Images, Registry and Secrets

**Status:** Accepted
**Date:** 2026-10-01
**Authors:** Engineering Team

---

## Context

ADR-016 slice (e) covers prod: Secrets, an image registry and a deploy job. Prod is k3s on an
Oracle Always Free **Ampere A1 VM, which is arm64**. Until now every image was built locally
for amd64 and loaded into a dev cluster without a registry (ADR-020).

Facts checked on 2026-10-01:

- **GHCR is currently free, including private images.** The GitHub docs state: "Container image
  storage and bandwidth for the Container registry is currently free." GitHub promises at least
  a month's notice before that changes
  ([GitHub docs](https://docs.github.com/en/billing/concepts/product-billing/github-packages)).
  - The 500 MB / 1 GB allowance of GitHub Free applies to the other GitHub Packages registries,
    not to containers.
  - **ADR-016's statement that private GHCR storage is limited to about 500 MB was wrong** and is
    corrected here.
- **arm64 runners**: `ubuntu-24.04-arm` is a standard GitHub-hosted runner, available in private
  repositories since 2026-01-29
  ([changelog](https://github.blog/changelog/2026-01-29-arm64-standard-runners-are-now-available-in-private-repositories)).
  In public repositories, standard runners are free and have 4 vCPUs.
- **The repository is public.** Its visibility was found to have changed from private.
- **Every base image has an arm64 variant** (checked with `docker manifest inspect`):
  `confluentinc/cp-kafka:7.5.0` and `cp-zookeeper:7.5.0`, `postgres:15-alpine`, `redis:7-alpine`,
  `eclipse-temurin:25-jre`, `maven:3.9-eclipse-temurin-25`, `node:24-alpine` and
  `nginxinc/nginx-unprivileged:1.30-alpine`. Moving Kafka to KRaft is therefore **not** needed for
  arm64. That closes the open question from ADR-018.
- **Oracle halved the Always Free A1 allowance to 2 OCPU / 12 GB on 2026-06-15**
  ([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)). That still fits
  the stack, which needs about 3 GB in the dev overlay.

## Options considered

### Order of work
- **e1 images → e2 prod overlay and Secrets → e3 VM and deploy job (chosen).** Each part can be
  verified on its own; e1 and e2 need no VM.
- **The VM first.** Early real arm64 feedback, but everything would wait on the account setup.
- **Everything at once.** This contradicts the testable-slices rule in `docs/plan.md`.

### Registry
- **GHCR, public images (chosen).**
  - The source code is already public, so public images expose nothing new.
  - The cluster needs no `imagePullSecret`, so there is no token that can expire on the VM.
  - GitHub doesn't bill public images.
- **GHCR, private images.** This was chosen first, while the repository was believed to be
  private. It needs a pull secret holding a `read:packages` token.
- **Docker Hub Free.** Only one private repository, and public pulls are rate-limited. It would
  also be another account.

### Multi-arch build
- **Native runners per architecture, then one manifest (chosen).** amd64 builds on
  `ubuntu-latest` and arm64 on `ubuntu-24.04-arm`. Maven never runs under emulation.
- **QEMU emulation** on one runner. Simplest to configure, but Maven under emulation is many
  times slower.
- **arm64 only.** Half the builds, but the registry images could not be tested on the amd64
  dev machine in e2.

### Secrets
- **GitHub Actions secrets, turned into Kubernetes Secrets by the deploy job (chosen).** This
  uses `kubectl create secret … --dry-run=client -o yaml | kubectl apply -f -`. There is no
  controller, and nothing encrypted lives in the repository. It fits one cluster that is only
  deployed from Actions.
- **Sealed Secrets.** GitOps-friendly, but the decryption key lives in the cluster. Losing the VM
  means re-sealing every secret, unless the key is backed up separately.
- **SOPS + age.** Encrypted files in the repository, decrypted in the deploy job. It adds
  another tool and another key to manage.

## Decision (e1)

- **`.github/workflows/release-images.yml`** runs manually (`workflow_dispatch`) or on a `v*`
  tag push. It does not run on every push.
  - A `build` matrix covers 7 targets × {amd64 on `ubuntu-latest`, arm64 on `ubuntu-24.04-arm`}.
    Since 2026-10-04 both are pinned to `ubuntu-26.04` / `ubuntu-26.04-arm` (`docs/plan.md`).
    Each job pushes `ghcr.io/comicnerd23/ticketing/<target>:sha-<7>-<arch>`, with
    `provenance: false` and a GitHub Actions layer cache per target and architecture.
  - A `merge` job per target runs `docker buildx imagetools create` to join both into
    **`:sha-<7>`**, plus the Git tag (for example `:v1.0.0`) on a tag push.
- **Immutable tags only:** `sha-<commit>` and release tags, no `latest`. A deploy always names
  exactly what it runs, and a rollback is a redeploy of an older tag.
- The image name stays `ticketing/<target>` with a registry prefix, so the prod overlay can map
  it with kustomize `images: newName/newTag` without touching the base.
- The `org.opencontainers.image.source` label links each package to this repository. That
  gives the workflow's `GITHUB_TOKEN` write access on later runs.

## Decision (e2)

Further choices, made with the user:
- **The image tag is inserted at deploy time, not committed (GitOps was the alternative).** A
  deploy or rollback needs no commit. What runs is visible in the cluster and in the deploy
  job's log.
- **The gateway's actuator is closed at the frontend nginx (Traefik middleware was the
  alternative).** That keeps the Ingress controller-neutral (ADR-018), and the rule is the same
  in dev and prod.
- **The local test cluster is Rancher Desktop**, which runs k3s with Traefik like prod. kind was
  the alternative.

What was built:
- **`k8s/overlays/prod`** maps each `ticketing/<target>` to
  `ghcr.io/comicnerd23/ticketing/<target>` with the tag `set-by-deploy`. It defines no Secret
  and no ConfigMap, because the repository is public.
- **`deploy-prod.js --context=<ctx> --tag=<sha-…|vX.Y.Z> --base-url=<url>`** is the one deploy
  path, run locally and by the e3 deploy job. In order, it:
  - requires an **explicit kube-context**. Unlike `deploy-dev.js`, it never uses the current
    one.
  - accepts **only immutable tags** (`sha-<7 hex>` or `vX.Y.Z`; `latest` is refused).
  - checks with an anonymous GHCR token that the tag exists for **all seven** images, before
    anything is applied.
  - writes the namespace, the `db-credentials` Secret and the `gateway-config` ConfigMap from
    `DB_PASSWORD`, `GATEWAY_CORS_ALLOWED_ORIGINS` and `DB_USERNAME` (default `ticketing`). They
    go to `kubectl apply -f -` on **stdin**, so values never appear on a command line or in the
    log.
  - renders the overlay, replaces exactly seven placeholders, applies it and waits for every
    rollout.
  - smoke-tests through the Ingress: the app shell, `/api/events`, and **`/api/actuator/health`
    must return 404**.
- **The frontend nginx returns 404 for `location ^~ /api/actuator`.** Probes and later
  Prometheus reach the pods directly, inside the cluster.
- `k8s-helpers.js` holds the kubectl, rollout and smoke-check helpers shared with
  `deploy-dev.js`.

## Decision (e3)

Facts checked first:
- **A self-hosted runner on the VM is out.** GitHub advises against self-hosted runners on
  public repositories, because a fork's pull request can run code on them
  ([background](https://www.legitsecurity.com/blog/securing-your-ci/cd-pipeline-exploring-the-dangers-of-self-hosted-agents)).
  The deploy therefore runs on a GitHub-hosted runner, which has to reach the VM.
- **Oracle blocks traffic twice.** A port must be open in the VCN security list **and** in the
  Ubuntu image's iptables rules, before the image's own REJECT rule
  ([Oracle blog](https://blogs.oracle.com/developers/enabling-network-traffic-to-ubuntu-images-in-oracle-cloud-infrastructure)).

Choices, made with the user:
- **An SSH tunnel to the k3s API (chosen).** The alternatives were Tailscale, which means
  another account, and a public port 6443, which is not recommended. The job runs
  `ssh -L 6443:127.0.0.1:6443` with a dedicated deploy key, and the host key is pinned through
  `known_hosts`. The API port is never opened.
- **Plain HTTP on the public IP first.** TLS (sslip.io or a domain, plus Let's Encrypt) follows
  as its own slice.
- **Manual deploys with a tag input** (`workflow_dispatch`) in a GitHub environment named
  `production`. The alternatives were deploying on every `v*` tag or on every push.
- **Ubuntu 24.04** on the A1 VM. The alternative was Oracle Linux 9 with SELinux.

What was built:
- **`k8s/prod/setup-k3s.sh`** runs once on the VM and can be re-run safely. It opens 80/443 in
  iptables before the first REJECT, removes the image's blanket FORWARD REJECT (pod traffic is
  forwarded) and persists the rules. Then it installs k3s **`v1.36.4+k3s1`**, the stable channel
  and the same version as Rancher Desktop, and waits for the node.
- **`.github/workflows/deploy-prod.yml`** reads the kubeconfig, deploy key and `known_hosts`
  from the environment's secrets, renames the k3s context to `prod`, opens the tunnel and runs
  `deploy-prod.js --context=prod`.
- **`docs/runbooks/prod-vm.md`** lists the user's steps: the Oracle account, the instance and
  security list, the deploy key, and the environment secrets set with `gh secret set` over stdin.

**Known limit:** Postgres sets its password only when its volume is first initialized. Changing
`DB_PASSWORD` later updates the Secret, but not the database user. A password rotation needs an
`ALTER USER` in Postgres as well.

**Verified on 2026-10-02:** the first deploy to the real A1 VM (Ubuntu 24.04, k3s
`v1.36.4+k3s1`) ran green with tag `sha-8b82a63`
([run 37028620560](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37028620560)).
The tunnel from the hosted runner worked, and the API port stayed closed from outside. All 11 pods
came up without restarts, and the smoke check returned 200 for `/` and `/api/events` and 404 for
`/api/actuator/health`. On 2026-10-04 prod was seeded through runbook part D, and a hold →
confirm → cancel run in the browser worked, including the live seat updates over the WebSocket.
Details are in `docs/plan.md`.

## Consequences

**Positive**
- The prod VM pulls native arm64 images; dev machines can pull the same tags as amd64.
- Release tags can be traced to a commit, and older tags remain available for rollback.
- No registry cost and no pull secret.

**Negative / accepted**
- **14 build jobs per release**, plus 7 merge jobs. On a public repository that costs no minutes,
  but a release takes several minutes of wall time.
- **The per-arch tags (`-amd64`, `-arm64`) stay in the registry** next to the multi-arch tag. A
  push-by-digest flow would avoid them, at the cost of passing digests between jobs as artifacts.
- **Package visibility is a GHCR setting, not a workflow setting.** If the first push creates
  the packages as private, they have to be switched to public once in the package settings.
- **GHCR's free status can change**, with at least a month's notice. Images could then move to
  another registry by changing the `images:` mapping.

---

---

# ADR-021: Prod-Images, Registry und Secrets

**Status:** Akzeptiert  
**Datum:** 2026-10-01  
**Autoren:** Engineering-Team

---

## Kontext

ADR-016 Slice (e) behandelt Prod: Secrets, eine Image-Registry und einen Deploy-Job. Prod ist k3s
auf einer Oracle-Always-Free-**Ampere-A1-VM, die arm64 ist**. Bisher wurde jedes Image lokal für
amd64 gebaut und ohne Registry in einen Dev-Cluster geladen (ADR-020).

Am 2026-10-01 geprüfte Fakten:

- **GHCR ist derzeit kostenlos, auch für private Images.** Die GitHub-Doku sagt: „Container image
  storage and bandwidth for the Container registry is currently free.“ GitHub verspricht
  mindestens einen Monat Vorankündigung, bevor sich das ändert
  ([GitHub-Doku](https://docs.github.com/en/billing/concepts/product-billing/github-packages)).
  - Das 500-MB-/1-GB-Kontingent von GitHub Free gilt für die anderen GitHub-Packages-Registries,
    nicht für Container.
  - **Die Aussage in ADR-016, der Speicher für private GHCR-Images sei auf etwa 500 MB begrenzt,
    war falsch** und wird hier korrigiert.
- **arm64-Runner**: `ubuntu-24.04-arm` ist ein standardmäßiger GitHub-gehosteter Runner, seit
  2026-01-29 in privaten Repositories verfügbar
  ([Changelog](https://github.blog/changelog/2026-01-29-arm64-standard-runners-are-now-available-in-private-repositories)).
  In öffentlichen Repositories sind Standard-Runner kostenlos und haben 4 vCPUs.
- **Das Repository ist öffentlich.** Es stellte sich heraus, dass sich seine Sichtbarkeit von
  privat geändert hatte.
- **Jedes Basis-Image hat eine arm64-Variante** (geprüft mit `docker manifest inspect`):
  `confluentinc/cp-kafka:7.5.0` und `cp-zookeeper:7.5.0`, `postgres:15-alpine`, `redis:7-alpine`,
  `eclipse-temurin:25-jre`, `maven:3.9-eclipse-temurin-25`, `node:24-alpine` und
  `nginxinc/nginx-unprivileged:1.30-alpine`. Kafka auf KRaft umzustellen ist für arm64 daher
  **nicht** nötig. Das schließt die offene Frage aus ADR-018.
- **Oracle hat das Always-Free-A1-Kontingent am 2026-06-15 auf 2 OCPU / 12 GB halbiert**
  ([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)). Das passt weiterhin
  zum Stack, der im Dev-Overlay etwa 3 GB braucht.

## Betrachtete Optionen

### Reihenfolge der Arbeit
- **e1 Images → e2 Prod-Overlay und Secrets → e3 VM und Deploy-Job (gewählt).** Jeder Teil ist für
  sich verifizierbar; e1 und e2 brauchen keine VM.
- **Zuerst die VM.** Frühes echtes arm64-Feedback, aber alles würde auf die Kontoeinrichtung
  warten.
- **Alles auf einmal.** Das widerspricht der Regel testbarer Slices in `docs/plan.md`.

### Registry
- **GHCR, öffentliche Images (gewählt).**
  - Der Quellcode ist bereits öffentlich, öffentliche Images legen also nichts Neues offen.
  - Der Cluster braucht kein `imagePullSecret`, also gibt es auf der VM keinen ablaufenden Token.
  - GitHub berechnet öffentliche Images nicht.
- **GHCR, private Images.** Das wurde zuerst gewählt, als man das Repository für privat hielt. Es
  braucht ein Pull-Secret mit einem `read:packages`-Token.
- **Docker Hub Free.** Nur ein privates Repository, und öffentliche Pulls sind ratenbegrenzt. Es
  wäre außerdem ein weiteres Konto.

### Multi-Arch-Build
- **Native Runner pro Architektur, dann ein Manifest (gewählt).** amd64 baut auf `ubuntu-latest`
  und arm64 auf `ubuntu-24.04-arm`. Maven läuft nie unter Emulation.
- **QEMU-Emulation** auf einem Runner. Am einfachsten zu konfigurieren, aber Maven unter Emulation
  ist um ein Vielfaches langsamer.
- **Nur arm64.** Halb so viele Builds, aber die Registry-Images ließen sich in e2 nicht auf der
  amd64-Dev-Maschine testen.

### Secrets
- **GitHub-Actions-Secrets, vom Deploy-Job in Kubernetes-Secrets umgewandelt (gewählt).** Das
  nutzt `kubectl create secret … --dry-run=client -o yaml | kubectl apply -f -`. Es gibt keinen
  Controller, und im Repository liegt nichts Verschlüsseltes. Es passt zu einem Cluster, der nur
  aus Actions deployt wird.
- **Sealed Secrets.** GitOps-freundlich, aber der Entschlüsselungsschlüssel liegt im Cluster. Geht
  die VM verloren, muss jedes Secret neu versiegelt werden, sofern der Schlüssel nicht separat
  gesichert ist.
- **SOPS + age.** Verschlüsselte Dateien im Repository, im Deploy-Job entschlüsselt. Es fügt ein
  weiteres Werkzeug und einen weiteren zu verwaltenden Schlüssel hinzu.

## Entscheidung (e1)

- **`.github/workflows/release-images.yml`** läuft manuell (`workflow_dispatch`) oder bei einem
  `v*`-Tag-Push. Es läuft nicht bei jedem Push.
  - Eine `build`-Matrix deckt 7 Ziele × {amd64 auf `ubuntu-latest`, arm64 auf `ubuntu-24.04-arm`}
    ab. Seit 2026-10-04 sind beide auf `ubuntu-26.04` / `ubuntu-26.04-arm` festgelegt
    (`docs/plan.md`). Jeder Job pusht `ghcr.io/comicnerd23/ticketing/<target>:sha-<7>-<arch>`, mit
    `provenance: false` und einem GitHub-Actions-Layer-Cache pro Ziel und Architektur.
  - Ein `merge`-Job pro Ziel führt `docker buildx imagetools create` aus, um beide zu
    **`:sha-<7>`** zu verbinden, plus das Git-Tag (z. B. `:v1.0.0`) bei einem Tag-Push.
- **Nur unveränderliche Tags:** `sha-<commit>` und Release-Tags, kein `latest`. Ein Deployment
  benennt immer genau das, was es ausführt, und ein Rollback ist ein erneutes Deployment eines
  älteren Tags.
- Der Image-Name bleibt `ticketing/<target>` mit einem Registry-Präfix, sodass das Prod-Overlay ihn
  per kustomize `images: newName/newTag` abbilden kann, ohne die Basis anzufassen.
- Das Label `org.opencontainers.image.source` verknüpft jedes Paket mit diesem Repository. Das gibt
  dem `GITHUB_TOKEN` des Workflows bei späteren Läufen Schreibzugriff.

## Entscheidung (e2)

Weitere Entscheidungen, mit dem Nutzer getroffen:
- **Das Image-Tag wird zur Deploy-Zeit eingesetzt, nicht committet (GitOps war die Alternative).**
  Ein Deployment oder Rollback braucht keinen Commit. Was läuft, ist im Cluster und im Log des
  Deploy-Jobs sichtbar.
- **Das Actuator des Gateways wird am Frontend-nginx geschlossen (Traefik-Middleware war die
  Alternative).** Das hält den Ingress Controller-neutral (ADR-018), und die Regel ist in Dev und
  Prod gleich.
- **Der lokale Testcluster ist Rancher Desktop**, das k3s mit Traefik wie Prod betreibt. kind war
  die Alternative.

Was gebaut wurde:
- **`k8s/overlays/prod`** bildet jedes `ticketing/<target>` auf
  `ghcr.io/comicnerd23/ticketing/<target>` mit dem Tag `set-by-deploy` ab. Es definiert weder ein
  Secret noch eine ConfigMap, da das Repository öffentlich ist.
- **`deploy-prod.js --context=<ctx> --tag=<sha-…|vX.Y.Z> --base-url=<url>`** ist der eine
  Deploy-Pfad, lokal und vom e3-Deploy-Job ausgeführt. In dieser Reihenfolge:
  - verlangt einen **expliziten Kube-Context**. Anders als `deploy-dev.js` nutzt es nie den
    aktuellen.
  - akzeptiert **nur unveränderliche Tags** (`sha-<7 hex>` oder `vX.Y.Z`; `latest` wird
    abgelehnt).
  - prüft mit einem anonymen GHCR-Token, dass das Tag für **alle sieben** Images existiert, bevor
    irgendetwas angewendet wird.
  - schreibt den Namespace, das `db-credentials`-Secret und die `gateway-config`-ConfigMap aus
    `DB_PASSWORD`, `GATEWAY_CORS_ALLOWED_ORIGINS` und `DB_USERNAME` (Standard `ticketing`). Sie
    gehen über **stdin** an `kubectl apply -f -`, sodass Werte nie in einer Kommandozeile oder im
    Log erscheinen.
  - rendert das Overlay, ersetzt genau sieben Platzhalter, wendet es an und wartet auf jeden
    Rollout.
  - führt einen Smoke-Test über den Ingress aus: die App-Shell, `/api/events`, und
    **`/api/actuator/health` muss 404 liefern**.
- **Der Frontend-nginx liefert 404 für `location ^~ /api/actuator`.** Probes und später Prometheus
  erreichen die Pods direkt, innerhalb des Clusters.
- `k8s-helpers.js` enthält die kubectl-, Rollout- und Smoke-Check-Helfer, die mit `deploy-dev.js`
  geteilt werden.

## Entscheidung (e3)

Zuerst geprüfte Fakten:
- **Ein selbst gehosteter Runner auf der VM scheidet aus.** GitHub rät in öffentlichen Repositories
  von selbst gehosteten Runnern ab, da ein Pull Request aus einem Fork darauf Code ausführen kann
  ([Hintergrund](https://www.legitsecurity.com/blog/securing-your-ci/cd-pipeline-exploring-the-dangers-of-self-hosted-agents)).
  Das Deployment läuft daher auf einem GitHub-gehosteten Runner, der die VM erreichen muss.
- **Oracle blockiert Traffic doppelt.** Ein Port muss in der Sicherheitsliste des VCN **und** in
  den iptables-Regeln des Ubuntu-Images geöffnet sein, vor der REJECT-Regel des Images
  ([Oracle-Blog](https://blogs.oracle.com/developers/enabling-network-traffic-to-ubuntu-images-in-oracle-cloud-infrastructure)).

Entscheidungen, mit dem Nutzer getroffen:
- **Ein SSH-Tunnel zur k3s-API (gewählt).** Die Alternativen waren Tailscale, was ein weiteres
  Konto bedeutet, und ein öffentlicher Port 6443, der nicht empfohlen wird. Der Job führt
  `ssh -L 6443:127.0.0.1:6443` mit einem eigenen Deploy-Schlüssel aus, und der Host-Schlüssel ist
  über `known_hosts` festgesetzt. Der API-Port wird nie geöffnet.
- **Zuerst einfaches HTTP auf der öffentlichen IP.** TLS (sslip.io oder eine Domain, plus Let's
  Encrypt) folgt als eigener Slice.
- **Manuelle Deployments mit Tag-Eingabe** (`workflow_dispatch`) in einer GitHub-Umgebung namens
  `production`. Die Alternativen waren Deployment bei jedem `v*`-Tag oder bei jedem Push.
- **Ubuntu 24.04** auf der A1-VM. Die Alternative war Oracle Linux 9 mit SELinux.

Was gebaut wurde:
- **`k8s/prod/setup-k3s.sh`** läuft einmal auf der VM und kann gefahrlos erneut ausgeführt werden.
  Es öffnet 80/443 in iptables vor dem ersten REJECT, entfernt das pauschale FORWARD-REJECT des
  Images (Pod-Traffic wird weitergeleitet) und persistiert die Regeln. Danach installiert es k3s
  **`v1.36.4+k3s1`**, den stabilen Kanal und dieselbe Version wie Rancher Desktop, und wartet auf
  den Knoten.
- **`.github/workflows/deploy-prod.yml`** liest Kubeconfig, Deploy-Schlüssel und `known_hosts` aus
  den Secrets der Umgebung, benennt den k3s-Context in `prod` um, öffnet den Tunnel und führt
  `deploy-prod.js --context=prod` aus.
- **`docs/runbooks/prod-vm.md`** listet die Schritte des Nutzers auf: das Oracle-Konto, die
  Instanz und Sicherheitsliste, den Deploy-Schlüssel und die per `gh secret set` über stdin
  gesetzten Umgebungs-Secrets.

**Bekannte Grenze:** Postgres setzt sein Passwort nur bei der ersten Initialisierung seines Volumes.
Eine spätere Änderung von `DB_PASSWORD` aktualisiert das Secret, aber nicht den Datenbanknutzer.
Eine Passwortrotation braucht zusätzlich ein `ALTER USER` in Postgres.

**Verifiziert am 2026-10-02:** Das erste Deployment auf die echte A1-VM (Ubuntu 24.04, k3s
`v1.36.4+k3s1`) lief grün mit dem Tag `sha-8b82a63`
([Run 37028620560](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37028620560)).
Der Tunnel vom gehosteten Runner funktionierte, und der API-Port blieb von außen geschlossen. Alle
11 Pods kamen ohne Neustarts hoch, und der Smoke-Check lieferte 200 für `/` und `/api/events` und
404 für `/api/actuator/health`. Am 2026-10-04 wurde Prod über Teil D des Runbooks gesät, und ein
Durchlauf Reservieren → Bestätigen → Stornieren im Browser funktionierte, einschließlich der
Live-Sitzplatz-Updates über den WebSocket. Details stehen in `docs/plan.md`.

## Konsequenzen

**Positiv**
- Die Prod-VM zieht native arm64-Images; Dev-Maschinen können dieselben Tags als amd64 ziehen.
- Release-Tags lassen sich auf einen Commit zurückverfolgen, und ältere Tags bleiben für Rollbacks
  verfügbar.
- Keine Registry-Kosten und kein Pull-Secret.

**Negativ / akzeptiert**
- **14 Build-Jobs pro Release**, plus 7 Merge-Jobs. In einem öffentlichen Repository kostet das
  keine Minuten, aber ein Release dauert mehrere Minuten Echtzeit.
- **Die Tags pro Architektur (`-amd64`, `-arm64`) bleiben in der Registry** neben dem
  Multi-Arch-Tag. Ein Push-by-Digest-Ablauf würde sie vermeiden, auf Kosten der Weitergabe von
  Digests zwischen Jobs als Artefakte.
- **Die Sichtbarkeit von Paketen ist eine GHCR-Einstellung, keine Workflow-Einstellung.** Legt der
  erste Push die Pakete als privat an, müssen sie einmal in den Paketeinstellungen auf öffentlich
  umgestellt werden.
- **Der kostenlose Status von GHCR kann sich ändern**, mit mindestens einem Monat Vorankündigung.
  Images könnten dann durch Änderung der `images:`-Zuordnung in eine andere Registry umziehen.
