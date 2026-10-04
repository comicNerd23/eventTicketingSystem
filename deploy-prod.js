#!/usr/bin/env node
// Deploys k8s/overlays/prod with a given release tag (ADR-016 slice (e), ADR-021). The same
// script runs locally and in the GitHub Actions deploy job.
//
//   node deploy-prod.js --context=<kube-context> --tag=<sha-1234567|v1.2.3> --base-url=https://<host>
//                       [--tls-issuer=letsencrypt-prod|letsencrypt-staging|selfsigned]
//
// --base-url must be HTTPS on a host name (ADR-022), e.g. https://203-0-113-10.sslip.io: the
// certificate is issued for that name. --tls-issuer defaults to letsencrypt-prod; use
// letsencrypt-staging after any change to the TLS setup, and selfsigned on a local cluster.
//
// Required environment variables (in the deploy job: GitHub Actions secrets):
//   DB_PASSWORD                    password for every service's Postgres user
//   GATEWAY_CORS_ALLOWED_ORIGINS   the public origin of the app, e.g. https://tickets.example.org
// Optional:
//   DB_USERNAME                    defaults to "ticketing"
//
// Steps: check that the tag exists in GHCR for all seven images, install cert-manager (pinned
// version, checksum verified), write the db-credentials Secret and gateway-config ConfigMap,
// render the overlay with tag, host and issuer, apply, wait for every rollout and for the
// certificate, then smoke-test through the Ingress: HTTPS, the HTTP -> HTTPS redirect, and that
// the gateway's actuator is not reachable from outside.
//
// The kube-context must be named explicitly; the current context is never used, so a dev
// session can't deploy to prod (or the other way round) by accident. Secret values are piped to
// kubectl on stdin, never passed on its command line.

const crypto = require("node:crypto");
const tls = require("node:tls");
const { spawnSync } = require("node:child_process");
const { ROOT, NAMESPACE, TARGETS, log, fail, run, capture, waitForRollouts, smokeCheck } = require("./k8s-helpers");

const REGISTRY_REPO = "comicnerd23/ticketing";
const TAG_PLACEHOLDER = "set-by-deploy";
const TAG_PATTERN = /^(sha-[0-9a-f]{7}|v\d+\.\d+\.\d+)$/;
const HOST_PLACEHOLDER = "set-by-deploy-host";
const ISSUER_PLACEHOLDER = "set-by-deploy-issuer";
const ISSUERS = ["letsencrypt-prod", "letsencrypt-staging", "selfsigned"];
const TLS_SECRET = "ticketing-tls";
const CERTIFICATE_TIMEOUT_MS = 300_000;

// cert-manager's official static manifest. To upgrade: change the version, download the file
// and put its sha256 here; a manifest that doesn't match is never applied.
const CERT_MANAGER_VERSION = "v1.21.2";
const CERT_MANAGER_URL = `https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml`;
const CERT_MANAGER_SHA256 = "e03b668ec8675214af6b0a671699d088f2601fa3878e0dbe1b41d3feafd1879f";
const CERT_MANAGER_DEPLOYMENTS = ["cert-manager", "cert-manager-cainjector", "cert-manager-webhook"];

function usage() {
  log("Usage: node deploy-prod.js --context=<kube-context> --tag=<sha-1234567|vX.Y.Z> --base-url=https://<host>");
  log(`       [--tls-issuer=${ISSUERS.join("|")}]  (default: letsencrypt-prod)`);
  log("Env:   DB_PASSWORD, GATEWAY_CORS_ALLOWED_ORIGINS (required); DB_USERNAME (default: ticketing)");
  process.exit(2);
}

