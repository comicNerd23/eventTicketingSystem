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

---

---

# ADR-014: Zoneless Change Detection

**Status:** Akzeptiert  
**Datum:** 2026-08-28  
**Autoren:** Engineering-Team

---

## Kontext

ADR-013 (Angular-Upgrade 20 → 21) hat die Zoneless-Migration bewusst als eigenen Folge-Slice
zurückgestellt, da sie verändert, wie Change Detection in jeder Komponente läuft, und kein
Tooling-/Build-Austausch ist. Dieses ADR behandelt den zurückgestellten Slice: ob und wie
`provideZonelessChangeDetection()` übernommen und `zone.js` aus dem Frontend entfernt wird.

### Was Zoneless tatsächlich ändert
Heute ruft `app.config.ts` `provideZoneChangeDetection({ eventCoalescing: true })` auf, und das
`build`-Target in `angular.json` führt `zone.js` unter `polyfills`. Zone.js funktioniert, indem es
jede asynchrone Browser-API (`setTimeout`, `Promise`, DOM-Events, `XMLHttpRequest`, `WebSocket`
usw.) per Monkey-Patching verändert, sodass Angular nach dem Auslösen *irgendeiner* davon einen
vollständigen Change-Detection-Durchlauf ausführen kann, ohne dass die Anwendung sagen muss, was
sich geändert hat. Zoneless entfernt diesen Patch vollständig; Change Detection läuft stattdessen
nur, wenn etwas Angulars Scheduler ausdrücklich benachrichtigt – ein Signal-Schreibzugriff, ein
im Template gebundener Event-Handler, `AsyncPipe` oder ein expliziter `ChangeDetectorRef`-Aufruf.

### Audit der Codebasis – durch Lesen jeder Komponente geprüft, nicht angenommen
Alle drei echten Feature-Komponenten wurden vollständig gelesen, um zu prüfen, ob sie bereits zu
diesem Modell passen:

- **`EventListComponent`** – zu 100 % signalgesteuert (`eventPage`, `loadError`), jeder
  HTTP-Callback ruft nur `.set(...)` auf. Bereits zoneless-sicher.
- **`BookingStatusComponent`** – zu 100 % signalgesteuert (`booking`, `error`, `confirming`,
  `cancelling`, `remainingSeconds`, `totalHoldSeconds` sowie berechnete Signals für
  Countdown/Dringlichkeit/Fortschrittsbalken). Der 1-Sekunden-Countdown läuft über ein
  RxJS-`interval(1000)`, aber der Callback ruft nur Signal-Setter auf – dass der *Timer* nicht
  mehr zone-gepatcht ist, spielt keine Rolle, da Signal-Schreibzugriffe Angulars Scheduler
  direkt benachrichtigen, unabhängig davon, was den Callback eingeplant hat. Bereits
  zoneless-sicher.
- **`SeatMapComponent`** – überwiegend signalgesteuert (`data`, `loadError`, `openSections`),
  und ihr nativer `WebSocket.onmessage`-Handler (auf den ersten Blick ein wirklich
  zonenabhängig aussehendes Muster) läuft bereits über `this.data.set(...)`, ist also ebenfalls
  in Ordnung. **Aber** sie hat zwei einfache, nicht-signalbasierte Instanzfelder –
  `holdingSeatId: string | null` und `holdError: string | null` –, die direkt in `selectSeat()`
  verändert werden, auch innerhalb des *asynchronen* Callbacks
  `bookingApi.holdSeat(...).subscribe({ error: ... })` (`seat-map.component.ts:147-149`).
  `holdError` wird direkt im Template gelesen (`seat-map.component.html:31`, per grep bestätigt).
  Heute funktioniert das nur, weil zone.js die asynchrone Grenze des HTTP-Callbacks patcht und
  danach einen CD-Durchlauf auslöst. **Unter Zoneless aktualisiert sich die Ansicht hier
  tatsächlich nicht mehr** – ein echter, konkreter Fehler, den diese Migration beheben muss,
  kein hypothetisches Risiko.
- `AppComponent`/`NotFoundComponent` haben überhaupt keinen dynamischen Zustand – für diese
  Migration irrelevant.

**Es gibt kein offizielles automatisiertes Schematic für diese Migration** – ein Schematic-Name
(`ng generate @angular/core:zoneless-migration`) tauchte bei der ersten Recherche auf, wurde aber
direkt gegen die installierte Schematics-Sammlung von `@angular/core` geprüft, genau wie ADR-013
einen ähnlich behaupteten Schematic-Namen für Karma→Vitest geprüft (und widerlegt) hat, und er
existiert auch in der Sammlung von Angular 21.2.22 nicht. `provideZonelessChangeDetection()`
selbst ist real und stabil (`@publicApi 20.2`, bestätigt in den eigenen Typdeklarationen von
`@angular/core`) – nur die Behauptung zum Migrations-Tooling war falsch. Das muss eine kleine
Handmigration sein.

### Option A: Zonenbasiert bleiben
Keine Änderung.

