package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * {@code @QuarkusTest} coverage of {@link Basket.Item} price resolution.
 * <p>
 * The price-resolution branches live behind static Panache finders, mocked here with
 * {@link org.mockito.Mockito#mockStatic}. The existing {@code BasketTest} exercises the same
 * logic but is a plain unit test, which quarkus-jacoco does not attribute; this class re-drives
 * every branch from within a {@code @QuarkusTest} so the source lines are counted. Static mocks
 * are held in try-with-resources blocks, one scope per test.
 */
@QuarkusTest
public class BasketCoverageTest {

    /**
     * Covers the manual-pricing branch: when the three price fields are present a transient
     * {@link Price} is built and no database lookup is performed.
     */
    @Test
    void getPriceUsesManualPricingWhenTripletPresent() {
        try (MockedStatic<Price> mockedPrice = mockStatic(Price.class)) {
            Basket.Item item = new Basket.Item();
            item.pricePerUnitExclTax = new BigDecimal("10.00");
            item.pricePerUnitInclTax = new BigDecimal("12.00");
            item.vatRate = new BigDecimal("0.20");
            Store store = new Store();
            store.id = 1L;
            Price result = item.getPrice(store, PriceUsage.DEFAULT);
            assertEquals(new BigDecimal("10.00"), result.priceExcludingTax);
            assertEquals(new BigDecimal("12.00"), result.priceIncludingTax);
            assertEquals(new BigDecimal("0.20"), result.vatRate);
            mockedPrice.verifyNoInteractions();
        }
    }

    /**
     * Covers the database-lookup branch with an explicit ISO price date.
     */
    @Test
    void getPriceLooksUpDatabaseWithExplicitDate() {
        try (MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<Price> mockedPrice = mockStatic(Price.class)) {
            Basket.Item item = new Basket.Item();
            item.produceEan = "111";
            item.priceDate = "2023-10-27T10:00:00";
            Product product = new Product();
            product.id = 100L;
            mockedProduct.when(() -> Product.findByEan("111")).thenReturn(product);
            Store store = new Store();
            store.id = 5L;
            Price dbPrice = new Price();
            mockedPrice.when(() -> Price.findActivePriceAtDate(
                    100L, 5L, LocalDateTime.parse("2023-10-27T10:00:00"), PriceUsage.DEFAULT))
                    .thenReturn(dbPrice);
            assertEquals(dbPrice, item.getPrice(store, PriceUsage.DEFAULT));
        }
    }

    /**
     * Covers the invalid-date branch: a non-ISO price date raises an
     * {@link IllegalStateException} wrapping a {@link DateTimeParseException}.
     */
    @Test
    void getPriceRejectsInvalidDate() {
        try (MockedStatic<Product> mockedProduct = mockStatic(Product.class)) {
            Basket.Item item = new Basket.Item();
            item.produceEan = "333";
            item.priceDate = "27/10/2023";
            Product product = new Product();
            product.id = 300L;
            mockedProduct.when(() -> Product.findByEan("333")).thenReturn(product);
            Store store = new Store();
            store.id = 7L;
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> item.getPrice(store, PriceUsage.DEFAULT));
            assertTrue(ex.getMessage().contains("Invalid date format"));
            assertTrue(ex.getCause() instanceof DateTimeParseException);
        }
    }

    /**
     * Covers the default-date branch: with no price date the current time from
     * {@link DateTimeProvider} is used for the lookup.
     */
    @Test
    void getPriceDefaultsToNowWhenNoDate() {
        try (MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<Price> mockedPrice = mockStatic(Price.class);
             MockedStatic<DateTimeProvider> mockedTime = mockStatic(DateTimeProvider.class)) {
            Basket.Item item = new Basket.Item();
            item.produceEan = "222";
            Product product = new Product();
            product.id = 200L;
            mockedProduct.when(() -> Product.findByEan("222")).thenReturn(product);
            Store store = new Store();
            store.id = 6L;
            LocalDateTime now = LocalDateTime.now();
            mockedTime.when(DateTimeProvider::now).thenReturn(now);
            Price dbPrice = new Price();
            mockedPrice.when(() -> Price.findActivePriceAtDate(200L, 6L, now, PriceUsage.DEFAULT))
                    .thenReturn(dbPrice);
            assertEquals(dbPrice, item.getPrice(store, PriceUsage.DEFAULT));
            mockedTime.verify(DateTimeProvider::now);
        }
    }

    /**
     * Covers the price-not-found branch: a null lookup result raises an
     * {@link IllegalStateException} naming the product and store.
     */
    @Test
    void getPriceThrowsWhenNoActivePrice() {
        try (MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<Price> mockedPrice = mockStatic(Price.class);
             MockedStatic<DateTimeProvider> mockedTime = mockStatic(DateTimeProvider.class)) {
            Basket.Item item = new Basket.Item();
            item.produceEan = "444";
            Product product = new Product();
            product.id = 400L;
            product.name = "Banana";
            mockedProduct.when(() -> Product.findByEan("444")).thenReturn(product);
            Store store = new Store();
            store.id = 8L;
            store.code = "S08";
            mockedTime.when(DateTimeProvider::now).thenReturn(LocalDateTime.now());
            mockedPrice.when(() -> Price.findActivePriceAtDate(anyLong(), anyLong(), any(), any()))
                    .thenReturn(null);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> item.getPrice(store, PriceUsage.DEFAULT));
            assertTrue(ex.getMessage().contains("No active price found"));
            assertTrue(ex.getMessage().contains("Banana"));
            assertTrue(ex.getMessage().contains("S08"));
        }
    }

    /**
     * Covers the false arm of the manual-pricing guard: an incomplete triplet (missing
     * inclusive-tax price) falls back to the database lookup.
     */
    @Test
    void getPriceFallsBackToDatabaseOnIncompleteTriplet() {
        try (MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<Price> mockedPrice = mockStatic(Price.class);
             MockedStatic<DateTimeProvider> mockedTime = mockStatic(DateTimeProvider.class)) {
            Basket.Item item = new Basket.Item();
            item.produceEan = "777";
            item.pricePerUnitExclTax = new BigDecimal("10.00");
            item.pricePerUnitInclTax = null;
            item.vatRate = new BigDecimal("0.20");
            Product product = new Product();
            product.id = 777L;
            mockedProduct.when(() -> Product.findByEan("777")).thenReturn(product);
            Store store = new Store();
            store.id = 77L;
            mockedTime.when(DateTimeProvider::now).thenReturn(LocalDateTime.now());
            Price dbPrice = new Price();
            mockedPrice.when(() -> Price.findActivePriceAtDate(anyLong(), anyLong(), any(), any()))
                    .thenReturn(dbPrice);
            assertEquals(dbPrice, item.getPrice(store, PriceUsage.DEFAULT));
        }
    }

    /**
     * Covers {@code getAmount}, which resolves the price then delegates the arithmetic to the
     * static {@link AmountEvaluation#getAmount(Product, Price, Double)} utility.
     */
    @Test
    void getAmountDelegatesToAmountEvaluation() {
        try (MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<AmountEvaluation> mockedAmount = mockStatic(AmountEvaluation.class)) {
            Basket.Item item = new Basket.Item();
            item.produceEan = "555";
            item.quantity = 2.5;
            item.pricePerUnitExclTax = new BigDecimal("10.00");
            item.pricePerUnitInclTax = new BigDecimal("12.00");
            item.vatRate = new BigDecimal("0.20");
            Product product = new Product();
            product.id = 500L;
            mockedProduct.when(() -> Product.findByEan("555")).thenReturn(product);
            Store store = new Store();
            store.id = 9L;
            AmountEvaluation expected = new AmountEvaluation(
                    new BigDecimal("25.00"), new BigDecimal("30.00"), new BigDecimal("0.20"));
            mockedAmount.when(() -> AmountEvaluation.getAmount(eq(product), any(Price.class), eq(2.5)))
                    .thenReturn(expected);
            AmountEvaluation result = item.getAmount(store, PriceUsage.DEFAULT);
            assertNotNull(result);
            assertEquals(new BigDecimal("25.00"), result.amountExcludingTax);
        }
    }
}
