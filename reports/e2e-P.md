# E2E campaign — Group P (Coutures transverses, 2ᵉ passe d'audit)

Class: `src/test/java/com/intermarche/valuation/e2e/GroupPIT.java` — **written from scratch** (no prior
`GroupPIT`). One `@Test` per scenario, the scenario id in the method name and its Javadoc.

Command: `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupPIT -DskipITs=false`
Result: **Tests run 8, Failures 0, Errors 0, Skipped 1** (P5 justified residue). **2 iterations to green.**

Tier: every P scenario is **unmarked** → `@QuarkusTest` + RestAssured (HTTP Basic `admin/admin`),
with a few GraphQL delete mutations (P8) and two direct `DateTimeProvider` calls (P8). No `[W]`/`[P]`
scenarios in this letter, so no Playwright and no prod-like harness needed.

Mirror seed: **needed.** The group audits engine-wide seams, so the 7 CSV imports are replayed once at
class start in the mandated order (Stores → StoreGroups → Products → ProductFamilies → Categories →
Prices → Offers), followed by two additive phases: `EXTRA_PRICES` (probe `…007` priced on the
quarantine stores `0103`/`0104`/`0105`) and `EXTRA_OFFERS` (the P1/P3 poisons + the P4 `eans` probes).

## Scenarios derived and tier

| Id | Claim | Tier | Verdict |
|----|-------|------|---------|
| P1 | Observable idempotence (10× same basket) + the zero-dry-amount division-by-zero score | RestAssured | ✅ |
| P2 | The maximal reference basket — every family fires, all G6 invariants hold | RestAssured | ✅ |
| P3 | Two spec-failure paths: unparsable JSON at PERSIST; schema-nonconforming at VALUATION | RestAssured | ✅ |
| P4 | `Offer.eans` recursive index (targetEans / contents[].ean / substituteEans) + `@PreUpdate` | RestAssured (UI filter) | ✅ |
| P5 | 80 000-product volumetry + quadratic dry-score load test | **@Disabled residue** | ⏭️ |
| P6 | `availableToUpcell` empty + no suggestion on a fully-consumed basket | RestAssured | ✅ |
| P7 | Scale-sensitive price checksum (`1.20` re-imported as `1.2000` → one update) | RestAssured | ✅ |
| P8 | Hygiene: `DateTimeProvider.clear()` restores pricing + reverse-order GraphQL teardown | RestAssured + GraphQL | ✅ |

## Calibration findings (catalog vs observed reality)

- **P1 — the division by zero is UNREACHABLE, not observable.** The catalog wants an "offer whose dry
  amount is 0" to yield `Infinity`/`NaN` in `OfferApplier.computeEfficiencyScore` (line 87-89,
  `x / totalAmount.doubleValue()`). Observed: no schema-valid offer can zero that denominator.
  `bundlePrice` carries `exclusiveMinimum:0` (`MixedBundleOfferFactory`) and N+M `quantityToPay`
  carries `minimum:1` in the upsell schema, so both zeroing configs (`bundlePrice:0.0`, `quantityToPay:0`)
  are **valid JSON** — they import — but are **refused at valuation** with `Error validating offer:`.
  The division is dead code masked by the schemas; the score is never `Infinity`/`NaN` in practice. The
  test graves both rejections. (The efficiency score itself is internal — never in the `/valuation`
  JSON — so it cannot be asserted directly; only its guard is observable.)
- **P1 — idempotence is real and total.** The engine mutates only per-request working copies
  (`BasketEvaluation.feedFrom` deep-copies items; the dry run builds a throwaway `BasketEvaluation`);
  vignettes come from the request body and nothing is written back to the catalog. Ten identical POSTs
  produce the identical evaluation, compared via a sorted signature of offer/advantage
  `type=amountIncludingTax` + total (HashSet-order-independent).
- **P2 — "each family appears once" ⇒ "appears".** On the seeded `0101` catalog the immediate-voucher
  family necessarily fires **twice** (both `PROMO_STORE_101` and `BRI_APPLES_DISCOUNT` target the apple's
  standard line), and the franco family fires twice (store `FREE_DELIVERY_THRESHOLD_0101` + the
  `REGION_NORTH` group `PROMO_GROUP_NORD`, since `0101 ∈ REGION_NORTH`). Exactly-once is therefore not a
  faithful assertion; the five **G6 structural invariants** over the whole evaluation are the real
  non-regression contract, reused verbatim from `GroupGIT.g6`.
