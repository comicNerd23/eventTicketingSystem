# ADR-014: Zoneless Change Detection

**Status:** Accepted
**Date:** 2026-08-28
**Authors:** Engineering Team

---

## Context

ADR-013 (Angular 20 → 21 upgrade) deliberately deferred the zoneless migration as its own
follow-up slice, since it changes how change detection runs across every component rather
than being a tooling/build swap. This ADR covers that deferred slice: whether and how to
adopt `provideZonelessChangeDetection()` and remove `zone.js` from the frontend.

### What zoneless actually changes
Today, `app.config.ts` calls `provideZoneChangeDetection({ eventCoalescing: true })`, and
`angular.json`'s `build` target lists `zone.js` in `polyfills`. Zone.js works by monkey-patching
every async browser API (`setTimeout`, `Promise`, DOM events, `XMLHttpRequest`, `WebSocket`,
etc.) so that Angular can run a full change-detection pass after *any* of them fires, without
the application having to say what changed. Zoneless removes that patch entirely; change
detection instead runs only when something explicitly notifies Angular's scheduler — a signal
write, a template-bound event handler, `AsyncPipe`, or an explicit `ChangeDetectorRef` call.

### Codebase audit — verified by reading every component, not assumed
All three real feature components were read in full to check whether they already fit this
model:

- **`EventListComponent`** — 100% signal-driven (`eventPage`, `loadError`), every HTTP
  callback only calls `.set(...)`. Already zoneless-safe.
- **`BookingStatusComponent`** — 100% signal-driven (`booking`, `error`, `confirming`,
  `cancelling`, `remainingSeconds`, `totalHoldSeconds`, plus computed signals for the
  countdown/urgency/progress bar). Its 1-second countdown runs on an RxJS `interval(1000)`,
  but the callback only calls signal setters — the fact that the *timer* isn't zone-patched
  anymore doesn't matter, because signal writes notify Angular's scheduler directly,
  independent of what scheduled the callback. Already zoneless-safe.
- **`SeatMapComponent`** — mostly signal-driven (`data`, `loadError`, `openSections`), and its
  native `WebSocket.onmessage` handler (a genuinely zone-dependent-looking pattern at first
  glance) already routes through `this.data.set(...)`, so it's fine too. **But** it has two
  plain, non-signal instance fields — `holdingSeatId: string | null` and
  `holdError: string | null` — mutated directly inside `selectSeat()`, including inside the
  *async* `bookingApi.holdSeat(...).subscribe({ error: ... })` callback
  (`seat-map.component.ts:147-149`). `holdError` is read directly in the template
  (`seat-map.component.html:31`, confirmed by grep). Today this works only because zone.js
  patches the HTTP callback's async boundary and triggers a CD sweep after it runs. **Under
  zoneless this genuinely stops updating the view** — a real, concrete bug this migration
  must fix, not a hypothetical risk.
- `AppComponent`/`NotFoundComponent` have no dynamic state at all — irrelevant to this
  migration.

**No official automated schematic exists for this migration** — a schematic name
(`ng generate @angular/core:zoneless-migration`) surfaced during initial research, but was
checked directly against the installed `@angular/core` schematics collection the same way
ADR-013 checked (and disproved) a similarly-claimed Karma→Vitest schematic name, and it does
not exist in Angular 21.2.22's collection either. `provideZonelessChangeDetection()` itself is
real and stable (`@publicApi 20.2`, confirmed in `@angular/core`'s own type declarations) —
only the migration tooling claim was wrong. This has to be a small hand-migration.

### Option A: Stay zone-based
No change.

**Strengths:**
- Zero risk, zero work.

**Weaknesses:**
- Misses zoneless's real benefits for this app: no zone.js in the bundle (one less
  ~30-40kB-ish polyfill patch layer), faster/more predictable change detection (no
  full-tree sweep on every DOM event, timer, or HTTP response — only on the ~3 signals that
  actually changed), and it's the direction Angular itself has committed to (zoneless is the
  default for all new v21 projects per ADR-013's research).
- Leaves the `holdingSeatId`/`holdError` gap in `SeatMapComponent` undiscovered — it happens
  to work today only because zone.js papers over it; staying zone-based doesn't fix the
  latent bug, it just keeps it invisible.

### Option B: Migrate to zoneless, minimal scope
Swap `provideZoneChangeDetection(...)` → `provideZonelessChangeDetection()`, remove `zone.js`
from `angular.json`'s polyfills and from `package.json`, and fix the one genuine
zoneless-incompatibility found (`holdingSeatId`/`holdError` → signals). Leave every
component's `changeDetection` strategy at its current default (`Default`, implicit) rather
than adding explicit `OnPush` everywhere in the same pass.

**Strengths:**
- Smallest change that actually delivers zoneless — matches the testable-slices convention of
  not bundling adjacent-but-separable improvements into one slice (the same reasoning ADR-012
  used to defer tracing/logging/alerting, and ADR-013 used to defer Signal Forms/Aria).
- `Default` strategy still works correctly zoneless — it only means Angular *would* check a
  component during a scheduled pass triggered by an ancestor, rather than skipping unrelated
  subtrees the way `OnPush` does. Given this app is 3 small components deep with no shared
  parent state, the perf difference is negligible.

