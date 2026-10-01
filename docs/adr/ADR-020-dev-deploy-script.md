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
