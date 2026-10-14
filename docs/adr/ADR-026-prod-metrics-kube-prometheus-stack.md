# ADR-026: Metrics in Prod with kube-prometheus-stack (Helm)

**Status:** Accepted
**Date:** 2026-10-05
**Authors:** Engineering Team

---

## Context

ADR-012 added metrics for the local Compose stack: every service exposes
`/actuator/prometheus` (Micrometer), Prometheus scrapes it, and Grafana shows the provisioned
dashboard `ticketing-overview.json`. None of this exists in Kubernetes. `k8s/base/infra` holds
only Postgres, Redis, Kafka and Zookeeper, and the frontend's nginx blocks `/api/actuator` from
outside, so that Prometheus can scrape the pods from inside the cluster later (ADR-021).

The user wants metrics in prod and wants to learn **Helm**, the way most companies install
third-party software on Kubernetes.

Checked on 2026-10-05:
- **kube-prometheus-stack 91.9.0** (published 2026-10-02, Prometheus Operator v0.94.1) is the
  current chart. It bundles the Prometheus Operator, Prometheus 3.15, Alertmanager 0.34,
  Grafana 13.2, kube-state-metrics 2.20 and node-exporter 1.12. It requires Kubernetes >= 1.25.
- **The chart is published as an OCI artifact:**
  `oci://ghcr.io/prometheus-community/charts/kube-prometheus-stack:91.9.0`, digest
  `sha256:04047e500c803dc8d2bc284ecb43e9229bb32b5c36bd13fc89592c8a2f3cdc72`.
- **Every image of the chart has linux/arm64 and linux/amd64 variants** (`docker buildx
  imagetools inspect`), including `prometheus-config-reloader` and the Grafana sidecar. The prod
  VM is arm64 (ADR-016).
- **Helm 4.2.3** is preinstalled on GitHub's ubuntu-26.04 runners, the same version that Rancher
  Desktop ships locally.
- **k3s runs etcd, the controller manager, the scheduler and kube-proxy inside its own binary**,
  not as pods. The chart's default ServiceMonitors for them find no targets and show up as
  down, so they are disabled on k3s.
- The prod VM has 12 GB (ADR-016). The six services have a 512 MiB memory limit each.

## Options

**A) Own manifests.** Prometheus and Grafana as plain Deployments, the Compose setup ported to
Kustomize. Light (estimated 300–500 MB) and no new tool, but no Operator, no Alertmanager and no
node or cluster metrics. Not what companies typically run.

**B) kube-prometheus-stack via Helm (chosen).** The common way to run self-hosted monitoring
on Kubernetes. The Operator manages Prometheus and Alertmanager through CRDs
(`ServiceMonitor`, `PrometheusRule`, ...), and the chart ships dashboards and alert rules for
nodes, pods and the cluster. Heavier (estimated 1–1.5 GB), adds Helm and CRDs that need care on
upgrades.

**C) Grafana Cloud free tier,** with Grafana Alloy pushing metrics out of the cluster. Nothing
to operate, but an external account, data leaving the project (against ADR-012), and a limit
of 10,000 active series with 14 days of retention, which six Spring Boot services plus
cluster metrics would likely approach.

## Decision

### Installation
- **Helm installs the chart as release `monitoring` in namespace `monitoring`**, from the OCI
  registry, **pinned by version and digest**. `helm` refuses a chart whose digest doesn't match,
  the same guarantee the sha256 check gives the cert-manager manifest (ADR-022).
- **The configuration lives in `k8s/monitoring/values.yaml`.** It only lists what differs from
  the chart's defaults, so a reader sees our choices and not 6,000 lines of defaults.
