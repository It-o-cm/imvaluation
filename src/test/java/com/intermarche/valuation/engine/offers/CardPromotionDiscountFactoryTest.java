package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CardPromotionDiscountFactory} (spec §5, §6).
 * <p>
 * Applier-level tests feed hand-built product-aware applications into the evaluation and assert
 * the arithmetic to the cent (base netting, unique rounding, residue on the last line, VAT
 * split, weighed unit counting). Factory-level tests exercise the synthesis of transient offers,
 * the {@code CAGNOTTE} skip and the EAN-uniqueness rejection. End-to-end tests drive
 * {@link ValuationEngine#evaluate} for the demonstration fixtures, the store-promotion
 * interaction, the open-basket rule, determinism over fifty runs, and the no-op regression on a
 * basket without card promotions.
 */
@QuarkusTest
@TestTransaction
public class CardPromotionDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    CardPromotionDiscountFactory factory;

    /**
     * The engine, injected for the end-to-end scenarios.
     */
    @Inject
    ValuationEngine engine;

    /**
     * Mapper used to assert bit-for-bit evaluation equality (determinism, regression).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The store the products and offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by the database-backed tests; called manually because
     * {@code @TestTransaction} rolls back between tests.
     */
    private void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_CP", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a single DEFAULT price (excl. = incl., given VAT rate).
     *
     * @param ean     the product EAN.
     * @param ht      the price excluding tax.
     * @param ttc     the price including tax.
     * @param vatRate the VAT rate.
     */
    private void seedUnit(String ean, String ht, String ttc, String vatRate) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT,
                new BigDecimal(ht), new BigDecimal(ttc), new BigDecimal(vatRate));
    }

    /**
     * Builds a basket on the seeded store from the given lines.
     *
     * @param items the basket lines.
     * @return the basket.
     */
    private Basket basket(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_CP";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds an evaluation on a store-less basket for the pure applier tests.
     * <p>
     * The applier arithmetic reads the offer applications fed into the evaluation, never the
     * store, so no seeded store is needed: a null store code keeps the evaluation independent of
     * the database.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        return new BasketEvaluation(new Basket());
    }

    /**
     * Builds a card promotion entry.
     *
     * @param ean     the targeted EAN.
     * @param type    the promotion type ({@code PERCENT} or {@code AMOUNT}).
     * @param value   the promotion value.
     * @param benefit the benefit ({@code null}, {@code IMMEDIATE_DISCOUNT} or {@code CAGNOTTE}).
     * @param label   the optional label.
     * @return the card promotion.
     */
    private Basket.CardPromotion promotion(String ean, String type, String value, String benefit, String label) {
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = ean;
        promotion.promotionType = type;
        promotion.value = new BigDecimal(value);
        promotion.benefit = benefit;
        promotion.label = label;
        return promotion;
    }

    /**
     * Builds a direct applier (no basket, so a zero sandbox score) for the arithmetic tests.
     *
     * @param product the targeted product (its EAN and type drive the logic).
     * @param type    the promotion type.
     * @param value   the promotion value.
     * @param label   the optional label.
     * @return the applier under test.
     */
    private CardPromotionDiscountFactory.CardPromotionDiscountApplier applier(
            Product product, String type, String value, String label) {
        return new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                "CARD_PROMO:" + product.ean, "2990000000019", product.ean, product, type,
                new BigDecimal(value), label, null, null);
    }

    /**
     * Builds a UNIT product carrying only an EAN and a type (no DB row).
     *
     * @param ean the product EAN.
     * @return the in-memory product.
     */
    private Product unitProduct(String ean) {
        Product product = new Product();
        product.ean = ean;
        product.productType = ProductType.UNIT;
        return product;
    }

    /**
     * Sums the tax-included amount of every card-promotion discount of an evaluation.
     *
     * @param evaluation the evaluation.
     * @return the total card-promotion discount, tax included.
     */
    private BigDecimal cardDiscountTotal(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication advantage : evaluation.getAdvantages()) {
            if (advantage instanceof CardPromotionDiscountFactory.CardPromotionDiscountApplication discount
                    && discount.getDiscountAmount() != null) {
                total = total.add(discount.getDiscountAmount().amountIncludingTax);
            }
        }
        return total;
    }

    /**
     * Counts the card-promotion advantages of an evaluation.
     *
     * @param evaluation the evaluation.
     * @return the number of card-promotion applications.
     */
    private long cardDiscountCount(BasketEvaluation evaluation) {
        return evaluation.getAdvantages().stream()
                .filter(a -> a instanceof CardPromotionDiscountFactory.CardPromotionDiscountApplication)
                .count();
    }

    // --------------------------------------------------
    // §5 edge cases — applier arithmetic
    // --------------------------------------------------

    /**
     * §5.1: two available lines of the same EAN yield a single reduction, its base summed, its
     * rounding unique, and the residue on the last line. Bases 3.33 and 3.34 (sum 6.67) at 10%
     * give 0.67, split 0.33 then 0.34 (residue).
     */
    @Test
    void testApply_TwoLinesSameEan_SummedBaseUniqueRoundingResidueLast() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("3.33"), new BigDecimal("3.33"), BigDecimal.ZERO)));
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("3.34"), new BigDecimal("3.34"), BigDecimal.ZERO)));
        List<AdvantageApplication> discounts = new ArrayList<>(applier(product, "PERCENT", "0.10", null).apply(evaluation));
        assertEquals(2, discounts.size());
        BigDecimal first = ((DiscountApplication) discounts.get(0)).getDiscountAmount().amountIncludingTax;
        BigDecimal last = ((DiscountApplication) discounts.get(1)).getDiscountAmount().amountIncludingTax;
        assertEquals(new BigDecimal("0.33"), first);
        assertEquals(new BigDecimal("0.34"), last);
        assertEquals(new BigDecimal("0.67"), first.add(last));
    }

    /**
     * §5.2: an AMOUNT of 0.50 on a weighed line of 0.738 kg reduces 0.50 (the weighing counts
     * one), within the net base of the line.
     */
    @Test
    void testApply_AmountOnWeighedLine_CountsOne() {
        Product product = new Product();
        product.ean = "TOMATO";
        product.productType = ProductType.WEIGHT;
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 0.738,
                new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.00"), BigDecimal.ZERO)));
        List<AdvantageApplication> discounts = new ArrayList<>(applier(product, "AMOUNT", "0.50", null).apply(evaluation));
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("0.50"),
                ((DiscountApplication) discounts.get(0)).getDiscountAmount().amountIncludingTax);
    }

    /**
     * §5.2 cap: an AMOUNT larger than the weighed line's net base is capped at the base, never
     * negative.
     */
    @Test
    void testApply_AmountOnWeighedLine_CappedAtBase() {
        Product product = new Product();
        product.ean = "TOMATO";
        product.productType = ProductType.WEIGHT;
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 0.100,
                new AmountEvaluation(new BigDecimal("0.30"), new BigDecimal("0.30"), BigDecimal.ZERO)));
        List<AdvantageApplication> discounts = new ArrayList<>(applier(product, "AMOUNT", "0.50", null).apply(evaluation));
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("0.30"),
                ((DiscountApplication) discounts.get(0)).getDiscountAmount().amountIncludingTax);
    }

    /**
     * §5.3: an AMOUNT of 0.50 on a quantity of 9 reduces 4.50, the distribution being trivial on
     * the single covering line.
     */
    @Test
    void testApply_AmountTimesNineUnits() {
        Product product = unitProduct("PACK");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 9.0,
                new AmountEvaluation(new BigDecimal("9.00"), new BigDecimal("9.00"), BigDecimal.ZERO)));
        List<AdvantageApplication> discounts = new ArrayList<>(applier(product, "AMOUNT", "0.50", null).apply(evaluation));
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("4.50"),
                ((DiscountApplication) discounts.get(0)).getDiscountAmount().amountIncludingTax);
    }

    /**
     * §5.4: a PERCENT on a line already discounted to a net base of zero produces nothing,
     * silently.
     */
    @Test
    void testApply_PercentOnFullyDiscountedLine_Nothing() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertTrue(applier(product, "PERCENT", "0.10", null).apply(evaluation).isEmpty());
    }

    /**
     * §5.4 (netting): a PERCENT measures the base net of a discount already retained against the
     * same line — 6.00 gross reduced by a 2.00 prior discount is a 4.00 base, so 10% is 0.40.
     */
    @Test
    void testApply_PercentMeasuredNetOfRetainedDiscount() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 2.0,
                new AmountEvaluation(new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO));
        evaluation.getOffers().add(stub);
        evaluation.getAdvantages().add(new CardPromotionDiscountFactory.CardPromotionDiscountApplication(
                "PRIOR", "CAFE", null, null, stub,
                new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.00"), BigDecimal.ZERO)));
        List<AdvantageApplication> discounts = new ArrayList<>(applier(product, "PERCENT", "0.10", null).apply(evaluation));
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("0.40"),
                ((DiscountApplication) discounts.get(0)).getDiscountAmount().amountIncludingTax);
    }

    /**
     * §5.8: a PERCENT value of 1 is legal (-100%, product offered): the whole net base is
     * reduced.
     */
    @Test
    void testApply_PercentValueOne_FullBaseOffered() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("2.50"), new BigDecimal("2.50"), BigDecimal.ZERO)));
        List<AdvantageApplication> discounts = new ArrayList<>(applier(product, "PERCENT", "1", null).apply(evaluation));
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("2.50"),
                ((DiscountApplication) discounts.get(0)).getDiscountAmount().amountIncludingTax);
    }

    /**
     * VAT split: a PERCENT of 10% on a 10.00 TTC base at 20% VAT reduces 1.00 TTC, 0.83 HT.
     */
    @Test
    void testApply_PercentWithVat_SplitToTheCent() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("8.33"), new BigDecimal("10.00"), new BigDecimal("0.20"))));
        AmountEvaluation amount = ((DiscountApplication) applier(product, "PERCENT", "0.10", null)
                .apply(evaluation).iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("1.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.83"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * A reduction that rounds down to zero on a non-empty base produces nothing: a 10% PERCENT
     * on a 0.01 base rounds to 0.00.
     */
    @Test
    void testApply_ReductionRoundsToZero_Nothing() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ZERO)));
        assertTrue(applier(product, "PERCENT", "0.10", null).apply(evaluation).isEmpty());
    }

    /**
     * §5.6: an unknown EAN (no catalog product) makes the promotion produce nothing, silently.
     */
    @Test
    void testApply_UnknownProduct_Nothing() {
        CardPromotionDiscountFactory.CardPromotionDiscountApplier applier =
                new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                        "CARD_PROMO:GHOST", "2990000000019", "GHOST", null, "AMOUNT",
                        new BigDecimal("0.50"), null, null, null);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(unitProduct("CAFE"), 1.0,
                new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO)));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * §5.6: a non-product-aware application (a service line) is never part of the assiette.
     */
    @Test
    void testApply_NonProductAwareApplication_Ignored() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ServiceStub(new BigDecimal("10.00")));
        assertTrue(applier(product, "AMOUNT", "0.50", null).apply(evaluation).isEmpty());
    }

    /**
     * A covered product with a zero quantity is skipped.
     */
    @Test
    void testApply_ZeroQuantity_Nothing() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 0.0,
                new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO)));
        assertTrue(applier(product, "AMOUNT", "0.50", null).apply(evaluation).isEmpty());
    }

    /**
     * A covered product with a null attributed amount is skipped.
     */
    @Test
    void testApply_NullProductAmount_Nothing() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0, null));
        assertTrue(applier(product, "AMOUNT", "0.50", null).apply(evaluation).isEmpty());
    }

    /**
     * §5.4.4: a consumed offer application leaves the assiette (spec §4.4).
     */
    @Test
    void testApply_ConsumedOffer_Excluded() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO));
        evaluation.getOffers().add(stub);
        evaluation.markConsumed(List.of(stub));
        assertTrue(applier(product, "AMOUNT", "0.50", null).apply(evaluation).isEmpty());
    }

    /**
     * An empty assiette (no offers) produces nothing.
     */
    @Test
    void testApply_EmptyAssiette_Nothing() {
        Product product = unitProduct("CAFE");
        assertTrue(applier(product, "AMOUNT", "0.50", null).apply(newEvaluation()).isEmpty());
    }

    // --------------------------------------------------
    // §5 edge cases — applier metadata
    // --------------------------------------------------

    /**
     * The output type is {@code Card Promotion: CARD_PROMO:<ean>}, extended with the label when
     * one is provided, and the card number is carried for traceability.
     */
    @Test
    void testApplication_TypeLabelAndCardNumber() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO)));
        CardPromotionDiscountFactory.CardPromotionDiscountApplication withLabel =
                (CardPromotionDiscountFactory.CardPromotionDiscountApplication)
                        applier(product, "AMOUNT", "0.50", "Café Grand-Mère").apply(evaluation).iterator().next();
        assertEquals("Card Promotion: CARD_PROMO:CAFE — Café Grand-Mère", withLabel.getType());
        assertEquals("2990000000019", withLabel.getCardNumber());
        CardPromotionDiscountFactory.CardPromotionDiscountApplication noLabel =
                (CardPromotionDiscountFactory.CardPromotionDiscountApplication)
                        applier(product, "AMOUNT", "0.50", null).apply(evaluation).iterator().next();
        assertEquals("Card Promotion: CARD_PROMO:CAFE", noLabel.getType());
        assertEquals("CAFE", noLabel.discountedEan());
    }

    /**
     * The application-moment round-trip: AT_TOTAL by default, overwritten by the arbitration.
     */
    @Test
    void testApplication_ApplicationMomentRoundTrip() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO)));
        CardPromotionDiscountFactory.CardPromotionDiscountApplication app =
                (CardPromotionDiscountFactory.CardPromotionDiscountApplication)
                        applier(product, "AMOUNT", "0.50", null).apply(evaluation).iterator().next();
        assertEquals("AT_TOTAL", app.getApplicationMoment());
        app.setApplicationMoment("AT_TRIGGER");
        assertEquals("AT_TRIGGER", app.getApplicationMoment());
    }

    /**
     * The produced application exposes its targeted offer application.
     */
    @Test
    void testApplication_OfferApplicationTarget() {
        Product product = unitProduct("CAFE");
        BasketEvaluation evaluation = newEvaluation();
        ProductStub stub = new ProductStub(product, 1.0,
                new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO));
        evaluation.getOffers().add(stub);
        DiscountApplication app = (DiscountApplication)
                applier(product, "AMOUNT", "0.50", null).apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * isApplicable is true for a product-aware applier covering the product, false for a
     * non-covering one, a non-product-aware one, and always false when the product is unknown.
     */
    @Test
    void testIsApplicable_Branches() {
        Product product = unitProduct("CAFE");
        CardPromotionDiscountFactory.CardPromotionDiscountApplier applier = applier(product, "AMOUNT", "0.50", null);
        assertTrue(applier.isApplicable(new FakeProductApplier("CAFE")));
        assertFalse(applier.isApplicable(new FakeProductApplier("OTHER")));
        assertFalse(applier.isApplicable(new NonProductApplier()));
        CardPromotionDiscountFactory.CardPromotionDiscountApplier unknown =
                new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                        "CARD_PROMO:GHOST", null, "GHOST", null, "AMOUNT",
                        new BigDecimal("0.50"), null, null, null);
        assertFalse(unknown.isApplicable(new FakeProductApplier("GHOST")));
    }

    /**
     * The sandbox efficiency score is the reduction the promotion would produce on the current
     * basket at the reference price: an AMOUNT of 0.50 on 3 units gives 1.50.
     */
    @Test
    void testEfficiencyScore_SandboxFromBasket() {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        Product product = Product.findByEan("CAFE");
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO);
        Basket b = basket(DomainUtils.createItem("CAFE", 3.0));
        CardPromotionDiscountFactory.CardPromotionDiscountApplier applier =
                new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                        "CARD_PROMO:CAFE", "2990000000019", "CAFE", product, "AMOUNT",
                        new BigDecimal("0.50"), null, b, store);
        assertEquals(0, new BigDecimal("1.50").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * The sandbox score sums only the matching lines: a non-target EAN line and a target line
     * without a quantity are skipped, so a single café line of quantity 3 still scores 1.50.
     */
    @Test
    void testEfficiencyScore_SandboxSkipsNonMatchingAndNullQuantity() {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        seedUnit("THE", "2.00", "2.00", "0.00");
        Product product = Product.findByEan("CAFE");
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("1.50"), new BigDecimal("1.50"), BigDecimal.ZERO);
        Basket b = basket(
                DomainUtils.createItem("CAFE", 3.0),
                DomainUtils.createItem("THE", 1.0),
                DomainUtils.createItem("CAFE", null));
        CardPromotionDiscountFactory.CardPromotionDiscountApplier applier =
                new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                        "CARD_PROMO:CAFE", null, "CAFE", product, "AMOUNT",
                        new BigDecimal("0.50"), null, b, store);
        assertEquals(0, new BigDecimal("1.50").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * The sandbox score is zero without a basket or a store, and when the target line has no
     * reference price.
     */
    @Test
    void testEfficiencyScore_ZeroBranches() {
        setUpDatabase();
        Product ghost = unitProduct("GHOST");
        assertEquals(0.0, new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                "CARD_PROMO:GHOST", null, "GHOST", ghost, "AMOUNT",
                new BigDecimal("0.50"), null, null, null).getEfficiencyScore());
        DomainUtils.createAndPersistProduct("NOPRICE", "NOPRICE", ProductType.UNIT);
        Product noPrice = Product.findByEan("NOPRICE");
        Basket b = basket(DomainUtils.createItem("NOPRICE", 1.0));
        assertEquals(0.0, new CardPromotionDiscountFactory.CardPromotionDiscountApplier(
                "CARD_PROMO:NOPRICE", null, "NOPRICE", noPrice, "AMOUNT",
                new BigDecimal("0.50"), null, b, store).getEfficiencyScore());
    }

    // --------------------------------------------------
    // §2, §3 — factory: synthesis, CAGNOTTE skip, uniqueness
    // --------------------------------------------------

    /**
     * §3: an IMMEDIATE_DISCOUNT entry is synthesised into one applier whose transient
     * configuration carries the aligned {@code CARD_PROMO:<ean>} code, the type and the entry
     * JSON, and is never persisted.
     */
    @Test
    void testBuildAppliers_SynthesisesTransientOffer() {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0));
        b.cardPromotions = new ArrayList<>(List.of(
                promotion("CAFE", "AMOUNT", "0.50", "IMMEDIATE_DISCOUNT", "Café")));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(new BasketEvaluation(b));
        assertEquals(1, appliers.size());
        AdvantageApplier applier = appliers.iterator().next();
        Offer configuration = applier.getConfiguration();
        assertNotNull(configuration);
        assertEquals("CARD_PROMO:CAFE", configuration.code);
        assertEquals("CARD_PROMOTION_DISCOUNT", configuration.type);
        assertTrue(configuration.specification.contains("\"targetEan\":\"CAFE\""));
        assertNull(configuration.id);
    }

    /**
     * §2.1, §5.5: a CAGNOTTE entry is silently ignored — no applier, nothing applied.
     */
    @Test
    void testBuildAppliers_CagnotteEntry_Ignored() {
        setUpDatabase();
        seedUnit("THE", "2.00", "2.00", "0.00");
        Basket b = basket(DomainUtils.createItem("THE", 1.0));
        b.cardPromotions = new ArrayList<>(List.of(
                promotion("THE", "PERCENT", "0.10", "CAGNOTTE", "Thé")));
        assertTrue(factory.buildAppliers(new BasketEvaluation(b)).isEmpty());
    }

    /**
     * A null or absent cardPromotions block builds no applier.
     */
    @Test
    void testBuildAppliers_NoPromotions_Empty() {
        setUpDatabase();
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0));
        assertTrue(factory.buildAppliers(new BasketEvaluation(b)).isEmpty());
    }

    /**
     * A null entry and an entry without an EAN are tolerated by the build (neither can be
     * synthesised); an IMMEDIATE_DISCOUNT entry without a label still yields one applier whose
     * synthetic specification carries no {@code label}.
     */
    @Test
    void testBuildAppliers_NullEntryAndNoLabel_Tolerated() {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0));
        b.cardPromotions = new ArrayList<>();
        b.cardPromotions.add(null);
        b.cardPromotions.add(new Basket.CardPromotion());
        b.cardPromotions.add(promotion("CAFE", "AMOUNT", "0.50", "IMMEDIATE_DISCOUNT", null));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(new BasketEvaluation(b));
        assertEquals(1, appliers.size());
        assertFalse(appliers.iterator().next().getConfiguration().specification.contains("label"));
    }

    /**
     * A null basket is rejected with an explicit message before any synthesis.
     */
    @Test
    void testBuildAppliers_NoBasket_Throws() {
        BasketEvaluation eval = new BasketEvaluation(null) {
        };
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(eval));
    }

    /**
     * §5.7: a duplicate EAN in cardPromotions is rejected with the exact message, both when the
     * basket is validated directly and when the factory builds its appliers.
     */
    @Test
    void testBuildAppliers_DuplicateEan_Rejected() {
        setUpDatabase();
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0));
        b.cardPromotions = new ArrayList<>(List.of(
                promotion("CAFE", "AMOUNT", "0.50", null, null),
                promotion("CAFE", "PERCENT", "0.10", null, null)));
        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class, b::validateCardPromotions);
        assertEquals("Duplicate card promotion for EAN 'CAFE'", direct.getMessage());
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(new BasketEvaluation(b)));
    }

    /**
     * §5.8: a PERCENT value above 1 is rejected by the basket cross-rule validation.
     */
    @Test
    void testValidateCardPromotions_PercentAboveOne_Rejected() {
        Basket b = new Basket();
        b.cardPromotions = new ArrayList<>(List.of(promotion("CAFE", "PERCENT", "1.5", null, null)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, b::validateCardPromotions);
        assertTrue(error.getMessage().contains("PERCENT value greater than 1"));
    }

    /**
     * A null cardPromotions list, and a list with a null or EAN-less entry, are tolerated by the
     * uniqueness validation.
     */
    @Test
    void testValidateCardPromotions_NullAndBlankEntries_Tolerated() {
        Basket empty = new Basket();
        empty.validateCardPromotions();
        Basket b = new Basket();
        Basket.CardPromotion noEan = new Basket.CardPromotion();
        b.cardPromotions = new ArrayList<>();
        b.cardPromotions.add(null);
        b.cardPromotions.add(noEan);
        b.validateCardPromotions();
    }

    /**
     * A PERCENT entry with a null value, and an AMOUNT entry, both pass the uniqueness
     * validation (the ceiling rule only fires for a non-null PERCENT value above 1).
     */
    @Test
    void testValidateCardPromotions_NullValueAndAmount_Tolerated() {
        Basket b = new Basket();
        Basket.CardPromotion nullValue = new Basket.CardPromotion();
        nullValue.ean = "CAFE";
        nullValue.promotionType = "PERCENT";
        b.cardPromotions = new ArrayList<>(List.of(nullValue,
                promotion("THE", "AMOUNT", "5.0", null, null)));
        b.validateCardPromotions();
    }

    /**
     * §5.8: a PERCENT value of exactly 1 is accepted (a legal 100% offer).
     */
    @Test
    void testValidateCardPromotions_PercentExactlyOne_Accepted() {
        Basket b = new Basket();
        b.cardPromotions = new ArrayList<>(List.of(promotion("CAFE", "PERCENT", "1", null, null)));
        b.validateCardPromotions();
    }

    /**
     * §5.8 (schema): a PERCENT value above 1 is also rejected by the basket JSON schema, the
     * channel that answers the caller with a 400.
     */
    @Test
    void testBasketSchema_PercentAboveOne_Rejected() {
        String json = "{ \"storeCode\": \"S\", \"items\": [ { \"produceEan\": \"E\", \"quantity\": 1 } ], "
                + "\"cardPromotions\": [ { \"ean\": \"E\", \"promotionType\": \"PERCENT\", \"value\": 1.5 } ] }";
        assertThrows(IllegalArgumentException.class,
                () -> factory.processSpecification(Basket.BASKET_SCHEMA, json, node -> { }));
    }

    /**
     * §5.8 (schema): a PERCENT value within (0, 1] and an AMOUNT of any positive value are
     * accepted by the basket JSON schema.
     */
    @Test
    void testBasketSchema_ValidPromotions_Accepted() {
        String json = "{ \"storeCode\": \"S\", \"items\": [ { \"produceEan\": \"E\", \"quantity\": 1 } ], "
                + "\"cardNumber\": \"2990000000019\", "
                + "\"cardPromotions\": [ "
                + "{ \"ean\": \"E1\", \"promotionType\": \"PERCENT\", \"value\": 0.10, \"benefit\": \"CAGNOTTE\" }, "
                + "{ \"ean\": \"E2\", \"promotionType\": \"AMOUNT\", \"value\": 5.0, \"label\": \"X\" } ] }";
        factory.processSpecification(Basket.BASKET_SCHEMA, json, node -> { });
    }

    // --------------------------------------------------
    // §6 — end-to-end demonstration fixtures
    // --------------------------------------------------

    /**
     * §6 demonstration set (card 2990000000019): the café AMOUNT 0.50 IMMEDIATE_DISCOUNT is
     * applied (0.50 off), the thé PERCENT 0.10 CAGNOTTE is ignored (nothing).
     */
    @Test
    void testEndToEnd_DemonstrationSet_CafeAppliedTeaIgnored() {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        seedUnit("THE", "2.00", "2.00", "0.00");
        Basket b = demonstrationBasket();
        BasketEvaluation evaluation = engine.evaluate(b);
        assertEquals(1, cardDiscountCount(evaluation));
        assertEquals(0, new BigDecimal("0.50").compareTo(cardDiscountTotal(evaluation)));
        // Café 1.50 − 0.50 + thé 2.00 = 3.00.
        assertEquals(0, new BigDecimal("3.00").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * §5.9: on an open basket the AT_TOTAL card promotion does not fall during the scan.
     */
    @Test
    void testEndToEnd_OpenBasket_NotApplied() {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0));
        b.cardNumber = "2990000000019";
        b.cardPromotions = new ArrayList<>(List.of(promotion("CAFE", "AMOUNT", "0.50", null, null)));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertEquals(0, cardDiscountCount(evaluation));
        assertEquals(0, new BigDecimal("1.50").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * §6: a store immediate voucher and a card PERCENT stack on the same EAN; the wave order
     * makes the card PERCENT measure on the store-net base. A 10.00 line, a 2.00 store voucher
     * then a 10% card promotion, gives 2.00 + 0.80 = 2.80 total discount.
     */
    @Test
    void testEndToEnd_StorePromotionInteraction_PercentOnStoreNet() {
        setUpDatabase();
        seedUnit("CAFE", "10.00", "10.00", "0.00");
        DomainUtils.createAndPersistOffer("STORE_VOUCHER", store, "IMMEDIATE_VOUCHER",
                "{ \"targetOfferClass\": \"BasicOffer\", \"targetEans\": [\"CAFE\"], "
                        + "\"discountType\": \"FIXED_AMOUNT\", \"value\": 2.0 }");
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0));
        b.cardNumber = "2990000000019";
        b.cardPromotions = new ArrayList<>(List.of(promotion("CAFE", "PERCENT", "0.10", null, null)));
        BasketEvaluation evaluation = engine.evaluate(b);
        // Card promotion measures the 8.00 store-net base: 10% = 0.80.
        assertEquals(0, new BigDecimal("0.80").compareTo(cardDiscountTotal(evaluation)));
        // Total: 10.00 − 2.00 − 0.80 = 7.20.
        assertEquals(0, new BigDecimal("7.20").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * §6 determinism: fifty evaluations of the demonstration basket produce a bit-for-bit
     * identical response.
     *
     * @throws Exception if serialization fails.
     */
    @Test
    void testEndToEnd_DeterministicOverFiftyRuns() throws Exception {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        seedUnit("THE", "2.00", "2.00", "0.00");
        String reference = MAPPER.writeValueAsString(engine.evaluate(demonstrationBasket()));
        for (int run = 0; run < 50; run++) {
            assertEquals(reference, MAPPER.writeValueAsString(engine.evaluate(demonstrationBasket())));
        }
    }

    /**
     * §6 regression: a basket without cardPromotions is evaluated bit-for-bit identically whether
     * or not the field is present (a null block is a no-op), and carries no card advantage.
     *
     * @throws Exception if serialization fails.
     */
    @Test
    void testEndToEnd_NoCardPromotions_BitForBitIdentical() throws Exception {
        setUpDatabase();
        seedUnit("CAFE", "1.50", "1.50", "0.00");
        Basket without = basket(DomainUtils.createItem("CAFE", 1.0));
        Basket withEmpty = basket(DomainUtils.createItem("CAFE", 1.0));
        withEmpty.cardNumber = "2990000000019";
        withEmpty.cardPromotions = new ArrayList<>();
        BasketEvaluation e1 = engine.evaluate(without);
        BasketEvaluation e2 = engine.evaluate(withEmpty);
        assertEquals(0, cardDiscountCount(e1));
        assertEquals(0, cardDiscountCount(e2));
        assertEquals(MAPPER.writeValueAsString(e1), MAPPER.writeValueAsString(e2));
        assertEquals(0, new BigDecimal("1.50").compareTo(e1.getTotalPrice().amountIncludingTax));
    }

    /**
     * Builds the demonstration basket of card 2990000000019: a café and a thé line, an
     * IMMEDIATE_DISCOUNT AMOUNT on the café, and a CAGNOTTE PERCENT on the thé.
     *
     * @return the demonstration basket.
     */
    private Basket demonstrationBasket() {
        Basket b = basket(DomainUtils.createItem("CAFE", 1.0), DomainUtils.createItem("THE", 1.0));
        b.cardNumber = "2990000000019";
        b.cardPromotions = new ArrayList<>(List.of(
                promotion("CAFE", "AMOUNT", "0.50", "IMMEDIATE_DISCOUNT", "Café"),
                promotion("THE", "PERCENT", "0.10", "CAGNOTTE", "Thé")));
        return b;
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
