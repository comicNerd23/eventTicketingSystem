# ADR-008: Frontend Styling — Tailwind CSS

**Status:** Accepted
**Date:** 2026-07-29
**Authors:** Engineering Team

---

## Context

The Angular frontend (Phase 4) has three real views so far — the events list, the
SVG seat map, and the booking-status flow — all styled with small, hand-written
per-component CSS files (`event-list.component.css`, `seat-map.component.css`,
`booking-status.component.css`). That's fine for correctness, but a portfolio
project is judged partly on visual polish, which ad hoc CSS doesn't demonstrate
well as the app grows. Several options were considered for how to style the rest
of Phase 4:

### Option A: Keep hand-written CSS per component
No new dependency; the current approach.

**Strengths:**
- Zero build-pipeline changes, nothing new to learn or configure.
- Full control over every rule.

**Weaknesses:**
- Doesn't scale — every new view repeats the same layout/spacing/color decisions
  from scratch, with no shared design vocabulary.
- Doesn't demonstrate any frontend-tooling skill beyond raw CSS, which is a real
  goal of this project (same reasoning ADR-007 applied to the backend gateway
  choice).

### Option B: Angular Material
Angular's own component library — buttons, cards, dialogs, tables, form fields —
integrated natively with Angular's module/DI system.

**Strengths:**
- Native Angular integration; demonstrates ecosystem fluency with the framework
  this project is already built on.
- Accessible, pre-built components with consistent theming out of the box.

**Weaknesses:**
- Heaviest option — pulls in a full component library and its own theming system
  for an app that doesn't (yet) have much form-heavy or data-table UI, which is
  where Material's components earn their weight.
- Its component-level styling would fight the app's custom-rendered elements —
  most notably the SVG seat map, which isn't a Material component and would need
  to be hand-styled around Material's design language anyway.
- A more Angular-specific skill than a broadly transferable one.

### Option C: Tailwind CSS
A utility-first CSS framework — styling is done via composable utility classes
directly in markup, with no component library or JS runtime involved.

**Strengths:**
- Lightweight: works purely at the CSS layer (via a PostCSS plugin), no component
  library or extra JS to load, and only the utilities actually used end up in the
  compiled CSS.
- Skill is transferable well beyond Angular — one of the most broadly expected
  frontend skills across stacks, not tied to this framework specifically.
- Fits this app's shape well: most of the UI so far is custom-rendered (the SVG
  grid, status badges, a countdown) rather than form-heavy, and utility classes
  skin custom markup directly without fighting a component library's own opinions.
- Angular's esbuild-based `application` builder (already in use — see
  `angular.json`) picks up a PostCSS config automatically, so integration is a
  small, well-trodden addition rather than a new build tool.

**Weaknesses:**
- Templates accumulate longer `class` attributes than either alternative — a real
  readability trade-off, not free.
- Another devDependency and build-pipeline integration point (`@tailwindcss/postcss`).

---

## Decision

We adopt **Tailwind CSS** (v4) — Option C.

---

## Rationale

Between the three options, Option A stops scaling as more Phase 4 views get built,
and Option B's strengths (native component library, form/table-heavy UI) don't
match what this app actually is — mostly custom-rendered views (SVG seat map,
status-driven booking flow) where a component library's opinions would need to be
worked around rather than leveraged. Tailwind styles that kind of custom markup
directly, stays framework-agnostic (a skill that transfers outside Angular
specifically), and integrates as a small PostCSS addition to the build Angular
already runs, rather than a new framework layer.

---

## Consequences

### Positive
- One consistent utility vocabulary across all frontend views going forward,
  instead of each component re-deriving its own spacing/color/typography choices.
- No component-library runtime or theming system to configure around the app's
  custom-rendered elements (the SVG seat map in particular).
- Broadly transferable skill, not Angular-specific.

### Negative / Trade-offs
- Templates carry more utility classes inline, which is a genuine verbosity
  trade-off against the alternative of shorter semantic class names backed by a
  separate stylesheet — accepted here because the previous three slices already
  established each view's structure/logic; this only touches presentation.
- Status-identifying class names already used by existing logic and tests
  (`seat-available`, `seat-held`, `seat-booked`, `seat-holding` on the seat map)
  stay exactly as they are — Tailwind utilities are added alongside them, not
  used to replace them, so this decision doesn't ripple into component logic.

---

---

# ADR-008: Frontend-Styling – Tailwind CSS

**Status:** Akzeptiert  
**Datum:** 2026-07-29  
**Autoren:** Engineering-Team

---

## Kontext

Das Angular-Frontend (Phase 4) hat bisher drei echte Ansichten – die Event-Liste, den
SVG-Sitzplan und den Buchungsstatus-Ablauf –, alle gestylt mit kleinen, handgeschriebenen
CSS-Dateien pro Komponente (`event-list.component.css`, `seat-map.component.css`,
`booking-status.component.css`). Für die Korrektheit genügt das, aber ein Portfolio-Projekt
wird teils nach visueller Politur beurteilt, die Ad-hoc-CSS mit wachsender App nur schlecht
zeigt. Für das Styling der restlichen Phase 4 wurden mehrere Optionen betrachtet:

### Option A: Handgeschriebenes CSS pro Komponente beibehalten
Keine neue Abhängigkeit; der aktuelle Ansatz.

**Stärken:**
- Keine Änderungen an der Build-Pipeline, nichts Neues zu lernen oder zu konfigurieren.
- Volle Kontrolle über jede Regel.

