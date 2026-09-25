package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.EgalimRegime;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the EGAlim regime facet of the product CSV import (EGALIM_GUARD spec §2, §9): the
 * optional {@code EGALIM_REGIME} column, its strict rejection of unknown values, its presence in
 * the checksum, and the {@link EgalimRegime} enum's default ceilings.
 */
@QuarkusTest
public class EgalimRegimeImportTest {

    /**
     * The Product CSV resource under test.
     */
    @Inject
    ProductCsvResource productCsvResource;

    /**
     * Starts a request authenticated as the bootstrap administrator.
     *
     * @return an authenticated request specification.
     */
    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("admin", "admin");
    }

    /**
     * Cleans the database before each test to ensure isolation.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        ProductCategoryStorage.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Builds a single-column {@code EGALIM_REGIME} line for the parser under test.
     *
     * @param value the cell value.
     * @return the header-bound line.
     */
    private ImporterCsvResource.LineData regimeLine(String value) {
        Map<String, Integer> header = new LinkedHashMap<>();
        header.put(ProductCsvResource.COL_EGALIM_REGIME, 0);
        return new ImporterCsvResource.LineData(1, header, new String[]{value},
                ProductCsvResource.COL_EGALIM_REGIME);
    }

    // --------------------------------------------------
    // Enum and checksum
    // --------------------------------------------------

    /**
     * The enum carries the legal ceilings and the exempt sentinel.
     */
    @Test
    void testEnumDefaultCaps() {
        assertEquals(new BigDecimal("0.34"), EgalimRegime.FOOD_34.defaultCap());
        assertEquals(new BigDecimal("0.40"), EgalimRegime.DPH_40.defaultCap());
        assertNull(EgalimRegime.EXEMPT.defaultCap());
        assertTrue(EgalimRegime.FOOD_34.isCapped());
        assertTrue(EgalimRegime.DPH_40.isCapped());
        assertEquals(false, EgalimRegime.EXEMPT.isCapped());
    }

    /**
     * The EGAlim regime is part of the product checksum (spec §2).
     */
    @Test
    void testChecksumIncludesEgalimRegime() {
        Product product = new Product();
        product.ean = "1234567890123";
        product.name = "Test";
        product.productType = ProductType.UNIT;
        product.egalimRegime = EgalimRegime.EXEMPT;
        int exempt = product.getChecksum();
        product.egalimRegime = EgalimRegime.FOOD_34;
        int food = product.getChecksum();
        assertNotEquals(exempt, food);
    }

    // --------------------------------------------------
    // Column parsing
    // --------------------------------------------------

    /**
     * A present, valid value parses to its regime (case-insensitive).
     */
    @Test
    void testParseRegime_Valid() {
        assertEquals(EgalimRegime.FOOD_34, productCsvResource.safeParseEgalimRegime(
                regimeLine("FOOD_34"), ProductCsvResource.COL_EGALIM_REGIME));
        assertEquals(EgalimRegime.DPH_40, productCsvResource.safeParseEgalimRegime(
                regimeLine("dph_40"), ProductCsvResource.COL_EGALIM_REGIME));
    }

    /**
     * An absent or blank cell reads as the fail-open default {@code EXEMPT}.
     */
    @Test
    void testParseRegime_BlankDefaultsExempt() {
        assertEquals(EgalimRegime.EXEMPT, productCsvResource.safeParseEgalimRegime(
                regimeLine(""), ProductCsvResource.COL_EGALIM_REGIME));
        Map<String, Integer> emptyHeader = new LinkedHashMap<>();
        ImporterCsvResource.LineData noColumn =
                new ImporterCsvResource.LineData(1, emptyHeader, new String[]{}, "EAN");
        assertEquals(EgalimRegime.EXEMPT, productCsvResource.safeParseEgalimRegime(
                noColumn, ProductCsvResource.COL_EGALIM_REGIME));
    }

    /**
     * A present but unknown value rejects the line (strict house pattern).
     */
    @Test
    void testParseRegime_UnknownRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> productCsvResource.safeParseEgalimRegime(
                        regimeLine("BOGUS"), ProductCsvResource.COL_EGALIM_REGIME));
        assertTrue(error.getMessage().contains("Invalid EGAlim regime"));
    }

    // --------------------------------------------------
    // Import round-trip
    // --------------------------------------------------

    /**
     * Importing with the optional {@code EGALIM_REGIME} column stores the regime; re-importing
     * the same row is a checksum no-op.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImport_WithRegime_StoredAndStable() {
        String csv = "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE|EGALIM_REGIME\n"
                + "3270190100001|Cafe|Cafe moulu|Brand|null|null|UNIT|pcs|true|FOOD_34";
        authenticated().body(csv).contentType(ContentType.TEXT).when().post("/products/import")
                .then().statusCode(200).body(containsString("\"createdCount\":1"));
        assertEquals(EgalimRegime.FOOD_34, Product.findByEan("3270190100001").egalimRegime);
        authenticated().body(csv).contentType(ContentType.TEXT).when().post("/products/import")
                .then().statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Importing without the optional column leaves the product exempt (fail-open default).
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImport_WithoutColumn_Exempt() {
        String csv = "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE\n"
                + "3270190100002|The|The vert|Brand|null|null|UNIT|pcs|true";
        authenticated().body(csv).contentType(ContentType.TEXT).when().post("/products/import")
                .then().statusCode(200).body(containsString("\"createdCount\":1"));
        assertEquals(EgalimRegime.EXEMPT, Product.findByEan("3270190100002").egalimRegime);
    }

    /**
     * Importing an unknown regime rejects the line: nothing is created and an error is reported.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImport_UnknownRegime_LineRejected() {
        String csv = "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE|EGALIM_REGIME\n"
                + "3270190100003|Savon|Savon|Brand|null|null|UNIT|pcs|true|WRONG";
        authenticated().body(csv).contentType(ContentType.TEXT).when().post("/products/import")
                .then().statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""));
        assertNull(Product.findByEan("3270190100003"));
    }
}
