# E2E campaign — Group E (CSV imports, per-resource specifics)

Spec source: `e2e-scenarios.md` § **E. Imports CSV — spécificités par ressource** (E1–E7),
cross-checked against **Q-D. Imports CSV — réponses & erreurs de ligne**. Derived by grep of
`src/main/java/com/intermarche/valuation/imports/*.java` and the `domain` entities — never from
memory.

- **Class**: `src/test/java/com/intermarche/valuation/e2e/GroupEIT.java` — **written from scratch**
  (no prior GroupEIT existed).
- **Tier**: every E scenario is **unmarked** → `@QuarkusTest` + RestAssured, pure HTTP, Basic
  `admin/admin`. No `[W]`/`[P]` scenarios in this letter, so **no Playwright, no `@Disabled`
  residue**.
- **Seed**: **no mirror seed needed.** Group E *tests the import endpoints themselves*, so each
  scenario imports its own throwaway prerequisites (stores/products/groups) through the very
  endpoints under test, with a per-scenario code prefix — the same self-contained philosophy as
  Group D. The 7-CSV mirror seed is for groups that *valuate against* the referential, not for E.
- **Command**: `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupEIT -DskipITs=false`
- **Result**: **Tests run: 27, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS.**
- **Iterations to green**: 2 (first run: 26/27; one E4 assertion flipped after calibration — see
  below).

## Scenarios derived, by catalog id and facet (tier: all RestAssured)

| Scenario | @Test method | What it pins |
|---|---|---|
| E1 | `e1_upsertByCodeCoordinatesSilentlyOptional` | upsert by `code`; `safeParseDouble` swallows bad lat/lon → `null`, row still created; valid coords on re-import → `updatedCount:1`, persisted |
| E1 | `e1_emptyNameRejected` | empty `name` → `Line 2 (E1NONAME):` isolated, 0 created (constraint text commit-wrapped) |
| E2 | `e2_additiveStoreLinkingNeverRemoves` | **additive**: amputated re-import keeps the dropped store link (found by predicate) |
| E2 | `e2_unknownStoreRejected` | `Store 'E2NOSUCH' not found.` (verbatim), rolled back |
| E2 | `e2_unknownSubGroupOrderingGuard` | `StoreGroup 'E2NOSUCHGRP' not found. Check CSV order (Parent must be defined before Child).` |
| E2 | `e2_selfContainmentAccepted` | a group **can** contain itself — no `cannot contain itself` guard (contrast E4) |
| E3 | `e3_activeEmptyDefaultsToFalse` | blank `active` ⇒ **`false`** (the major seed trap) |
| E3 | `e3_unknownProductTypeRejected` | unknown `productType` → `null` → `Line 2 (E3BADTYPE):` isolated, 0 created |
| E4 | `e4_existingFamilyUpdateCountedAndPersistedHere` | **CALIBRATION**: catalog's frozen non-persistence defect does NOT reproduce here (see below) |
| E4 | `e4_unknownProductEanRejected` | `Product EAN 'E4NOSUCHEAN' not found.` |
| E4 | `e4_unknownSubFamilyRejected` | `SubFamily code 'E4NOSUCHFAM' not found.` |
| E4 | `e4_selfReferenceRejected` | `Family 'E4SELF' cannot contain itself.` (the guard E2 lacks) |
| E5 | `e5_midLevelChangeDetectedAsUpdate` | key `(ean, level1, level5)`; a `level2`-only change is still `updatedCount:1` and persisted |
| E5 | `e5_unknownEanQuoted` | `Product with EAN 'E5NOSUCH' not found.` — **WITH** quotes |
| E6 | `e6_endDateChangeUpdatesInPlace` | same composite key, new `endDateTime` → `updatedCount:1`, still one row |
| E6 | `e6_priorityChangeCreatesAdditionalPrice` | different `priority` → new key → 2 overlapping prices |
| E6 | `e6_invalidUsageRejected` | `PriceUsage is mandatory` (common substring, either spelling), 0 written |
| E6 | `e6_unknownEanUnquoted` | `Product with EAN E6NOSUCH not found.` — **WITHOUT** quotes (asymmetry vs E5) |
| E6 | `e6_emptyPriorityRejected` | blank `priority` → `Line 2 (E6PNOPRIO):` isolated (NULL-not-allowed at commit), 0 written |
| E6 | `e6_nonIsoStartDateNulled` | non-ISO `startDateTime` → WARN + `null`, row still created |
| E7 | `e7_noTargetRejected` | `Line 2: Must define at least one store_code or store_group_code.` (line-numbered) |
| E7 | `e7_unknownStoreTargetRejected` | `Store code 'E7NOSUCH' not found.` (offer-specific spelling) |
| E7 | `e7_unknownGroupTargetRejected` | `StoreGroup code 'E7NOSUCHGRP' not found.` |
| E7 | `e7_unknownTypeAcceptedAndStored` | canary: nonsense `type` accepted & persisted verbatim |
| E7 | `e7_invalidSpecificationJsonRejected` | `Failed to parse specification for Offer E7SPEC:` (thrown from `@PrePersist`) |
| E7 | `e7_specificationTooLongRejected` | valid JSON > 1000 chars → `Line 2 (E7LONG):` isolated (column-length at commit) |
| E7 | `e7_targetReplacementReLinks` | **replacement** (clear + re-link) — opposite of E2's additive strategy |

