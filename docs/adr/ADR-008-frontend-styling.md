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
