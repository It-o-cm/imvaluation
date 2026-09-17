package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.VatRate;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the VAT-regime CSV import endpoint, covering creation, update by number, the
 * checksum-optimised no-op re-import, and the doctrine that a rate corrected in the referential
 * never rewrites the price rows that name the regime.
 */
@QuarkusTest
public class VatRateCsvResourceTest {

    /**
     * The JTA transaction manager, for the programmatic seeding transactions.
     */
    @Inject
    TransactionManager tm;

    /**
     * Runs a supplier inside a fresh committed transaction.
     *
     * @param runnable the logic to run.
     * @param <R>      the result type.
     * @return the supplier's result.
     */
    private <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (NotSupportedException | SystemException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            try {
                tm.setRollbackOnly();
            } catch (SystemException ex) {
                throw new RuntimeException(e);
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts a request carrying the admin credentials the import endpoints require.
     *
     * @return an authenticated request specification.
     */
    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("admin", "admin");
    }

    /**
     * Clears the mutable catalog and the regimes before each test, so assertions do not depend
     * on rows a committing class left in the shared database. Prices are cleared before the
     * regimes they reference.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        Product.deleteAll();
        Store.deleteAll();
        VatRate.deleteAll();
    }

    /**
     * Posts a CSV body to the VAT-rate import endpoint.
     *
     * @param csv the CSV body.
     * @return the RestAssured response validatable.
     */
    private io.restassured.response.ValidatableResponse importVatRates(String csv) {
        return authenticated().body(csv).contentType(ContentType.TEXT)
                .when().post("/vat-rates/import").then();
    }

    /**
     * Tests the creation of new regimes.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewRegimes_Created() {
        importVatRates("NUMBER|RATE|LABEL\n10|0.1500|Fifteen\n11|0.0800|Eight")
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"));
        VatRate ten = withTransaction(() -> VatRate.findByNumber(10));
        assertNotNull(ten);
        assertEquals(0, new BigDecimal("0.1500").compareTo(ten.rate));
        assertEquals("Fifteen", ten.label);
    }

    /**
     * Tests that a regime is updated by its number when the rate or label changed.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateByNumber() {
        withTransaction(() -> {
            new VatRate(10, new BigDecimal("0.1500"), "Fifteen").persist();
            return null;
        });
        importVatRates("NUMBER|RATE|LABEL\n10|0.1600|Sixteen")
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));
        VatRate ten = withTransaction(() -> VatRate.findByNumber(10));
        assertEquals(0, new BigDecimal("0.1600").compareTo(ten.rate));
        assertEquals("Sixteen", ten.label);
    }

    /**
     * Tests that re-importing an unchanged regime file reports zero updates.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportUnchangedRegime_NoUpdate() {
        String csv = "NUMBER|RATE|LABEL\n10|0.1500|Fifteen";
        importVatRates(csv).statusCode(200).body(containsString("\"createdCount\":1"));
        importVatRates(csv).statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests that a file without the RATE column is rejected.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testMissingRateColumn_Rejected() {
        importVatRates("NUMBER|LABEL\n10|Fifteen")
                .statusCode(400)
                .body(containsString("RATE"));
    }

    /**
     * Tests that a line whose NUMBER is not an integer is rejected, the error naming it. This
     * also drives the one-by-one fallback (the chunk transaction fails and retries line by line).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testInvalidNumber_Rejected() {
        importVatRates("NUMBER|RATE|LABEL\nabc|0.1500|X")
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("Invalid VAT number: abc"));
    }

    /**
     * Tests that a line with an empty RATE cell is rejected, the error naming the number.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testMissingRateValue_Rejected() {
        importVatRates("NUMBER|RATE|LABEL\n10||X")
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("Invalid VAT rate for number 10"));
    }

    /**
     * Tests that a re-import of unchanged prices reports zero updates, the checksum being keyed
     * on the regime NUMBER.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportUnchangedPrices_ZeroUpdated() {
        seedStoreAndProduct();
        importVatRates("NUMBER|RATE|LABEL\n1|0.2000|Taux normal").statusCode(200);
        String priceCsv = "EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n"
                + "EAN1|S1|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|";
        importPrices(priceCsv).statusCode(200).body(containsString("\"createdCount\":1"));
        importPrices(priceCsv).statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the doctrine: correcting a regime's rate does not rewrite the price rows that name
     * it. The price's checksum (keyed on the regime number) and its number are untouched, while
     * its rate is read through the now-corrected regime.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRateCorrectionDoesNotRewritePriceRows() {
        seedStoreAndProduct();
        importVatRates("NUMBER|RATE|LABEL\n1|0.2000|Taux normal").statusCode(200);
        importPrices("EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n"
                + "EAN1|S1|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|")
                .statusCode(200).body(containsString("\"createdCount\":1"));
        Integer checksumBefore = withTransaction(() ->
                Price.find("product.ean = ?1", "EAN1").<Price>firstResult().checksum);
        // Correct the regime's legal rate on the SAME number.
        importVatRates("NUMBER|RATE|LABEL\n1|0.2100|Taux normal")
                .statusCode(200).body(containsString("\"updatedCount\":1"));
        withTransaction(() -> {
            Price price = Price.find("product.ean = ?1", "EAN1").firstResult();
            // The price row is untouched: same checksum, same regime number.
            assertEquals(checksumBefore, price.checksum);
            assertEquals(1, price.vatNumber());
            // But the rate is now read through the corrected regime.
            assertEquals(0, new BigDecimal("0.2100").compareTo(price.vatRate()));
            return null;
        });
    }

    /**
     * Seeds one store and one product used by the price-import tests.
     */
    private void seedStoreAndProduct() {
        withTransaction(() -> {
            Store store = new Store();
            store.code = "S1";
            store.name = "Store 1";
            store.address = testAddress();
            store.persist();
            Product product = new Product();
            product.ean = "EAN1";
            product.name = "Product 1";
            product.productType = ProductType.UNIT;
            product.persist();
            return null;
        });
    }

    /**
     * Builds a minimal valid address.
     *
     * @return the address.
     */
    private Adresse testAddress() {
        Adresse address = new Adresse();
        address.streetLine1 = "1 Test Street";
        address.city = "Paris";
        address.postalCode = "75000";
        address.country = "France";
        return address;
    }

    /**
     * Posts a CSV body to the price import endpoint.
     *
     * @param csv the CSV body.
     * @return the RestAssured response validatable.
     */
    private io.restassured.response.ValidatableResponse importPrices(String csv) {
        return authenticated().body(csv).contentType(ContentType.TEXT)
                .when().post("/prices/import").then();
    }
}
