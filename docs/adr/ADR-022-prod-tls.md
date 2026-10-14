# ADR-022: TLS for Prod

**Status:** Accepted
**Date:** 2026-10-04
**Authors:** Engineering Team

---

## Context

Slice (e3) put prod on the Oracle A1 VM, reachable as plain HTTP on its public IP (ADR-021). The
browser marks the page "Not secure", and every request, including the seat-status WebSocket,
crosses the internet in clear text. This slice adds HTTPS between the browser and the VM.

**Scope:** TLS ends at the k3s Ingress controller (Traefik). Traffic inside the cluster stays
plain HTTP: it never leaves the single VM. The deploy path is already encrypted (SSH tunnel, the
k3s API's own TLS, GHCR over HTTPS).

Constraints carried over: it must stay **cost-free**, and it must be testable on the VM. Dev
(kind, Rancher Desktop, compose) stays on HTTP.

Facts checked on 2026-10-04:

- **Let's Encrypt issues certificates for IP addresses** since 2026-01-15. They must use the
  `shortlived` profile and are valid for **160 hours, just over six days**
  ([Let's Encrypt](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)).
  Only HTTP-01 and TLS-ALPN-01 can validate an IP, not DNS-01.
- **sslip.io and nip.io are one service now** (sslip.io redirects to nip.io). A name such as
  `92-5-64-140.sslip.io` resolves to that IP, checked with `nslookup`. Let's Encrypt has raised
  the domain's rate limit to 250,000 certificates, and the service has run for over ten years
  ([nip.io](https://nip.io/)). The rate limit is still shared by all its users, because neither
  domain is on the Public Suffix List (checked in the list itself). If it is hit, the other
  domain or an IP certificate is the fallback.
- **DuckDNS is on the Public Suffix List**, so every subdomain has its own rate limit. It needs
  an account and a token, and the IP has to be kept up to date.
- **k3s `v1.36.4+k3s1` ships Traefik 3.7.8** (checked in k3s's `manifests/traefik.yaml`).
  Traefik's built-in ACME client supports a `profile` setting. Renewals ignored the profile
  until [traefik#12467](https://github.com/traefik/traefik/pull/12467), merged into v3.6 on
  2026-01-05, so 3.7.8 includes the fix. IPv4 IP certificates in Traefik were reported working
  by users only in March 2026
  ([Traefik forum](https://community.traefik.io/t/almost-able-to-use-letsencrypts-new-shortlived-ip-acme-tls-certs/27913)).
- **Traefik's ACME client works only with one Traefik replica.** It stores account and
  certificates in a file (`acme.json`) that has to sit on a persistent volume
  ([Traefik docs](https://doc.traefik.io/traefik/reference/install-configuration/tls/certificate-resolvers/acme/)).
  k3s runs one replica.
- **cert-manager** is at v1.21.2 (2026-09-11), with arm64 images. Its ACME issuer has a
  `profile` field since v1.18 ([cert-manager docs](https://cert-manager.io/docs/configuration/acme/)).
  It stores certificates as Kubernetes Secrets and works with any Ingress controller.
- **The frontend needs no change.** It builds the WebSocket URL from the page's protocol, so
  HTTPS gives `wss://` automatically.

## Options considered

### Name on the certificate

| Option | Pros | Cons |
|---|---|---|
| **A. IP certificate** (`https://92.5.64.140`) | No third party for the name. | Six-day certificates: a broken renewal takes the site down within days. Ingress `host` rules can't hold an IP, so it needs Traefik-specific config. Newest and least proven path. |
| **B. sslip.io name** (`https://92-5-64-140.sslip.io`) | No account, no cost. Normal 90-day certificate, so renewal problems leave about 30 days to react. A plain `host` rule in the Ingress. | Depends on a free third-party DNS service. Shared rate limit. The name looks technical. |
| **C. DuckDNS** (`https://<name>.duckdns.org`) | A readable name. Own rate limit. | Needs an account and a token as an extra secret. Another service to keep running. |
| **D. Own domain** | The nicest name, full control. | Costs money every year. **Ruled out by the cost constraint.** |

All options tie the name to the VM's IP: a new IP means a new certificate (A, B) or a DNS update
(C).

### Who gets the certificate

| Option | Pros | Cons |
|---|---|---|
| **1. Traefik's built-in ACME client** | Nothing new to install; configured through a k3s `HelmChartConfig`. | Traefik-specific: the prod Ingress gets Traefik annotations. `acme.json` needs a persistent volume, and permission errors on it are common. Certificates aren't visible as Kubernetes objects. |
| **2. cert-manager** | The standard Kubernetes way. Certificates are Secrets, their state is visible (`kubectl get certificate`). Controller-neutral, standard `tls:` section in the Ingress. | Three more pods (controller, webhook, cainjector) and CRDs to install and update. |

The challenge type is **HTTP-01** in every case: port 80 is already open, and DNS-01 doesn't work
for sslip.io or IP certificates.

## Decision

Chosen with the user: **B, an sslip.io name, and 2, cert-manager.**

- **sslip.io** gives `https://<IP with dashes>.sslip.io` with Let's Encrypt's normal 90-day
  certificate. It needs no account and no secret, and of the free options it leaves the most time
  to fix a broken renewal. The IP certificate (A) is the fallback if sslip.io goes away, and
  nip.io the fallback if its shared rate limit is hit.
- **cert-manager v1.21.2** keeps the Ingress controller-neutral (ADR-018) and is the tool an
  enterprise platform team would run (see below). Its three pods fit the VM, which used 3.4 GB of
  11.6 GB before.
- **HTTP-01** through Traefik. Port 80 stays open for it.

How it is built:
- **`deploy-prod.js` installs cert-manager** on every deploy, from the official static manifest
  with a pinned version **and its sha256**: a manifest that doesn't match is never applied. The
  deploy waits for cert-manager's three Deployments, then applies the overlay, retrying a few
  times while the webhook comes up. The alternative was a one-time install in `setup-k3s.sh`; this
  way an upgrade is a reviewed commit, and a new VM needs no extra step.
- **`k8s/overlays/prod`** adds three `ClusterIssuer`s (`letsencrypt-staging`, `letsencrypt-prod`,
  `selfsigned` for local clusters), a host rule and a `tls:` section on the Ingress, and the
  `cert-manager.io/cluster-issuer` annotation. The ACME account has no email address: Let's
  Encrypt stopped sending expiry mails on 2025-06-04
  ([Let's Encrypt](https://letsencrypt.org/2025/06/26/expiration-notification-service-has-ended)).
- **Host and issuer are set at deploy time**, like the image tag: the host comes from
  `--base-url` (the `PROD_BASE_URL` variable), the issuer from `--tls-issuer`, a choice input of
  the deploy workflow. The VM's IP stays out of the repository. `deploy-prod.js` refuses
  `http://` and bare IPs.
- **Staging first.** After any change to the TLS setup, the deploy runs with
  `letsencrypt-staging`, then with `letsencrypt-prod`.
- **The deploy waits for the right certificate.** It requires `Ready=True` and the Secret's
  `cert-manager.io/issuer-name` annotation to name the chosen issuer. After an issuer switch,
  cert-manager marks the old certificate `Ready=False` (`IncorrectIssuer`), but only once its
  controller has reacted, so `Ready` alone could pass too early. Then the deploy prints the served
  certificate's issuer and expiry. With `letsencrypt-prod` it fails if Node doesn't trust the
  certificate.
- **A failed issuance stops the deploy at once.** cert-manager retries a failed request only after
  a backoff of about an hour, so waiting is pointless. After a 30-second grace period, the deploy
  stops on `Issuing=False/Failed` and prints the reason from the Certificate's conditions and the
  ACME order and challenges, e.g. Let's Encrypt's error. An issuer switch starts a new attempt
  right away despite the backoff (tested on Rancher Desktop).
- **HTTP → HTTPS** with a Traefik `Middleware` on the app's Ingress only. That is
  Traefik-specific, but only in the prod overlay, where k3s ships Traefik. cert-manager's solver
  Ingress has a longer path rule, which Traefik prefers, so challenges are answered over plain
  HTTP. Let's Encrypt would also follow a redirect to HTTPS without validating that certificate
  ([community answer](https://community.letsencrypt.org/t/http-domain-verification-with-redirect/130449)),
  so either way works.
- **The smoke check runs over HTTPS** and checks that `http://<host>/api/events` returns a
  permanent redirect to the HTTPS URL. With staging or self-signed certificates, certificate
  verification is switched off for the smoke check only.

## What an enterprise setup would add

This setup is a cost-free, single-VM version of what larger companies run. What they typically
add, and why it is deferred here:

| Enterprise practice | What it is | Why deferred |
|---|---|---|
| **Own domain, DNS-01** | A registered domain with DNS behind an API (Route 53, Azure DNS, Cloudflare). DNS-01 proves control through a TXT record and allows wildcard certificates and certificates for internal services. | A domain costs money every year. Switching later only changes the Ingress host and the issuer's solver. |
| **TLS at the edge** | A CDN, WAF or cloud load balancer (Cloudflare, AWS ALB/CloudFront, Azure Front Door) ends TLS, often with platform-managed certificates such as AWS Certificate Manager. | Not free at this scale, and one VM has no load balancer. Traefik on the VM plays this role. |
| **mTLS between services** | A service mesh (Istio, Linkerd) gives every pod a short-lived identity certificate, so services authenticate each other ("zero trust"). Databases and Kafka also use TLS. | Traffic never leaves the single VM, and a mesh adds several components. It is the largest gap compared with an enterprise setup. |
| **Private PKI** | An internal CA (HashiCorp Vault PKI, AWS Private CA, Microsoft AD CS) for certificates that only need to be trusted inside the company. | Nothing internal needs certificates yet. cert-manager has issuers for these CAs. |
| **Certificate lifecycle management** | A central inventory, policy enforcement and expiry alerts (Venafi/CyberArk, Keyfactor). Keys sit in HSMs or a cloud KMS. | One certificate. The deploy prints its expiry, and a scheduled expiry check is a possible follow-up. |
| **Short lifetimes, full automation** | The CA/Browser Forum's ballot SC-081v3 cuts the maximum public certificate lifetime to 200 days from 2026-03-15, 100 days from 2027-03-15 and **47 days from 2029-03-15** ([summary](https://www.appviewx.com/blogs/its-official-ca-b-forum-votes-yes-to-47-day-tls-certificates/)). Manual renewal stops being viable. | Already covered: cert-manager renews on its own. |

## Verification

- **Locally on Rancher Desktop** with the `selfsigned` issuer: the cert-manager install, the
  Ingress TLS wiring, the redirect, a repeat deploy without reissue, and a failing
  `letsencrypt-staging` run that stops early and prints Let's Encrypt's reason.
- **On the VM on 2026-10-04,** with release `sha-8b82a63`:
  - Staging, [run 37212540349](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37212540349),
    then prod, [run 37212908972](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37212908972).
    Both were green.
  - HTTP-01 passed next to the redirect on the first attempt.
  - The prod certificate is issued by Let's Encrypt `YR2`, is valid until 2027-01-02, and is
    trusted by Node and by curl without `-k`.
  - The WebSocket works over `wss://`.
  - The bare IP now returns 404.
- Details are in `docs/plan.md`.

## Consequences

**Positive**
- The browser shows a trusted certificate, and REST and the seat-status WebSocket (`wss://`) are
  encrypted between the browser and the VM.
- No account, no secret and no cost was added. The certificate renews on its own.
- The Ingress stays standard Kubernetes. Only the redirect is Traefik-specific, and only in prod.
- cert-manager's version and manifest are pinned and checked, so an upgrade is a reviewed commit.

**Negative / accepted**
- **sslip.io is a third-party dependency without an SLA.** If it stops resolving, the site is
  unreachable by name. The fallbacks are nip.io and an IP certificate.
- **The name contains the IP.** A new IP means a new name and a new certificate (runbook Notes).
- **`http://<IP>` no longer serves the app**, because the Ingress now only answers for its host.
- **Every deploy downloads the cert-manager manifest from GitHub.** The deploy already depends on
  GitHub (Actions, GHCR), and the checksum guards the content.
- **Most of the TLS path can only be tested on the VM.** Let's Encrypt can't reach a local
  cluster. Locally, the `selfsigned` issuer checks the cert-manager install, the Ingress wiring,
  the redirect and the deploy script.
- **Inside the cluster, traffic stays plain HTTP** (see the enterprise table).

---

---

# ADR-022: TLS für Prod

**Status:** Akzeptiert  
**Datum:** 2026-10-04  
**Autoren:** Engineering-Team

---

## Kontext

Slice (e3) brachte Prod auf die Oracle-A1-VM, erreichbar als einfaches HTTP auf ihrer öffentlichen
IP (ADR-021). Der Browser markiert die Seite als „Nicht sicher“, und jede Anfrage, einschließlich
des Sitzstatus-WebSockets, durchquert das Internet im Klartext. Dieser Slice ergänzt HTTPS zwischen
Browser und VM.

**Umfang:** TLS endet am Ingress-Controller von k3s (Traefik). Der Traffic innerhalb des Clusters
bleibt einfaches HTTP: Er verlässt die einzelne VM nie. Der Deploy-Pfad ist bereits verschlüsselt
(SSH-Tunnel, das eigene TLS der k3s-API, GHCR über HTTPS).

Übernommene Randbedingungen: Es muss **kostenfrei** bleiben und auf der VM testbar sein. Dev (kind,
Rancher Desktop, Compose) bleibt bei HTTP.

Am 2026-10-04 geprüfte Fakten:

- **Let's Encrypt stellt seit 2026-01-15 Zertifikate für IP-Adressen aus.** Sie müssen das Profil
  `shortlived` nutzen und sind **160 Stunden gültig, knapp über sechs Tage**
  ([Let's Encrypt](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)). Nur
  HTTP-01 und TLS-ALPN-01 können eine IP validieren, nicht DNS-01.
- **sslip.io und nip.io sind jetzt ein Dienst** (sslip.io leitet auf nip.io weiter). Ein Name wie
  `92-5-64-140.sslip.io` löst zu dieser IP auf, geprüft mit `nslookup`. Let's Encrypt hat das
  Ratenlimit der Domain auf 250.000 Zertifikate erhöht, und der Dienst läuft seit über zehn Jahren
  ([nip.io](https://nip.io/)). Das Ratenlimit teilen sich weiterhin alle Nutzer, da keine der
  beiden Domains auf der Public Suffix List steht (in der Liste selbst geprüft). Wird es erreicht,
  ist die andere Domain oder ein IP-Zertifikat der Fallback.
- **DuckDNS steht auf der Public Suffix List**, sodass jede Subdomain ihr eigenes Ratenlimit hat.
  Es braucht ein Konto und einen Token, und die IP muss aktuell gehalten werden.
- **k3s `v1.36.4+k3s1` liefert Traefik 3.7.8 mit** (geprüft in `manifests/traefik.yaml` von k3s).
  Traefiks eingebauter ACME-Client unterstützt eine `profile`-Einstellung. Erneuerungen ignorierten
  das Profil bis [traefik#12467](https://github.com/traefik/traefik/pull/12467), am 2026-01-05 in
  v3.6 gemergt, daher enthält 3.7.8 den Fix. IPv4-IP-Zertifikate in Traefik wurden von Nutzern erst
  im März 2026 als funktionierend gemeldet
  ([Traefik-Forum](https://community.traefik.io/t/almost-able-to-use-letsencrypts-new-shortlived-ip-acme-tls-certs/27913)).
- **Traefiks ACME-Client funktioniert nur mit einem Traefik-Replikat.** Er speichert Konto und
  Zertifikate in einer Datei (`acme.json`), die auf einem persistenten Volume liegen muss
  ([Traefik-Doku](https://doc.traefik.io/traefik/reference/install-configuration/tls/certificate-resolvers/acme/)).
  k3s betreibt ein Replikat.
- **cert-manager** ist bei v1.21.2 (2026-09-11), mit arm64-Images. Sein ACME-Issuer hat seit v1.18
  ein `profile`-Feld ([cert-manager-Doku](https://cert-manager.io/docs/configuration/acme/)). Er
  speichert Zertifikate als Kubernetes-Secrets und funktioniert mit jedem Ingress-Controller.
- **Das Frontend braucht keine Änderung.** Es baut die WebSocket-URL aus dem Protokoll der Seite,
  HTTPS ergibt also automatisch `wss://`.

## Betrachtete Optionen

### Name auf dem Zertifikat

| Option | Vorteile | Nachteile |
|---|---|---|
| **A. IP-Zertifikat** (`https://92.5.64.140`) | Kein Dritter für den Namen. | Sechs-Tage-Zertifikate: Eine fehlgeschlagene Erneuerung legt die Seite innerhalb von Tagen lahm. Ingress-`host`-Regeln können keine IP aufnehmen, daher braucht es Traefik-spezifische Konfiguration. Der neueste und am wenigsten erprobte Weg. |
| **B. sslip.io-Name** (`https://92-5-64-140.sslip.io`) | Kein Konto, keine Kosten. Normales 90-Tage-Zertifikat, bei Erneuerungsproblemen bleiben also etwa 30 Tage zum Reagieren. Eine einfache `host`-Regel im Ingress. | Hängt von einem kostenlosen DNS-Dienst eines Dritten ab. Geteiltes Ratenlimit. Der Name wirkt technisch. |
| **C. DuckDNS** (`https://<name>.duckdns.org`) | Ein lesbarer Name. Eigenes Ratenlimit. | Braucht ein Konto und einen Token als zusätzliches Secret. Ein weiterer Dienst, der laufen muss. |
| **D. Eigene Domain** | Der schönste Name, volle Kontrolle. | Kostet jedes Jahr Geld. **Durch die Kostenvorgabe ausgeschlossen.** |

Alle Optionen binden den Namen an die IP der VM: Eine neue IP bedeutet ein neues Zertifikat (A, B)
oder ein DNS-Update (C).

### Wer das Zertifikat bekommt

| Option | Vorteile | Nachteile |
|---|---|---|
| **1. Traefiks eingebauter ACME-Client** | Nichts Neues zu installieren; konfiguriert über eine k3s-`HelmChartConfig`. | Traefik-spezifisch: Der Prod-Ingress bekommt Traefik-Annotationen. `acme.json` braucht ein persistentes Volume, und Berechtigungsfehler dabei sind häufig. Zertifikate sind nicht als Kubernetes-Objekte sichtbar. |
| **2. cert-manager** | Der Standardweg in Kubernetes. Zertifikate sind Secrets, ihr Zustand ist sichtbar (`kubectl get certificate`). Controller-neutral, Standard-`tls:`-Abschnitt im Ingress. | Drei weitere Pods (Controller, Webhook, Cainjector) und CRDs, die installiert und aktualisiert werden müssen. |

Der Challenge-Typ ist in jedem Fall **HTTP-01**: Port 80 ist bereits offen, und DNS-01 funktioniert
weder für sslip.io noch für IP-Zertifikate.

## Entscheidung

Mit dem Nutzer gewählt: **B, ein sslip.io-Name, und 2, cert-manager.**

- **sslip.io** liefert `https://<IP mit Bindestrichen>.sslip.io` mit dem normalen 90-Tage-Zertifikat
  von Let's Encrypt. Es braucht kein Konto und kein Secret und lässt unter den kostenlosen Optionen
  die meiste Zeit, um eine fehlgeschlagene Erneuerung zu beheben. Das IP-Zertifikat (A) ist der
  Fallback, falls sslip.io wegfällt, und nip.io der Fallback, falls sein geteiltes Ratenlimit
  erreicht wird.
- **cert-manager v1.21.2** hält den Ingress Controller-neutral (ADR-018) und ist das Werkzeug, das
  ein Enterprise-Plattform-Team betreiben würde (siehe unten). Seine drei Pods passen auf die VM,
  die zuvor 3,4 von 11,6 GB belegte.
- **HTTP-01** über Traefik. Port 80 bleibt dafür offen.

Wie es gebaut ist:
- **`deploy-prod.js` installiert cert-manager** bei jedem Deployment aus dem offiziellen statischen
  Manifest mit festgelegter Version **und dessen sha256**: Ein Manifest, das nicht passt, wird nie
  angewendet. Das Deployment wartet auf die drei Deployments von cert-manager und wendet dann das
  Overlay an, mit einigen Wiederholungen, während der Webhook hochkommt. Die Alternative war eine
  einmalige Installation in `setup-k3s.sh`; so ist ein Upgrade ein reviewter Commit, und eine neue
  VM braucht keinen zusätzlichen Schritt.
- **`k8s/overlays/prod`** fügt drei `ClusterIssuer` hinzu (`letsencrypt-staging`,
  `letsencrypt-prod`, `selfsigned` für lokale Cluster), eine Host-Regel und einen `tls:`-Abschnitt
  am Ingress sowie die Annotation `cert-manager.io/cluster-issuer`. Das ACME-Konto hat keine
  E-Mail-Adresse: Let's Encrypt hat den Versand von Ablaufmails am 2025-06-04 eingestellt
  ([Let's Encrypt](https://letsencrypt.org/2025/06/26/expiration-notification-service-has-ended)).
- **Host und Issuer werden zur Deploy-Zeit gesetzt**, wie das Image-Tag: Der Host kommt aus
  `--base-url` (die Variable `PROD_BASE_URL`), der Issuer aus `--tls-issuer`, einer
  Auswahleingabe des Deploy-Workflows. Die IP der VM bleibt aus dem Repository heraus.
  `deploy-prod.js` lehnt `http://` und nackte IPs ab.
- **Zuerst Staging.** Nach jeder Änderung am TLS-Setup läuft das Deployment mit
  `letsencrypt-staging`, dann mit `letsencrypt-prod`.
- **Das Deployment wartet auf das richtige Zertifikat.** Es verlangt `Ready=True` und dass die
  Annotation `cert-manager.io/issuer-name` des Secrets den gewählten Issuer nennt. Nach einem
  Issuer-Wechsel markiert cert-manager das alte Zertifikat als `Ready=False` (`IncorrectIssuer`),
  aber erst, wenn sein Controller reagiert hat, daher könnte `Ready` allein zu früh durchgehen.
  Dann gibt das Deployment den Issuer und das Ablaufdatum des ausgelieferten Zertifikats aus. Mit
  `letsencrypt-prod` scheitert es, wenn Node dem Zertifikat nicht vertraut.
- **Eine fehlgeschlagene Ausstellung stoppt das Deployment sofort.** cert-manager wiederholt eine
  fehlgeschlagene Anfrage erst nach einem Backoff von etwa einer Stunde, Warten ist also sinnlos.
  Nach einer Karenzzeit von 30 Sekunden stoppt das Deployment bei `Issuing=False/Failed` und gibt
  den Grund aus den Conditions des Certificate sowie der ACME-Order und -Challenges aus, z. B. den
  Fehler von Let's Encrypt. Ein Issuer-Wechsel startet trotz Backoff sofort einen neuen Versuch
  (auf Rancher Desktop getestet).
- **HTTP → HTTPS** mit einer Traefik-`Middleware` nur am Ingress der App. Das ist
  Traefik-spezifisch, aber nur im Prod-Overlay, wo k3s Traefik mitliefert. Der Solver-Ingress von
  cert-manager hat eine längere Pfadregel, die Traefik bevorzugt, sodass Challenges über einfaches
  HTTP beantwortet werden. Let's Encrypt würde zudem einer Weiterleitung auf HTTPS folgen, ohne
  dieses Zertifikat zu validieren
  ([Community-Antwort](https://community.letsencrypt.org/t/http-domain-verification-with-redirect/130449)),
  beides funktioniert also.
- **Der Smoke-Check läuft über HTTPS** und prüft, dass `http://<host>/api/events` eine permanente
  Weiterleitung auf die HTTPS-URL liefert. Bei Staging- oder selbstsignierten Zertifikaten wird die
  Zertifikatsprüfung nur für den Smoke-Check abgeschaltet.

## Was ein Enterprise-Setup ergänzen würde

Dieses Setup ist eine kostenfreie Ein-VM-Version dessen, was größere Unternehmen betreiben. Was sie
typischerweise ergänzen, und warum es hier zurückgestellt ist:

| Enterprise-Praxis | Was es ist | Warum zurückgestellt |
|---|---|---|
| **Eigene Domain, DNS-01** | Eine registrierte Domain mit DNS hinter einer API (Route 53, Azure DNS, Cloudflare). DNS-01 beweist die Kontrolle über einen TXT-Eintrag und erlaubt Wildcard-Zertifikate und Zertifikate für interne Services. | Eine Domain kostet jedes Jahr Geld. Ein späterer Wechsel ändert nur den Ingress-Host und den Solver des Issuers. |
| **TLS am Rand** | Ein CDN, WAF oder Cloud-Load-Balancer (Cloudflare, AWS ALB/CloudFront, Azure Front Door) beendet TLS, oft mit plattformverwalteten Zertifikaten wie AWS Certificate Manager. | In diesem Maßstab nicht kostenlos, und eine VM hat keinen Load-Balancer. Traefik auf der VM übernimmt diese Rolle. |
| **mTLS zwischen Services** | Ein Service Mesh (Istio, Linkerd) gibt jedem Pod ein kurzlebiges Identitätszertifikat, sodass sich Services gegenseitig authentifizieren („Zero Trust“). Datenbanken und Kafka nutzen ebenfalls TLS. | Der Traffic verlässt die einzelne VM nie, und ein Mesh fügt mehrere Komponenten hinzu. Es ist die größte Lücke im Vergleich zu einem Enterprise-Setup. |
| **Private PKI** | Eine interne CA (HashiCorp Vault PKI, AWS Private CA, Microsoft AD CS) für Zertifikate, denen nur innerhalb des Unternehmens vertraut werden muss. | Noch braucht nichts Internes Zertifikate. cert-manager hat Issuer für diese CAs. |
| **Verwaltung des Zertifikats-Lebenszyklus** | Ein zentrales Inventar, Richtlinienumsetzung und Ablaufwarnungen (Venafi/CyberArk, Keyfactor). Schlüssel liegen in HSMs oder einem Cloud-KMS. | Ein Zertifikat. Das Deployment gibt sein Ablaufdatum aus, und eine geplante Ablaufprüfung ist ein möglicher Folgeschritt. |
| **Kurze Laufzeiten, volle Automatisierung** | Die Abstimmung SC-081v3 des CA/Browser Forums senkt die maximale Laufzeit öffentlicher Zertifikate auf 200 Tage ab 2026-03-15, 100 Tage ab 2027-03-15 und **47 Tage ab 2029-03-15** ([Zusammenfassung](https://www.appviewx.com/blogs/its-official-ca-b-forum-votes-yes-to-47-day-tls-certificates/)). Manuelle Erneuerung ist nicht mehr praktikabel. | Bereits abgedeckt: cert-manager erneuert selbstständig. |

## Verifikation

- **Lokal auf Rancher Desktop** mit dem Issuer `selfsigned`: die cert-manager-Installation, die
  TLS-Verdrahtung des Ingress, die Weiterleitung, ein wiederholtes Deployment ohne Neuausstellung
  und ein fehlschlagender `letsencrypt-staging`-Lauf, der früh stoppt und den Grund von Let's
  Encrypt ausgibt.
- **Auf der VM am 2026-10-04,** mit Release `sha-8b82a63`:
  - Staging, [Run 37212540349](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37212540349),
    dann Prod, [Run 37212908972](https://github.com/comicNerd23/eventTicketingSystem/actions/runs/37212908972).
    Beide waren grün.
  - HTTP-01 bestand neben der Weiterleitung beim ersten Versuch.
  - Das Prod-Zertifikat wurde von Let's Encrypt `YR2` ausgestellt, ist bis 2027-01-02 gültig und
    wird von Node und von curl ohne `-k` als vertrauenswürdig akzeptiert.
  - Der WebSocket funktioniert über `wss://`.
  - Die nackte IP liefert nun 404.
- Details stehen in `docs/plan.md`.

## Konsequenzen

**Positiv**
- Der Browser zeigt ein vertrauenswürdiges Zertifikat, und REST sowie der Sitzstatus-WebSocket
  (`wss://`) sind zwischen Browser und VM verschlüsselt.
- Es wurden kein Konto, kein Secret und keine Kosten hinzugefügt. Das Zertifikat erneuert sich
  selbstständig.
- Der Ingress bleibt Standard-Kubernetes. Nur die Weiterleitung ist Traefik-spezifisch, und nur in
  Prod.
- Version und Manifest von cert-manager sind festgelegt und geprüft, ein Upgrade ist also ein
  reviewter Commit.

**Negativ / akzeptiert**
- **sslip.io ist eine Abhängigkeit von einem Dritten ohne SLA.** Löst es nicht mehr auf, ist die
  Seite per Namen nicht erreichbar. Die Fallbacks sind nip.io und ein IP-Zertifikat.
- **Der Name enthält die IP.** Eine neue IP bedeutet einen neuen Namen und ein neues Zertifikat
  (Notizen im Runbook).
- **`http://<IP>` liefert die App nicht mehr aus**, da der Ingress nun nur noch für seinen Host
  antwortet.
- **Jedes Deployment lädt das cert-manager-Manifest von GitHub herunter.** Das Deployment hängt
  bereits von GitHub ab (Actions, GHCR), und die Prüfsumme schützt den Inhalt.
- **Der Großteil des TLS-Pfads lässt sich nur auf der VM testen.** Let's Encrypt kann einen lokalen
  Cluster nicht erreichen. Lokal prüft der Issuer `selfsigned` die cert-manager-Installation, die
  Ingress-Verdrahtung, die Weiterleitung und das Deploy-Skript.
- **Innerhalb des Clusters bleibt der Traffic einfaches HTTP** (siehe die Enterprise-Tabelle).