- **`deploy-prod.js` runs `helm upgrade --install --rollback-on-failure --wait`** as its last
  step, after the app is rolled out and smoke-tested, so a monitoring problem never holds back an
  app deploy. `upgrade --install` is idempotent: it installs on the first deploy and upgrades or
  changes nothing afterwards. Every run becomes a Helm revision that `helm rollback` can return
  to, and a failed one is rolled back at once (`--rollback-on-failure` is Helm 4's name for
  Helm 3's `--atomic`).
- **The deploy ends with a check against Prometheus itself:** through a port-forward, it waits
  until all six services are scrape targets in state `up`.
- **The chart is referenced as `…/kube-prometheus-stack:91.9.0@sha256:…`.** Helm fetches by the
  digest; a wrong digest fails with `not found` (tested).
- **Upgrades are manual,** like cert-manager: change version and digest, read the chart's
  upgrade notes (CRD changes), test locally, deploy.

### What runs
- **On:** Prometheus Operator, Prometheus, Grafana, kube-state-metrics, node-exporter, and
  Alertmanager **without receivers**. Alerts are visible in the UIs. Routing them to a channel
  (e-mail, Slack, ...) is a later slice.
- **Off, because of k3s:** `kubeEtcd`, `kubeControllerManager`, `kubeScheduler`, `kubeProxy`.
- **Retention:** 7 days, capped at 4 GB, on a 5 GiB volume from k3s's `local-path`
  StorageClass. Grafana gets no volume: its dashboards come from Git.
- **Requests and limits for every component,** set from measurements during the slice.

### Our services
- **One `ServiceMonitor` for all six services** in `k8s/monitoring/`, scraping
  `/actuator/prometheus` every 15 s. It selects Services by the label `metrics: actuator`, which
  the six backend Services in `k8s/base` carry (the frontend has no metrics). Their ports get the
  name `http`, because a ServiceMonitor addresses ports by name. The `job` label is the Service's
  name, as in the Compose setup, so the dashboard's queries work unchanged.
- **Prometheus selects every ServiceMonitor in the cluster**
  (`serviceMonitorSelectorNilUsesHelmValues: false`), not only those carrying the release
  label. One cluster, one Prometheus, so no filter is needed.
- **The existing dashboard is reused:** `ticketing-overview.json` becomes a ConfigMap with the
  label `grafana_dashboard: "1"`, which Grafana's sidecar loads. The chart's own dashboards
  (nodes, pods, cluster) come on top. The file moves to `k8s/monitoring/dashboards/`, because
  Kustomize doesn't read files outside its directory. Compose mounts the same directory, so both
  environments share one file.
- **`k8s/monitoring/` is applied after the Helm release,** because ServiceMonitors need the
  Operator's CRDs. `k8s/base` stays free of them, so the dev cluster (ADR-020) works without the
  monitoring stack.

### Access
- **No Ingress. Grafana, Prometheus and Alertmanager are reachable only through
  `kubectl port-forward`** over the existing SSH tunnel (runbook part D). Nothing new faces the
  internet. Companies get the same effect with SSO in front of Grafana and access only from the
  internal network or a VPN.
- **The Grafana admin password comes from a new GitHub secret, `GRAFANA_ADMIN_PASSWORD`.**
  `deploy-prod.js` creates a Kubernetes Secret from it (via stdin, like `DB_PASSWORD`) and the
  chart uses it as `existingSecret`. The repository holds no password.

## Verification (local, kind, 2026-10-05)

kind with the dev overlay, scaled down to infra, event-service, booking-service and api-gateway
(the 4 GB WSL VM can't hold all six services plus the monitoring stack):
- **Install:** revision 1 in about 5 minutes, most of it pulling images. All pods ready, the
  Prometheus volume bound (5Gi, kind's `standard` StorageClass).
- **Targets:** every active target `up`, none `down`: the three running services (with `job` =
  Service name), apiserver, kubelet, coredns, kube-state-metrics, node-exporter and the stack's
  own components. Nothing for etcd, the scheduler, the controller manager or kube-proxy.
- **Grafana:** *Ticketing Platform Overview* loaded next to 25 dashboards of the chart. The
  password from the `grafana-admin` Secret logs in; a wrong one gets 401. The dashboard's queries
  return values per service after some traffic.
- **Helm lifecycle:** upgrade (revision 2, new Grafana limits applied), `helm rollback monitoring
  1` (revision 3, old limits back), upgrade again (revision 4).
- **`deploy-prod.js`'s monitoring step,** run against kind: install/upgrade, `k8s/monitoring`, and
  the scrape check passes with the services that run. With all six required it reports the three
  missing ones by name and fails after 180 s.
- **Compose:** Grafana still loads the dashboard from its new place.

## Consequences

**Positive**
- Prod has metrics for the services, the JVMs, the pods and the node, with history and
  dashboards.
- The setup follows how companies run self-hosted monitoring on Kubernetes: Helm, a pinned
  chart, values in Git, the Operator's CRDs, ServiceMonitors next to the applications.
- Rollback of the monitoring stack is `helm rollback monitoring <revision>`, independent of the
  application.

**Negative / accepted**
- **More memory on the VM:** about 0.7 GB measured in kind (Grafana 288 MiB, its two sidecars
  about 72 MiB each, Prometheus 176 MiB with three services, the rest under 40 MiB each). Prod
  scrapes six services, so Prometheus will need more; its limit is 1 GiB.
- **Many series:** 45,898 in kind with three services, mostly from the cluster metrics. That
  confirms that option C's 10,000-series free tier wouldn't have been enough.
- **CRDs:** Helm installs them on the first install but doesn't upgrade them. A chart upgrade
  that changes CRDs needs an extra step, described in the chart's upgrade notes.
- **Dependabot doesn't update the chart,** because the version is pinned in `deploy-prod.js`,
  not in a `Chart.yaml`. Upgrades are manual, like cert-manager.
- **Locally,** the full stack plus monitoring doesn't fit into the 4 GB WSL VM. The local check
  runs the monitoring stack with a reduced set of services.
- **Single instance, no long-term storage** (no Thanos or Mimir, no high availability). Enough
  for one VM and a demo project.

---

---

# ADR-026: Metriken in Prod mit kube-prometheus-stack (Helm)

**Status:** Akzeptiert  
**Datum:** 2026-10-05  
**Autoren:** Engineering-Team

---

## Kontext

ADR-012 fügte Metriken für den lokalen Compose-Stack hinzu: Jeder Service stellt
`/actuator/prometheus` bereit (Micrometer), Prometheus scrapt es, und Grafana zeigt das
bereitgestellte Dashboard `ticketing-overview.json`. Nichts davon existiert in Kubernetes.
`k8s/base/infra` enthält nur Postgres, Redis, Kafka und Zookeeper, und das nginx des Frontends
blockiert `/api/actuator` von außen, damit Prometheus die Pods später von innerhalb des Clusters
scrapen kann (ADR-021).

Der Nutzer möchte Metriken in Prod und möchte **Helm** lernen, die Art, wie die meisten Unternehmen
Drittsoftware auf Kubernetes installieren.

Am 2026-10-05 geprüft:
- **kube-prometheus-stack 91.9.0** (veröffentlicht 2026-10-02, Prometheus Operator v0.94.1) ist das
  aktuelle Chart. Es bündelt den Prometheus Operator, Prometheus 3.15, Alertmanager 0.34,
  Grafana 13.2, kube-state-metrics 2.20 und node-exporter 1.12. Es verlangt Kubernetes >= 1.25.
- **Das Chart wird als OCI-Artefakt veröffentlicht:**
  `oci://ghcr.io/prometheus-community/charts/kube-prometheus-stack:91.9.0`, Digest
  `sha256:04047e500c803dc8d2bc284ecb43e9229bb32b5c36bd13fc89592c8a2f3cdc72`.
- **Jedes Image des Charts hat linux/arm64- und linux/amd64-Varianten** (`docker buildx imagetools
  inspect`), einschließlich `prometheus-config-reloader` und des Grafana-Sidecars. Die Prod-VM ist
  arm64 (ADR-016).
- **Helm 4.2.3** ist auf den ubuntu-26.04-Runnern von GitHub vorinstalliert, dieselbe Version, die
  Rancher Desktop lokal mitliefert.
- **k3s betreibt etcd, den Controller-Manager, den Scheduler und kube-proxy innerhalb seiner
  eigenen Binärdatei,** nicht als Pods. Die Standard-ServiceMonitors des Charts dafür finden keine
  Ziele und erscheinen als down, daher werden sie auf k3s deaktiviert.
- Die Prod-VM hat 12 GB (ADR-016). Die sechs Services haben jeweils ein Speicherlimit von 512 MiB.

## Optionen

**A) Eigene Manifeste.** Prometheus und Grafana als einfache Deployments, das Compose-Setup nach
Kustomize portiert. Leicht (geschätzt 300–500 MB) und kein neues Werkzeug, aber kein Operator, kein
Alertmanager und keine Node- oder Cluster-Metriken. Nicht das, was Unternehmen typischerweise
betreiben.

**B) kube-prometheus-stack über Helm (gewählt).** Der übliche Weg, selbst gehostetes Monitoring auf
Kubernetes zu betreiben. Der Operator verwaltet Prometheus und Alertmanager über CRDs
(`ServiceMonitor`, `PrometheusRule`, ...), und das Chart liefert Dashboards und Alarmregeln für
Nodes, Pods und den Cluster. Schwerer (geschätzt 1–1,5 GB), fügt Helm und CRDs hinzu, die bei
Upgrades Sorgfalt brauchen.

**C) Grafana-Cloud-Free-Tarif,** mit Grafana Alloy, das Metriken aus dem Cluster herausschiebt.
Nichts zu betreiben, aber ein externes Konto, Daten verlassen das Projekt (gegen ADR-012) und ein
Limit von 10.000 aktiven Serien bei 14 Tagen Aufbewahrung, dem sich sechs Spring-Boot-Services plus
Cluster-Metriken wahrscheinlich nähern würden.

