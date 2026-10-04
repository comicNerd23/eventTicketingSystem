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
