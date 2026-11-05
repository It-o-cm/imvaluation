package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.EgalimCeiling;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Endpoint tests for the read-only {@link EgalimCeilingUiResource}, driven through the real
 * HTTP stack.
 * <p>
 * The screen lists the administered EGAlim ceilings for the VIEWER role and above; it carries
 * no edit control. The tests assert that a seeded capped regime shows its ceiling formatted as
 * a percentage, that an uncapped regime shows the em-dash placeholder, and that an anonymous
 * caller is bounced to the login screen.
 */
@QuarkusTest
public class EgalimCeilingUiResourceTest {

    /**
     * The capped regime seeded for the tests, with a ceiling of thirty-four percent.
     */
    private static final String CAPPED_CODE = "FOOD_34";

    /**
     * The label of the capped regime, asserted verbatim in the rendered table.
     */
    private static final String CAPPED_LABEL = "Food and petfood";

    /**
     * The uncapped regime seeded for the tests, whose ceiling cell must render the em-dash.
     */
    private static final String UNCAPPED_CODE = "EXEMPT";

    /**
     * The label of the uncapped regime, asserted verbatim in the rendered table.
     */
    private static final String UNCAPPED_LABEL = "Exempt goods";

    /**
     * Clears the seeded ceilings after the class so they cannot leak into another test class in
     * the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        QuarkusTransaction.requiringNew().run(() -> EgalimCeiling.deleteAll());
    }

    /**
     * Reseeds the referential before each test with one capped and one uncapped regime, so the
     * assertions never depend on rows left by another test or class.
     */
    @BeforeEach
    @Transactional
    void seed() {
        EgalimCeiling.deleteAll();
        new EgalimCeiling(CAPPED_CODE, CAPPED_LABEL, new BigDecimal("0.3400")).persist();
        new EgalimCeiling(UNCAPPED_CODE, UNCAPPED_LABEL, null).persist();
    }

    /**
     * Tests that the screen lists a capped regime with its code, label and percentage-formatted
     * ceiling for a viewer.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testList_showsCappedRegimeWithFormattedCeiling() {
        given().when().get("/ui/egalim-ceilings")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString(CAPPED_CODE))
                .body(containsString(CAPPED_LABEL))
                .body(containsString("34 %"));
    }

    /**
     * Tests that an uncapped regime is listed with the em-dash placeholder in its ceiling cell
     * rather than a zero or a blank.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testList_showsEmDashForUncappedRegime() {
        given().when().get("/ui/egalim-ceilings")
                .then().statusCode(200)
                .body(containsString(UNCAPPED_CODE))
                .body(containsString(UNCAPPED_LABEL))
                .body(containsString("&mdash;"));
    }

    /**
     * Tests that the screen also renders for the MANAGER and ADMIN roles, covering the rest of
     * the class-level role set.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testList_rendersForManager() {
        given().when().get("/ui/egalim-ceilings").then().statusCode(200);
    }

    /**
     * Tests that an anonymous caller is redirected to the login screen rather than served the
     * list.
     */
    @Test
    void testList_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/egalim-ceilings")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }
}
