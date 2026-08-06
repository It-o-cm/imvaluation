package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group C — Accounts &amp; roles — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application. The account-administration screens ({@code /ui/users}) are reached with HTTP
 * Basic as {@code admin/admin}; the role matrix (C4) authenticates as purpose-built accounts
 * holding a single role each; the interface guards (C5) are read as a VIEWER.
 * <p>
 * The group needs no referential seed: accounts are created directly through Panache, the
 * GraphQL security matrix only observes the authorization outcome (an empty catalog answers
 * every query), and the import guards refuse before any CSV is parsed.
 * <p>
 * Redirects are never followed automatically: the success and delete-guard paths return a
 * 303 whose {@code Location} notice is asserted explicitly, while the validation refusals
 * re-render the form with a 200.
 * <p>
 * CALIBRATION — C3 last-administrator guards: {@code isLastActiveAdmin} counts the accounts
 * that are {@code active} AND hold {@code ADMIN}. Because the bootstrap {@code admin} is
 * always such an account, no other account can ever be "the last active administrator" while
 * it stays enabled. Each C3 guard therefore installs a disposable sole active administrator
 * for the duration of the call (every other active admin temporarily disabled) and restores
 * the previous state in a {@code finally} block, so the scenario stays isolated and leaves
 * the bootstrap admin usable for the rest of the suite.
 * <p>
 * CALIBRATION — C3 "all in 303": the catalog states every last-admin guard answers 303 + a
 * notice. Observed reality: only the three <em>delete</em> guards go through
 * {@code redirectWithNotice} (303). The demote/disable guard is an <em>update</em> validation
 * failure, so it re-renders the form with a 200 carrying the same message. Asserted as
 * observed.
 * <p>
 * CALIBRATION — C4 GraphQL: a {@code @RolesAllowed} denial is rendered by SmallRye as an
 * HTTP 200 whose body carries an {@code "errors"} array (the operation field resolving to
 * {@code null}); a granted operation answers 200 with a {@code "data"} payload and no
 * {@code "errors"} key. The matrix is asserted on that key, not on a fragile message.
 */
@QuarkusTest
class GroupCIT {

