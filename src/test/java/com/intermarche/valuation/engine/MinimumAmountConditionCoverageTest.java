package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage complement for {@link MinimumAmountCondition}: pins the arms that
 * {@link MinimumAmountConditionTest} leaves untouched — both scopes of {@code measure}, the
 * consumed-carrier skip of {@code ticketAmount}, the null tax-included guards of the offer amount,
 * the retained discounts and the valued items, the defensive null-collection arms (reachable only
 * through a mocked evaluation) and the negative-total clamp of {@code itemsAmount}. Pure in-memory
 * arithmetic over a store-less basket, no database.
 */
public class MinimumAmountConditionCoverageTest {

    /**
     * Builds a tax-included amount whose HT and TTC are equal and whose rate is zero.
     *
     * @param value the tax-included amount as a literal.
     * @return the amount evaluation.
     */
    private static AmountEvaluation ttc(String value) {
        return new AmountEvaluation(new BigDecimal(value), new BigDecimal(value), BigDecimal.ZERO);
    }

    /**
     * Builds an amount whose tax-included component is explicitly null.
     *
     * @return the amount with a null tax-included component.
     */
    private static AmountEvaluation nullTtc() {
        AmountEvaluation amount = new AmountEvaluation();
        amount.amountIncludingTax = null;
        return amount;
    }

    /**
     * Builds a valued result item carrying an EAN and an amount.
     *
     * @param ean    the product EAN.
     * @param amount the attributed amount, possibly null.
     * @return the valued item.
     */
    private static BasketEvaluation.Item valued(String ean, AmountEvaluation amount) {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.produceEan = ean;
        item.quantity = BigDecimal.ONE;
        item.amount = amount;
        return item;
    }

    /**
     * Builds an offer stub exposing its own amount and valued items.
     *
     * @param own    the offer's own amount, possibly null.
     * @param valued the valued items exposed by the offer.
     * @return the offer application stub.
     */
    private static OfferApplication offer(AmountEvaluation own, List<BasketEvaluation.Item> valued) {
        return new OfferApplication() {
            /**
             * Returns the offer's own amount.
             *
             * @return the amount, possibly null.
             */
            @Override
            public AmountEvaluation getAmount() {
                return own;
            }

            /**
             * Returns the covered basket items.
             *
             * @return an empty collection.
             */
            @Override
            public Collection<Basket.Item> getItems() {
                return List.of();
            }

            /**
             * Returns the offer type label.
             *
             * @return a constant label.
             */
            @Override
            public String getType() {
                return "TEST";
            }

            /**
             * Returns the valued items.
             *
             * @return the configured valued items.
             */
            @Override
            public List<BasketEvaluation.Item> getValuedItems() {
                return valued;
            }
        };
    }

    /**
     * Builds a discount stub with an amount and a target application.
     *
     * @param amount the discount amount, possibly null.
     * @param target the targeted application, possibly null.
     * @return the discount application stub.
     */
    private static DiscountApplication discount(AmountEvaluation amount, OfferApplication target) {
        return new DiscountApplication() {
            /**
             * Returns the discount amount.
             *
             * @return the amount, possibly null.
             */
            @Override
            public AmountEvaluation getDiscountAmount() {
                return amount;
            }

            /**
             * Returns the targeted application.
             *
             * @return the target, possibly null.
             */
            @Override
            public OfferApplication getOfferApplication() {
                return target;
            }
        };
    }

    /**
     * Builds a bare evaluation over an empty, store-less basket, so no database is touched.
     *
     * @return the evaluation.
     */
    private static BasketEvaluation evaluation() {
        return new BasketEvaluation(new Basket());
    }

