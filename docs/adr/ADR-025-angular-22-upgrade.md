# ADR-025: Angular 21 → 22 Upgrade, new defaults kept, webpack chain removed

**Status:** Accepted
**Date:** 2026-10-05
**Authors:** Engineering Team

---

## Context

The frontend ran Angular 21.2.25 with TypeScript 5.9.3 (ADR-013, zoneless since 2026-08-28).
Two open items were waiting on Angular 22:

- **The `piscina` override.** `@angular/build` and `@angular-devkit/build-angular` 21.2.24 pin
  `piscina` at exactly 5.2.0 (critical advisory GHSA-67c8-pqhq-4rmx, fixed in 5.3.2), so
  ADR-024 added an npm `overrides` entry, to be removed with Angular 22.
- **The `braces` findings.** `npm audit` reported 7 high findings around `braces`
  (GHSA-vfj7-8cjw-p6xm, no fixed version exists). They come only through `webpack-dev-server` in
  `@angular-devkit/build-angular`, which `angular.json` still used for two builders: `dev-server`
  and `extract-i18n`. `application` and `unit-test` already ran on `@angular/build` (ADR-013).

Checked on npm on 2026-10-05: `@angular/*` 22.2.1 is `latest` (22.3 is only `next`).
`@angular/build` 22.2.1 requires TypeScript `>=6.0 <6.1` and depends on `piscina` 5.3.2. The CLI's
Node engines are `^22.22.3 || ^24.15.0 || >=26.0.0`, which CI (Node 24) and the frontend's build
image (`node:26-alpine`) meet.

Angular 22 changes three defaults that matter here:
- **Components without `changeDetection` are OnPush.** `ng update` adds
  `ChangeDetectionStrategy.Eager` to every such component to keep the old behavior.
- **`HttpClient` uses the Fetch API.** `ng update` adds `withXhr()` to `provideHttpClient()`.
- **Two extended diagnostics are on** (`nullishCoalescingNotNullable`, `optionalChainNotNullable`).
  `ng update` suppresses them in `tsconfig.app.json` and `tsconfig.spec.json`.

## Decision