## Entscheidung

### Installation
- **Helm installiert das Chart als Release `monitoring` im Namespace `monitoring`,** aus der
  OCI-Registry, **auf Version und Digest festgelegt**. `helm` lehnt ein Chart ab, dessen Digest
  nicht passt, dieselbe Garantie, die die sha256-Prüfung dem cert-manager-Manifest gibt (ADR-022).
- **Die Konfiguration liegt in `k8s/monitoring/values.yaml`.** Sie listet nur auf, was von den
  Standardwerten des Charts abweicht, sodass man unsere Entscheidungen sieht und nicht 6.000 Zeilen
  Standardwerte.
- **`deploy-prod.js` führt `helm upgrade --install --rollback-on-failure --wait` aus,** als letzten
  Schritt, nachdem die App ausgerollt und per Smoke-Test geprüft wurde, sodass ein
  Monitoring-Problem nie ein App-Deployment aufhält. `upgrade --install` ist idempotent: Es
  installiert beim ersten Deployment und aktualisiert danach oder ändert nichts. Jeder Lauf wird
  eine Helm-Revision, auf die `helm rollback` zurückgehen kann, und ein fehlgeschlagener wird sofort
  zurückgerollt (`--rollback-on-failure` ist Helm 4s Name für Helm 3s `--atomic`).
