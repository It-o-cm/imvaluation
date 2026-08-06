# E2E campaign — Group F (GraphQL, `POST /graphql`, Basic)

**Class:** `src/test/java/com/intermarche/valuation/e2e/GroupFIT.java` — **written from scratch** (did not
previously exist).
**Command:** `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupFIT -DskipITs=false`
**Result:** `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
**Iterations to green:** 2 (1 write + 1 calibration pass).
**Mirror seed:** **not needed.** Every scenario builds its own throwaway entities through the very
GraphQL mutations under test, under per-scenario code/EAN prefixes (`F2…`, `F7…`), so the class needs
no CSV replay. The single-role F1 accounts are created directly through Panache (as Group C does for C4).

## Scenarios derived and tier

All 11 scenarios are **RestAssured** (pure HTTP GraphQL over Basic auth). Group F has **no** `[W]`, `[P]`
or `[D]` marks, so there is **no `@Disabled` residue** — every scenario is implemented and green.

| Id | Test method | Tier | What it pins |
|----|-------------|------|--------------|
| F1 | `f1_securityMatrixQueriesManagerMutationsAdmin` | RestAssured | Queries=MANAGER, mutations=ADMIN; both senses of the flat-role C4 trap on `allStores`/`createStore`. |
| F2 | `f2_creationConflictsExactMessages` | RestAssured | The 11 exact `AlreadyExistsException` conflict texts (store code/name, product ean/name, group code/name, family code/**description**, offer code, price composite key, storage link). |
| F3 | `f3_notFoundRenderingCalibrated` | RestAssured | Not-found rendering — **the catalog unknown, now pinned**: unwrapped `NoSuchElementException` is MASKED as `"System error"`. |
| F4 | `f4_maskedMessages` | RestAssured | Catch-all generics `An error occurred during <op>.` (no-target offer, group/family self-ref on update, invalid `productType`). |
| F5 | `f5_partialUpdates` | RestAssured | `null` = unchanged (can't reset to null); `[]` = emptying → a **targetless offer** with no revalidation. |
| F6 | `f6_immutableKeys` | RestAssured | `code` ignored on `updateOffer`/`updateStoreGroup`/`updateProductFamily`; sibling field applied. |
| F7 | `f7_deletions` | RestAssured | Missing id → `false`; FK violation on referenced store; **`deleteProductFamily` cascade ALL** destroys linked products + sub-families. |
| F8 | `f8_priceViciousCases` | RestAssured | `currentPrice` → `null` no error; duplicate with null `startDateTime` undetected (two rows); null-start `updatePrice` masked NPE. |
| F9 | `f9_createStoreGroupAsymmetry` | RestAssured | Unknown sub-group on create vs update. |
| F10 | `f10_indirectCyclesAccepted` | RestAssured | A→B then B→A accepted; `allStoreGroups` survives (visited sets). |
| F11 | `f11_offersByStoresAndTypeDuplicate` | RestAssured | Duplicate in `storeCodes` mistaken for a missing store. |

## Calibration findings — catalog vs observed reality

The dominant finding governs F3, F7, F9 and F11. **SmallRye masks every exception that ESCAPES
`GraphQLTrait.execute()` as the literal generic `"System error"`** (`extensions.classification =
DataFetchingException`, operation field resolved to `null`). Only what `execute()` itself converts to a
`GraphQLException` keeps its message. The dividing line is *where the throw is caught*, not the exception
type.

- **F3 — the flagged unknown, resolved.** `NoSuchElementException` is deliberately re-thrown UNWRAPPED,
  which the catalog *hoped* would give a "clean specific message". Observed: because it is not a
  `GraphQLException`, SmallRye **masks it as `"System error"`**. The clean text (`Store with id 999999
  not found`) lives only in the server log. Pinned as `"System error"` for id, code and product-id shapes.
- **F9 — two écarts.** (a) An unknown sub-group on **create** is NOT rejected: `findByCode` returns null,
  `storeGroups.add(null)` is called, and Hibernate **silently drops the null** → the create SUCCEEDS with
  no sub-group link (catalog expected a "generic error"). (b) The unknown sub-group on **update** IS
  checked and throws `NoSuchElementException`, but — exactly like F3 — it renders as `"System error"`, not
  the catalog's clean `StoreGroup with code '<c>' not found.`.
- **F11 — écart.** The duplicate-code size trap throws `One or more Store codes provided do not exist.`,
  but again masked to `"System error"`. The trap is still proven by the error being raised at all (a real
  match would have returned `data` with an empty list).
- **F7 — écart.** The catalog expects the FK violation on a referenced store to surface as the persistence
  generic `Database error while performing deleteStore. Please check your data.`. Observed: the constraint
  fires at **COMMIT**, after `execute()` has returned, so the `PersistenceException` handler never sees it;
  the `RollbackException` escapes and is masked as `"System error"`. The persistence generic is only
  reachable for a `PersistenceException` thrown *inside* the lambda (not exercised by this delete path).

The GraphQLException-wrapped messages (thrown *inside* the lambda) surface verbatim as the catalog quotes,
and are asserted to the exact text: **F2** (all conflicts), **F4** (all four generics), **F8** (the
null-start `updatePrice` NPE → `An error occurred during updatePrice.`).

## Pitfalls encountered (and fixed)

- **Mandatory `Offer.specification`.** `createOffer` calls that must SUCCEED need a
  `specification: "{}"`: the entity's `@NotBlank` fires at COMMIT otherwise, and (per the finding above)
  masks the intended outcome as `"System error"`. Added to F2/F5/F6 success-path offers. F4's no-target
  offer deliberately omits it — it fails earlier, *inside* the lambda, on the target guard, so the
  intended `An error occurred during createOffer.` is preserved.
- **Scalar mutation selection set.** `deleteStore`/`deleteProductFamily` return `boolean`; the GraphQL
  document must NOT carry a selection set (`mutation { deleteStore(id: …) }`, no braces).
- **Enum & scalar literals.** `priceUsage: DEFAULT` is an unquoted GraphQL enum; `startDateTime` is an ISO
  string; `BigDecimal` money fields are numbers.
- **Transverse guards respected.** No assertion by index — every link check (`Offer.stores`,
  `StoreGroup.storeGroups`, `ProductFamily.products`/`productFamilies`) is a `HashSet` predicate lookup by
  code/EAN. No absolute id or row-count of the shared tables is asserted; scenarios only test their own
  prefixed codes, so they stay order-independent inside the JVM-long H2 database.

## Justified residue

**None.** No `[P]`/`[W]` scenarios in Group F; all 11 are implemented and green. The catalog écarts (F3,
F7, F9, F11 → `"System error"`) are documented above and pinned as observed reality, not skipped.

## Scope

Touched only `src/test/java/com/intermarche/valuation/e2e/GroupFIT.java` and this report. **Nothing under
`src/main` was modified.** No production bug requiring a stop was found — every écart is a rendering/masking
behaviour of the existing SmallRye + `GraphQLTrait` design, faithfully pinned by the tests.