    /**
     * A TICKET-scoped {@code measure} returns the ticket amount, summing every valued offer.
     */
    @Test
    void testMeasureTicketScope() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(ttc("30.00"), List.of()));
        evaluation.getOffers().add(offer(ttc("10.00"), List.of()));
        MinimumAmountCondition condition =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("40.00"));
        assertEquals(0, new BigDecimal("40.00").compareTo(condition.measure(evaluation)));
    }

    /**
     * An ITEMS-scoped {@code measure} returns the matched amount of the targeted lines only.
     */
    @Test
    void testMeasureItemsScope() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(null, List.of(valued("EAN_A", ttc("7.00")))));
        MinimumAmountCondition condition =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("7.00"));
        assertEquals(0, new BigDecimal("7.00").compareTo(condition.measure(evaluation)));
    }

    /**
     * A consumed carrier is skipped by {@code ticketAmount}: the ticket total counts only the free
     * offer.
     */
    @Test
    void testTicketSkipsConsumedCarrier() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication consumed = offer(ttc("40.00"), List.of());
        OfferApplication free = offer(ttc("10.00"), List.of());
        evaluation.getOffers().add(consumed);
        evaluation.getOffers().add(free);
        evaluation.markConsumed(List.of(consumed));
        MinimumAmountCondition atTen =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("10.00"));
        MinimumAmountCondition atEleven =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("10.01"));
        assertTrue(atTen.evaluate(evaluation).satisfied());
        assertFalse(atEleven.evaluate(evaluation).satisfied());
    }

    /**
     * An offer whose amount carries a null tax-included component contributes nothing to the ticket
     * total.
     */
    @Test
    void testTicketIgnoresNullTaxIncludedOfferAmount() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(nullTtc(), List.of()));
        evaluation.getOffers().add(offer(ttc("6.00"), List.of()));
        MinimumAmountCondition atSix =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("6.00"));
        MinimumAmountCondition atSeven =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("7.00"));
        assertTrue(atSix.evaluate(evaluation).satisfied());
        assertFalse(atSeven.evaluate(evaluation).satisfied());
    }

    /**
     * When the evaluation exposes neither offers nor advantages (a mocked context), the ticket
     * amount is zero and an at-zero threshold is still satisfied.
     */
    @Test
    void testTicketWithNullCollections() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getOffers()).thenReturn(null);
        when(evaluation.getAdvantages()).thenReturn(null);
        MinimumAmountCondition atZero =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("0.00"));
        MinimumAmountCondition aboveZero =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("0.01"));
        assertTrue(atZero.evaluate(evaluation).satisfied());
        assertFalse(aboveZero.evaluate(evaluation).satisfied());
    }

    /**
     * A discount whose amount is null retains nothing, leaving the ticket total intact.
     */
    @Test
    void testRetainedDiscountsIgnoreNullDiscountAmount() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(ttc("20.00"), List.of()));
        evaluation.getAdvantages().add(discount(null, null));
        MinimumAmountCondition atTwenty =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("20.00"));
        assertTrue(atTwenty.evaluate(evaluation).satisfied());
    }

    /**
     * A discount whose amount carries a null tax-included component retains nothing.
     */
    @Test
    void testRetainedDiscountsIgnoreNullTaxIncludedDiscount() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(ttc("20.00"), List.of()));
        evaluation.getAdvantages().add(discount(nullTtc(), null));
        MinimumAmountCondition atTwenty =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("20.00"));
        assertTrue(atTwenty.evaluate(evaluation).satisfied());
    }

    /**
     * When the evaluation exposes no offers collection (a mocked context), the ITEMS amount is zero
     * and the condition is not satisfied, collecting no contributor.
     */
    @Test
    void testItemsWithNullOffersCollection() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getOffers()).thenReturn(null);
        MinimumAmountCondition condition =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("0.01"));
        TriggerResult result = condition.evaluate(evaluation);
        assertFalse(result.satisfied());
        assertTrue(result.contributors().isEmpty());
    }

    /**
     * A matching valued item whose amount carries a null tax-included component adds nothing while
     * still marking the offer a contributor.
     */
    @Test
    void testItemsIgnoreNullTaxIncludedItemAmount() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication app = offer(null, List.of(
                valued("EAN_A", nullTtc()),
                valued("EAN_A", ttc("5.00"))));
        evaluation.getOffers().add(app);
        MinimumAmountCondition atFive =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("5.00"));
        MinimumAmountCondition atSix =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("5.01"));
        TriggerResult satisfied = atFive.evaluate(evaluation);
        assertTrue(satisfied.satisfied());
        assertEquals(1, satisfied.contributors().size());
        assertFalse(atSix.evaluate(evaluation).satisfied());
    }

    /**
     * A matching valued item with a negative amount drives the ITEMS total negative, which is
     * clamped to zero.
     */
    @Test
    void testItemsClampsNegativeTotalToZero() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(null, List.of(valued("EAN_A", ttc("-5.00")))));
        MinimumAmountCondition condition =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("0.00"));
        assertEquals(0, new BigDecimal("0.00").compareTo(condition.measure(evaluation)));
    }
}
