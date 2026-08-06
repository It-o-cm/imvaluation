# imvaluation — JavaScript inventory

Read-only inventory of every piece of JavaScript shipped by the application.
Nothing was modified. Two sources of JS exist:

1. **Static `.js` files** served from `src/main/resources/META-INF/resources/ui/`.
2. **Inline `<script>` blocks** in the Qute templates under `src/main/resources/templates/`.

**Headline finding:** the Qute templates contain **no inline JavaScript logic**. Every
`<script>` tag is either a `src` reference to a static file or a `type="application/json"`
data island consumed by those files. All executable JS lives in the 5 static files below.
The only inline behaviour is three trivial `onsubmit="return confirm(...)"` guards.

---

## 1. Static `.js` files

| File (`.../ui/`) | Size | Lines | Role (one line) |
|---|---|---|---|
| `list-filters.js` | 6.7 KB | 205 | Autocomplete inputs for list filters + CSV import button trigger. |
| `valuation-refresh.js` | 1.3 KB | 39 | Polls the valuation list table body once a second and swaps rows in place. |
| `valuation-view.js` | 9.7 KB | 227 | Renders a valuation request/response JSON into readable HTML with a JSON toggle. |
| `schema-form.js` | 40 KB | 1086 | Schema-driven offer specification editor (widgets generated from JSON Schema). |
| `store-group-workbench.js` | 24 KB | 722 | In-memory store-group hierarchy editor (drag/drop, select, save/revert). |

## 2. Inline `<script>` blocks in Qute templates

| Template | Block | Length | What it does |
|---|---|---|---|
| `StoreGroupUiResource/workbench.html` | `#wb-model` | 1 line | JSON data island: serialized hierarchy model. No logic. |
| `StoreGroupUiResource/workbench.html` | `#wb-can-write` | 1 line | JSON data island: write-permission flag. No logic. |
| `StoreGroupUiResource/workbench.html` | `src=store-group-workbench.js` | 1 line | Loads the workbench script. |
| `ValuationUiResource/list.html` | `src=list-filters.js` | 1 line | Loads the filters script. |
| `ValuationUiResource/list.html` | `src=valuation-refresh.js` | 1 line | Loads the refresh script. |
| `ValuationUiResource/detail.html` | `src=valuation-view.js` | 1 line | Loads the view script. |
| `ValuationUiResource/test.html` | `#offer-schemas` | 1 line | JSON data island: offer schemas. No logic. |
| `ValuationUiResource/test.html` | `#offer-specification` | 1 line | JSON data island: request payload. No logic. |
| `ValuationUiResource/test.html` | `src=schema-form.js` | 1 line | Loads the schema-form script. |
| `OfferUiResource/form.html` | `#offer-schemas` | 1 line | JSON data island: offer schemas. No logic. |
| `OfferUiResource/form.html` | `#offer-specification` | 1 line | JSON data island: current specification (or `{}`). No logic. |
| `OfferUiResource/form.html` | `src=schema-form.js` | 1 line | Loads the schema-form script. |
| `OfferUiResource/list.html` | `src=list-filters.js` | 1 line | Loads the filters script. |

**Inline event handlers (not `<script>`, but the only other inline JS):**

| Template | Handler | What it does |
|---|---|---|
| `UserUiResource/list.html:88` | `onsubmit="return confirm(...)"` | Delete-account confirmation guard. |
| `OfferUiResource/list.html:109` | `onsubmit="return confirm(...)"` | Delete-offer confirmation guard. |
| `ValuationUiResource/list.html:41` | `onsubmit="return confirm(...)"` | Delete-all-valuations confirmation guard. |

Templates carrying **no** JS at all: `AuthUiResource/login.html`, `AuthUiResource/password.html`,
`page.qute.html`, `ui/layout.html`, `UserUiResource/form.html`.

---

## 3. Classification — pure logic vs DOM/network wiring

Each identifiable unit (function or region) is placed in one of two columns:

- **Pure logic — testable:** deterministic input → output; calculation, formatting,
  validation, or in-memory data manipulation. Testable with plain JS unit tests, no DOM,
  no network, no globals.
- **Wiring — DOM/network:** creates elements, attaches listeners, calls `fetch`, mutates
  `innerHTML`, reads `document`/`window`. Needs a DOM/network harness to test.

### `list-filters.js`

| Pure logic — testable | Wiring — DOM/network |
|---|---|
| `ENDPOINTS`, `PLACEHOLDERS` maps (constants) | `el(tag, className, text)` — `document.createElement` |
| `debounce(fn, delay)` — timing combinator | `lookup(endpoint, query)` — `fetch` |
| | `buildFilterInput(host)` — builds input, listeners, submit-on-pick |
| | `init()` — `querySelectorAll` wiring |
| | import IIFE (`initImport`) — button/file-input/form listeners |

**Verdict:** overwhelmingly wiring. Only `debounce` and the constant maps are cleanly pure.

### `valuation-refresh.js`

| Pure logic — testable | Wiring — DOM/network |
|---|---|
| *(none)* | Whole file: `getElementById`, `fetch`, `setInterval`, `innerHTML` swap, `document.hidden` guard |

**Verdict:** 100% wiring. Nothing to unit-test in isolation.

### `valuation-view.js` — richest pure-logic surface

