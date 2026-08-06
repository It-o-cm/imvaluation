# E2E campaign — Group C (Accounts & roles)

Catalog section: `## C. Comptes & rôles` of `e2e-scenarios.md`.
Test class: `src/test/java/com/intermarche/valuation/e2e/GroupCIT.java` — **written from scratch**
(did not exist).

Campaign command:
`mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupCIT -DskipITs=false`

Final result: **Tests run: 6, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS.
Iterations to green: **3** (initial + 2 calibration passes).

## Scenarios derived and their tier

| Id | Scenario | Tier | Notes |
|----|----------|------|-------|
| C1 | Account creation — ordered refusals then success | RestAssured (Basic) | 4 refusals in order + 303 success, `mustChangePassword` forced |
| C2 | Edition — immutable username, password reset semantics, 404 | RestAssured (Basic) | GET form hint + blank-vs-supplied password + unknown-id 404 on GET & POST |
| C3 | Last-administrator guards | RestAssured (Basic) | demote/disable (200), delete-last (303), delete-self (303), unknown-id (303) |
| C4 | Non-hierarchical roles | RestAssured (Basic) + GraphQL | MANAGER↔ADMIN query/mutation trap, imports=ADMIN, /valuation=any authenticated |
| C5 | VIEWER journey in the UI | RestAssured (Basic) | list buttons/nav gating + 403 on write endpoints |
| C6 | Role sanitisation | RestAssured (Basic) | unknown role dropped; unchecked `active` ⇒ disabled |

All six C scenarios are **unmarked** in the catalog → pure HTTP RestAssured with HTTP Basic
(`admin/admin` for the admin surface, purpose-built single-role accounts for C4/C5). No `[W]`
(browser) nor `[P]` (prod-like) scenarios in this letter.

**No `@Disabled` residue.** Every scenario is implemented and green.

## Mirror seed

**Not needed.** Group C exercises accounts and authorization, not the referential:
- accounts are created directly through Panache (`ensureUser`, `QuarkusTransaction`);
- the GraphQL security matrix (C4) only observes the authorization outcome — an empty
  catalog answers every query (`allStores` → `[]`), which is enough to prove the guard;
- the import guards (C4) refuse on role **before** any CSV is parsed.

The 7-CSV replay was therefore skipped entirely.

## Calibration findings (catalog vs observed reality)

1. **Notice apostrophes are percent-encoded in the redirect `Location`.** The catalog quotes
   `Account '{u}' created.`; the actual `Location` is
   `…/ui/users?notice=Account+%27c1ok%27+created.&noticeOk=true`. Assertions use `%27`.

2. **The validation banner HTML-escapes the apostrophe (`&#39;`).** `An account named
   'admin' already exists.` renders in the re-served form as
   `An account named &#39;admin&#39; already exists.` (Qute auto-escape). The C1 duplicate-
   refusal asserts the escaped form — same class of quirk the JS suite documents.

3. **Media-type match precedes authorization on the import endpoints.** A `POST
   /stores/import` with no `Content-Type` answers **415**, not 403 — the JAX-RS media-type
   negotiation (`@Consumes(text/plain, octet-stream)`) runs before `@RolesAllowed`. The C4
   import-guard calls send `Content-Type: text/plain` with an empty body so the request
   reaches the security layer and yields the expected **403** (MANAGER/VIEWER) / non-403
   (ADMIN).

4. **C3 "all in 303" is only partly true.** The catalog states every last-admin guard answers
   303 + notice. Observed: only the three **delete** guards go through `redirectWithNotice`
   (303 — `The last administrator cannot be deleted.`, `You cannot delete your own account.`,
   `Account not found.`, all with `noticeOk=false`). The **demote/disable** guard is an
   *update* validation failure → `Response.ok(form)` = **200** re-render carrying
   `This is the last administrator: keep the role and the account enabled.` Asserted as
   observed.

5. **GraphQL `@RolesAllowed` denial → HTTP 200 with an `"errors"` array** (SmallRye rendering,
   the F3 unknown). A granted operation answers 200 with a `"data"` payload and no `"errors"`
   key. The C4 matrix asserts on the presence/absence of the `"errors"` key rather than on a
   fragile message, which cleanly captures both directions of the trap:
   MANAGER→`allStores` OK / `createStore` denied; ADMIN→`createStore` OK / `allStores` denied.

## Pitfall solved — C3 isolation vs the bootstrap admin

`isLastActiveAdmin(user)` is `user.active && user.hasRole(ADMIN) && countActiveAdmins() <= 1`.
Because the bootstrap `admin` is *always* an active administrator, no other account can ever
be "the last active administrator" while it stays enabled, and the delete-self guard fires
before the last-admin guard for the caller itself. C3 therefore:
- creates a disposable **sole active admin** (`c3admin`) and an **inactive admin** caller
  (`c3caller`, which still authenticates — cf. B6 — and passes `@RolesAllowed(ADMIN)` but is
  not counted as active);
- calls `makeSoleActiveAdmin("c3admin")` to disable every *other* active admin (including the
  bootstrap `admin`) for the duration of the guarded calls, restoring them in a `finally`;
- exercises delete-self and unknown-id against the untouched bootstrap `admin`.

A closing assertion verifies the bootstrap admin survives active + ADMIN, so the class leaves
the shared account usable for the rest of the suite. Every account name is unique per method,
so scenarios stay order-independent (CLAUDE.md isolation rule).

## Transverse guards honoured

- Redirects never followed (`redirects().follow(false)`); `Location` matched with
  `containsString` on relative fragments.
- 403/404/303 asserted on status + literal message; no absolute ids or counters (ids resolved
  by username through Panache).
- GraphQL matrix asserted by authorization outcome, not by index into any HashSet.

## Justified residue

**None.** All six scenarios implemented and green; no `[P]`/`[W]` scenarios exist in group C.

## Scope

Touched only `src/test/java/com/intermarche/valuation/e2e/GroupCIT.java` and this report.
Nothing under `src/main` modified. No bug or testability obstacle found.
