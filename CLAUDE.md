# Test generation conventions

## Test architecture — NON-NEGOTIABLE
- Plain unit tests only: JUnit 5 + Mockito. NEVER @QuarkusTest,
  never H2, never boot the application for a class-level test.
- Mock all collaborators.
- Panache entities are NOT bytecode-enhanced under plain mvn test:
  static finders resolve to PanacheEntityBase — mock them with
  Mockito.mockStatic(PanacheEntityBase.class), and neutralize
  persist() with Mockito.mockConstruction(<Entity>.class).
- Static mocks go in try-with-resources blocks.
- Each test must be fully isolated: no shared state, no ordering
  dependency, assertions on absolute expected values.

## Style
- Code and comments in English.
- Javadoc on EVERY method without exception, test methods and
  private helpers included.
- Assertions: org.junit.jupiter.api.Assertions only — never AssertJ.
- No blank lines inside method bodies.
- One complete, compilable test class per production class.

## Coverage
- Target: 100% JaCoCo branch coverage on the target class.
- Systematically cover BOTH arms (null and non-null) of every
  ternary and null guard — do not wait for a JaCoCo re-run.
- Always report the branch count, not only the percentage.

## Scope — STRICT
- Never modify anything under src/main. If a bug or an obstacle
  to testability is found, stop and report it in one line.
- Touch only the test class being generated.

## Per-class workflow
1. Read the target class (and only what you actually need).
2. Enumerate every branch before writing.
3. Write the test class → mvn -Dtest=XTest test → fix until green.
4. mvn verify → read JaCoCo for the class → fill missing branches.
5. Report: branch count, coverage, files read, iterations.

## Offer appliers — PURE LOGIC, the heart of the engine
- The offer appliers are pure computation: test them WITHOUT mocking
  Panache whenever possible — build Basket/Offer objects in memory and
  assert the arithmetic directly. This is the core value of the engine,
  so aim for exhaustive to-the-cent coverage:
  - fractional quantities (kg),
  - HT / TTC / rate rounding,
  - cent residues,
  - multiple portions of the same line (the 2+1 of a 2FOR1).

## Golden files on /valuation
- For each offer scenario, version a pair (basket.json → expected
  evaluation.json). Any change in the engine's output breaks a golden:
  the contract with the till is protected.
- The example JSONs exchanged during phase 7 (2FOR1, multi-rate bundle,
  manual gestures, MEAL_VOUCHER, upsell) are the first goldens.

## Regression tests — historic defects, now GREEN
- Two historic defects (GraphQL createPrice missing priceUsage; CSV duplicate
  key read on wrong columns) were FIXED before this campaign. Pin them as GREEN
  regression tests: GQ must assert createPrice succeeds with priceUsage and
  rejects its absence; IM must assert that re-importing the same price CSV
  creates zero new rows (checksum no-op).

## JS tests — plain unit tests on the browser sources
- Bench: Vitest + jsdom + @vitest/coverage-istanbul. Specs in
  src/test/js/**/*.spec.js, one spec per source file. Run with
  `npx vitest run --coverage`, or as part of `mvn verify`
  (exec-maven-plugin, test phase). Skip with `-DskipJsTests=true`.
  Install deps once with `npm install`.
- The sources under META-INF/resources/ui/*.js are browser IIFEs, not
  ES modules, and are served verbatim: NEVER edit them to add exports.
  They are made importable IN MEMORY by the `expose-ui-iife` Vite
  plugin in vitest.config.js; tests reach their functions via
  loadScript() from src/test/js/harness.js. If a source is not
  exposable yet (multiple IIFEs, top-level return, auto-boot), EXTEND
  the plugin — never edit src/main and never drop coverage to dodge it.
- NEVER load a source with readFileSync + eval/new Function: it runs
  the code but bypasses Vite, so Istanbul reports 0% coverage (the JS
  twin of the JaCoCo attribution blindspot). Always go through
  loadScript()/import so coverage is attributed to the real file path.
- Coverage oracle: Istanbul BRANCH coverage on the target file, 100%
  or a residue justified line-by-line in the campaign report. Cover
  BOTH arms of every guard, ternary and `||`/`&&` fallback. Always
  report the branch count (n/n), not only the %.
- Style mirrors the Java suite: one describe per function, absolute
  expected values (assert to the cent for money/rate formatting),
  JSDoc on every helper and every test, English only.
- Split logic vs wiring: pure functions (formatters, resolvers, graph
  helpers) are asserted to the exact string/value; DOM wiring
  (build/render/drag-drop) is driven against a jsdom document set per
  test via document.body. Poll/interval code uses Vitest fake timers
  and a mocked fetch — never a real network call or a real clock.
- Assert the ACTUAL output, including quirks (e.g. a missing lineId
  renders `&amp;mdash;` because esc escapes the `&`). Report quirks in
  one line; never fix them under src/main during a test campaign.
