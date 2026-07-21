package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ImmediateVoucherDiscountFactory}.
 * <p>
 * Updated to reflect that {@code processSpecification} now validates the JSON
 * against a JSON Schema and throws {@link IllegalArgumentException} on non-conformity.
 */
@QuarkusTest
@TestTransaction
public class ImmediateVoucherDiscountFactoryTest {

    @Inject
    ImmediateVoucherDiscountFactory factory;

    private Store store;
    private Product product;

    /**
     * Sets up the database with a {@link Store} and a {@link Product}.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.3522);

        // Create a standard product
        product = new Product();
        product.ean = "1234567890123";
        product.name = "Test Product";
        product.productType = ProductType.UNIT;
        product.active = true;
        product.persist();

        // Reference price (BASE_FOR_DISCOUNT) used to calculate efficiency
        Price price = new Price();
        price.product = product;
        price.store = store;
        price.priceExcludingTax = new BigDecimal("10.00");
        price.priceIncludingTax = new BigDecimal("12.00"); // 20% VAT
        price.vatRate = new BigDecimal("0.20");
        price.priceUsage = PriceUsage.BASE_FOR_DISCOUNT;
        price.priority = 0;
        price.persist();
    }

    // --------------------------------------------------
    // Mock Helper Class
    // --------------------------------------------------

    /**
     * Mock to simulate an existing offer (e.g., N+M) on which the voucher applies.
     */
    public static class MockProductOfferApplication implements ProductAwareOfferApplication {
        final AmountEvaluation amount;
        final double quantity;
        final Product product;

        public MockProductOfferApplication(double priceHt, double quantity, Product product) {
            this.quantity = quantity;
            this.product = product;
            BigDecimal ht = BigDecimal.valueOf(priceHt);
            BigDecimal ttc = ht.multiply(BigDecimal.ONE.add(new BigDecimal("0.20")));
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.20"));
        }

        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        @Override
        public Collection<Basket.Item> getItems() {
            return Collections.emptyList();
        }

        @Override
        public String getType() {
            // This name is used for "matchesTarget" verification
            return "MockProductOffer";
        }

        @Override
        public AmountEvaluation getProductAmount(Product product) {
            if (this.product.equals(product)) return this.amount;
            return null;
        }

        @Override
        public double getProductQuantity(Product product) {
            if (this.product.equals(product)) return this.quantity;
            return 0.0;
        }
    }

