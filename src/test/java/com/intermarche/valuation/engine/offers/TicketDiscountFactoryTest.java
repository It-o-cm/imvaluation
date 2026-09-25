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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TicketDiscountFactory} using the real database.
 * <p>
 * Factory-level tests exercise the schema and the percentage bound; applier-level tests assert
 * the percentage and capped-amount arithmetic and the pro-rata distribution; end-to-end tests
 * cover the canonical "5€ from 50€" trigger and the inherited C2 arbitration.
 */
@QuarkusTest
@TestTransaction
public class TicketDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    TicketDiscountFactory factory;

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
        store = DomainUtils.createAndPersistStore("STORE_TD", 48.8566, 2.352214);
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
        basket.storeCode = "STORE_TD";
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
        basket.storeCode = "STORE_TD";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds an applier with no basket (sandbox score zero).
     *
     * @param type  the discount type.
     * @param value the percentage or amount.
     * @return the applier under test.
     */
    private TicketDiscountFactory.TicketDiscountApplier applier(TicketDiscountFactory.DiscountType type, String value) {
        return new TicketDiscountFactory.TicketDiscountApplier("TD1", type, new BigDecimal(value), null, null);
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
        DomainUtils.createAndPersistOffer("TD_OK", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 10.0 }");
        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof TicketDiscountFactory.TicketDiscountApplier);
    }

    /**
     * Tests that a percentage above 100 is rejected.
     */
    @Test
    void testBuildAppliers_PercentageAbove100_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("TD_HI", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 150 }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a non-positive value is rejected by the schema.
     */
    @Test
    void testBuildAppliers_NonPositiveValue_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("TD_ZERO", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"AMOUNT\", \"value\": 0 }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a missing value is rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingValue_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("TD_NV", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\" }");
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
     * Tests the percentage nominal case: 10% of a 100.00 assiette is a 10.00 discount.
     */
    @Test
    void testApply_PercentageNominal() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> discounts = applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").apply(evaluation);
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("10.00"), ((TicketDiscountFactory.TicketDiscountApplication)
                discounts.iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests the amount case capped at the assiette: a 200 flat amount on a 100 assiette gives
     * a 100 discount.
     */
    @Test
    void testApply_AmountCappedAtAssiette() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO)));
        assertEquals(new BigDecimal("100.00"), ((TicketDiscountFactory.TicketDiscountApplication)
                applier(TicketDiscountFactory.DiscountType.AMOUNT, "200").apply(evaluation)
                        .iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests the amount case below the assiette: a 5 flat amount is taken in full.
     */
    @Test
    void testApply_AmountBelowAssiette() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product,
                new AmountEvaluation(new BigDecimal("50.00"), new BigDecimal("50.00"), BigDecimal.ZERO)));
        assertEquals(new BigDecimal("5.00"), ((TicketDiscountFactory.TicketDiscountApplication)
                applier(TicketDiscountFactory.DiscountType.AMOUNT, "5").apply(evaluation)
                        .iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests the pro-rata distribution across two applications, the residue on the last.
     */
    @Test
    void testApply_ProRataDistribution() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO)));
        evaluation.getOffers().add(new ProductStub(b,
                new AmountEvaluation(new BigDecimal("50.00"), new BigDecimal("50.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> discounts = applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").apply(evaluation);
        assertEquals(2, discounts.size());
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication d : discounts) {
            total = total.add(((TicketDiscountFactory.TicketDiscountApplication) d)
                    .getDiscountAmount().amountIncludingTax);
        }
        // 10% of 150 = 15.00, split 10.00 (A) + 5.00 (B).
        assertEquals(new BigDecimal("15.00"), total);
    }

    /**
     * Tests that an empty assiette produces nothing.
     */
    @Test
    void testApply_EmptyAssiette_NoApplication() {
        setUpDatabase();
        assertTrue(applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").apply(newEvaluation()).isEmpty());
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
        ProductStub stub = new ProductStub(product,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO));
        evaluation.getOffers().add(stub);
        evaluation.markConsumed(List.of(stub));
        assertTrue(applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a non-product-aware application is ignored.
     */
    @Test
    void testApply_NonProductAware_Ignored() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ServiceStub(new BigDecimal("10.00")));
        assertTrue(applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applicability, score
    // --------------------------------------------------

    /**
     * Tests that the discount is applicable to any product-aware applier and not to others.
     */
    @Test
    void testIsApplicable() {
        setUpDatabase();
        assertTrue(applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").isApplicable(new FakeProductApplier("E1")));
        assertFalse(applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").isApplicable(new NonProductApplier()));
    }

    /**
     * Tests the sandbox score for a percentage over the basket at the reference price.
     */
    @Test
    void testEfficiencyScore_Percentage() {
        setUpDatabase();
        seedProduct("TD_S", "100.00");
        Basket b = basket(DomainUtils.createItem("TD_S", 1.0));
        TicketDiscountFactory.TicketDiscountApplier applier = new TicketDiscountFactory.TicketDiscountApplier(
                "TDS", TicketDiscountFactory.DiscountType.PERCENTAGE, new BigDecimal("10"), b, store);
        assertEquals(0, new BigDecimal("10.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests the sandbox score for a capped flat amount over the basket at the reference price.
     */
    @Test
    void testEfficiencyScore_AmountCapped() {
        setUpDatabase();
        seedProduct("TD_SA", "30.00");
        Basket b = basket(DomainUtils.createItem("TD_SA", 1.0));
        TicketDiscountFactory.TicketDiscountApplier applier = new TicketDiscountFactory.TicketDiscountApplier(
                "TDSA", TicketDiscountFactory.DiscountType.AMOUNT, new BigDecimal("50"), b, store);
        // The 50 flat amount is capped at the 30 assiette.
        assertEquals(0, new BigDecimal("30.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests that the sandbox score is zero without a basket or store.
     */
    @Test
    void testEfficiencyScore_NoBasketOrStore_Zero() {
        setUpDatabase();
        assertEquals(0.0, applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").getEfficiencyScore());
    }

    /**
     * Tests the application getters: type and application-moment round-trip.
     */
    @Test
    void testApplication_Getters() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO)));
        TicketDiscountFactory.TicketDiscountApplication app =
                (TicketDiscountFactory.TicketDiscountApplication) applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10")
                        .apply(evaluation).iterator().next();
        assertEquals("Ticket Discount: TD1", app.getType());
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
        ProductStub stub = new ProductStub(product,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO));
        evaluation.getOffers().add(stub);
        TicketDiscountFactory.TicketDiscountApplication app =
                (TicketDiscountFactory.TicketDiscountApplication) applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10")
                        .apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * Tests that an unpriced basket line leaves the sandbox assiette empty, for a zero score.
     */
    @Test
    void testEfficiencyScore_UnpricedLine_Zero() {
        setUpDatabase();
        DomainUtils.createAndPersistProduct("TD_NOPRICE", "TD_NOPRICE", ProductType.UNIT);
        Basket b = basket(DomainUtils.createItem("TD_NOPRICE", 1.0));
        TicketDiscountFactory.TicketDiscountApplier applier = new TicketDiscountFactory.TicketDiscountApplier(
                "TDU", TicketDiscountFactory.DiscountType.PERCENTAGE, new BigDecimal("10"), b, store);
        assertEquals(0.0, applier.getEfficiencyScore());
    }

    /**
     * Tests the distribution of a line with a zero taxable base: the rate falls back to zero.
     */
    @Test
    void testApply_ZeroHtLine_RateFallback() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product,
                new AmountEvaluation(BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = applier(TicketDiscountFactory.DiscountType.PERCENTAGE, "10").apply(evaluation);
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("10.00"), ((TicketDiscountFactory.TicketDiscountApplication)
                discounts.iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    // --------------------------------------------------
    // End-to-end (trigger and arbitration)
    // --------------------------------------------------

    /**
     * Tests the canonical "5€ from 50€": the trigger satisfied applies a 5.00 discount.
     */
    @Test
    void testEndToEnd_FiveFromFifty_Satisfied() {
        setUpDatabase();
        seedProduct("TD_G", "50.00");
        DomainUtils.createAndPersistOffer("TD_5", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"AMOUNT\", \"value\": 5, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"threshold\": 50 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("TD_G", 1.0)));
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the canonical "5€ from 50€": below the threshold, nothing applies.
     */
    @Test
    void testEndToEnd_FiveFromFifty_NotSatisfied() {
        setUpDatabase();
        seedProduct("TD_G", "40.00");
        DomainUtils.createAndPersistOffer("TD_5", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"AMOUNT\", \"value\": 5, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"threshold\": 50 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("TD_G", 1.0)));
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited open-basket dimension: an AT_TOTAL ticket discount does not fall on
     * an open basket.
     */
    @Test
    void testArbitration_OpenBasket_NotApplied() {
        setUpDatabase();
        seedProduct("TD_OB", "100.00");
        DomainUtils.createAndPersistOffer("TD_OBX", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 10 }");
        Basket b = basket(DomainUtils.createItem("TD_OB", 1.0));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited cumul dimension: a non-cumulable ticket discount bars a second
     * non-cumulable advantage.
     */
    @Test
    void testArbitration_NonCumulable() {
        setUpDatabase();
        seedProduct("TD_NC", "100.00");
        DomainUtils.createAndPersistOffer("TD_NC1", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 10, "
                        + "\"arbitration\": { \"priority\": 10, \"cumulable\": false } }");
        DomainUtils.createAndPersistOffer("TD_NC2", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 5, "
                        + "\"arbitration\": { \"priority\": 20, \"cumulable\": false } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("TD_NC", 1.0)));
        // Only the first (higher priority) 10% applies: 10.00.
        assertEquals(0, new BigDecimal("10.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the inherited consumption dimension: a triggered ticket discount consuming its
     * contributors withdraws them from a second advantage that measures the same lines.
     */
    @Test
    void testArbitration_ConsumesContributors() {
        setUpDatabase();
        seedProduct("TD_CC", "100.00");
        DomainUtils.createAndPersistOffer("TD_CC1", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 10, "
                        + "\"arbitration\": { \"priority\": 10, \"consumesContributors\": true }, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"TD_CC\"], \"threshold\": 1 } ] } }");
        DomainUtils.createAndPersistOffer("TD_CC2", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 5, "
                        + "\"arbitration\": { \"priority\": 20 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("TD_CC", 1.0)));
        // Only the first applies (10.00); the second sees no available line.
        assertEquals(0, new BigDecimal("10.00").compareTo(totalDiscount(evaluation)));
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * A product-aware application with a single supplied amount.
     */
    public static class ProductStub implements ProductAwareOfferApplication {

        /**
         * The covered product.
         */
        private final Product product;

        /**
         * The attributed amount.
         */
        private final AmountEvaluation amount;

        /**
         * Builds the stub.
         *
         * @param product the covered product.
         * @param amount  the attributed amount.
         */
        public ProductStub(Product product, AmountEvaluation amount) {
            this.product = product;
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
         * @return one when the EAN matches, zero otherwise.
         */
        @Override
        public BigDecimal getProductQuantity(Product p) {
            return (p != null && product.ean.equals(p.ean)) ? BigDecimal.valueOf(1.0) : BigDecimal.ZERO;
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