- **P3 — the valuation message is double-wrapped.** The wire/trace text is
  `Error building appliers from factory: Error validating offer: <networknt details>` — asserted on the
  `Error validating offer:` substring, per the catalog's F4/G4 wrapping note. The PERSIST message
  `Failed to parse specification for Offer <code>:` is thrown by `Offer.@PrePersist` (JSON parse only, no
  schema check) and surfaces in the import's `errors[]` line report — proving CSV import and UI form
  validate at different depths (CSV never runs the schema).
- **P4 — the UI EAN filter lives on the list screen, not the export.** `GET /ui/offers/export?ean=…`
  returns **404** under RESTEasy (the literal `/export` loses to the `/{id}` template, whose `Long`
  coercion of `"export"` fails). The equivalent `GET /ui/offers?ean=…` (same `exists (… o.eans e where e
  like ?)` query) renders the matching offer codes in HTML and is used instead. Re-importing `P4_NM`
  with a new `targetEans` re-derives the index in place (`@PreUpdate`), so the stale EAN stops matching
  and the new one starts — proven through the same filter.
- **P6 — the empty pool is OMITTED, not `{}`.** Jackson drops the empty `availableToUpcell` map from the
  `/valuation` JSON, so a fully-consumed basket yields the key **absent** rather than `{}`. The
  assertion tolerates both (null or empty). `availableToUpcell` is fed exclusively by
  `BasicOfferApplier` (the standard pool), so a basket consumed entirely by N+M + bundle + a manual
  gesture leaves it empty and emits no `suggestion` (the always-on `MEAL_VOUCHER` plate is suggestion-less
  and tolerated).
- **P7 — confirmed exactly as documented.** `Price` checksum hashes `BigDecimal`, whose `hashCode`
  distinguishes scale 2 from scale 4; re-importing the couteaux `…033`/`0102` price widened to `1.2000`
  yields `createdCount:0, updatedCount:1` though `compareTo` finds the value unchanged.

## Pitfalls encountered (and the 2 iterations)

1. **Iteration 1 → 2 failures.** (a) P4 hit the `/ui/offers/export` **404** described above — switched to
   the list screen. (b) P6 asserted `availableToUpcell` was non-null — Jackson omits the empty map;
   relaxed to null-or-empty.
2. The P1/P3 poisons had to be **quarantined** on stores `0103`/`0104`/`0105`, each with a priced probe
   `…007`, so the 500 is the schema rejection (`Error building appliers from factory:` runs in
   `createDiscountAppliers`, step 2, before pricing) and never a benign "No active price" — mirroring the
   `GroupJIT` quarantine recipe.
3. **P8 must reset the clock in a `finally`.** `DateTimeProvider` is a global static shared by the whole
   JVM; pinning it to `2026-01-01` (before the seed price start `2026-01-12`) makes apples un-priceable
   (`No active price found … Checked at date`), and `clear()` restores live time. Leaking the fixed time
   would break every other scenario, so the reset is unconditional.
4. The P4 probe EANs (`…015/016/017/018/023`) were chosen to be referenced by **no seed offer**, so each
   `ean` filter isolates exactly the probe under test (the `e like 'ean%'` filter on a full 13-char EAN is
   effectively exact). The `eans` index is a `HashSet`, so membership — never position — is asserted.

## Justified residue

- **P5 — 80 000-product volumetry & the quadratic dry-score load test → `@Disabled`.**
  `MassProductImporterClient` exists (`src/test/java/…/client/MassProductImporterClient.java`) but only as
  a standalone `main()` HTTP client bound to the **fixed port `8090`** (not the random `@QuarkusTest`
  port), with no assertion, time budget, or counter parsing. An 80 000-product import plus a 50-line
  quadratic-cost soak test has **no automated harness** and would pollute the shared catalog every other
  scenario depends on; there is nothing to observe deterministically in CI. Disabled with a one-line
  justification, counted as the single residue.

## Scope

Touched only `GroupPIT.java` and `reports/e2e-P.md`. No `src/main` change. The P4/P7/P8 disposable rows
(`P4_*`, `…033`/`0102`, `P8DISP`/`3309999999999`) use codes/EANs disjoint from the mirror and, for P8,
are torn down within the scenario. No commit — the campaign script owns it.