**27 tests, one row per facet.** Every E1–E7 catalog behavior and every Q-D E-surface message is
covered.

## Calibration findings (catalog vs observed reality)

1. **E4 frozen defect does NOT reproduce (the one flip to green).** The catalog pins a frozen bug:
   an existing family's update is counted (`updatedCount:1`) but *never persisted* because the
   pre-fetched entity is detached and never re-attached. Observed: the change **is** persisted
   (`First Description` → `Second Description`). Root cause: the family fetched in
   `processChunkWithFallback` remains **managed** in the request-scoped Hibernate session that the
   later `withTransaction` commit flushes (the `EntityManager.clear()` runs only *after* the
   commit). The test asserts the observed durable state and documents the écart in-code and here.
   This is the sole reason iteration 1 was 26/27.

2. **Direct vs commit-wrapped error lines** (as Group D already calibrated). Errors thrown as
   `IllegalArgumentException`/`RuntimeException` *inside* `processLineLogic` (E2/E4/E5/E6-EAN/E7
   not-found, `cannot contain itself`, `Must define…`, `Failed to parse…`) surface **verbatim** in
   the malformed `errors[]` body and are asserted literally. Bean-validation / column-length
   failures fire only **at commit** and are wrapped by the Narayana wrapper (`ARJUNA016053…`), so
   for E1 empty name, E3 mandatory type, E6 empty priority and E7 over-long spec only the
   deterministic `Line N (<code>):` prefix and the zero-row DB outcome are pinned — never the
   wrapped constraint text. (E6 empty priority actually surfaces as an H2 `NULL not allowed for
   column "PRIORITY"` at commit; still wrapped, still zero rows.)

3. **E6 usage-message spelling.** The bulk chunk throws `PriceUsage is mandatory at column 5`
   (`feedPrice`) but the *definitive* `errors[]` line is produced by the 1-by-1 fallback, whose
   `retrievePrices` throws the shorter `PriceUsage is mandatory`. The catalog lists both; the
   assertion pins the common substring so it holds whichever staged path records the line.

4. **The three "not found" spellings are genuinely distinct and all confirmed by code + runtime**:
   price store lookup would say `Store with code <c> not found.`, offer says `Store code '<c>' not
   found.`, store-group says `Store '<c>' not found.`; and EAN is **quoted** for categories
   (`'<ean>'`) but **unquoted** for prices (`<ean>`). Both asymmetries are asserted positively.

## Pitfalls handled

- **HashSet membership**: `Offer.stores`, `StoreGroup.stores`, `StoreGroup.storeGroups` read by
  predicate on `code`/`ean`, never by index (E2, E7 replacement).
- **Managed/detached staging**: E2 additive and E7 replacement force the checksum to differ (rename
  / changed store set) so the clean update path re-attaches the entity, avoiding reliance on the
  fragile checksum-equal + `LazyInitializationException` fallback path.
- **Line numbers**: every isolated-row assertion uses a header + single data row, so the faulty
  physical line is deterministically `2`.
- **Self-containment vs self-reference**: E2 asserts a store-group loop is *accepted* (no guard)
  while E4 asserts a family loop is *rejected* (`cannot contain itself`) — the deliberate contrast.

## Residue

**None.** All 27 scenarios are live RestAssured tests; no `@Disabled`, no `[P]`/`[W]` residue in
this letter.

## Scope

Touched only `src/test/java/com/intermarche/valuation/e2e/GroupEIT.java` and this report. No
`src/main` change. No bug or testability obstacle found (the E4 "defect" is a catalog/reality
discrepancy, documented, not a code fix).
