Complete the test class for $ARGUMENTS following CLAUDE.md, then make it
pass. If a test class already exists for $ARGUMENTS, COMPLETE it — add the
missing cases to reach full branch coverage — never replace or truncate the
existing tests. Apply the per-class workflow: read the target class,
enumerate every branch, write/extend the test, run
mvn -q -Dtest=<TestClass> -DskipITs test until green, then run the targeted
mvn -q -Dtest=<TestClass> -DskipITs verify and read the JaCoCo report for the
target class to fill any uncovered branch or line. Finish with the report:
branch count n/n, coverage %, files read, iterations, and any justified
residue.
