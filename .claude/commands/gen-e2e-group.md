Generate (or complete) the e2e scenario test class for group $ARGUMENTS,
following CLAUDE.md (§ "E2E scenario tests (imvaluation — *IT classes)").

1. Read the group's SECTION in e2e-scenarios.md — the whole letter block for
   $ARGUMENTS (e.g. "## G. …"). That section is the catalog: enumerate EVERY
   scenario id it lists (G1, G2, …) plus any Q-inventory rows that belong to
   this letter's surface. Derive the concrete assertions from the LITERAL texts
   the catalog quotes and by grep of the code that produces them
   (src/main/java/com/intermarche/valuation) — never from memory.
2. One test class only: src/test/java/com/intermarche/valuation/e2e/Group${ARGUMENTS}IT.java.
   NEVER duplicate it: if it already exists, COMPLETE it in place — add the
   missing scenarios, never truncate the existing ones. One @Test per scenario,
   the scenario id in the method name AND in its Javadoc.
3. Tier each scenario by its catalog mark:
   - unmarked → @QuarkusTest + RestAssured, pure HTTP, Basic auth (admin/admin).
   - [W]      → @QuarkusTest + Playwright (quarkus-playwright, headless
     Chromium); neutralize the workbench beforeunload and tolerate the 1 s
     valuations auto-refresh.
   - [P]      → prod-like profile: implement ONLY if a prod-like harness
     (@TestProfile with app.password-change.enforced=true + a forced admin)
     already exists; otherwise mark @Disabled with a one-line justified reason
     and count it as residue.
4. Seed — ONLY if the group's scenarios need the referential. When they do,
   mirror the catalog ONCE at class start by replaying the 7 CSV imports in the
   MANDATED order (Stores → StoreGroups → Products → ProductFamilies →
   Categories → Prices → Offers) through the HTTP import endpoints (admin/admin
   Basic). Auth-only / bootstrap-only groups skip the seed entirely.
5. Transverse guards from the catalog and CLAUDE.md:
   - offers/advantages are HashSets → NEVER assert by index, always find by
     predicate.
   - 4xx/5xx bodies of /valuation carry no entity → assert the message in
     valuation_traces.error_message (Panache under QuarkusTransaction); calibrate
     raw bodies ONCE before any textual assertion.
   - Redirects: given().redirects().follow(false); match Location with
     containsString (absolute or relative). Never assert a followed body for a
     redirect step.
   - Static /ui/*.css and /ui/*.js are served anonymously (200) — a known
     catalog defect; assert 200, never a redirect, for non-login assets.
   - Assert the LITERAL texts the catalog quotes. Never absolute ids or counters.
6. Loop the TARGETED campaign command until truly green (residual @Disabled
   [P]/[W]-without-harness scenarios are acceptable):
   mvn -q verify -DskipUTs=true -DskipJsTests=true -Dit.test=Group${ARGUMENTS}IT -DskipITs=false
   (skipUTs so the unit suite is not re-run; skipITs=false so failsafe does not
   report a false green.)
7. Write the report to reports/e2e-${ARGUMENTS}.md: every scenario derived and
   its tier (RestAssured / Playwright / @Disabled residue), whether the mirror
   seed was needed, classes written vs completed, iterations to green,
   calibration findings (catalog vs observed reality), pitfalls, and the
   justified residue. Do NOT commit — the campaign script owns the commit.
8. Scope: touch only Group${ARGUMENTS}IT.java, its seed/golden resources, and
   reports/e2e-${ARGUMENTS}.md. Never modify anything under src/main. If a bug or
   an obstacle to testability is found, stop and report it in one line.
