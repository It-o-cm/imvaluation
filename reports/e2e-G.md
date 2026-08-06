# E2E campaign — Group G (`/valuation`, the contract)

Spec source: `e2e-scenarios.md` § G (G1–G7) plus the Q-A / Q-B inventory rows for the
`/valuation` surface. One class written from scratch:
`src/test/java/com/intermarche/valuation/e2e/GroupGIT.java` (`@QuarkusTest`, pure
RestAssured, HTTP Basic `admin/admin`). No pre-existing GroupGIT to complete.

**Result:** `mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupGIT
-DskipITs=false` → Tests run: 9, Failures: 0, Errors: 0, **Skipped: 1** (the one `[P]`
residue). Iterations to green: 3 (see Pitfalls).

## Mirror seed — needed

Every priced scenario (G1 viewer path, G3–G6) needs the referential, so the mirror
catalog is replayed ONCE at class start through the seven import endpoints in the mandated
order (Stores → StoreGroups → Products → ProductFamilies → Categories → Prices → Offers).
The CSV bodies are versioned as seed resources under `src/test/resources/seed/01..07-*.csv`,
transcribed verbatim from the `*ImporterClient` sources (the `<<START_DATE>>` placeholder
resolved to the client's literal `2026-01-12T00:00:00`). Seeding runs in a `@BeforeEach`
guarded by a static `seeded` flag rather than `@BeforeAll` (see Pitfalls).

## Scenarios derived, per tier

| Id | Tier | Method | Notes |
|----|------|--------|-------|
| G1 | RestAssured | `g1_apiChallengesInBasicAndAnyRoleValuates` | 401 bare Basic challenge (no redirect); a VIEWER values a basket (200). The `[P]` sub-clause is split out as residue below. |
| G2 | RestAssured | `g2_schemaViolationsAreRejectedAndTraced` + `g2_unknownFieldIsTolerated` | 10 schema violations → 400 + `REJECTED` trace; the tolerated unknown field → 200 + `SUCCESS`. |
| G3 | RestAssured (calibration) | `g3_unconsumedItems422IsUnreachable` | The 422 path is unreachable via the contract — pinned reality instead of a synthetic 422. |
| G4 | RestAssured | `g4_configurationErrorsAreFailedAndTraced` | 4 config errors → 500 + `FAILED` trace, null `responsePayload`, literal messages. |
| G5 | RestAssured | `g5_responseShapeAndAdvantageDiscriminants` | 200 response shape + the three advantage discriminants. |
| G6 | RestAssured | `g6_structuralInvariantsHold` | Σ items = offer amount; `vatAmount = TTC−HT`; Σ breakdown = total; no blended item rate. |
| G7 | RestAssured | `g7_transportFailuresLeaveNoTrace` | text/plain → 415, malformed JSON → 400, neither records a trace. |
| G1 `[P]` | @Disabled residue | `g1_mustChangePasswordDoesNotBlockApi` | Needs the prod-like enforcement filter (off in `%test`). |

All 4xx/5xx textual assertions read `valuation_traces.error_message` (Panache under
`QuarkusTransaction`), keyed by a unique `customerCode` per probe — never the entity-less
HTTP body, per the transverse guard. `offers`/`advantages` are iterated / matched by
predicate, never by index (they are `HashSet`s).

## Calibration findings (catalog vs observed reality)

1. **`availableToUpcell` is ABSENT from the response.** The catalog (G5) lists
   `availableToUpcell{}` as part of the 200 shape, but the field is `@JsonIgnore` in
   `BasketEvaluation` (line 67); a field-level ignore masks the un-annotated public
   `getAvailableToUpcell()`, so Jackson drops the whole property. G5 pins its absence
   (`assertNull` + the raw body must not contain the key).
2. **Schema-validation messages are localised in FRENCH** (`propriété requise 'items'
   introuvable`, `doit avoir au moins 1 éléments mais trouvé 0`, `n'a pas de valeur dans
   l'énumération …`). Only the English literal prefix `Error validating offer:` and the
   offending field token (which appears in the networknt JSON-path, e.g. `$.items[0].quantity`)
   are asserted — the localised detail is left uncalibrated exactly as the catalog instructs.
3. **G3's 422 is unreachable through the contract.** `BasicOfferFactory` builds a catch-all
   applier for every unique priced EAN and consumes its whole remaining quantity, so a
   schema-valid basket always empties `toEvaluate` (→ 200) or throws first (→ 500). The only
   states that would leave an item unconsumed — a missing/zero `quantity`, a null
   `produceEan` — are barred by the basket schema and rejected at 400. The test pins that:
   the missing-quantity basket is a 400 `REJECTED`, its valid twin a 200 carrying a
   `Standard: EAN=…` line.
4. **Unknown-store 500 is NOT wrapped.** It is thrown in the `BasketEvaluation` constructor,
   before the factory loop, so `error_message` is the bare `Configuration Error: Store not
   found for code 'ZZZZ'`. By contrast unknown-EAN, bad-date and expired-price throw inside
   `BasicOfferFactory.buildAppliers` and ARE wrapped with `Error building appliers from
   factory: ` — both forms asserted verbatim (via substring).
5. **`MEAL_VOUCHER` is emitted below its threshold.** The full G5 basket (2 apples, eligible
   total 1.78 « 25.00) still carries a `MEAL_VOUCHER` advantage — the advantage is produced
   whenever the basket holds a `RESTAURANT_VOUCHER_ELIGIBLE` product, independent of the
   threshold — so the discriminant is asserted on that single response (no separate basket).
6. **G7's two 400s differ by trace.** A malformed JSON body is a Jackson-provider 400 that
   records NO trace (the exception precedes the resource method); text/plain is a 415, also
   traceless. Asserted by an unchanged `ValuationTrace.count()` across both, the mirror of
   G2's schema-400 which DOES write a `REJECTED` trace.

Observed but not asserted textually: the delivery label uses a French decimal comma and
trims the trailing zero — `Delivery: DELIVERY_HOME_0101 (10,23 km) for 9.9€` — so G5 only
asserts that Delivery/Deposit offers carry an empty `items` list.

## Pitfalls

- **Seed timing.** RestAssured's test port is wired per test instance, not before
  `@BeforeAll`; seeding there failed with `Connection refused`. Moved the seven imports into
  a `@BeforeEach` guarded by a static flag (H2 is boot-created and lives for the class run,
  so the guard runs the imports exactly once).
- **RestAssured number parsing.** Floating JSON numbers deserialize to `Float`/`Double`, so
  a `getString("totalPrice.vatRate")` string-equals `"0.0000"` is unsafe. Money/rate
  invariants compare `new BigDecimal(getString(...))` with `compareTo` (scale-insensitive),
  and the exact `0.0000` scale-4 sentinel is confirmed against the raw body substring
  `"vatRate":0.0000`.

## Justified residue

- **G1 `[P]` sub-clause** — `mustChangePassword=true` does not block the API call — is
  `@Disabled`: it depends on the prod-like password-change enforcement filter, disabled by
  `%test.app.password-change.enforced=false`, with no prod-like harness available. This is
  the single skipped test.

## Scope

Touched only `GroupGIT.java`, the seven `src/test/resources/seed/*.csv` seed resources, and
this report. Nothing under `src/main` was modified. Not committed — the campaign script owns
the commit.