function parseArgs() {
  const opts = {};
  for (const arg of process.argv.slice(2)) {
    const m = arg.match(/^--(context|tag|base-url|tls-issuer)=(.+)$/);
    if (!m) usage();
    opts[m[1]] = m[2];
  }
  if (!opts.context || !opts.tag || !opts["base-url"]) usage();
  if (!TAG_PATTERN.test(opts.tag)) fail(`tag "${opts.tag}" is neither sha-<7 hex> nor vX.Y.Z — only immutable release tags are deployed`);
  const baseUrl = opts["base-url"].replace(/\/$/, "");
  let url;
  try {
    url = new URL(baseUrl);
  } catch {
    fail(`--base-url "${baseUrl}" is not a URL`);
  }
  if (url.protocol !== "https:" || url.port || url.pathname !== "/") {
    fail(`--base-url must be https://<host> without port or path (ADR-022), got "${baseUrl}"`);
  }
  // An Ingress host rule can't hold an IP, and the certificate is issued for a name.
  if (/^[\d.]+$/.test(url.hostname) || url.hostname.startsWith("[")) {
    fail("--base-url needs a host name, not an IP; for 203.0.113.10 use https://203-0-113-10.sslip.io");
  }
  const issuer = opts["tls-issuer"] || "letsencrypt-prod";
  if (!ISSUERS.includes(issuer)) fail(`--tls-issuer must be one of ${ISSUERS.join(", ")}, got "${issuer}"`);
  return { context: opts.context, tag: opts.tag, baseUrl, host: url.hostname, issuer };
}

function requireEnv(name) {
  const value = process.env[name];
  if (!value) fail(`environment variable ${name} is not set`);
  return value;
}

// Anonymous pull token + HEAD on the manifest: works because the packages are public (ADR-021).
async function checkTagExists(tag) {
  log(`>>> Checking that ${tag} exists in GHCR for all ${TARGETS.length} images`);
  for (const target of TARGETS) {
    const repo = `${REGISTRY_REPO}/${target}`;
    const tokenRes = await fetch(`https://ghcr.io/token?scope=repository:${repo}:pull`);
    const { token } = await tokenRes.json();
    const res = await fetch(`https://ghcr.io/v2/${repo}/manifests/${tag}`, {
      method: "HEAD",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json",
      },
    });
    log(`    ${res.ok ? "OK  " : "MISSING"} ghcr.io/${repo}:${tag}`);
    if (!res.ok) fail(`ghcr.io/${repo}:${tag} not found (HTTP ${res.status}) — run the release-images workflow for it first`);
  }
  log("");
}

// The CRDs and the webhook must exist before the overlay's ClusterIssuers can be applied.
async function installCertManager(kubectl) {
  log(`>>> Installing cert-manager ${CERT_MANAGER_VERSION}`);
  const res = await fetch(CERT_MANAGER_URL);
  if (!res.ok) fail(`could not download ${CERT_MANAGER_URL} (HTTP ${res.status})`);
  const manifest = Buffer.from(await res.arrayBuffer());
  const sha256 = crypto.createHash("sha256").update(manifest).digest("hex");
  if (sha256 !== CERT_MANAGER_SHA256) fail(`cert-manager manifest checksum mismatch: expected ${CERT_MANAGER_SHA256}, got ${sha256}`);
  log(`    sha256 OK (${sha256.slice(0, 12)}…)`);
  run("kubectl", [...kubectl, "apply", "-f", "-"], { input: manifest, quiet: true });
  for (const name of CERT_MANAGER_DEPLOYMENTS) {
    run("kubectl", [...kubectl, "-n", "cert-manager", "rollout", "status", `deployment/${name}`, "--timeout=180s"]);
  }
  log("");
}

function applyConfig(kubectl, { dbUsername, dbPassword, corsOrigins }) {
  log(">>> Writing namespace, db-credentials Secret and gateway-config ConfigMap (values not shown)");
  const docs = [
    { apiVersion: "v1", kind: "Namespace", metadata: { name: NAMESPACE } },
    {
      apiVersion: "v1",
      kind: "Secret",
      metadata: { name: "db-credentials", namespace: NAMESPACE },
      type: "Opaque",
      stringData: { username: dbUsername, password: dbPassword },
    },
    {
      apiVersion: "v1",
      kind: "ConfigMap",
      metadata: { name: "gateway-config", namespace: NAMESPACE },
      data: { "cors-allowed-origins": corsOrigins },
    },
  ];
  // kubectl accepts a JSON List on stdin.
  run("kubectl", [...kubectl, "apply", "-f", "-"], { input: JSON.stringify({ apiVersion: "v1", kind: "List", items: docs }) });
  log("");
}

function replaceAll(text, placeholder, value, expected) {
  const count = text.split(placeholder).length - 1;
  if (count !== expected) fail(`expected ${expected} "${placeholder}" in the prod overlay, found ${count}`);
  return text.split(placeholder).join(value);
}