- **Das Deployment endet mit einer Prüfung gegen Prometheus selbst:** Über einen Port-Forward wartet
  es, bis alle sechs Services Scrape-Ziele im Zustand `up` sind.
- **Das Chart wird als `…/kube-prometheus-stack:91.9.0@sha256:…` referenziert.** Helm lädt per
  Digest; ein falscher Digest scheitert mit `not found` (getestet).
- **Upgrades sind manuell,** wie bei cert-manager: Version und Digest ändern, die Upgrade-Hinweise
  des Charts lesen (CRD-Änderungen), lokal testen, deployen.

### Was läuft
- **An:** Prometheus Operator, Prometheus, Grafana, kube-state-metrics, node-exporter und
  Alertmanager **ohne Receiver**. Alarme sind in den Oberflächen sichtbar. Sie an einen Kanal
  (E-Mail, Slack, ...) weiterzuleiten, ist ein späterer Slice.
- **Aus, wegen k3s:** `kubeEtcd`, `kubeControllerManager`, `kubeScheduler`, `kubeProxy`.
- **Aufbewahrung:** 7 Tage, auf 4 GB begrenzt, auf einem 5-GiB-Volume aus der StorageClass
  `local-path` von k3s. Grafana bekommt kein Volume: Seine Dashboards kommen aus Git.
- **Requests und Limits für jede Komponente,** anhand von Messungen während des Slices festgelegt.

### Unsere Services
- **Ein `ServiceMonitor` für alle sechs Services** in `k8s/monitoring/`, der
  `/actuator/prometheus` alle 15 s scrapt. Er wählt Services über das Label `metrics: actuator`,
  das die sechs Backend-Services in `k8s/base` tragen (das Frontend hat keine Metriken). Ihre Ports
  erhalten den Namen `http`, da ein ServiceMonitor Ports über den Namen anspricht. Das Label `job`
  ist der Name des Services, wie im Compose-Setup, sodass die Abfragen des Dashboards unverändert
  funktionieren.
- **Prometheus wählt jeden ServiceMonitor im Cluster aus**
  (`serviceMonitorSelectorNilUsesHelmValues: false`), nicht nur die mit dem Release-Label. Ein
  Cluster, ein Prometheus, also ist kein Filter nötig.
- **Das bestehende Dashboard wird wiederverwendet:** `ticketing-overview.json` wird zu einer
  ConfigMap mit dem Label `grafana_dashboard: "1"`, das Grafanas Sidecar lädt. Die eigenen
  Dashboards des Charts (Nodes, Pods, Cluster) kommen obendrauf. Die Datei zieht nach
  `k8s/monitoring/dashboards/` um, da Kustomize keine Dateien außerhalb seines Verzeichnisses
  liest. Compose bindet dasselbe Verzeichnis ein, beide Umgebungen teilen sich also eine Datei.
- **`k8s/monitoring/` wird nach dem Helm-Release angewendet,** da ServiceMonitors die CRDs des
  Operators brauchen. `k8s/base` bleibt frei davon, sodass der Dev-Cluster (ADR-020) ohne den
  Monitoring-Stack funktioniert.

