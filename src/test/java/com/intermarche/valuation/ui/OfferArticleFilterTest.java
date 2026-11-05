package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

/**
 * Tests the offer list/export "article" filter (spec-exploitation §4, §5.4): an EAN selects the
 * offers whose targeting covers it, resolved exactly as the engine does — a directly targeted EAN, an
 * EAN covered through a family, and never an excluded EAN — and the CSV export is consistent with the
 * screen.
 * <p>
 * Offers are seeded in a committed transaction so the real HTTP list/export see them; the filter is
 * exercised over the wire under an ADMIN identity.
 */
@QuarkusTest
public class OfferArticleFilterTest {

    /**
     * EAN of the directly targeted article.
     */
    private static final String EAN_A = "1000000000011";

    /**
     * EAN of the article covered only through a family.
     */
    private static final String EAN_B = "1000000000012";

    /**
     * EAN of the excluded article.
     */
    private static final String EAN_C = "1000000000013";

    /**
     * Seeds the store, products, family and offers before each test.
     */
    @BeforeEach
    void setUp() {
        wipe();
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = DomainUtils.createAndPersistStore("STORE_ART", 48.0, 2.0);
            DomainUtils.createAndPersistProduct(EAN_A, "Article A", ProductType.UNIT);
            Product productB = DomainUtils.createAndPersistProduct(EAN_B, "Article B", ProductType.UNIT);
            DomainUtils.createAndPersistProduct(EAN_C, "Article C", ProductType.UNIT);
            ProductFamily family = DomainUtils.createAndPersistProductFamily("FAM_ART");
            family.products.add(productB);
            family.persist();
            DomainUtils.createAndPersistOffer("OA_DIRECT", store, "IMMEDIATE_VOUCHER_DISCOUNT",
                    "{\"targetEans\":[\"" + EAN_A + "\"]}");
            DomainUtils.createAndPersistOffer("OA_FAMILY", store, "IMMEDIATE_VOUCHER_DISCOUNT",
                    "{\"targetFamilyCodes\":[\"FAM_ART\"]}");
            DomainUtils.createAndPersistOffer("OA_EXCL", store, "IMMEDIATE_VOUCHER_DISCOUNT",
                    "{\"targetEans\":[\"" + EAN_A + "\",\"" + EAN_C + "\"],\"excludedEans\":[\"" + EAN_C + "\"]}");
        });
    }

    /**
     * Clears the seeded rows after the class.
     */
    @AfterAll
    static void afterAll() {
        wipe();
    }

    /**
     * Deletes the offers, family, products and store this test seeds, in dependency order.
     */
    private static void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            Offer.delete("code like ?1", "OA_%");
            ProductFamily.delete("code = ?1", "FAM_ART");
            Product.delete("ean in ?1", java.util.List.of(EAN_A, EAN_B, EAN_C));
            Store.delete("code = ?1", "STORE_ART");
        });
    }

    /**
     * §5.4: a directly targeted EAN finds the offer that lists it and the offer that lists it minus an
     * exclusion, but not the family-only offer.
     */
    @Test
    @TestSecurity(user = "art_admin", roles = "ADMIN")
    void testDirectlyTargetedEanFound() {
        given().when().get("/ui/offers?article=" + EAN_A)
                .then().statusCode(200)
                .body(containsString("OA_DIRECT"))
                .body(containsString("OA_EXCL"))
                .body(not(containsString("OA_FAMILY")));
    }

    /**
     * §5.4: an EAN covered only through a family finds the family-targeted offer and no other.
     */
    @Test
    @TestSecurity(user = "art_admin", roles = "ADMIN")
    void testFamilyCoveredEanFound() {
        given().when().get("/ui/offers?article=" + EAN_B)
                .then().statusCode(200)
                .body(containsString("OA_FAMILY"))
                .body(not(containsString("OA_DIRECT")))
                .body(not(containsString("OA_EXCL")));
    }

    /**
     * §5.4: an excluded EAN finds no offer — the exclusion wins even over an explicitly targeted EAN.
     */
    @Test
    @TestSecurity(user = "art_admin", roles = "ADMIN")
    void testExcludedEanNotFound() {
        given().when().get("/ui/offers?article=" + EAN_C)
                .then().statusCode(200)
                .body(not(containsString("OA_DIRECT")))
                .body(not(containsString("OA_EXCL")))
                .body(not(containsString("OA_FAMILY")));
    }

    /**
     * §5.4: the CSV export filtered by article is consistent with the screen — same offers in, same
     * offers out.
     */
    @Test
    @TestSecurity(user = "art_admin", roles = "ADMIN")
    void testCsvExportConsistentWithScreen() {
        String csv = given().accept("text/csv").when().get("/ui/offers/export?article=" + EAN_A)
                .then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("OA_DIRECT"), "export must carry OA_DIRECT: " + csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("OA_EXCL"), "export must carry OA_EXCL: " + csv);
        org.junit.jupiter.api.Assertions.assertFalse(csv.contains("OA_FAMILY"), "export must not carry OA_FAMILY: " + csv);
    }
}
