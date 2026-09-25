package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MinimumAmountCondition}: the TICKET and ITEMS scopes, the inclusive
 * threshold, the netting of retained advantages and the collection of contributors.
 */
public class MinimumAmountConditionTest {

    /**
     * Builds a valued result item carrying an EAN, a quantity and an amount.
     *
     * @param ean      the product EAN.
     * @param quantity the quantity.
     * @param ttc      the tax-included amount, or {@code null} for no amount.
     * @return the valued item.
     */
    private BasketEvaluation.Item valued(String ean, double quantity, String ttc) {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.produceEan = ean;
        item.quantity = BigDecimal.valueOf(quantity);
        item.amount = ttc == null ? null
                : new AmountEvaluation(new BigDecimal(ttc), new BigDecimal(ttc), BigDecimal.ZERO);
        return item;
    }

    /**
     * Builds an offer stub exposing given valued items and its own amount.
     *
     * @param amountTtc the offer's own tax-included amount, or {@code null}.
     * @param valued    the valued items exposed by the offer.
     * @return the offer application stub.
     */
    private OfferApplication offer(String amountTtc, List<BasketEvaluation.Item> valued) {
        final AmountEvaluation amount = amountTtc == null ? null
                : new AmountEvaluation(new BigDecimal(amountTtc), new BigDecimal(amountTtc), BigDecimal.ZERO);
        return new OfferApplication() {
            /**
             * Returns the offer's own amount.
             *
             * @return the amount, possibly null.
             */
            @Override
            public AmountEvaluation getAmount() {
                return amount;
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
     * Builds a discount stub with an amount and a target offer application.
     *
     * @param ttc    the tax-included discount amount.
     * @param target the targeted offer application, or {@code null}.
     * @return the discount application stub.
     */
    private DiscountApplication discount(String ttc, OfferApplication target) {
        final AmountEvaluation amount =
                new AmountEvaluation(new BigDecimal(ttc), new BigDecimal(ttc), BigDecimal.ZERO);
        return new DiscountApplication() {
            /**
             * Returns the discount amount.
             *
             * @return the amount.
             */
            @Override
            public AmountEvaluation getDiscountAmount() {
                return amount;
            }

            /**
             * Returns the targeted offer application.
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
    private BasketEvaluation evaluation() {
        return new BasketEvaluation(new Basket());
    }

    /**
     * Tests the TICKET scope below, on and above the threshold, summing every offer.
     */
    @Test
    void testTicketThresholdInclusive() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer("30.00", List.of()));
        evaluation.getOffers().add(offer("10.00", List.of()));
        MinimumAmountCondition below =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("40.01"));
        MinimumAmountCondition exact =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("40.00"));
        assertFalse(below.evaluate(evaluation).satisfied());
        assertTrue(exact.evaluate(evaluation).satisfied());
        assertTrue(exact.evaluate(evaluation).contributors().isEmpty());
    }

    /**
     * Tests that a ticket amount nets the advantages already retained (52 - 4 = 48): the
     * "52 to 48" normative case (spec §5.2) at leaf level.
     */
    @Test
    void testTicketNetsRetainedDiscounts() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer("52.00", List.of()));
        evaluation.getAdvantages().add(discount("4.00", null));
        MinimumAmountCondition at50 =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("50.00"));
        MinimumAmountCondition at48 =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("48.00"));
        assertFalse(at50.evaluate(evaluation).satisfied());
        assertTrue(at48.evaluate(evaluation).satisfied());
    }

    /**
     * Tests that a null offer amount and a non-discount advantage are ignored, and that a
     * ticket driven negative by discounts is clamped to zero.
     */
    @Test
    void testTicketNullAmountsAndNegativeClamp() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(null, List.of()));
        evaluation.getOffers().add(offer("10.00", List.of()));
        evaluation.getAdvantages().add(discount("20.00", null));
        evaluation.getAdvantages().add(new AdvantageApplication() {
            /**
             * Returns no associated offer application.
             *
             * @return null.
             */
            @Override
            public OfferApplication getOfferApplication() {
                return null;
            }
        });
        MinimumAmountCondition atZero =
                new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(), new BigDecimal("0.01"));
        assertFalse(atZero.evaluate(evaluation).satisfied());
    }

    /**
     * Tests the ITEMS scope: only the targeted EANs are summed, the contributing offers are
     * collected, and the threshold is inclusive.
     */
    @Test
    void testItemsScopeSumsAndCollectsContributors() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication grocery = offer("40.00", List.of(
                valued("EAN_A", 2.0, "25.00"),
                valued("EAN_B", 1.0, "15.00")));
        OfferApplication frozen = offer("8.00", List.of(valued("EAN_Z", 1.0, "8.00")));
        evaluation.getOffers().add(grocery);
        evaluation.getOffers().add(frozen);
        MinimumAmountCondition condition = new MinimumAmountCondition(
                MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A", "EAN_B"), new BigDecimal("40.00"));
        TriggerResult result = condition.evaluate(evaluation);
        assertTrue(result.satisfied());
        assertEquals(1, result.contributors().size());
        assertSame(grocery, result.contributors().get(0));
    }

    /**
     * Tests that an ITEMS scope matching no line yields zero, is not satisfied and collects
     * no contributor.
     */
    @Test
    void testItemsScopeNoMatch() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer("40.00", List.of(valued("EAN_A", 1.0, "40.00"))));
        MinimumAmountCondition condition = new MinimumAmountCondition(
                MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_MISSING"), new BigDecimal("0.01"));
        TriggerResult result = condition.evaluate(evaluation);
        assertFalse(result.satisfied());
        assertTrue(result.contributors().isEmpty());
    }

    /**
     * Tests that an ITEMS scope nets the advantage retained against the contributing offer,
     * lowering the matched amount (10 * (10-2)/10 = 8).
     */
    @Test
    void testItemsScopeNetsPerApplicationDiscount() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication app = offer("10.00", List.of(valued("EAN_A", 1.0, "10.00")));
        OfferApplication other = offer("5.00", List.of(valued("EAN_B", 1.0, "5.00")));
        evaluation.getOffers().add(app);
        evaluation.getOffers().add(other);
        evaluation.getAdvantages().add(discount("2.00", app));
        evaluation.getAdvantages().add(discount("5.00", other));
        MinimumAmountCondition at8 = new MinimumAmountCondition(
                MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("8.00"));
        MinimumAmountCondition at9 = new MinimumAmountCondition(
                MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("9.00"));
        assertTrue(at8.evaluate(evaluation).satisfied());
        assertFalse(at9.evaluate(evaluation).satisfied());
    }

    /**
     * Tests that an offer with no own amount keeps a net factor of one, and that a valued
     * item with no amount and one with a null EAN are ignored while still marking the offer
     * a contributor when another item matches.
     */
    @Test
    void testItemsScopeNullAmountsAndNullEan() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication app = offer(null, List.of(
                valued(null, 1.0, "99.00"),
                valued("EAN_A", 1.0, null),
                valued("EAN_A", 1.0, "7.00")));
        evaluation.getOffers().add(app);
        MinimumAmountCondition condition = new MinimumAmountCondition(
                MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("7.00"));
        TriggerResult result = condition.evaluate(evaluation);
        assertTrue(result.satisfied());
        assertEquals(1, result.contributors().size());
    }

    /**
     * Tests the accessors and the defensive copy of the EAN set.
     */
    @Test
    void testAccessors() {
        MinimumAmountCondition condition = new MinimumAmountCondition(
                MinimumAmountCondition.Scope.ITEMS, Set.of("EAN_A"), new BigDecimal("12.34"));
        assertEquals(MinimumAmountCondition.Scope.ITEMS, condition.getScope());
        assertEquals(Set.of("EAN_A"), condition.getEans());
        assertEquals(new BigDecimal("12.34"), condition.getThreshold());
        assertTrue(new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, null, BigDecimal.ONE)
                .getEans().isEmpty());
    }
}
