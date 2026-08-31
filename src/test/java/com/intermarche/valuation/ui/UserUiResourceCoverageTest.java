package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.valuation.CoverageDbReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Endpoint coverage tests for {@link UserUiResource}, exercised through the real HTTP stack so
 * the container instruments the resource for coverage.
 * <p>
 * Authentication uses {@code @TestSecurity} to inject an identity carrying the role under test;
 * the role matrix (VIEWER forbidden, ADMIN allowed, anonymous redirected) is asserted alongside
 * the list, form and CRUD flows and every server-side validation branch.
 * <p>
 * The bootstrap {@code admin} account created at startup is never deleted, so other test classes
 * can still authenticate as {@code admin/admin}. Only accounts whose login name starts with
 * {@code test_} are created and removed here. Because that bootstrap account is itself an active
 * administrator, {@link AppUser#countActiveAdmins()} never drops to one on its own; the last
 * administrator guards are therefore reached by temporarily disabling the bootstrap account and
 * restoring it immediately, both in a {@code finally} block and in the per-test cleanup.
 */
@QuarkusTest
public class UserUiResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * Login name of the bootstrap administrator seeded at application startup.
     */
    private static final String BOOTSTRAP = "admin";

    /**
     * A password satisfying the application policy, used when seeding accounts.
     */
    private static final String VALID_PW = "secret12";

    /**
     * Removes every test-created account and re-enables the bootstrap administrator before each
     * test, so a row or a disabled flag left by another test cannot skew the assertions.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        AppUser.delete("username like ?1", "test_%");
        AppUser.update("active = true where username = ?1", BOOTSTRAP);
    }

    /**
     * Persists an account in its own committed transaction and returns its identifier.
     *
     * @param username The login name, expected to start with {@code test_}.
     * @param roles    The roles to grant.
     * @param active   Whether the account may sign in.
     * @return The identifier of the created account.
     */
    Long seedUser(String username, Set<String> roles, boolean active) {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser user = new AppUser();
            user.username = username;
            user.displayName = "Display " + username;
            user.setPassword(VALID_PW);
            user.setRoleSet(roles);
            user.active = active;
            user.persist();
            return user.id;
        });
    }

    /**
     * Enables or disables the bootstrap administrator in its own committed transaction.
     *
     * @param active The new active flag.
     */
    void setBootstrapActive(boolean active) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser admin = AppUser.findByUsername(BOOTSTRAP);
            if (admin != null) {
                admin.active = active;
            }
        });
    }

    /**
     * Starts a form-encoded request without following redirects, so a 303 outcome can be asserted
     * directly.
     *
     * @return A request specification ready for form parameters.
     */
    private io.restassured.specification.RequestSpecification form() {
        return given().redirects().follow(false).contentType(ContentType.URLENC);
    }

    // --------------------------------------------------
    // List
    // --------------------------------------------------

    /**
     * Tests that the list screen renders for an administrator and shows a seeded account.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testList_rendersForAdmin() {
        seedUser("test_alice", Set.of(AppUser.ROLE_VIEWER), true);
        given().when().get("/ui/users")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("test_alice"));
    }

    /**
     * Tests the search-only filter with an unknown sort key, exercising the invalid-sort fallback
     * and the no-role branch of the query builder.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testList_searchOnlyWithInvalidSort() {
        seedUser("test_bob", Set.of(AppUser.ROLE_MANAGER), true);
        given().when().get("/ui/users?q=bob&sort=unknown")
                .then().statusCode(200).body(containsString("test_bob"));
    }

    /**
     * Tests the role-only filter, exercising the role clause without a preceding search clause.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testList_roleOnlyFilter() {
        seedUser("test_carol", Set.of(AppUser.ROLE_ADMIN), true);
        given().when().get("/ui/users?role=ADMIN")
                .then().statusCode(200).body(containsString("test_carol"));
    }

    /**
     * Tests both filters together with the display-name sort, its descending direction and an
     * out-of-range page, exercising the combined where clause, the secondary sort suffix and the
     * page clamping.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testList_combinedFiltersSortAndPaging() {
        seedUser("test_dave", Set.of(AppUser.ROLE_VIEWER), true);
        given().when().get("/ui/users?q=dave&role=VIEWER&sort=displayName&dir=desc&page=999&notice=hi&noticeOk=false")
                .then().statusCode(200);
    }

    /**
     * Tests that an anonymous caller is redirected to the login screen by the form authentication
     * mechanism.
     */
    @Test
    void testList_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/users")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }

    /**
     * Tests that a viewer is forbidden from the list screen.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testList_forbiddenForViewer() {
        given().when().get("/ui/users").then().statusCode(403);
    }

    // --------------------------------------------------
    // Forms
    // --------------------------------------------------

    /**
     * Tests that the creation form renders for an administrator.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testCreateForm_rendersForAdmin() {
        given().when().get("/ui/users/new")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that a viewer is forbidden from the creation form.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testCreateForm_forbiddenForViewer() {
        given().when().get("/ui/users/new").then().statusCode(403);
    }

    /**
     * Tests that the edition form renders for an existing account.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testEditForm_rendersForExistingUser() {
        Long id = seedUser("test_edit", Set.of(AppUser.ROLE_MANAGER), true);
        given().when().get("/ui/users/" + id)
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("test_edit"));
    }

    /**
     * Tests that editing an unknown account yields a 404.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testEditForm_unknownYields404() {
        given().when().get("/ui/users/999999")
                .then().statusCode(404).body(containsString("not found"));
    }

    /**
     * Tests that a viewer is forbidden from the edition form.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testEditForm_forbiddenForViewer() {
        given().when().get("/ui/users/1").then().statusCode(403);
    }

    // --------------------------------------------------
    // Create
    // --------------------------------------------------

    /**
     * Tests that a valid submission creates the account and redirects to the list.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_validRedirects() {
        form().formParam("username", "  test_new  ").formParam("password", VALID_PW)
                .formParam("displayName", "New User").formParam("roles", "VIEWER")
                .formParam("active", "on")
                .when().post("/ui/users/new")
                .then().statusCode(303).header("location", containsString("noticeOk=true"));
    }

    /**
     * Tests that omitting the username entirely re-renders the form with the mandatory error,
     * exercising the null arm of the username defaulting.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_missingUsernameError() {
        form().formParam("password", VALID_PW).formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(200).body(containsString("The username is mandatory."));
    }

    /**
     * Tests that a blank username re-renders the form with the mandatory error, exercising the
     * non-null arm of the username defaulting followed by the blank check.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_blankUsernameError() {
        form().formParam("username", "   ").formParam("password", VALID_PW).formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(200).body(containsString("The username is mandatory."));
    }

    /**
     * Tests that a duplicate username re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_duplicateUsernameError() {
        seedUser("test_dup", Set.of(AppUser.ROLE_VIEWER), true);
        form().formParam("username", "test_dup").formParam("password", VALID_PW).formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(200).body(containsString("already exists"));
    }

    /**
     * Tests that a missing password re-renders the form with the mandatory password error.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_missingPasswordError() {
        form().formParam("username", "test_np").formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(200).body(containsString("The password is mandatory."));
    }

    /**
     * Tests that a too-short password re-renders the form with the length error.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_shortPasswordError() {
        form().formParam("username", "test_sp").formParam("password", "short").formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(200).body(containsString("at least 8 characters"));
    }

    /**
     * Tests that a submission whose only role is unrecognised re-renders the form with the
     * role error, exercising the sanitiser dropping an unknown value.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testSave_noRoleError() {
        form().formParam("username", "test_nr").formParam("password", VALID_PW).formParam("roles", "BOGUS")
                .when().post("/ui/users/new")
                .then().statusCode(200).body(containsString("At least one role must be granted."));
    }

    /**
     * Tests that a viewer is forbidden from creating an account.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSave_forbiddenForViewer() {
        form().formParam("username", "test_v").formParam("password", VALID_PW).formParam("roles", "VIEWER")
                .when().post("/ui/users/new")
                .then().statusCode(403);
    }

    // --------------------------------------------------
    // Update
    // --------------------------------------------------

    /**
     * Tests that a valid update without a new password redirects to the list, exercising the
     * password-kept branch.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_validNoPasswordRedirects() {
        Long id = seedUser("test_upd", Set.of(AppUser.ROLE_VIEWER), true);
        form().formParam("displayName", "Renamed").formParam("roles", "MANAGER").formParam("active", "on")
                .when().post("/ui/users/" + id)
                .then().statusCode(303).header("location", containsString("noticeOk=true"));
    }

    /**
     * Tests that a valid update carrying a new password redirects, exercising the password-set
     * branch and its policy success path.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_validWithPasswordRedirects() {
        Long id = seedUser("test_pwd", Set.of(AppUser.ROLE_VIEWER), true);
        form().formParam("password", "brandnew1").formParam("roles", "VIEWER").formParam("active", "on")
                .when().post("/ui/users/" + id)
                .then().statusCode(303);
    }

    /**
     * Tests that updating an unknown account yields a 404.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_unknownYields404() {
        form().formParam("roles", "VIEWER").formParam("active", "on")
                .when().post("/ui/users/999999")
                .then().statusCode(404).body(containsString("not found"));
    }

    /**
     * Tests that an update dropping every role re-renders the form with the role error.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_noRoleError() {
        Long id = seedUser("test_unr", Set.of(AppUser.ROLE_VIEWER), true);
        form().formParam("roles", "BOGUS").formParam("active", "on")
                .when().post("/ui/users/" + id)
                .then().statusCode(200).body(containsString("At least one role must be granted."));
    }

    /**
     * Tests that an update supplying a too-short password re-renders the form with the length
     * error, exercising the update password-policy branch.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_shortPasswordError() {
        Long id = seedUser("test_usp", Set.of(AppUser.ROLE_VIEWER), true);
        form().formParam("password", "x").formParam("roles", "VIEWER").formParam("active", "on")
                .when().post("/ui/users/" + id)
                .then().statusCode(200).body(containsString("at least 8 characters"));
    }

    /**
     * Tests that demoting the sole active administrator is refused, exercising the lost-admin
     * arm of the last-administrator guard. The bootstrap account is disabled around the request
     * so the seeded account is the only active administrator, then restored.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_lastAdminDemotionRefused() {
        Long id = seedUser("test_last", Set.of(AppUser.ROLE_ADMIN), true);
        setBootstrapActive(false);
        try {
            form().formParam("roles", "VIEWER").formParam("active", "on")
                    .when().post("/ui/users/" + id)
                    .then().statusCode(200).body(containsString("last administrator"));
        } finally {
            setBootstrapActive(true);
        }
    }

    /**
     * Tests that disabling the sole active administrator is refused, exercising the lost-access
     * arm of the last-administrator guard while the administrator role is kept.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testUpdate_lastAdminDisableRefused() {
        Long id = seedUser("test_last", Set.of(AppUser.ROLE_ADMIN), true);
        setBootstrapActive(false);
        try {
            form().formParam("roles", "ADMIN")
                    .when().post("/ui/users/" + id)
                    .then().statusCode(200).body(containsString("last administrator"));
        } finally {
            setBootstrapActive(true);
        }
    }

    /**
     * Tests that a viewer is forbidden from updating an account.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testUpdate_forbiddenForViewer() {
        form().formParam("roles", "VIEWER").formParam("active", "on")
                .when().post("/ui/users/1")
                .then().statusCode(403);
    }

    // --------------------------------------------------
    // Delete
    // --------------------------------------------------

    /**
     * Tests that deleting an existing account redirects to the list with a success notice.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testDelete_existingRedirects() {
        Long id = seedUser("test_del", Set.of(AppUser.ROLE_VIEWER), true);
        given().redirects().follow(false)
                .when().post("/ui/users/" + id + "/delete")
                .then().statusCode(303).header("location", containsString("noticeOk=true"));
    }

    /**
     * Tests that deleting an unknown account redirects with the not-found notice rather than a
     * 404, matching the resource contract.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testDelete_unknownRedirectsWithNotice() {
        given().redirects().follow(false)
                .when().post("/ui/users/999999/delete")
                .then().statusCode(303)
                .header("location", containsString("noticeOk=false"));
    }

    /**
     * Tests that a user cannot delete their own account, exercising the self-deletion guard.
     */
    @Test
    @TestSecurity(user = "test_self", roles = "ADMIN")
    void testDelete_selfRefused() {
        Long id = seedUser("test_self", Set.of(AppUser.ROLE_ADMIN), true);
        given().redirects().follow(false)
                .when().post("/ui/users/" + id + "/delete")
                .then().statusCode(303)
                .header("location", containsString("noticeOk=false"));
    }

    /**
     * Tests that deleting the sole active administrator is refused, exercising the
     * last-administrator delete guard. The bootstrap account is disabled around the request so
     * the seeded account is the only active administrator, then restored.
     */
    @Test
    @TestSecurity(user = "test_admin", roles = "ADMIN")
    void testDelete_lastAdminRefused() {
        Long id = seedUser("test_last", Set.of(AppUser.ROLE_ADMIN), true);
        setBootstrapActive(false);
        try {
            given().redirects().follow(false)
                    .when().post("/ui/users/" + id + "/delete")
                    .then().statusCode(303)
                    .header("location", containsString("noticeOk=false"));
        } finally {
            setBootstrapActive(true);
        }
    }

    /**
     * Tests that a viewer is forbidden from deleting an account.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testDelete_forbiddenForViewer() {
        given().redirects().follow(false)
                .when().post("/ui/users/1/delete")
                .then().statusCode(403);
    }
}