- **Upgrade to Angular 22.2.1 and TypeScript 6.0.3** through `ng update @angular/core@22
  @angular/cli@22`, as one slice (ADR-024: Angular majors aren't a Dependabot merge).
- **Keep the new defaults and drop the three compatibility shims** that `ng update` added:
  - **OnPush.** The app has been zoneless since 2026-08-28, and all five components keep their
    state in signals, so an OnPush view gets marked for check by every signal write, template
    event and `interval` tick it depends on. `Eager` would only preserve a behavior the app no
    longer relies on.
  - **Fetch instead of XHR.** The app uses no upload or download progress events, the one feature
    `withXhr()` exists for.
  - **The extended diagnostics stay on.** Without the suppression, the build reports no warning.
- **`dev-server` and `extract-i18n` move to `@angular/build`**, and `@angular-devkit/build-angular`
  is removed from `devDependencies`. All four builders now come from `@angular/build`, and the
  webpack chain (with `webpack-dev-server` and `braces`) is gone.
- **The `piscina` override is removed.** `@angular/build` 22.2.1 brings `piscina` 5.3.2 itself.

## Verification (local, 2026-10-05)

- `npm audit`: **0 vulnerabilities**. `npm ls piscina` shows 5.3.2 under `@angular/build`, without
  an override.
- `ng build` without warnings, `ng test` 34/34 green, `node ci.js frontend` PASS (with coverage).
- `ng serve` on the new `dev-server` builder, proxying `/api` to the gateway. A reduced compose
  stack (Postgres, Redis, Kafka, event-service, booking-service, api-gateway), checked in Chrome:
  - Event list renders all 13 seeded events.
  - Seat map: toggling a section updates `aria-expanded`. A hold made from outside the page
    turns one seat from available to held within 2.5 s, through the WebSocket, without a reload.
  - Clicking a seat holds it and navigates to the booking page, whose countdown keeps ticking
    (9:51 → 9:47).
  - No console errors.

## Consequences

**Positive**
- The `piscina` override and all `npm audit` findings are gone.
- One build package (`@angular/build`, Vite and esbuild) instead of two. webpack no longer gets
  installed.
- No compatibility shims to remove later: the code follows Angular 22's defaults.

**Negative / accepted**
- **OnPush is now implicit.** A future component that keeps state in plain fields and changes it
  from an async callback won't re-render. The convention stays: component state goes in signals.
- **TypeScript 6** is stricter. It reported nothing in this codebase, but future code may trip
  deprecation diagnostics sooner.
- **The browser check covered the frontend's pages, not the full saga.** Payment, notification and
  waitlist weren't running: the full stack plus the dev server ran this machine out of memory. The
  frontend doesn't talk to them directly, and their code didn't change.

---

---

# ADR-025: Upgrade von Angular 21 → 22, neue Standardwerte beibehalten, Webpack-Kette entfernt

**Status:** Akzeptiert  
**Datum:** 2026-10-05  
**Autoren:** Engineering-Team

---

## Kontext

Das Frontend lief mit Angular 21.2.25 und TypeScript 5.9.3 (ADR-013, zoneless seit 2026-08-28).
Zwei offene Punkte warteten auf Angular 22:

- **Das `piscina`-Override.** `@angular/build` und `@angular-devkit/build-angular` 21.2.24 pinnen
  `piscina` auf exakt 5.2.0 (kritischer Advisory GHSA-67c8-pqhq-4rmx, behoben in 5.3.2), daher
  ergänzte ADR-024 einen npm-Eintrag `overrides`, der mit Angular 22 entfernt werden sollte.
- **Die `braces`-Funde.** `npm audit` meldete 7 hohe Funde rund um `braces`
  (GHSA-vfj7-8cjw-p6xm, es gibt keine behobene Version). Sie kommen nur über `webpack-dev-server`
  in `@angular-devkit/build-angular`, das `angular.json` noch für zwei Builder nutzte:
  `dev-server` und `extract-i18n`. `application` und `unit-test` liefen bereits auf
  `@angular/build` (ADR-013).

Am 2026-10-05 auf npm geprüft: `@angular/*` 22.2.1 ist `latest` (22.3 ist nur `next`).
`@angular/build` 22.2.1 verlangt TypeScript `>=6.0 <6.1` und hängt von `piscina` 5.3.2 ab. Die
Node-Engines der CLI sind `^22.22.3 || ^24.15.0 || >=26.0.0`, was CI (Node 24) und das Build-Image
des Frontends (`node:26-alpine`) erfüllen.

Angular 22 ändert drei Standardwerte, die hier eine Rolle spielen:
- **Komponenten ohne `changeDetection` sind OnPush.** `ng update` fügt jeder solchen Komponente
  `ChangeDetectionStrategy.Eager` hinzu, um das alte Verhalten beizubehalten.
- **`HttpClient` nutzt die Fetch-API.** `ng update` fügt `withXhr()` zu `provideHttpClient()` hinzu.
- **Zwei erweiterte Diagnosen sind an** (`nullishCoalescingNotNullable`,
  `optionalChainNotNullable`). `ng update` unterdrückt sie in `tsconfig.app.json` und
  `tsconfig.spec.json`.

## Entscheidung

- **Upgrade auf Angular 22.2.1 und TypeScript 6.0.3** über `ng update @angular/core@22
  @angular/cli@22`, als ein Slice (ADR-024: Angular-Major-Versionen sind kein Dependabot-Merge).
- **Die neuen Standardwerte beibehalten und die drei Kompatibilitäts-Shims entfernen,** die
  `ng update` hinzugefügt hat:
  - **OnPush.** Die App ist seit 2026-08-28 zoneless, und alle fünf Komponenten halten ihren
    Zustand in Signals, daher wird eine OnPush-Ansicht durch jeden Signal-Schreibzugriff, jedes
    Template-Event und jeden `interval`-Tick, von dem sie abhängt, zur Prüfung markiert. `Eager`
    würde nur ein Verhalten bewahren, auf das sich die App nicht mehr verlässt.
  - **Fetch statt XHR.** Die App nutzt keine Upload- oder Download-Fortschrittsereignisse, die
    einzige Funktion, für die `withXhr()` existiert.
  - **Die erweiterten Diagnosen bleiben an.** Ohne die Unterdrückung meldet der Build keine
    Warnung.
- **`dev-server` und `extract-i18n` wechseln zu `@angular/build`,** und
  `@angular-devkit/build-angular` wird aus den `devDependencies` entfernt. Alle vier Builder
  kommen nun aus `@angular/build`, und die Webpack-Kette (mit `webpack-dev-server` und `braces`)
  ist weg.
- **Das `piscina`-Override wird entfernt.** `@angular/build` 22.2.1 bringt `piscina` 5.3.2 selbst
  mit.

## Verifikation (lokal, 2026-10-05)

- `npm audit`: **0 Schwachstellen**. `npm ls piscina` zeigt 5.3.2 unter `@angular/build`, ohne
  Override.
- `ng build` ohne Warnungen, `ng test` 34/34 grün, `node ci.js frontend` PASS (mit Coverage).
- `ng serve` auf dem neuen `dev-server`-Builder, der `/api` an das Gateway proxyt. Ein
  verkleinerter Compose-Stack (Postgres, Redis, Kafka, event-service, booking-service,
  api-gateway), in Chrome geprüft:
  - Die Event-Liste stellt alle 13 gesäten Events dar.
  - Sitzplan: Das Umschalten eines Bereichs aktualisiert `aria-expanded`. Eine von außerhalb der
    Seite gemachte Reservierung lässt einen Sitz innerhalb von 2,5 s über den WebSocket ohne
    Neuladen von verfügbar auf reserviert wechseln.
  - Ein Klick auf einen Sitz reserviert ihn und navigiert zur Buchungsseite, deren Countdown
    weiterläuft (9:51 → 9:47).
  - Keine Konsolenfehler.

## Konsequenzen

**Positiv**
- Das `piscina`-Override und alle Funde von `npm audit` sind weg.
- Ein Build-Paket (`@angular/build`, Vite und esbuild) statt zwei. Webpack wird nicht mehr
  installiert.
- Keine Kompatibilitäts-Shims, die später entfernt werden müssen: Der Code folgt den
  Standardwerten von Angular 22.

**Negativ / akzeptiert**
- **OnPush ist jetzt implizit.** Eine künftige Komponente, die Zustand in einfachen Feldern hält
  und ihn aus einem asynchronen Callback ändert, wird nicht neu gerendert. Die Konvention bleibt:
  Komponentenzustand gehört in Signals.
- **TypeScript 6** ist strenger. Es meldete in dieser Codebasis nichts, aber künftiger Code könnte
  früher auf Deprecation-Diagnosen stoßen.
- **Die Browserprüfung deckte die Seiten des Frontends ab, nicht die vollständige Saga.** Payment,
  Notification und Waitlist liefen nicht: Der vollständige Stack plus Dev-Server brachte diese
  Maschine an die Speichergrenze. Das Frontend spricht nicht direkt mit ihnen, und ihr Code hat
  sich nicht geändert.
