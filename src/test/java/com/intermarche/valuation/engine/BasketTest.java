package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class for {@link Basket} and its inner classes.
 * <p>
 * Focuses on the business logic contained within the {@link Basket.Item} class,
 * specifically price resolution strategies and amount calculation delegation.
 */
@ExtendWith(MockitoExtension.class)
public class BasketTest {

    // MockedStatic handles for static dependencies (Panache repositories, Time providers)
    private MockedStatic<Product> mockedProduct;
    private MockedStatic<Price> mockedPrice;
    private MockedStatic<DateTimeProvider> mockedDateTimeProvider;
    private MockedStatic<AmountEvaluation> mockedAmountEvaluation;

    @BeforeEach
    void setUp() {
        mockedProduct = mockStatic(Product.class);
        mockedPrice = mockStatic(Price.class);
        mockedDateTimeProvider = mockStatic(DateTimeProvider.class);
        mockedAmountEvaluation = mockStatic(AmountEvaluation.class);
    }

    @AfterEach
    void tearDown() {
        // Close static mocks after each test to avoid memory leaks and interference
        mockedProduct.close();
        mockedPrice.close();
        mockedDateTimeProvider.close();
        mockedAmountEvaluation.close();
    }

    // --------------------------------------------------
    // Tests for Basket.Item
    // --------------------------------------------------

    /**
     * Tests {@link Basket.Item#getProduct()} when the product is not cached.
     * Verifies that the product is fetched from the repository (via EngineTrait) and cached.
     */
    @Test
    void testItemGetProduct_FetchesFromRepository() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";

        Product mockProduct = new Product();
        mockProduct.id = 1L;
        mockProduct.ean = "1234567890123";
        mockProduct.name = "Test Product";

        // Assuming EngineTrait delegates to the static Panache method
        mockedProduct.when(() -> Product.findByEan("1234567890123")).thenReturn(mockProduct);

        // Act
        Product result = item.getProduct();

        // Assert
        assertNotNull(result);
        assertEquals(mockProduct, result);
        // Verify interaction
        mockedProduct.verify(() -> Product.findByEan("1234567890123"), times(1));

