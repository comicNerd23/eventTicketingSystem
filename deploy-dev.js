#!/usr/bin/env node
// Builds the images and deploys k8s/overlays/dev to a local Kubernetes dev cluster, with no
// registry (ADR-016 slice (d), ADR-020). The cluster is taken from the current kube-context:
//
//   kind-<name>       docker build, then `kind load docker-image` into that cluster.
//                     The app is at http://localhost:8000 (k8s/kind/cluster.yaml).
//   rancher-desktop   docker build against Rancher Desktop's dockerd (moby runtime). k3s uses
//                     the same image store, so there is nothing to load. App: http://localhost
//                     The docker CLI's own current context may still be Docker Desktop's, so
//                     builds use the "rancher-desktop" docker context if it exists, otherwise
//                     "default" (on Windows the docker_engine pipe Rancher Desktop serves), and
//                     the script checks that this engine really is Rancher Desktop.
//
// Any other context is refused, so a prod context can never be deployed to by accident.
//
//   node deploy-dev.js [targets...] [--no-build] [--seed]
//
// Targets: the six service names and "frontend"; default is all seven. Only the targets given
// are rebuilt and restarted, but the whole overlay is always applied.
//   --no-build   deploy the :dev images that already exist
//   --seed       run `seed-events.js --k8s` afterwards
//
// Same style as ci.js / seed-events.js: Node built-ins only, cross-platform.

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = __dirname;
const NAMESPACE = "ticketing";
const SERVICES = [
  "api-gateway",
  "event-service",
  "booking-service",
  "payment-service",
  "notification-service",
  "waitlist-service",
];
const TARGETS = [...SERVICES, "frontend"];
const ROLLOUT_TIMEOUT = "360s";

