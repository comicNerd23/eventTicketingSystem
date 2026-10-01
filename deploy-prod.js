#!/usr/bin/env node
// Deploys k8s/overlays/prod with a given release tag (ADR-016 slice (e), ADR-021). The same
// script runs locally and in the GitHub Actions deploy job.
//
//   node deploy-prod.js --context=<kube-context> --tag=<sha-1234567|v1.2.3> --base-url=<url>
//
// Required environment variables (in the deploy job: GitHub Actions secrets):
//   DB_PASSWORD                    password for every service's Postgres user
//   GATEWAY_CORS_ALLOWED_ORIGINS   the public origin of the app, e.g. https://tickets.example.org
// Optional:
//   DB_USERNAME                    defaults to "ticketing"
//
// Steps: check that the tag exists in GHCR for all seven images, write the db-credentials
// Secret and gateway-config ConfigMap, render the overlay with the tag, apply, wait for every
// rollout, then smoke-test through the Ingress, including that the gateway's actuator is not
// reachable from outside.
//
// The kube-context must be named explicitly; the current context is never used, so a dev
// session can't deploy to prod (or the other way round) by accident. Secret values are piped to
// kubectl on stdin, never passed on its command line.

const { NAMESPACE, TARGETS, log, fail, run, capture, waitForRollouts, smokeCheck } = require("./k8s-helpers");

const REGISTRY_REPO = "comicnerd23/ticketing";
const TAG_PLACEHOLDER = "set-by-deploy";
const TAG_PATTERN = /^(sha-[0-9a-f]{7}|v\d+\.\d+\.\d+)$/;

function usage() {
  log("Usage: node deploy-prod.js --context=<kube-context> --tag=<sha-1234567|vX.Y.Z> --base-url=<url>");
  log("Env:   DB_PASSWORD, GATEWAY_CORS_ALLOWED_ORIGINS (required); DB_USERNAME (default: ticketing)");
  process.exit(2);
}

function parseArgs() {
  const opts = {};
  for (const arg of process.argv.slice(2)) {
    const m = arg.match(/^--(context|tag|base-url)=(.+)$/);
    if (!m) usage();
    opts[m[1]] = m[2];
  }
  if (!opts.context || !opts.tag || !opts["base-url"]) usage();
  if (!TAG_PATTERN.test(opts.tag)) fail(`tag "${opts.tag}" is neither sha-<7 hex> nor vX.Y.Z — only immutable release tags are deployed`);
  return { context: opts.context, tag: opts.tag, baseUrl: opts["base-url"].replace(/\/$/, "") };
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

function renderOverlay(tag) {
  const rendered = capture("kubectl", ["kustomize", "k8s/overlays/prod"]);
  if (!rendered) fail("kubectl kustomize k8s/overlays/prod failed");
  const placeholders = rendered.split(`:${TAG_PLACEHOLDER}`).length - 1;
  if (placeholders !== TARGETS.length) {
    fail(`expected ${TARGETS.length} "${TAG_PLACEHOLDER}" image tags in the prod overlay, found ${placeholders}`);
  }
  return rendered.split(`:${TAG_PLACEHOLDER}`).join(`:${tag}`);
}

async function main() {
  const { context, tag, baseUrl } = parseArgs();
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
  log(`  Prod deploy ${tag} -> ${context}`);
  log("=========================================");
  log("");
  const started = Date.now();

  await checkTagExists(tag);
  applyConfig(kubectl, config);

  log(`>>> Applying k8s/overlays/prod with ${tag}`);
  run("kubectl", [...kubectl, "apply", "-f", "-"], { input: renderOverlay(tag) });
  log("");

  waitForRollouts(kubectl);

  await smokeCheck(baseUrl, [
    // Probes and scrapers reach the pods directly; from outside the actuator must be closed.
    { url: `${baseUrl}/api/actuator/health`, ok: (res) => res.status === 404 },
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
