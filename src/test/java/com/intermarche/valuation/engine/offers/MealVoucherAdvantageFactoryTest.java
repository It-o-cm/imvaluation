package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MealVoucherAdvantageFactory}.
 * <p>
 * Covers factory creation, JSON parsing, family flag resolution,
 * and defensive null checks in the apply loop.
 * Note: The 'payableAmount' field is private with no getter, so it is not tested directly.
 */
@QuarkusTest
@TestTransaction
public class MealVoucherAdvantageFactoryTest {

    @Inject
    MealVoucherAdvantageFactory factory;

    private Store store;
    private ProductFamily foodFamily;
    private Product foodProduct;

    /**
     * Sets up the database with a Store, Product Families, and Products.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.0, 2.0);

        foodFamily = new ProductFamily();
        foodFamily.code = "FAM_FOOD";
        foodFamily.flags = "FOOD";
        foodFamily.products = new HashSet<>();
        foodFamily.persist();

        foodProduct = DomainUtils.createAndPersistProduct("1111111111111", "Apple", ProductType.UNIT);

        foodFamily.products.add(foodProduct);
        foodFamily.persist();
    }

    // --------------------------------------------------
    // Factory Logic Tests
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier with a valid JSON specification.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        String jsonSpec = "{ \"flag\": \"FOOD\", \"threshold\": 50.00 }";
        DomainUtils.createAndPersistOffer("MV_01", store, "MEAL_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof MealVoucherAdvantageFactory.MealVoucherAdvantageApplier);
    }

    /**
     * Tests the scenario where no meal voucher offers exist for the store.
     */
    @Test
    void testBuildAppliers_NoOffers() {
        setUpDatabase();
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests the scenario where the JSON specification is missing the 'flag' field.
     * <p>
     * Previously, the factory caught the error and returned an empty list.
     * Now, with schema validation, it throws an exception for invalid configuration.
     */
    @Test
    void testBuildAppliers_MissingFlagField() {
        setUpDatabase();
        // Missing 'flag' which is required by the schema
        String jsonSpec = "{ \"threshold\": 50.00 }";
        DomainUtils.createAndPersistOffer("MV_BAD", store, "MEAL_VOUCHER", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // The factory now propagates the validation exception
        assertThrows(Exception.class, () -> factory.buildAppliers(evaluation));
    }

    // --------------------------------------------------
    // Applier Logic Tests
    // --------------------------------------------------

    /**
     * Tests that eligible products are correctly summed up.
     * <p>
     * Note: We verify 'totalEligibleAmount'. The capped 'payableAmount' is internal and untestable without reflection.
     */
    @Test
    void testApply_EligibleProduct_CalculatesTotal() {
        setUpDatabase();
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", new BigDecimal("10.00"));

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Eligible product (Apple), Price 20€
        evaluation.getOffers().add(new MockProductApplication(foodProduct, 20.00));

        Collection<AdvantageApplication> results = applier.apply(evaluation);
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                (MealVoucherAdvantageFactory.MealVoucherAdvantageApplication) results.iterator().next();

        // We can verify the total calculated
        assertEquals(new BigDecimal("20.00"), app.getTotalEligibleAmount());
        // We verify the threshold is correct
        assertEquals(new BigDecimal("10.00"), app.getThreshold());
    }

    /**
     * Tests that multiple eligible products are summed correctly.
     */
    @Test
    void testApply_MultipleEligibleProducts_SumsTotal() {
        setUpDatabase();
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", new BigDecimal("100.00"));

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Add two eligible items (20€ + 30€)
        evaluation.getOffers().add(new MockProductApplication(foodProduct, 20.00));
        evaluation.getOffers().add(new MockProductApplication(foodProduct, 30.00));

        Collection<AdvantageApplication> results = applier.apply(evaluation);
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                (MealVoucherAdvantageFactory.MealVoucherAdvantageApplication) results.iterator().next();

        assertEquals(new BigDecimal("50.00"), app.getTotalEligibleAmount());
    }

    /**
     * Tests that products without the required family flag are ignored.
     */
    @Test
    void testApply_NonEligibleProduct() {
        setUpDatabase();
        Product unknownProduct = DomainUtils.createAndPersistProduct("9999999999999", "Mystery Item", ProductType.UNIT);

        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", new BigDecimal("50.00"));

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        evaluation.getOffers().add(new MockProductApplication(unknownProduct, 100.00));

        Collection<AdvantageApplication> results = applier.apply(evaluation);
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                (MealVoucherAdvantageFactory.MealVoucherAdvantageApplication) results.iterator().next();

        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), app.getTotalEligibleAmount());
    }

