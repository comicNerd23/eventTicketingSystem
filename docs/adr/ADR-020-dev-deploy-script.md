# ADR-020: Dev Deploy Script for kind and Rancher Desktop

**Status:** Accepted
**Date:** 2026-10-01
**Authors:** Engineering Team

---

## Context

ADR-016 slice (d) asks for a dev deploy script against Rancher Desktop, with images loaded
locally and no registry. Slices (b) and (c) verified the manifests on **kind** in Docker Desktop
(ADR-018), with every step typed by hand: tag or build each image, `kind load` it, apply the
overlay, restart the pods. `seed-events.js` worked only against docker-compose, because it
truncated the event tables with `docker exec docker-postgres-1`.

Facts that shaped the decision (checked 2026-10-01):

- **Rancher Desktop 1.24.0** is the current stable release
  ([release notes](https://newreleases.io/project/github/rancher-sandbox/rancher-desktop/release/v1.24.0)).
  It runs k3s and bundles Traefik, like the prod target.
- **On Windows it runs as a WSL2 distribution.** All WSL2 distributions share one VM and the
  `.wslconfig` limits, so CPU and memory can't be set in Rancher Desktop itself
  ([Rancher Desktop docs](https://docs.rancherdesktop.io/1.8/ui/preferences/wsl/)).
  On this machine that cap is 4 GB, shared with Docker Desktop. Rancher Desktop with the stack
  and Docker Desktop with kind can't run at the same time.
- **With the `dockerd (moby)` runtime, k3s uses the same image store as the `docker` CLI.**
  A `docker build` is visible to the cluster immediately, with no push and no load
  ([Rancher](https://rancher.com/products/rancher-desktop)).

## Options considered

### Target cluster
- **A. Both, chosen from the kube-context (chosen).** `kind-*` builds and runs `kind load`;
  `rancher-desktop` only builds. kind stays usable next to docker-compose and Testcontainers in
  Docker Desktop, and Rancher Desktop gives a k3s dev cluster like prod.
- **B. Rancher Desktop only**, as ADR-016 wrote it. Every cluster session means quitting
  Docker Desktop, and with it compose and Testcontainers.
- **C. kind only.** Already works, but the dev cluster would not be k3s like prod, and ADR-016
  would need an amendment.

### Tool
- **Node script (chosen).** The same style as `seed-events.js`, `demo.js` and `ci.js`: Node
  built-ins only, and it runs the same on Windows without Git Bash.
- **Skaffold.** The standard build-and-deploy loop with kind support and file watching. It is
  another tool to install and learn, with more configuration for the same steps.
- **Tilt.** A comfortable dev loop with a web UI, but its own Starlark config and more memory,
  which is tight at 4 GB.

### Seeding in the cluster
- **`seed-events.js --k8s` (chosen).** One catalog, no duplicated code.
- **A separate script.** Easier to read, but the 13 events would be maintained twice.
- **A Kubernetes Job.** Kubernetes-native, but demo data doesn't belong in a manifest that prod
  could apply, and the catalog would move into YAML.

## Decision

- **`deploy-dev.js`**: `node deploy-dev.js [targets...] [--no-build] [--seed]`.
  - Reads `kubectl config current-context`. Only `kind-*` and `rancher-desktop` are accepted.
    Any other context is refused before anything is built, so a prod context can't be deployed
    to by accident.
  - Builds `ticketing/<target>:dev` from each Dockerfile, **one at a time**, because parallel
    Maven builds don't fit in the 4 GB VM. On kind it then runs `kind load docker-image`. kind is
    also found in winget's `Links` folder when it isn't on `PATH` yet.
  - On Rancher Desktop it builds with `docker --context <ctx>`: `rancher-desktop` if that
    context exists, otherwise `default`, which on Windows is the `docker_engine` pipe Rancher
    Desktop serves. The docker CLI's own current context often stays Docker Desktop's
    `desktop-linux`, so the script picks the context itself and checks that the engine reports
    "Rancher Desktop" before building. It never changes the global docker context.
  - Applies Traefik for kind when the cluster has no IngressClass yet. Rancher Desktop bundles
    Traefik.
  - `kubectl apply -k k8s/overlays/dev`. Then `rollout restart` for rebuilt targets **whose
    Deployment already existed before the apply**. The tag stays `:dev` and the pod spec doesn't
    change, so without a restart an existing Deployment keeps the old image. A Deployment that the
    apply just created already starts with the new image; restarting it too would start a second
    ReplicaSet next to the first.
  - Waits for every Deployment and StatefulSet in `ticketing`. Then a smoke check through the
    Ingress: `/` must return the app shell, `/api/events` a 200.
  - `--seed` runs `seed-events.js --k8s` with the cluster's base URL: `http://localhost:8000`
    for kind (k8s/kind/cluster.yaml), `http://localhost` for Rancher Desktop.
- **`seed-events.js --k8s [--base-url=…]`** posts the catalog through the Ingress (`/api`). The
  truncate runs via `kubectl exec postgres-0 -- psql`. Without the flag it behaves as before
  against compose.
- **Rancher Desktop runs with the `dockerd (moby)` runtime** and Kubernetes enabled, in place of
  Docker Desktop, never at the same time. Its Kubernetes version is set to the **stable k3s
  channel (1.36.4)**, the prod line. A fresh install preselected 1.25.16, which is long out of
  support.

  ```
  rdctl start --container-engine.name=moby --kubernetes.enabled=true
  rdctl set --kubernetes.version=1.36.4
  ```

## Consequences

**Positive**
- One command from source to a running, seeded cluster, the same on kind and Rancher Desktop.
- Only the named targets are rebuilt and restarted, so changing one service is a short loop.
- The context guard keeps dev tooling away from any non-dev cluster.

**Negative / accepted**
- **Switching between Docker Desktop and Rancher Desktop costs a WSL restart.** While Rancher
  Desktop runs, docker-compose, Testcontainers and the kind cluster are unavailable.
- **The `:dev` tag is mutable.** Pods are restarted to pick up a new build, and `kubectl
  rollout undo` can't go back to an older build. Immutable tags are part of the prod slice (e),
  where images go to a registry.
- **No file watching.** Each change means running the script again. Skaffold or Tilt can be
  added later without changing the manifests.
- **After Docker Desktop restarts, the stopped compose app containers can come back.** With
  `restart: on-failure` and no infrastructure they crash-loop. On the switch back from Rancher
  Desktop this pushed the kind node to a load of 46 on two CPUs, and every JVM failed its
  startup probe until they were stopped again.
- **`kubectl rollout status` can report done from stale status** right after a node restart.
  The smoke check through the Ingress catches that, because it tests real requests.
- `demo.js` still calls each service on its own port. In a cluster that needs
  `kubectl port-forward`. It is not changed here.

---

---

# ADR-020: Dev-Deploy-Skript für kind und Rancher Desktop

**Status:** Akzeptiert  
**Datum:** 2026-10-01  
**Autoren:** Engineering-Team

---

## Kontext

ADR-016 Slice (d) verlangt ein Dev-Deploy-Skript gegen Rancher Desktop, mit lokal geladenen
Images und ohne Registry. Die Slices (b) und (c) haben die Manifeste auf **kind** in Docker
Desktop verifiziert (ADR-018), wobei jeder Schritt von Hand getippt wurde: jedes Image taggen oder
bauen, per `kind load` laden, das Overlay anwenden, die Pods neu starten. `seed-events.js`
funktionierte nur gegen docker-compose, weil es die Event-Tabellen mit
`docker exec docker-postgres-1` leerte.

Fakten, die die Entscheidung prägten (geprüft am 2026-10-01):

- **Rancher Desktop 1.24.0** ist das aktuelle stabile Release
  ([Release-Notes](https://newreleases.io/project/github/rancher-sandbox/rancher-desktop/release/v1.24.0)).
  Es betreibt k3s und bündelt Traefik, wie das Prod-Ziel.
- **Unter Windows läuft es als WSL2-Distribution.** Alle WSL2-Distributionen teilen sich eine VM
  und die `.wslconfig`-Grenzen, daher lassen sich CPU und Speicher nicht in Rancher Desktop selbst
  einstellen ([Rancher-Desktop-Doku](https://docs.rancherdesktop.io/1.8/ui/preferences/wsl/)). Auf
  dieser Maschine beträgt diese Grenze 4 GB, geteilt mit Docker Desktop. Rancher Desktop mit dem
  Stack und Docker Desktop mit kind können nicht gleichzeitig laufen.
- **Mit der Laufzeit `dockerd (moby)` nutzt k3s denselben Image-Speicher wie die `docker`-CLI.**
  Ein `docker build` ist für den Cluster sofort sichtbar, ohne Push und ohne Laden
  ([Rancher](https://rancher.com/products/rancher-desktop)).

## Betrachtete Optionen

### Zielcluster
- **A. Beide, gewählt anhand des Kube-Contexts (gewählt).** `kind-*` baut und führt `kind load`
  aus; `rancher-desktop` baut nur. kind bleibt neben docker-compose und Testcontainers in Docker
  Desktop nutzbar, und Rancher Desktop liefert einen k3s-Dev-Cluster wie Prod.
- **B. Nur Rancher Desktop**, wie ADR-016 es schrieb. Jede Cluster-Sitzung bedeutet, Docker
  Desktop und damit Compose und Testcontainers zu beenden.
- **C. Nur kind.** Funktioniert bereits, aber der Dev-Cluster wäre nicht k3s wie Prod, und
  ADR-016 bräuchte eine Änderung.

### Werkzeug
- **Node-Skript (gewählt).** Derselbe Stil wie `seed-events.js`, `demo.js` und `ci.js`: nur
  Node-Bordmittel, und es läuft unter Windows ohne Git Bash genauso.
- **Skaffold.** Die Standard-Build-and-Deploy-Schleife mit kind-Unterstützung und Dateiüberwachung.
  Ein weiteres Werkzeug zum Installieren und Lernen, mit mehr Konfiguration für dieselben Schritte.
- **Tilt.** Eine komfortable Dev-Schleife mit Web-UI, aber mit eigener Starlark-Konfiguration und
  mehr Speicherbedarf, der bei 4 GB knapp ist.

### Seeding im Cluster
- **`seed-events.js --k8s` (gewählt).** Ein Katalog, kein duplizierter Code.
- **Ein separates Skript.** Leichter zu lesen, aber die 13 Events würden doppelt gepflegt.
- **Ein Kubernetes-Job.** Kubernetes-nativ, aber Demodaten gehören nicht in ein Manifest, das
  Prod anwenden könnte, und der Katalog würde in YAML wandern.

## Entscheidung

- **`deploy-dev.js`**: `node deploy-dev.js [targets...] [--no-build] [--seed]`.
  - Liest `kubectl config current-context`. Nur `kind-*` und `rancher-desktop` werden akzeptiert.
    Jeder andere Context wird abgelehnt, bevor irgendetwas gebaut wird, sodass nicht versehentlich
    in einen Prod-Context deployt werden kann.
  - Baut `ticketing/<target>:dev` aus jedem Dockerfile, **eines nach dem anderen**, da parallele
    Maven-Builds nicht in die 4-GB-VM passen. Bei kind führt es danach
    `kind load docker-image` aus. kind wird auch im `Links`-Ordner von winget gefunden, wenn es
    noch nicht im `PATH` ist.
  - Bei Rancher Desktop baut es mit `docker --context <ctx>`: `rancher-desktop`, falls dieser
    Context existiert, sonst `default`, was unter Windows die `docker_engine`-Pipe ist, die
    Rancher Desktop bedient. Der aktuelle Context der docker-CLI bleibt oft Docker Desktops
    `desktop-linux`, daher wählt das Skript den Context selbst und prüft, dass die Engine
    „Rancher Desktop“ meldet, bevor es baut. Den globalen Docker-Context ändert es nie.
  - Wendet Traefik für kind an, wenn der Cluster noch keine IngressClass hat. Rancher Desktop
    bündelt Traefik.
  - `kubectl apply -k k8s/overlays/dev`. Danach `rollout restart` für neu gebaute Ziele, **deren
    Deployment schon vor dem Apply existierte**. Das Tag bleibt `:dev` und die Pod-Spec ändert
    sich nicht, daher behält ein bestehendes Deployment ohne Neustart das alte Image. Ein
    Deployment, das der Apply gerade erst erzeugt hat, startet bereits mit dem neuen Image; es
    ebenfalls neu zu starten, würde ein zweites ReplicaSet neben dem ersten starten.
  - Wartet auf jedes Deployment und StatefulSet in `ticketing`. Danach ein Smoke-Check über den
    Ingress: `/` muss die App-Shell liefern, `/api/events` ein 200.
  - `--seed` führt `seed-events.js --k8s` mit der Basis-URL des Clusters aus:
    `http://localhost:8000` für kind (k8s/kind/cluster.yaml), `http://localhost` für Rancher
    Desktop.
- **`seed-events.js --k8s [--base-url=…]`** sendet den Katalog per POST über den Ingress (`/api`).
  Das Leeren der Tabellen läuft über `kubectl exec postgres-0 -- psql`. Ohne das Flag verhält es
  sich wie bisher gegen Compose.
- **Rancher Desktop läuft mit der Laufzeit `dockerd (moby)`** und aktiviertem Kubernetes, anstelle
  von Docker Desktop, nie gleichzeitig. Seine Kubernetes-Version ist auf den **stabilen
  k3s-Kanal (1.36.4)** gesetzt, die Prod-Linie. Eine frische Installation hatte 1.25.16
  vorausgewählt, was längst nicht mehr unterstützt wird.

  ```
  rdctl start --container-engine.name=moby --kubernetes.enabled=true
  rdctl set --kubernetes.version=1.36.4
  ```

## Konsequenzen

**Positiv**
- Ein Befehl vom Quellcode bis zu einem laufenden, gesäten Cluster, auf kind und Rancher Desktop
  gleich.
- Nur die genannten Ziele werden neu gebaut und neu gestartet, sodass die Änderung eines Services
  eine kurze Schleife ist.
- Die Context-Absicherung hält Dev-Tooling von jedem Nicht-Dev-Cluster fern.

**Negativ / akzeptiert**
- **Der Wechsel zwischen Docker Desktop und Rancher Desktop kostet einen WSL-Neustart.** Solange
  Rancher Desktop läuft, sind docker-compose, Testcontainers und der kind-Cluster nicht verfügbar.
- **Das Tag `:dev` ist veränderlich.** Pods werden neu gestartet, um einen neuen Build zu
  übernehmen, und `kubectl rollout undo` kann nicht auf einen älteren Build zurückgehen.
  Unveränderliche Tags gehören zum Prod-Slice (e), in dem Images in eine Registry gehen.
- **Keine Dateiüberwachung.** Jede Änderung bedeutet, das Skript erneut auszuführen. Skaffold oder
  Tilt können später ergänzt werden, ohne die Manifeste zu ändern.
- **Nach einem Neustart von Docker Desktop können die gestoppten Compose-App-Container
  zurückkehren.** Mit `restart: on-failure` und ohne Infrastruktur laufen sie in eine
  Absturzschleife. Beim Zurückwechseln von Rancher Desktop trieb das den kind-Knoten auf zwei CPUs
  auf eine Last von 46, und jede JVM scheiterte an ihrer Startup-Probe, bis sie wieder gestoppt
  wurden.
- **`kubectl rollout status` kann direkt nach einem Knoten-Neustart aus veraltetem Status „fertig“
  melden.** Der Smoke-Check über den Ingress fängt das ab, da er echte Anfragen testet.
- `demo.js` ruft weiterhin jeden Service auf seinem eigenen Port auf. In einem Cluster braucht das
  `kubectl port-forward`. Es wird hier nicht geändert.