**Stärken:**
- Null Risiko, null Aufwand.

**Schwächen:**
- Verpasst die echten Vorteile von Zoneless für diese App: kein zone.js im Bundle (eine
  Polyfill-Patch-Schicht von etwa 30–40 kB weniger), schnellere/vorhersehbarere Change Detection
  (kein Durchlauf des gesamten Baums bei jedem DOM-Event, Timer oder jeder HTTP-Antwort – nur bei
  den etwa 3 Signals, die sich tatsächlich geändert haben), und es ist die Richtung, auf die sich
  Angular selbst festgelegt hat (Zoneless ist laut der Recherche in ADR-013 der Standard für alle
  neuen v21-Projekte).
- Lässt die Lücke bei `holdingSeatId`/`holdError` in `SeatMapComponent` unentdeckt – sie
  funktioniert heute nur, weil zone.js sie überdeckt; zonenbasiert zu bleiben behebt den latenten
  Fehler nicht, sondern hält ihn nur unsichtbar.

### Option B: Zu Zoneless migrieren, minimaler Umfang
`provideZoneChangeDetection(...)` → `provideZonelessChangeDetection()` tauschen, `zone.js` aus den
Polyfills in `angular.json` und aus `package.json` entfernen und die eine tatsächlich gefundene
Zoneless-Inkompatibilität beheben (`holdingSeatId`/`holdError` → Signals). Die
`changeDetection`-Strategie jeder Komponente bleibt beim aktuellen Standard (`Default`,
implizit), statt im selben Durchgang überall explizit `OnPush` zu ergänzen.

**Stärken:**
- Die kleinste Änderung, die Zoneless tatsächlich liefert – entspricht der Konvention testbarer
  Slices, angrenzende, aber trennbare Verbesserungen nicht in einen Slice zu bündeln (dieselbe
  Überlegung, mit der ADR-012 Tracing/Logging/Alarmierung und ADR-013 Signal Forms/Aria
  zurückgestellt haben).
- Die `Default`-Strategie funktioniert auch zoneless korrekt – sie bedeutet nur, dass Angular
  eine Komponente in einem von einem Vorfahren ausgelösten, geplanten Durchlauf *prüfen würde*,
  statt unabhängige Teilbäume wie `OnPush` zu überspringen. Da diese App nur 3 kleine Komponenten
  tief ist und keinen gemeinsamen Zustand im Elternteil hat, ist der Leistungsunterschied
  vernachlässigbar.

**Schwächen:**
- Lässt `OnPush` als unfertige Modernisierung zurück – wer den Code später liest, könnte sich
  zu Recht fragen, warum Zoneless ohne es übernommen wurde.

### Option C: Zu Zoneless migrieren und im selben Slice jeder Komponente explizit `OnPush` geben
Wie Option B, zusätzlich `changeDetection: ChangeDetectionStrategy.OnPush` an
`EventListComponent`, `SeatMapComponent`, `BookingStatusComponent`, `AppComponent`.

**Stärken:**
- „Schließt“ die Modernisierung in einem Durchgang ab – kein Folgeschritt nötig.

**Schwächen:**
- Vergrößert den Wirkungsradius dieses Slices ohne messbaren Nutzen bei der Größe dieser App
  (4 Komponenten, keine mit aufwendigen Teilbäumen) – das tatsächliche Verhalten ist mit Zoneless
  und signalgesteuertem Zustand in jeder Komponente identisch zur `Default`-Strategie aus Option
  B, da es keine Kosten für das erneute Prüfen unabhängiger Geschwister zu vermeiden gibt, wenn
  praktisch nichts erneut zu prüfen ist, das nicht ohnehin von einem geänderten Signal abhängt.
- Dass `OnPush` in Angular 22 der *Standard* für neue Komponenten wird (laut der Recherche in
  ADR-013 dazu, warum dieses Projekt bei 21 blieb), bedeutet, dass es jetzt von Hand zu ergänzen
  Arbeit doppelt, die ein künftiges ADR „Angular 22 erwägen“ ohnehin neu aufgreifen würde.

---

## Entscheidung

Option B – Migration zu Zoneless mit `provideZonelessChangeDetection()`, `zone.js` entfernen und
`holdingSeatId`/`holdError` in `SeatMapComponent` als erforderliche Korrektheitsbehebung (nicht
als optionale Politur) in Signals umwandeln. `OnPush` wird ausdrücklich zurückgestellt.

Umgesetzt und am selben Tag verifiziert, an dem dieses ADR entworfen wurde, nach einem
Review-Checkpoint zur untenstehenden Änderungsliste (gemäß der ADR-vor-Umsetzung-Konvention und
der Checkpoint-Regel testbarer Slices dieses Projekts).

### Konkrete Änderungsliste (umgesetzt)
1. `frontend/src/app/app.config.ts`: `provideZoneChangeDetection({ eventCoalescing: true })`
   → `provideZonelessChangeDetection()`.
