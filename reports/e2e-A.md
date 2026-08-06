# E2E scenario campaign — Group A (startup & bootstrapping `[D]`)

Class: `src/test/java/com/intermarche/valuation/e2e/GroupAIT.java` —
`@QuarkusTest` + RestAssured + Panache. Run by failsafe as an `*IT` class.

## Command & result

```
mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupAIT -DskipITs=false
```

- **BUILD SUCCESS.** `Tests run: 5, Failures: 0, Errors: 0, Skipped: 1`.
- **4 scenarios pass** (A1, A2, A4, A5); **1 skipped** (A3 — `[P]` residue).
- Test execution: **~3.1 s** (4 active tests).
- **Iterations to green: 2** — first run red on A4 only (`Offer::deleteAll` method
  reference bypassed Panache call-site enhancement → `implementationInjectionMissing`);
  switched to a lambda `() -> Offer.deleteAll()` → green. A1/A2/A5 were green on the
  first run (the seven import counts matched on the first attempt).

## Scenario map

| Scenario | Method | Tier | Status |
|---|---|---|---|
| A1 bootstrap admin on empty base | `a1_bootstrapAdministratorCreatedOnEmptyBase` | RestAssured/Panache | ✅ |
| A2 conditional bootstrap (count>0 blocks) | `a2_bootstrapDoesNotRecreateWhenUsersExist` | Panache + reflection | ✅ |
| A3 incomplete prod startup `[P]` | `a3_prodStartupFailsWithoutSecrets` | @Disabled residue | ⏭ |
| A4 assumed amnesia (empty-catalog hint) | `a4_emptyCatalogShowsAmnesiaHint` | RestAssured (form) | ✅ |
| A5 mirror seed & idempotence | `a5_mirrorSeedIsIdempotentByChecksum` | RestAssured (Basic) | ✅ |

Every scenario id the `## A.` section lists (A1–A5) is derived. Group A has no rows in
the Q inventory tables (Q-A…Q-E cover the engine/GraphQL/import/UI surfaces of other
letters), so there is nothing extra to fold in from Q for this letter.

## Mirror seed

- **Needed only by A5.** A5 *is* the seed scenario: it replays the seven CSV imports in
  the mandated order (Stores → StoreGroups → Products → ProductFamilies → Categories →
  Prices → Offers) through the HTTP import endpoints (Basic admin/admin), so the payloads
  are embedded in the class rather than mirrored in a `@BeforeAll` — the double pass (create
  then no-op) is the whole point of the test.
- A1/A2 are bootstrap-only (they read `AppUser` through Panache) and skip the referential
  entirely. A4 needs no referential either — it asserts the *empty* catalog.
- Observed first-pass created counts (all with `updatedCount:0`, no `errors`):
  Stores **6**, StoreGroups **4**, Products **33**, ProductFamilies **9**, Categories **30**,
  Prices **94**, Offers **11**. Second pass immediately: **0 / 0** on all seven. Counts are
  computed from the payload line count in-test (`expectedCreated`), not hard-coded.

## Classes written vs completed

- **Written from scratch:** `GroupAIT.java` (did not exist).
- No production source touched. Payloads are copied verbatim from the existing
  `*ImporterClient` seed classes (the price `<<START_DATE>>` placeholder resolved to the
  same `2026-01-12T00:00:00` the client uses), plus one added row (see below).

## Calibration findings (catalog vs reality)

1. **A1 — WARN log is not asserted; the DB state is.** The scenario quotes the startup WARN
   (`No user found: created bootstrap administrator 'admin'…`), but the log line is a
   side-effect of a boot that already happened before the test thread runs. The test asserts
   the *observable* outcome instead: the `admin` row with `roles = "VIEWER,MANAGER,ADMIN"`
   (canonical order, both as the raw string and as the ordered `getRoleSet()`),
   `displayName = "Bootstrap administrator"`, `active = true`, `mustChangePassword = true`.
2. **A4 — the amnesia hint is rendered as an HTML entity.** `list.html` emits
   `reset at every restart &mdash; run the CSV imports…`. The served body carries the literal
   `&mdash;`, so the assertion is split into two entity-free substrings
   (`The in-memory database is reset at every restart` and
   `run the CSV imports to load a catalog.`) plus the empty-filter line
   `No offer matches the current filters.` — never the em dash directly.
3. **A5 — the address-less store is `0106`, added to the seed.** The five stock stores all
   carry an address; the catalog explicitly requires idempotence "y compris un magasin sans
   adresse". So the store payload adds `0106|Intermarché Sans Adresse|||||||` (all address
   fields empty, no coordinates). The re-import is a no-op (`updatedCount:0`), confirming the
   symmetric address checksum — `Store.getChecksum()` normalises a null address to
   `new Adresse()` and the importer always writes empty strings, so both sides agree.

## Pitfalls encountered

- **`Offer::deleteAll` (method reference) is not enhanced.** Panache rewrites *call sites*
  of the inherited static `deleteAll()`; a method reference binds to
  `PanacheEntityBase.deleteAll()` and throws `implementationInjectionMissing`. Use a lambda
  (`() -> Offer.deleteAll()`) so the enhanced `Offer` call site is generated.
- **A2 re-invokes a package-private observer by reflection.** `UserBootstrap.onStart` is
  package-private in `…security`, so it is looked up via `Arc.container().instance(...)` and
  invoked reflectively inside a `QuarkusTransaction.requiringNew()` (its `AppUser.count()`
  read needs an active transaction). `new StartupEvent()` is the public no-arg constructor.
  With `count() > 0` the observer returns before any `persist`, so the count is unchanged and
  no second `admin` appears.
- **Order-independence on a shared in-memory DB.** The single H2 lives for the whole JVM,
  which is precisely this group's subject. The tests stay order-free anyway: only A5 seeds
  the referential (so those tables are empty whenever A5's first pass runs), and A4 clears
  the offer table itself before asserting the empty state — neither depends on the other's
  position. No `@TestMethodOrder` is used.
- **admin/admin has `mustChangePassword=true` even in `%test`.** The enforcement filter is
  off (`%test.app.password-change.enforced=false`), so the admin form login lands on
  `/ui/offers` and A4/A5 authenticate without the forced-password redirect.

## Justified residue

- **A3 `[P]` — incomplete prod startup.** Reproducing a boot failure on a missing
  `VALUATION_ADMIN_PASSWORD` / `VALUATION_SESSION_KEY` requires starting a second,
  deliberately-misconfigured application context under the prod profile. No such prod-like
  boot harness exists, so A3 is `@Disabled` with the reason and counted as the group's single
  residue (1 of 5).
</content>
