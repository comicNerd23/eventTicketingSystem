#!/usr/bin/env node
// Single CI entry point, run identically on a developer machine and inside GitHub Actions
// (.github/workflows/ci.yml calls exactly these targets) — see docs/adr/ADR-015.
//
//   node ci.js <target> [--docker]
//
// Targets: one of the six service names, "frontend", "services" (all six), or "all".
//   service  -> mvn -B -ntp verify   (+ docker build of its Dockerfile with --docker)
//   frontend -> npm ci, ng test (single run), ng build (+ docker build of frontend/Dockerfile with --docker)
//
// Five services run Testcontainers suites (Postgres/Kafka). Locally, Testcontainers may be
// routed to Testcontainers Cloud (free plan capped at 50 min/month, see docs/plan.md) —
// this script only warns about that; switching Testcontainers Desktop to the local Docker
// runtime keeps local runs free and unlimited. In CI the runner's own Docker is used.
//
// Cross-platform (Windows without Git Bash/WSL), Node built-ins only, same style as
// seed-events.js / demo.js.

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const ROOT = __dirname;
const SERVICES = [
  "api-gateway",
  "event-service",
  "booking-service",
  "payment-service",
  "notification-service",
  "waitlist-service",
];
const USES_TESTCONTAINERS = SERVICES.filter((s) => s !== "api-gateway");

function log(line = "") {
  console.log(line);
}

function usage() {
  log("Usage: node ci.js <target> [--docker]");
  log(`Targets: ${SERVICES.join(", ")}, frontend, services, all`);
  process.exit(2);
}

// On Windows, mvn/npm/npx are .cmd shims that only resolve through a shell. The shell gets
// one joined command string (args here are fixed constants, never user input), which avoids
// Node's DEP0190 warning about passing an args array together with `shell: true`.
function run(command, args, cwd) {
  const line = `${command} ${args.join(" ")}`;
  log(`    $ ${line}`);
  const result =
    process.platform === "win32"
      ? spawnSync(line, { cwd, stdio: "inherit", shell: true })
      : spawnSync(command, args, { cwd, stdio: "inherit" });
  if (result.error) {
    log(`    could not start ${command}: ${result.error.message}`);
    return false;
  }
  return result.status === 0;
}

function warnIfTestcontainersCloud() {
  if (process.env.CI) return;
  const propsFile = path.join(os.homedir(), ".testcontainers.properties");
  let props = "";
  try {
    props = fs.readFileSync(propsFile, "utf8");
  } catch {
    return;
  }
  if (/^\s*tc\.host\s*=/m.test(props)) {
    // tc.host is set whenever Testcontainers Desktop is in use, whichever runtime it proxies to.
    log("NOTE: Testcontainers runs through Testcontainers Desktop (tc.host). Make sure its runtime");
    log("      is set to local Docker — Testcontainers Cloud is capped at 50 min/month (ADR-015, D).");
    log("");
  }
}

function checkService(name, withDocker) {
  const dir = path.join(ROOT, "services", name);
  if (!run("mvn", ["-B", "-ntp", "verify"], dir)) return false;
  if (withDocker) {
    return run("docker", ["build", "-t", `ticketing/${name}:ci`, "."], dir);
  }
  return true;
}

function checkFrontend(withDocker) {
  const dir = path.join(ROOT, "frontend");
  const ok =
    run("npm", ["ci", "--no-audit", "--no-fund"], dir) &&
    run("npx", ["ng", "test", "--watch=false"], dir) &&
    run("npx", ["ng", "build"], dir);
  if (ok && withDocker) {
    return run("docker", ["build", "-t", "ticketing/frontend:ci", "."], dir);
  }
  return ok;
}

function main() {
  const args = process.argv.slice(2);
  const withDocker = args.includes("--docker");
  const target = args.find((a) => !a.startsWith("--"));
  if (!target) usage();

  let targets;
  if (target === "all") targets = [...SERVICES, "frontend"];
  else if (target === "services") targets = SERVICES;
  else if (target === "frontend" || SERVICES.includes(target)) targets = [target];
  else usage();

  if (targets.some((t) => USES_TESTCONTAINERS.includes(t))) warnIfTestcontainersCloud();

  const results = [];
  for (const t of targets) {
    log("=========================================");
    log(`  ${t}`);
    log("=========================================");
    const started = Date.now();
    const ok = t === "frontend" ? checkFrontend(withDocker) : checkService(t, withDocker);
    const seconds = Math.round((Date.now() - started) / 1000);
    results.push({ target: t, ok, seconds });
    log(ok ? `  SUCCESS — ${t} (${seconds}s)` : `  FAILED — ${t} (${seconds}s)`);
    log("");
  }

  log("=========================================");
  log("  Summary");
  log("=========================================");
  for (const r of results) {
    log(`  ${r.ok ? "PASS" : "FAIL"}  ${r.target.padEnd(22)} ${r.seconds}s`);
  }
  const failed = results.filter((r) => !r.ok).map((r) => r.target);
  if (failed.length > 0) {
    log("");
    log(`ERROR: failed targets: ${failed.join(", ")}`);
    process.exit(1);
  }
}

main();