**Schwächen:**
- Skaliert nicht – jede neue Ansicht wiederholt dieselben Layout-/Abstands-/Farbentscheidungen
  von Grund auf, ohne gemeinsames Gestaltungsvokabular.
- Zeigt keine Frontend-Tooling-Kenntnisse über reines CSS hinaus, was ein echtes Ziel dieses
  Projekts ist (dieselbe Begründung wie in ADR-007 für die Gateway-Wahl im Backend).

### Option B: Angular Material
Angulars eigene Komponentenbibliothek – Buttons, Karten, Dialoge, Tabellen, Formularfelder –
nativ in Angulars Modul-/DI-System integriert.

**Stärken:**
- Native Angular-Integration; zeigt Vertrautheit mit dem Ökosystem des Frameworks, auf dem
  dieses Projekt bereits aufbaut.
- Barrierefreie, vorgefertigte Komponenten mit einheitlichem Theming von Haus aus.

**Schwächen:**
- Die schwerste Option – zieht eine vollständige Komponentenbibliothek samt eigenem
  Theming-System für eine App herein, die (noch) wenig formular- oder tabellenlastige UI hat,
  wo Materials Komponenten ihr Gewicht rechtfertigen.
- Das Komponenten-Styling würde mit den selbst gerenderten Elementen der App kollidieren –
  vor allem mit dem SVG-Sitzplan, der keine Material-Komponente ist und ohnehin von Hand um
  Materials Designsprache herum gestylt werden müsste.
- Eher eine Angular-spezifische als eine breit übertragbare Fähigkeit.

### Option C: Tailwind CSS
Ein Utility-First-CSS-Framework – das Styling erfolgt über kombinierbare Utility-Klassen direkt
im Markup, ohne Komponentenbibliothek und ohne JS-Laufzeit.

**Stärken:**
- Leichtgewichtig: arbeitet rein auf CSS-Ebene (über ein PostCSS-Plugin), keine
  Komponentenbibliothek oder zusätzliches JS zu laden, und nur die tatsächlich verwendeten
  Utilities landen im kompilierten CSS.
- Die Fähigkeit ist weit über Angular hinaus übertragbar – eine der am breitesten erwarteten
  Frontend-Fähigkeiten über Stacks hinweg, nicht an dieses Framework gebunden.
- Passt gut zur Form dieser App: Der Großteil der bisherigen UI ist selbst gerendert (das
  SVG-Raster, Status-Badges, ein Countdown) und nicht formularlastig, und Utility-Klassen stylen
  eigenes Markup direkt, ohne gegen die Vorgaben einer Komponentenbibliothek zu kämpfen.
- Angulars esbuild-basierter `application`-Builder (bereits im Einsatz – siehe `angular.json`)
  übernimmt eine PostCSS-Konfiguration automatisch; die Integration ist also eine kleine,
  gut begangene Ergänzung statt eines neuen Build-Werkzeugs.

**Schwächen:**
- Templates sammeln längere `class`-Attribute an als bei beiden Alternativen – ein echter
  Kompromiss bei der Lesbarkeit, nicht umsonst.
- Eine weitere devDependency und ein weiterer Integrationspunkt in der Build-Pipeline
  (`@tailwindcss/postcss`).

---

## Entscheidung

Wir führen **Tailwind CSS** (v4) ein – Option C.

---

## Begründung

Unter den drei Optionen skaliert Option A nicht mehr, sobald weitere Ansichten in Phase 4
entstehen, und Option Bs Stärken (native Komponentenbibliothek, formular-/tabellenlastige UI)
passen nicht zu dem, was diese App tatsächlich ist – überwiegend selbst gerenderte Ansichten
(SVG-Sitzplan, statusgesteuerter Buchungsablauf), bei denen man die Vorgaben einer
Komponentenbibliothek umgehen statt nutzen müsste. Tailwind stylt solches eigenes Markup
direkt, bleibt Framework-unabhängig (eine Fähigkeit, die auch außerhalb von Angular
übertragbar ist) und fügt sich als kleine PostCSS-Ergänzung in den bereits laufenden
Angular-Build ein, statt eine neue Framework-Schicht einzuziehen.

---

## Konsequenzen

### Positiv
- Ein einheitliches Utility-Vokabular über alle künftigen Frontend-Ansichten, statt dass jede
  Komponente ihre eigenen Abstands-/Farb-/Typografie-Entscheidungen neu ableitet.
- Keine Komponentenbibliotheks-Laufzeit und kein Theming-System, das um die selbst gerenderten
  Elemente der App (insbesondere den SVG-Sitzplan) herum konfiguriert werden müsste.
- Breit übertragbare Fähigkeit, nicht Angular-spezifisch.

### Negativ / Abwägungen
- Templates tragen mehr Utility-Klassen inline, was ein echter Kompromiss bei der
  Ausführlichkeit gegenüber kürzeren semantischen Klassennamen in einem separaten Stylesheet
  ist – hier akzeptiert, weil die vorherigen drei Slices Struktur und Logik jeder Ansicht
  bereits etabliert haben; das hier betrifft nur die Darstellung.
- Statusbezeichnende Klassennamen, die bestehende Logik und Tests bereits nutzen
  (`seat-available`, `seat-held`, `seat-booked`, `seat-holding` am Sitzplan), bleiben exakt
  erhalten – Tailwind-Utilities werden zusätzlich dazugesetzt, nicht als Ersatz, sodass diese
  Entscheidung nicht in die Komponentenlogik hineinwirkt.
