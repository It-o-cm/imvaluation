package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

/**
 * Server-side perimeter enforcement on the UI (spec §2, §3): hidden menus refuse their screen and
 * drop their link, an administrator cannot widen their own perimeter, and deletion stays ADMIN-only.
 * <p>
 * Users and stores are seeded committed (RestAssured reads over HTTP), and {@code @TestSecurity}
 * injects the matching identity so {@code CurrentUser} resolves the seeded perimeter.
 */
@QuarkusTest
public class PerimeterUiTest {

    /**
     * Seeds the test users and stores before each test.
     */
    @BeforeEach
    void seed() {
        wipe();
        QuarkusTransaction.requiringNew().run(() -> {
            DomainUtils.createAndPersistStore("PERI_SA", 48.0, 2.0);
            DomainUtils.createAndPersistStore("PERI_SB", 48.0, 2.0);
            AppUser hider = new AppUser();
            hider.username = "perihider";
            hider.setPassword("password1");
            hider.setRoleSet(Set.of(AppUser.ROLE_ADMIN, AppUser.ROLE_MANAGER));
            hider.hiddenMenus = "imports";
            hider.allowedOfferTypes = "TICKET_DISCOUNT,GIFT_ITEM";
            hider.persist();
            AppUser self = new AppUser();
            self.username = "periself";
            self.setPassword("password1");
            self.setRoleSet(Set.of(AppUser.ROLE_ADMIN));
            self.storeScope = "PERI_SA,PERI_SB";
            self.persist();
        });
    }

    /**
     * Clears the seeded users and stores after the class.
     */
    @AfterAll
    static void afterAll() {
        wipe();
    }

    /**
     * Deletes the seeded test users and stores.
     */
    private static void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser.delete("username in ?1", List.of("perihider", "periself"));
            Store.delete("code in ?1", List.of("PERI_SA", "PERI_SB"));
        });
    }

    /**
     * The database id of the seeded self-administered account.
     *
     * @return the id.
     */
    private Long selfId() {
        return QuarkusTransaction.requiringNew().call(
                () -> ((AppUser) AppUser.find("username", "periself").firstResult()).id);
    }

    /**
     * §3.3: a screen whose menu is hidden is refused server-side, not merely delinked.
     */
    @Test
    @TestSecurity(user = "perihider", roles = {"ADMIN", "MANAGER"})
    void testHiddenMenu_screenRefused() {
        given().when().get("/ui/imports").then().statusCode(403);
    }

    /**
     * §3.3: a screen whose menu is NOT hidden is reachable, and its layout omits the hidden link.
     */
    @Test
    @TestSecurity(user = "perihider", roles = {"ADMIN", "MANAGER"})
    void testVisibleMenu_reachableAndHiddenLinkAbsent() {
        given().when().get("/ui/offers")
                .then().statusCode(200)
                .body(not(containsString("href=\"/ui/imports\"")));
    }

    /**
     * §3.5: an administrator restricting their own account may not widen its store perimeter —
     * clearing it (an empty scope is unrestricted) is refused with a form error, no redirect.
     */
    @Test
    @TestSecurity(user = "periself", roles = "ADMIN")
    void testSelfPerimeter_cannotClear() {
        given().redirects().follow(false)
                .formParam("displayName", "Self")
                .formParam("roles", "ADMIN")
                .formParam("active", "on")
                .when().post("/ui/users/" + selfId())
                .then().statusCode(200)
                .body(containsString("élargir votre propre périmètre magasins"));
    }

    /**
     * §3.5: an administrator narrowing their own store perimeter to a subset is accepted (redirect
     * to the list).
     */
    @Test
    @TestSecurity(user = "periself", roles = "ADMIN")
    void testSelfPerimeter_narrowingAccepted() {
        given().redirects().follow(false)
                .formParam("displayName", "Self")
                .formParam("roles", "ADMIN")
                .formParam("storeScope", "PERI_SA")
                .formParam("active", "on")
                .when().post("/ui/users/" + selfId())
                .then().statusCode(303);
    }

    /**
     * The database id of the seeded self-administered menu-hider account.
     *
     * @return the id.
     */
    private Long hiderId() {
        return QuarkusTransaction.requiringNew().call(
                () -> ((AppUser) AppUser.find("username", "perihider").firstResult()).id);
    }

    /**
     * §5: an administrator editing their own account may not widen their allowed offer types —
     * adding a type absent from the stored set is refused with a form error, no redirect.
     */
    @Test
    @TestSecurity(user = "perihider", roles = {"ADMIN", "MANAGER"})
    void testSelfPerimeter_cannotWidenTypes() {
        given().redirects().follow(false)
                .formParam("displayName", "Hider")
                .formParam("roles", "ADMIN", "MANAGER")
                .formParam("allowedOfferTypes", "TICKET_DISCOUNT", "GIFT_ITEM", "IMMEDIATE_VOUCHER")
                .formParam("hiddenMenus", "imports")
                .formParam("active", "on")
                .when().post("/ui/users/" + hiderId())
                .then().statusCode(200)
                .body(containsString("élargir vos propres types"));
    }

    /**
     * §5: an administrator editing their own account may narrow their allowed offer types to a
     * subset — dropping a stored type is accepted (redirect to the list).
     */
    @Test
    @TestSecurity(user = "perihider", roles = {"ADMIN", "MANAGER"})
    void testSelfPerimeter_narrowingTypesAccepted() {
        given().redirects().follow(false)
                .formParam("displayName", "Hider")
                .formParam("roles", "ADMIN", "MANAGER")
                .formParam("allowedOfferTypes", "TICKET_DISCOUNT")
                .formParam("hiddenMenus", "imports")
                .formParam("active", "on")
                .when().post("/ui/users/" + hiderId())
                .then().statusCode(303);
    }

    /**
     * §5: an administrator editing their own account may not reveal a menu hidden from their own
     * profile — submitting without the stored hidden menu is refused with a form error, no redirect.
     * The offer types are resubmitted unchanged so the earlier types guard passes and the menu guard
     * is the one exercised.
     */
    @Test
    @TestSecurity(user = "perihider", roles = {"ADMIN", "MANAGER"})
    void testSelfPerimeter_cannotRevealMenu() {
        given().redirects().follow(false)
                .formParam("displayName", "Hider")
                .formParam("roles", "ADMIN", "MANAGER")
                .formParam("allowedOfferTypes", "TICKET_DISCOUNT", "GIFT_ITEM")
                .formParam("active", "on")
                .when().post("/ui/users/" + hiderId())
                .then().statusCode(200)
                .body(containsString("révéler un menu masqué de votre propre profil"));
    }

    /**
     * §2 (GB-01-01-23): deletion of an offer is reserved to ADMIN — a MANAGER is refused.
     */
    @Test
    @TestSecurity(user = "perihider", roles = "MANAGER")
    void testDelete_reservedToAdmin() {
        given().redirects().follow(false)
                .when().post("/ui/offers/999999/delete")
                .then().statusCode(403);
    }
}
