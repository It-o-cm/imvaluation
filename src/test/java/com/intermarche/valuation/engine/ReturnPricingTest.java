package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.VatRate;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Plain unit tests for {@link ReturnPricing}: the pushed trio is checked field by field, the
 * referential lookup is driven through mocked {@link Product} and {@link Price} finders.
 */
class ReturnPricingTest {

    /**
     * Builds a decimal.
     *
     * @param value the decimal text.
     * @return the decimal.
     */
    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /**
     * Builds a catalog product.
     *
     * @return a unit product named "Lait UHT 1L".
     */
    private static Product product() {
        Product product = new Product();
        product.id = 7L;
        product.ean = "3300000000002";
        product.name = "Lait UHT 1L";
        product.productType = ProductType.UNIT;
        return product;
    }

    /**
     * Builds a catalog price.
     *
     * @param excl the price tax excluded.
     * @param incl the price tax included.
     * @return the price at 20% VAT.
     */
    private static Price price(String excl, String incl) {
        Price price = new Price();
        price.priceExcludingTax = d(excl);
        price.priceIncludingTax = d(incl);
        price.vat = new VatRate(null, d("0.2000"), null);
        return price;
    }

    /**
     * Builds the store the prices belong to.
     *
     * @return the store 0101.
     */
    private static Store store() {
        Store store = new Store();
        store.id = 3L;
        store.code = "0101";
        return store;
    }

    /**
     * Covers the private constructor of the utility class.
     *
     * @throws Exception when reflection fails.
     */
    @Test
    void privateConstructor_isReachable() throws Exception {
        Constructor<ReturnPricing> constructor = ReturnPricing.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertTrue(constructor.newInstance() instanceof ReturnPricing);
    }

    /**
     * A complete pushed trio is used as is, with no referential read: unit price and amount for
     * the quantity, no label (DG-01-03-08).
     */
    @Test
    void pushed_completeTrio_pricesWithoutReferential() {
        ReturnPricing.Priced priced = ReturnPricing.pushed(d("2"), d("1.50"), d("1.80"), d("0.20"));
        assertEquals(d("1.50"), priced.unit.amountExcludingTax);
        assertEquals(d("1.80"), priced.unit.amountIncludingTax);
        assertEquals(d("0.2000"), priced.unit.vatRate);
        assertEquals(d("3.00"), priced.amount.amountExcludingTax);
        assertEquals(d("3.60"), priced.amount.amountIncludingTax);
        assertNull(priced.label);
    }

    /**
     * Each missing member of the pushed trio is named in the 400 refusal.
     */
    @Test
    void pushed_partialTrio_namesMissingFields() {
        ReturnValuationService.Refusal noExcl = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.pushed(d("1"), null, d("1.80"), d("0.20")));
        assertEquals(400, noExcl.status);
        assertEquals("A PUSHED price requires pricePerUnitExclTax.", noExcl.getMessage());
        ReturnValuationService.Refusal noIncl = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.pushed(d("1"), d("1.50"), null, d("0.20")));
        assertEquals("A PUSHED price requires pricePerUnitInclTax.", noIncl.getMessage());
        ReturnValuationService.Refusal noRate = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.pushed(d("1"), d("1.50"), d("1.80"), null));
        assertEquals("A PUSHED price requires vatRate.", noRate.getMessage());
        ReturnValuationService.Refusal none = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.pushed(d("1"), null, null, null));
        assertEquals("A PUSHED price requires pricePerUnitExclTax, pricePerUnitInclTax, vatRate.", none.getMessage());
    }

    /**
     * Without a date, the referential price in force now is used, with the product label
     * (DG-01-03-04).
     */
    @Test
    void referential_withoutDate_usesCurrentPrice() {
        Product product = product();
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findByEan("3300000000002")).thenReturn(product);
            prices.when(() -> Price.findActivePriceAtDate(eq(7L), eq(3L), any(LocalDateTime.class), eq(PriceUsage.DEFAULT)))
                    .thenReturn(price("2.30", "2.76"));
            ReturnPricing.Priced priced = ReturnPricing.referential("3300000000002", d("3"), null, store());
            assertEquals(d("2.76"), priced.unit.amountIncludingTax);
            assertEquals(d("8.28"), priced.amount.amountIncludingTax);
            assertEquals(d("6.90"), priced.amount.amountExcludingTax);
            assertEquals("Lait UHT 1L", priced.label);
        }
    }

    /**
     * With a date-time, or a bare date read as the start of that day, the referential price in
     * force at that instant is used (DG-01-03-07).
     */
    @Test
    void referential_withDate_usesHistoricalPrice() {
        Product product = product();
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findByEan("3300000000002")).thenReturn(product);
            prices.when(() -> Price.findActivePriceAtDate(7L, 3L, LocalDateTime.of(2025, 6, 15, 10, 0), PriceUsage.DEFAULT))
                    .thenReturn(price("0.80", "0.96"));
            ReturnPricing.Priced priced = ReturnPricing.referential("3300000000002", d("1"), "2025-06-15T10:00:00", store());
            assertEquals(d("0.96"), priced.amount.amountIncludingTax);
            prices.when(() -> Price.findActivePriceAtDate(7L, 3L, LocalDateTime.of(2025, 6, 15, 0, 0), PriceUsage.DEFAULT))
                    .thenReturn(price("0.75", "0.90"));
            assertEquals(d("0.90"), ReturnPricing.referential("3300000000002", d("1"), "2025-06-15", store())
                    .amount.amountIncludingTax);
        }
    }

    /**
     * A missing or blank EAN, and an unreadable date, are 400 refusals.
     */
    @Test
    void referential_malformedLine_isRefused() {
        ReturnValuationService.Refusal noEan = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.referential(null, d("1"), null, store()));
        assertEquals(400, noEan.status);
        assertEquals("produceEan is required for a CURRENT or DATE price.", noEan.getMessage());
        ReturnValuationService.Refusal blankEan = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.referential(" ", d("1"), null, store()));
        assertEquals(400, blankEan.status);
        ReturnValuationService.Refusal badDate = assertThrows(ReturnValuationService.Refusal.class,
                () -> ReturnPricing.referential("3300000000002", d("1"), "15/06/2025", store()));
        assertEquals(400, badDate.status);
        assertEquals("Invalid priceDate '15/06/2025': expected an ISO-8601 date or date-time.", badDate.getMessage());
    }

    /**
     * An article or a price the referential does not know is a 422 refusal carrying the engine's
     * message.
     */
    @Test
    void referential_unknownArticle_is422() {
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("9999")).thenReturn(null);
            ReturnValuationService.Refusal e = assertThrows(ReturnValuationService.Refusal.class,
                    () -> ReturnPricing.referential("9999", d("1"), null, store()));
            assertEquals(422, e.status);
            assertEquals("Article 9999: Configuration Error: Product not found for EAN '9999'", e.getMessage());
        }
    }
}
