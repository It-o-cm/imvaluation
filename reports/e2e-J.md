# E2E campaign — Group J (N+M & bundles mixtes)

**Class:** `src/test/java/com/intermarche/valuation/e2e/GroupJIT.java` (written, new)
**Command:** `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupJIT -DskipITs=false`
**Result:** Tests run: **11**, Failures: 0, Errors: 0, Skipped: 0 — **BUILD SUCCESS**
**Iterations to green:** 2 (only J5's expected value was recalibrated after run 1).
**Mirror seed needed:** YES — the two heart-of-the-engine appliers price every bundle from the
catalog. The seven CSV imports are replayed once at class start, followed by two ADDITIVE phases.

## Scenarios derived & tiers

Every J scenario is **unmarked** → `@QuarkusTest` + RestAssured, pure HTTP, Basic `admin/admin`.
No `[W]`/`[P]`/`[D]` marks in section J, so **no Playwright and no `@Disabled` residue**.

| Id  | Tier | Surface | Store | Custom offer (imported) |
|-----|------|---------|-------|--------------------------|
| J1  | RestAssured | N+M exact, 2-item application, source `lineId` | 0101 | seed `PROMO_2FOR1_3300` |
| J2  | RestAssured | leftover Standard + N+M upsell suggestion | 0101 | seed `PROMO_2FOR1_3300` |
| J3  | RestAssured | CHEAPEST vs MOST_EXPENSIVE inversion, to the cent | 0101 | `PROMO_J3A_CHEAP`, `PROMO_J3B_EXP` |
| J4  | RestAssured | FIXED_AMOUNT capped at block, never negative | 0101 | `PROMO_J4_FIXED` |
| J5  | RestAssured | multi-lot aliasing defect (NEGATIVE, graved) | 0101 | `PROMO_J5_ALIAS` |
| J6  | RestAssured | toxic N+M configs = store poison (NEGATIVE) | 0104, 0103 | `POISON_J6A`, `POISON_J6B` |
| J7  | RestAssured | fixed-price bundle, substitute, inert-if-missing | 0101 | seed `PROMO_COFFEE_PACK` |
| J8  | RestAssured | VAT derived from goods, not declared | 0101 | `PROMO_J8_BUNDLE` |
| J9  | RestAssured | discount-mode bundle = global poison (NEGATIVE) | 0105 | `POISON_J9` |
| J10 | RestAssured | bundle capacity = per-component min, substitute last | 0101 | seed `PROMO_COFFEE_PACK` |
| J11 | RestAssured | bundle upsell suggests cheapest deficient component | 0101 | seed `PROMO_COFFEE_PACK` |

No Q-inventory row is exclusive to J beyond the two `type` literals already asserted:
`Mixed Bundle Promo: <code>` (Q-B) and `MixedBundle: <code> x<n> for <ttc>€` (Q-B), plus the two
locale-formatted upsell literals `Upsell N+M: …` / `Upsell Mixed Bundle: …`.

## Isolation architecture (why extra imports, no seed edits)

`@QuarkusTest` shares one in-JVM app + reset DB across the class, so all offers coexist in one
catalog. Two facts drove the layout:

- An N+M/bundle **offer applier** is built only when a target EAN is in the basket, and its
  **upsell twin** yields nothing when its EANs are absent. → Custom NON-poison offers (J3/J4/J5/J8)
  live on the full store **0101** with **disjoint UNIT-product EANs** that no other J basket
  touches; each scenario's basket is therefore blind to the others' offers.
- A **poison** offer is rejected by the upsell factory's schema during `createDiscountAppliers`
  (step 2, before any pricing), so it 500s **every** valuation of its store. → Poison offers are
  quarantined on dedicated stores (**0104**/**0103** for the two J6 configs, **0105** for J9),
  each given a single `…007` price so the poison — not a missing price — is the failure.

All extra rows are imported as inline CSV via the same `/prices/import` and `/offers/import`
endpoints, **additive** (distinct codes / rows). The shared `seed/*.csv` files are untouched.

## Calibration findings (catalog vs observed code)

1. **Price priority.** `Price.findActivePriceAtDate` orders `priority DESC`, so the apple's
   priority-1 `BASE_FOR_DISCOUNT` (0.99/1.19) wins → J1 pays 2×1.19 = **2.38** TTC / 1.98 HT.
2. **J5 — aliasing value corrected after run 1.** First run observed **10.56**, not the 0.00 I
   predicted. Re-tracing `createApplicationsFromPool`: the two lots pick lot 1 = cheap `…026`
   (paid 2.64) and lot 2 = expensive `…027` (paid 5.28); the bug-free total is **7.92**. Because
   the same two lists are shared and `clear()`+refilled, BOTH applications alias the final `…027`
   lot (each 5.28), so the offer totals **10.56**. Test now pins the observed 10.56 and asserts
   divergence from the correct 7.92.
3. **J6 — the div-by-zero is unreachable.** Both toxic configs (`quantityToPay:0`; and
   `quantityToPay:0` + `discountedQuantity:0`) fail the SAME upsell-schema guard
   (`quantityToPay` minimum 1) at step 2, which preempts the division-by-zero the catalog
   attributes to the second config. Both stores surface the identical
   `Error building appliers from factory: Error validating offer: …` message.
4. **J8 — discount-mode bundle is unreachable (poison, = J9).** The multi-rate VAT derivation is
   therefore proven on the reachable FIXED-price bundle: covering riz `…022` (5.5%) + miel `…024`
   (20%), the goods total 8.25 HT / 9.50 TTC → effective rate **0.1515** (`9.50/8.25 − 1`), NOT
   the declared 0.20; the 8.00 bundle is 6.95 HT. Cross-references G6.
5. **J10 — "2 lots" recalibrated to 3.** For 5 coffees + 2 biscuits + 1 chips the code yields
   `min(floor(5/1), floor(3/1)) = 3` bundles (catalog said 2). Three is also the only count that
   makes the chips substitute a genuine "last resort" (biscuits fill lots 1-2, chips falls back
   for lot 3). Pinned at 3 → `MixedBundle: PROMO_COFFEE_PACK x3 for 13.50€`, 2 leftover coffees
   Standard.
6. **J9/J6 poison message.** The engine wraps the schema failure as
   `Error building appliers from factory: Error validating offer: <detail>`; the detail is the
   json-schema-validator's localized phrasing (fr_FR: `propriété requise 'bundlePrice'
   introuvable…`), so the assertion pins only the stable engine prefix.
7. **Locale.** The two upsell `type` literals go through `String.format("%.2f")` (comma in
   fr_FR). Expected strings are rebuilt in-test with the SAME `String.format` in the SAME JVM as
   the app, so they match whatever locale the app runs under — no hard-coded separator.

## Pitfalls handled

- HashSet ordering: every offer/advantage located by literal `type` or `suggestion.offerCode`,
  never by index.
- Money compared scale-insensitively (`BigDecimal.compareTo`).
- Poison probed with a priced product so the poison, not a price error, is asserted; poison and
  franco factories both run at step 2 on 0104/0103 (REGION_NORTH), so `…007` is priced there.
- WEIGHT products (reference-weight divisor) avoided for custom money assertions — only UNIT
  products used, except the seed apple (reference weight 1.000, neutral).

## Residue

**None.** All 11 scenarios are implemented and green as RestAssured HTTP tests. No `@Disabled`,
no `[P]`/`[W]`-without-harness scenarios in this section.
