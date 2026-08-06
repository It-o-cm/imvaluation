# E2E campaign — Group K (Remises : bons immédiats & vignettes)

## Summary

- **Class written**: `src/test/java/com/intermarche/valuation/e2e/GroupKIT.java` (new — did not exist).
- **Tier**: all 9 scenarios are unmarked → `@QuarkusTest` + RestAssured (pure HTTP, Basic `admin/admin`). No `[W]`/`[P]` scenarios in the group, so no Playwright and no prod-like harness.
- **Mirror seed**: YES. The two discount engines price against real catalog prices, so the 7 CSV imports are replayed once at class start in the mandated order, plus three additive phases (extra prices on `0103`/`0105`, non-poison vouchers/vignettes on `0101`, the K4 seam offers, and the K8 poison on `0105`).
- **Iterations to green**: 1 (green on the first campaign run; no calibration re-runs needed — all amounts derived from the source before writing).
- **Result**: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.
- **Residue**: none disabled. The K4 seam is exercised with 5 of its 8 types (see below); the 3 remaining are justified residue tied to the same retrieval contract.

## Scenarios derived and tier

| Id | Tier | What it graves |
|----|------|----------------|
| K1 | RestAssured | Two `IMMEDIATE_VOUCHER`s stack on one apple line (`PROMO_STORE_101` 15% + `BRI_APPLES_DISCOUNT` 0.10), both REDUCING the total (`0.15`/`0.18` + `0.10`/`0.12` off a `0.99`/`1.19` `BASE_FOR_DISCOUNT` base → `0.74`/`0.89`). The historic "wrong-sign" regression, now GREEN. |
| K2 | RestAssured | Uncapped formulas → NEGATIVE total. PERCENTAGE 150 on milk `…002` → `−1.27`/`−1.51`; FIXED 10.0 on baguette `…003` → `−9.12`/`−10.94`. Exact negatives graved. |
| K3 | RestAssured | `targetOfferClass` matches by case-insensitive `contains`: lowercase `basicoffer` still fires (`0.26`/`0.32`); `NPlusMOffer` lands on the N+M block (`0.99`/`1.19`) while the `BasicOffer` seed vouchers do NOT (apples consumed by the block); a no-match class yields no advantage and no error. |
| K4 | RestAssured | The store/group scope seam — see the matrix below. |
| K5 | RestAssured | Vignettes nominal: `applied = min(floor(qty), vignettes/required)`. 1 pan + 5 → applied 1 (`6.60`/`7.92`); 2 pans + 10 → applied 2 (`13.20`/`15.84`); 2 pans + 7 → applied 1 (stock caps). |
| K6 | RestAssured | FIXED vignette above the price → uncapped negative (`5.00` TTC / `4.17` HT off a `0.55`/`0.66` water line → `−3.62`/`−4.34`). Crosses K2. |
| K7 | RestAssured (negative, trace) | Unknown EAN in the `vignettes` map → NPE in the `VignetteDiscountApplier` constructor (`Collectors.toMap` with a null `Product::findByEan`) → 500, trace `Error building appliers from factory:`. |
| K8 | RestAssured (negative, trace) | `vignettesRequired: 0` → integer division by zero → 500, trace contains `/ by zero`. Crosses J6. |
| K9 | RestAssured | Inert vignettes: no map → no applier; map present but its EAN absent from the basket → no advantage, clean 200. |

## K4 — the 8-type seam matrix

The seam is a pure retrieval-method contrast in the factories:

| Type | Retrieval | Group attach | Tested in K4 |
|------|-----------|--------------|--------------|
| IMMEDIATE_VOUCHER | `findByStoreAndType` (store-only) | IGNORED | ✅ `K4S_IMM` fires on `0103`, `K4G_IMM` ignored |
| DEPOSIT_BASKET | `findByStoreAndType` (store-only) | IGNORED | ✅ `K4G_DEPO` ignored (no `Deposit Basket:` offer even with the instruction) |
| DELIVERY | `findByStoreAndType` (store-only) | IGNORED | residue (same retrieval method; needs a `HOME_DELIVERY` harness) |
| FREE_DELIVERY_THRESHOLD | `findByStoreAndType` (store-only) | IGNORED | residue (same retrieval method; only fires with a delivery line) |
| N+M | `getOffers` (store+groups) | HONORED | ✅ `K4G_NPM` applies on `0103` |
| MIXED_BUNDLE | `getOffers` (store+groups) | HONORED | ✅ `K4G_BUNDLE` applies on `0103` |
| VIGNETTE_DISCOUNT | `getOffers` (store+groups) | HONORED | ✅ `K4G_VIG` applies on `0103` |
| MEAL_VOUCHER | `getOffers` (store+groups) | HONORED | residue (same retrieval method; needs eligible-flag products over the threshold) |

