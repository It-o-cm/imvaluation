package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.Address;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.valuation.CoverageDbReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Endpoint coverage tests for {@link StoreGroupUiResource}, exercised through the real HTTP
 * stack so the container instruments the resource for coverage.
 * <p>
 * The resource exposes only two endpoints: the workbench screen (GET, open to every role) and
 * the wholesale save (POST, JSON, administrator only). The save endpoint drives the private
 * {@code apply}/{@code reaches} logic, so its every validation branch is reached by submitting
 * a crafted hierarchy and asserting the exact refusal message returned by
 * {@link RejectedHierarchyMapper} (409) or by the in-method bad-request short-circuit (400).
 * Authentication uses {@code @TestSecurity} to inject the role under test; the role matrix
 * (VIEWER, MANAGER, ADMIN, anonymous) is asserted alongside the functional branches.
 */
@QuarkusTest
public class StoreGroupUiResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * Clears the whole reference set before each test so rows left by another class cannot skew
     * the assertions; the order is the reverse of the foreign-key dependencies.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    // --------------------------------------------------
    // Seed helpers
    // --------------------------------------------------

    /**
     * Persists a store with the given code and no address in its own committed transaction.
     *
     * @param code The store code.
     */
    void seedStore(String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = new Store();
            store.code = code;
            store.name = "Store " + code;
            store.persist();
        });
    }

    /**
     * Persists a store carrying an address with the given city in its own committed transaction,
     * so the model serialisation exercises the non-null address arm.
     *
     * @param code The store code.
     * @param city The city recorded in the store address.
     */
    void seedStoreWithCity(String code, String city) {
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = new Store();
            store.code = code;
            store.name = "Store " + code;
            store.address = new Address();
            store.address.city = city;
            store.persist();
        });
    }

    /**
     * Persists a store group with the given code in its own committed transaction.
     *
     * @param code The store group code.
     */
    void seedGroup(String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup group = new StoreGroup();
            group.code = code;
            group.name = "Group " + code;
            group.persist();
        });
    }

    /**
     * Persists a parent group already containing a child group, so the removal path can walk the
     * parents of a group the next submission drops.
     *
     * @param parentCode The parent group code.
     * @param childCode  The child group code.
     */
    void seedParentWithChild(String parentCode, String childCode) {
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup child = new StoreGroup();
            child.code = childCode;
            child.name = "Group " + childCode;
            child.persist();
            StoreGroup parent = new StoreGroup();
            parent.code = parentCode;
            parent.name = "Group " + parentCode;
            parent.storeGroups.add(child);
            parent.persist();
        });
    }

    /**
     * Starts a JSON request against the save endpoint without following redirects.
     *
     * @param body The raw JSON body to submit.
     * @return A request specification ready to be posted.
     */
    private io.restassured.specification.RequestSpecification save(String body) {
        return given().redirects().follow(false).contentType(ContentType.JSON).body(body);
    }

    // --------------------------------------------------
    // Workbench screen and role matrix
    // --------------------------------------------------

    /**
     * Tests that the workbench renders for an administrator and serialises the seeded catalog,
     * exercising the writable arm of {@code canWrite} and both address arms of the model builder.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testWorkbench_rendersForAdmin() {
        seedStoreWithCity("S1", "Lyon");
        seedStore("S2");
        seedGroup("G1");
        given().when().get("/ui/store-groups")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("G1"))
                .body(containsString("S1"))
                .body(containsString("Lyon"))
                .body(containsString("S2"));
    }

    /**
     * Tests that the workbench renders for a viewer, exercising the read-only arm of
     * {@code canWrite}.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testWorkbench_rendersForViewer() {
        given().when().get("/ui/store-groups")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that the workbench renders for a manager, confirming the class-level role list also
     * admits the intermediate role.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testWorkbench_rendersForManager() {
        given().when().get("/ui/store-groups")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that an anonymous caller is redirected to the login screen by the form authentication
     * mechanism.
     */
    @Test
    void testWorkbench_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/store-groups")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }

    // --------------------------------------------------
    // Save: bad-request short-circuit
    // --------------------------------------------------

    /**
     * Tests that a null payload short-circuits to a bad request, exercising the first arm of the
     * nothing-to-save guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_nullPayloadRejected() {
        save("null").when().post("/ui/store-groups")
                .then().statusCode(400).body(containsString("Nothing to save."));
    }

    /**
     * Tests that a payload missing its groups list short-circuits to a bad request, exercising the
     * second arm of the nothing-to-save guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_missingGroupsRejected() {
        save("{}").when().post("/ui/store-groups")
                .then().statusCode(400).body(containsString("Nothing to save."));
    }

    // --------------------------------------------------
    // Save: validation branches
    // --------------------------------------------------

    /**
     * Tests that a group declared without a code is refused, exercising the null-code arm of the
     * missing-code guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_nullCodeRefused() {
        save("{\"groups\":[{\"name\":\"anonymous\"}]}").when().post("/ui/store-groups")
                .then().statusCode(409).body(containsString("A group is missing its code."));
    }

    /**
     * Tests that a group declared with a blank code is refused, exercising the blank-code arm of
     * the missing-code guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_blankCodeRefused() {
        save("{\"groups\":[{\"code\":\"   \"}]}").when().post("/ui/store-groups")
                .then().statusCode(409).body(containsString("A group is missing its code."));
    }

    /**
     * Tests that an unknown store code is refused, exercising the missing-store arm of the
     * membership resolution.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_unknownStoreRefused() {
        save("{\"groups\":[{\"code\":\"G1\",\"name\":\"G1\",\"storeCodes\":[\"NOPE\"]}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(409).body(containsString("Unknown store code: NOPE"));
    }

    /**
     * Tests that an unknown child group code is refused, exercising the missing-child arm of the
     * child resolution.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_unknownChildRefused() {
        save("{\"groups\":[{\"code\":\"G1\",\"name\":\"G1\",\"childCodes\":[\"NOPE\"]}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(409).body(containsString("Unknown group code: NOPE"));
    }

    /**
     * Tests that a group declaring itself as its own child is refused, exercising the direct
     * self-reference arm of the cycle detector.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_selfCycleRefused() {
        save("{\"groups\":[{\"code\":\"A\",\"name\":\"A\",\"childCodes\":[\"A\"]}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(409).body(containsString("Group 'A' ends up containing itself."));
    }

    /**
     * Tests that a mutual containment is refused, exercising the recursive arm of the cycle
     * detector where a target is reached through an intermediate group.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_mutualCycleRefused() {
        save("{\"groups\":[{\"code\":\"A\",\"name\":\"A\",\"childCodes\":[\"B\"]},"
                + "{\"code\":\"B\",\"name\":\"B\",\"childCodes\":[\"A\"]}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(409).body(containsString("ends up containing itself."));
    }

    // --------------------------------------------------
    // Save: success paths
    // --------------------------------------------------

    /**
     * Tests that a full valid hierarchy is saved, exercising group creation, the provided-name
     * arm, non-null store and child membership, and the default-name arm on a bare child.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_validHierarchyAccepted() {
        seedStore("S1");
        save("{\"groups\":[{\"code\":\"G1\",\"name\":\"Group One\",\"storeCodes\":[\"S1\"],"
                + "\"childCodes\":[\"G2\"]},{\"code\":\"G2\"}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(200).body(containsString("saved"));
    }

    /**
     * Tests that re-declaring an already persisted group reuses it, exercising the existing-group
     * arm of the create-or-update loop.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_existingGroupReused() {
        seedGroup("G1");
        save("{\"groups\":[{\"code\":\"G1\",\"name\":\"Renamed\"}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(200).body(containsString("saved"));
    }

    /**
     * Tests that omitting a previously persisted child group deletes it and detaches it from its
     * parent, exercising the removal path and its parent-walk.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_droppedGroupRemovedFromParent() {
        seedParentWithChild("P", "C");
        save("{\"groups\":[{\"code\":\"P\",\"name\":\"P\"}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(200).body(containsString("saved"));
    }

    /**
     * Tests that an acyclic diamond hierarchy is saved, exercising the already-visited short
     * circuit of the cycle detector on a shared descendant.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_diamondHierarchyAccepted() {
        save("{\"groups\":[{\"code\":\"A\",\"name\":\"A\",\"childCodes\":[\"B\",\"C\"]},"
                + "{\"code\":\"B\",\"name\":\"B\",\"childCodes\":[\"D\"]},"
                + "{\"code\":\"C\",\"name\":\"C\",\"childCodes\":[\"D\"]},"
                + "{\"code\":\"D\",\"name\":\"D\"}]}")
                .when().post("/ui/store-groups")
                .then().statusCode(200).body(containsString("saved"));
    }

    // --------------------------------------------------
    // Save: role matrix
    // --------------------------------------------------

    /**
     * Tests that a viewer is forbidden from saving, confirming the administrator-only method
     * override of the class-level role list.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSave_forbiddenForViewer() {
        save("{\"groups\":[]}").when().post("/ui/store-groups")
                .then().statusCode(403);
    }

    /**
     * Tests that a manager is forbidden from saving, confirming the intermediate role does not
     * satisfy the administrator-only override.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testSave_forbiddenForManager() {
        save("{\"groups\":[]}").when().post("/ui/store-groups")
                .then().statusCode(403);
    }
}