    /**
     * Ensures an account exists with the given attributes, creating it when absent.
     * <p>
     * Idempotent: a second call with an already-present username is a no-op, which keeps the
     * scenarios independent of their execution order.
     *
     * @param username   The login name.
     * @param password   The clear-text password.
     * @param active     Whether the account may sign in.
     * @param mustChange Whether a password change is pending on the account.
     * @param roles      The roles granted to the account.
     */
    private void ensureUser(String username, String password, boolean active,
                            boolean mustChange, Set<String> roles) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.findByUsername(username) != null) {
                return;
            }
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(password);
            user.setRoleSet(roles);
            user.displayName = username;
            user.active = active;
            user.mustChangePassword = mustChange;
            user.persist();
        });
    }

    /**
     * Returns the database identifier of an account.
     *
     * @param username The login name to resolve.
     * @return The persisted identifier.
     */
    private Long userId(String username) {
        Long[] id = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername(username);
            assertNotNull(user, "Expected account '" + username + "' to exist");
            id[0] = user.id;
        });
        return id[0];
    }

    /**
     * Disables every active administrator except the named account, so it becomes the single
     * active administrator recognised by {@code isLastActiveAdmin}.
     *
     * @param keep The login name of the account left as the sole active administrator.
     * @return The login names of the administrators that were disabled, for later restoration.
     */
    private List<String> makeSoleActiveAdmin(String keep) {
        List<String> disabled = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            List<AppUser> admins = AppUser.list("active = true and roles like ?1",
                    "%" + AppUser.ROLE_ADMIN + "%");
            for (AppUser admin : admins) {
                if (!admin.username.equals(keep)) {
                    admin.active = false;
                    disabled.add(admin.username);
                }
            }
        });
        return disabled;
    }

    /**
     * Re-enables the administrators previously disabled by {@link #makeSoleActiveAdmin}.
     *
     * @param usernames The login names to re-activate.
     */
    private void restoreActive(List<String> usernames) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String username : usernames) {
                AppUser user = AppUser.findByUsername(username);
                if (user != null) {
                    user.active = true;
                }
            }
        });
    }

    /**
     * Posts a GraphQL operation with HTTP Basic credentials and asserts a 200 transport.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @param query    The GraphQL query or mutation document.
     * @return The response body as a string, for {@code "errors"}/{@code "data"} assertions.
     */
    private String graphql(String username, String password, String query) {
        return given().auth().preemptive().basic(username, password)
                .contentType(ContentType.JSON)
                .body("{\"query\":\"" + query + "\"}")
                .when().post("/graphql")
                .then().statusCode(200)
                .extract().asString();
    }

    // --------------------------------------------------
    // C1 — account creation: ordered refusals then success
    // --------------------------------------------------

    /**
     * C1 — account creation. {@code POST /ui/users/new} refuses in a fixed order — missing
     * username, duplicate username, password policy, then missing role — each as a 200
     * form re-render that never echoes the submitted password; a valid submission redirects
     * 303 to the list with the green {@code Account '{u}' created.} notice and forces
     * {@code mustChangePassword=true} on the new account.
     */
    @Test
    void c1_accountCreationOrderedRefusalsThenSuccess() {
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "")
                .formParam("password", "topsecret123")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(200)
                .body(containsString("The username is mandatory."))
                .body(not(containsString("topsecret123")));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "admin")
                .formParam("password", "validpass1")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(200)
                // Qute HTML-escapes the message banner: the apostrophes render as &#39;.
                .body(containsString("An account named &#39;admin&#39; already exists."));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c1fresh1")
                .formParam("password", "")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(200)
                .body(containsString("The password is mandatory."));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c1fresh2")
                .formParam("password", "short")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(200)
                .body(containsString("The password must be at least 8 characters long."));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c1fresh3")
                .formParam("password", "validpass1")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(200)
                .body(containsString("At least one role must be granted."));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c1ok")
                .formParam("password", "validpass1")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(303)
                .header("Location", containsString("/ui/users?notice=Account+%27c1ok%27+created."));
        boolean[] forced = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("c1ok");
            forced[0] = user != null && user.mustChangePassword
                    && user.getRoleSet().equals(Set.of(AppUser.ROLE_VIEWER));
        });
        assertTrue(forced[0], "The created account must be VIEWER with a forced password change");
    }

    // --------------------------------------------------
    // C2 — edition: immutable username, password reset semantics, 404
    // --------------------------------------------------

    /**
     * C2 — edition. The edit form shows the username disabled with the hint
     * {@code The login name cannot be changed.}; submitting a blank password keeps the
     * current one untouched, submitting a new one resets it and forces a change; a valid
     * update redirects with {@code Account '{u}' updated.}; an unknown id answers a 404
     * carrying {@code User {id} not found} on both the GET form and the POST.
     */
    @Test
    void c2_editionImmutableUsernamePasswordResetAndNotFound() {
        ensureUser("c2user", "initpass1", true, false, Set.of(AppUser.ROLE_VIEWER));
        Long id = userId("c2user");
        given().auth().preemptive().basic("admin", "admin")
                .when().get("/ui/users/" + id)
                .then().statusCode(200)
                .body(containsString("The login name cannot be changed."))
                .body(containsString("value=\"c2user\""));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("password", "")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/" + id)
                .then().statusCode(303)
                .header("Location", containsString("/ui/users?notice=Account+%27c2user%27+updated."));
        boolean[] unchanged = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("c2user");
            unchanged[0] = user.matchesPassword("initpass1") && !user.mustChangePassword
                    && user.username.equals("c2user");
        });
        assertTrue(unchanged[0], "A blank password must keep the current credentials");
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("password", "newpass12")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/" + id)
                .then().statusCode(303)
                .header("Location", containsString("/ui/users?notice=Account+%27c2user%27+updated."));
        boolean[] reset = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("c2user");
            reset[0] = user.matchesPassword("newpass12") && user.mustChangePassword
                    && user.username.equals("c2user");
        });
        assertTrue(reset[0], "A supplied password must reset the credentials and force a change");
        given().auth().preemptive().basic("admin", "admin")
                .when().get("/ui/users/999999")
                .then().statusCode(404)
                .body(containsString("User 999999 not found"));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("password", "")
                .formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/999999")
                .then().statusCode(404)
                .body(containsString("User 999999 not found"));
    }

    // --------------------------------------------------
    // C3 — last-administrator guards
    // --------------------------------------------------

    /**
     * C3 — last-administrator guards. Demoting or disabling the sole active administrator is
     * refused with {@code This is the last administrator: keep the role and the account
     * enabled.} (a 200 form re-render); deleting it answers a 303 with
     * {@code The last administrator cannot be deleted.}; deleting one's own account (reached
     * by direct URL) answers {@code You cannot delete your own account.}; an unknown id
     * answers {@code Account not found.}
     */
    @Test
    void c3_lastAdministratorGuards() {
        ensureUser("c3admin", "adminpass1", true, false, Set.of(AppUser.ROLE_ADMIN));
        ensureUser("c3caller", "callerpass1", false, false, Set.of(AppUser.ROLE_ADMIN));
        Long targetId = userId("c3admin");
        List<String> restored = makeSoleActiveAdmin("c3admin");
        try {
            given().redirects().follow(false)
                    .auth().preemptive().basic("c3caller", "callerpass1")
                    .formParam("roles", "VIEWER")
                    .formParam("active", "on")
                    .when().post("/ui/users/" + targetId)
                    .then().statusCode(200)
                    .body(containsString(
                            "This is the last administrator: keep the role and the account enabled."));
            given().redirects().follow(false)
                    .auth().preemptive().basic("c3caller", "callerpass1")
                    .formParam("roles", "ADMIN")
                    .when().post("/ui/users/" + targetId)
                    .then().statusCode(200)
                    .body(containsString(
                            "This is the last administrator: keep the role and the account enabled."));
            given().redirects().follow(false)
                    .auth().preemptive().basic("c3caller", "callerpass1")
                    .when().post("/ui/users/" + targetId + "/delete")
                    .then().statusCode(303)
                    .header("Location",
                            containsString("The+last+administrator+cannot+be+deleted."))
                    .header("Location", containsString("noticeOk=false"));
        } finally {
            restoreActive(restored);
        }
        Long adminId = userId("admin");
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .when().post("/ui/users/" + adminId + "/delete")
                .then().statusCode(303)
                .header("Location", containsString("You+cannot+delete+your+own+account."))
                .header("Location", containsString("noticeOk=false"));
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .when().post("/ui/users/999999/delete")
                .then().statusCode(303)
                .header("Location", containsString("Account+not+found."))
                .header("Location", containsString("noticeOk=false"));
        boolean[] adminSurvives = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("admin");
            adminSurvives[0] = user != null && user.active && user.hasRole(AppUser.ROLE_ADMIN);
        });
        assertTrue(adminSurvives[0], "The bootstrap administrator must survive every guard");
    }

    // --------------------------------------------------
    // C4 — non-hierarchical roles
    // --------------------------------------------------

    /**
     * C4 — non-hierarchical roles. A MANAGER-only account runs GraphQL queries but is denied
     * every mutation; an ADMIN-only account runs mutations but is denied every query (the
     * assumed trap of a flat role model); CSV imports demand ADMIN; and {@code /valuation}
     * accepts any authenticated caller, so even a VIEWER reaches the engine.
     */
    @Test
    void c4_nonHierarchicalRoles() {
        ensureUser("c4manager", "managerpass1", true, false, Set.of(AppUser.ROLE_MANAGER));
        ensureUser("c4admin", "adminpass1", true, false, Set.of(AppUser.ROLE_ADMIN));
        ensureUser("c4viewer", "viewerpass1", true, false, Set.of(AppUser.ROLE_VIEWER));
        String managerQuery = graphql("c4manager", "managerpass1", "{ allStores { id code } }");
        assertFalse(managerQuery.contains("\"errors\""),
                "A MANAGER must be allowed to query allStores");
        assertTrue(managerQuery.contains("allStores"), "The query field must be resolved");
        String managerMutation = graphql("c4manager", "managerpass1",
                "mutation { createStore(input: {code: \\\"C4M\\\", name: \\\"C4 Manager\\\"}) { id } }");
        assertTrue(managerMutation.contains("\"errors\""),
                "A MANAGER must be denied the createStore mutation");
        String adminQuery = graphql("c4admin", "adminpass1", "{ allStores { id code } }");
        assertTrue(adminQuery.contains("\"errors\""),
                "An ADMIN must be denied the allStores query (flat role model)");
        String adminMutation = graphql("c4admin", "adminpass1",
                "mutation { createStore(input: {code: \\\"C4STORE\\\", name: \\\"C4 Store\\\"}) { id code } }");
        assertFalse(adminMutation.contains("\"errors\""),
                "An ADMIN must be allowed the createStore mutation");
        assertTrue(adminMutation.contains("C4STORE"), "The mutation must return the created store");
        given().redirects().follow(false)
                .auth().preemptive().basic("c4manager", "managerpass1")
                .contentType(ContentType.TEXT)
                .body("")
                .when().post("/stores/import")
                .then().statusCode(403);
        given().redirects().follow(false)
                .auth().preemptive().basic("c4viewer", "viewerpass1")
                .contentType(ContentType.TEXT)
                .body("")
                .when().post("/stores/import")
                .then().statusCode(403);
        given().redirects().follow(false)
                .auth().preemptive().basic("c4admin", "adminpass1")
                .contentType(ContentType.TEXT)
                .body("")
                .when().post("/stores/import")
                .then().statusCode(not(403)).statusCode(not(401));
        given().redirects().follow(false)
                .auth().preemptive().basic("c4viewer", "viewerpass1")
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/valuation")
                .then().statusCode(not(401)).statusCode(not(403));
    }

    // --------------------------------------------------
    // C5 — VIEWER journey in the interface
    // --------------------------------------------------

    /**
     * C5 — VIEWER journey. A VIEWER reads the offers list (with {@code Export CSV} but neither
     * {@code New offer} nor {@code Import CSV}), the store groups and the valuations
     * (with {@code New test} but no {@code Recording} card); the {@code Users} navigation link
     * is hidden; posting a new offer answers 403 and reaching {@code /ui/users} answers 403.
     */
    @Test
    void c5_viewerJourneyInTheInterface() {
        ensureUser("c5viewer", "viewerpass1", true, false, Set.of(AppUser.ROLE_VIEWER));
        given().auth().preemptive().basic("c5viewer", "viewerpass1")
                .when().get("/ui/offers")
                .then().statusCode(200)
                .body(containsString("Export CSV"))
                .body(not(containsString("New offer")))
                .body(not(containsString("Import CSV")))
                .body(not(containsString("href=\"/ui/users\"")));
        given().auth().preemptive().basic("c5viewer", "viewerpass1")
                .when().get("/ui/store-groups")
                .then().statusCode(200);
        given().auth().preemptive().basic("c5viewer", "viewerpass1")
                .when().get("/ui/valuations")
                .then().statusCode(200)
                .body(containsString("New test"))
                .body(not(containsString("action=\"/ui/valuations/config\"")));
        given().redirects().follow(false)
                .auth().preemptive().basic("c5viewer", "viewerpass1")
                .formParam("code", "C5OFFER")
                .when().post("/ui/offers/new")
                .then().statusCode(403);
        given().redirects().follow(false)
                .auth().preemptive().basic("c5viewer", "viewerpass1")
                .when().get("/ui/users")
                .then().statusCode(403);
    }

    // --------------------------------------------------
    // C6 — role sanitisation
    // --------------------------------------------------

    /**
     * C6 — role sanitisation. An unknown role such as {@code HACKER} is silently dropped: on
     * its own it leaves no role and is refused with {@code At least one role must be granted.},
     * and alongside a real role only the recognised one is stored; leaving the
     * {@code active} box unchecked creates the account disabled (crossing B6).
     */
    @Test
    void c6_roleSanitisation() {
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c6hacker")
                .formParam("password", "validpass1")
                .formParam("roles", "HACKER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(200)
                .body(containsString("At least one role must be granted."));
        boolean[] hackerAbsent = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            hackerAbsent[0] = AppUser.findByUsername("c6hacker") == null;
        });
        assertTrue(hackerAbsent[0], "A HACKER-only submission must not create an account");
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c6mix")
                .formParam("password", "validpass1")
                .formParam("roles", "HACKER", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(303)
                .header("Location", containsString("/ui/users?notice=Account+%27c6mix%27+created."));
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("c6mix");
            assertNotNull(user);
            assertEquals(Set.of(AppUser.ROLE_VIEWER), user.getRoleSet());
        });
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .formParam("username", "c6disabled")
                .formParam("password", "validpass1")
                .formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(303)
                .header("Location", containsString("/ui/users?notice=Account+%27c6disabled%27+created."));
        boolean[] disabled = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("c6disabled");
            disabled[0] = user != null && !user.active;
        });
        assertTrue(disabled[0], "An unchecked active box must create the account disabled");
    }
}