    // --------------------------------------------------
    // Tests for buildAppliers
    // --------------------------------------------------

    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        // Arrange: Valid Offer JSON
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_01", store, "IMMEDIATE_VOUCHER", jsonSpec);
        // Basket containing the product
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        item.quantity = 1.0;
        basket.items = List.of(item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        // Assert
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof ImmediateVoucherDiscountFactory.ImmediateVoucherApplier);
    }

    @Test
    void testBuildAppliers_NoMatchingEanInBasket() {
        setUpDatabase();
        // Arrange: Offer targets EAN 999, but basket has 123
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"9999999999999\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_02", store, "IMMEDIATE_VOUCHER", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        item.quantity = 1.0;
        basket.items = List.of(item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        // Assert: No applier created because product is not in basket items map
        assertTrue(appliers.isEmpty());
    }

    // --------------------------------------------------
    // Tests for ImmediateVoucherApplier (Logic)
    // --------------------------------------------------

    @Test
    void testApply_PercentageDiscount() {
        setUpDatabase();
        // Arrange: 10% discount
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_PERC", store, "IMMEDIATE_VOUCHER", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        item.quantity = 2.0; // Quantity 2
        basket.items = List.of(item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add a mock offer that covers the product (Unit Price 10.00 HT)
        evaluation.getOffers().add(new MockProductOfferApplication(10.00, 2.0, product));
        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertFalse(appliers.isEmpty());
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplier) appliers.iterator().next();
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        // Assert
        assertEquals(1, discounts.size());
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplication app =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplication)discounts.iterator().next();
        // Expected calculation: 10.00 HT total * 10% = 1.00 HT
        // TTC: 1.00 * 1.20 = 1.20 TTC
        AmountEvaluation discount = app.getDiscountAmount();
        assertEquals(new BigDecimal("-1.00"), discount.amountExcludingTax);
        assertEquals(new BigDecimal("-1.20"), discount.amountIncludingTax);
    }

    @Test
    void testApply_FixedAmountDiscount() {
        setUpDatabase();
        // Arrange: 2.00€ fixed discount
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"FIXED_AMOUNT\", " +
                "\"value\": 2.00 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_FIX", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        item.quantity = 3.0;
        basket.items = List.of(item);

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.getOffers().add(new MockProductOfferApplication(10.00, 3.0, product));

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplier) appliers.iterator().next();
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        // Product Price HT = 10.00. Discount = 2.00.
        // Calculation: 2.00 * 3 (quantity) = 6.00 HT
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplication app =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplication)discounts.iterator().next();

        AmountEvaluation discount = app.getDiscountAmount();
        assertEquals(new BigDecimal("-6.00"), discount.amountExcludingTax);
        assertEquals(new BigDecimal("-7.20"), discount.amountIncludingTax); // +20%
    }

    @Test
    void testApply_OfferDoesNotMatchClass() {
        setUpDatabase();
        // Arrange: Offer targets "OtherOffer" but app is "MockProductOffer"
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"OtherOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_BAD", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        basket.items = List.of(item);

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.getOffers().add(new MockProductOfferApplication(10.00, 1.0, product));

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplier) appliers.iterator().next();
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty());
    }

    // --------------------------------------------------
    // Tests for ImmediateVoucherApplication
    // --------------------------------------------------

    @Test
    void testImmediateVoucherApplication_Getters() {
        AmountEvaluation amount = new AmountEvaluation(new BigDecimal("-5.00"), new BigDecimal("-6.00"), new BigDecimal("0.20"));
        MockProductOfferApplication mockOffer = new MockProductOfferApplication(10.00, 1.0, product);

        ImmediateVoucherDiscountFactory.ImmediateVoucherApplication app =
                new ImmediateVoucherDiscountFactory.ImmediateVoucherApplication("CODE_123", mockOffer, amount);

        assertEquals("Immediate Voucher Discount : CODE_123", app.getType());
        assertSame(mockOffer, app.getOfferApplication());
        assertSame(amount, app.getDiscountAmount());
    }

    // --------------------------------------------------
    // Mocks for OfferApplier (Necessary because OfferApplier is abstract)
    // --------------------------------------------------

    /**
     * Concrete mock to simulate a Product Offer Applier.
     * The class name ("MockProductOfferApplier") is used for name verification.
     */
    public static class MockProductOfferApplier extends OfferApplier implements ProductAwareOfferApplier {
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) {
            return Collections.emptyList();
        }

        @Override
        public double computeEfficiencyScore(Basket basket) {
            return 0.0;
        }

        @Override
        public boolean isApplicable(Product product) {
            // For the test, we say the product is always applicable
            return true;
        }
    }

    /**
     * Concrete mock to simulate another type of applier (e.g., Service).
     */
    public static class OtherOfferApplier extends OfferApplier implements ProductAwareOfferApplier {
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) { return Collections.emptyList(); }
        @Override
        public double computeEfficiencyScore(Basket basket) { return 0.0; }
        @Override
        public boolean isApplicable(Product product) { return true; }
    }

    // --------------------------------------------------
    // Tests for isApplicable (Applier logic)
    // --------------------------------------------------

    @Test
    void testIsApplicable_ClassNameMatch() {
        setUpDatabase();
        // Arrange
        Map<String, Basket.Item> items = new HashMap<>();
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        items.put("1234567890123", item);

        // We target "MockProductOfferApplier" (the simple name of the class above)
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                new ImmediateVoucherDiscountFactory.ImmediateVoucherApplier(
                        "CODE",
                        List.of("MockProductOfferApplier"),
                        items,
                        ImmediateVoucherDiscountFactory.DiscountType.PERCENTAGE,
                        10.0,
                        store
                );

        // We use the concrete instance that extends OfferApplier
        OfferApplier targetApplier = new MockProductOfferApplier();

        // Act
        boolean result = applier.isApplicable(targetApplier);

        // Assert
        assertTrue(result, "Applier should be applicable because class name matches");
    }


    @Test
    void testIsApplicable_ClassNameMismatch() {
        setUpDatabase();
        // Arrange
        Map<String, Basket.Item> items = new HashMap<>();
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        items.put("1234567890123", item);

        // We target "TargetOffer" which does not match "OtherOfferApplier"
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                new ImmediateVoucherDiscountFactory.ImmediateVoucherApplier(
                        "CODE",
                        List.of("TargetOffer"),
                        items,
                        ImmediateVoucherDiscountFactory.DiscountType.PERCENTAGE,
                        10.0,
                        store
                );

        OfferApplier targetApplier = new OtherOfferApplier();

        // Act
        boolean result = applier.isApplicable(targetApplier);

        // Assert
        assertFalse(result, "Applier should not be applicable because class name does not match");
    }

    // --------------------------------------------------
    // Tests for processOffer Logic (Missing Fields)
    // --------------------------------------------------

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when the offer specification
     * is missing the "targetOfferClass" field.
     * <p>
     * Condition tested: Schema validation failure (missing required field).
     * Expects an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_MissingTargetOfferClass() {
        setUpDatabase();
        // Arrange: JSON missing "targetOfferClass", but having valid EANs, Type, and Value
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_NO_CLASS", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if targetOfferClass is missing (Schema validation)");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when the offer specification
     * is missing the "discountType" field.
     * <p>
     * Condition tested: Schema validation failure (missing required field).
     * Expects an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_MissingDiscountType() {
        setUpDatabase();
        // Arrange: JSON missing "discountType", but having valid Class, EANs, and Value
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"value\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_NO_TYPE", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if discountType is missing (Schema validation)");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when the offer specification
     * is missing the "value" field.
     * <p>
     * Condition tested: Schema validation failure (missing required field).
     * Expects an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_MissingValue() {
        setUpDatabase();
        // Arrange: JSON missing "value", but having valid Class, EANs, and Type
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\" " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_NO_VALUE", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if value is missing (Schema validation)");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when the "targetOfferClass"
     * is provided as a list (JSON array) of strings.
     * <p>
     * Scenario: JSON includes a boolean inside the string list.
     * Condition tested: Schema validation failure (items must be strings).
     * Expects an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_TargetOfferClassIsList() {
        setUpDatabase();
        // Arrange: "targetOfferClass" is a list, but contains a boolean `false`
        // Schema expects items to be strings.
        String jsonSpec = "{ " +
                "\"targetOfferClass\": [\"MockProductOfferApplier\", \"OtherOfferApplier\", false], " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_LIST", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if targetOfferClass list contains non-string items (Schema validation)");
    }

    // --------------------------------------------------
    // Tests for getTargetEans (Type Handling)
    // --------------------------------------------------

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when "targetEans"
     * is provided as a single string value.
     * <p>
     * Condition tested: {@code eansObj instanceof String} is {@code true}.
     * Expectation: The applier is created because the helper retrieves the single item
     * from the basket map.
     */
    @Test
    void testBuildAppliers_TargetEansIsSingleString() {
        setUpDatabase();
        // Arrange: "targetEans" is a single string, not an array
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": \"1234567890123\", " + // Note: No brackets []
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_SINGLE_EAN", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertFalse(appliers.isEmpty(), "Applier should be created for a single string EAN");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when "targetEans"
     * is neither a String nor a List (e.g., a Number or Object).
     * <p>
     * Condition tested: Schema validation failure.
     * Expects an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_TargetEansIsInvalidType() {
        setUpDatabase();
        // Arrange: "targetEans" is a number (123456), not a String or List
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": 123456, " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_BAD_TYPE", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if targetEans type is invalid (neither String nor List) - Schema validation");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when "targetEans"
     * is a String, but the EAN is not found in the basket items map.
     * <p>
     * Condition tested: {@code eansObj instanceof String} is {@code true},
     * AND {@code item != null} is {@code false} (lookup returns null).
     * Expectation: Returns an empty list of appliers. The helper retrieves a null item,
     * so it is not added to {@code targetItems}, causing the final validation to fail.
     */
    @Test
    void testBuildAppliers_TargetEansStringNotFoundInBasket() {
        setUpDatabase();
        // Arrange: Offer targets EAN "9999999999999" (String)
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": \"9999999999999\", " + // This EAN is NOT in the basket
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_MISSING_EAN", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        // Basket contains a DIFFERENT item ("123..."), so "999..." will not be found
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        // Because getTargetEans returns empty (item was null in basketItems),
        // the processOffer condition !targetItems.isEmpty() fails.
        assertTrue(appliers.isEmpty(), "Should not create applier if the target EAN string is not in the basket");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when "targetEans"
     * is a list containing elements of different types (String and Number).
     * <p>
     * Condition tested: Schema validation failure (items must be strings).
     * Expects an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_TargetEansListWithNonStringElements() {
        setUpDatabase();
        // Arrange: "targetEans" is a list containing a valid String EAN and an invalid Integer.
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\", 123456], " + // 123456 is a Number, not a String
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_MIXED_LIST", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        // Basket contains the valid String EAN
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if targetEans list contains non-string elements - Schema validation");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when the "targetOfferClass"
     * is present in the JSON but is an empty string or empty list.
     * <p>
     * Condition tested: Business logic {@code !targetOfferClasses.isEmpty()}.
     * Expectation: Returns an empty list of appliers.
     * (Note: Empty array is valid JSON per schema, but rejected by logic).
     */
    @Test
    void testBuildAppliers_EmptyTargetOfferClass() {
        setUpDatabase();
        // Arrange: "targetOfferClass" is an empty list
        String jsonSpec = "{ " +
                "\"targetOfferClass\": [], " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_EMPTY_CLASS", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty(), "Should not create applier if targetOfferClass list is empty");
    }

    // --------------------------------------------------
    // Tests for calculateAverageEfficiencyScore (Applier Logic)
    // --------------------------------------------------

    /**
     * Tests {@link ImmediateVoucherDiscountFactory.ImmediateVoucherApplier#getEfficiencyScore()}
     * when there are no target items (empty price map).
     * <p>
     * Condition tested: {@code count} remains 0 (loop doesn't run).
     * Expectation: Returns 0.0.
     */
    @Test
    void testCalculateEfficiencyScore_NoItems() {
        setUpDatabase();
        // Arrange: Create applier with empty target items map
        Map<String, Basket.Item> emptyItems = new HashMap<>();

        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                new ImmediateVoucherDiscountFactory.ImmediateVoucherApplier(
                        "CODE",
                        List.of("MockOffer"),
                        emptyItems, // Empty map
                        ImmediateVoucherDiscountFactory.DiscountType.PERCENTAGE,
                        50.0,
                        store
                );

        // Act
        double score = applier.getEfficiencyScore();

        // Assert
        assertEquals(0.0, score, "Efficiency score should be 0.0 if there are no items (count is 0)");
    }

    // --------------------------------------------------
    // New Mocks for specific tests
    // --------------------------------------------------

    /**
     * Concrete mock to simulate an OfferApplier that is NOT ProductAware.
     * Used to test the guard {@code !(offerApplier instanceof ProductAwareOfferApplier)}.
     */
    public static class NonProductOfferApplier extends OfferApplier {
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) { return Collections.emptyList(); }
        @Override
        public double computeEfficiencyScore(Basket basket) { return 0.0; }
    }

    /**
     * Concrete mock to simulate a ProductAwareOfferApplier that is never applicable.
     * Used to test the case where no product in the map matches in the loop.
     */
    public static class NeverApplicableOfferApplier extends OfferApplier implements ProductAwareOfferApplier {
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) { return Collections.emptyList(); }
        @Override
        public double computeEfficiencyScore(Basket basket) { return 0.0; }
        @Override
        public boolean isApplicable(Product product) { return false; }
    }

    // --------------------------------------------------
    // New Tests for uncovered branches
    // --------------------------------------------------

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when "targetOfferClass"
     * is provided as the full canonical class name.
     * <p>
     * Condition tested: {@code matchesTarget} -> {@code fullClassName.equalsIgnoreCase(targetLower)}.
     */
    @Test
    void testBuildAppliers_WithFullClassName() {
        setUpDatabase();
        // Arrange: Offer JSON with the full class name
        // We use the canonical name of the inner class MockProductOfferApplier
        String fullClassName = "com.intermarche.valuation.engine.offers.ImmediateVoucherDiscountFactoryTest$MockProductOfferApplier";

        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"" + fullClassName + "\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_FULL_NAME", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertFalse(appliers.isEmpty());
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplier) appliers.iterator().next();

        // Verify it is applicable to a MockProductOfferApplier instance
        OfferApplier targetApplier = new MockProductOfferApplier();
        assertTrue(applier.isApplicable(targetApplier), "Should be applicable via full class name match");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory#buildAppliers} when "targetOfferClass"
     * is provided as a valid list (JSON array) of strings.
     * <p>
     * Condition tested: {@code getTargetOfferClassNames} -> loop {@code for (JsonNode item : node)}.
     */
    @Test
    void testBuildAppliers_TargetOfferClassIsList_Valid() {
        setUpDatabase();
        // Arrange: "targetOfferClass" is a valid list containing MockProductOfferApplier
        String jsonSpec = "{ " +
                "\"targetOfferClass\": [\"MockProductOfferApplier\", \"OtherClass\"], " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_LIST", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertFalse(appliers.isEmpty(), "Applier should be created for a valid list of target classes");
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                (ImmediateVoucherDiscountFactory.ImmediateVoucherApplier) appliers.iterator().next();

        // Verify the internal logic: The applier should be applicable to "MockProductOfferApplier"
        // because it is in the list parsed from the JSON.
        assertTrue(applier.isApplicable(new MockProductOfferApplier()),
                "Applier should be applicable to MockProductOfferApplier as it is in the target list");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory.ImmediateVoucherApplier#isApplicable(OfferApplier)}
     * when the target applier is NOT an instance of {@link ProductAwareOfferApplier}.
     * <p>
     * Condition tested: {@code !(offerApplier instanceof ProductAwareOfferApplier)} is {@code true}.
     * Note: We must ensure the class name matches to pass the first check ({@code matchesTarget}).
     */
    @Test
    void testIsApplicable_NotProductAware() {
        setUpDatabase();
        // Arrange
        Map<String, Basket.Item> items = new HashMap<>();
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        items.put("1234567890123", item);

        // We configure the applier to target "NonProductOfferApplier" specifically
        // to pass the 'matchesTarget' check.
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                new ImmediateVoucherDiscountFactory.ImmediateVoucherApplier(
                        "CODE",
                        List.of("NonProductOfferApplier"), // Matches the class name to pass first check
                        items,
                        ImmediateVoucherDiscountFactory.DiscountType.PERCENTAGE,
                        10.0,
                        store
                );

        // We use an OfferApplier that does NOT implement ProductAwareOfferApplier
        OfferApplier nonProductApplier = new NonProductOfferApplier();

        // Act
        boolean result = applier.isApplicable(nonProductApplier);

        // Assert
        assertFalse(result, "Applier should not be applicable if target offer is not ProductAware");
    }

    /**
     * Tests {@link ImmediateVoucherDiscountFactory.ImmediateVoucherApplier#isApplicable(OfferApplier)}
     * when the target applier is {@link ProductAwareOfferApplier} but no products in the map match.
     * <p>
     * Condition tested: Loop {@code for (Product product : productMap.values())} finishes without finding a match,
     * executing the final {@code return false}.
     * Note: We must ensure the class name matches to pass the first check ({@code matchesTarget}).
     */
    @Test
    void testIsApplicable_NoProductMatches() {
        setUpDatabase();
        // Arrange
        Map<String, Basket.Item> items = new HashMap<>();
        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        items.put("1234567890123", item);

        // We configure the applier to target "NeverApplicableOfferApplier" specifically
        // to pass the 'matchesTarget' check.
        ImmediateVoucherDiscountFactory.ImmediateVoucherApplier applier =
                new ImmediateVoucherDiscountFactory.ImmediateVoucherApplier(
                        "CODE",
                        List.of("NeverApplicableOfferApplier"), // Matches the class name to pass first check
                        items,
                        ImmediateVoucherDiscountFactory.DiscountType.PERCENTAGE,
                        10.0,
                        store
                );

        // We use an applier that implements ProductAware but always returns false
        OfferApplier neverApplicableApplier = new NeverApplicableOfferApplier();

        // Act
        boolean result = applier.isApplicable(neverApplicableApplier);

        // Assert
        assertFalse(result, "Applier should not be applicable if no product matches in the loop");
    }

    // --------------------------------------------------
    // Mocks for apply() edge cases
    // --------------------------------------------------

    /**
     * Mock for an OfferApplication that is NOT ProductAware.
     */
    public static class NonProductAwareApplication implements OfferApplication {
        @Override
        public AmountEvaluation getAmount() { return new AmountEvaluation(); }

        @Override
        public Collection<Basket.Item> getItems() { return Collections.emptyList(); }

        @Override
        public String getType() { return "NonProductAwareOffer"; }
    }

    /**
     * Mock for a ProductAwareOfferApplication where quantity is 0 or less.
     */
    public static class ZeroQuantityApplication implements ProductAwareOfferApplication {
        private final Product product;

        public ZeroQuantityApplication(Product product) {
            this.product = product;
        }

        @Override
        public AmountEvaluation getAmount() { return new AmountEvaluation(); }

        @Override
        public Collection<Basket.Item> getItems() { return Collections.emptyList(); }

        @Override
        public String getType() { return "MockProductOffer"; } // Matches target name

        @Override
        public AmountEvaluation getProductAmount(Product product) {
            // Should not be called if quantity check works, but return valid just in case
            return new AmountEvaluation(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        }

        @Override
        public double getProductQuantity(Product product) {
            if (this.product.equals(product)) return 0.0; // The condition to test
            return 0.0;
        }
    }

    /**
     * Mock for a ProductAwareOfferApplication where price is null.
     */
    public static class NullPriceApplication implements ProductAwareOfferApplication {
        private final Product product;

        public NullPriceApplication(Product product) {
            this.product = product;
        }

        @Override
        public AmountEvaluation getAmount() { return new AmountEvaluation(); }

        @Override
        public Collection<Basket.Item> getItems() { return Collections.emptyList(); }

        @Override
        public String getType() { return "MockProductOffer"; } // Matches target name

        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return null; // The condition to test
        }

        @Override
        public double getProductQuantity(Product product) {
            if (this.product.equals(product)) return 1.0; // Valid quantity
            return 0.0;
        }
    }

    // --------------------------------------------------
    // Tests for apply() branches
    // --------------------------------------------------

    /**
     * Tests that `apply` skips offers that are not ProductAwareOfferApplication.
     * <p>
     * Condition tested: `offerApp instanceof ProductAwareOfferApplication` is false.
     */
    @Test
    void testApply_OfferNotProductAware() {
        setUpDatabase();
        // Arrange: Create voucher offer targeting "MockProductOffer"
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"MockProductOffer\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_NOT_AWARE", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Add an offer that is NOT ProductAware
        evaluation.getOffers().add(new NonProductAwareApplication());

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertFalse(appliers.isEmpty());
        Collection<AdvantageApplication> discounts = appliers.iterator().next().apply(evaluation);

        // Assert: No discount generated for non-product aware offer
        assertTrue(discounts.isEmpty(), "Should return empty list if offer is not ProductAware");
    }

    /**
     * Tests that `apply` skips products when quantity in offer is <= 0.
     * <p>
     * Condition tested: `productQuantityInOffer <= 0` is true.
     */
    @Test
    void testApply_QuantityZero() {
        setUpDatabase();
        // Arrange
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"ZeroQuantity\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_QTY_ZERO", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Add an offer where the product quantity is 0
        evaluation.getOffers().add(new ZeroQuantityApplication(product));

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        Collection<AdvantageApplication> discounts = appliers.iterator().next().apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty(), "Should return empty list if product quantity is 0 in the offer");
    }

    /**
     * Tests that `apply` skips products when the price evaluation is null.
     * <p>
     * Condition tested: `basePriceForProduct == null` is true.
     */
    @Test
    void testApply_PriceNull() {
        setUpDatabase();
        // Arrange
        String jsonSpec = "{ " +
                "\"targetOfferClass\": \"NullPrice\", " +
                "\"targetEans\": [\"1234567890123\"], " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"value\": 10 " +
                "}";
        DomainUtils.createAndPersistOffer("VOUCHER_PRICE_NULL", store, "IMMEDIATE_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1234567890123", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Add an offer where the product price is null
        evaluation.getOffers().add(new NullPriceApplication(product));

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        Collection<AdvantageApplication> discounts = appliers.iterator().next().apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty(), "Should return empty list if product price is null in the offer");
    }
}