function renderOverlay(tag, host, issuer) {
  let rendered = capture("kubectl", ["kustomize", "k8s/overlays/prod"]);
  if (!rendered) fail("kubectl kustomize k8s/overlays/prod failed");
  // Host and issuer first: their placeholders start with the tag's.
  rendered = replaceAll(rendered, HOST_PLACEHOLDER, host, 2);
  rendered = replaceAll(rendered, ISSUER_PLACEHOLDER, issuer, 1);
  const placeholders = rendered.split(`:${TAG_PLACEHOLDER}`).length - 1;
  if (placeholders !== TARGETS.length) {
    fail(`expected ${TARGETS.length} "${TAG_PLACEHOLDER}" image tags in the prod overlay, found ${placeholders}`);
  }
  return rendered.split(`:${TAG_PLACEHOLDER}`).join(`:${tag}`);
}

// Right after cert-manager starts, its webhook can still reject requests for a few seconds
// (CA not injected yet), so the apply is retried a few times.
async function applyOverlay(kubectl, rendered) {
  for (let attempt = 1; ; attempt++) {
    log(`    $ kubectl ${kubectl.join(" ")} apply -f -   (attempt ${attempt})`);
    const result = spawnSync("kubectl", [...kubectl, "apply", "-f", "-"], { cwd: ROOT, input: rendered, stdio: ["pipe", "inherit", "inherit"] });
    if (result.status === 0) break;
    if (attempt === 6) fail("kubectl apply of the prod overlay failed");
    log("    retrying in 10s (cert-manager's webhook may not be ready yet)");
    await new Promise((r) => setTimeout(r, 10_000));
  }
  log("");
}

// Ready alone isn't enough after switching issuers: the old certificate can still look Ready
// until the controller reacts. The Secret's issuer-name annotation shows which issuer signed
// what's served. A failed issuance is retried by cert-manager only after a backoff of about an
// hour, so the wait stops at the first failure instead of running into the timeout. An issuer
// switch starts a new attempt right away, which replaces a stale Failed within the grace period.
async function waitForCertificate(kubectl, issuer) {
  log(`>>> Waiting for certificate ${TLS_SECRET} from ${issuer}`);
  const ns = [...kubectl, "-n", NAMESPACE];
  const started = Date.now();
  let lastState = "";
  while (Date.now() - started < CERTIFICATE_TIMEOUT_MS) {
    const conditions = capture("kubectl", [...ns, "get", "certificate", TLS_SECRET, "-o", 'jsonpath={range .status.conditions[*]}{.type}={.status}/{.reason}{" "}{end}']) || "";
    const ready = (conditions.match(/\bReady=(\w+)/) || [])[1];
    const issuingFailed = /\bIssuing=False\/Failed\b/.test(conditions);
    const signedBy = capture("kubectl", [...ns, "get", "secret", TLS_SECRET, "-o", "jsonpath={.metadata.annotations.cert-manager\\.io/issuer-name}"]);
    const state = `Ready=${ready || "-"}, signed by ${signedBy || "-"}${issuingFailed ? ", issuance failed" : ""}`;
    if (state !== lastState) log(`    ${state}`);
    lastState = state;
    if (ready === "True" && signedBy === issuer) {
      log("");
      return;
    }
    // Give the controller a moment to pick up an issuer change before trusting a failure.
    if (issuingFailed && Date.now() - started > 30_000) {
      printCertificateDiagnostics(ns);
      fail(`certificate ${TLS_SECRET} from ${issuer} could not be issued (see the reasons above)`);
    }
    await new Promise((r) => setTimeout(r, 5000));
  }
  printCertificateDiagnostics(ns);
  fail(`certificate ${TLS_SECRET} from ${issuer} not ready after ${CERTIFICATE_TIMEOUT_MS / 1000}s`);
}

