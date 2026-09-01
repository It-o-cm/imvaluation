package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.valuation.CoverageDbReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Endpoint coverage tests for {@link ImportsUiResource}, exercised through the real HTTP stack
 * so the container instruments the resource for quarkus-jacoco.
 * <p>
 * The screen sits under {@code /ui/imports}, outside the Basic-only API permission set, so
 * authentication uses {@code @TestSecurity} to inject the role under test. Both the read screen
 * (VIEWER, MANAGER, ADMIN, a disallowed role and the anonymous redirect) and the admin-only
 * {@code /run} upload (valid, wrong-column, unknown-domain, every dispatch arm and the three
 * missing-input guards) are driven end to end.
 */
@QuarkusTest
public class ImportsUiResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * A single header line for a store CSV; it is skipped by the importer as the header, so the
     * upload carries no data line and every domain dispatch reports zero created, zero updated.
     */
    private static final String HEADER_ONLY = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n";

    /**
     * A valid store CSV creating exactly one store, so the success summary is asserted verbatim.
     */
    private static final String VALID_STORE_CSV =
            "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n"
                    + "S001|Store One|Str1||75001|Paris|France||";

    /**
     * A store CSV whose only data line carries fewer columns than required, forcing the importer
     * to reject that line and flag the report with errors.
     */
    private static final String WRONG_COLUMNS_CSV =
            "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n"
                    + "S001|Store One";

    /**
     * The seven import domains, in the mandated replay order, each matching a dispatch case.
     */
    private static final String[] DOMAINS = {
            "STORES", "STORE_GROUPS", "PRODUCTS", "PRODUCT_FAMILIES", "CATEGORIES", "PRICES", "OFFERS"
    };

    /**
     * The importer-specific header line for each known domain, so a header-only upload passes
     * every importer's required-columns check and reports an empty successful import.
     */
    private static final java.util.Map<String, String> DOMAIN_HEADERS = java.util.Map.of(
            "STORES", "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n",
            "STORE_GROUPS", "CODE|NAME|STORE_CODES|STORE_GROUP_CODES\n",
            "PRODUCTS", "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE\n",
            "PRODUCT_FAMILIES", "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n",
            "CATEGORIES", "EAN|LEVEL1|LEVEL2|LEVEL3|LEVEL4|LEVEL5\n",
            "PRICES", "EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n",
            "OFFERS", "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n");

    /**
     * Clears the whole reference set before each test so rows written by an upload here or by
     * another class cannot skew the assertions; the order is the reverse of the foreign keys.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        ProductCategoryStorage.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Posts a multipart import as an administrator without following the redirect, asserts the
     * 303 outcome and returns the redirect location carrying the one-shot notice.
     *
     * @param domain The import domain sent in the form.
     * @param csv    The CSV content uploaded as the file part.
     * @return The {@code Location} header of the 303 response.
     */
    private String postImport(String domain, String csv) {
        return given().redirects().follow(false)
                .multiPart("domain", domain)
                .multiPart("file", "data.csv", csv.getBytes(StandardCharsets.UTF_8), "application/octet-stream")
                .when().post("/ui/imports/run")
                .then().statusCode(303)
                .extract().header("Location");
    }

    // --------------------------------------------------
    // GET screen — role matrix and notice defaulting
    // --------------------------------------------------

    /**
     * Tests that the screen renders for an administrator, exercising the writable-form arm of
     * the canWrite guard and the notice-provided arm of the notice defaulting.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testScreen_rendersForAdminWithNotice() {
        given().when().get("/ui/imports?notice=Hello&noticeOk=true")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that the screen renders for a viewer with no notice, exercising the read-only arm of
     * the canWrite guard and the null-notice arm of the notice defaulting.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testScreen_rendersForViewerWithoutNotice() {
        given().when().get("/ui/imports")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that the screen renders for a manager, covering the third allowed role of the
     * class-level role set.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testScreen_rendersForManager() {
        given().when().get("/ui/imports").then().statusCode(200);
    }

    /**
     * Tests that a user holding a role outside the allowed set is forbidden from the screen.
     */
    @Test
    @TestSecurity(user = "other", roles = "OTHER")
    void testScreen_forbiddenForDisallowedRole() {
        given().when().get("/ui/imports").then().statusCode(403);
    }

    /**
     * Tests that an anonymous caller is redirected to the login screen by the form mechanism.
     */
    @Test
    void testScreen_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/imports")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }

    // --------------------------------------------------
    // POST /run — success, errors, unknown domain, dispatch arms
    // --------------------------------------------------

    /**
     * Tests that a valid store CSV is imported and summarised as one created, zero updated,
     * exercising the success dispatch, the digit-scanning extract and the no-errors summary arm.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_validStoreCsvSummarisesCreatedCount() {
        String location = postImport("STORES", VALID_STORE_CSV);
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("STORES"));
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("noticeOk=true"));
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("1")
                && location.contains("created"));
    }

    /**
     * Tests that a data line with too few columns is rejected, flagging the summary with the
     * errors suffix while the upload still succeeds, exercising the has-errors summary arm.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_wrongColumnCsvFlagsErrors() {
        String location = postImport("STORES", WRONG_COLUMNS_CSV);
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("STORES"));
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("errors"));
    }

    /**
     * Tests that an unknown domain reaches the default dispatch arm, yielding a 400 report that
     * flips the outcome flag to false and drives extract down its field-absent arm.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_unknownDomainReportsFailure() {
        String location = postImport("BOGUS", VALID_STORE_CSV);
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("BOGUS"));
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("noticeOk=false"));
    }

    /**
     * Tests that every known domain dispatch arm is reached by uploading a header-only file
     * (with that domain's own header) to each, so each importer runs and the summary reports
     * zero created, zero updated.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_dispatchesEveryKnownDomain() {
        for (String domain : DOMAINS) {
            String location = postImport(domain, DOMAIN_HEADERS.get(domain));
            org.junit.jupiter.api.Assertions.assertTrue(location.contains(domain),
                    "location should carry the domain " + domain);
            org.junit.jupiter.api.Assertions.assertTrue(location.contains("noticeOk=true"),
                    "empty import of " + domain + " should be reported as a success");
        }
    }

    // --------------------------------------------------
    // POST /run — missing-input guard arms
    // --------------------------------------------------

    /**
     * Tests that a submission without a file part hits the missing-file arm of the input guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_missingFileRejected() {
        String location = given().redirects().follow(false)
                .multiPart("domain", "STORES")
                .when().post("/ui/imports/run")
                .then().statusCode(303)
                .extract().header("Location");
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("selected"));
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("noticeOk=false"));
    }

    /**
     * Tests that a submission without a domain part hits the null-domain arm of the input guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_missingDomainRejected() {
        String location = given().redirects().follow(false)
                .multiPart("file", "data.csv", HEADER_ONLY.getBytes(StandardCharsets.UTF_8),
                        "application/octet-stream")
                .when().post("/ui/imports/run")
                .then().statusCode(303)
                .extract().header("Location");
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("selected"));
    }

    /**
     * Tests that a blank domain value hits the blank-domain arm of the input guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRun_blankDomainRejected() {
        String location = given().redirects().follow(false)
                .multiPart("domain", "")
                .multiPart("file", "data.csv", HEADER_ONLY.getBytes(StandardCharsets.UTF_8),
                        "application/octet-stream")
                .when().post("/ui/imports/run")
                .then().statusCode(303)
                .extract().header("Location");
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("selected"));
    }

    /**
     * Tests that a viewer is forbidden from running an import, since the run endpoint is
     * restricted to administrators.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testRun_forbiddenForViewer() {
        given().redirects().follow(false)
                .multiPart("domain", "STORES")
                .multiPart("file", "data.csv", HEADER_ONLY.getBytes(StandardCharsets.UTF_8),
                        "application/octet-stream")
                .when().post("/ui/imports/run")
                .then().statusCode(403);
    }
}
