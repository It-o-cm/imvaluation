package com.intermarche.valuation;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Coverage test for {@link ReferentialReadinessCheck}, driven through the standard readiness
 * endpoint so the check executes inside the container and quarkus-jacoco attributes it.
 * <p>
 * The two servable-state conditions are exercised: an empty store table reports the probe
 * {@code DOWN} naming the missing stores (HTTP 503), and a seeded store flips it {@code UP}
 * (HTTP 200). The offer-schema registry is always populated by the real factories, so its
 * own DOWN arm is an unreachable guard in a running application.
 */
@QuarkusTest
public class ReferentialReadinessCheckCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * Clears the reference set before each test, in reverse dependency order, so the store
     * count reflects only what the test seeds.
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
     * Tests that an empty store table reports the readiness probe DOWN and names the missing
     * stores.
     */
    @Test
    void testReady_downWhenNoStore() {
        given().accept("application/json")
                .when().get("/q/health/ready")
                .then().statusCode(503)
                .body(containsString("imvaluation-ready"))
                .body(containsString("\"missing\": \"stores\""));
    }

    /**
     * Tests that a single seeded store flips the readiness probe UP, the offer schemas being
     * always present.
     */
    @Test
    void testReady_upWhenStoreExists() {
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = new Store();
            store.code = "S1";
            store.name = "Store S1";
            store.persist();
        });
        given().accept("application/json")
                .when().get("/q/health/ready")
                .then().statusCode(200)
                .body(containsString("imvaluation-ready"))
                .body(containsString("\"status\": \"UP\""));
    }
}
