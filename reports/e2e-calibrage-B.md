# E2E calibration — Group B (authentication & session)

Class: `src/test/java/com/intermarche/valuation/e2e/GroupBIT.java` —
`@QuarkusTest` + RestAssured, pure HTTP. Run by failsafe as an `*IT` class.

## Command & result

```
mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=GroupBIT -DskipITs=false
```

- **BUILD SUCCESS.** `Tests run: 9, Failures: 0, Errors: 0, Skipped: 2`.
- **7 scenarios pass** (B1, B2, B3, B4, B5, B6, B8); **2 skipped** (B7, B9 — `[P]` residue).
- Test execution: **3.06 s** (7 active tests). Whole `verify` wall-clock: **~22 s**
  (the Quarkus `package`/build dominates; the in-JVM app boot + tests are ~3 s).
- **Iterations to green: 2** — first run red on B4 and B5 (2 failures); one probe run to
  capture the raw status/headers; calibrated assertions → green. No test logic churned
  beyond those two.

## Scenario map

| Scenario | Method | Status |
|---|---|---|
| B1 redirect chain + session cookie | `b1_nominalRedirectChainSetsSessionCookie` | ✅ |
| B2 rejected login, message only for `error=true` | `b2_invalidCredentialsMessageOnlyForErrorTrue` | ✅ (corrected behavior) |
| B3 logout clears cookie + notice | `b3_logoutClearsCookieAndShowsNotice` | ✅ |
| B4 public paths | `b4_publicPathsAndStaticAssetsAreServedAnonymously` | ✅ (asserts observed reality — see finding 1) |
| B5 Basic vs form | `b5_apiPathsChallengeInBasicUiHonoursBasicHeader` | ✅ (see finding 2) |
| B6 disabled account still signs in | `b6_disabledAccountStillSignsIn` | ✅ |
| B8 five password refusals + success | `b8_passwordChangeRefusalsThenSuccess` | ✅ |
| B7 forced password change `[P]` | `b7_forcedPasswordChangeRedirect` | ⏭ `@Disabled` residue |
| B9 admin reset loop `[P]` | `b9_adminResetForcesChangeLoop` | ⏭ `@Disabled` residue |

## Pitfalls encountered

- **RestAssured follows redirects by default.** Every redirect scenario must set
  `.redirects().follow(false)`, otherwise the 303/302 is swallowed and the `Location`
  header is lost. All redirect steps of B1/B2/B3/B4 are asserted step by step with
  `containsString` on `Location` (it may be absolute or relative).
- **Form authentication.** `POST /j_security_check` is not a JAX-RS resource; it is driven
  with `.formParam("j_username"/"j_password")` (urlencoded). With no prior
  `quarkus-redirect-location` cookie, success lands on the configured `landing-page`
  (`/ui/offers`) — matching B1 — and sets `quarkus-credential`.
- **Session cookie handling.** Extract with `.extract().detailedCookie("quarkus-credential")`
  (needed for B3, which asserts the logout clears it with `maxAge 0`), re-send with
  `.cookie(name, value)` for authenticated posts (B3, B8).
- **Admin bootstrap.** The bootstrap account is `admin/admin` with `mustChangePassword=true`
  **even in the test profile** (the flag is always set; only the *enforcement filter* is
  profile-gated). So B8's first refusal (`The current password is incorrect.`) is
  unreachable as admin — forced mode skips the current-password check. B8 therefore creates
  a dedicated non-forced user via `QuarkusTransaction.requiringNew()` + Panache.
- **`[P]` profile gap.** `%test.app.password-change.enforced=false` disables the forced
  password-change redirect, so B7/B9 cannot be reproduced under the default test profile.
  With no prod-like harness they are `@Disabled` and reported as justified residue.
- **`@QuarkusTest` as an `*IT` class** runs in-JVM under failsafe (not
  `@QuarkusIntegrationTest`); the campaign command drives it correctly.

## Calibration findings (catalog vs reality)

1. **B4 — static UI assets are world-readable (catalog is wrong).** The catalog states that
   `/ui/offer.css`, `/ui/valuation.css` and every `*.js` are *refused* (302 to /ui/login)
   without a session. **Observed: they return 200 anonymously.** Static resources under
   `META-INF/resources` are served before the `quarkus.http.auth.permission` layer, so the
   explicit permit list (`/ui/login,/ui/base.css,/ui/auth.css`) does **not** actually gate
   the others — the whole `/ui/*.css` and `/ui/*.js` surface is public. The test asserts the
   observed 200 and flags it. **This is a real security divergence to arbitrate:** either add
   an authenticated policy covering the non-login assets, or correct catalog B4.
2. **B5 — the 401 carries no `WWW-Authenticate` header.** `/valuation`, `/graphql` and the
   imports answer `401` (never a 3xx redirect — the catalog's core claim holds), but with no
   `WWW-Authenticate: Basic` challenge header in this configuration. The test asserts the
   401 (and absence of redirect) only; the missing header is documented, not asserted.
3. **B3 — `GET /ui/logout` → 405** (Method Not Allowed), not 404. Calibrated to 405.

## Proposed additions to the contract (for the rafale) — NOT applied

```markdown
## E2E scenario tests — burst conventions (proposed)
- Shared base: an abstract E2eBase with helpers reused by every GroupXIT —
  nofollow() (given().redirects().follow(false)), signIn(user,pass)->cookie,
  ensureUser(...), and seed() (the 7 imports in order). Avoids per-class drift.
- Redirects: ALWAYS assert with follow(false); match Location with
  containsString (it may be absolute or relative). Never assert a followed body
  for a redirect step.
- Auth: form login via formParam(j_username/j_password) on /j_security_check;
  reuse quarkus-credential via detailedCookie/cookie. API paths are Basic-only:
  assert 401 (status is the challenge; do NOT assert WWW-Authenticate — it is
  absent in this config).
- Static assets: /ui/*.css and /ui/*.js are served anonymously (200) — they
  bypass the permit layer. Until arbitrated, assert 200, never a redirect, for
  non-login assets; treat B4's "refused" wording as a KNOWN catalog defect.
- [P] scenarios need a prod-like harness: a @TestProfile that sets
  app.password-change.enforced=true with a fresh forced admin. Provide it once
  so B7/B9 (and any forced-change flow) run; otherwise keep them @Disabled with
  the reason, counted as justified residue in the group report.
- Bootstrap caveat: admin/admin has mustChangePassword=true; any scenario that
  needs the current-password check must create a non-forced user.
```
