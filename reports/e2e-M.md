# E2E campaign — Group M (Titre-restaurant, assiette MEAL_VOUCHER)

Engine under test: `MealVoucherAdvantageFactory` (advantage `MEAL_VOUCHER`).
Class written: `src/test/java/com/intermarche/valuation/e2e/GroupMIT.java` (NEW — no prior class).
Command: `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupMIT -DskipITs=false`.
Result: **Tests run: 4, Failures: 0, Errors: 0** — BUILD SUCCESS. **Iterations to green: 1** (green on the first real run; all calibrated exact values held).

## Scenarios derived and tier

Every scenario in section M is **unmarked** → **RestAssured** (`@QuarkusTest`, HTTP Basic `admin/admin`). No `[W]`, `[P]` or `[D]` marks in the section, so no Playwright and no prod-like harness — zero `@Disabled` residue.

| Id | @Test | Tier | Assertions |
|----|-------|------|-----------|
| M1 Assiette nominale | `m1_nominalPlate` | RestAssured | Exactly one `MEAL_VOUCHER` advantage, `offerCode=MEAL_VOUCHER_0101`, `threshold=25.00` exposed, `totalEligibleAmount>0` and `<` basket total (flag-less lait `…002` excluded); apples via `POMMES`, eau via `EAU_MINERALE`. |
| M2 Plafond invisible | `m2_invisibleCap` | RestAssured | `50 × eau …007 = 30.00` exact uncapped plate `> threshold 25.00`; raw body carries **no** `payableAmount` field. |
| M3 Assiette nette / casse | `m3_netOfDiscountsAndCaseSensitiveFlag` | RestAssured | NET: apples `…001 ×2` carry a real `discountAmount` advantage, plate `>0` and `<2.40` (gross apple upper bound). CASE: additive lowercase-flag offer on `0102` → plate `0.00` over 5 eligible eau. |
| M4 Exclusions | `m4_exclusionsAndAlwaysEmitted` | RestAssured | NO FLAG (lait only) → plate emitted at `0.00`; DELIVERY (5 eau + Seclin) → plate `3.00`, delivery offer present, plate `<` total; DEPOSIT (5 eau + `Deposit basket`) → plate `3.00`, deposit offer present; GESTURE (apple `manualForcedPrice`) → plate `0.00`. |

## Mirror seed

**Needed** — the plate prices against the real catalog. The seven CSV imports are replayed once at
class start in the mandated order. **One additive phase** beyond the mirror: a second
`MEAL_VOUCHER` (`M3_LOWERCASE_0102`) quarantined on `0102` with the LOWERCASE flag
`restaurant_voucher_eligible`, for M3's case-sensitivity probe. The seed already prices eau `…007`
on `0102`, so no extra price row was required. `0102` owns no meal voucher of its own, so the
additive offer is the sole meal voucher there.

## Calibration findings (catalog vs observed code)

- **M2 — invisible cap confirmed.** `MealVoucherAdvantageApplication` exposes only `offerCode`,
  `totalEligibleAmount`, `type`, `threshold` (getters). `payableAmount` is a field with **no
  getter** and `getOffer()`/`getOfferApplication()` are `@JsonIgnore`, so the JSON is exactly
  `{offerCode, totalEligibleAmount, type, threshold}`. The client sees the uncapped `30.00`, never
  the `25.00` cap. Asserted by `body.contains("payableAmount") == false`.
- **M1 — flag on the leaf, not an ancestor.** The catalog phrases the resolution as a climb
  `POMMES ← FRUITS ← ALIMENTAIRE`, but the seed places `RESTAURANT_VOUCHER_ELIGIBLE` **directly** on
  `POMMES` (and on `EAU_MINERALE`). `ProductFamily.productHasFlag` does climb the family DAG, but it
  resolves at the leaf here. No seeded product is eligible through an ancestor-only flag
  (`ALIMENTAIRE`/`FRUITS`/`BOISSONS` carry no flags), so the **ancestor-only climb is a documented
  residue** — it cannot be positively exercised without mutating the family seed (out of scope).
- **M3 — flag match is exact-case.** `getFlagsSet().contains(token.trim())` trims but never
  lower-cases, so a `restaurant_voucher_eligible` offer flag does **not** match the family's
  `RESTAURANT_VOUCHER_ELIGIBLE` → plate `0.00`, proven on the `0102` quarantine offer.
- **M4 — always emitted.** `apply()` unconditionally returns one `MealVoucherAdvantageApplication`
  per store `MEAL_VOUCHER` offer regardless of the plate; the engine adds it with no amount filter,
  so a flag-less basket still carries a `MEAL_VOUCHER` advantage at `0.00`. Delivery and deposit are
  services (not `ProductAware`), so their cost stays out of the plate — the plate equals the exact
  `5 × 0.60 = 3.00` eau in both probes. A gestured line is consumed by the ultra-priority applier and
  is invisible to the plate (`0.00`), matching I4.

## Pitfalls

- **Advantages are a `HashSet`** — the meal voucher is located by its literal `type` (`MEAL_VOUCHER`)
  and pinned by `offerCode`, never by array index.
- **Eau `…007` is the clean ruler** — it is targeted by no product offer, priced `0.60` TTC by the
  base `BasicOffer` with no discount, giving exact `n × 0.60` plates. The apple `…001` is targeted by
  two `IMMEDIATE_VOUCHER` discounts (and, at `≥3`, the 2FOR1), so apple plates are only bounded
  (`>0`, `<2.40`), never pinned to the cent — the exactness lives on the eau probes.
- **2FOR1 avoidance** — M3's apple probe uses `×2` so `PROMO_2FOR1_3300` (needs 3) does not fire; the
  plate reflects only the two immediate discounts.

## Residue

- **M1 ancestor-only hierarchy climb** — not exercisable with the current family seed (flag sits on
  the leaf families); documented, not `@Disabled` (M1 still asserts eligibility + exclusion + the
  exposed threshold). No `@Disabled` scenarios: every M scenario is a live RestAssured test.
