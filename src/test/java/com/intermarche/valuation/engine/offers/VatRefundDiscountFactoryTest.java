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
 * Integration tests for {@link VatRefundDiscountFactory} using the real database.
 * <p>
 * Factory-level tests exercise the schema and the scope cross rules; applier-level tests assert
 * the VAT arithmetic and the pro-rata distribution to the cent; the end-to-end test enforces the
 * normative example (120.00 TTC / 20 % ⇒ discount 20.00, paid 100.00, fiscal VAT 16.67) and the
 * inherited C2 arbitration.
 */
@QuarkusTest
@TestTransaction
public class VatRefundDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    VatRefundDiscountFactory factory;

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
        store = DomainUtils.createAndPersistStore("STORE_VR", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a DEFAULT and a BASE_FOR_DISCOUNT price at the given rate.
     *
     * @param ean the product EAN.
     * @param ht  the price excluding tax.
     * @param ttc the price including tax.
     * @param vat the VAT rate.
     */
    private void seedProduct(String ean, String ht, String ttc, String vat) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        BigDecimal e = new BigDecimal(ht);
        BigDecimal i = new BigDecimal(ttc);
        BigDecimal r = new BigDecimal(vat);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, e, i, r);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT, e, i, r);
    }

    /**
     * Builds an evaluation on an empty basket attached to the seeded store.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_VR";
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
        basket.storeCode = "STORE_VR";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds a TICKET-scope applier with no basket (sandbox score zero).
     *
     * @return the applier under test.
     */
    private VatRefundDiscountFactory.VatRefundDiscountApplier ticketApplier() {
        return new VatRefundDiscountFactory.VatRefundDiscountApplier(
                "VR1", VatRefundDiscountFactory.Scope.TICKET, List.of(), null, null);
    }

    /**
     * Builds an ITEMS-scope applier targeting one product.
     *
     * @param ean the targeted EAN.
     * @return the applier under test.
     */
    private VatRefundDiscountFactory.VatRefundDiscountApplier itemsApplier(String ean) {
        Product product = new Product();
        product.ean = ean;
        return new VatRefundDiscountFactory.VatRefundDiscountApplier(
                "VR2", VatRefundDiscountFactory.Scope.ITEMS, List.of(product), null, null);
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
     * Tests the successful creation of a TICKET-scope applier.
     */
    @Test
    void testBuildAppliers_TicketSuccess() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("VR_OK", store, "VAT_REFUND_DISCOUNT", "{ \"scope\": \"TICKET\" }");
        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof VatRefundDiscountFactory.VatRefundDiscountApplier);
    }

    /**
     * Tests the successful creation of an ITEMS-scope applier with target EANs.
     */
    @Test
    void testBuildAppliers_ItemsSuccess() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("VR_ITEMS", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"E1\"] }");
        assertEquals(1, factory.buildAppliers(newEvaluation()).size());
    }

    /**
     * Tests that scope ITEMS without target EANs is rejected.
     */
    @Test
    void testBuildAppliers_ItemsWithoutEans_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("VR_NOEAN", store, "VAT_REFUND_DISCOUNT", "{ \"scope\": \"ITEMS\" }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that scope TICKET with target EANs is rejected.
     */
    @Test
    void testBuildAppliers_TicketWithEans_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("VR_TE", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"TICKET\", \"targetEans\": [\"E1\"] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a specification without a scope is rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingScope_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("VR_NS", store, "VAT_REFUND_DISCOUNT", "{ }");
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
     * Tests the TICKET nominal case: a 120.00 TTC / 20 % line yields a 20.00 discount (HT
     * 16.67).
     */
    @Test
    void testApply_TicketNominal() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = ticketApplier().apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((VatRefundDiscountFactory.VatRefundDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("20.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("16.67"), amount.amountExcludingTax);
    }

    /**
     * Tests the ITEMS scope: only the targeted line contributes to the VAT.
     */
    @Test
    void testApply_ItemsScope() {
        setUpDatabase();
        Product target = new Product();
        target.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(target, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = itemsApplier("E1").apply(evaluation);
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("20.00"), ((VatRefundDiscountFactory.VatRefundDiscountApplication)
                discounts.iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests that a zero-VAT line (gift card) contributes nothing, so nothing is produced.
     */
    @Test
    void testApply_ZeroVat_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("50.00"), new BigDecimal("50.00"), BigDecimal.ZERO)));
        assertTrue(ticketApplier().apply(evaluation).isEmpty());
    }

    /**
     * Tests the multi-rate case: the VAT is summed line by line across two rates.
     */
    @Test
    void testApply_MultiRate() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"))));
        evaluation.getOffers().add(new ProductStub(b, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("105.00"), new BigDecimal("0.05"))));
        // VAT: 20.00 + 5.00 = 25.00.
        assertEquals(0, new BigDecimal("25.00").compareTo(totalDiscount(ticketApplyToEvaluation(evaluation))));
    }

    /**
     * Applies the TICKET applier and records its applications on the evaluation, returning it
     * so the shared {@link #totalDiscount} can sum them.
     *
     * @param evaluation the evaluation to apply on.
     * @return the same evaluation with the applications recorded.
     */
    private BasketEvaluation ticketApplyToEvaluation(BasketEvaluation evaluation) {
        evaluation.getAdvantages().addAll(ticketApplier().apply(evaluation));
        return evaluation;
    }

    /**
     * Tests the pro-rata distribution across two applications, the residue landing on the last.
     */
    @Test
    void testApply_ProRataDistribution() {
        setUpDatabase();
        Product a = new Product();
        a.ean = "A";
        Product b = new Product();
        b.ean = "B";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(a, 1.0,
                new AmountEvaluation(new BigDecimal("83.33"), new BigDecimal("100.00"), new BigDecimal("0.20"))));
        evaluation.getOffers().add(new ProductStub(b, 1.0,
                new AmountEvaluation(new BigDecimal("41.67"), new BigDecimal("50.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = ticketApplier().apply(evaluation);
        assertEquals(2, discounts.size());
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication d : discounts) {
            total = total.add(((VatRefundDiscountFactory.VatRefundDiscountApplication) d)
                    .getDiscountAmount().amountIncludingTax);
        }
        // VAT of A = 16.67, of B = 8.33; total 25.00, distributed pro-rata of the 150 assiette.
        assertEquals(new BigDecimal("25.00"), total);
    }

    /**
     * Tests that an empty assiette produces nothing.
     */
    @Test
    void testApply_EmptyAssiette_NoApplication() {
        setUpDatabase();
        assertTrue(ticketApplier().apply(newEvaluation()).isEmpty());
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
        ProductStub stub = new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        evaluation.markConsumed(List.of(stub));
        assertTrue(ticketApplier().apply(evaluation).isEmpty());
    }

    /**
     * Tests that a non-product-aware application is ignored in the assiette.
     */
    @Test
    void testApply_NonProductAware_Ignored() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ServiceStub(new BigDecimal("10.00")));
        assertTrue(ticketApplier().apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applicability, score
    // --------------------------------------------------

    /**
     * Tests that the TICKET discount is applicable to any product-aware applier.
     */
    @Test
    void testIsApplicable_TicketAnyProductAware_True() {
        setUpDatabase();
        assertTrue(ticketApplier().isApplicable(new FakeProductApplier("E1")));
    }

    /**
     * Tests that the ITEMS discount is applicable only to appliers covering a target.
     */
    @Test
    void testIsApplicable_ItemsCovering_True() {
        setUpDatabase();
        assertTrue(itemsApplier("E1").isApplicable(new FakeProductApplier("E1")));
        assertFalse(itemsApplier("E1").isApplicable(new FakeProductApplier("E9")));
    }

    /**
     * Tests that the discount is not applicable to a non-product-aware applier.
     */
    @Test
    void testIsApplicable_NonProductAware_False() {
        setUpDatabase();
        assertFalse(ticketApplier().isApplicable(new NonProductApplier()));
    }

    /**
     * Tests that the sandbox score is the VAT of the assiette at the reference price.
     */
    @Test
    void testEfficiencyScore_SandboxFromBasket() {
        setUpDatabase();
        seedProduct("VR_S", "100.00", "120.00", "0.20");
        Basket b = basket(DomainUtils.createItem("VR_S", 1.0));
        VatRefundDiscountFactory.VatRefundDiscountApplier applier =
                new VatRefundDiscountFactory.VatRefundDiscountApplier(
                        "VRS", VatRefundDiscountFactory.Scope.TICKET, List.of(), b, store);
        assertEquals(0, new BigDecimal("20.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests that the sandbox score is zero without a basket or store.
     */
    @Test
    void testEfficiencyScore_NoBasketOrStore_Zero() {
        setUpDatabase();
        assertEquals(0.0, ticketApplier().getEfficiencyScore());
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
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"))));
        VatRefundDiscountFactory.VatRefundDiscountApplication app =
                (VatRefundDiscountFactory.VatRefundDiscountApplication) ticketApplier().apply(evaluation).iterator().next();
        assertEquals("VAT Refund: VR1", app.getType());
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
        ProductStub stub = new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20")));
        evaluation.getOffers().add(stub);
        VatRefundDiscountFactory.VatRefundDiscountApplication app =
                (VatRefundDiscountFactory.VatRefundDiscountApplication) ticketApplier().apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * Tests that a TICKET line with no amount is skipped in the assiette.
     */
    @Test
    void testApply_TicketNullAmount_Skipped() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0, null));
        assertTrue(ticketApplier().apply(evaluation).isEmpty());
    }

    /**
     * Tests the ITEMS skip guards: a targeted product covered with a zero quantity, and one
     * with a null amount, both leave the assiette.
     */
    @Test
    void testApply_ItemsSkipGuards() {
        setUpDatabase();
        Product target = new Product();
        target.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(target, 0.0,
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"))));
        evaluation.getOffers().add(new ProductStub(target, 1.0, null));
        assertTrue(itemsApplier("E1").apply(evaluation).isEmpty());
    }

    /**
     * Tests the distribution of a line with a zero taxable base (a fully taxed amount): the
     * rate falls back to zero rather than dividing by zero.
     */
    @Test
    void testApply_ZeroHtLine_RateFallback() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "E1";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(BigDecimal.ZERO, new BigDecimal("20.00"), new BigDecimal("0.20"))));
        Collection<AdvantageApplication> discounts = ticketApplier().apply(evaluation);
        assertEquals(1, discounts.size());
        // VAT of the line = 20.00 - 0.00 = 20.00.
        assertEquals(new BigDecimal("20.00"), ((VatRefundDiscountFactory.VatRefundDiscountApplication)
                discounts.iterator().next()).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests that an unpriced basket line is skipped when computing the sandbox score.
     */
    @Test
    void testEfficiencyScore_UnpricedLine_Skipped() {
        setUpDatabase();
        DomainUtils.createAndPersistProduct("VR_NOPRICE", "VR_NOPRICE", ProductType.UNIT);
        Basket b = basket(DomainUtils.createItem("VR_NOPRICE", 1.0));
        VatRefundDiscountFactory.VatRefundDiscountApplier applier =
                new VatRefundDiscountFactory.VatRefundDiscountApplier(
                        "VRU", VatRefundDiscountFactory.Scope.TICKET, List.of(), b, store);
        assertEquals(0.0, applier.getEfficiencyScore());
    }

    // --------------------------------------------------
    // End-to-end (normative and arbitration)
    // --------------------------------------------------

    /**
     * Tests the normative example end to end: a 120.00 TTC / 20 % basket yields a 20.00
     * discount, a 100.00 paid total, and a fiscal VAT of 16.67 in the untouched breakdown.
     */
    @Test
    void testEndToEnd_Normative_120_100_20() {
        setUpDatabase();
        seedProduct("VR_N", "100.00", "120.00", "0.20");
        DomainUtils.createAndPersistOffer("VR_E2E", store, "VAT_REFUND_DISCOUNT", "{ \"scope\": \"TICKET\" }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("VR_N", 1.0)));
        assertEquals(0, new BigDecimal("20.00").compareTo(totalDiscount(evaluation)));
        assertEquals(0, new BigDecimal("100.00").compareTo(evaluation.getTotalPrice().amountIncludingTax));
        BasketEvaluation.VatLine line = evaluation.getVatBreakdown().stream()
                .filter(l -> l.vatRate.compareTo(new BigDecimal("0.2000")) == 0)
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("16.67").compareTo(line.vatAmount));
    }

    /**
     * Tests the inherited limits dimension: maxApplicationsPerTicket caps the applications.
     */
    @Test
    void testArbitration_MaxApplicationsPerTicket() {
        setUpDatabase();
        seedProduct("VR_L1", "100.00", "120.00", "0.20");
        seedProduct("VR_L2", "100.00", "120.00", "0.20");
        DomainUtils.createAndPersistOffer("VR_LIM", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"TICKET\", \"arbitration\": { \"maxApplicationsPerTicket\": 1 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("VR_L1", 1.0), DomainUtils.createItem("VR_L2", 1.0)));
        long count = evaluation.getAdvantages().stream()
                .filter(a -> a instanceof VatRefundDiscountFactory.VatRefundDiscountApplication).count();
        assertEquals(1, count);
    }

    /**
     * Tests the inherited open-basket dimension: an AT_TOTAL VAT refund does not fall on an
     * open basket.
     */
    @Test
    void testArbitration_OpenBasket_NotApplied() {
        setUpDatabase();
        seedProduct("VR_OB", "100.00", "120.00", "0.20");
        DomainUtils.createAndPersistOffer("VR_OBX", store, "VAT_REFUND_DISCOUNT", "{ \"scope\": \"TICKET\" }");
        Basket b = basket(DomainUtils.createItem("VR_OB", 1.0));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited cumul dimension: a non-cumulable VAT refund bars a second
     * non-cumulable advantage.
     */
    @Test
    void testArbitration_NonCumulable() {
        setUpDatabase();
        seedProduct("VR_NC", "100.00", "120.00", "0.20");
        DomainUtils.createAndPersistOffer("VR_NC1", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"TICKET\", \"arbitration\": { \"priority\": 10, \"cumulable\": false } }");
        DomainUtils.createAndPersistOffer("VR_NC2", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"TICKET\", \"arbitration\": { \"priority\": 20, \"cumulable\": false } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("VR_NC", 1.0)));
        // Only one VAT refund applies: 20.00.
        assertEquals(0, new BigDecimal("20.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the inherited consumption dimension: a triggered VAT refund that consumes its
     * contributors withdraws them from a second advantage.
     */
    @Test
    void testArbitration_ConsumesContributors() {
        setUpDatabase();
        seedProduct("VR_CC", "100.00", "120.00", "0.20");
        DomainUtils.createAndPersistOffer("VR_CC1", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"VR_CC\"], "
                        + "\"arbitration\": { \"priority\": 10, \"consumesContributors\": true }, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"VR_CC\"], \"threshold\": 1 } ] } }");
        DomainUtils.createAndPersistOffer("VR_CC2", store, "VAT_REFUND_DISCOUNT",
                "{ \"scope\": \"ITEMS\", \"targetEans\": [\"VR_CC\"], "
                        + "\"arbitration\": { \"priority\": 20 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("VR_CC", 1.0)));
        // Only the first applies (20.00); the second sees no available line.
        assertEquals(0, new BigDecimal("20.00").compareTo(totalDiscount(evaluation)));
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
