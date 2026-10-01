# ADR-019: Containerized Frontend with a Same-Origin /api Proxy

**Status:** Accepted
**Date:** 2026-10-01
**Authors:** Engineering Team

---

## Context

ADR-016 slice (c) asks for a containerized frontend, "with API and WebSocket URLs set per
environment". Until now the Angular app only ran through `ng serve` on `localhost:4200`, with
`http://localhost:8080` and `ws://localhost:8080` hard-coded in both environment files. The
gateway allowed that origin through CORS (ADR-017 made the origin configurable).

Three findings shaped this decision:

- **Path collision.** The SPA's client-side routes `/events/:id` and `/bookings/:id` are also
  api-gateway route prefixes (`/events/**`, `/bookings/**`). If the app and the API share one
  origin without a prefix, reloading `/events/123` returns the gateway's JSON instead of the
  app.
- **A plain Ingress can't strip a prefix.** With an Ingress `/api → api-gateway`, the gateway
  would receive `/api/events`. Stripping the prefix needs controller-specific annotations or
  Traefik `Middleware` CRDs. ADR-018 deliberately kept the Ingress controller-neutral.
- **The prod VM is arm64** (ADR-016). Every runtime image must exist for arm64.

## Options considered

### How the browser reaches the API
- **A. nginx in the frontend image proxies `/api/**` to api-gateway (chosen).**
  - `proxy_pass http://api-gateway:8080/` with a trailing slash strips the prefix, so the
    gateway and its routes stay unchanged.
  - The Ingress keeps a single rule (`/ → frontend`).
  - The app uses relative URLs, so one image serves every environment and CORS is no longer
    needed.
  - Cost: one extra hop, and nginx must pass WebSocket upgrades through.
- **B. Ingress split (`/api → api-gateway`, `/ → frontend`) and a gateway that accepts `/api`.**
  - Without controller-specific stripping, every gateway route moves to `/api/**` plus
    `StripPrefix`. That changes the gateway, its tests, `demo.js` and the compose ports.
- **C. Separate hosts (`app.…`, `api.…`) with a runtime `config.json`.**
  - Needs a CORS origin and DNS names per environment, and a config fetch before the app
    starts. That is the most moving parts for the same result.

### Web server
- **nginx-unprivileged (chosen).** The official NGINX/F5 image runs as a non-root user (UID 101)
  on port 8080 and is built for amd64 and arm64
  ([Docker Hub](https://hub.docker.com/r/nginxinc/nginx-unprivileged),
  [GitHub](https://github.com/nginx/docker-nginx-unprivileged)). Its entrypoint renders
  `/etc/nginx/templates/*.template` with `envsubst` at container start.
- **Official `nginx`.** Same configuration, but it runs as root on port 80.
- **Caddy.** A shorter config, but another tool next to the rest of the stack.

## Decision

- **`frontend/Dockerfile`, multi-stage:**
  - `node:24-alpine` runs `npm ci` and `ng build`, with the production configuration as the
    default.
  - `nginxinc/nginx-unprivileged:1.30-alpine` serves `dist/frontend/browser`.
  - Both images are multi-arch, including arm64.
- **`frontend/nginx/default.conf.template`:**
  - `location /api/` proxies to `${API_GATEWAY_URL}/` with HTTP/1.1 and `Upgrade`/`Connection`
    headers, and a 1 h read timeout for open seat-map WebSockets.
  - `NGINX_ENVSUBST_FILTER=^API_GATEWAY_URL$` limits substitution to that one variable, so
    nginx's own `$host` and `$http_upgrade` stay intact.
  - The SPA fallback is `try_files $uri $uri/ /index.html`. `index.html` is sent with
    `Cache-Control: no-cache`, hashed assets with a one-year immutable cache.
  - `/healthz` serves the probes and doesn't depend on the gateway.
- **The Angular environment** uses `apiBaseUrl: '/api'`. `wsBaseUrl` is derived from
  `location`: `ws:` or `wss:` matches the page, followed by `//host/api`.
- **`ng serve`** gets `proxy.conf.json` (`/api → localhost:8080`, prefix stripped, `ws: true`).
  Local development therefore uses the same relative URLs as the container.
- **Kubernetes:**
  - `k8s/base/services/frontend.yaml` adds a Deployment and a Service on port 8080, with
    `runAsNonRoot`, `allowPrivilegeEscalation: false` and `enableServiceLinks: false`.
  - Resources are 16 Mi requested and 64 Mi limit.
  - The Ingress `/` now points at `frontend`. API calls go through `/api`.
- **docker-compose** gets a `frontend` service on host port 8000, the same port as the kind
  Ingress. The app has one URL in both setups, which never run together on this machine.
- **CI:** `node ci.js frontend --docker` also builds the image. The workflow's frontend job
  uses that flag, as the service jobs already do.

ADR-016's "API and WebSocket URLs set per environment" is replaced by this. Only the upstream,
`API_GATEWAY_URL`, varies per environment, and it is the same in-cluster Service name in dev
and prod.

## Consequences

**Positive**
- One image for dev and prod. Nothing about the API location is baked into the build.
- The browser makes only same-origin requests, so no CORS preflights.
- The gateway, its routes and `demo.js` are unchanged.
- Deep links and reloads on client-side routes work.

**Negative / accepted**
- **One extra hop** (Ingress → nginx → gateway). On kind a hold's WebSocket push arrived after
  about 120–160 ms through nginx, against about 100 ms directly at the gateway.
- **API URLs moved.** Through the Ingress the API is now `localhost:8000/api/...`, not
  `localhost:8000/...`. Direct calls to the gateway (`:8080`, used by `demo.js`) are unchanged.
- **The gateway's CORS configuration is now unused by the app.** It stays for direct API
  clients and could be narrowed later.
- **nginx resolves `api-gateway` once at startup.** In Kubernetes the Service name is stable,
  so this is fine. A gateway Service that doesn't exist yet keeps the frontend pod from
  starting until it does.
- **Same-origin works only behind this nginx or the `ng serve` proxy.** Opening the built files
  any other way has no `/api`.