2. `frontend/angular.json`: `"zone.js"` aus dem `polyfills`-Array des `build`-Targets entfernen.
3. `frontend/package.json`: die Abhängigkeit `zone.js` entfernen.
4. `frontend/src/app/events/seat-map.component.ts`: `holdingSeatId`/`holdError` werden zu
   `signal<string | null>(null)`, mit `.set(...)` statt direkter Zuweisung; Template
   (`seat-map.component.html`) und `seatClass()` rufen sie als Funktionen auf.
5. Testseite: bestätigt, dass kein Test und kein `TestBed.configureTestingModule`-Aufruf im Repo
   `NgZone` oder zone-gepatchtes Timing referenzierte, abgesehen von dem einen `fakeAsync`-Fall,
   den ADR-013 bereits durch `vi.useFakeTimers()` ersetzt hat – es waren keine Teständerungen
   nötig.
6. Vollständiger Neulauf von `ng build` + `ng test` (weiterhin 32/32 bestanden, keine
   Änderungen am Testinhalt) sowie eine Live-Browserprüfung (Headless Chrome über rohes CDP – die
   Erweiterung war in dieser Sitzung nicht verbunden) gegen den echten laufenden Backend-Stack,
   gezielt auf die zwei riskantesten Pfade: ein echter synthetischer Klick auf einen echten
   `AVAILABLE`-Sitz (echtes `POST /bookings/hold` → Erfolg → `router.navigate` → echte
   Buchungsseite mit Live-Countdown, alles mit zuvor bestätigtem `window.Zone === undefined`),
   und eine erzwungene Wiederholung genau des Konfliktpfads `holdError`/`holdingSeatId` gegen
   einen bereits serverseitig reservierten Sitz (echtes `409` → `"That seat was just taken by
   someone else — please pick another."` korrekt dargestellt) – das eine Verhalten, das diese
   Migration plausibel stillschweigend hätte brechen können, da es der einzige Zustand ist, der
   von einem einfachen Feld zu einem Signal wechselte. Beide Test-Buchungen wurden anschließend
   storniert, um die Demodaten sauber zu hinterlassen.

---

## Begründung

Die Umfangsentscheidung (Option B statt C) folgt derselben Disziplin „kleinster vollständiger,
unabhängig überprüfbarer Slice“, die `docs/plan.md` im gesamten Projekt anwendet – Zoneless selbst
ist hier die vollständige, vorführbare Einheit (messbar: kein `zone.js` im Bundle, die App
funktioniert weiterhin); `OnPush` ist eine separate, rein interne Optimierung ohne beobachtbare
Verhaltensänderung im Maßstab dieser App, sie mitzunehmen würde also nur Review-Fläche hinzufügen,
ohne etwas Überprüfbares zu ergänzen.

Überhaupt zu migrieren (B/C statt A), statt weiter zurückzustellen, folgt aus dem Audit-Ergebnis,
dass diese App *bereits* fast vollständig signalgesteuert ist – die beiden vorherigen
Frontend-Slices, die Signals einführten (`BookingStatusComponent` in Phase 4 Slice 3, dann
`SeatMapComponent` in Phase 4 Slice 5), taten das damals aus Gründen, die nichts mit Zoneless zu
tun hatten, haben die Codebasis aber zufällig in einen Zustand gebracht, in dem die verbleibende
Zoneless-inkompatible Fläche genau zwei Felder in einer Komponente umfasst – so risikoarm, wie
diese Migration für dieses Projekt nur sein kann.

---

## Konsequenzen

### Positiv
- Kein `zone.js` im Produktions-Bundle – eine globale Monkey-Patching-Schicht weniger, und Change
  Detection läuft nur, wenn sich wirklich etwas geändert hat, statt nach jedem asynchronen
  Callback in der gesamten App.
- Behebt als Nebeneffekt der Migration selbst einen echten, bisher unentdeckten latenten Fehler
  (`holdError` wurde nicht zuverlässig dargestellt), ohne separate Fehlersuche.
- Entspricht Angulars eigener Standardrichtung (Zoneless-by-Default seit v21, laut ADR-013).

### Negativ / Abwägungen
- Jede *künftige* Komponente oder jeder Service, der einfachen (nicht-Signal-)Zustand innerhalb
  eines asynchronen Callbacks verändert und erwartet, dass er dargestellt wird, wird
  stillschweigend genauso aufhören zu funktionieren, wie es `SeatMapComponent` beinahe passiert
  wäre – das ist eine dauerhafte Disziplin, die die Codebasis nun aufrechterhalten muss, keine
  einmalige Korrektur. Bei der geringen Größe der App ist ein einzeiliger Hinweis in diesem ADR
  für künftige Mitwirkende sinnvoller als eine Lint-Regel.
- `OnPush` bleibt vorerst unübernommen (Option B); wer künftig eine Komponente mit tatsächlich
  aufwendigen Kind-Teilbäumen erweitert, erhält dessen Vorteil des Überspringens unabhängiger
  Teilbäume nicht automatisch – zurückgestellt, nicht vergessen, hier für einen künftigen Slice
  vermerkt, falls die App je so weit wächst, dass es nötig wird.
