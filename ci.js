#!/usr/bin/env node
// Single CI entry point, run identically on a developer machine and inside GitHub Actions
// (.github/workflows/ci.yml calls exactly these targets) — see docs/adr/ADR-015.
//
//   node ci.js <target> [--docker] [--sonar]
//
// Targets: one of the six service names, "frontend", "services" (all six), or "all".
//   service  -> mvn -B -ntp verify, incl. JaCoCo coverage (+ docker build of its Dockerfile with --docker)
//   frontend -> npm ci, ng test (single run, with coverage), ng build (+ docker build of frontend/Dockerfile with --docker)
//
// --sonar sends the analysis, with the coverage just produced, to SonarQube Cloud (ADR-023) and
// fails the target if its quality gate fails. It needs SONAR_TOKEN and SONAR_ORGANIZATION in the
// environment and is skipped without them (e.g. pull requests from forks, which get no secrets).
// CI passes it on every run; locally the coverage reports are enough
// (services/<name>/target/site/jacoco/index.html, frontend/coverage/frontend/lcov-report/index.html).
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

// One SonarQube Cloud project per target, keyed <organization>_<target> (ADR-023). Scanner
// versions are pinned like everything else in CI; the token is read from SONAR_TOKEN by the
// scanners themselves and never appears on a command line.
const SONAR_HOST = "https://sonarcloud.io";
const SONAR_MAVEN_PLUGIN = "org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar";
const SONAR_NPM_SCANNER = "@sonar/scan@5.0.1";

function log(line = "") {
  console.log(line);
}

function usage() {
  log("Usage: node ci.js <target> [--docker] [--sonar]");
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

// Returns the scanner arguments, or null (with a note) when the analysis has to be skipped.
function sonarArgs(target) {
  const organization = process.env.SONAR_ORGANIZATION;
  if (!process.env.SONAR_TOKEN || !organization) {
    log("    SonarQube: skipped (SONAR_TOKEN or SONAR_ORGANIZATION not set)");
    return null;
  }
  return [
    `-Dsonar.host.url=${SONAR_HOST}`,
    `-Dsonar.organization=${organization}`,
    `-Dsonar.projectKey=${organization}_${target}`,
    `-Dsonar.projectName=${target}`,
    "-Dsonar.qualitygate.wait=true",
  ];
}

function checkService(name, withDocker, withSonar) {
  const dir = path.join(ROOT, "services", name);
  if (!run("mvn", ["-B", "-ntp", "verify"], dir)) return false;
  const sonar = withSonar && sonarArgs(name);
  if (sonar && !run("mvn", ["-B", "-ntp", SONAR_MAVEN_PLUGIN, ...sonar], dir)) return false;
  if (withDocker) {
    return run("docker", ["build", "-t", `ticketing/${name}:ci`, "."], dir);
  }
  return true;
}

function checkFrontend(withDocker, withSonar) {
  const dir = path.join(ROOT, "frontend");
  let ok =
    run("npm", ["ci", "--no-audit", "--no-fund"], dir) &&
    run("npx", ["ng", "test", "--watch=false", "--coverage", "--coverage-reporters=lcov", "--coverage-reporters=text-summary"], dir) &&
    run("npx", ["ng", "build"], dir);
  // Sources, tests and the LCOV path are in frontend/sonar-project.properties.
  const sonar = ok && withSonar && sonarArgs("frontend");
  if (sonar) ok = run("npx", ["--yes", SONAR_NPM_SCANNER, ...sonar], dir);
  if (ok && withDocker) {
    return run("docker", ["build", "-t", "ticketing/frontend:ci", "."], dir);
  }
  return ok;
}

function main() {
  const args = process.argv.slice(2);
  const withDocker = args.includes("--docker");
  const withSonar = args.includes("--sonar");
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
    const ok = t === "frontend" ? checkFrontend(withDocker, withSonar) : checkService(t, withDocker, withSonar);
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
