package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
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
import com.intermarche.valuation.engine.TierTable;
import com.intermarche.valuation.engine.ValuationEngine;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AntiWasteDiscountFactory} using the real database.
 * <p>
 * The server clock is fixed to a known day so the remaining-days arithmetic is deterministic.
 * Factory-level tests exercise the schema and the duplicate-threshold cross rule; applier-level
 * tests assert the strictest-tier resolution (including expired lines and skipped lines);
 * end-to-end tests cover the per-line discount and the inherited arbitration.
 */
@QuarkusTest
@TestTransaction
public class AntiWasteDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    AntiWasteDiscountFactory factory;

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
     * The fixed reference day used by every test.
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    /**
     * Seeds the store and fixes the server clock to {@link #TODAY} noon.
     */
    private void setUpDatabase() {
        DateTimeProvider.setFixedDateTime(TODAY.atTime(12, 0));
        store = DomainUtils.createAndPersistStore("STORE_AW", 48.8566, 2.352214);
    }

    /**
     * Clears the fixed clock so the static provider does not leak into other tests.
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
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
     * Returns the ISO date string a given number of days after the fixed today.
     *
     * @param plusDays the offset in days (may be negative).
     * @return the ISO date string.
     */
    private static String date(int plusDays) {
        return TODAY.plusDays(plusDays).toString();
    }

    /**
     * Builds a basket item carrying a best-before date.
     *
     * @param lineId         the line id.
     * @param ean            the product EAN.
     * @param quantity       the quantity.
     * @param bestBeforeDate the best-before date, ISO; may be null.
     * @return the basket item.
     */
    private static Basket.Item dlcItem(String lineId, String ean, double quantity, String bestBeforeDate) {
        Basket.Item item = new Basket.Item();
        item.lineId = lineId;
        item.produceEan = ean;
        item.quantity = BigDecimal.valueOf(quantity);
        item.bestBeforeDate = bestBeforeDate;
        return item;
    }

    /**
     * Builds an evaluation whose basket carries the given lines.
     *
     * @param items the basket lines.
     * @return the evaluation under test.
     */
    private BasketEvaluation evaluationOf(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_AW";
        basket.items = new ArrayList<>(List.of(items));
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
        basket.storeCode = "STORE_AW";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds the standard two-tier applier (3 days → 30%, 1 day → 50%) on the inverted axis.
     *
     * @param targetEans     the restricted EANs (empty for any line).
     * @param targetProducts the restricted products.
     * @return the applier under test.
     */
    private AntiWasteDiscountFactory.AntiWasteDiscountApplier applier(Set<String> targetEans, List<Product> targetProducts) {
        // offset = 3 (largest maxRemainingDays): 3-day → threshold 0 (30%), 1-day → threshold 2 (50%).
        TierTable<BigDecimal> table = TierTable.of(List.of(
                new TierTable.Tier<>(BigDecimal.ZERO, new BigDecimal("30")),
                new TierTable.Tier<>(new BigDecimal("2"), new BigDecimal("50"))));
        return new AntiWasteDiscountFactory.AntiWasteDiscountApplier(
                "AW1", targetEans, targetProducts, table, 3L, null, null);
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
     * Applies the given applier and returns the single discount amount, tax included.
     *
     * @param evaluation the evaluation to apply on.
     * @param applier    the applier.
     * @return the discount amount of the single produced application.
     */
    private BigDecimal singleDiscount(BasketEvaluation evaluation,
                                      AntiWasteDiscountFactory.AntiWasteDiscountApplier applier) {
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        return ((AntiWasteDiscountFactory.AntiWasteDiscountApplication) discounts.iterator().next())
                .getDiscountAmount().amountIncludingTax;
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
        DomainUtils.createAndPersistOffer("AW_OK", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 }, "
                        + "{ \"maxRemainingDays\": 1, \"percent\": 50 } ] }");
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluationOf());
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof AntiWasteDiscountFactory.AntiWasteDiscountApplier);
    }

    /**
     * Tests that a duplicate maxRemainingDays is rejected at creation.
     */
    @Test
    void testBuildAppliers_DuplicateThreshold_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("AW_DUP", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 }, "
                        + "{ \"maxRemainingDays\": 3, \"percent\": 50 } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluationOf()));
    }

    /**
     * Tests that a specification without tiers is rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingTiers_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("AW_NT", store, "ANTI_WASTE_DISCOUNT", "{ }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluationOf()));
    }

    /**
     * Tests that a percentage above 100 is rejected by the schema.
     */
    @Test
    void testBuildAppliers_PercentAbove100_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("AW_HI", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 150 } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluationOf()));
    }

    /**
     * Tests that a negative maxRemainingDays is rejected by the schema.
     */
    @Test
    void testBuildAppliers_NegativeMaxDays_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("AW_NEG", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": -1, \"percent\": 30 } ] }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluationOf()));
    }

    /**
     * Tests that a specification with target EANs builds successfully.
     */
    @Test
    void testBuildAppliers_WithTargetEans() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("AW_TE", store, "ANTI_WASTE_DISCOUNT",
                "{ \"targetEans\": [\"MILK\"], \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 } ] }");
        assertEquals(1, factory.buildAppliers(evaluationOf()).size());
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
     * Tests the loose tier: two days left reaches the 3-day tier only, for a 30% discount.
     */
    @Test
    void testApply_LooseTier_30Percent() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(2)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertEquals(new BigDecimal("3.00"), singleDiscount(evaluation, applier(Set.of(), List.of())));
    }

    /**
     * Tests the strict tier: one day left reaches the 1-day tier, for a 50% discount.
     */
    @Test
    void testApply_StrictTier_50Percent() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(1)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertEquals(new BigDecimal("5.00"), singleDiscount(evaluation, applier(Set.of(), List.of())));
    }

    /**
     * Tests that an expired line is treated as the strictest tier (50%).
     */
    @Test
    void testApply_Expired_StrictestTier() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(-2)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertEquals(new BigDecimal("5.00"), singleDiscount(evaluation, applier(Set.of(), List.of())));
    }

    /**
     * Tests that a line whose remaining days exceed every tier gets no discount.
     */
    @Test
    void testApply_TooFar_NoDiscount() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(10)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertTrue(applier(Set.of(), List.of()).apply(evaluation).isEmpty());
    }

    /**
     * Tests that a line without a best-before date is silently skipped.
     */
    @Test
    void testApply_NoDlc_Skipped() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, null));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertTrue(applier(Set.of(), List.of()).apply(evaluation).isEmpty());
    }

    /**
     * Tests that a line outside the target EANs is skipped when the discount is restricted.
     */
    @Test
    void testApply_OutsideTargetEans_Skipped() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(1)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertTrue(applier(Set.of("OTHER"), List.of()).apply(evaluation).isEmpty());
    }

    /**
     * Tests that only the targeted line among several is discounted.
     */
    @Test
    void testApply_TargetedLineOnly() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(
                dlcItem("L1", "MILK", 1.0, date(1)),
                dlcItem("L2", "BREAD", 1.0, date(1)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        evaluation.getOffers().add(new ValuedStub("L2", "BREAD", 1.0, "4.00"));
        Collection<AdvantageApplication> discounts = applier(Set.of("MILK"), List.of()).apply(evaluation);
        assertEquals(1, discounts.size());
        AntiWasteDiscountFactory.AntiWasteDiscountApplication app =
                (AntiWasteDiscountFactory.AntiWasteDiscountApplication) discounts.iterator().next();
        assertEquals("MILK", app.getEan());
        assertEquals(new BigDecimal("5.00"), app.getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests that a consumed offer application leaves the assiette.
     */
    @Test
    void testApply_ConsumedOffer_Excluded() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(1)));
        ValuedStub stub = new ValuedStub("L1", "MILK", 1.0, "10.00");
        evaluation.getOffers().add(stub);
        evaluation.markConsumed(List.of(stub));
        assertTrue(applier(Set.of(), List.of()).apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applicability, score, getters
    // --------------------------------------------------

    /**
     * Tests that the unrestricted discount is applicable to any product-aware applier.
     */
    @Test
    void testIsApplicable_Unrestricted_True() {
        setUpDatabase();
        assertTrue(applier(Set.of(), List.of()).isApplicable(new FakeProductApplier("MILK")));
    }

    /**
     * Tests that the restricted discount is applicable only to covering appliers, and never to
     * a non-product-aware one.
     */
    @Test
    void testIsApplicable_Restricted() {
        setUpDatabase();
        Product milk = new Product();
        milk.ean = "MILK";
        assertTrue(applier(Set.of("MILK"), List.of(milk)).isApplicable(new FakeProductApplier("MILK")));
        assertFalse(applier(Set.of("MILK"), List.of(milk)).isApplicable(new FakeProductApplier("OTHER")));
        assertFalse(applier(Set.of(), List.of()).isApplicable(new NonProductApplier()));
    }

    /**
     * Tests the sandbox score: the summed discount on the basket lines carrying an eligible
     * best-before date, at the reference price.
     */
    @Test
    void testEfficiencyScore_SandboxFromBasket() {
        setUpDatabase();
        seedProduct("AW_S", "10.00");
        TierTable<BigDecimal> table = TierTable.of(List.of(
                new TierTable.Tier<>(BigDecimal.ZERO, new BigDecimal("30")),
                new TierTable.Tier<>(new BigDecimal("2"), new BigDecimal("50"))));
        Basket b = basket(dlcItem("L1", "AW_S", 1.0, date(1)));
        AntiWasteDiscountFactory.AntiWasteDiscountApplier applier =
                new AntiWasteDiscountFactory.AntiWasteDiscountApplier("AWS", Set.of(), List.of(), table, 3L, b, store);
        // 1 day left → 50% of 10.00 = 5.00.
        assertEquals(0, new BigDecimal("5.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests that the sandbox score is zero without a basket or store.
     */
    @Test
    void testEfficiencyScore_NoBasketOrStore_Zero() {
        setUpDatabase();
        assertEquals(0.0, applier(Set.of(), List.of()).getEfficiencyScore());
    }

    /**
     * Tests the application getters: type, EAN, and application-moment round-trip.
     */
    @Test
    void testApplication_Getters() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(1)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        AntiWasteDiscountFactory.AntiWasteDiscountApplication app =
                (AntiWasteDiscountFactory.AntiWasteDiscountApplication)
                        applier(Set.of(), List.of()).apply(evaluation).iterator().next();
        assertEquals("Anti-Waste: AW1 (MILK)", app.getType());
        assertEquals("MILK", app.getEan());
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
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(1)));
        ValuedStub stub = new ValuedStub("L1", "MILK", 1.0, "10.00");
        evaluation.getOffers().add(stub);
        AntiWasteDiscountFactory.AntiWasteDiscountApplication app =
                (AntiWasteDiscountFactory.AntiWasteDiscountApplication)
                        applier(Set.of(), List.of()).apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * Tests that a malformed best-before date is treated as no date and silently skipped.
     */
    @Test
    void testApply_MalformedDate_Skipped() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, "not-a-date"));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "10.00"));
        assertTrue(applier(Set.of(), List.of()).apply(evaluation).isEmpty());
    }

    /**
     * Tests that a valued line with a zero amount is skipped.
     */
    @Test
    void testApply_ZeroAmountLine_Skipped() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(1)));
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "0.00"));
        assertTrue(applier(Set.of(), List.of()).apply(evaluation).isEmpty());
    }

    /**
     * Tests that a discount rounding down to zero produces no application.
     */
    @Test
    void testApply_DiscountRoundsToZero_Skipped() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(dlcItem("L1", "MILK", 1.0, date(2)));
        // 30% of 0.01 = 0.003, which rounds to 0.00.
        evaluation.getOffers().add(new ValuedStub("L1", "MILK", 1.0, "0.01"));
        assertTrue(applier(Set.of(), List.of()).apply(evaluation).isEmpty());
    }

    /**
     * Tests the sandbox skip branches: an out-of-target line, a line without a date, a line too
     * far from expiry, and an unpriced line are all skipped; only the eligible priced line
     * sets the score.
     */
    @Test
    void testEfficiencyScore_SandboxSkipBranches() {
        setUpDatabase();
        seedProduct("AW_PRICED", "10.00");
        DomainUtils.createAndPersistProduct("AW_NOPRICE", "AW_NOPRICE", ProductType.UNIT);
        TierTable<BigDecimal> table = TierTable.of(List.of(
                new TierTable.Tier<>(BigDecimal.ZERO, new BigDecimal("30")),
                new TierTable.Tier<>(new BigDecimal("2"), new BigDecimal("50"))));
        Basket b = basket(
                dlcItem("L1", "AW_OTHER", 1.0, date(1)),
                dlcItem("L2", "AW_PRICED", 1.0, null),
                dlcItem("L3", "AW_PRICED", 1.0, date(10)),
                dlcItem("L4", "AW_NOPRICE", 1.0, date(1)),
                dlcItem("L5", "AW_PRICED", 1.0, date(1)));
        AntiWasteDiscountFactory.AntiWasteDiscountApplier applier =
                new AntiWasteDiscountFactory.AntiWasteDiscountApplier(
                        "AWX", Set.of("AW_PRICED", "AW_NOPRICE"), List.of(), table, 3L, b, store);
        // Only L5 counts: 50% of 10.00 = 5.00.
        assertEquals(0, new BigDecimal("5.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    // --------------------------------------------------
    // End-to-end (per-line discount and arbitration)
    // --------------------------------------------------

    /**
     * Tests the end-to-end per-line discount: a two-days-left line gets 30% off.
     */
    @Test
    void testEndToEnd_PerLine() {
        setUpDatabase();
        seedProduct("AW_MILK", "10.00");
        DomainUtils.createAndPersistOffer("AW_E2E", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 }, "
                        + "{ \"maxRemainingDays\": 1, \"percent\": 50 } ] }");
        BasketEvaluation evaluation = engine.evaluate(basket(dlcItem("L1", "AW_MILK", 1.0, date(2))));
        assertEquals(0, new BigDecimal("3.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the inherited open-basket dimension: an AT_TOTAL anti-waste discount does not fall
     * on an open basket.
     */
    @Test
    void testArbitration_OpenBasket_NotApplied() {
        setUpDatabase();
        seedProduct("AW_OB", "10.00");
        DomainUtils.createAndPersistOffer("AW_OBX", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 } ] }");
        Basket b = basket(dlcItem("L1", "AW_OB", 1.0, date(2)));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited cumul dimension: a non-cumulable anti-waste discount bars a second
     * non-cumulable advantage.
     */
    @Test
    void testArbitration_NonCumulable() {
        setUpDatabase();
        seedProduct("AW_NC", "10.00");
        DomainUtils.createAndPersistOffer("AW_NC1", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 } ], "
                        + "\"arbitration\": { \"priority\": 10, \"cumulable\": false } }");
        DomainUtils.createAndPersistOffer("AW_NC2", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 50, "
                        + "\"arbitration\": { \"priority\": 20, \"cumulable\": false } }");
        BasketEvaluation evaluation = engine.evaluate(basket(dlcItem("L1", "AW_NC", 1.0, date(2))));
        // Only the anti-waste (higher priority) applies: 30% of 10.00 = 3.00.
        assertEquals(0, new BigDecimal("3.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the inherited limits dimension: maxApplicationsPerTicket caps the applications.
     */
    @Test
    void testArbitration_MaxApplicationsPerTicket() {
        setUpDatabase();
        seedProduct("AW_L1", "10.00");
        seedProduct("AW_L2", "10.00");
        DomainUtils.createAndPersistOffer("AW_LIM", store, "ANTI_WASTE_DISCOUNT",
                "{ \"tiers\": [ { \"maxRemainingDays\": 3, \"percent\": 30 } ], "
                        + "\"arbitration\": { \"maxApplicationsPerTicket\": 1 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                dlcItem("L1", "AW_L1", 1.0, date(2)), dlcItem("L2", "AW_L2", 1.0, date(2))));
        long count = evaluation.getAdvantages().stream()
                .filter(a -> a instanceof AntiWasteDiscountFactory.AntiWasteDiscountApplication).count();
        assertEquals(1, count);
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * A product-aware application exposing a single valued item.
     */
    public static class ValuedStub implements ProductAwareOfferApplication {

        /**
         * The single valued item.
         */
        private final BasketEvaluation.Item valued;

        /**
         * Builds the stub.
         *
         * @param lineId   the source line id.
         * @param ean      the product EAN.
         * @param quantity the quantity.
         * @param ttc      the tax-included amount at 0% VAT.
         */
        public ValuedStub(String lineId, String ean, double quantity, String ttc) {
            this.valued = new BasketEvaluation.Item();
            this.valued.lineId = lineId;
            this.valued.produceEan = ean;
            this.valued.quantity = BigDecimal.valueOf(quantity);
            BigDecimal amount = new BigDecimal(ttc);
            this.valued.amount = new AmountEvaluation(amount, amount, BigDecimal.ZERO);
        }

        /**
         * Returns the whole application amount.
         *
         * @return the item amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return valued.amount;
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
         * Returns the valued result items.
         *
         * @return the single valued item.
         */
        @Override
        public List<BasketEvaluation.Item> getValuedItems() {
            return List.of(valued);
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "ValuedStub";
        }

        /**
         * Returns the amount attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the item amount when the EAN matches, null otherwise.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return (product != null && valued.produceEan.equals(product.ean)) ? valued.amount : null;
        }

        /**
         * Returns the quantity attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the quantity when the EAN matches, zero otherwise.
         */
        @Override
        public BigDecimal getProductQuantity(Product product) {
            return (product != null && valued.produceEan.equals(product.ean)) ? valued.quantity : BigDecimal.ZERO;
        }
    }

    /**
     * A product-aware offer applier stub applicable to a single EAN.
     */
    public static class FakeProductApplier extends OfferApplier
            implements com.intermarche.valuation.engine.ProductAwareOfferApplier {

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
