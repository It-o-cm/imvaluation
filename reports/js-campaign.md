# JS test campaign — admin UI coverage

Bench: **Vitest 2.1.9 + jsdom 25 + `@vitest/coverage-istanbul`**, wired into Maven's
`test` phase (`exec-maven-plugin`, skip with `-DskipJsTests=true`). No file under `src/main`
was modified. Istanbul **branch** coverage is the oracle; the target is 100% or a residue
justified line-by-line below.

## Result — all five browser sources

| Source | Branches | Statements | Functions | Tests | Fix iters |
|---|---|---|---|---|---|
| `valuation-view.js` | **75 / 75 (100%)** | 96 / 96 | 16 / 16 | 36 | 1 |
| `schema-form.js` | **279 / 280 (99.64%)** | 510 / 511 | 106 / 106 | 77 | 3 |
| `list-filters.js` | **32 / 35 (91.4%)** | 80 / 82 | 20 / 20 | 27 | 1 |
| `valuation-refresh.js` | **9 / 10 (90%)** | 17 / 18 | 5 / 5 | 7 | 1 |
| `store-group-workbench.js` | **195 / 202 (96.5%)** | 340 / 341 | 71 / 71 | 87 | 3 |
| **TOTAL** | **590 / 602 (98.0%)** | 1043 / 1048 | 218 / 218 | **234** | — |

- **Every function is covered (218/218).** All 234 tests are green in a single combined
  `npx vitest run --coverage`, and inside a single `mvn verify -DskipITs` alongside the
  1135 Java tests (**BUILD SUCCESS**).
- The 12 uncovered branches are **all justified unreachable or once-only code** (detailed
  below). None was skipped by lowering the bar; where a branch was reachable only through a
  second module evaluation, a behavioural test still exercises it — it simply does not move
  the Istanbul counter (see "The boot-dispatcher limitation").

## Plugin extension (done, not a workaround)

The calibration plugin only unwrapped a **single** IIFE with no top-level `return`. Three of
the four new files broke that shape, so the `expose-ui-iife` Vite plugin was **extended**
(never `src/main`, never the coverage bar) to a general **capture-injection** rewrite:

- it injects, right after each IIFE's `'use strict'`, `Object.assign(__ui_exports__, { …top-level fns… })`, and default-exports `__ui_exports__`;
- the IIFE is **kept** (not unwrapped), so `valuation-refresh.js`'s top-level `return`
  stays legal;
- **every** IIFE in a file is matched, so `list-filters.js`'s two IIFEs both expose their
  functions;
- the capture runs **before** the auto-boot, so the functions are exposed even when the
  IIFE later boots `init()` or returns early.

Every source is therefore exposable; no file was left un-testable.

## The boot-dispatcher limitation (4 of the 12 residues)

Every file except `valuation-view.js` ends with once-only DOM-ready glue:

```js
if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', init); }
else { init(); }
```

A module's top level runs **once**, at import, when jsdom's `readyState` is `'complete'` →
the `else → init()` arm is taken and covered. Covering the `'loading'` arm needs a **second**
evaluation. Each spec attempts it with `loadScript(path, { bust })` under an overridden
`readyState`; the behaviour is verified (the listener is attached, nothing throws), but
**Istanbul instruments the `?bust=…` import under a separate id and does not merge its
counters into the real file's single coverage entry** (confirmed: `coverage-final.json` holds
exactly one key per source). So the `'loading'` arm stays uncovered in the number. This is a
property of once-only bootstrap code under single-path instrumentation, not a gap in the
tests.

## Residues, file:line, justified

### `schema-form.js` — 1 residue
- **`:1082` loading arm** of the boot dispatcher. See above.

### `list-filters.js` — 3 residues
- **`:95`** `PLACEHOLDERS[kind] || 'Search…'` **right arm — dead code.** `ENDPOINTS` and
  `PLACEHOLDERS` share the same keys, and `buildFilterInput` early-returns (`:87`) for any
  kind without an endpoint, so a kind reaching `:95` always has a placeholder. Unreachable
  without editing `src/main`.
