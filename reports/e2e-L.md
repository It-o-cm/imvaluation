# E2E campaign — Group L (Livraison, consigne, franco de port)

**Class:** `src/test/java/com/intermarche/valuation/e2e/GroupLIT.java` (written, not previously present).
**Command:** `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupLIT -DskipITs=false`
**Result:** `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
**Iterations to green:** 2 (first run 5/7; two type-string literals recalibrated for trailing-zero rendering).
**Mirror seed:** REQUIRED — all three engines price against the real catalog. The 7 CSV imports are
replayed once at class start, plus three additive extra phases (see below).

## Engines under test
- `DeliveryOfferFactory` → `Delivery:` offer (Haversine R=6371, first tier `distance ≤ maxDistance`, ascending sort).
- `DepositBasketOfferFactory` → `Deposit Basket:` offer (Σ `standardQuantity × referenceVolume`, `ceil(vol/basketVolume)`).
- `FreeDeliveryThresholdDiscountFactory` → `Free Delivery Threshold Discount:` advantage (tiers sorted descending, capped at delivery cost).

All three retrieve **store-only** via `Offer.findByStoreAndType`.

## Scenarios derived and tiers

| Id | Tier | What it pins |
|----|------|--------------|
| **L1** Livraison nominale | RestAssured | `HOME_DELIVERY` Seclin on `0101` → `Delivery: DELIVERY_HOME_0101 (…km) for 9.9€`, 9.90 TTC / 8.25 HT, no valued items; total 15.90/13.25. |
| **L2** Hors paliers | RestAssured | `HOME_DELIVERY` ~299 km → no delivery offer, 200, merchandise valued normally (6.00/5.00). |
| **L3** Coordonnées manquantes | RestAssured | 4 poison probes: no address (`0101`), address without lat/lon (`0101`), no address on a store **without** a delivery offer (`0102` — guard precedes the offer search), and store without coordinates (`0199` → store message). |
| **L4** Modes sans livraison | RestAssured | `PICKUP` (with address), `IN_STORE`, absent mode → no delivery offer, no error; total = merchandise alone (6.00). |
| **L5** Offres multiples interdites | RestAssured | Second `DELIVERY` and second `DEPOSIT_BASKET` on `0104` → 500 `Configuration Error: Multiple … offers found for store '0104'. Expected 1, found 2.` |
| **L6** Consigne | RestAssured | Trimmed/case-insensitive `  deposit BASKET  ` on 5 apples → `Deposit Basket: 2 x 0.5€` (12.5 L → 2 baskets), 1.00/0.83; no instruction → nothing; zero-volume pan → nothing despite the instruction. |
| **L7** Franco de port | RestAssured | Above (24.00 merchandise) → one franco refunds the full 9.90/8.25, total collapses to 24.00/20.00; below (6.00) → delivery kept, no franco; `IN_STORE` (no delivery) → no franco. |

All 7 scenarios are **RestAssured** (pure HTTP, Basic `admin/admin`). No `[W]`/`[P]`/`[D]` marks in the L
section, so **no Playwright, no prod-like profile, no `@Disabled` residue**.

## Mirror seed + extra phases
Extra fixtures are additive (distinct codes/rows, no checksum collision), quarantined off `0101`:
1. **Store `0199`** with empty `latitude`/`longitude` — the L3 store-guard probe.
2. **Extra prices** — pâtes `…005` mirrored onto `0104` (L5) and `0199` (L3) with `DEFAULT` + `BASE_FOR_DISCOUNT`.
3. **L5 poisons on `0104`** — a second `DELIVERY` (`L5_DELIV_A/B`) and a second `DEPOSIT_BASKET` (`L5_DEPO_A/B`).

Quarantining L5 on `0104` (not `0101`) is deliberate: a second offer on `0101` would poison L1's nominal
delivery and L6's nominal consignment, which both run on `0101`.

## Calibration findings (catalog vs observed reality)
- **L1 distance** — catalog says Seclin is `~11 km` / type `(11,xx km)`; the Haversine between `0101`
  (50.63/3.06) and Seclin (50.540/3.030) is **10.23 km**, still inside the 16 km tier → 9.90€. The `%.2f`
  distance is **locale-formatted** (comma under `fr_FR`), so the type is asserted by prefix +
  `km) for 9.9€` suffix, never on the decimal separator.
- **Trailing-zero rendering** — the `Delivery:`/`Deposit Basket:` type strings embed
  `BigDecimal.toString()` / Jackson `decimalValue()`, which **drop trailing zeros**: the configured
  `9.90` renders `9.9€` and `0.50` renders `0.5€`. The numeric `amount`/`discountAmount` fields keep the
  money exact (asserted scale-insensitively via `compareTo`). This was the only recalibration between runs.
- **L5 store code** — catalog quotes `'0101'` in the `Multiple … offers` message; the poison is quarantined
  on `0104`, so the asserted literal carries `'0104'`. The message FORMAT (`Configuration Error: … Expected 1,
  found 2.`) is verbatim.
- **L7 tiers are FIXED, not percentages** — catalog reads `10 → 50 %` / `20 → 100 %`, but
  `FREE_DELIVERY_THRESHOLD_0101` stores both as `FIXED_AMOUNT` (50.0 / 100.0). Both exceed the 9.90€
  delivery, so **both tiers cap to the full refund** — the discount is 9.90/8.25 whichever tier wins, and
  the delivered total collapses to the merchandise total. The tier distinction is therefore observationally
  inert here.
- **Group franco is dead** — `PROMO_GROUP_NORD` (`FREE_DELIVERY_THRESHOLD` on `REGION_NORTH`) is never
  retrieved: the factory is store-only. So on `0101` the only franco is `FREE_DELIVERY_THRESHOLD_0101`
  (exactly one advantage asserted).

## Pitfalls
- The delivery **address guard precedes the offer search** (proven by L3c on `0102`, a store with no
  delivery offer): the guard sits at the top of `buildAppliers`, before `findByStoreAndType`.
- The guard order within a `HOME_DELIVERY` basket is address → store → offer count; the L3d store-coordinates
  probe needs a **valid** address so the address guard passes first, and a **priced** item (`…005` on `0199`)
  so `BasicOfferFactory` does not throw a price error before the store guard fires.
- L5's two poisons live on the same store but are gated by mode: the delivery poison uses `HOME_DELIVERY`
  (deposit factory returns early — no instruction); the deposit poison uses `IN_STORE` + the instruction
  (delivery factory returns early — not `HOME_DELIVERY`). The thrown message is deterministic regardless of
  factory iteration order.
- Deposit **volume reads the original basket** (`basket.items`), independent of offer consumption, so the 5
  apples that also feed the N+M/voucher offers still contribute their full 12.5 L to the consignment.

## Residue
None. All 7 scenarios are implemented and green; no `[W]`/`[P]` scenarios exist in the L section, so no
justified `@Disabled` residue. Documentary notes in the L catalog (delivery excluded from the TR plate and
from the franco merchandise total; the H7 `referenceWeight` volume quirk — harmless here since apples have
`referenceWeight 1.000`) are covered narratively and by L7's ProductAware-only merchandise sum rather than a
dedicated assertion.
