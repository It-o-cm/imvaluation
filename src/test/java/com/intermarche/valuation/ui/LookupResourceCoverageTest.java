package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.Address;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.valuation.CoverageDbReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * Endpoint coverage tests for {@link LookupResource}, exercised through the real HTTP stack so
 * the container instruments the resource and its inner {@link LookupResource.Suggestion} for
 * coverage.
 * <p>
 * Every suggestion endpoint is driven with matching, non-matching and empty queries so both
 * arms of each presentation branch (brand present or blank, city present or absent, the
 * store/group merge, the {@code MAX_RESULTS} cut-offs and the CSV split) are reached. The role
 * matrix is asserted for an authenticated viewer and for an anonymous caller.
 */
@QuarkusTest
public class LookupResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * Clears every entity the lookups query before each test so rows left by another class
     * cannot skew the assertions; the order is the reverse of the foreign-key dependencies.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Persists a product in its own committed transaction.
     *
     * @param ean   The product EAN.
     * @param name  The product name.
     * @param brand The product brand, may be null or blank.
     */
    void seedProduct(String ean, String name, String brand) {
        QuarkusTransaction.requiringNew().run(() -> {
            Product product = new Product();
            product.ean = ean;
            product.name = name;
            product.brand = brand;
            product.productType = ProductType.UNIT;
            product.persist();
        });
    }

    /**
     * Persists a store in its own committed transaction, optionally with an address city.
     *
     * @param code The store code.
     * @param name The store name.
     * @param city The address city, or null to leave the store without an address.
     */
    void seedStore(String code, String name, String city) {
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = new Store();
            store.code = code;
            store.name = name;
            if (city != null) {
                Address address = new Address();
                address.city = city;
                store.address = address;
            }
            store.persist();
        });
    }

    /**
     * Persists a store group in its own committed transaction.
     *
     * @param code The group code.
     * @param name The group name.
     */
    void seedGroup(String code, String name) {
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup group = new StoreGroup();
            group.code = code;
            group.name = name;
            group.persist();
        });
    }

    /**
     * Persists a product family carrying the given flags in its own committed transaction.
     *
     * @param code  The family code.
     * @param flags The comma separated flag tokens.
     */
    void seedFamily(String code, String flags) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductFamily family = new ProductFamily();
            family.code = code;
            family.flags = flags;
            family.persist();
        });
    }

    // --------------------------------------------------
    // Products
    // --------------------------------------------------

    /**
     * Tests the numeric-query branch of the product search, covering both the brand-present
     * detail line and the blank-brand fallback to the raw EAN.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchProducts_numericQueryCoversBrandBranches() {
        seedProduct("3000000000017", "Milk", "Lactel");
        seedProduct("3000000000024", "Bread", null);
        given().when().get("/ui/lookup/products?q=30000000000")
                .then().statusCode(200)
                .body("value", hasItem("3000000000017"))
                .body("value", hasItem("3000000000024"))
                .body("detail", hasItem("Lactel · 3000000000017"))
                .body("detail", hasItem("3000000000024"));
    }

    /**
     * Tests the text-query branch of the product search, matching on the product name.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchProducts_textQueryMatchesName() {
        seedProduct("3000000000017", "Milk", "Lactel");
        given().when().get("/ui/lookup/products?q=milk")
                .then().statusCode(200)
                .body("label", hasItem("Milk"));
    }

    /**
     * Tests the empty-query branch of the product search, which returns the first products.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchProducts_emptyQueryReturnsAll() {
        seedProduct("3000000000017", "Milk", "Lactel");
        given().when().get("/ui/lookup/products")
                .then().statusCode(200)
                .body("value", hasItem("3000000000017"));
    }

    /**
     * Tests that the resolve endpoint maps a batch of EANs to their labels, exercising the
     * non-blank CSV split with a value that is added after trimming.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testResolveProducts_knownEans() {
        seedProduct("3000000000017", "Milk", "Lactel");
        seedProduct("3000000000024", "Bread", null);
        given().when().get("/ui/lookup/products/resolve?eans=3000000000017,3000000000024")
                .then().statusCode(200)
                .body("label", hasItem("Milk"))
                .body("label", hasItem("Bread"));
    }

    /**
     * Tests that a blank {@code eans} parameter yields an empty response, covering the
     * blank-input arm of the CSV split.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testResolveProducts_blankYieldsEmpty() {
        given().when().get("/ui/lookup/products/resolve?eans=")
                .then().statusCode(200)
                .body("size()", org.hamcrest.CoreMatchers.is(0));
    }

    /**
     * Tests that empty CSV segments are skipped while non-empty ones are kept, covering the
     * trim/skip branch of the CSV split.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testResolveProducts_skipsEmptySegments() {
        seedProduct("3000000000017", "Milk", "Lactel");
        given().queryParam("eans", " ,3000000000017, ")
                .when().get("/ui/lookup/products/resolve")
                .then().statusCode(200)
                .body("value", hasItem("3000000000017"))
                .body("size()", org.hamcrest.CoreMatchers.is(1));
    }

    // --------------------------------------------------
    // Stores and groups
    // --------------------------------------------------

    /**
     * Tests the store search, covering the city-present detail line and the missing-city
     * fallback to a null detail.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchStores_coversCityBranches() {
        seedStore("S1", "Store One", "Lyon");
        seedStore("S2", "Store Two", null);
        given().when().get("/ui/lookup/stores?q=s")
                .then().statusCode(200)
                .body("value", hasItem("S1"))
                .body("value", hasItem("S2"))
                .body("detail", hasItem("Lyon"));
    }

    /**
     * Tests the empty-query branch of the store search.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchStores_emptyQuery() {
        seedStore("S1", "Store One", "Lyon");
        given().when().get("/ui/lookup/stores")
                .then().statusCode(200)
                .body("value", hasItem("S1"));
    }

    /**
     * Tests the store-group search on both a matching and the empty query.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchStoreGroups_matchingAndEmpty() {
        seedGroup("G1", "Group One");
        given().when().get("/ui/lookup/store-groups?q=g")
                .then().statusCode(200).body("value", hasItem("G1"));
        given().when().get("/ui/lookup/store-groups")
                .then().statusCode(200).body("value", hasItem("G1"));
    }

    /**
     * Tests the non-matching branch of a search, which returns an empty list.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchStoreGroups_noMatch() {
        seedGroup("G1", "Group One");
        given().when().get("/ui/lookup/store-groups?q=zzz")
                .then().statusCode(200).body("value", not(hasItem("G1")));
    }

    // --------------------------------------------------
    // Targets (stores + groups merge)
    // --------------------------------------------------

    /**
     * Tests the merged target search, covering the "Store" detail for a store without a city,
     * the "Store · city" detail for a store with one, and the "Store group" detail.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchTargets_mergesStoresAndGroups() {
        seedStore("S1", "Store One", "Lyon");
        seedStore("S2", "Store Two", null);
        seedGroup("G1", "Group One");
        given().when().get("/ui/lookup/targets")
                .then().statusCode(200)
                .body("detail", hasItem("Store · Lyon"))
                .body("detail", hasItem("Store"))
                .body("detail", hasItem("Store group"))
                .body("value", hasItem("S1"))
                .body("value", hasItem("G1"));
    }

    /**
     * Tests that the target search stops at {@code MAX_RESULTS} while consuming the stores,
     * covering the early-return branch of the store loop.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchTargets_storeCapStopsMerge() {
        for (int i = 0; i < 20; i++) {
            seedStore(String.format("S%02d", i), "Store " + i, null);
        }
        seedGroup("G1", "Group One");
        given().when().get("/ui/lookup/targets")
                .then().statusCode(200)
                .body("size()", org.hamcrest.CoreMatchers.is(20))
                .body("value", not(hasItem("G1")));
    }

    /**
     * Tests that the target search stops at {@code MAX_RESULTS} while consuming the groups,
     * covering the break branch of the group loop.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchTargets_groupCapStopsMerge() {
        for (int i = 0; i < 15; i++) {
            seedStore(String.format("A%02d", i), "Store " + i, null);
        }
        for (int i = 0; i < 10; i++) {
            seedGroup(String.format("B%02d", i), "Group " + i);
        }
        given().when().get("/ui/lookup/targets")
                .then().statusCode(200)
                .body("size()", org.hamcrest.CoreMatchers.is(20));
    }

    // --------------------------------------------------
    // Flags
    // --------------------------------------------------

    /**
     * Tests the flag search on both the empty query and a matching fragment.
     */
    @Test
    @TestSecurity(user = "test_viewer", roles = "VIEWER")
    void testSearchFlags_matchingAndEmpty() {
        seedFamily("FAM1", "BIO,FROZEN");
        given().when().get("/ui/lookup/flags")
                .then().statusCode(200)
                .body("value", hasItem("BIO"))
                .body("value", hasItem("FROZEN"));
        given().when().get("/ui/lookup/flags?q=bio")
                .then().statusCode(200)
                .body("value", hasItem("BIO"))
                .body("value", not(hasItem("FROZEN")));
    }

    // --------------------------------------------------
    // Authorization
    // --------------------------------------------------

    /**
     * Tests that an anonymous caller is redirected to the login screen by the form
     * authentication mechanism.
     */
    @Test
    void testLookup_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/lookup/products")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }
}