- **`:164` (IIFE-1)** and **`:200` (IIFE-2)** loading arms of the two boot dispatchers. See above.

### `valuation-refresh.js` — 1 residue
- **`:13` `if (!tbody) return;` true arm.** `tbody` (`#valuation-rows`) is read exactly once
  at import. The table-present path (needed for `refresh`, `baseUrl`, the `setInterval`) and
  the table-absent early return cannot coexist in one evaluation, and the cache-busted second
  evaluation does not merge (see above). The absent-table path is still exercised
  behaviourally.

### `store-group-workbench.js` — 7 residues
- **`:77`, `:99`, `:161`, `:201`, `:441`** — the `(g.childCodes || [])` / `(g.storeCodes || [])`
  **right arms.** `init()` unconditionally normalizes every group at `:682–683`
  (`g.storeCodes = g.storeCodes || []; g.childCodes = g.childCodes || []`) before any of these
  run, and no mutation ever removes those arrays, so the left operand is always a truthy
  array. Defensive dead code; no exposed path injects a group lacking the arrays.
- **`:563`** — the implicit `else` after `else if (dragging.kind === 'group')` in the drop
  handler. `dragging.kind` is only ever set to `'stores'` or `'group'` by the dragstart
  handlers; the third case is unreachable.
- **`:718`** loading arm of the boot dispatcher. See above.

## Pitfalls encountered (per file)

**Cross-cutting** — module state (element refs, `schemas`/`model`, closures) binds at
**import** because the IIFE auto-runs `init()`; specs build the full DOM and mock
`global.fetch` **before** `loadScript`, keep those nodes for the whole suite, and re-invoke
exported functions to drive later branches. `vi.useFakeTimers()` is installed before import
where the module schedules `setInterval`/`setTimeout` at boot.

- **`valuation-view.js`** — pure formatters asserted to the exact character; a missing
  `lineId` renders `&amp;mdash;` because `esc` escapes the `&` — asserted as-is, not fixed.
- **`schema-form.js`** — a pending debounced search re-showed the dropdown after `blur`
  (`clearAllTimers` before asserting); jsdom's `type=number` rejects non-numeric input, so
  `buildNumberControl.read()`'s `isNaN` guard is reachable only after flipping the node to
  `type=text`; rate scaling `0.2 ↔ '20'` asserted both ways; `object`/`object-list`/
  `ean-quantity-map` build nested `.field` nodes, so assertions count the grid's direct
  children; `validate`'s `|| … && !value.length` needed a required field whose read value is
  a filled array.
- **`list-filters.js`** — two IIFEs both exposed by the extended plugin; jsdom does not
  implement `HTMLFormElement.submit` (stubbed with `vi.fn`); `input.files` is read-only
  (faked via `Object.defineProperty` to cover the `files && files.length` arms); debounce
  (180 ms) and blur-hide (150 ms) driven with `advanceTimersByTimeAsync`.
- **`valuation-refresh.js`** — fake timers installed before import (the `setInterval` is
  scheduled at boot); `window.location.search` set via `history.pushState` (a direct set
  trips jsdom navigation); `document.hidden` overridden per test; the mocked fetch's promise
  chain flushed with a microtask helper; the `inFlight` guard exercised with a pending
  fetch promise; the `tbody` element is reused, never rebuilt.
- **`store-group-workbench.js`** — `init()` re-seeds `model`/`canWrite`/`pristine` but not
  `selection`/`collapsed`/`anchor`/`dragging`, so a `boot(model, canWrite)` helper rebuilds
  a fresh DOM skeleton + re-runs `init()`, and an `afterEach` fires `dragend` to force
  `dragging` back to `null`; jsdom has no `DataTransfer` (a stub `{ effectAllowed: '' }` is
  attached to dispatched drag `Event`s); `window.confirm` stubbed; `beforeunload` asserted
  via a cancelable event's `defaultPrevented`; `canWrite` tested both `true` and `false`.

## Reproduce

```bash
npm install                 # once
npx vitest run --coverage   # 234 tests, 590/602 branches
mvn verify -DskipITs        # Java 1135 + JS 234, one build, BUILD SUCCESS
```