// cert-manager keeps the actual reason (e.g. Let's Encrypt's error) in conditions and in the
// orders' and challenges' status, not in the plain `get` columns.
function printCertificateDiagnostics(ns) {
  log("    cert-manager's view:");
  spawnSync("kubectl", [...ns, "get", "certificate,certificaterequest,orders.acme.cert-manager.io,challenges.acme.cert-manager.io"], { stdio: "inherit" });
  const reasons = [
    ["certificate", TLS_SECRET, '{range .status.conditions[*]}certificate {.type}={.status} {.reason}: {.message}{"\\n"}{end}'],
    ["orders.acme.cert-manager.io", "", '{range .items[*]}order {.metadata.name}: {.status.state} {.status.reason}{"\\n"}{end}'],
    ["challenges.acme.cert-manager.io", "", '{range .items[*]}challenge {.spec.dnsName}: {.status.state} {.status.reason}{"\\n"}{end}'],
  ];
  for (const [kind, name, jsonpath] of reasons) {
    const out = capture("kubectl", [...ns, "get", kind, ...(name ? [name] : []), "-o", `jsonpath=${jsonpath}`]);
    for (const line of (out || "").split("\n").filter(Boolean)) log(`    ${line}`);
  }
}

// What a browser would get: issuer, expiry and whether Node's trust store accepts it.
function inspectServedCertificate(host) {
  return new Promise((resolve) => {
    const socket = tls.connect({ host, port: 443, servername: host, rejectUnauthorized: false }, () => {
      const cert = socket.getPeerCertificate();
      const info = {
        trusted: socket.authorized,
        reason: socket.authorizationError,
        // cert-manager's certificates carry the name only as a SAN, and self-signed ones have an
        // empty issuer.
        names: cert.subjectaltname || cert.subject?.CN || "(none)",
        issuer: [cert.issuer?.O, cert.issuer?.CN].filter(Boolean).join(" / ") || "(empty, self-signed)",
        validTo: cert.valid_to,
      };
      socket.end();
      resolve(info);
    });
    socket.setTimeout(10_000, () => socket.destroy(new Error("timeout")));
    socket.on("error", (err) => resolve({ error: err.message }));
  });
}

async function main() {
  const { context, tag, baseUrl, host, issuer } = parseArgs();
  const config = {
    dbUsername: process.env.DB_USERNAME || "ticketing",
    dbPassword: requireEnv("DB_PASSWORD"),
    corsOrigins: requireEnv("GATEWAY_CORS_ALLOWED_ORIGINS"),
  };
  const kubectl = ["--context", context];
  if (capture("kubectl", [...kubectl, "get", "--raw", "/readyz"]) === null) {
    fail(`cluster of kube-context "${context}" is not reachable`);
  }

  log("=========================================");
  log(`  Prod deploy ${tag} -> ${context} (${host}, ${issuer})`);
  log("=========================================");
  log("");
  const started = Date.now();

  await checkTagExists(tag);
  const rendered = renderOverlay(tag, host, issuer);
  await installCertManager(kubectl);
  applyConfig(kubectl, config);

  log(`>>> Applying k8s/overlays/prod with ${tag}`);
  await applyOverlay(kubectl, rendered);

  waitForRollouts(kubectl);
  await waitForCertificate(kubectl, issuer);

  log(`>>> Certificate served for ${host}`);
  const cert = await inspectServedCertificate(host);
  if (cert.error) fail(`TLS connection to ${host}:443 failed: ${cert.error}`);
  log(`    names ${cert.names}, issuer ${cert.issuer}, valid until ${cert.validTo}`);
  log(`    trusted by Node: ${cert.trusted ? "yes" : `no (${cert.reason})`}`);
  if (issuer === "letsencrypt-prod" && !cert.trusted) fail("the served certificate is not trusted");
  if (issuer !== "letsencrypt-prod") {
    // Staging and self-signed certificates are untrusted by design; check everything else.
    log(`    ${issuer}: skipping certificate verification for the smoke check`);
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
  }
  log("");

  await smokeCheck(baseUrl, [
    // Probes and scrapers reach the pods directly; from outside the actuator must be closed.
    { url: `${baseUrl}/api/actuator/health`, ok: (res) => res.status === 404 },
    // Plain HTTP only redirects (port 80 itself stays open for ACME challenges).
    {
      url: `http://${host}/api/events`,
      init: { redirect: "manual" },
      ok: (res) => [301, 308].includes(res.status) && res.headers.get("location") === `${baseUrl}/api/events`,
    },
  ]);

  const seconds = Math.round((Date.now() - started) / 1000);
  log("=========================================");
  log(`  Done in ${seconds}s — ${tag} is live at ${baseUrl}`);
  log("=========================================");
}

// Only deploy when run directly; `require("./deploy-prod")` just exposes the helpers.
if (require.main === module) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}

module.exports = { checkTagExists, renderOverlay };