| Pure logic — testable | Wiring — DOM/network |
|---|---|
| `esc(value)` — HTML escaping | `build(kind)` — reads DOM, `JSON.parse`, sets `innerHTML` |
| `euro(value)` — number → `"12.34 €"` / dash | `wireToggle()` — tab click listeners, class toggles |
| `rate(value)` — fraction → `"20.0%"` / dash | top-level `build("request"/"response")`, `wireToggle()` calls |
| `amountCells(amount)` — amount trio → `<td>` HTML | |
| `renderRequest(basket)` — request JSON → HTML string | |
| `renderAdvantage(adv)` — advantage → HTML row (shape dispatch) | |
| `renderResponse(res)` — response JSON → HTML string | |

**Verdict:** 7 pure functions form ~80% of the file — a strong candidate for exhaustive
string-output unit tests (rounding, null/NaN dashes, gesture dispatch, advantage variants).

### `schema-form.js` — mixed; pure resolution/formatting core

| Pure logic — testable | Wiring — DOM/network |
|---|---|
| `CONVENTIONS`, `LOOKUP`, `UNITS` maps + regex tests | `el`, `debounce` (debounce is pure; el is DOM) |
| `resolveWidget(name, schema)` — 3-step widget resolution | `lookup()` — `fetch` |
| `resolveByType(schema)` — JSON type → widget | `buildAutocomplete(options)` — chips/dropdown/listeners/`fetch` |
| `labelOf(name, schema)` — property name → label | `buildField` / `buildControl` dispatch — build DOM controllers |
| number scale/parse logic inside `buildNumberControl.read()` (rate ↔ fraction, `toFixed` trimming) — *pure but embedded in a DOM builder* | `buildLookup/Enum/Boolean/Number/Text/StringList/EanQuantityMap/Object/ObjectList Control` — element + listener builders |
| the `read()` result-assembly logic (skip `undefined`, `filled` flags) — *pure but embedded* | `readForm`, `syncHidden`, `validate`, `onFieldChange`, `renderForm`, `switchMode`, `initTargetWidgets`, `init` — DOM/JSON-island wiring |

**Verdict:** the widget-resolution and label logic (`resolveWidget`, `resolveByType`,
`labelOf` + the convention regexes) is cleanly pure and high-value to test. The rate↔fraction
scaling and the `read()` assembly rules are pure *arithmetic/validation* but currently trapped
inside DOM builders — testable only if the harness constructs controllers, or if that logic
were extracted.

### `store-group-workbench.js` — pure model core behind a DOM shell

| Pure logic — testable | Wiring — DOM/network |
|---|---|
| `group(code)` — lookup by code | `el(...)` — `document.createElement` |
| `roots()` — root groups, sorted, from child sets | `render` / `renderTree` / `renderStores` / `renderPending` |
| `wouldCycle(parentCode, childCode)` — cycle detection (DFS) | `groupNode(...)` — recursive DOM tree + drag listeners |
| `isDirty()` — model vs pristine snapshot compare | `buildRemoveButton` — SVG button + click listener |
| `storeByCode(code)` — lookup by code | `confirmRemoval` mixes pure counting with `window.confirm` |
| `assignStores` / `detachStore` / `moveGroup` / `createGroup` / `removeGroup` — model mutations (pure on `model`, but each calls `render()` / `showError`) | `renderStores` filter + assigned-set (reads DOM input) |
| shift-click range math inside `toggleSelection` — *pure but reads DOM for the visible order* | `toggleSelection`, `makeDropTarget`, `startRename`, drag/drop handlers |
| | `showError` / `clearError`, `save()` (`fetch`), `revert()`, `init()` (JSON islands, `beforeunload`) |

**Verdict:** the graph/model layer — `wouldCycle`, `roots`, `isDirty`, `group`,
`storeByCode`, and the mutation functions — is genuinely pure data manipulation and the
best-value test target in the file. Testing the mutations in isolation requires either
stubbing the trailing `render()` call or extracting it; `wouldCycle`/`roots`/`isDirty`
operate on a plain `model` object and are directly testable as-is.

---

## 4. Summary

- **Pure, directly testable today (no DOM, no stubs):**
  `valuation-view.js` → `esc`, `euro`, `rate`, `amountCells`, `renderRequest`,
  `renderAdvantage`, `renderResponse`; `schema-form.js` → `resolveWidget`, `resolveByType`,
  `labelOf`, `debounce`; `store-group-workbench.js` → `group`, `roots`, `wouldCycle`,
  `isDirty`, `storeByCode`; `list-filters.js` → `debounce`.
- **Pure but embedded** (testable only after extraction or with a controller harness):
  the rate↔fraction scaling and `read()` assembly rules in `schema-form.js`; the model
  mutations and shift-range math in `store-group-workbench.js`.
- **Pure wiring** (needs a DOM/network harness): all `build*Control`, `render*`,
  `makeDropTarget`, drag/drop handlers, `fetch` calls, `init`, and the whole of
  `valuation-refresh.js`.
- **Templates:** zero inline JS logic; only `src` includes, JSON data islands, and three
  `onsubmit` confirm guards.

The highest-leverage unit-testing target is `valuation-view.js` (a self-contained
JSON→HTML formatter), followed by the model/graph functions of `store-group-workbench.js`
and the widget-resolution functions of `schema-form.js`.
