# E2E campaign — Group I (Gestes manuels portés par la ligne)

Class: `src/test/java/com/intermarche/valuation/e2e/GroupIIT.java` — **written** (new).
Engine under test: `ManualGestureOfferFactory` (+ `Basket.Item.validateManualGesture`).
Mirror seed: **needed** — the 7 CSV imports are replayed once at class start; real catalog
prices back every gesture and the discount/vignette/meal-voucher surfaces of I4.

Campaign: `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupIIT -DskipITs=false`
Result: **6 tests, 0 failures, 0 skipped — BUILD SUCCESS.** Iterations to green: **2**.

## Scenarios derived (whole letter block I1…I6), all RestAssured (pure HTTP, Basic admin/admin)

| Id | Tier | What it pins |
|----|------|--------------|
| I1 | RestAssured | The three gestures on store `0101`: forced `1.0` on penne `…005` → `1.00` TTC (0.2000 rate kept); amount `0.5` on oil `…006` (6.00) → `5.50`; percent `50` on pan `…031` (14.40) → `7.20`. Literal types `(forced price 1.0)` / `(amount -0.5)` / `(percent -50%)`. A gestured line leaves no standard offer for its EAN. |
| I2 | RestAssured | Zero floor: a line-borne transient price `10.00/12.00/0.20` with `manualDiscountAmount 20.0` floors the unit at `0.00` TTC **and** `0.00` HT, never negative. |
| I3 | RestAssured | Double gesture forbidden: the 3 pairs + the triplet each 500 with the literal `Item EAN '3300000000006' carries more than one manual gesture (amount, percentage, forced price); only one is allowed.` asserted on `valuation_traces.error_message`. |
| I4 | RestAssured | Ultra-priority total exclusion, before/after on the apple `…001`: a CONTROL (qty 2, ungestured) carries a `discountAmount` advantage, a `suggestion` advantage and a `MEAL_VOUCHER` plate; the GESTURED twin carries the gesture offer, **zero** `discountAmount` advantages, **zero** `suggestion` advantages, and a `MEAL_VOUCHER` plate of `0.00`. |
| I5 | RestAssured | Targeted multi-line gesture: L1 `…001`×3 (no gesture) + L2 `…001`×2 (amount 0.3). One gesture offer covers exactly qty `2.0`, restores only line `L2`, amount `1.56` (1.08 DEFAULT − 0.30, ×2); the non-gesture offers still price exactly L1's `3.0` apples. |
| I6 | RestAssured | Per-unit gesture: forced `5.0` on qty `2` of oil `…006` → `10.00` TTC (5.00 × 2), not a `5.00` flat line. |

No `[W]`/`[P]`/`[D]` marks in section I → **no Playwright, no prod-like, no @Disabled residue.**

## Calibration findings (catalog vs observed reality)

- **Gesture ignores the WEIGHT/VOLUME divisor.** Products `001` and `005` are WEIGHT, but
  `ManualGestureApplication.getAmount` prices `base.priceIncludingTax × quantity` directly,
  bypassing the reference-weight divisor used by standard lines. The catalog figures
  (1.00/5.50/7.20/1.56/10.00) match because those products' `referenceWeight` is `1.000`, so
  the arithmetic coincides; the report pins the raw per-unit maths as the actual behaviour.
- **Base price still required under a forced price.** Even a `manualForcedPrice` calls
  `getPrice(store, DEFAULT)` to recover the VAT rate, so the gestured product must own a DEFAULT
  catalog price (all of `005/006/031/001` do at `0101`). I2 sidesteps this by porting a
  line-borne transient price, which `getPrice` returns without a DB lookup — giving an exact
  `12.00` base independent of the catalog.
- **JSON scale is load-bearing for the offer type.** `getType()` prints the `BigDecimal` verbatim,
  so `manualForcedPrice: 1.0` renders `(forced price 1.0)` — every I1/I6 gesture number is sent
  with the exact scale the catalog quotes (`1.0`, `0.5`, `5.0`, `50`, `20.0`, `0.3`).
- **`availableToUpcell` is not serialized (inherited G5 calibration).** The field is
  `@JsonIgnore` in `BasketEvaluation`; I4 cannot assert "absent from availableToUpcell" against a
  visible key, so it pins the key's absence from the raw body **plus** the absence of any
  `suggestion` advantage on the fully-gestured basket (the gesture never feeds the upsell pool).
- **The gesture is an OFFER, not an advantage.** `ManualGestureApplication` lands in `offers`
  with the `Manual Gesture: EAN=… (…)` type — located by predicate on the type prefix, never by
  index (`offers` is a `HashSet`).

## Pitfalls encountered (iteration 1 → 2)

- **I5 first cut asserted L1's eligibility by offer *type* containing the EAN — wrong.** L1's 3
  apples are consumed by the N+M 2FOR1, whose type is `N+M`/`Mixed Bundle Promo: …` and carries
  **no** raw EAN. Fixed by summing, across every non-gesture offer, the quantity of items whose
  `produceEan` is the apple EAN (= `3.0`, exactly L1); the gesture offer restores `L2` only.

## Justified residue

**None.** All six scenarios are unmarked RestAssured tier and pass; no `[P]`/`[W]` scenarios in
the block, so nothing is `@Disabled`.

## Scope

Touched only `GroupIIT.java` and this report. No `src/main` change; no new seed/golden resource
was needed (the existing 7-CSV mirror seed and one line-borne transient price cover the group).