**Weaknesses:**
- Leaves `OnPush` as unfinished modernization — someone reading the code later might
  reasonably ask why zoneless was adopted without it.

### Option C: Migrate to zoneless and add explicit `OnPush` to every component in the same slice
Same as Option B, plus `changeDetection: ChangeDetectionStrategy.OnPush` added to
`EventListComponent`, `SeatMapComponent`, `BookingStatusComponent`, `AppComponent`.

**Strengths:**
- "Finishes" the modernization in one pass — no follow-up needed.

**Weaknesses:**
- Expands this slice's blast radius for no measurable benefit at this app's size (4
  components, none with expensive subtrees) — the actual behavior is identical to Option B's
  `Default` strategy once zoneless is active and every component's state is signal-driven,
  since there's no unrelated-sibling-re-check cost to avoid when there's effectively nothing
  to re-check that doesn't already depend on a changed signal.
- `OnPush` becoming Angular 22's *default* for new components (per ADR-013's research on why
  this project stayed on 21) means adding it by hand now duplicates work a future "consider
  Angular 22" ADR would revisit anyway.

---

## Decision

Option B — migrate to zoneless with `provideZonelessChangeDetection()`, remove
`zone.js`, and convert `SeatMapComponent`'s `holdingSeatId`/`holdError` to signals as a
required correctness fix, not an optional polish item. `OnPush` is explicitly deferred.

Implemented and verified the same day this ADR was drafted, after a review checkpoint on the
change list below (per this project's ADR-before-implementing convention and the
testable-slices checkpoint rule).

### Concrete change list (applied)
1. `frontend/src/app/app.config.ts`: `provideZoneChangeDetection({ eventCoalescing: true })`
   → `provideZonelessChangeDetection()`.
2. `frontend/angular.json`: remove `"zone.js"` from the `build` target's `polyfills` array.
3. `frontend/package.json`: remove the `zone.js` dependency.
4. `frontend/src/app/events/seat-map.component.ts`: `holdingSeatId`/`holdError` become
   `signal<string | null>(null)`, with `.set(...)` in place of direct assignment; template
   (`seat-map.component.html`) and `seatClass()` updated to call them as functions.
5. Test-side: confirmed no test or `TestBed.configureTestingModule` call anywhere in the repo
   referenced `NgZone` or zone-patched timing beyond the one `fakeAsync` case ADR-013 already
   replaced with `vi.useFakeTimers()` — no test changes were needed.
6. Full re-run of `ng build` + `ng test` (32/32 still passing, no test-content changes), plus
   a live browser check (headless Chrome via raw CDP — the extension wasn't connected this
   session) against the real running backend stack, specifically targeting the two riskiest
   paths: a genuine synthetic click on a real `AVAILABLE` seat (real `POST /bookings/hold` →
   success → `router.navigate` → real booking page with a live countdown, all with
   `window.Zone === undefined` confirmed first), and a forced repeat of the exact
   `holdError`/`holdingSeatId` conflict path against a seat already held server-side (real
   `409` → `"That seat was just taken by someone else — please pick another."` rendered
   correctly) — the one behavior this migration could plausibly have broken silently, since
   it's the only state that changed from a plain field to a signal. Both test bookings were
   cancelled afterward to leave demo data clean.

---

## Rationale

The scope decision (Option B over C) follows the same "smallest complete, independently
checkable slice" discipline `docs/plan.md` has applied throughout this project — zoneless
itself is the complete, demonstrable unit here (measurable: no `zone.js` in the bundle, app
still functions); `OnPush` is a separate, purely internal optimization with no observable
behavior change at this app's scale, so bundling it in would only add review surface without
adding anything checkable.

Choosing to migrate at all (B/C over A) rather than deferring further follows from the audit
finding that this app is *already* almost entirely signal-driven — the two prior frontend
slices that introduced signals (`BookingStatusComponent` in Phase 4 Slice 3, then
`SeatMapComponent` in Phase 4 Slice 5) did so for reasons unrelated to zoneless at the time,
but happen to have left the codebase in a state where the remaining zoneless-incompatible
surface is exactly two fields in one component — about as low-risk as this migration ever
gets for this project.

---

## Consequences

### Positive
- No `zone.js` in the production bundle — one less global monkey-patching layer, and change
  detection runs only when something real changed rather than after every async callback
  app-wide.
- Fixes a real, previously-undiscovered latent bug (`holdError` not reliably rendering) as a
  side effect of the migration itself, rather than needing a separate bug-hunt.
- Aligns with Angular's own default direction (zoneless-by-default since v21, per ADR-013).

### Negative / Trade-offs
- Any *future* component or service that mutates plain (non-signal) state from inside an
  async callback and expects it to render will silently stop working the same way
  `SeatMapComponent` almost did — this is a standing discipline the codebase now has to
  maintain, not a one-time fix. Worth a one-line note in this ADR for future contributors
  rather than a lint rule, given the app's small size.
- `OnPush` stays unadopted for now (Option B), so a future contributor extending a component
  with genuinely expensive child subtrees won't get its skip-unrelated-subtrees benefit
  automatically — deferred, not forgotten, tracked here for a future slice if the app ever
  grows enough to need it.
