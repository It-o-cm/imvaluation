# E2E campaign — Group D (CSV imports, the common mechanics)

Spec section: `e2e-scenarios.md` § **D. Imports CSV — mécanique commune** (D1–D9).
Class: `src/test/java/com/intermarche/valuation/e2e/GroupDIT.java` — **written from scratch**
(did not exist before this campaign).

Campaign command:

```
mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupDIT -DskipITs=false
```

Final result: **BUILD SUCCESS** — `Tests run: 9, Failures: 0, Errors: 0, Skipped: 1`
(8 active scenarios green, D8 a justified `@Disabled` residue).

## Tiering — every scenario derived from the catalog

All of D1–D9 are **unmarked** in the catalog → the whole group is `@QuarkusTest` + RestAssured,
pure HTTP, HTTP Basic (`admin/admin`, plus a purpose-built MANAGER for D9). No `[P]`, `[W]` or
`[D]`-specific scenario in this letter.

| Scenario | Tier | Endpoint driven | Verdict |
|---|---|---|---|
| **D1** Nominal + empty lines ignored | RestAssured | `/stores/import` | ✅ green |
| **D2** Not enough columns / extra columns | RestAssured | `/stores/import` | ✅ green |
| **D3** Malformed error-JSON contract | RestAssured | `/stores/import` | ✅ green |
| **D4** Leading empty line shifts header into data | RestAssured | `/prices/import` | ✅ green |
| **D5** Staged transactional fallback | RestAssured | `/stores/import` | ✅ green |
| **D6** Idempotence by checksum | RestAssured | `/stores/import` | ✅ green |
| **D7** In-file duplicate key, last wins | RestAssured | `/stores/import` | ✅ green |
| **D8** Stream errors (500) | `@Disabled` residue | — | ⏭ residue |
| **D9** Insufficient role (MANAGER → 403) | RestAssured | all 7 imports | ✅ green |

## Mirror seed — NOT needed

The group needs **no referential seed**. Every scenario imports its own throwaway rows, and the
generic mechanics under test (header skip, column count, staged fallback, checksum, in-file
duplicate) never require a pre-existing catalog. Most scenarios drive the dependency-free
`/stores/import` (Store: unique `code`, mandatory `name`, no foreign keys). D4 deliberately drives
`/prices/import` so that the header row consumed as data hits a **clean business error**
(`Product with EAN EAN not found.`). D9 creates a single MANAGER account through Panache.
Isolation from the shared-JVM H2 database is kept via per-scenario code prefixes (`D1…`, `D5S…`,
`D6S1`, `D7DUP`).

## Classes written vs completed

- **Written:** `GroupDIT.java` (new). No pre-existing D class to complete.
- Touched nothing else under `src/main` (scope respected); no seed/golden resource needed.

## Iterations to green

1. **Iteration 0 — compile failure.** The Javadoc contained `{@code /*/import}`; the `*/` inside it
   closed the block comment early and cascaded into ~50 bogus parse errors. Reworded to
   "the seven CSV import endpoints" (two occurrences).
2. **Iteration 1 — D5 calibration.** Expected `Line 13 (D5BAD):`; observed `Line 14 (D5BAD):`.
   The faulty row is the 13th **data** row, but the header is physical line 1, so `lineNumber`
   makes it **line 14**. Also observed the isolated message is the Narayana wrapper
   `ARJUNA016053: Could not commit transaction.`, not the raw `@NotBlank` text. Assertion + Javadoc
   corrected.
3. **Iteration 2 — green.** `Tests run: 9, Failures: 0, Skipped: 1`, BUILD SUCCESS.

## Calibration findings (catalog vs observed reality)

- **D3 malformed JSON — confirmed as a de-facto contract.** `buildAnswer` joins error messages
  with `","` and appends a trailing `"]` but never prepends the opening quote of the first
  message. With two errors the body reads `"errors":[Line 2 … firstbad","Line 3 … secondbad|x"]}`.
  Assertions are strictly textual (`contains`), the well-formed `"errors":["` prefix is asserted
  **absent**. A JSON parse would (correctly) reject this body.
- **D4 header-as-data is resource-dependent.** The catalog says the shifted header "fails as a
  business error". On `/stores/import` it would **not** — the header `code|name|…` would silently
  create a junk store named `name`. Choosing `/prices/import` makes the trap surface as a genuine
  business error (`Line 2 (EAN): Product with EAN EAN not found.`), faithful to the catalog wording.
- **D5 line numbering.** `lineNumber` counts **physical** lines including the header (line 1) and
  blank lines (which increment then `continue`). The faulty 13th data row is reported as line 14.
- **D5 isolated-row message.** The `@NotBlank Store name is mandatory` violation fires at commit,
  so the message reaching `errors[]` is the JTA/Narayana commit wrapper
  (`ARJUNA016053: Could not commit transaction.`), not the constraint text. Only the deterministic
  `Line 14 (D5BAD):` prefix and the **24 survivors** are pinned — that pair is the observable proof
  of the staged best-effort fallback (a global all-or-nothing would create 0; a naive
  line-by-line would not have retried in bulk first). The WARN log
  `Failed to process chunk of size N with step X. Retrying with step Y` is emitted (visible in the
  run output) but not asserted — see residue.
- **D6 checksum idempotence.** First import `createdCount:1`; a byte-identical re-import returns the
  exact `{"createdCount":0, "updatedCount":0}` and leaves the persisted `updated_at` **untouched**
  (no `@PreUpdate` fires when the entity is not dirtied). A single changed field returns
  `{"createdCount":0, "updatedCount":1}` and persists the new value. All asserted on both the HTTP
  body and the Panache DB state.
- **D7 last-line-wins.** Two rows with the same store code fail the bulk chunk on the unique
  constraint, then the staged fallback creates the first and turns the second into an update at
  level 1 → `createdCount:1, updatedCount:1`, DB name = `Second Name`.
- **D9.** The `@RolesAllowed("ADMIN")` guard is enforced before the body is read, so an empty
  payload from an authenticated MANAGER still yields **403** (never a 401 challenge) on each of the
  seven endpoints, including `/product-category-storages/import` (the "Categories" import).

## Pitfalls encountered

- `{@code /*/import}` in Javadoc silently closes the comment (`*/`) → catastrophic compile cascade.
  Never embed a `*/` glob in a doc comment.
- `lineNumber` includes the header and blank lines — off-by-one traps when predicting the reported
  line of a faulty data row.
- The staged fallback surfaces the JTA **commit** wrapper, not the underlying constraint message;
  assert on the stable `Line N (code):` prefix rather than the volatile tail.

## Justified residue

- **D8 — stream errors (`@Disabled`).** Both paths — an `IOException` while reading the request body
  (500 `Error reading file: <msg>`) and an unexpected `Throwable` (500 `Unexcepted error: <msg>`,
  the typo pinned verbatim by the catalog) — require **infrastructure-level fault injection**
  (aborted/truncated body, corrupted transfer encoding, forced unchecked throw). A well-formed
  RestAssured request drives a fully-buffered, well-formed stream and cannot reach either `catch`
  branch. Listed as residue with a one-line `@Disabled` reason; the two 500 texts are otherwise
  reachable only through unit-level tests of `ImporterCsvResource`, outside this HTTP e2e surface.

Not committed — the campaign script owns the commit.
