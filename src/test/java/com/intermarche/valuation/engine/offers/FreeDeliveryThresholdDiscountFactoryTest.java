package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link FreeDeliveryThresholdDiscountFactory} using the real database.
 * <p>
 * This test class verifies the logic for creating discount appliers and applying tiered discounts
 * based on the merchandise total.
 * <p>
 * Updated to reflect that {@code processSpecification} now validates the JSON
 * against a JSON Schema and throws {@link IllegalArgumentException} on non-conformity.
 */
@QuarkusTest
@TestTransaction
public class FreeDeliveryThresholdDiscountFactoryTest {

    @Inject
    FreeDeliveryThresholdDiscountFactory factory;

    private Store store;

    /**
     * Sets up the database with a {@link Store} entity required for the tests.
     * <p>
     * This method is not annotated with {@code @BeforeEach} and must be called manually
     * at the beginning of each test method.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
    }

    // --------------------------------------------------
    // Tests for buildAppliers (Factory Logic)
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier with a valid JSON specification.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        // Arrange: Offer with valid tiers
        String jsonSpec = "{ \"tiers\": [ " +
                "{ \"threshold\": 50.00, \"value\": 5.00, \"type\": \"FIXED_AMOUNT\" }, " +
                "{ \"threshold\": 100.00, \"value\": 50, \"type\": \"PERCENTAGE\" } " +
                "] }";
        DomainUtils.createAndPersistOffer("FREE_DEL_01", store, "FREE_DELIVERY_THRESHOLD", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier);
    }

    /**
     * Tests the scenario where no offers are found in the database.
     */
    @Test
    void testBuildAppliers_NoOffers() {
        setUpDatabase();
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests the scenario where the offer specification is missing the "tiers" key.
     * <p>
     * Expects an {@link IllegalArgumentException} to be thrown due to schema validation failure.
     */
    @Test
    void testBuildAppliers_MissingTiersKey() {
        setUpDatabase();
        String jsonSpec = "{ \"discount\": 10.00 }";
        DomainUtils.createAndPersistOffer("BAD_OFFER", store, "FREE_DELIVERY_THRESHOLD", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if tiers key is missing (Schema validation)");
    }

    /**
     * Tests the scenario where the tier list in the specification is empty.
     * <p>
     * Schema validation requires "minItems": 1.
     * Expects an {@link IllegalArgumentException} to be thrown.
     */
    @Test
    void testBuildAppliers_EmptyTiers() {
        setUpDatabase();
        String jsonSpec = "{ \"tiers\": [] }";
        DomainUtils.createAndPersistOffer("EMPTY_OFFER", store, "FREE_DELIVERY_THRESHOLD", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if tiers list is empty (Schema validation requires minItems 1)");
    }

    // --------------------------------------------------
    // Tests for FreeDeliveryThresholdApplier (Discount Logic)
    // --------------------------------------------------

    /**
     * Tests the application of a percentage discount when the threshold is met.
     */
    @Test
    void testApply_PercentageDiscount_Success() {
        setUpDatabase();
        // Arrange: Tier 100€ -> 50% off
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("100.00"),
                        50.0,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.PERCENTAGE
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE1", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add Offers: 100€ product (meets threshold) + 10€ delivery
        Product p = new Product();
        evaluation.getOffers().add(new DeliveryApplication(10.00));
        evaluation.getOffers().add(new ProductApplication(100.00, p));

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertEquals(1, discounts.size());
        AdvantageApplication discount = discounts.iterator().next();
        assertTrue(discount instanceof FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication);
        AmountEvaluation amount = ((FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication) discount).getDiscountAmount();
        // 50% of 10€ = 5€
        assertEquals(new BigDecimal("5.00"), amount.amountIncludingTax);
        // 5€ TTC / 1.2 = 4.166... -> 4.17
        assertEquals(new BigDecimal("4.17"), amount.amountExcludingTax);
    }

    /**
     * Tests the application of a fixed amount discount when the threshold is met.
     */
    @Test
    void testApply_FixedAmountDiscount_Success() {
        setUpDatabase();
        // Arrange: Tier 50€ -> 5€ fixed off
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("50.00"),
                        5.00,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.FIXED_AMOUNT
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE2", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add Offers: 60€ product (meets threshold) + 10€ delivery
        Product p = new Product();
        evaluation.getOffers().add(new DeliveryApplication(10.00));
        evaluation.getOffers().add(new ProductApplication(60.00, p));

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication) discounts.iterator().next()).getDiscountAmount();
        // 5€ fixed
        assertEquals(new BigDecimal("5.00"), amount.amountIncludingTax);
    }

    /**
     * Tests that no discount is applied if the merchandise threshold is not met.
     */
    @Test
    void testApply_ThresholdNotMet() {
        setUpDatabase();
        // Arrange: Tier 200€
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("200.00"),
                        100.0,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.PERCENTAGE
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE3", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add Offers: 50€ product (does NOT meet threshold) + 10€ delivery
        Product p = new Product();
        evaluation.getOffers().add(new DeliveryApplication(10.00));
        evaluation.getOffers().add(new ProductApplication(50.00, p));

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty());
    }