function log(line = "") {
  console.log(line);
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

function usage() {
  log("Usage: node deploy-dev.js [targets...] [--no-build] [--seed]");
  log(`Targets: ${TARGETS.join(", ")} (default: all)`);
  process.exit(2);
}

// Runs a command with inherited output; exits the script if it fails.
function run(command, args, { cwd = ROOT } = {}) {
  log(`    $ ${command} ${args.join(" ")}`);
  const result = spawnSync(command, args, { cwd, stdio: "inherit" });
  if (result.error) fail(`could not start ${command}: ${result.error.message}`);
  if (result.status !== 0) fail(`${command} exited with ${result.status}`);
}

// Runs a command and returns its trimmed stdout, or null if it fails.
function capture(command, args) {
  const result = spawnSync(command, args, { cwd: ROOT, encoding: "utf8" });
  return result.status === 0 ? result.stdout.trim() : null;
}

// winget puts kind.exe in a Links folder that only shells started after the install have on PATH.
function kindBinary() {
  if (capture("kind", ["version"]) !== null) return "kind";
  const winget = path.join(process.env.LOCALAPPDATA || "", "Microsoft", "WinGet", "Links", "kind.exe");
  if (fs.existsSync(winget)) return winget;
  fail("kind is not on PATH (install it, e.g. `winget install Kubernetes.kind`)");
}

function detectCluster() {
  const context = capture("kubectl", ["config", "current-context"]);
  if (!context) fail("kubectl has no current context — is a dev cluster running?");
  if (context.startsWith("kind-")) {
    return { context, kind: "kind", name: context.slice("kind-".length), baseUrl: "http://localhost:8000" };
  }
  if (context === "rancher-desktop") {
    return { context, kind: "rancher-desktop", baseUrl: "http://localhost" };
  }
  fail(`refusing to deploy to kube-context "${context}": only kind-* and rancher-desktop are dev clusters`);
}

// Picks the docker context whose engine is Rancher Desktop's, so builds land in k3s's image store.
function rancherDockerContext() {
  const contexts = (capture("docker", ["context", "ls", "--format", "{{.Name}}"]) || "").split(/s+/);
  const name = contexts.includes("rancher-desktop") ? "rancher-desktop" : "default";
  const os = capture("docker", ["--context", name, "info", "--format", "{{.OperatingSystem}}"]);
  if (!os || !os.includes("Rancher Desktop")) {
    fail(`docker context "${name}" is not Rancher Desktop's engine (got: ${os || "no answer"}). Is Rancher Desktop running with the dockerd (moby) runtime, and Docker Desktop stopped?`);
  }
  return name;
}

function buildContext(target) {
  return target === "frontend" ? path.join(ROOT, "frontend") : path.join(ROOT, "services", target);
}

function ensureIngressController(cluster) {
  const classes = capture("kubectl", ["get", "ingressclass", "-o", "name"]);
  if (classes) return;
  if (cluster.kind !== "kind") fail("no IngressClass in the cluster — Rancher Desktop normally bundles Traefik; is it enabled?");
  log(">>> No ingress controller yet: installing Traefik for kind");
  run("kubectl", ["apply", "-f", "k8s/kind/traefik.yaml"]);
  log("");
}

async function smokeCheck(baseUrl) {
  log(`>>> Smoke check through the Ingress (${baseUrl})`);
  const checks = [
    { url: `${baseUrl}/`, ok: (res, body) => res.ok && body.includes("<app-root") },
    { url: `${baseUrl}/api/events?size=1`, ok: (res) => res.ok },
  ];
  for (const { url, ok } of checks) {
    let passed = false;
    let last = "";
    // Traefik can need a few seconds to pick up new endpoints after a rollout.
    for (let attempt = 1; attempt <= 15 && !passed; attempt++) {
      try {
        const res = await fetch(url);
        const body = await res.text();
        passed = ok(res, body);
        last = `HTTP ${res.status}`;
      } catch (err) {
        last = err.cause?.code || err.message;
      }
      if (!passed) await new Promise((r) => setTimeout(r, 2000));
    }
    log(`    ${passed ? "OK  " : "FAIL"} ${url} (${last})`);
    if (!passed) fail(`smoke check failed for ${url}`);
  }
  log("");
}

async function main() {
  const args = process.argv.slice(2);
  const noBuild = args.includes("--no-build");
  const seed = args.includes("--seed");
  const unknownFlags = args.filter((a) => a.startsWith("--") && !["--no-build", "--seed"].includes(a));
  if (unknownFlags.length > 0) usage();
  const named = args.filter((a) => !a.startsWith("--"));
  if (named.some((t) => !TARGETS.includes(t))) usage();
  const targets = named.length > 0 ? named : TARGETS;

  const cluster = detectCluster();
  log("=========================================");
  log(`  Dev deploy -> ${cluster.context}`);
  log("=========================================");
  log("");
  const started = Date.now();

  const dockerArgs = !noBuild && cluster.kind === "rancher-desktop" ? ["--context", rancherDockerContext()] : [];
  if (!noBuild) {
    // One image at a time: parallel Maven builds don't fit in the 4 GB Docker/WSL VM.
    for (const t of targets) {
      log(`>>> Building ticketing/${t}:dev`);
      run("docker", [...dockerArgs, "build", "-t", `ticketing/${t}:dev`, buildContext(t)]);
      if (cluster.kind === "kind") {
        run(kindBinary(), ["load", "docker-image", `ticketing/${t}:dev`, "--name", cluster.name]);
      }
      log("");
    }
  }

  ensureIngressController(cluster);

  // Deployments that exist before the apply keep running the old image under the same :dev tag.
  // Ones created by this apply start with the new image already: restarting those too would start
  // a second ReplicaSet next to the first, and on a cold cluster 12 JVMs don't fit in 4 GB.
  const existing = new Set(
    (capture("kubectl", ["-n", NAMESPACE, "get", "deployments", "-o", "name"]) || "")
      .split(/\s+/)
      .filter(Boolean)
      .map((d) => d.replace(/^deployment\.apps\//, ""))
  );

  log(">>> Applying k8s/overlays/dev");
  run("kubectl", ["apply", "-k", "k8s/overlays/dev"]);
  log("");

  const toRestart = noBuild ? [] : targets.filter((t) => existing.has(t));
  if (toRestart.length > 0) {
    log(">>> Restarting the rebuilt deployments");
    run("kubectl", ["-n", NAMESPACE, "rollout", "restart", ...toRestart.map((t) => `deployment/${t}`)]);
    log("");
  }

  log(">>> Waiting for rollouts");
  const workloads = capture("kubectl", ["-n", NAMESPACE, "get", "deployments,statefulsets", "-o", "name"]);
  for (const w of (workloads || "").split(/\s+/).filter(Boolean)) {
    run("kubectl", ["-n", NAMESPACE, "rollout", "status", w, `--timeout=${ROLLOUT_TIMEOUT}`]);
  }
  log("");

  await smokeCheck(cluster.baseUrl);

  if (seed) {
    log(">>> Seeding the demo catalog");
    run(process.execPath, ["seed-events.js", "--k8s", `--base-url=${cluster.baseUrl}`]);
  }

  const seconds = Math.round((Date.now() - started) / 1000);
  log("=========================================");
  log(`  Done in ${seconds}s — app: ${cluster.baseUrl}`);
  log("=========================================");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
