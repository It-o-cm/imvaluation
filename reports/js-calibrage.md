# JS test bench — calibration on `valuation-view.js`

Bench: **Vitest 2.1.9 + jsdom 25 + `@vitest/coverage-istanbul`**, wired into Maven's
`test` phase via `exec-maven-plugin`. No file under `src/main` was modified.

## Result

| Metric | Value |
|---|---|
| Spec | `src/test/js/valuation-view.spec.js` |
| Tests | **36 passing** (one `describe` per function) |
| **Branches** | **75 / 75 (100%)** |
| Statements | 95 / 95 (100%) |
| Functions | 16 / 16 (100%) |
| Fix iterations on the spec | **1** (green on the first `vitest run`) |
| `mvn verify -DskipITs` | **BUILD SUCCESS** — Java 1135/0/0, JS 36/36 |
| `-DskipJsTests=true` | JS skipped (`skipping execute as per configuration`), build still green |

The branch count is reported as **75/75**, not only the 100%: every `describe` block drives
both arms of each guard and ternary — the `== null` / `isNaN` pairs of `euro`/`rate`, the
four gesture arms of `renderRequest` (forced / discount amount / discount percent / none),
the four shapes of `renderAdvantage` (+ its `type ||` and `offer ||` fallbacks), the
`offer.amount && …` and `items || []` arms of `renderResponse`, the `!src || !panel` /
`try…catch` / `kind === "request" ? …` arms of `build`, and the active-tab / missing-panel
arms of `wireToggle`.

## Files created

| File | Role |
|---|---|
| `package.json` | Dev deps (vitest, jsdom, coverage-istanbul), `type: module`. |
| `vitest.config.js` | jsdom env, specs glob, istanbul branch coverage on `…/ui/*.js`, + the unwrap plugin. |
| `src/test/js/harness.js` | `loadScript(relativePath)` → imports a source and returns its top-level functions. |
| `src/test/js/valuation-view.spec.js` | The 36-test, 75-branch spec. |
| `pom.xml` | `exec-maven-plugin` execution `js-tests` bound to `test`, skip flag `-DskipJsTests=true`. |

## Pitfalls encountered

### 1. The sources are IIFEs, not modules — and must stay intact
Every `…/ui/*.js` file wraps its logic in `(function () { … })();` and exports nothing; the
functions are private closures. They are served verbatim to the browser, so they cannot be
edited to add exports. The harness therefore makes them importable **in memory only**: a
Vite `transform` plugin (`expose-ui-iife`, `enforce: 'pre'`) strips the single outer IIFE
and appends `export { … }` listing the top-level functions (detected by the repo's 4-space
indent convention). The file on disk is untouched; `loadScript` just `import()`s the real
path and gets a normal namespace object back.

### 2. `readFileSync` + `eval` would report **0% coverage** (the known blindspot)
The instinctive harness — read the file and `new Function(body)()` — works for *calling* the
functions but bypasses Vite's transform pipeline entirely, so Istanbul never instruments the
code and coverage comes back empty. This is the JS twin of the JaCoCo attribution blindspot
already noted for this project. The fix is to route the source **through** Vite (via
`import()` of its real path) so the coverage provider instruments it and attributes the
result to `src/main/resources/META-INF/resources/ui/valuation-view.js`. Anticipating this is
why the spec was green — with real coverage — on the first run.

### 3. Unwrap, don't re-wrap (block scoping)
An early idea was to replace the IIFE with a bare `{ … }` block. In a strict ES module that
block-scopes the inner `function` declarations, so `export { esc }` fails to resolve. The
wrapper must be **removed outright** so the declarations land at module top level.

### 4. `all: false` in coverage
The unwrap handles the *single*-IIFE, no-top-level-`return` shape. `list-filters.js` (two
IIFEs) and `valuation-refresh.js` (top-level `return`) would break that naive transform, so
coverage `all` is left `false`: only sources an imported spec actually pulls in are
instrumented. Calibrating on `valuation-view.js` alone is therefore safe; extending the
unwrap is the next step for those files.

### 5. jsdom specifics
`build` and `wireToggle` touch `document`. Under the jsdom environment the IIFE's trailing
`build("request"); build("response"); wireToggle();` runs once at import against an empty
document — harmless because each is null-guarded. Tests then set `document.body.innerHTML`
per case and call the exported functions directly. `element.click()` dispatches a real event
so the `wireToggle` listeners fire.

### 6. A real behaviour worth pinning (not a bug to fix here)
A missing `lineId` renders as `esc(it.lineId || "&mdash;")`, and `esc` escapes the `&`, so
the cell contains the literal `&amp;mdash;` rather than an em dash. The spec asserts the
**actual** output (`<td>&amp;mdash;</td>`) — reported, not changed, per the src/main scope
rule.

### 7. Maven wiring
`exec-maven-plugin:exec` bound to `test` runs `npx vitest run --coverage` from the project
root. It honours `<skip>${skipJsTests}</skip>` so `-DskipJsTests=true` disables it. A single
`mvn verify -DskipITs` runs Java (surefire) then JS (exec) in one pass. Prerequisite: JS deps
installed once with `npm install` (the exec resolves the local `vitest` binary via `npx`; it
does not install for you).

## Proposed `CLAUDE.md` addition (NOT applied)

The following lines are proposed for a new **"JS tests"** section of `CLAUDE.md`. They are
listed here only; nothing was written to `CLAUDE.md`.

```markdown
## JS tests — plain unit tests on the browser sources
- Bench: Vitest + jsdom + @vitest/coverage-istanbul. Specs in
  src/test/js/**/*.spec.js. Run with `npx vitest run --coverage`, or
  as part of `mvn verify` (exec-maven-plugin, test phase). Skip with
  `-DskipJsTests=true`. Install deps once with `npm install`.
- The sources under META-INF/resources/ui/*.js are browser IIFEs, not
  ES modules, and are served verbatim: NEVER edit them to add exports.
  They are made importable in memory by the `expose-ui-iife` Vite
  plugin in vitest.config.js; tests reach their functions via
  loadScript() from src/test/js/harness.js.
- NEVER load a source with readFileSync + eval/new Function: it runs
  the code but bypasses Vite, so Istanbul reports 0% coverage. Always
  go through loadScript()/import so coverage is attributed to the real
  file path.
- Coverage target: 100% JaCoCo-equivalent BRANCH coverage on the
  target file. Cover BOTH arms of every guard, ternary and `||`/`&&`
  fallback. Always report the branch count (n/n), not only the %.
- Style mirrors the Java suite: one describe per function, absolute
  expected values (assert to the cent for money/rate formatting),
  JSDoc on every helper and test, English only, no AssertJ-style
  chains beyond Vitest's expect.
- Split logic vs wiring: pure formatters (esc/euro/rate/render*) are
  asserted to the exact string; DOM wiring (build/wireToggle) is
  driven against a jsdom document set per test via document.body.
- Assert the ACTUAL output, including quirks (e.g. a missing lineId
  renders `&amp;mdash;` because esc escapes the `&`). Report quirks
  in one line; never fix them under src/main during a test campaign.
- The in-memory unwrap currently handles the single-IIFE, no-top-level
  -return shape only. list-filters.js (two IIFEs) and
  valuation-refresh.js (top-level return) need the plugin extended
  before they can be imported; coverage `all` is kept false meanwhile.
```
