package com.intermarche.e2e;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Group O — administration UI screens (extension 27/09) — of e2e-scenarios.md, the HTTP-testable
 * slice, in pure RestAssured.
 * <p>
 * No {@code GroupOIT} existed before this campaign (O1–O7 are browser-driven {@code [W]} screens
 * with no Playwright harness in the project). This class pins the parts of O8–O10 reachable over
 * plain HTTP: the read-only EGAlim-ceilings screen (O8), the consumptions screen and its RBAC
 * (O9), and the imports screen with its nine domains (O10). The {@code /ui/*} screens use form
 * auth, so an anonymous request (no {@code Authorization} header) is a 302 redirect to the login
 * page, never a 401; a Basic credential with an insufficient role is a 403.
 * <p>
 * RESIDUE — the filename-driven domain preselection on the imports screen (O10) and the
 * Form/JSON generator behaviours run in client-side JavaScript; with no browser harness they are
 * covered by the Vitest bench, not here, and are left {@code @Disabled} with the {@code [W]}
 * residue documented.
 */
@QuarkusTest
class GroupOIT {

    /**
     * The EGAlim ceiling feed, so the read-only screen has administered rows (including an
     * uncapped EXEMPT rendered as an em-dash entity).
     */
    private static final String EGALIM_REGIMES_CSV =
            "CODE|LABEL|CAP_RATE\nFOOD_34|Food 34|0.3400\nDPH_40|DPH 40|0.4000\nEXEMPT|Exempt|\n";

    /**
     * Whether the feed and the screen users have been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Imports the EGAlim feed and creates the VIEWER and MANAGER screen users once.
     */
    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT).body(EGALIM_REGIMES_CSV)
                .when().post("/egalim-regimes/import")
                .then().statusCode(200);
        user("oviewer", AppUser.ROLE_VIEWER);
        user("omanager", AppUser.ROLE_MANAGER);
        seeded = true;
    }

    /**
     * Ensures a single-role screen user exists, password {@code pass1234}.
     *
     * @param username the login name.
     * @param role     the single granted role.
     */
    private void user(String username, String role) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.findByUsername(username) != null) {
                return;
            }
            AppUser u = new AppUser();
            u.username = username;
            u.setPassword("pass1234");
            u.setRoleSet(Set.of(role));
            u.displayName = username;
            u.active = true;
            u.mustChangePassword = false;
            u.persist();
        });
    }

    // --------------------------------------------------
    // O8 — /ui/egalim-ceilings (read-only)
    // --------------------------------------------------

    /**
     * O8 — the read-only EGAlim-ceilings screen (VIEWER+) lists the administered ceilings: a capped
     * regime renders its percentage, an uncapped one the em-dash entity {@code &mdash;}. An
     * anonymous request is redirected (302) to the login page, not challenged.
     */
    @Test
    void o8_egalimCeilingsScreen() {
        given().auth().preemptive().basic("oviewer", "pass1234")
                .when().get("/ui/egalim-ceilings")
                .then().statusCode(200)
                .body(containsString("Plafonds EGAlim"))
                .body(containsString("34"))
                .body(containsString("&mdash;"));
        given().redirects().follow(false)
                .when().get("/ui/egalim-ceilings")
                .then().statusCode(302);
    }

    // --------------------------------------------------
    // O9 — /ui/consumptions
    // --------------------------------------------------

    /**
     * O9 — the consumptions screen is MANAGER+: a MANAGER reaches it (200, with the CSV/JSON export
     * links), a VIEWER is refused (403), and an anonymous request is redirected (302) to login.
     */
    @Test
    void o9_consumptionsScreen() {
        given().auth().preemptive().basic("omanager", "pass1234")
                .queryParam("from", "2027-03-01").queryParam("to", "2027-03-31")
                .when().get("/ui/consumptions")
                .then().statusCode(200)
                .body(containsString("Consommations"))
                .body(containsString("Télécharger le CSV"));
        given().auth().preemptive().basic("oviewer", "pass1234")
                .when().get("/ui/consumptions")
                .then().statusCode(403);
        given().redirects().follow(false)
                .when().get("/ui/consumptions")
                .then().statusCode(302);
    }

    // --------------------------------------------------
    // O10 — /ui/imports (nine domains)
    // --------------------------------------------------

    /**
     * O10 — the imports screen exposes exactly nine referential domains, including the extension's
     * {@code EGALIM_REGIMES} and {@code VAT_RATES}. The domain {@code <select>} is gated behind the
     * write role, so it is asserted as {@code admin}; a VIEWER still reaches the page (200) without
     * the form.
     * <p>
     * ÉCART noted — the nine domains are STORES, STORE_GROUPS, EGALIM_REGIMES, PRODUCTS,
     * PRODUCT_FAMILIES, CATEGORIES, VAT_RATES, PRICES, OFFERS; {@code BUDGET_GROUPS} has an import
     * endpoint but is NOT offered on this screen (import it via the API), so it is asserted ABSENT.
     */
    @Test
    void o10_importsScreenNineDomains() {
        given().auth().preemptive().basic("admin", "admin")
                .when().get("/ui/imports")
                .then().statusCode(200)
                .body(containsString("EGALIM_REGIMES"))
                .body(containsString("VAT_RATES"))
                .body(containsString("9. OFFERS"))
                .body(org.hamcrest.Matchers.not(containsString("value=\"BUDGET_GROUPS\"")));
        given().auth().preemptive().basic("oviewer", "pass1234")
                .when().get("/ui/imports")
                .then().statusCode(200);
    }

    /**
     * O10 [W] residue — the filename-driven domain preselection (e.g. a name containing
     * {@code vat} preselecting {@code VAT_RATES}) is client-side JavaScript in {@code imports.js};
     * with no browser harness in the project it is covered by the Vitest bench, not over HTTP.
     */
    @Test
    @Disabled("[W] imports.js filename preselection runs in the browser; covered by the Vitest bench")
    void o10_filenamePreselection() {
    }
}