    /**
     * Tests that non-product-aware offers are skipped.
     */
    @Test
    void testApply_NonProductAwareOffer_Ignored() {
        setUpDatabase();
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", new BigDecimal("50.00"));

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        evaluation.getOffers().add(new MockGenericOfferApplication(100.00));

        Collection<AdvantageApplication> results = applier.apply(evaluation);
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                (MealVoucherAdvantageFactory.MealVoucherAdvantageApplication) results.iterator().next();

        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), app.getTotalEligibleAmount());
    }

    /**
     * Tests the defensive check when a product EAN is not found in the database.
     */
    @Test
    void testApply_ProductNotFoundInDb() {
        setUpDatabase();
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", new BigDecimal("50.00"));

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        evaluation.getOffers().add(new MockProductApplicationWithEan("0000000000000", 50.00));

        Collection<AdvantageApplication> results = applier.apply(evaluation);
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                (MealVoucherAdvantageFactory.MealVoucherAdvantageApplication) results.iterator().next();

        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), app.getTotalEligibleAmount());
    }

    /**
     * Tests the defensive check when the offer application returns a null amount.
     */
    @Test
    void testApply_ProductAmountIsNull() {
        setUpDatabase();
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", new BigDecimal("50.00"));

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        evaluation.getOffers().add(new MockProductApplicationReturningNullAmount(foodProduct));

        Collection<AdvantageApplication> results = applier.apply(evaluation);
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                (MealVoucherAdvantageFactory.MealVoucherAdvantageApplication) results.iterator().next();

        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), app.getTotalEligibleAmount());
    }

    // --------------------------------------------------
    // Trivial Method Tests
    // --------------------------------------------------

    /**
     * Tests that isApplicable always returns false.
     */
    @Test
    void testApplier_IsApplicable_ReturnsFalse() {
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", BigDecimal.TEN);

        assertFalse(applier.isApplicable(null));
    }

    /**
     * Tests that the efficiency score is fixed at -2.0.
     */
    @Test
    void testApplier_GetEfficiencyScore() {
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplier applier =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplier("CODE", "FOOD", BigDecimal.TEN);

        assertEquals(-2.0, applier.getEfficiencyScore());
    }

    // --------------------------------------------------
    // Application Class Getters Tests
    // --------------------------------------------------

    /**
     * Tests the accessible getters of the Application result object.
     */
    @Test
    void testApplication_Getters() {
        MealVoucherAdvantageFactory.MealVoucherAdvantageApplication app =
                new MealVoucherAdvantageFactory.MealVoucherAdvantageApplication(
                        "CODE1", new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("60")
                );

        assertEquals("CODE1", app.getOfferCode());
        assertEquals("MEAL_VOUCHER", app.getType());
        assertEquals("MEAL_VOUCHER", app.getOffer());
        assertNull(app.getOfferApplication());
        assertEquals(new BigDecimal("100"), app.getTotalEligibleAmount());
        assertEquals(new BigDecimal("60"), app.getThreshold());
        // Note: getPayableAmount() does not exist, cannot test the value 50.
    }

    // --------------------------------------------------
    // Mock Helpers
    // --------------------------------------------------

    /**
     * Mock implementation of ProductAwareOfferApplication.
     */
    private static class MockProductApplication implements ProductAwareOfferApplication {
        private final Product product;
        private final AmountEvaluation amount;
        private final Basket.Item item;

        MockProductApplication(Product product, double priceTTC) {
            this.product = product;
            BigDecimal ttc = BigDecimal.valueOf(priceTTC);
            BigDecimal ht = ttc.divide(BigDecimal.valueOf(1.10), 2, RoundingMode.HALF_UP);
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.10"));
            this.item = new Basket.Item();
            this.item.produceEan = product.ean;
            this.item.quantity = 1.0;
        }

        @Override public AmountEvaluation getAmount() { return amount; }
        @Override public Collection<Basket.Item> getItems() { return List.of(item); }
        @Override public String getType() { return "Mock"; }
        @Override public AmountEvaluation getProductAmount(Product p) {
            if (p != null && p.ean.equals(this.product.ean)) return amount;
            return null;
        }
        @Override public double getProductQuantity(Product p) { return 1.0; }
    }

    /**
     * Mock implementation simulating a missing product EAN.
     */
    private static class MockProductApplicationWithEan implements ProductAwareOfferApplication {
        private final String ean;
        private final AmountEvaluation amount;
        private final Basket.Item item;

        MockProductApplicationWithEan(String ean, double priceTTC) {
            this.ean = ean;
            BigDecimal ttc = BigDecimal.valueOf(priceTTC);
            this.amount = new AmountEvaluation(BigDecimal.ZERO, ttc, BigDecimal.ZERO);
            this.item = new Basket.Item();
            this.item.produceEan = ean;
        }

        @Override public AmountEvaluation getAmount() { return amount; }
        @Override public Collection<Basket.Item> getItems() { return List.of(item); }
        @Override public String getType() { return "MockMissing"; }
        @Override public AmountEvaluation getProductAmount(Product p) { return amount; }
        @Override public double getProductQuantity(Product p) { return 1.0; }
    }

    /**
     * Mock implementation forcing a null return for getProductAmount.
     */
    private static class MockProductApplicationReturningNullAmount implements ProductAwareOfferApplication {
        private final Product product;
        private final Basket.Item item;

        MockProductApplicationReturningNullAmount(Product product) {
            this.product = product;
            this.item = new Basket.Item();
            this.item.produceEan = product.ean;
        }

        @Override public AmountEvaluation getAmount() { return new AmountEvaluation(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE); }
        @Override public Collection<Basket.Item> getItems() { return List.of(item); }
        @Override public String getType() { return "MockNullAmount"; }
        @Override public AmountEvaluation getProductAmount(Product p) { return null; }
        @Override public double getProductQuantity(Product p) { return 1.0; }
    }

    /**
     * Mock implementation for generic non-product-aware offers.
     */
    private static class MockGenericOfferApplication implements OfferApplication {
        private final AmountEvaluation amount;
        MockGenericOfferApplication(double priceTTC) {
            this.amount = new AmountEvaluation(BigDecimal.ZERO, BigDecimal.valueOf(priceTTC), BigDecimal.ZERO);
        }
        @Override public AmountEvaluation getAmount() { return amount; }
        @Override public Collection<Basket.Item> getItems() { return List.of(); }
        @Override public String getType() { return "Generic"; }
    }
}