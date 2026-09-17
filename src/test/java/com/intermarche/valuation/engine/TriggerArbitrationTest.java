package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the C1 trigger brick and the C2 two-wave arbitration, run end to end
 * through {@link ValuationEngine#evaluate(Basket)} on a real database (spec §7 "Intégration").
 * <p>
 * Every scenario seeds its own store, products (each with a DEFAULT and a BASE_FOR_DISCOUNT
 * price, as the Basic valuation looks both up) and configurations, then evaluates a basket and
 * asserts on the applied advantages. Advantages are {@code TIERED_DISCOUNT} configurations, so
 * their trigger and arbitration blocks exercise the injected schema, the memoized triggers and
 * the wave-ordered application.
 */
@QuarkusTest
@TestTransaction
public class TriggerArbitrationTest {

    /**
     * The engine under test, injected as a CDI bean.
     */
    @Inject
    ValuationEngine engine;

    /**
     * The store every configuration and price is attached to.
     */
    private Store store;

    /**
     * Seeds the store shared by the scenario; called at the start of each test because
     * {@code @TestTransaction} rolls back between tests.
     */
    private void seedStore() {
        store = DomainUtils.createAndPersistStore("ARB_STORE", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a DEFAULT and a BASE_FOR_DISCOUNT price at a zero VAT rate, so
     * the tax-included amount equals the given price and the arithmetic stays exact.
     *
     * @param ean   the product EAN.
     * @param price the unit price, tax excluded and included alike.
     */
    private void seedProduct(String ean, String price) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        BigDecimal amount = new BigDecimal(price);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT,
                amount, amount, BigDecimal.ZERO);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                amount, amount, BigDecimal.ZERO);
    }

    /**
     * Seeds a TIERED_DISCOUNT configuration on the store.
     *
     * @param code          the configuration code.
     * @param specification the JSON specification.
     */
    private void seedTiered(String code, String specification) {
        DomainUtils.createAndPersistOffer(code, store, "TIERED_DISCOUNT", specification);
    }

    /**
     * Builds a basket on the seeded store from the given lines.
     *
     * @param items the basket lines.
     * @return the basket.
     */
    private Basket basket(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "ARB_STORE";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Sums the tax-included amount of every discount advantage of an evaluation.
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
     * A TIERED_DISCOUNT giving 15% on the frozen product, gated by "40€ of grocery" — the
     * canonical example (spec §5, §7).
     *
     * @return the specification.
     */
    private String frozenWhenGrocerySpec() {
        return "{ \"scope\": \"ITEMS\", \"targetEans\": [\"ARB_FROZ\"], \"metric\": \"AMOUNT\", "
                + "\"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 15 } } ], "
                + "\"trigger\": { \"conditions\": [ "
                + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"ITEMS\", \"eans\": [\"ARB_GROC\"], "
                + "\"threshold\": 40 } ] } }";
    }

    /**
     * Tests the canonical example when the trigger is satisfied: 40€ of grocery unlocks 15%
     * on the 10€ of frozen — a 1.50€ discount.
     */
    @Test
    void testCanonicalTriggerSatisfied() {
        seedStore();
        seedProduct("ARB_GROC", "20.00");
        seedProduct("ARB_FROZ", "10.00");
        seedTiered("ARB_FROZEN", frozenWhenGrocerySpec());
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_GROC", 2.0),
                DomainUtils.createItem("ARB_FROZ", 1.0)));
        assertFalse(evaluation.getAdvantages().isEmpty());
        assertEquals(0, new BigDecimal("1.50").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the canonical example when the trigger is not satisfied: 20€ of grocery is below
     * the 40€ threshold, so no discount applies (spec §5.3, satisfied side excluded).
     */
    @Test
    void testCanonicalTriggerNotSatisfied() {
        seedStore();
        seedProduct("ARB_GROC", "20.00");
        seedProduct("ARB_FROZ", "10.00");
        seedTiered("ARB_FROZEN", frozenWhenGrocerySpec());
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_GROC", 1.0),
                DomainUtils.createItem("ARB_FROZ", 1.0)));
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the canonical example with a satisfied trigger but an empty assiette: 40€ of
     * grocery, no frozen item, so nothing is produced and no error is raised (spec §5.3).
     */
    @Test
    void testCanonicalTriggerSatisfiedButAssietteEmpty() {
        seedStore();
        seedProduct("ARB_GROC", "20.00");
        seedProduct("ARB_FROZ", "10.00");
        seedTiered("ARB_FROZEN", frozenWhenGrocerySpec());
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_GROC", 2.0)));
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests that the threshold is reached inclusively: exactly 40€ of grocery unlocks the
     * discount (spec §5.1).
     */
    @Test
    void testExactThresholdIsReached() {
        seedStore();
        seedProduct("ARB_GROC", "40.00");
        seedProduct("ARB_FROZ", "10.00");
        seedTiered("ARB_FROZEN", frozenWhenGrocerySpec());
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_GROC", 1.0),
                DomainUtils.createItem("ARB_FROZ", 1.0)));
        assertEquals(0, new BigDecimal("1.50").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Builds a TICKET-scope TIERED_DISCOUNT specification, optionally carrying a trigger, an
     * application moment and an arbitration block.
     *
     * @param awardType   the award type ({@code PERCENTAGE} or {@code AMOUNT}).
     * @param value       the award value.
     * @param trigger     the trigger JSON, or {@code null} for none.
     * @param moment      the application moment, or {@code null} for the default.
     * @param arbitration the arbitration JSON, or {@code null} for the defaults.
     * @return the specification.
     */
    private String ticketSpec(String awardType, String value, String trigger, String moment, String arbitration) {
        StringBuilder spec = new StringBuilder("{ \"scope\": \"TICKET\", \"metric\": \"AMOUNT\", "
                + "\"mode\": \"HIGHEST_REACHED\", \"tiers\": [ { \"threshold\": 0, "
                + "\"award\": { \"type\": \"" + awardType + "\", \"value\": " + value + " } } ]");
        if (moment != null) {
            spec.append(", \"applicationMoment\": \"").append(moment).append("\"");
        }
        if (trigger != null) {
            spec.append(", \"trigger\": ").append(trigger);
        }
        if (arbitration != null) {
            spec.append(", \"arbitration\": ").append(arbitration);
        }
        return spec.append(" }").toString();
    }

    /**
     * Builds an ITEMS-scope TIERED_DISCOUNT giving a flat amount on one EAN, optionally with a
     * trigger and an arbitration block.
     *
     * @param ean         the targeted EAN.
     * @param amount      the flat amount awarded.
     * @param trigger     the trigger JSON, or {@code null}.
     * @param arbitration the arbitration JSON, or {@code null}.
     * @return the specification.
     */
    private String itemsAmountSpec(String ean, String amount, String trigger, String arbitration) {
        StringBuilder spec = new StringBuilder("{ \"scope\": \"ITEMS\", \"targetEans\": [\"" + ean + "\"], "
                + "\"metric\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", \"tiers\": [ { \"threshold\": 0, "
                + "\"award\": { \"type\": \"AMOUNT\", \"value\": " + amount + " } } ]");
        if (trigger != null) {
            spec.append(", \"trigger\": ").append(trigger);
        }
        if (arbitration != null) {
            spec.append(", \"arbitration\": ").append(arbitration);
        }
        return spec.append(" }").toString();
    }

    /**
     * A ticket-amount trigger of the given threshold.
     *
     * @param threshold the threshold.
     * @return the trigger JSON.
     */
    private String ticketTrigger(String threshold) {
        return "{ \"conditions\": [ { \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", "
                + "\"threshold\": " + threshold + " } ] }";
    }

    /**
     * An items-amount trigger on one EAN of the given threshold.
     *
     * @param ean       the targeted EAN.
     * @param threshold the threshold.
     * @return the trigger JSON.
     */
    private String itemsTrigger(String ean, String threshold) {
        return "{ \"conditions\": [ { \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"ITEMS\", "
                + "\"eans\": [\"" + ean + "\"], \"threshold\": " + threshold + " } ] }";
    }

    /**
     * Tests that a COUPON_CODE trigger is satisfied when the code is presented, once even when
     * presented twice (spec §5.7), and not satisfied when absent.
     */
    @Test
    void testCouponCodeTrigger() {
        seedStore();
        seedProduct("ARB_TICK", "50.00");
        seedTiered("ARB_COUPON",
                ticketSpec("PERCENTAGE", "10", "{ \"conditions\": [ { \"kind\": \"COUPON_CODE\", "
                        + "\"code\": \"HIVER24\" } ] }", null, null));
        Basket withCoupon = basket(DomainUtils.createItem("ARB_TICK", 1.0));
        withCoupon.couponCodes = List.of("HIVER24", "HIVER24");
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(engine.evaluate(withCoupon))));
        Basket withoutCoupon = basket(DomainUtils.createItem("ARB_TICK", 1.0));
        assertTrue(engine.evaluate(withoutCoupon).getAdvantages().isEmpty());
    }

    /**
     * Tests a MINIMUM_QUANTITY trigger: three units of the targeted product reach the
     * three-unit threshold (spec §3.2).
     */
    @Test
    void testMinimumQuantityTrigger() {
        seedStore();
        seedProduct("ARB_TICK", "10.00");
        seedTiered("ARB_QTY", ticketSpec("PERCENTAGE", "10",
                "{ \"conditions\": [ { \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"ARB_TICK\"], "
                        + "\"threshold\": 3 } ] }", null, null));
        assertTrue(engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 2.0)))
                .getAdvantages().isEmpty());
        assertEquals(0, new BigDecimal("3.00").compareTo(totalDiscount(
                engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 3.0))))));
    }

    /**
     * Tests the two waves and current amounts (spec §5.2): an AT_TRIGGER discount takes the
     * ticket from 52 to 48, so an AT_TOTAL discount gated on "≥ 50€ ticket" no longer applies.
     */
    @Test
    void testTwoWavesCurrentAmounts() {
        seedStore();
        seedProduct("ARB_TICK", "52.00");
        seedTiered("ARB_A_ATTRIGGER", ticketSpec("AMOUNT", "4", null, "AT_TRIGGER", null));
        seedTiered("ARB_B_ATTOTAL", ticketSpec("PERCENTAGE", "10",
                ticketTrigger("50"), "AT_TOTAL", null));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 1.0)));
        // Only the AT_TRIGGER 4€ applies; the AT_TOTAL 10% is starved by the current amount.
        assertEquals(0, new BigDecimal("4.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the open-basket guard (spec §5.4, §5.14): on an open basket an AT_TOTAL advantage
     * is silently dropped while an AT_TRIGGER advantage still applies.
     */
    @Test
    void testOpenBasketDropsAtTotalKeepsAtTrigger() {
        seedStore();
        seedProduct("ARB_TICK", "50.00");
        seedTiered("ARB_ATTOTAL", ticketSpec("AMOUNT", "5", null, "AT_TOTAL", null));
        seedTiered("ARB_ATTRIGGER", ticketSpec("AMOUNT", "3", null, "AT_TRIGGER", null));
        Basket open = basket(DomainUtils.createItem("ARB_TICK", 1.0));
        open.closed = false;
        // Open basket: only the AT_TRIGGER 3€ falls; the AT_TOTAL 5€ is silently dropped.
        assertEquals(0, new BigDecimal("3.00").compareTo(totalDiscount(engine.evaluate(open))));
        Basket closed = basket(DomainUtils.createItem("ARB_TICK", 1.0));
        // A basket without closed is closed (default): both waves run, 5 + 3 = 8.
        assertEquals(0, new BigDecimal("8.00").compareTo(totalDiscount(engine.evaluate(closed))));
    }

    /**
     * Tests the priority-starves-a-better-combination case (spec §5.13): a prioritized A at 6€
     * consumes the carriers that B and C at 3.5€ each would need, so only A applies — the
     * parametrized order wins over global optimality.
     */
    @Test
    void testPriorityStarvesBetterCombination() {
        seedStore();
        seedProduct("ARB_SHARE", "50.00");
        String trigger = itemsTrigger("ARB_SHARE", "40");
        String consuming = "{ \"consumesContributors\": true, \"priority\": 100 }";
        String plain = "{ \"consumesContributors\": true, \"priority\": 500 }";
        seedTiered("ARB_A", itemsAmountSpec("ARB_SHARE", "6", trigger, consuming));
        seedTiered("ARB_B", itemsAmountSpec("ARB_SHARE", "3.5", trigger, plain));
        seedTiered("ARB_C", itemsAmountSpec("ARB_SHARE", "3.5", trigger, plain));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_SHARE", 1.0)));
        // A (priority 100) applies first and consumes the carriers; B and C then measure zero.
        assertEquals(0, new BigDecimal("6.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests {@code cumulable:false} (spec §5.10): two non-cumulable advantages, the better
     * classified applies and the other is dropped by the rule, not by its score.
     */
    @Test
    void testCumulableFalsePair() {
        seedStore();
        seedProduct("ARB_TICK", "50.00");
        seedTiered("ARB_D", ticketSpec("AMOUNT", "5", null, null,
                "{ \"cumulable\": false, \"priority\": 100 }"));
        seedTiered("ARB_E", ticketSpec("AMOUNT", "3", null, null,
                "{ \"cumulable\": false, \"priority\": 200 }"));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 1.0)));
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests a shared {@code exclusionGroup} across three candidates (spec §5.11): at most one
     * advantage of the group applies, whatever the number of candidates.
     */
    @Test
    void testExclusionGroupCapsAtOne() {
        seedStore();
        seedProduct("ARB_TICK", "50.00");
        String group = "{ \"exclusionGroups\": [\"X\"], \"priority\": %d }";
        seedTiered("ARB_F", ticketSpec("AMOUNT", "5", null, null, String.format(group, 100)));
        seedTiered("ARB_G", ticketSpec("AMOUNT", "3", null, null, String.format(group, 200)));
        seedTiered("ARB_H", ticketSpec("AMOUNT", "2", null, null, String.format(group, 300)));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 1.0)));
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests that {@code consumesContributors} on a TICKET-scope trigger is a no-op (spec §4.4):
     * a ticket trigger has no contributors, so it consumes nothing and a following advantage
     * on the same ticket still applies.
     */
    @Test
    void testConsumesContributorsOnTicketScopeIsNoOp() {
        seedStore();
        seedProduct("ARB_TICK", "50.00");
        seedTiered("ARB_I", ticketSpec("AMOUNT", "5", ticketTrigger("40"), null,
                "{ \"consumesContributors\": true, \"priority\": 100 }"));
        seedTiered("ARB_J", ticketSpec("AMOUNT", "3", ticketTrigger("40"), null,
                "{ \"priority\": 200 }"));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 1.0)));
        // Both apply (5 + 3): the TICKET trigger of I has no carriers to consume.
        assertEquals(0, new BigDecimal("8.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests that priority governs the order against the score, and that the configuration code
     * is the stable final tiebreaker between equal priorities (spec §4.2): here two equal
     * cumulable-false advantages are ordered by code, so the one whose code sorts first wins.
     */
    @Test
    void testPriorityAndCodeTiebreak() {
        seedStore();
        seedProduct("ARB_TICK", "50.00");
        // Equal default priority (500) and equal score: the code breaks the tie, so ARB_K01
        // (before ARB_K02) applies and the non-cumulable ARB_K02 is dropped.
        seedTiered("ARB_K01", ticketSpec("AMOUNT", "5", null, null, "{ \"cumulable\": false }"));
        seedTiered("ARB_K02", ticketSpec("AMOUNT", "3", null, null, "{ \"cumulable\": false }"));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_TICK", 1.0)));
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests {@code maxApplicationsPerTicket} (spec §5.9): a TICKET discount spanning two
     * products would produce two applications, but the per-ticket cap of one keeps a single
     * application.
     */
    @Test
    void testMaxApplicationsPerTicketCapsCount() {
        seedStore();
        seedProduct("ARB_P1", "20.00");
        seedProduct("ARB_P2", "20.00");
        seedTiered("ARB_CAP", ticketSpec("PERCENTAGE", "10", null, null,
                "{ \"maxApplicationsPerTicket\": 1 }"));
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_P1", 1.0),
                DomainUtils.createItem("ARB_P2", 1.0)));
        assertEquals(1, evaluation.getAdvantages().size());
    }

    /**
     * Tests {@code maxApplicationsPerLine} (spec §5.9): a TICKET discount spanning two
     * products produces one application per targeted offer application, and a per-line cap of
     * one keeps them both (one each) — while the per-ticket cap would have kept only one.
     */
    @Test
    void testMaxApplicationsPerLineCapsPerTarget() {
        seedStore();
        seedProduct("ARB_P1", "20.00");
        seedProduct("ARB_P2", "20.00");
        seedTiered("ARB_CAPLINE", ticketSpec("PERCENTAGE", "10", null, null,
                "{ \"maxApplicationsPerLine\": 1 }"));
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_P1", 1.0),
                DomainUtils.createItem("ARB_P2", 1.0)));
        // Two distinct targeted offer applications, one application each: both survive the
        // per-line cap of one, so 2€ + 2€ = 4€ total.
        assertEquals(2, evaluation.getAdvantages().size());
        assertEquals(0, new BigDecimal("4.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the optional-offer fallback (spec §5.8, §4.2.A): an N+M offer carrying a
     * COUPON_CODE trigger that is not satisfied is dropped, and its lines fall back on the
     * Basic valuation — every line keeps a price, none is offered.
     */
    @Test
    void testOptionalOfferTriggerFallsBackToBasic() {
        seedStore();
        seedProduct("ARB_NPM", "10.00");
        String spec = "{ \"targetEans\": [\"ARB_NPM\"], \"quantityToPay\": 2, "
                + "\"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", "
                + "\"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0, "
                + "\"trigger\": { \"conditions\": [ { \"kind\": \"COUPON_CODE\", "
                + "\"code\": \"PROMO\" } ] } }";
        DomainUtils.createAndPersistOffer("ARB_2FOR1", store, "N+M", spec);
        // No coupon: the N+M trigger is not satisfied, so the three units are all priced by
        // the Basic valuation — full price 30, no free unit.
        BasketEvaluation withoutCoupon =
                engine.evaluate(basket(DomainUtils.createItem("ARB_NPM", 3.0)));
        assertEquals(0, new BigDecimal("30.00").compareTo(
                withoutCoupon.getTotalPrice().amountIncludingTax));
        // With the coupon: the N+M applies, one unit is offered, so the total drops below 30.
        Basket withCoupon = basket(DomainUtils.createItem("ARB_NPM", 3.0));
        withCoupon.couponCodes = List.of("PROMO");
        assertTrue(engine.evaluate(withCoupon).getTotalPrice().amountIncludingTax
                .compareTo(new BigDecimal("30.00")) < 0);
    }

    /**
     * Tells whether any offer application of a collection covers a given EAN.
     *
     * @param offers the offer applications.
     * @param ean    the EAN to look for.
     * @return {@code true} when at least one application carries a valued item of that EAN.
     */
    private boolean covers(java.util.Collection<OfferApplication> offers, String ean) {
        for (OfferApplication offer : offers) {
            for (BasketEvaluation.Item item : offer.getValuedItems()) {
                if (ean.equals(item.produceEan)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tests carrier consumption on the assiette (spec §4.4, GB-01-06-13 / GM-06-01-03): a
     * consumed carrier no longer receives a later advantage's discount, yet stays visible in
     * the offers of the response — the customer still bought it.
     */
    @Test
    void testConsumedCarrierLeavesLaterAssietteButStaysInOffers() {
        seedStore();
        seedProduct("ARB_SHARE", "50.00");
        seedTiered("ARB_CONS", itemsAmountSpec("ARB_SHARE", "6",
                itemsTrigger("ARB_SHARE", "40"),
                "{ \"consumesContributors\": true, \"priority\": 100 }"));
        // A trigger-less advantage that would discount ARB_SHARE, arbitrated after the
        // consuming one: its assiette is amputated of the consumed carrier, so it applies
        // nothing.
        seedTiered("ARB_LATER", itemsAmountSpec("ARB_SHARE", "5", null, "{ \"priority\": 500 }"));
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("ARB_SHARE", 1.0)));
        // Only the consuming advantage's 6€ applies; the later 5€ finds an empty assiette.
        assertEquals(0, new BigDecimal("6.00").compareTo(totalDiscount(evaluation)));
        // The carrier is gone from the arbitration view but still present in the response.
        assertTrue(covers(evaluation.getOffers(), "ARB_SHARE"));
        assertFalse(covers(evaluation.getAvailableOffers(), "ARB_SHARE"));
    }

    /**
     * Tests that a later advantage's assiette shrinks to exactly its non-consumed part
     * (spec §4.4): with the carrier consumed, a 10% discount on {carrier, other} applies to
     * the other product only.
     */
    @Test
    void testConsumedCarrierShrinksLaterAssiette() {
        seedStore();
        seedProduct("ARB_SHARE", "50.00");
        seedProduct("ARB_OTHER", "20.00");
        seedTiered("ARB_CONS", itemsAmountSpec("ARB_SHARE", "6",
                itemsTrigger("ARB_SHARE", "40"),
                "{ \"consumesContributors\": true, \"priority\": 100 }"));
        seedTiered("ARB_PCT", "{ \"scope\": \"ITEMS\", "
                + "\"targetEans\": [\"ARB_SHARE\", \"ARB_OTHER\"], \"metric\": \"AMOUNT\", "
                + "\"mode\": \"HIGHEST_REACHED\", \"tiers\": [ { \"threshold\": 0, "
                + "\"award\": { \"type\": \"PERCENTAGE\", \"value\": 10 } } ], "
                + "\"arbitration\": { \"priority\": 500 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("ARB_SHARE", 1.0),
                DomainUtils.createItem("ARB_OTHER", 1.0)));
        // Consuming advantage 6€ + 10% on the non-consumed ARB_OTHER only (2€, not 7€) = 8€.
        assertEquals(0, new BigDecimal("8.00").compareTo(totalDiscount(evaluation)));
    }
}
