package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import com.intermarche.valuation.engine.ValuationEngine;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AmountPerItemDiscountFactory} using the real database.
 * <p>
 * Factory-level tests exercise the schema and the duplicate-EAN cross rule; applier-level tests
 * assert the per-unit amount and its cap at the line base; end-to-end tests cover the trigger
 * interaction and the inherited C2 arbitration.
 */
@QuarkusTest
@TestTransaction
public class AmountPerItemDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    AmountPerItemDiscountFactory factory;

    /**
     * The engine, injected for the end-to-end scenarios.
     */
    @Inject
    ValuationEngine engine;

    /**
     * The store the offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by every test.
     */
    private void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_API", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product at a zero VAT rate with a DEFAULT and a BASE_FOR_DISCOUNT price.
     *
     * @param ean   the product EAN.
     * @param price the price, tax excluded and included alike.
     */
    private void seedProduct(String ean, String price) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        BigDecimal amount = new BigDecimal(price);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, amount, amount, BigDecimal.ZERO);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT, amount, amount, BigDecimal.ZERO);
    }

    /**
     * Builds an evaluation on an empty basket attached to the seeded store.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_API";
        return new BasketEvaluation(basket);
    }

    /**
     * Builds a basket on the seeded store from the given lines.
     *
     * @param items the basket lines.
     * @return the basket.
     */
    private Basket basket(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_API";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds a single-target applier with no basket (sandbox score zero).
     *
     * @param ean           the targeted EAN.
     * @param amountPerItem the amount off each unit.
     * @return the applier under test.
     */
    private AmountPerItemDiscountFactory.AmountPerItemDiscountApplier applier(String ean, String amountPerItem) {
        Product product = new Product();
        product.ean = ean;
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put(ean, new BigDecimal(amountPerItem));
        return new AmountPerItemDiscountFactory.AmountPerItemDiscountApplier(
                "API1", map, List.of(product), null, null);
    }

    /**
     * Sums the tax-included amount of every discount advantage.
     *
     * @param evaluation the evaluation.
     * @return the total discount, tax included.
     */
    private BigDecimal totalDiscount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication advantage : evaluation.getAdvantages()) {
            if (advantage instanceof DiscountApplication discount && discount.getDiscountAmount() != null) {
                total = total.add(discount.getDiscountAmount().amountIncludingTax);
            }
        }
        return total;
    }

    // --------------------------------------------------
    // Factory logic
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier from a valid specification.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("API_OK", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"E1\"], \"amountPerItem\": 0.50 } ] }");
        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof AmountPerItemDiscountFactory.AmountPerItemDiscountApplier);
    }

    /**
     * Tests that an EAN present in two targets is rejected at creation.
     */
    @Test
    void testBuildAppliers_DuplicateEan_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("API_DUP", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"E1\"], \"amountPerItem\": 0.50 }, "
                        + "{ \"eans\": [\"E1\"], \"amountPerItem\": 1.00 } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a non-positive per-item amount is rejected by the schema.
     */
    @Test
    void testBuildAppliers_NonPositiveAmount_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("API_ZERO", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"E1\"], \"amountPerItem\": 0 } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that missing targets are rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingTargets_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("API_NT", store, "AMOUNT_PER_ITEM_DISCOUNT", "{ }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that no offer of the type yields no applier.
     */
    @Test
    void testBuildAppliers_NoOffer_Empty() {
        setUpDatabase();
        assertTrue(factory.buildAppliers(newEvaluation()).isEmpty());
    }

    /**
     * Tests that a null basket is rejected.
     */
    @Test
    void testBuildAppliers_NoBasket_Throws() {
        setUpDatabase();
        BasketEvaluation eval = new BasketEvaluation(null) {
        };
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(eval));
    }

    // --------------------------------------------------
    // Applier logic
    // --------------------------------------------------

    /**
     * Tests the nominal case: 0.50 off each of two units is a 1.00 discount.
     */
    @Test
    void testApply_Nominal() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = applier("E1", "0.50").apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((AmountPerItemDiscountFactory.AmountPerItemDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("1.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.83"), amount.amountExcludingTax);
    }

    /**
     * Tests the cap at the line base: 5.00 per unit over two units (10.00) is capped at the
     * line's 6.00 base.
     */
    @Test
    void testApply_CappedAtLineBase() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        assertEquals(new BigDecimal("6.00"), ((AmountPerItemDiscountFactory.AmountPerItemDiscountApplication)
                applier("E1", "5.00").apply(evaluation).iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests a two-target multi-list applier.
     */
    @Test
    void testApply_MultiTarget() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("A", new BigDecimal("0.50"));
        map.put("B", new BigDecimal("1.00"));
        AmountPerItemDiscountFactory.AmountPerItemDiscountApplier applier =
                new AmountPerItemDiscountFactory.AmountPerItemDiscountApplier("APM", map, List.of(a, b), null, null);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        evaluation.getOffers().add(new ProductStub(b, 1.0,
                new AmountEvaluation(new BigDecimal("2.50"), new BigDecimal("3.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(2, discounts.size());
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication d : discounts) {
            total = total.add(((AmountPerItemDiscountFactory.AmountPerItemDiscountApplication) d)
                    .getDiscountAmount().amountIncludingTax);
        }
        // A: 0.50 * 2 = 1.00 ; B: 1.00 * 1 = 1.00.
        assertEquals(new BigDecimal("2.00"), total);
    }

    /**
     * Tests that an empty assiette produces nothing.
     */
    @Test
    void testApply_EmptyAssiette_NoApplication() {
        setUpDatabase();
        assertTrue(applier("E1", "0.50").apply(newEvaluation()).isEmpty());
    }

    /**
     * Tests that a zero-quantity covered product is skipped.
     */
    @Test
    void testApply_ZeroQuantity_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 0.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        assertTrue(applier("E1", "0.50").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a covered product with a null attributed amount is skipped.
     */
    @Test
    void testApply_NullProductAmount_Skipped() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0, null));
        assertTrue(applier("E1", "0.50").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a consumed offer application leaves the assiette.
     */
    @Test
    void testApply_ConsumedOffer_Excluded() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        evaluation.markConsumed(List.of(stub));
        assertTrue(applier("E1", "0.50").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a non-product-aware application is ignored.
     */
    @Test
    void testApply_NonProductAware_Ignored() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ServiceStub(new BigDecimal("10.00")));
        assertTrue(applier("E1", "0.50").apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applicability, score
    // --------------------------------------------------

    /**
     * Tests applicability to a covering applier, a non-covering one, and a non-product-aware
     * one.
     */
    @Test
    void testIsApplicable() {
        setUpDatabase();
        assertTrue(applier("E1", "0.50").isApplicable(new FakeProductApplier("E1")));
        assertFalse(applier("E1", "0.50").isApplicable(new FakeProductApplier("E9")));
        assertFalse(applier("E1", "0.50").isApplicable(new NonProductApplier()));
    }

    /**
     * Tests the sandbox score: the summed capped discount over the basket at the reference
     * price.
     */
    @Test
    void testEfficiencyScore_SandboxFromBasket() {
        setUpDatabase();
        seedProduct("API_S", "3.00");
        Product product = Product.findByEan("API_S");
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("API_S", new BigDecimal("0.50"));
        Basket b = basket(DomainUtils.createItem("API_S", 2.0));
        AmountPerItemDiscountFactory.AmountPerItemDiscountApplier applier =
                new AmountPerItemDiscountFactory.AmountPerItemDiscountApplier("APS", map, List.of(product), b, store);
        // 0.50 * 2 = 1.00 (below the 6.00 line base).
        assertEquals(0, new BigDecimal("1.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests that the sandbox score is zero without a basket or store.
     */
    @Test
    void testEfficiencyScore_NoBasketOrStore_Zero() {
        setUpDatabase();
        assertEquals(0.0, applier("E1", "0.50").getEfficiencyScore());
    }

    /**
     * Tests the application getters: type, EAN, and application-moment round-trip.
     */
    @Test
    void testApplication_Getters() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        AmountPerItemDiscountFactory.AmountPerItemDiscountApplication app =
                (AmountPerItemDiscountFactory.AmountPerItemDiscountApplication)
                        applier("E1", "0.50").apply(evaluation).iterator().next();
        assertEquals("Amount Per Item: API1", app.getType());
        assertEquals("E1", app.getEan());
        assertEquals("AT_TOTAL", app.getApplicationMoment());
        app.setApplicationMoment("AT_TRIGGER");
        assertEquals("AT_TRIGGER", app.getApplicationMoment());
    }

    /**
     * Tests that the produced application exposes its targeted offer application.
     */
    @Test
    void testApplication_OfferApplicationTarget() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        AmountPerItemDiscountFactory.AmountPerItemDiscountApplication app =
                (AmountPerItemDiscountFactory.AmountPerItemDiscountApplication)
                        applier("E1", "0.50").apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * Tests the sandbox skip branches: a non-target line and a target line without a price both
     * contribute nothing to the score.
     */
    @Test
    void testEfficiencyScore_SandboxSkipBranches() {
        setUpDatabase();
        seedProduct("API_OTHER", "3.00");
        DomainUtils.createAndPersistProduct("API_NOPRICE", "API_NOPRICE", ProductType.UNIT);
        Product noPrice = Product.findByEan("API_NOPRICE");
        Map<String, BigDecimal> map = new java.util.LinkedHashMap<>();
        map.put("API_NOPRICE", new BigDecimal("0.50"));
        Basket b = basket(
                DomainUtils.createItem("API_OTHER", 1.0),
                DomainUtils.createItem("API_NOPRICE", 1.0));
        AmountPerItemDiscountFactory.AmountPerItemDiscountApplier applier =
                new AmountPerItemDiscountFactory.AmountPerItemDiscountApplier("APX", map, List.of(noPrice), b, store);
        assertEquals(0.0, applier.getEfficiencyScore());
    }

    // --------------------------------------------------
    // End-to-end (trigger and arbitration)
    // --------------------------------------------------

    /**
     * Tests the end-to-end nominal case with a satisfied per-list trigger.
     */
    @Test
    void testEndToEnd_TriggerSatisfied() {
        setUpDatabase();
        seedProduct("API_G", "3.00");
        DomainUtils.createAndPersistOffer("API_E2E", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"API_G\"], \"amountPerItem\": 0.50 } ], "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"API_G\"], \"threshold\": 2 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("API_G", 2.0)));
        // 0.50 * 2 = 1.00.
        assertEquals(0, new BigDecimal("1.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the end-to-end case with an unsatisfied trigger: nothing applies.
     */
    @Test
    void testEndToEnd_TriggerNotSatisfied() {
        setUpDatabase();
        seedProduct("API_G", "3.00");
        DomainUtils.createAndPersistOffer("API_E2E", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"API_G\"], \"amountPerItem\": 0.50 } ], "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"API_G\"], \"threshold\": 5 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("API_G", 2.0)));
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited open-basket dimension: an AT_TOTAL advantage does not fall on an
     * open basket.
     */
    @Test
    void testArbitration_OpenBasket_NotApplied() {
        setUpDatabase();
        seedProduct("API_OB", "3.00");
        DomainUtils.createAndPersistOffer("API_OBX", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"API_OB\"], \"amountPerItem\": 0.50 } ] }");
        Basket b = basket(DomainUtils.createItem("API_OB", 2.0));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited limits dimension: maxApplicationsPerTicket caps the applications.
     */
    @Test
    void testArbitration_MaxApplicationsPerTicket() {
        setUpDatabase();
        seedProduct("API_L1", "3.00");
        seedProduct("API_L2", "3.00");
        DomainUtils.createAndPersistOffer("API_LIM", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"API_L1\", \"API_L2\"], \"amountPerItem\": 0.50 } ], "
                        + "\"arbitration\": { \"maxApplicationsPerTicket\": 1 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("API_L1", 1.0), DomainUtils.createItem("API_L2", 1.0)));
        long count = evaluation.getAdvantages().stream()
                .filter(a -> a instanceof AmountPerItemDiscountFactory.AmountPerItemDiscountApplication).count();
        assertEquals(1, count);
    }

    /**
     * Tests the inherited consumption dimension: a triggered advantage consuming its
     * contributors withdraws them from a second advantage.
     */
    @Test
    void testArbitration_ConsumesContributors() {
        setUpDatabase();
        seedProduct("API_CC", "3.00");
        DomainUtils.createAndPersistOffer("API_CC1", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"API_CC\"], \"amountPerItem\": 0.50 } ], "
                        + "\"arbitration\": { \"priority\": 10, \"consumesContributors\": true }, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"API_CC\"], \"threshold\": 1 } ] } }");
        DomainUtils.createAndPersistOffer("API_CC2", store, "AMOUNT_PER_ITEM_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"API_CC\"], \"amountPerItem\": 1.00 } ], "
                        + "\"arbitration\": { \"priority\": 20 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("API_CC", 1.0)));
        // Only the first applies (0.50 * 1 = 0.50); the second sees no available line.
        assertEquals(0, new BigDecimal("0.50").compareTo(totalDiscount(evaluation)));
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * A product-aware application: one product, one quantity, one supplied amount.
     */
    public static class ProductStub implements ProductAwareOfferApplication {

        /**
         * The covered product.
         */
        private final Product product;

        /**
         * The covered quantity in standard units.
         */
        private final double quantity;

        /**
         * The attributed amount; may be null.
         */
        private final AmountEvaluation amount;

        /**
         * Builds the stub.
         *
         * @param product  the covered product.
         * @param quantity the covered quantity.
         * @param amount   the attributed amount; may be null.
         */
        public ProductStub(Product product, double quantity, AmountEvaluation amount) {
            this.product = product;
            this.quantity = quantity;
            this.amount = amount;
        }

        /**
         * Returns the whole application amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "ProductStub";
        }

        /**
         * Returns the amount attributed to the given product.
         *
         * @param p the product being asked about.
         * @return the amount when the EAN matches, null otherwise.
         */
        @Override
        public AmountEvaluation getProductAmount(Product p) {
            return (p != null && product.ean.equals(p.ean)) ? amount : null;
        }

        /**
         * Returns the quantity attributed to the given product.
         *
         * @param p the product being asked about.
         * @return the quantity when the EAN matches, zero otherwise.
         */
        @Override
        public double getProductQuantity(Product p) {
            return (p != null && product.ean.equals(p.ean)) ? quantity : 0.0;
        }
    }

    /**
     * A non-product-aware application, simulating a service.
     */
    public static class ServiceStub implements OfferApplication {

        /**
         * The service amount.
         */
        private final AmountEvaluation amount;

        /**
         * Builds the stub.
         *
         * @param ttc the service amount at 0% VAT.
         */
        public ServiceStub(BigDecimal ttc) {
            this.amount = new AmountEvaluation(ttc, ttc, BigDecimal.ZERO);
        }

        /**
         * Returns the service amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "ServiceStub";
        }
    }

    /**
     * A product-aware offer applier stub applicable to a single EAN.
     */
    public static class FakeProductApplier extends OfferApplier implements ProductAwareOfferApplier {

        /**
         * The single EAN this applier covers.
         */
        private final String ean;

        /**
         * Builds the stub.
         *
         * @param ean the covered EAN.
         */
        public FakeProductApplier(String ean) {
            this.ean = ean;
        }

        /**
         * Produces no application.
         *
         * @param basketEvaluation the evaluation context.
         * @return an empty collection.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            return List.of();
        }

        /**
         * Tells whether this stub covers the given product.
         *
         * @param product the product to check.
         * @return true when the product carries the covered EAN.
         */
        @Override
        public boolean isApplicable(Product product) {
            return product != null && ean.equals(product.ean);
        }

        /**
         * Returns no configuration.
         *
         * @return always null.
         */
        @Override
        public com.intermarche.valuation.domain.Offer getConfiguration() {
            return null;
        }
    }

    /**
     * A plain offer applier stub that is not product-aware.
     */
    public static class NonProductApplier extends OfferApplier {

        /**
         * Produces no application.
         *
         * @param basketEvaluation the evaluation context.
         * @return an empty collection.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            return List.of();
        }

        /**
         * Returns no configuration.
         *
         * @return always null.
         */
        @Override
        public com.intermarche.valuation.domain.Offer getConfiguration() {
            return null;
        }
    }
}
