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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NewPriceDiscountFactory} using the real database.
 * <p>
 * Factory-level tests exercise the schema and cross-field validations through real offers;
 * applier-level tests feed hand-built product-aware applications into the evaluation and assert
 * the arithmetic to the cent; end-to-end tests drive {@link ValuationEngine#evaluate} to cover
 * the reference-price switch and the inherited C2 arbitration.
 */
@QuarkusTest
@TestTransaction
public class NewPriceDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    NewPriceDiscountFactory factory;

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
     * Seeds the store required by every test; called manually because {@code @TestTransaction}
     * rolls back between tests.
     */
    private void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_NP", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a DEFAULT and a BASE_FOR_DISCOUNT price.
     *
     * @param ean       the product EAN.
     * @param defaultTtc the DEFAULT price, tax excluded and included alike (0% VAT).
     * @param baseTtc   the BASE_FOR_DISCOUNT price, tax excluded and included alike (0% VAT).
     */
    private void seedProduct(String ean, String defaultTtc, String baseTtc) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        BigDecimal def = new BigDecimal(defaultTtc);
        BigDecimal base = new BigDecimal(baseTtc);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, def, def, BigDecimal.ZERO);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT, base, base, BigDecimal.ZERO);
    }

    /**
     * Builds an evaluation on an empty basket attached to the seeded store.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_NP";
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
        basket.storeCode = "STORE_NP";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds a single-target applier with no basket (so its sandbox score is zero).
     *
     * @param ean      the targeted EAN.
     * @param newPrice the new unit price, tax included.
     * @return the applier under test.
     */
    private NewPriceDiscountFactory.NewPriceDiscountApplier applier(String ean, String newPrice) {
        Product product = new Product();
        product.ean = ean;
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put(ean, new BigDecimal(newPrice));
        return new NewPriceDiscountFactory.NewPriceDiscountApplier(
                "NP1", map, List.of(product), null, null);
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
            if (advantage instanceof com.intermarche.valuation.engine.DiscountApplication discount
                    && discount.getDiscountAmount() != null) {
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
        String spec = "{ \"targets\": [ { \"eans\": [\"1000000000001\"], \"newPrice\": 1.99 } ] }";
        DomainUtils.createAndPersistOffer("NP_OK", store, "NEW_PRICE_DISCOUNT", spec);
        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof NewPriceDiscountFactory.NewPriceDiscountApplier);
    }

    /**
     * Tests that an EAN present in two targets is rejected at creation.
     */
    @Test
    void testBuildAppliers_DuplicateEanAcrossTargets_Rejected() {
        setUpDatabase();
        String spec = "{ \"targets\": [ { \"eans\": [\"E1\"], \"newPrice\": 1.99 }, "
                + "{ \"eans\": [\"E1\"], \"newPrice\": 0.99 } ] }";
        DomainUtils.createAndPersistOffer("NP_DUP", store, "NEW_PRICE_DISCOUNT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a specification without targets is rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingTargets_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("NP_NOTGT", store, "NEW_PRICE_DISCOUNT", "{ }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a non-positive new price is rejected by the schema (exclusiveMinimum 0).
     */
    @Test
    void testBuildAppliers_NonPositiveNewPrice_Rejected() {
        setUpDatabase();
        String spec = "{ \"targets\": [ { \"eans\": [\"E1\"], \"newPrice\": 0 } ] }";
        DomainUtils.createAndPersistOffer("NP_ZERO", store, "NEW_PRICE_DISCOUNT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an empty EAN list in a target is rejected by the schema (minItems 1).
     */
    @Test
    void testBuildAppliers_EmptyEans_Rejected() {
        setUpDatabase();
        String spec = "{ \"targets\": [ { \"eans\": [], \"newPrice\": 1.99 } ] }";
        DomainUtils.createAndPersistOffer("NP_EMPTY", store, "NEW_PRICE_DISCOUNT", spec);
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
     * Tests that a null basket is rejected with an explicit message.
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
     * Tests the nominal case: two units currently at 6.00 (3.00 each), a new price of 1.99,
     * gives a 2.02 discount so the paid amount is 3.98 = 1.99 × 2.
     */
    @Test
    void testApply_Nominal() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = applier("1000000000001", "1.99").apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((NewPriceDiscountFactory.NewPriceDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("2.02"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("1.68"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Tests that a new price equal to the current unit price produces nothing.
     */
    @Test
    void testApply_NewPriceEqualsCurrent_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        assertTrue(applier("1000000000001", "3.00").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a new price above the current unit price produces nothing (no negative
     * discount).
     */
    @Test
    void testApply_NewPriceAboveCurrent_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        assertTrue(applier("1000000000001", "4.00").apply(evaluation).isEmpty());
    }

    /**
     * Tests a two-target multi-list applier: each list carries its own new price.
     */
    @Test
    void testApply_MultiTarget() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "AAA";
        Product b = new Product();
        b.ean = "BBB";
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("AAA", new BigDecimal("1.99"));
        map.put("BBB", new BigDecimal("0.99"));
        NewPriceDiscountFactory.NewPriceDiscountApplier applier =
                new NewPriceDiscountFactory.NewPriceDiscountApplier("NPM", map, List.of(a, b), null, null);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 1.0,
                new AmountEvaluation(new BigDecimal("2.50"), new BigDecimal("3.00"), new BigDecimal("0.20"))));
        evaluation.getOffers().add(new ProductStub(b, 1.0,
                new AmountEvaluation(new BigDecimal("1.25"), new BigDecimal("2.00"), new BigDecimal("0.00"))));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(2, discounts.size());
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication d : discounts) {
            total = total.add(((NewPriceDiscountFactory.NewPriceDiscountApplication) d)
                    .getDiscountAmount().amountIncludingTax);
        }
        // AAA: 3.00 - 1.99 = 1.01 ; BBB: 2.00 - 0.99 = 1.01.
        assertEquals(new BigDecimal("2.02"), total);
    }

    /**
     * Tests that an empty assiette (no offer applications) produces nothing.
     */
    @Test
    void testApply_EmptyAssiette_NoApplication() {
        setUpDatabase();
        assertTrue(applier("1000000000001", "1.99").apply(newEvaluation()).isEmpty());
    }

    /**
     * Tests that a product covered with a zero quantity is skipped.
     */
    @Test
    void testApply_ZeroQuantity_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 0.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        assertTrue(applier("1000000000001", "1.99").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a covered product with a null attributed amount is skipped.
     */
    @Test
    void testApply_NullProductAmount_Skipped() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0, null));
        assertTrue(applier("1000000000001", "1.99").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a non-product-aware application is ignored.
     */
    @Test
    void testApply_NonProductAwareApplication_Ignored() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ServiceStub(new BigDecimal("10.00")));
        assertTrue(applier("1000000000001", "1.99").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a consumed offer application leaves the assiette (spec §4.4).
     */
    @Test
    void testApply_ConsumedOffer_Excluded() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        evaluation.markConsumed(List.of(stub));
        assertTrue(applier("1000000000001", "1.99").apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applicability, score, application getters
    // --------------------------------------------------

    /**
     * Tests that the discount is applicable to an applier covering a targeted product.
     */
    @Test
    void testIsApplicable_CoveringApplier_True() {
        setUpDatabase();
        assertTrue(applier("1000000000001", "1.99").isApplicable(new FakeProductApplier("1000000000001")));
    }

    /**
     * Tests that the discount is not applicable to an applier covering no targeted product.
     */
    @Test
    void testIsApplicable_NonCoveringApplier_False() {
        setUpDatabase();
        assertFalse(applier("1000000000001", "1.99").isApplicable(new FakeProductApplier("9999999999999")));
    }

    /**
     * Tests that the discount is not applicable to a non-product-aware applier.
     */
    @Test
    void testIsApplicable_NonProductAware_False() {
        setUpDatabase();
        assertFalse(applier("1000000000001", "1.99").isApplicable(new NonProductApplier()));
    }

    /**
     * Tests that the sandbox efficiency score is the summed potential discount on the basket
     * at the reference price.
     */
    @Test
    void testEfficiencyScore_SandboxFromBasket() {
        setUpDatabase();
        seedProduct("1000000000001", "3.00", "3.00");
        Product product = Product.findByEan("1000000000001");
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("1000000000001", new BigDecimal("1.99"));
        Basket b = basket(DomainUtils.createItem("1000000000001", 2.0));
        NewPriceDiscountFactory.NewPriceDiscountApplier applier =
                new NewPriceDiscountFactory.NewPriceDiscountApplier("NPS", map, List.of(product), b, store);
        // (3.00 - 1.99) * 2 = 2.02.
        assertEquals(0, new BigDecimal("2.02").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests that the sandbox score is zero without a basket or a store.
     */
    @Test
    void testEfficiencyScore_NoBasketOrStore_Zero() {
        setUpDatabase();
        assertEquals(0.0, applier("1000000000001", "1.99").getEfficiencyScore());
    }

    /**
     * Tests the application getters: type, EAN, and the application-moment round-trip.
     */
    @Test
    void testApplication_Getters() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))));
        NewPriceDiscountFactory.NewPriceDiscountApplication app =
                (NewPriceDiscountFactory.NewPriceDiscountApplication)
                        applier("1000000000001", "1.99").apply(evaluation).iterator().next();
        assertEquals("New Price: NP1", app.getType());
        assertEquals("1000000000001", app.getEan());
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
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        NewPriceDiscountFactory.NewPriceDiscountApplication app =
                (NewPriceDiscountFactory.NewPriceDiscountApplication)
                        applier("1000000000001", "1.99").apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * Tests the sandbox skip branches: a non-target line, a target line without a price, and a
     * target line whose new price is not below the reference all contribute nothing.
     */
    @Test
    void testEfficiencyScore_SandboxSkipBranches() {
        setUpDatabase();
        seedProduct("NP_OTHER", "3.00", "3.00");
        DomainUtils.createAndPersistProduct("NP_NOPRICE", "NP_NOPRICE", ProductType.UNIT);
        seedProduct("NP_HIGH", "3.00", "3.00");
        Product noPrice = Product.findByEan("NP_NOPRICE");
        Product high = Product.findByEan("NP_HIGH");
        java.util.Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("NP_NOPRICE", new BigDecimal("1.00"));
        map.put("NP_HIGH", new BigDecimal("100.00"));
        Basket b = basket(
                DomainUtils.createItem("NP_OTHER", 1.0),
                DomainUtils.createItem("NP_NOPRICE", 1.0),
                DomainUtils.createItem("NP_HIGH", 1.0));
        NewPriceDiscountFactory.NewPriceDiscountApplier applier =
                new NewPriceDiscountFactory.NewPriceDiscountApplier("NPX", map, List.of(noPrice, high), b, store);
        assertEquals(0.0, applier.getEfficiencyScore());
    }

    /**
     * Tests the net factor when the offer application carries no amount: the base stays the
     * gross product amount, so the discount is computed off it.
     */
    @Test
    void testApply_NetFactor_NullGrossAmount() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        // getAmount() is null but the per-product amount is 6.00.
        evaluation.getOffers().add(new ProductStub(product, 2.0, null) {
            @Override
            public AmountEvaluation getProductAmount(Product p) {
                return (p != null && "1000000000001".equals(p.ean))
                        ? new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"))
                        : null;
            }
        });
        assertEquals(new BigDecimal("2.02"), ((NewPriceDiscountFactory.NewPriceDiscountApplication)
                applier("1000000000001", "1.99").apply(evaluation).iterator().next())
                .getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests the net factor clamped to zero: a prior discount exceeding the gross amount leaves
     * no base, so the new price produces nothing.
     */
    @Test
    void testApply_NetFactor_NegativeClamped() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        // A prior discount larger than the gross amount drives the net factor below zero.
        evaluation.getAdvantages().add(new NewPriceDiscountFactory.NewPriceDiscountApplication(
                "PRIOR", "1000000000001", stub,
                new AmountEvaluation(new BigDecimal("8.33"), new BigDecimal("10.00"), new BigDecimal("0.20"))));
        assertTrue(applier("1000000000001", "1.00").apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // End-to-end (reference-price switch and arbitration)
    // --------------------------------------------------

    /**
     * Tests the normative reference-price switch: with a DEFAULT price of 5.00 and a reference
     * of 6.00, a new price of 4.00 discounts 6.00 − 4.00 = 2.00 and the paid amount is 4.00 =
     * the new price × quantity.
     */
    @Test
    void testEndToEnd_ReferenceSwitch_PaidIsNewPrice() {
        setUpDatabase();
        seedProduct("NP_REF", "5.00", "6.00");
        DomainUtils.createAndPersistOffer("NP_E2E", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_REF\"], \"newPrice\": 4.00 } ] }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("NP_REF", 1.0)));
        assertEquals(0, new BigDecimal("2.00").compareTo(totalDiscount(evaluation)));
        assertEquals(0, new BigDecimal("4.00").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * Tests two competing new prices resolved by priority: the higher-priority offer applies
     * first, and the second sees the base already at the first new price and only bites if it
     * goes lower still. Here the second new price (2.50) is below the first (3.00), so it adds
     * a further discount.
     */
    @Test
    void testEndToEnd_TwoCompeting_Priorities() {
        setUpDatabase();
        seedProduct("NP_C", "10.00", "10.00");
        DomainUtils.createAndPersistOffer("NP_FIRST", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_C\"], \"newPrice\": 3.00 } ], "
                        + "\"arbitration\": { \"priority\": 10 } }");
        DomainUtils.createAndPersistOffer("NP_SECOND", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_C\"], \"newPrice\": 2.50 } ], "
                        + "\"arbitration\": { \"priority\": 20 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("NP_C", 1.0)));
        // First drives 10 -> 3 (7.00). Second, seeing the base at 3.00, drives 3 -> 2.50 (0.50).
        assertEquals(0, new BigDecimal("7.50").compareTo(totalDiscount(evaluation)));
        assertEquals(0, new BigDecimal("2.50").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * Tests the inherited cumul dimension: a non-cumulable new-price advantage bars a second
     * advantage on the same ticket.
     */
    @Test
    void testArbitration_NonCumulable() {
        setUpDatabase();
        seedProduct("NP_NC", "10.00", "10.00");
        DomainUtils.createAndPersistOffer("NP_NC1", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_NC\"], \"newPrice\": 6.00 } ], "
                        + "\"arbitration\": { \"priority\": 10, \"cumulable\": false } }");
        DomainUtils.createAndPersistOffer("NP_NC2", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_NC\"], \"newPrice\": 4.00 } ], "
                        + "\"arbitration\": { \"priority\": 20, \"cumulable\": false } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("NP_NC", 1.0)));
        // Both are non-cumulable: the first (higher priority) applies (10 -> 6 = 4.00) and bars
        // the second non-cumulable advantage.
        assertEquals(0, new BigDecimal("4.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the inherited limits dimension: maxApplicationsPerTicket caps the produced
     * applications.
     */
    @Test
    void testArbitration_MaxApplicationsPerTicket() {
        setUpDatabase();
        seedProduct("NP_L1", "10.00", "10.00");
        seedProduct("NP_L2", "10.00", "10.00");
        DomainUtils.createAndPersistOffer("NP_LIM", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_L1\", \"NP_L2\"], \"newPrice\": 5.00 } ], "
                        + "\"arbitration\": { \"maxApplicationsPerTicket\": 1 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("NP_L1", 1.0), DomainUtils.createItem("NP_L2", 1.0)));
        long count = evaluation.getAdvantages().stream()
                .filter(a -> a instanceof NewPriceDiscountFactory.NewPriceDiscountApplication).count();
        assertEquals(1, count);
    }

    /**
     * Tests the inherited open-basket dimension: an AT_TOTAL new-price advantage does not fall
     * on an open basket (spec §4.2).
     */
    @Test
    void testArbitration_OpenBasket_NotApplied() {
        setUpDatabase();
        seedProduct("NP_OB", "10.00", "10.00");
        DomainUtils.createAndPersistOffer("NP_OBX", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_OB\"], \"newPrice\": 5.00 } ] }");
        Basket b = basket(DomainUtils.createItem("NP_OB", 1.0));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited consumption dimension: a triggered new-price advantage that consumes
     * its contributors withdraws them from a second advantage measuring the same lines.
     */
    @Test
    void testArbitration_ConsumesContributors() {
        setUpDatabase();
        seedProduct("NP_CC", "10.00", "10.00");
        // First advantage: triggered by 1 unit of NP_CC, consumes its contributors.
        DomainUtils.createAndPersistOffer("NP_CC1", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_CC\"], \"newPrice\": 6.00 } ], "
                        + "\"arbitration\": { \"priority\": 10, \"consumesContributors\": true }, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"NP_CC\"], \"threshold\": 1 } ] } }");
        // Second advantage: same line, lower priority; its contributors were consumed.
        DomainUtils.createAndPersistOffer("NP_CC2", store, "NEW_PRICE_DISCOUNT",
                "{ \"targets\": [ { \"eans\": [\"NP_CC\"], \"newPrice\": 4.00 } ], "
                        + "\"arbitration\": { \"priority\": 20 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("NP_CC", 1.0)));
        // Only the first applies (10 -> 6 = 4.00); the second sees no available line.
        assertEquals(0, new BigDecimal("4.00").compareTo(totalDiscount(evaluation)));
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
        public BigDecimal getProductQuantity(Product p) {
            return (p != null && product.ean.equals(p.ean)) ? BigDecimal.valueOf(quantity) : BigDecimal.ZERO;
        }
    }

    /**
     * A non-product-aware application, simulating a service (delivery).
     */
    public static class ServiceStub implements OfferApplication {

        /**
         * The service amount.
         */
        private final AmountEvaluation amount;

        /**
         * Builds the stub.
         *
         * @param ttc the service amount, tax included at 0% VAT.
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
