# ADR-013: Angular 20 → 21 Upgrade, including Karma → Vitest Migration

**Status:** Accepted
**Date:** 2026-08-28
**Authors:** Engineering Team

---

## Context

The frontend is currently pinned to Angular 20.3.29 (`@angular/cli` 20.3.35, TypeScript 5.9.3,
RxJS 7.8, zone.js 0.15) — see `docs/plan.md`'s Phase 4 entry for the 19→20 upgrade this
follows. Unit tests still run on the classic `@angular-devkit/build-angular:karma` builder with
Jasmine (`karma.conf` is the CLI's inline default — no custom `karma.conf.js` exists in this
repo; 7 spec files total, a small surface).

Two separate but related questions triggered this ADR: whether it's worth moving off
Karma/Jasmine (Karma itself is unmaintained upstream and was dropped as Angular's default
test runner starting in v16's roadmap announcement), and whether to take the Angular 21 major
version bump now that it's available. `npm view @angular/core versions` confirms the real
state of the registry as of this writing: `21.2.22` is the latest stable 21.x release
(21.0.0 shipped 2025-11-20), and `22.1.4` is *also* already stable (22.0.0 shipped
2026-06-03) — so "upgrade" isn't a single obvious target, and picking between 21 and 22
needed its own comparison.

### Option A: Stay on Angular 20
No work, no risk.

**Strengths:**
- Zero migration effort or regression risk.

**Weaknesses:**
- Karma/Jasmine stay in place with no first-party migration path getting easier over time —
  every version further behind makes a future jump larger.
- Misses zoneless-by-default, Signal Forms, Angular Aria, and the other v21 improvements
  (see Decision below) that are directly relevant to a portfolio project meant to
  demonstrate current framework fluency.
- `docs/plan.md`'s existing 19→20 entry already established the precedent of taking Angular
  majors promptly once the environment supports them (that entry explicitly notes the
  earlier Node-version block "resolved on its own" and the upgrade was done right away) —
  sitting on 20 indefinitely breaks that precedent for no stated reason.

### Option B: Upgrade to Angular 21 (latest: 21.2.22)
**What's new relevant to this project, verified against the official v21 announcement and
release notes (blog.angular.dev, angular.dev/update-guide), not assumed:**

- **Zoneless change detection becomes the default for new apps**; zone.js is no longer
  auto-included in new projects. Existing apps (this one) keep zone.js unless explicitly
  migrated — v21 does *not* force zoneless on upgrade.
- **Karma is replaced by Vitest as the default test runner.** Angular ships an official
  schematic, `ng generate @angular/core:karma-to-vitest`, that rewrites `angular.json`'s
  `test` target and converts spec files automatically. Karma/Jasmine remain fully supported
  (not removed), but this schematic is exactly the "new alternative" this project's earlier
  Karma/Jasmine discussion was evaluating — it turns that question from a manual migration
  project into a low-risk, tool-assisted one, which is why it's bundled into this same ADR
  rather than deferred.
- **Signal Forms** ships as an experimental API — reactive forms driven by signals instead
  of `valueChanges` RxJS chains. Experimental only; not adopted in this slice (see Decision).
- **Angular Aria**: a new headless-primitives library for accessibility (replaces hand-rolled
  `aria-*`/`role` wiring). Not currently needed — this app has no complex ARIA widgets yet.
- **`HttpClient` is now provided in the root injector by default** — this project's
  `EventsApiService`/`BookingApiService` already call `provideHttpClient()` explicitly in
  `app.config.ts`; that call becomes redundant but harmless, not a breaking change here.
- **HammerJS built-in support is fully removed** (deprecated since v20) — irrelevant, this
  project has never used Hammer.
- Node requirement (per `@angular/cli@21.2.22`'s own `engines` field, checked directly rather
  than assumed): `^20.19.0 || ^22.12.0 || >=24.0.0`. This machine runs Node v24.19.0 — already
  satisfied, no Node change needed.
- TypeScript requirement (per `@angular/compiler-cli@21.2.22`'s peer dependency, checked
  directly): `>=5.9 <6.1`. This project is on TypeScript 5.9.3 — already satisfied, no
  TypeScript bump needed.
- RxJS/zone.js peer ranges (`^6.5.3 || ^7.4.0` / `~0.15.0 || ~0.16.0`) both already match
  what's installed.

**Strengths:**
- Every peer requirement (Node, TypeScript, RxJS, zone.js) is already satisfied by this
  project's current toolchain — genuinely a drop-in major version, not one that forces a
  cascade of unrelated upgrades.
- Bundles the already-desired Karma→Vitest move behind an official, low-risk schematic
  instead of a hand-written migration.
- Nine months of real-world usage since 21.0.0 (2025-11-20) — mature relative to 22.

**Weaknesses:**
- Still a major version bump — CLI migration schematics can touch `angular.json`/
  `tsconfig*.json`/spec files project-wide, which needs a full rebuild + test run to verify,
  not just a version-number change.

### Option C: Jump straight to Angular 22 (latest: 22.1.4)
**Strengths:**
- Newest available; would avoid a second major-version migration in the near future.
- OnPush becomes the default change-detection strategy for components, and `HttpClient` uses
  the Fetch API by default — both further modernization in the same direction as v21.

**Weaknesses:**
- **Requires TypeScript v6** (`@angular/compiler-cli` for v22 drops support for 5.9 per the
  Ninja Squad v22.0 write-up) — this project is on TypeScript 5.9.3, so this path forces an
  *additional*, unrequested major bump on top of the Angular one, expanding blast radius well
  beyond what was asked for.
- Only released 2026-06-03 — about three months old at the time of this ADR, versus v21's
  nine months. Meaningfully less real-world mileage and third-party-library compatibility
  data for a version this new.
- OnPush-by-default and Fetch-by-default `HttpClient` are behavioral defaults for *new*
  projects; adopting v22 wouldn't silently change this existing app's behavior, but going two
  majors past what was requested, on a three-month-old release, for no concrete feature this
  project currently needs, is more risk than the ask justifies.
- The user's request was specifically "Version 21," not "latest" — worth surfacing 22's
  existence here since it wasn't otherwise obvious it existed, but not worth overriding the
  explicit ask on the strength of a three-month-old release with a forced TypeScript bump.

---

## Decision

We adopt **Option B**: upgrade to **Angular 21 (21.2.22)**, and as part of the same upgrade,
migrate the frontend's unit tests from Karma+Jasmine to **Vitest** via the official
`ng generate @angular/core:karma-to-vitest` schematic.

Explicitly **out of scope** for this slice, deferred as future work:
- **Zoneless migration** (`ng generate @angular/core:zoneless-migration` / removing zone.js
  entirely) — a materially larger behavioral change than a test-runner swap, since it touches
  how change detection runs across every component in the app, not just build tooling. Stays
  on zone.js for now, matching the testable-slices convention of not bundling unrelated
  large changes into one upgrade.
- **Signal Forms adoption** — experimental API; the existing `BookingStatusComponent`
  signal-based state (from the Phase 4 booking-flow slice) already covers this project's
  reactive-state needs without it.
- **Angular Aria** — no current ARIA widget complex enough to need it.
- **Angular 22** — see Option C above; revisit once it has more real-world mileage or this
  project has a concrete reason to need OnPush-by-default/Fetch-by-default.

---

## Rationale

The version choice was straightforward once the peer-dependency chain was actually checked
against this project's installed toolchain (Node, TypeScript, RxJS, zone.js) rather than
assumed: v21 requires no changes beyond the Angular packages themselves, while v22 would
silently drag in an unrequested TypeScript major bump. Combined with v21 having roughly 3x
the real-world mileage of v22 at the time of writing, and the user's explicit ask being "21"
specifically, Option B was the clear choice over Option C.

Bundling the Karma→Vitest migration into this same ADR (rather than a separate one) follows
from the fact that Angular 21 is precisely what makes that migration low-risk: it ships an
official, first-party schematic for exactly this conversion, so the "should we replace
Karma?" question from the earlier discussion and the "should we take the v21 bump?" question
converge on the same answer at the same time, rather than being two separate migrations that
would each touch `angular.json` and the spec files independently.

---

## Consequences

### Positive
- Test runner moves off an upstream-unmaintained project (Karma) onto Vitest, using Angular's
  own supported migration path rather than a hand-rolled config.
- Zero forced changes to Node, TypeScript, RxJS, or zone.js versions — the smallest possible
  blast radius for a major-version bump.
- Establishes the same "take framework majors promptly once the toolchain already supports
  them" precedent the 19→20 upgrade set, documented here the same way.

### Negative / Trade-offs
- `ng update` and the Vitest schematic both run automated codemods across `angular.json` and
  every spec file — each needs to be diffed and the full test suite re-run to confirm nothing
  silently broke, rather than trusting the migration output blindly.
- Vitest is new to this project; going forward, `ng test` behavior (watch mode, coverage
  flags, CI invocation) differs from the Karma-based command developers may be used to from
  other Angular projects.
- Zone.js remains in the dependency tree for now — this upgrade does not yet capture v21's
  zoneless performance/bundle-size benefits; that's the deferred follow-up slice.
