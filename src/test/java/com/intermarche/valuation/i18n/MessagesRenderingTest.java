package com.intermarche.valuation.i18n;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Validates the i18n rendering mechanism end to end through the real HTTP stack (spec-exploitation
 * §1, §5.1): the shared layout navigation is localised, the default language is French, and the
 * {@code Accept-Language} header switches the rendered bundle.
 */
@QuarkusTest
public class MessagesRenderingTest {

    /**
     * Tests that with no language preference the layout renders in French (the default) — the nav
     * shows "Offres" and the document language is fr.
     */
    @Test
    @TestSecurity(user = "test_i18n_admin", roles = "ADMIN")
    void testDefaultRendersFrench() {
        given().when().get("/ui/offers")
                .then().statusCode(200)
                .body(containsString("html lang=\"fr\""))
                .body(containsString("/ui/offers\">Offres<"));
    }

    /**
     * Tests that an {@code Accept-Language: en} header switches the layout to English — the nav
     * shows "Offers" and the document language is en.
     */
    @Test
    @TestSecurity(user = "test_i18n_admin", roles = "ADMIN")
    void testAcceptLanguageEnglishRendersEnglish() {
        given().header("Accept-Language", "en").when().get("/ui/offers")
                .then().statusCode(200)
                .body(containsString("html lang=\"en\""))
                .body(containsString("/ui/offers\">Offers<"));
    }

    /**
     * Tests that every localised back-office screen renders without a template error (HTTP 200),
     * guarding against a missing message key or a malformed {@code inject:i18n} expression after the
     * mass localization.
     */
    @Test
    @TestSecurity(user = "test_i18n_admin", roles = "ADMIN")
    void testEveryScreenRenders() {
        for (String path : new String[]{
                "/ui/offers", "/ui/offers/new",
                "/ui/users", "/ui/users/new",
                "/ui/store-groups",
                "/ui/valuations", "/ui/valuations/new",
                "/ui/egalim-ceilings",
                "/ui/consumptions",
                "/ui/anomalies",
                "/ui/imports",
                "/ui/password"}) {
            given().when().get(path).then().statusCode(200);
        }
    }

    /**
     * Tests that the anonymous authentication screens render without a template error, exercising the
     * i18n bean when there is no signed-in user (Accept-Language / default fallback path).
     */
    @Test
    void testAnonymousAuthScreensRender() {
        given().when().get("/ui/login").then().statusCode(200);
        given().when().get("/ui/forgot").then().statusCode(200);
    }
}