### Zugriff
- **Kein Ingress. Grafana, Prometheus und Alertmanager sind nur über `kubectl port-forward`
  erreichbar,** über den vorhandenen SSH-Tunnel (Runbook Teil D). Nichts Neues blickt ins Internet.
  Unternehmen erreichen denselben Effekt mit SSO vor Grafana und Zugriff nur aus dem internen Netz
  oder über ein VPN.
- **Das Grafana-Admin-Passwort kommt aus einem neuen GitHub-Secret, `GRAFANA_ADMIN_PASSWORD`.**
  `deploy-prod.js` erstellt daraus ein Kubernetes-Secret (über stdin, wie `DB_PASSWORD`), und das
  Chart nutzt es als `existingSecret`. Das Repository enthält kein Passwort.

## Verifikation (lokal, kind, 2026-10-05)

kind mit dem Dev-Overlay, verkleinert auf Infra, event-service, booking-service und api-gateway
(die 4-GB-WSL-VM kann nicht alle sechs Services plus den Monitoring-Stack halten):
- **Installation:** Revision 1 in etwa 5 Minuten, großteils Image-Pulls. Alle Pods bereit, das
  Prometheus-Volume gebunden (5Gi, die StorageClass `standard` von kind).
- **Ziele:** jedes aktive Ziel `up`, keines `down`: die drei laufenden Services (mit `job` =
  Servicename), apiserver, kubelet, coredns, kube-state-metrics, node-exporter und die eigenen
  Komponenten des Stacks. Nichts für etcd, Scheduler, Controller-Manager oder kube-proxy.
- **Grafana:** *Ticketing Platform Overview* wurde neben 25 Dashboards des Charts geladen. Das
  Passwort aus dem Secret `grafana-admin` meldet an; ein falsches bekommt 401. Die Abfragen des
  Dashboards liefern nach etwas Traffic Werte pro Service.
- **Helm-Lebenszyklus:** Upgrade (Revision 2, neue Grafana-Limits angewendet), `helm rollback
  monitoring 1` (Revision 3, alte Limits zurück), erneutes Upgrade (Revision 4).
- **Der Monitoring-Schritt von `deploy-prod.js`,** gegen kind ausgeführt: Installation/Upgrade,
  `k8s/monitoring`, und die Scrape-Prüfung besteht mit den laufenden Services. Sind alle sechs
  verlangt, meldet er die drei fehlenden namentlich und scheitert nach 180 s.
- **Compose:** Grafana lädt das Dashboard weiterhin von seinem neuen Ort.

## Konsequenzen

**Positiv**
- Prod hat Metriken zu Services, JVMs, Pods und Node, mit Historie und Dashboards.
- Das Setup folgt der Art, wie Unternehmen selbst gehostetes Monitoring auf Kubernetes betreiben:
  Helm, ein festgelegtes Chart, Values in Git, die CRDs des Operators, ServiceMonitors neben den
  Anwendungen.
- Das Zurückrollen des Monitoring-Stacks ist `helm rollback monitoring <revision>`, unabhängig von
  der Anwendung.

**Negativ / akzeptiert**
- **Mehr Speicher auf der VM:** etwa 0,7 GB, in kind gemessen (Grafana 288 MiB, seine zwei Sidecars
  je etwa 72 MiB, Prometheus 176 MiB mit drei Services, der Rest unter je 40 MiB). Prod scrapt sechs
  Services, Prometheus braucht also mehr; sein Limit ist 1 GiB.
- **Viele Serien:** 45.898 in kind mit drei Services, überwiegend aus den Cluster-Metriken. Das
  bestätigt, dass der Free-Tarif von Option C mit 10.000 Serien nicht gereicht hätte.
- **CRDs:** Helm installiert sie bei der ersten Installation, aktualisiert sie aber nicht. Ein
  Chart-Upgrade, das CRDs ändert, braucht einen zusätzlichen Schritt, der in den Upgrade-Hinweisen
  des Charts beschrieben ist.
- **Dependabot aktualisiert das Chart nicht,** da die Version in `deploy-prod.js` festgelegt ist,
  nicht in einer `Chart.yaml`. Upgrades sind manuell, wie bei cert-manager.
- **Lokal** passt der vollständige Stack plus Monitoring nicht in die 4-GB-WSL-VM. Die lokale
  Prüfung betreibt den Monitoring-Stack mit einer verkleinerten Menge an Services.
- **Einzelne Instanz, kein Langzeitspeicher** (kein Thanos oder Mimir, keine Hochverfügbarkeit).
  Genug für eine VM und ein Demoprojekt.
