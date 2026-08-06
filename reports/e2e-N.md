# E2E Group N — Traces & administration des valorisations

Class **written** (new): `src/test/java/com/intermarche/valuation/e2e/GroupNIT.java`.
Campaign: `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupNIT -DskipITs=false`.
Result: **16 tests run, 0 failures, 0 errors, 3 skipped** (the [W] browser residues). BUILD SUCCESS.
Iterations to green: **2** (one calibration fix on N5, below).

Mirror seed: **needed**. The 200/500 arms and every list/detail/replay probe require a priced
catalog, so the seven CSV imports are replayed ONCE at class start in the mandated order
(Stores → StoreGroups → Products → ProductFamilies → Categories → Prices → Offers).

## Scenarios derived (catalog section N + Q-E "Valuations" surface)

| Id | Tier | Test method | Notes |
|----|------|-------------|-------|
| N1 | RestAssured + injected recorder | `n1_successTraceContentAndReserializedRequest`, `n1_rejectedTraceContent`, `n1_failedNoPayloadTraceContent`, `n1_failedWithPayloadAndTruncation` | 200/400/500 over HTTP; 422+payload & 1997-truncation via injected `ValuationTraceService.record` |
| N2 | RestAssured | `n2_disableLeavesNoTraceBannerAndImmediateReenable` | disable → zero trace + banner + immediate re-enable |
| N3 | RestAssured | `n3_configValidationAndRoleGuards` | retention≥1 notice, success notice, VIEWER/MANAGER 403 on config+purge |
| N4 | RestAssured + injected recorder | `n4_manualPurgeAll`, `n4_scheduledPurgeRemovesExpiredKeepsFresh` | manual purge 303; scheduled purge via antedated `createdAt` + direct `purgeExpired()` |
| N5 | RestAssured (+ [W] residue) | `n5_listFiltersBadgesAndEmptyState`, `n5_rowsRefreshFragment`, `n5_autoRefreshTimer` (@Disabled) | filters/badges/empty + `/rows` data source; 1 s timer is residue |
| N6 | RestAssured (+ [W] residue) | `n6_detailMetaErrorBannerNoResponseAndUnknownId`, `n6_readableJsonClientRendering` (@Disabled) | meta/error banner/no-response/303 unknown id; client Readable render is residue |
| N7 | RestAssured (+ [W] residue) | `n7_testFormSubmissions`, `n7_replayPreloadAndSilentEmpty`, `n7_formJsonToggle` (@Disabled) | empty/400/success + trace creation, replay preload & silent empty; Form/JSON toggle is residue |

## Calibration findings (catalog vs observed reality)

- **N1 422 unreachable via HTTP** — confirmed against Group G's G3 calibration: the basic offer
  applier is a catch-all consuming every priced line, so a schema-valid basket empties the
  working set to a **200**, every unconsumable state is barred by the schema to a **400**, and a
  configuration fault throws first to a **500**. The `422 FAILED with responsePayload` arm and the
  `>2000`-char → `1997 + "..."` truncation are therefore driven through the injected
  `ValuationTraceService.record` with a **genuine** `engine.evaluate` result — the only faithful
  way to reach the recorder's 422 branch. Verified: `responsePayload` present, `errorMessage`
  length exactly 2000 ending `...`.
- **N1 requestPayload re-serialized** — a `mysteryField` sent over the wire is dropped by JAX-RS
  deserialization (`additionalProperties` is permissive at the schema, but `Basket` has no field
  for it), so the re-serialized `NON_NULL` payload never carries it. Pinned directly.
- **N5 filter value is echoed** — the customer/store search inputs render `value="{filter}"`, so a
  raw `body.contains("<customer>")` is a **false positive on the empty page** (the filter text is
  present even with zero rows). Fixed by asserting on the row **badge** (`badge-ok">Success`, only
  emitted inside a table row) for the positive filters, and on the empty-state message
  (`No valuation recorded yet.`) for the strict-status mismatch. This was the single red→green fix.
- **N2 disable semantics** — `enabled = (formParam != null)`: an unchecked box sends no `enabled`
  param, so omitting it turns recording off; re-sending `enabled=on` restores it. Config is
  re-read on every `record` call, so the very next valuation is traced without a restart —
  verified by counting traces for `N2ON`.
- **Redirect notices** — `UriBuilder.queryParam` URL-encodes spaces as `+`; asserted with
  `containsString` on `Tracing+configuration+updated.`, `The+retention+must+be+at+least+one+day.`,
  `Valuation+999999999+no+longer+exists.` and `noticeOk=true/false`. The purge count `{n}` is
  suite-shared, so only `deleted.` + `noticeOk=true` are matched, with the emptied table proven by
  a Panache `count() == 0`.

## Pitfalls handled

- **Shared JVM state** — the trace config is one row and the trace table is suite-wide. Every
  recording probe forces `enabled=true` through Panache first; N2 restores it in a `finally`; N3
  restores retention to 1; N4's manual purge legitimately empties the table (each probe
  records-then-asserts on its own `customerCode` within one sequential method, so a later purge
  never races an earlier assertion). `DateTimeProvider` is cleared in a `finally` after the
  scheduled-purge antedating.
- **4xx/5xx bodies carry no entity** — all trace-content assertions read `valuation_traces`
  columns via Panache under `QuarkusTransaction`, keyed by a unique `customerCode`, never a raw
  body. Redirects use `redirects().follow(false)` and match `Location` with `containsString`.
- **`/rows` is a fragment** — asserted it carries the matching row but omits the page chrome
  (`<h1>Valuations</h1>`), pinning the auto-refresh **server data source** without a browser.

## Justified residue (3 × @Disabled, all [W] browser-only)

- `n5_autoRefreshTimer` — the 1 s `tbody` auto-refresh (suspended on `document.hidden`, no doubled
  in-flight requests) lives in `valuation-refresh.js`. Its server source is covered by
  `n5_rowsRefreshFragment`; the DOM/timer logic belongs to the Vitest suite.
- `n6_readableJsonClientRendering` — the `Readable`/`JSON` tab rendering and the real em-dash
  placeholder for a missing `lineId` are produced by `valuation-view.js` (client-side); not
  reachable through server-rendered HTML.
- `n7_formJsonToggle` — the `Form`/`JSON` mode switch, client schema-form generation and the
  malformed-JSON toggle-cancellation live in `schema-form.js`.

No Playwright harness / `quarkus-playwright` dependency exists in the build, and adding one is out
of this task's scope (touch only `GroupNIT.java`, its resources and this report); the full **server
surface** of the [W] scenarios is covered over HTTP, leaving only the irreducibly-browser
behaviours as residue.

## Scope

Only `GroupNIT.java` and this report were added. Nothing under `src/main` was modified. No new
seed/golden resources were required (the existing `seed/*.csv` mirror suffices).
