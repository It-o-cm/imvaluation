package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.TierTable;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the additive {@code perProduct} flag of {@link TieredDiscountFactory}
 * (spec §8), kept in a dedicated class so the pre-existing {@code TieredDiscountFactoryTest} —
 * which pins the {@code perProduct}-false behaviour — stays untouched.
 * <p>
 * The flag measures the metric and resolves the tiers per distinct target EAN: 6 of A and 2 of
 * B each reach their own tier ("produit identique"). The creation-time cross rule requires scope
 * ITEMS and metric QUANTITY.
 */
@QuarkusTest
@TestTransaction
public class TieredDiscountPerProductTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    TieredDiscountFactory factory;

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
        store = DomainUtils.createAndPersistStore("STORE_PP", 48.8566, 2.352214);
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
        basket.storeCode = "STORE_PP";
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
        basket.storeCode = "STORE_PP";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds a HIGHEST_REACHED quantity applier (threshold 6 → 10%) over two products.
     *
     * @param a          the first product.
     * @param b          the second product.
     * @param perProduct whether the tiers resolve per distinct EAN.
     * @return the applier under test.
     */
    private TieredDiscountFactory.TieredDiscountApplier applier(Product a, Product b, boolean perProduct) {
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("6"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        return new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PP", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Metric.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(a, b), perProduct);
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

    /**
     * Sums the tax-included amount of a collection of discount applications.
     *
     * @param discounts the discount applications.
     * @return the total, tax included.
     */
    private BigDecimal sum(Collection<AdvantageApplication> discounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication d : discounts) {
            total = total.add(((DiscountApplication) d).getDiscountAmount().amountIncludingTax);
        }
        return total;
    }

    // --------------------------------------------------
    // Factory cross rules
    // --------------------------------------------------

    /**
     * Tests the successful creation of a perProduct applier (scope ITEMS, metric QUANTITY).
     */
    @Test
    void testBuildAppliers_PerProductSuccess() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("PP_OK", store, "TIERED_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"A\"], \"metric\": \"QUANTITY\", "
                        + "\"mode\": \"HIGHEST_REACHED\", \"perProduct\": true, "
                        + "\"tiers\": [ { \"threshold\": 6, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ] }");
        assertEquals(1, factory.buildAppliers(newEvaluation()).size());
    }

    /**
     * Tests that perProduct with scope TICKET is rejected.
     */
    @Test
    void testBuildAppliers_PerProductTicket_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("PP_TK", store, "TIERED_DISCOUNT",
                "{ \"scope\": \"TICKET\", \"metric\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                        + "\"perProduct\": true, "
                        + "\"tiers\": [ { \"threshold\": 6, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that perProduct with metric AMOUNT is rejected.
     */
    @Test
    void testBuildAppliers_PerProductAmount_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("PP_AM", store, "TIERED_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"A\"], \"metric\": \"AMOUNT\", "
                        + "\"mode\": \"HIGHEST_REACHED\", \"perProduct\": true, "
                        + "\"tiers\": [ { \"threshold\": 6, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that the flag defaults to false when absent (the applier still builds).
     */
    @Test
    void testBuildAppliers_DefaultFalse() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("PP_DEF", store, "TIERED_DISCOUNT",
                "{ \"scope\": \"TICKET\", \"metric\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                        + "\"tiers\": [ { \"threshold\": 6, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ] }");
        assertEquals(1, factory.buildAppliers(newEvaluation()).size());
    }

    // --------------------------------------------------
    // Applier logic
    // --------------------------------------------------

    /**
     * Tests the per-product resolution: with 6 of A and 2 of B and a 6-unit threshold, only A
     * reaches its tier, for a 10% discount on A alone (0.60).
     */
    @Test
    void testApply_PerProduct_6A2B() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 6.0,
                new AmountEvaluation(new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO)));
        evaluation.getOffers().add(new ProductStub(b, 2.0,
                new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> discounts = applier(a, b, true).apply(evaluation);
        // Only A reaches the 6-unit tier: 10% of 6.00 = 0.60.
        assertEquals(new BigDecimal("0.60"), sum(discounts));
    }

    /**
     * Tests the whole-assiette resolution (perProduct false): the summed 8 units reach the
     * tier, for a 10% discount on the whole 8.00 assiette (0.80).
     */
    @Test
    void testApply_WholeAssiette_6A2B() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 6.0,
                new AmountEvaluation(new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO)));
        evaluation.getOffers().add(new ProductStub(b, 2.0,
                new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> discounts = applier(a, b, false).apply(evaluation);
        // The 8 summed units reach the tier: 10% of 8.00 = 0.80.
        assertEquals(new BigDecimal("0.80"), sum(discounts));
    }

    /**
     * Tests that per-product resolution gives each EAN its own tier: 6 of A and 6 of B both
     * reach the tier, for a discount on each (0.60 + 0.60 = 1.20).
     */
    @Test
    void testApply_PerProduct_BothReach() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 6.0,
                new AmountEvaluation(new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO)));
        evaluation.getOffers().add(new ProductStub(b, 6.0,
                new AmountEvaluation(new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> discounts = applier(a, b, true).apply(evaluation);
        assertEquals(new BigDecimal("1.20"), sum(discounts));
    }

    // --------------------------------------------------
    // End-to-end
    // --------------------------------------------------

    /**
     * Tests the per-product flag end to end: 6 of A and 2 of B, only A reaches the tier.
     */
    @Test
    void testEndToEnd_PerProduct() {
        setUpDatabase();
        seedProduct("PP_A", "1.00");
        seedProduct("PP_B", "1.00");
        DomainUtils.createAndPersistOffer("PP_E2E", store, "TIERED_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"PP_A\", \"PP_B\"], \"metric\": \"QUANTITY\", "
                        + "\"mode\": \"HIGHEST_REACHED\", \"perProduct\": true, "
                        + "\"tiers\": [ { \"threshold\": 6, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ] }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("PP_A", 6.0), DomainUtils.createItem("PP_B", 2.0)));
        // Only PP_A reaches the tier: 10% of 6.00 = 0.60.
        assertEquals(0, new BigDecimal("0.60").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the same basket without the flag: the summed 8 units reach the tier for a 0.80
     * discount, confirming the flag changes the outcome.
     */
    @Test
    void testEndToEnd_WholeAssiette() {
        setUpDatabase();
        seedProduct("PP_A", "1.00");
        seedProduct("PP_B", "1.00");
        DomainUtils.createAndPersistOffer("PP_E2E", store, "TIERED_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"PP_A\", \"PP_B\"], \"metric\": \"QUANTITY\", "
                        + "\"mode\": \"HIGHEST_REACHED\", "
                        + "\"tiers\": [ { \"threshold\": 6, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ] }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("PP_A", 6.0), DomainUtils.createItem("PP_B", 2.0)));
        assertEquals(0, new BigDecimal("0.80").compareTo(totalDiscount(evaluation)));
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * A product-aware application: one product, one quantity, one amount.
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
         * The attributed amount.
         */
        private final AmountEvaluation amount;

        /**
         * Builds the stub.
         *
         * @param product  the covered product.
         * @param quantity the covered quantity.
         * @param amount   the attributed amount.
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
        public BigDecimal getProductQuantity(Product p) {
            return (p != null && product.ean.equals(p.ean)) ? BigDecimal.valueOf(quantity) : BigDecimal.ZERO;
        }
    }
}
