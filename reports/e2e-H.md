# E2E campaign — Group H (price resolution & standard lines)

Class: `src/test/java/com/intermarche/valuation/e2e/GroupHIT.java` — **written** (new class).
Command: `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupHIT -DskipITs=false`
Result: **Tests run: 8, Failures: 0, Errors: 0, Skipped: 0** — 8 scenarios, 0 residue.
Iterations to green: **2** (1st run: one `getString`-scale assertion failure; 2nd: green).

## Scenarios derived and their tier

Every H scenario is unmarked in the catalog → all **RestAssured / pure HTTP**, Basic `admin/admin`.
No `[W]`/`[P]` scenarios in this section, so **no `@Disabled` residue**.

| Id | Tier | What it pins |
|----|------|--------------|
| H1 | RestAssured | Single milk `002` line → one `Standard: EAN=3300000000002, Qty=1.0`, 20 % rate, single-rate breakdown summing to total. |
| H2 | RestAssured | Overlap: priority-1 DEFAULT wins (`order by priority DESC`); same-priority tie is documentary (winner journaled). |
| H3 | RestAssured | Window `[start, end)`: end boundary exclusive; past end → 500 `No active price`; null end = +∞. |
| H4 | RestAssured | Explicit `priceDate`: before start → 500; inclusive start → 200; date-only → 500 `Invalid date format`. |
| H5 | RestAssured | Line-borne transient price (all 3 fields) → no DB access, DEFAULT≡BASE_FOR_DISCOUNT; 3 partial combos silently ignored → catalog. |
| H6 | RestAssured | Same profile → one merged offer, line-by-line restitution; different profile → two offers; scale quirk `1.0` vs `1.00` → not merged. |
| H7 | RestAssured | WEIGHT ham `008` @0.5kg → 12.00 TTC; WEIGHT/VOLUME without reference → 500; VOLUME with reference priced by `referenceVolume`. |
| H8 | RestAssured | DEFAULT → BASE_FOR_DISCOUNT switch: a registered discount lifts the standard line to the +10 % reference price. |

## Mirror seed

**Needed** — the whole group is priced. The 7 CSV imports are replayed once at class start in the
mandated order (Stores → StoreGroups → Products → ProductFamilies → Categories → Prices → Offers).
A few scenarios add catalog states the base seed lacks, through the SAME import endpoints, on
products/stores no other H test values (order-independent, self-contained):

- **H2 tie** — a second DEFAULT priority-0 price for the otherwise-single-priced knife set `033`
  (start `2026-01-13`, TTC `24.00`), creating a same-priority overlap with the seed's `30.00`.
- **H3 window** — a milk `002` price bounded to `[2026-01-12, 2026-06-01)` on the fully-unpriced
  store `0103`.
- **H7 types** — three synthetic products (`901` WEIGHT ref 0, `902` VOLUME ref 0, `903` VOLUME
  ref 2.000) plus their DEFAULT/BASE_FOR_DISCOUNT prices, since **the seed carries no VOLUME
  product and no WEIGHT product with a missing reference**.

H5's no-DB-access probe uses store `0104` (never priced, never imported into) so the transient-price
short-circuit is proven against a genuine absence of any catalog price.

## Calibration — catalog vs observed reality

1. **H1 total is `2.76`, not the catalog's `3.00`.** The seed carries two overlapping DEFAULT prices
   for milk `002` — priority 0 at `3.00` and priority 1 at `2.76` (the very overlap H2 exercises).
   `Price.findActivePriceAtDate` orders by `priority DESC`, so the observed winner is the priority-1
   `2.76`. The catalog's `3.00` is the superseded priority-0 price. Same effect on apple `001`
   (DEFAULT `1.08`, BASE_FOR_DISCOUNT `1.19`), which H8 relies on. Pinned as observed.
2. **`JsonPath.getString` normalizes numeric scale.** A JSON `vatRate` of `0.2000` is read back as
   `"0.2"` (and money as `"2.76"` etc.). All rate/money assertions compare via
   `BigDecimal.compareTo` (scale-insensitive), never string equality — the single first-run failure
   was exactly this and is the reason G5 already asserts the `0.0000` sentinel through the raw body.
3. **H2 same-priority tie is genuinely indeterminate.** Observed winner this run: **`30.00`** (the
   seeded row), journaled to stdout `[H2] same-priority tie winner ... = 30.0`. The assertion accepts
   either `30.00` or `24.00` per the catalog's "documentary" clause.
4. **Config errors surface with the bare literal.** `No active price…`, `Invalid date format…`,
   `…no valid reference weight/volume defined.` all reach `valuation_traces.error_message` wrapped by
   `Error building appliers from factory: ` (H3/H4/H7 resolve the price during `buildAppliers`), so
   assertions use a substring match — consistent with G4.

## Pitfalls encountered

- **`getString` scale** (above) — the one first-run failure; fixed by numeric compare throughout.
- **Eager BASE_FOR_DISCOUNT resolution.** `BasicOfferFactory.buildAppliers` resolves *both* DEFAULT
  and BASE_FOR_DISCOUNT prices up front, so every synthetic product (`033`, `901`–`903`, `002@0103`)
  needs a BASE_FOR_DISCOUNT price too, or valuation 500s before any applier runs.
- **CSV import composite key** is `EAN:store:usage:start:priority` — a same-priority *and* same-start
  duplicate would be an UPDATE, not a tie. The H2 tie therefore differs the **start date**
  (`2026-01-13`) so both priority-0 rows coexist and stay active on today's date.
- **N+M does not steal the apple.** H5/H8 value one apple `001`; `PROMO_2FOR1_3300` needs 3 units to
  fire, so a single apple falls to the BasicOffer and its IMMEDIATE_VOUCHER — the standard line is
  what the scenarios assert.
- Offers are a `HashSet`: standard lines are always located by their `Standard: EAN=<ean>, ` type
  prefix, never by index.

## Residue

**None.** No `[P]`/`[W]` scenarios in group H; all 8 are HTTP-testable and green.

Cross-reference noted but out of scope: H7's frozen quirk "`standardQuantity` divides by
`referenceWeight` even for a VOLUME product" affects the deposit-basket / fixed-discount volume maths
(group L, L6), not the standard-line pricing exercised here, which correctly uses `referenceVolume`.

## Files

- Written: `src/test/java/com/intermarche/valuation/e2e/GroupHIT.java`, `reports/e2e-H.md`.
- Read: `e2e-scenarios.md` (§H, §Q), `GroupGIT.java` (pattern), `Price.java`, `BasicOfferFactory.java`,
  `Basket.java`, `AmountEvaluation.java`, `BasketEvaluation.java`, `ItemValuation.java`,
  `ValuationResource.java`, `ValuationEngine.java`, `PriceCsvResource.java`, `ImporterCsvResource.java`,
  `DateTimeProvider.java`, seed CSVs (`03-products`, `06-prices`, `07-offers`, `01-stores`).
- Never touched `src/main`.
