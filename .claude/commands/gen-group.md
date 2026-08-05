Generate (or complete) the test classes for scenario group $ARGUMENTS,
following CLAUDE.md.

1. Read the group's entry in imvaluation-test-kit.md §2 (VA / OF / GE / PR /
   IM / GQ). That entry is a SKELETON, not the catalog.
2. Derive the concrete cases BY GREP OF THE CODE, never from memory — this is
   the method proven on impos: the catalog is derived from the surface of the
   code. Grep the relevant packages under src/main/java/com/intermarche/valuation
   (appliers under engine/offers, resolution under engine, imports under
   imports, GraphQL under graphql, the /valuation contract under ui/domain) to
   enumerate the real methods, branches, offer types, columns and mutations,
   then turn each into a case. The kit's bullets tell you what to look for; the
   code tells you what is actually there.
3. Write the test classes, or COMPLETE existing ones. NEVER duplicate a test
   class that already exists: if src/test/.../XTest.java is present, add the
   missing cases to it in place — do not create a second class for the same
   production class.
   - OF / GE / PR are unit-pur (U): JUnit 5 + Mockito, no @QuarkusTest, appliers
     as pure logic per CLAUDE.md; add the versioned golden pairs under
     src/test/resources/valuation for the /valuation scenarios.
   - VA / IM / GQ are RestAssured (R): *IT classes run under failsafe.
4. Loop with the TARGETED mvn until green AND 100% branch coverage of the
   targeted classes (report the branch count, not only the percentage):
   - U groups: mvn -q -Dtest=<TestClass> test, then mvn -q verify and read
     JaCoCo, filling every uncovered branch.
   - R groups: mvn -q verify -DskipUTs=true -Dit.test=<ClassIT> -DskipITs=false
     (skipUTs so the unit suite is not re-run; skipITs=false so failsafe does
     not report a false green).
   Iterate until truly green.
5. Write the full report to reports/<group>.md (create the directory if needed):
   cases derived and the grep that produced them, classes written vs completed,
   branch count n/n per class, iterations, hard points, and any justified
   residue. Do not commit — the campaign script owns the commit.