    /**
     * Tests that the discount amount is capped at the total delivery cost.
     */
    @Test
    void testApply_CapAtDeliveryCost() {
        setUpDatabase();
        // Arrange: Tier 10€ -> 20€ fixed discount (more than delivery)
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("10.00"),
                        20.00,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.FIXED_AMOUNT
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE4", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add Offers: 20€ product + 10€ delivery
        Product p = new Product();
        evaluation.getOffers().add(new DeliveryApplication(10.00));
        evaluation.getOffers().add(new ProductApplication(20.00, p));

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication) discounts.iterator().next()).getDiscountAmount();
        // Should cap at 10.00
        assertEquals(new BigDecimal("10.00"), amount.amountIncludingTax);
    }

    /**
     * Tests that service offers (non-ProductAware) are excluded from the merchandise total.
     */
    @Test
    void testApply_ExcludeServicesFromThreshold() {
        setUpDatabase();
        // Arrange: Tier 100€
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("100.00"),
                        50.0,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.PERCENTAGE
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE5", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add Offers: 50€ product + 200€ service + 10€ delivery
        Product p = new Product();
        evaluation.getOffers().add(new DeliveryApplication(10.00));
        evaluation.getOffers().add(new ProductApplication(50.00, p)); // Only this counts
        // Mock a service offer (Standard OfferApplication, not ProductAware)
        evaluation.getOffers().add(new OfferApplication() {
            private final AmountEvaluation amt = new AmountEvaluation(
                    BigDecimal.valueOf(166.66), BigDecimal.valueOf(200.00), new BigDecimal("0.20")
            );
            @Override public AmountEvaluation getAmount() { return amt; }
            @Override public Collection<Basket.Item> getItems() { return List.of(); }
            @Override public String getType() { return "Service"; }
        });

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        // Total merchandise = 50€. Threshold is 100€. No discount.
        assertTrue(discounts.isEmpty());
    }

    /**
     * Tests that no discount is applied if there is no delivery offer in the basket.
     */
    @Test
    void testApply_NoDeliveryOffer() {
        setUpDatabase();
        // Arrange
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("50.00"),
                        5.00,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.FIXED_AMOUNT
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE6", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Add Offers: 100€ product, NO delivery
        Product p = new Product();
        evaluation.getOffers().add(new ProductApplication(100.00, p));

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty());
    }

    // --------------------------------------------------
    // Tests for Getters and Properties
    // --------------------------------------------------

    /**
     * Tests the retrieval of the efficiency score.
     */
    @Test
    void testGetEfficiencyScore() {
        setUpDatabase();
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of();
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE7", tiers);

        assertEquals(-1.0, applier.getEfficiencyScore());
    }

    /**
     * Tests {@link FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier#isApplicable(OfferApplier)} with a valid target.
     * <p>
     * Uses a mock delivery applier to verify type checking.
     */
    @Test
    void testIsApplicable_True() {
        // Arrange
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier discountApplier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE", new ArrayList<>());
        // We create the target applier with null values to avoid heavy setup
        OfferApplier deliveryApplier = new DeliveryOfferFactory.DeliveryOfferApplier(
                "DEL_CODE", null, null, BigDecimal.ZERO, Collections.emptyList()
        );

        // Act
        boolean result = discountApplier.isApplicable(deliveryApplier);

        // Assert
        assertTrue(result);
    }

    /**
     * Tests {@link FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier#isApplicable(OfferApplier)} with an invalid target.
     */
    @Test
    void testIsApplicable_False() {
        // Arrange
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier discountApplier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE", new ArrayList<>());
        OfferApplier otherApplier = new OfferApplier() {
            @Override public Collection<OfferApplication> apply(BasketEvaluation e) { return Collections.emptyList(); }
            @Override public double computeEfficiencyScore(Basket b) { return 0.0; }
        };

        // Act
        boolean result = discountApplier.isApplicable(otherApplier);

        // Assert
        assertFalse(result);
    }

    /**
     * Tests the string representation of the discount application type.
     */
    @Test
    void testGetType() {
        // Arrange
        String code = "PROMO_123";
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication app =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication(
                        code,
                        null,
                        null // Amount is not used in getType()
                );

        // Act
        String result = app.getType();

        // Assert
        assertEquals("Free Delivery Threshold Discount: " + code, result);
    }

    /**
     * Tests the retrieval of the associated offer application.
     */
    @Test
    void testGetOfferApplication() {
        // Arrange
        DeliveryApplication expectedOffer = new DeliveryApplication(10.00);
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication app =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplication(
                        "CODE",
                        expectedOffer,
                        null // Discount amount is not used here
                );

        // Act
        OfferApplication result = app.getOfferApplication();

        // Assert
        assertSame(expectedOffer, result);
    }

    // --------------------------------------------------
    // Edge Cases and Robustness Tests (Schema Validation)
    // --------------------------------------------------

    /**
     * Tests processing an offer where "tiers" is present but not a list (e.g., a String).
     * <p>
     * Expects an {@link IllegalArgumentException} to be thrown by schema validation.
     */
    @Test
    void testProcessOffer_TiersNotAList() {
        setUpDatabase();
        // Arrange: "tiers" is a string, not a list
        String jsonSpec = "{ \"tiers\": \"ceci_est_une_string\" }";
        DomainUtils.createAndPersistOffer("OFFER_BAD_TYPE", store, "FREE_DELIVERY_THRESHOLD", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if 'tiers' is not a list (Schema validation)");
    }

    /**
     * Tests processing an offer where "tiers" is a list but contains invalid items (not a Map).
     * <p>
     * Expects an {@link IllegalArgumentException} to be thrown by schema validation.
     */
    @Test
    void testProcessOffer_TierNotAMap() {
        setUpDatabase();
        // Arrange: "tiers" is a list, but content is not a JSON Map
        String jsonSpec = "{ \"tiers\": [ \"ceci_nest_pas_une_map\" ] }";
        DomainUtils.createAndPersistOffer("OFFER_BAD_CONTENT", store, "FREE_DELIVERY_THRESHOLD", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if tier items are not objects (Schema validation)");
    }

    // --------------------------------------------------
    // Edge Cases and Robustness Tests (Application Logic)
    // --------------------------------------------------

    /**
     * Tests the scenario where the delivery price is zero.
     * <p>
     * In this case, the discount application object should be null, resulting in no discount.
     */
    @Test
    void testApply_NoDiscountWhenDeliveryPriceIsZero() {
        setUpDatabase();
        // Arrange: Threshold 10€, 100€ products
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("10.00"),
                        50.0,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.PERCENTAGE
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // 1. Add products (to trigger threshold)
        Product p = new Product();
        evaluation.getOffers().add(new ProductApplication(100.00, p));
        // 2. Add delivery with 0.00 price (triggers null in getDeliveryDiscountApplication)
        evaluation.getOffers().add(new DeliveryApplication(0.00));

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty(), "No discount should be created if delivery price is null");
    }

    /**
     * Tests the scenario where the list of offers in the evaluation is null.
     * <p>
     * Uses reflection to simulate this edge case.
     */
    @Test
    void testApply_OffersListIsNull() throws Exception {
        setUpDatabase();
        // Arrange
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE", new ArrayList<>());

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Force 'offers' field to null via reflection
        java.lang.reflect.Field field = BasketEvaluation.class.getDeclaredField("offers");
        field.setAccessible(true);
        field.set(evaluation, null);

        // Act (Should not throw NullPointerException)
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        assertTrue(discounts.isEmpty());
    }

    /**
     * Tests the scenario where a product's amount is null.
     * <p>
     * Uses reflection to set the amount to null, verifying the null check in `getMerchandiseTotal`.
     */
    @Test
    void testApply_NoDiscountWhenProductAmountIsNull() throws Exception {
        // Arrange
        List<FreeDeliveryThresholdDiscountFactory.DiscountTier> tiers = List.of(
                new FreeDeliveryThresholdDiscountFactory.DiscountTier(
                        new BigDecimal("50.00"),
                        10.0,
                        FreeDeliveryThresholdDiscountFactory.DiscountType.PERCENTAGE
                )
        );
        FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier applier =
                new FreeDeliveryThresholdDiscountFactory.FreeDeliveryThresholdApplier("CODE", tiers);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Create a product application
        Product p = new Product();
        ProductApplication prodApp = new ProductApplication(100.00, p);

        // Use reflection to force the amount to null
        java.lang.reflect.Field amountField = ProductApplication.class.getDeclaredField("amount");
        amountField.setAccessible(true);
        amountField.set(prodApp, null);
        evaluation.getOffers().add(prodApp);

        // Act
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        // Assert
        // Since amount is null, merchandise total is 0. Threshold 50€ not met.
        assertTrue(discounts.isEmpty(), "No discount if product amount is null");
    }

    // --------------------------------------------------
    // Inner Helper Classes (Mocks)
    // --------------------------------------------------

    /**
     * Mock implementation of {@link OfferApplication} representing a Delivery offer.
     * <p>
     * Used to simplify test setup by providing a dummy delivery offer with a fixed price.
     */
    public static class DeliveryApplication implements OfferApplication {
        final AmountEvaluation amount;

        /**
         * Constructs a delivery application with a specific TTC price.
         * Assumes a 20% VAT rate for simplicity in tests.
         *
         * @param ttcPrice The price including tax.
         */
        public DeliveryApplication(double ttcPrice) {
            BigDecimal ttc = BigDecimal.valueOf(ttcPrice);
            BigDecimal ht = ttc.divide(BigDecimal.valueOf(1.20), 2, RoundingMode.HALF_UP);
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.20"));
        }

        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        @Override
        public String getType() {
            return "Delivery";
        }
    }

    /**
     * Mock implementation of {@link ProductAwareOfferApplication} representing a Product offer.
     * <p>
     * Used to simulate products added to the basket that count towards the merchandise threshold.
     */
    public static class ProductApplication implements ProductAwareOfferApplication {
        final AmountEvaluation amount;
        final Product product;

        /**
         * Constructs a product application with a specific TTC price.
         * Assumes a 20% VAT rate.
         *
         * @param ttcPrice The price including tax.
         * @param product  The product entity.
         */
        public ProductApplication(double ttcPrice, Product product) {
            BigDecimal ttc = BigDecimal.valueOf(ttcPrice);
            BigDecimal ht = ttc.divide(BigDecimal.valueOf(1.20), 2, RoundingMode.HALF_UP);
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.20"));
            this.product = product;
        }

        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        @Override
        public String getType() {
            return "Product";
        }

        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return this.amount;
        }

        @Override
        public double getProductQuantity(Product product) {
            return 1.0;
        }
    }
}