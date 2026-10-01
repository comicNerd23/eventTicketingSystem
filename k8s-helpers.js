// Small helpers shared by deploy-dev.js and deploy-prod.js (ADR-020, ADR-021).
// Node built-ins only, like the other root scripts.

const { spawnSync } = require("node:child_process");

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

// Runs a command with inherited output; exits the script if it fails. `input` is piped to stdin
// and never echoed, so secrets can be passed that way instead of on the command line.
function run(command, args, { input, quiet = false } = {}) {
  if (!quiet) log(`    $ ${command} ${args.join(" ")}`);
  const result = spawnSync(command, args, {
    cwd: ROOT,
    input,
    stdio: [input === undefined ? "inherit" : "pipe", "inherit", "inherit"],
  });
  if (result.error) fail(`could not start ${command}: ${result.error.message}`);
  if (result.status !== 0) fail(`${command} exited with ${result.status}`);
}

// Runs a command and returns its trimmed stdout, or null if it fails.
function capture(command, args) {
  const result = spawnSync(command, args, { cwd: ROOT, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  return result.status === 0 ? result.stdout.trim() : null;
}

function waitForRollouts(kubectlArgs) {
  log(">>> Waiting for rollouts");
  const workloads = capture("kubectl", [...kubectlArgs, "-n", NAMESPACE, "get", "deployments,statefulsets", "-o", "name"]);
  for (const w of (workloads || "").split(/\s+/).filter(Boolean)) {
    run("kubectl", [...kubectlArgs, "-n", NAMESPACE, "rollout", "status", w, `--timeout=${ROLLOUT_TIMEOUT}`]);
  }
  log("");
}

// Real requests through the Ingress. `rollout status` alone can report success from stale status
// right after a node restart (docs/plan.md, slice (d)).
async function smokeCheck(baseUrl, extraChecks = []) {
  log(`>>> Smoke check through the Ingress (${baseUrl})`);
  const checks = [
    { url: `${baseUrl}/`, ok: (res, body) => res.ok && body.includes("<app-root") },
    { url: `${baseUrl}/api/events?size=1`, ok: (res) => res.ok },
    ...extraChecks,
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

module.exports = { ROOT, NAMESPACE, SERVICES, TARGETS, log, fail, run, capture, waitForRollouts, smokeCheck };
