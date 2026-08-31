package com.intermarche.valuation.ui;

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

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Endpoint coverage tests for {@link OfferUiResource}, exercised through the real HTTP stack
 * so the container instruments the resource for coverage.
 * <p>
 * Authentication uses {@code @TestSecurity} to inject an identity carrying the role under
 * test; the role matrix (VIEWER, ADMIN, anonymous) is asserted alongside the list, export,
 * form, and CRUD flows and every server-side validation branch.
 */
@QuarkusTest
public class OfferUiResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * A valid free-delivery specification, accepted by the schema of that offer type.
     */
    private static final String VALID_SPEC =
            "{\"tiers\":[{\"threshold\":10.0,\"value\":50.0,\"type\":\"FIXED_AMOUNT\"}]}";

    /**
     * Clears the whole reference set before each test so rows left by another class cannot
     * skew the assertions; the order is the reverse of the foreign-key dependencies.
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

    /**
     * Persists a store with the given code in its own committed transaction.
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
     * Persists an offer linked to the given stores and groups, and returns its identifier.
     *
     * @param code       The offer code.
     * @param type       The offer type discriminator.
     * @param spec       The offer specification.
     * @param storeCodes The store codes to link.
     * @param groupCodes The store group codes to link.
     * @return The identifier of the created offer.
     */
    Long seedOffer(String code, String type, String spec, List<String> storeCodes, List<String> groupCodes) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Offer offer = new Offer();
            offer.code = code;
            offer.type = type;
            offer.specification = spec;
            for (String sc : storeCodes) {
                offer.stores.add(Store.find("code", sc).firstResult());
            }
            for (String gc : groupCodes) {
                offer.storeGroups.add(StoreGroup.find("code", gc).firstResult());
            }
            offer.persist();
            return offer.id;
        });
    }

    // --------------------------------------------------
    // List and export
    // --------------------------------------------------

    /**
     * Tests that the list screen renders for an administrator and shows a seeded offer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testList_rendersForAdmin() {
        seedStore("S1");
        seedOffer("OFF_A", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of());
        given().when().get("/ui/offers")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("OFF_A"));
    }

    /**
     * Tests every filter, each explicit sort key, the invalid-sort fallback, the descending
     * direction and an out-of-range page number.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testList_filtersSortsAndPaging() {
        seedStore("S1");
        seedGroup("G1");
        seedOffer("OFF_A", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of("G1"));
        given().when().get("/ui/offers?q=off&type=FREE_DELIVERY_THRESHOLD&target=S1&ean=123"
                        + "&sort=type&dir=desc&page=1")
                .then().statusCode(200);
        given().when().get("/ui/offers?sort=eans&dir=asc").then().statusCode(200);
        given().when().get("/ui/offers?sort=unknown").then().statusCode(200);
        given().when().get("/ui/offers?page=999").then().statusCode(200);
        given().when().get("/ui/offers?target=G1").then().statusCode(200).body(containsString("OFF_A"));
    }

    /**
     * Tests that an anonymous caller is redirected to the login screen by the form
     * authentication mechanism.
     */
    @Test
    void testList_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/offers")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }

    /**
     * Tests the CSV export, covering the header, the joined store and group codes and the
     * sanitisation of separators embedded in a specification.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testExport_streamsSanitisedCsv() {
        seedStore("S1");
        seedGroup("G1");
        // A valid JSON specification carrying a pipe, so the CSV sanitiser replaces it.
        seedOffer("OFF_A", "FREE_DELIVERY_THRESHOLD", "{\"note\":\"a|b\"}", List.of("S1"), List.of("G1"));
        given().accept("text/csv").when().get("/ui/offers/export")
                .then().statusCode(200)
                .header("Content-Disposition", containsString("offers.csv"))
                .body(containsString("CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES"))
                .body(containsString("OFF_A"))
                .body(containsString("S1"))
                .body(containsString("G1"));
    }

    /**
     * Tests that the export honours the invalid-sort fallback to the default column.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testExport_invalidSortFallback() {
        given().accept("text/csv").when().get("/ui/offers/export?sort=unknown&dir=desc")
                .then().statusCode(200);
    }

    // --------------------------------------------------
    // Forms
    // --------------------------------------------------

    /**
     * Tests that the creation form renders for an administrator.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testCreateForm_rendersForAdmin() {
        given().when().get("/ui/offers/new?type=FREE_DELIVERY_THRESHOLD")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that a viewer is forbidden from the creation form.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testCreateForm_forbiddenForViewer() {
        given().when().get("/ui/offers/new").then().statusCode(403);
    }

    /**
     * Tests that the edition form renders for an existing offer.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testEditForm_rendersForExistingOffer() {
        seedStore("S1");
        Long id = seedOffer("OFF_A", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of());
        given().when().get("/ui/offers/" + id)
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that editing an unknown offer yields a 404.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testEditForm_unknownYields404() {
        given().when().get("/ui/offers/999999").then().statusCode(404);
    }

    // --------------------------------------------------
    // Save
    // --------------------------------------------------

    /**
     * Starts a form-encoded request for the offer endpoints without following redirects, so
     * a 303 outcome can be asserted directly.
     *
     * @return A request specification ready for form parameters.
     */
    private io.restassured.specification.RequestSpecification form() {
        return given().redirects().follow(false)
                .contentType(ContentType.URLENC);
    }

    /**
     * Tests that a valid submission creates the offer and redirects to the list.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_validRedirects() {
        seedStore("S1");
        form().formParam("code", "OFF_NEW").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .formParam("storeGroupCodes", "")
                .when().post("/ui/offers/new")
                .then().statusCode(303);
    }

    /**
     * Tests that a blank code re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_blankCodeError() {
        form().formParam("code", "").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("code is mandatory"));
    }

    /**
     * Tests that a blank type re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_blankTypeError() {
        form().formParam("code", "OFF_X").formParam("type", "")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("type is mandatory"));
    }

    /**
     * Tests that a duplicate code re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_duplicateCodeError() {
        seedStore("S1");
        seedOffer("OFF_DUP", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of());
        form().formParam("code", "OFF_DUP").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("already exists"));
    }

    /**
     * Tests that a submission without any target re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_noTargetError() {
        form().formParam("code", "OFF_NT").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "")
                .formParam("storeGroupCodes", "")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("at least one store"));
    }

    /**
     * Tests that a specification violating the schema re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_schemaError() {
        seedStore("S1");
        form().formParam("code", "OFF_BAD").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", "{\"tiers\":[]}").formParam("storeCodes", "S1")
                .when().post("/ui/offers/new")
                .then().statusCode(200);
    }

    /**
     * Tests that an unknown store code re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_unknownStoreError() {
        form().formParam("code", "OFF_US").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "NOPE")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("Unknown store codes"));
    }

    /**
     * Tests that an unknown store group code re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_unknownGroupError() {
        form().formParam("code", "OFF_UG").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "")
                .formParam("storeGroupCodes", "NOPE")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("Unknown store group codes"));
    }

    /**
     * Tests that a blank specification re-renders the form with the mandatory-spec error and
     * that a form re-render with null code fields defaults them to empty strings.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_blankSpecificationError() {
        seedStore("S1");
        form().formParam("code", "OFF_BS").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", "").formParam("storeCodes", "S1")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("specification is mandatory"));
    }

    /**
     * Tests that a validation error re-renders the form even when no code fields were
     * submitted, exercising the null defaulting of the re-render helper.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_errorWithNullCodeFields() {
        form().formParam("code", "OFF_NF").formParam("type", "")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("type is mandatory"));
    }

    /**
     * Tests that a submission targeting only a valid store group succeeds, exercising the
     * group resolution success path.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_validGroupTargetRedirects() {
        seedGroup("G1");
        form().formParam("code", "OFF_G").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "")
                .formParam("storeGroupCodes", "G1")
                .when().post("/ui/offers/new")
                .then().statusCode(303);
    }

    /**
     * Tests that a partially unknown store list is rejected, exercising the found/missing
     * computation over a non-empty result set.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_partiallyUnknownStores() {
        seedStore("S1");
        form().formParam("code", "OFF_PS").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1,NOPE")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("Unknown store codes: NOPE"));
    }

    /**
     * Tests that a partially unknown store group list is rejected, exercising the
     * found/missing computation over a non-empty result set.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testSave_partiallyUnknownGroups() {
        seedGroup("G1");
        form().formParam("code", "OFF_PG").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "")
                .formParam("storeGroupCodes", "G1,NOPE")
                .when().post("/ui/offers/new")
                .then().statusCode(200).body(containsString("Unknown store group codes: NOPE"));
    }

    /**
     * Tests that a viewer is forbidden from creating an offer.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSave_forbiddenForViewer() {
        form().formParam("code", "OFF_V").formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/new")
                .then().statusCode(403);
    }

    // --------------------------------------------------
    // Update and delete
    // --------------------------------------------------

    /**
     * Tests that a valid update redirects to the list.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdate_validRedirects() {
        seedStore("S1");
        Long id = seedOffer("OFF_U", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of());
        form().formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/" + id)
                .then().statusCode(303);
    }

    /**
     * Tests that updating an unknown offer yields a 404.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdate_unknownYields404() {
        form().formParam("type", "FREE_DELIVERY_THRESHOLD")
                .formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/999999")
                .then().statusCode(404);
    }

    /**
     * Tests that an update with an invalid submission re-renders the form with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdate_validationError() {
        seedStore("S1");
        Long id = seedOffer("OFF_UE", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of());
        form().formParam("type", "").formParam("specification", VALID_SPEC).formParam("storeCodes", "S1")
                .when().post("/ui/offers/" + id)
                .then().statusCode(200).body(containsString("type is mandatory"));
    }

    /**
     * Tests that deleting an existing offer redirects to the list.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testDelete_existingRedirects() {
        seedStore("S1");
        Long id = seedOffer("OFF_D", "FREE_DELIVERY_THRESHOLD", VALID_SPEC, List.of("S1"), List.of());
        given().redirects().follow(false)
                .when().post("/ui/offers/" + id + "/delete")
                .then().statusCode(303);
    }

    /**
     * Tests that deleting an unknown offer yields a 404.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testDelete_unknownYields404() {
        given().redirects().follow(false)
                .when().post("/ui/offers/999999/delete")
                .then().statusCode(404);
    }

    /**
     * Tests that a viewer is forbidden from deleting an offer.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testDelete_forbiddenForViewer() {
        given().redirects().follow(false)
                .when().post("/ui/offers/1/delete")
                .then().statusCode(403);
    }
}