        // Act 2: Call again to test caching
        Product cachedResult = item.getProduct();
        assertEquals(mockProduct, cachedResult);
        // Verify no new interaction (cached)
        mockedProduct.verify(() -> Product.findByEan(anyString()), times(1));
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} with manual pricing.
     * <p>
     * When price fields (ExclTax, InclTax, VatRate) are present on the item,
     * the method should return a constructed Price object without querying the database.
     */
    @Test
    void testItemGetPrice_ManualPricing() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.pricePerUnitExclTax = new BigDecimal("10.00");
        item.pricePerUnitInclTax = new BigDecimal("12.00");
        item.vatRate = new BigDecimal("0.20");

        Store store = new Store();
        store.id = 10L;
        store.code = "STORE01";

        // Act
        Price result = item.getPrice(store, PriceUsage.DEFAULT);

        // Assert
        assertNotNull(result);
        assertEquals(new BigDecimal("10.00"), result.priceExcludingTax);
        assertEquals(new BigDecimal("12.00"), result.priceIncludingTax);
        assertEquals(new BigDecimal("0.20"), result.vatRate);

        // Verify NO database lookup occurred
        mockedPrice.verify(() -> Price.findActivePriceAtDate(anyLong(), anyLong(), any(), any()), never());
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} in database lookup mode.
     * <p>
     * Scenario: A valid ISO date string is provided on the item.
     */
    @Test
    void testItemGetPrice_DatabaseLookup_WithDate() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "111";
        item.priceDate = "2023-10-27T10:00:00"; // Valid ISO date
        // Manual prices null
        item.pricePerUnitExclTax = null;

        Product product = new Product();
        product.id = 100L;
        product.name = "Apple";
        mockedProduct.when(() -> Product.findByEan("111")).thenReturn(product);

        Store store = new Store();
        store.id = 5L;
        store.code = "S01";

        Price dbPrice = new Price();
        dbPrice.priceExcludingTax = new BigDecimal("5.00");
        mockedPrice.when(() -> Price.findActivePriceAtDate(100L, 5L, LocalDateTime.parse("2023-10-27T10:00:00"), PriceUsage.DEFAULT))
                .thenReturn(dbPrice);

        // Act
        Price result = item.getPrice(store, PriceUsage.DEFAULT);

        // Assert
        assertNotNull(result);
        assertEquals(dbPrice, result);
        mockedPrice.verify(() -> Price.findActivePriceAtDate(eq(100L), eq(5L), any(LocalDateTime.class), eq(PriceUsage.DEFAULT)));
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} in database lookup mode.
     * <p>
     * Scenario: No date string is provided, so it defaults to {@link DateTimeProvider#now()}.
     */
    @Test
    void testItemGetPrice_DatabaseLookup_DefaultToNow() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "222";
        item.priceDate = null; // No date provided
        item.pricePerUnitExclTax = null;

        Product product = new Product();
        product.id = 200L;
        mockedProduct.when(() -> Product.findByEan("222")).thenReturn(product);

        Store store = new Store();
        store.id = 6L;
        store.code = "S02";

        LocalDateTime now = LocalDateTime.now();
        mockedDateTimeProvider.when(DateTimeProvider::now).thenReturn(now);

        Price dbPrice = new Price();
        mockedPrice.when(() -> Price.findActivePriceAtDate(200L, 6L, now, PriceUsage.DEFAULT))
                .thenReturn(dbPrice);

        // Act
        Price result = item.getPrice(store, PriceUsage.DEFAULT);

        // Assert
        assertNotNull(result);
        mockedDateTimeProvider.verify(DateTimeProvider::now, times(1));
        mockedPrice.verify(() -> Price.findActivePriceAtDate(200L, 6L, now, PriceUsage.DEFAULT));
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} with an invalid date format.
     * <p>
     * Expects an {@link IllegalStateException} wrapping a {@link DateTimeParseException}.
     */
    @Test
    void testItemGetPrice_DatabaseLookup_InvalidDateFormat() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "333";
        item.priceDate = "27/10/2023"; // Invalid format (not ISO)
        item.pricePerUnitExclTax = null;

        Product product = new Product();
        product.id = 300L;
        mockedProduct.when(() -> Product.findByEan("333")).thenReturn(product);

        Store store = new Store();
        store.id = 7L;

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            item.getPrice(store, PriceUsage.DEFAULT);
        });

        assertTrue(exception.getMessage().contains("Invalid date format"));
        assertTrue(exception.getCause() instanceof DateTimeParseException);
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} when the database lookup returns null.
     * <p>
     * Expects an {@link IllegalStateException} indicating no active price was found.
     */
    @Test
    void testItemGetPrice_DatabaseLookup_PriceNotFound() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "444";
        item.pricePerUnitExclTax = null;

        Product product = new Product();
        product.id = 400L;
        product.name = "Banana";
        mockedProduct.when(() -> Product.findByEan("444")).thenReturn(product);

        Store store = new Store();
        store.id = 8L;
        store.code = "S08";

        mockedPrice.when(() -> Price.findActivePriceAtDate(anyLong(), anyLong(), any(), any()))
                .thenReturn(null); // No price found

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            item.getPrice(store, PriceUsage.DEFAULT);
        });

        assertTrue(exception.getMessage().contains("No active price found"));
        assertTrue(exception.getMessage().contains("Banana"));
        assertTrue(exception.getMessage().contains("S08"));
    }

    /**
     * Tests {@link Basket.Item#getAmount(Store, PriceUsage)}.
     * <p>
     * Verifies that the method correctly resolves the product and price,
     * then delegates to the static {@link AmountEvaluation} utility.
     */
    @Test
    void testItemGetAmount_DelegatesToEngine() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "555";
        item.quantity = 2.5;
        item.pricePerUnitExclTax = new BigDecimal("10.00");
        item.pricePerUnitInclTax = new BigDecimal("12.00");
        item.vatRate = new BigDecimal("0.20");

        Product product = new Product();
        product.id = 500L;
        product.productType = ProductType.UNIT;
        mockedProduct.when(() -> Product.findByEan("555")).thenReturn(product);

        Store store = new Store();
        store.id = 9L;

        AmountEvaluation mockEval = new AmountEvaluation(new BigDecimal("25.00"), new BigDecimal("30.00"), new BigDecimal("0.20"));
        mockedAmountEvaluation.when(() -> AmountEvaluation.getAmount(eq(product), any(Price.class), eq(2.5)))
                .thenReturn(mockEval);

        // Act
        AmountEvaluation result = item.getAmount(store, PriceUsage.DEFAULT);

        // Assert
        assertNotNull(result);
        assertEquals(new BigDecimal("25.00"), result.amountExcludingTax);

        // Verify delegation
        mockedAmountEvaluation.verify(() -> AmountEvaluation.getAmount(eq(product), any(Price.class), eq(2.5)));
    }

    // --------------------------------------------------
    // Tests for Basket.Address
    // --------------------------------------------------

    /**
     * Tests {@link Basket.Address} structure.
     * <p>
     * Since Address is a simple POJO without logic, we just verify field accessibility.
     */
    @Test
    void testAddressFields() {
        Basket.Address address = new Basket.Address();
        address.streetLine1 = "1 Rue de la Paix";
        address.city = "Paris";
        address.postalCode = "75000";
        address.latitude = 48.8566;
        address.longitude = 2.3522;

        assertEquals("1 Rue de la Paix", address.streetLine1);
        assertEquals("Paris", address.city);
        assertEquals(48.8566, address.latitude);
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} when priceDate is blank.
     * <p>
     * Scenario: priceDate is present but empty ("").
     * Expected behavior: The method should skip parsing and use {@link DateTimeProvider#now()}.
     */
    @Test
    void testItemGetPrice_DatabaseLookup_BlankDateUsesNow() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "999";
        item.priceDate = "   "; // Blank (not null, but empty)
        item.pricePerUnitExclTax = null; // Force DB path to test date logic

        Product product = new Product();
        product.id = 900L;
        mockedProduct.when(() -> Product.findByEan("999")).thenReturn(product);

        Store store = new Store();
        store.id = 99L;
        store.code = "S99";

        LocalDateTime now = LocalDateTime.now();
        mockedDateTimeProvider.when(DateTimeProvider::now).thenReturn(now);

        Price dbPrice = new Price();
        mockedPrice.when(() -> Price.findActivePriceAtDate(900L, 99L, now, PriceUsage.DEFAULT))
                .thenReturn(dbPrice);

        // Act
        item.getPrice(store, PriceUsage.DEFAULT);

        // Assert
        // Verify that DateTimeProvider.now() was called because the date string was blank
        mockedDateTimeProvider.verify(DateTimeProvider::now, times(1));
        mockedPrice.verify(() -> Price.findActivePriceAtDate(900L, 99L, now, PriceUsage.DEFAULT));
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} when manual pricing fields are incomplete (missing InclTax).
     * <p>
     * Scenario: pricePerUnitExclTax is present, but pricePerUnitInclTax is null.
     * Expected behavior: The method must fallback to database lookup instead of creating a manual price.
     */
    @Test
    void testItemGetPrice_ManualPricingFallsBackToDB_MissingInclTax() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "777";
        item.pricePerUnitExclTax = new BigDecimal("10.00"); // Present
        item.pricePerUnitInclTax = null;                     // Missing!
        item.vatRate = new BigDecimal("0.20");               // Present

        Product product = new Product();
        product.id = 777L;
        mockedProduct.when(() -> Product.findByEan("777")).thenReturn(product);

        Store store = new Store();
        store.id = 77L;

        Price dbPrice = new Price(); // The price retrieved from DB
        mockedPrice.when(() -> Price.findActivePriceAtDate(anyLong(), anyLong(), any(), any()))
                .thenReturn(dbPrice);

        // Act
        Price result = item.getPrice(store, PriceUsage.DEFAULT);

        // Assert
        // Should return the DB price, not a constructed manual price
        assertEquals(dbPrice, result);
        // Verify DB lookup was triggered
        mockedPrice.verify(() -> Price.findActivePriceAtDate(eq(777L), eq(77L), any(), eq(PriceUsage.DEFAULT)));
    }

    /**
     * Tests {@link Basket.Item#getPrice(Store, PriceUsage)} when manual pricing fields are incomplete (missing VatRate).
     * <p>
     * Scenario: pricePerUnitExclTax and pricePerUnitInclTax are present, but vatRate is null.
     * Expected behavior: The method must fallback to database lookup.
     */
    @Test
    void testItemGetPrice_ManualPricingFallsBackToDB_MissingVatRate() {
        // Arrange
        Basket.Item item = new Basket.Item();
        item.produceEan = "888";
        item.pricePerUnitExclTax = new BigDecimal("10.00"); // Present
        item.pricePerUnitInclTax = new BigDecimal("12.00"); // Present
        item.vatRate = null;                                 // Missing!

        Product product = new Product();
        product.id = 888L;
        mockedProduct.when(() -> Product.findByEan("888")).thenReturn(product);

        Store store = new Store();
        store.id = 88L;

        Price dbPrice = new Price();
        mockedPrice.when(() -> Price.findActivePriceAtDate(anyLong(), anyLong(), any(), any()))
                .thenReturn(dbPrice);

        // Act
        Price result = item.getPrice(store, PriceUsage.DEFAULT);

        // Assert
        assertEquals(dbPrice, result);
        mockedPrice.verify(() -> Price.findActivePriceAtDate(eq(888L), eq(88L), any(), eq(PriceUsage.DEFAULT)));
    }

    /**
     * Tests the creation and population of a full {@link Basket} object.
     * <p>
     * Verifies that Address, Items, Instructions, and Vignettes can be set and retrieved correctly.
     * This test focuses on the DTO structure (POJO) rather than business logic calculation.
     */
    @Test
    void testCreateBasketWithAddressAndItem() {
        // Arrange & Act
        Basket basket = new Basket();
        basket.customerCode = "CUST_98765";
        basket.storeCode = "STORE_LYON_04";
        basket.deliveryMode = "HOME_DELIVERY";
        basket.createdAt = "2023-11-15T14:30:00";

        // --- 1. Create and set Address ---
        Basket.Address address = new Basket.Address();
        address.streetLine1 = "15 Rue de la République";
        address.streetLine2 = "Bâtiment B, Étage 3";
        address.postalCode = "69002";
        address.city = "Lyon";
        address.country = "France";
        address.latitude = 45.7620;
        address.longitude = 4.8366;
        basket.deliveryAddress = address;

        // --- 2. Create and set Items ---
        Basket.Item item1 = new Basket.Item();
        item1.lineId = "101";
        item1.produceEan = "3124567890123";
        item1.quantity = 2.0;
        // Using manual pricing for this structural test
        item1.pricePerUnitExclTax = new BigDecimal("15.00");
        item1.pricePerUnitInclTax = new BigDecimal("18.00");
        item1.vatRate = new BigDecimal("0.20");

        Basket.Item item2 = new Basket.Item();
        item2.lineId = "102";
        item2.produceEan = "3999999999999";
        item2.quantity = 1.5; // Weighted item
        item2.pricePerUnitExclTax = new BigDecimal("8.00");
        item2.pricePerUnitInclTax = new BigDecimal("8.80");
        item2.vatRate = new BigDecimal("0.10");

        basket.items = List.of(item1, item2);

        // --- 3. Create Instructions and Vignettes ---
        basket.instructions = List.of("Fragile", "Porte digicode : 1234A");
        basket.vignettes = Map.of(
                "3124567890123", 2, // 2 vignettes for item 1
                "3999999999999", 1  // 1 vignette for item 2
        );

        // Assert
        assertNotNull(basket);

        // Verify Address
        assertNotNull(basket.deliveryAddress);
        assertEquals("Lyon", basket.deliveryAddress.city);
        assertEquals("Bâtiment B, Étage 3", basket.deliveryAddress.streetLine2);

        // Verify Items
        assertNotNull(basket.items);
        assertEquals(2, basket.items.size());

        Basket.Item firstItem = basket.items.get(0);
        assertEquals("101", firstItem.lineId);
        assertEquals("3124567890123", firstItem.produceEan);
        assertEquals(2.0, firstItem.quantity);

        Basket.Item secondItem = basket.items.get(1);
        assertEquals("3999999999999", secondItem.produceEan);
        assertEquals(1.5, secondItem.quantity);

        // Verify Metadata
        assertFalse(basket.instructions.isEmpty());
        assertTrue(basket.instructions.contains("Fragile"));

        assertNotNull(basket.vignettes);
        assertEquals(2, basket.vignettes.get("3124567890123"));
    }
}