Both sides of the seam are proven with concrete probes (5 of 8 types); the 3 residue types share the *identical* two retrieval methods (verified by grep of `src/main`) and would only add HTTP plumbing (delivery address / flagged families), not new engine behaviour.

## Calibration findings (catalog vs observed reality)

- **The discounted standard line uses `BASE_FOR_DISCOUNT`, not `DEFAULT`.** `BasicOfferApplier.apply` picks `refPrice` (not `defaultPrice`) as soon as a discount applier has registered on it (`getDiscountAppliers().isEmpty() ? defaultPrice : refPrice`). Every K1/K2/K3/K5/K6 base amount is therefore the `BASE_FOR_DISCOUNT` price (apple `0.99`/`1.19`, not `0.90`/`1.08`). This is the single most important calibration point of the group.
- **K7 wraps as `Error building appliers from factory:`, not the apply wrapper.** The NPE is born in the applier CONSTRUCTOR (`productInCatalog = toMap(vignetteKeys, Product::findByEan)`), which runs inside `buildAppliers`. The NPE's own message is null, so only the prefix is asserted.
- **K8 also surfaces under `Error building appliers from factory:` — not `Error applying discount logic:`.** Observed reality: the division by zero fires during `OfferApplier.computeEfficiencyScore`, which calls the vignette `apply()` EARLY (efficiency estimation) inside `createOfferAppliers`, so the "building appliers" wrapper catches it before the dedicated apply-time wrapper is ever reached. The test asserts on the invariant substring `/ by zero`, which holds under either wrapper.
- **`vignettesConsumed` in the type literal is `required × applications`** (`VIGNETTE_CUISSON`: 5 per application), so 2 applications read `10 vignettes used, applied 2 times` and the stock-capped case reads `5 vignettes used, applied 1 times`.

## Pitfalls avoided

- **Store isolation of poison / group offers.** K8's poison VIGNETTE is quarantined on `0105` (`REGION_SUD`), deliberately OUTSIDE `REGION_NORTH`, so the K4 group offers (attached to `REGION_NORTH`) never bleed into the poison probe. K7 rides on `0101`'s own `VIGNETTE_CUISSON` (the NPE is independent of which vignette offer is processed).
- **Disjoint EANs.** Each non-poison K voucher/vignette targets a spare EAN (`…002`, `…003`, `…005`, `…006`, `…007`, `…009`) that no other K probe carries WITH a `vignettes` map, so `K4G_VIG` (group, `…009`) never double-fires against K6 (`…007`). A voucher builds an applier only when its target EAN is in the basket; a vignette only when the EAN is both covered and present in the `vignettes` map.
- **No perturbing offers on the exact-total probes.** DEPOSIT_BASKET only fires with a `"Deposit basket"` instruction, and FREE_DELIVERY_THRESHOLD only with a delivery line — both absent from the K1/K2/K6 IN_STORE baskets, so the graved totals are exact.
- **HashSet order.** Every discount is located by its literal `type` (with the telltale SPACE before the colon in `Immediate Voucher Discount : `), never by index; poison outcomes are read from `valuation_traces.error_message` keyed by a unique `customerCode`, never from the raw 500 body.

## Justified residue

- **K4 DELIVERY, FREE_DELIVERY_THRESHOLD, MEAL_VOUCHER**: not disabled tests, but sub-cases of the single K4 scenario not driven by an explicit probe. They use the exact same store-only / store+group retrieval methods as the tested types (proven by source inspection) and would require delivery-address or eligible-family plumbing to fire positively — no new engine behaviour. Documented in the K4 matrix and Javadoc.
- No `@Disabled` scenarios: the group has no `[W]`/`[P]` marks.
