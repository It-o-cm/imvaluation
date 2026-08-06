package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Store;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Group D — CSV imports, the common mechanics — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application, authenticating with HTTP Basic as {@code admin/admin} (or a purpose-built
 * MANAGER for the role guard D9). The scenarios target the generic import framework shared by
 * the seven CSV import endpoints ({@code ImporterCsvResource}): header skipping, empty
 * line handling, column validation, the malformed-JSON response contract, the staged
 * transactional fallback (1000&nbsp;&rarr;&nbsp;100&nbsp;&rarr;&nbsp;10&nbsp;&rarr;&nbsp;1),
 * checksum idempotence and in-file duplicate keys.
 * <p>
 * The group needs no referential seed: every scenario imports its own throwaway rows. Most
 * scenarios drive the dependency-free {@code /stores/import} endpoint (a Store has a unique
 * {@code code}, a mandatory {@code name}, and no foreign keys); D4 drives {@code /prices/import}
 * because feeding a header row as data there yields a clean business error
 * ({@code Product with EAN EAN not found.}), which is the trap the scenario documents.
 * <p>
 * Unique code prefixes per scenario keep the class isolated from itself even though the H2
 * database lives for the whole JVM.
 * <p>
 * CALIBRATION — D3 malformed JSON: {@code buildAnswer} joins the error messages with
 * {@code "\",\""} and appends a trailing {@code "\"]"}, but never prepends an opening quote to
 * the first message. With two errors the body therefore reads
 * {@code "errors":[msg1","msg2"]} — the first opening quote is missing. Assertions are textual
 * ({@code contains}), never a JSON parse, and pin the absence of the well-formed {@code ["}
 * prefix.
 * <p>
 * CALIBRATION — D5 isolated-line message: the faulty D5 row fails at commit on the
 * {@code @NotBlank Store name is mandatory} bean-validation constraint, so the isolated error
 * carries the Narayana commit wrapper ({@code ARJUNA016053: Could not commit transaction.})
 * rather than a hand-thrown message. The assertion pins the {@code Line 14 (D5BAD):} prefix
 * (the header is physical line 1, so the 13th data row is physical line 14) and the 24 survivors,
 * the observable proof of the staged best-effort fallback (a global all-or-nothing would create
 * zero, a naive line-by-line would not have retried in bulk first).
 * <p>
 * CALIBRATION — D8 stream faults: an {@code IOException} while reading the request body (500
 * {@code Error reading file:}) and an unexpected {@code Throwable} (500 {@code Unexcepted
 * error:}, typo pinned by the catalog) both require infrastructure-level fault injection
 * (aborted body, corrupted transfer encoding) that a well-formed RestAssured request cannot
 * produce. D8 is listed as justified residue ({@code @Disabled}).
 */
@QuarkusTest
class GroupDIT {

    /**
     * The seven import endpoints in the mandated referential order, shared by D9.
     */
    private static final String[] IMPORT_ENDPOINTS = {
            "/stores/import",
            "/store-groups/import",
            "/products/import",
            "/product-families/import",
            "/product-category-storages/import",
            "/prices/import",
            "/offers/import"
    };

    /**
     * Posts a raw CSV body to an import endpoint as {@code admin/admin} over HTTP Basic.
     *
     * @param path The import endpoint path.
     * @param csv  The raw CSV payload (pipe-separated, first line is the header).
     * @return The response body as a string, for textual assertions.
     */
    private String importCsv(String path, String csv) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post(path)
                .then().statusCode(200)
                .extract().asString();
    }

    /**
     * Ensures a MANAGER-only account exists for the D9 role guard, creating it when absent.
     *
     * @param username The login name.
     * @param password The clear-text password.
     */
    private void ensureManager(String username, String password) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.findByUsername(username) != null) {
                return;
            }
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(password);
            user.setRoleSet(Set.of(AppUser.ROLE_MANAGER));
            user.displayName = username;
            user.active = true;
            user.mustChangePassword = false;
            user.persist();
        });
    }

    // --------------------------------------------------
    // D1 — nominal import, empty lines silently ignored
    // --------------------------------------------------

    /**
     * D1 — nominal. Posting a raw pipe-separated CSV ({@code Content-Type: text/plain}, first
     * line always skipped as the header) answers 200 with the exact body
     * {@code {"createdCount":2, "updatedCount":0}}; a blank line between the two data rows is
     * ignored silently (it consumes a {@code lineNumber} but produces neither a row nor an
     * error).
     */
    @Test
    void d1_nominalImportEmptyLinesIgnored() {
        String csv = "code|name|s1|s2|pc|city|country|lat|lon\n"
                + "D1S1|D1 Store One|1 rue||59000|Lille|FR|50.6|3.0\n"
                + "\n"
                + "D1S2|D1 Store Two|2 rue||59000|Lille|FR|50.7|3.1\n";
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post("/stores/import")
                .then().statusCode(200)
                .body(containsString("{\"createdCount\":2, \"updatedCount\":0}"))
                .body(not(containsString("errors")));
    }

    // --------------------------------------------------
    // D2 — not enough columns collected as an error, extra columns accepted
    // --------------------------------------------------

    /**
     * D2 — column count. A line with fewer than the required columns is collected into
     * {@code errors} as {@code Line N ignored (not enough columns): <line>} while the import
     * still answers 200; a line carrying extra columns is accepted without any noise and is
     * counted as created.
     */
    @Test
    void d2_notEnoughColumnsCollectedExtraAccepted() {
        String csv = "code|name|s1|s2|pc|city|country\n"
                + "D2S1|D2 Store|1 rue||59000|Lille|FR|50.6|3.0|EXTRA\n"
                + "D2SHORT|only three\n";
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post("/stores/import")
                .then().statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("Line 3 ignored (not enough columns): D2SHORT|only three"));
    }

    // --------------------------------------------------
    // D3 — the error JSON is malformed by design (de-facto contract)
    // --------------------------------------------------

    /**
     * D3 — malformed JSON contract. When the response carries errors, the body joins the
     * messages with {@code ","} and closes with {@code "]} but omits the opening quote of the
     * first message, so it reads {@code "errors":[msg1","msg2"]}. The assertions are purely
     * textual: the absence of the well-formed {@code ["} prefix, the {@code ","} join between
     * the two messages, and the closing {@code "]}} are pinned as the de-facto contract.
     */
    @Test
    void d3_malformedErrorJsonContract() {
        String csv = "code|name|s1|s2|pc|city|country\n"
                + "firstbad\n"
                + "secondbad|x\n";
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post("/stores/import")
                .then().statusCode(200)
                .body(containsString("\"errors\":[Line 2 ignored (not enough columns): firstbad"))
                .body(containsString("firstbad\",\"Line 3 ignored (not enough columns): secondbad|x"))
                .body(containsString("secondbad|x\"]}"))
                .body(not(containsString("\"errors\":[\"")));
    }

    // --------------------------------------------------
    // D4 — a leading empty line shifts the header into the data
    // --------------------------------------------------

    /**
     * D4 — first line empty. A file beginning with a blank line consumes {@code lineNumber 1}
     * on the empty line, so the real header lands on {@code lineNumber 2} and is processed as
     * data instead of being skipped. On {@code /prices/import} the header row is read as a
     * price whose EAN is the literal {@code EAN}: it fails the business lookup and is isolated
     * as {@code Line 2 (EAN): Product with EAN EAN not found.} with zero rows created. The
     * documented trap.
     */
    @Test
    void d4_leadingEmptyLineShiftsHeaderIntoData() {
        String csv = "\n"
                + "EAN|StoreCode|PriceExcludingTax|PriceIncludingTax|VatRate|PriceUsage|Priority|StartDateTime|EndDateTime\n";
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post("/prices/import")
                .then().statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("Line 2 (EAN): Product with EAN EAN not found."));
    }

    // --------------------------------------------------
    // D5 — staged transactional fallback: best effort, faulty line isolated
    // --------------------------------------------------

    /**
     * D5 — staged fallback. A 25-row file with a single faulty row in the middle (a store with
     * an empty, mandatory name) fails the bulk chunk, then the framework retries with smaller
     * batches (1000&nbsp;&rarr;&nbsp;100&nbsp;&rarr;&nbsp;10&nbsp;&rarr;&nbsp;1) until the 24
     * healthy rows are created and the faulty one is isolated as {@code Line N (D5BAD): …}.
     * Neither a global all-or-nothing (which would create zero) nor a naive line-by-line: proven
     * best effort. The isolated message is the bean-validation wrapper (see class calibration);
     * only the deterministic prefix and the 24 survivors are pinned.
     */
    @Test
    void d5_stagedTransactionalFallbackIsolatesFaultyLine() {
        StringBuilder csv = new StringBuilder("code|name|s1|s2|pc|city|country|lat|lon\n");
        for (int i = 1; i <= 25; i++) {
            if (i == 13) {
                // 7+ columns (passes the column check) but an empty mandatory name.
                csv.append("D5BAD||1 rue||59000|Lille|FR\n");
            } else {
                csv.append(String.format("D5S%02d|D5 Store %02d|1 rue||59000|Lille|FR|50.6|3.0%n", i, i));
            }
        }
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv.toString())
                .when().post("/stores/import")
                .then().statusCode(200)
                .body(containsString("\"createdCount\":24"))
                // The header is physical line 1, so the 13th data row is physical line 14.
                .body(containsString("Line 14 (D5BAD):"));
        int[] created = new int[1];
        QuarkusTransaction.requiringNew().run(() ->
                created[0] = (int) Store.count("code like ?1", "D5S%"));
        assertEquals(24, created[0], "Exactly the 24 healthy rows must survive the staged fallback");
    }

    // --------------------------------------------------
    // D6 — idempotence by checksum: identical re-import writes nothing
    // --------------------------------------------------

    /**
     * D6 — checksum idempotence. A first import creates the row; a strictly identical re-import
     * answers {@code updatedCount:0} and writes nothing (the persisted {@code updated_at} stays
     * unchanged because no {@code @PreUpdate} fires); changing a single field re-imports as
     * {@code updatedCount:1} and the new value is persisted.
     */
    @Test
    void d6_idempotenceByChecksum() {
        String initial = "code|name|s1|s2|pc|city|country|lat|lon\n"
                + "D6S1|D6 Store|1 rue||59000|Lille|FR|50.6|3.0\n";
        String created = importCsv("/stores/import", initial);
        assertNotNull(created);
        assertEquals(true, created.contains("\"createdCount\":1"), "The first import must create the row");
        LocalDateTime[] firstStamp = new LocalDateTime[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("D6S1");
            assertNotNull(store, "The imported store must exist");
            firstStamp[0] = store.updatedAt;
        });
        String again = importCsv("/stores/import", initial);
        assertEquals(true, again.contains("{\"createdCount\":0, \"updatedCount\":0}"),
                "A strictly identical re-import must write nothing");
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("D6S1");
            assertEquals(firstStamp[0], store.updatedAt,
                    "An idempotent re-import must leave updated_at untouched");
        });
        String changed = "code|name|s1|s2|pc|city|country|lat|lon\n"
                + "D6S1|D6 Store Renamed|1 rue||59000|Lille|FR|50.6|3.0\n";
        String updated = importCsv("/stores/import", changed);
        assertEquals(true, updated.contains("{\"createdCount\":0, \"updatedCount\":1}"),
                "Changing a single field must be counted as one update");
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("D6S1");
            assertEquals("D6 Store Renamed", store.name, "The updated value must be persisted");
        });
    }

    // --------------------------------------------------
    // D7 — duplicate key inside the same file: last line wins
    // --------------------------------------------------

    /**
     * D7 — in-file duplicate key. Two lines sharing the same store code make the bulk chunk fail
     * on the unique constraint; the staged fallback then creates the first line and turns the
     * second into an update at level 1, so the result is {@code createdCount:1, updatedCount:1}
     * and the last line wins in the database.
     */
    @Test
    void d7_duplicateKeyLastLineWins() {
        String csv = "code|name|s1|s2|pc|city|country|lat|lon\n"
                + "D7DUP|First Name|1 rue||59000|Lille|FR|50.6|3.0\n"
                + "D7DUP|Second Name|2 rue||59000|Lille|FR|50.7|3.1\n";
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post("/stores/import")
                .then().statusCode(200)
                .body(containsString("\"createdCount\":1, \"updatedCount\":1"));
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("D7DUP");
            assertNotNull(store, "The duplicated key must leave exactly one store");
            assertEquals("Second Name", store.name, "The last line must win");
        });
    }

    // --------------------------------------------------
    // D8 — stream errors — justified residue
    // --------------------------------------------------

    /**
     * D8 — stream errors. An {@code IOException} while reading the body (500 {@code Error
     * reading file: <msg>}) and an unexpected {@code Throwable} (500 {@code Unexcepted error:
     * <msg>}, the typo pinned by the catalog) both require infrastructure-level fault injection
     * (aborted body / corrupted transfer encoding) that a well-formed RestAssured request cannot
     * produce. Listed as justified residue.
     */
    @Test
    @Disabled("D8: IOException/Throwable stream faults are not injectable through pure RestAssured HTTP")
    void d8_streamErrors() {
        // Intentionally empty: see the Javadoc and the class-level D8 calibration note.
    }

    // --------------------------------------------------
    // D9 — insufficient role: MANAGER is refused on every import endpoint
    // --------------------------------------------------

    /**
     * D9 — insufficient role. A MANAGER account (without ADMIN) is refused with 403 on each of
     * the seven CSV import endpoints; the guard is enforced before the body is parsed, so
     * an empty payload still yields a 403 (never a 401 challenge, since the caller is
     * authenticated).
     */
    @Test
    void d9_managerRefusedOnEveryImportEndpoint() {
        ensureManager("d9manager", "managerpass1");
        for (String endpoint : IMPORT_ENDPOINTS) {
            given().auth().preemptive().basic("d9manager", "managerpass1")
                    .contentType(ContentType.TEXT)
                    .body("")
                    .when().post(endpoint)
                    .then().statusCode(403);
        }
    }
}
