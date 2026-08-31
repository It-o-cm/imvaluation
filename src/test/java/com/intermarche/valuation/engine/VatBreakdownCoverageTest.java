package com.intermarche.valuation.engine;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-oriented @QuarkusTest for {@link VatBreakdown}.
 * <p>
 * Every scenario boots inside a QuarkusTest so quarkus-jacoco attributes the executed
 * lines; no database is touched because {@link BasketEvaluation} is built from a null
 * basket and the offers/discounts are in-memory stubs.
 */
@QuarkusTest
class VatBreakdownCoverageTest {

    /**
     * Builds a valued result item carrying an amount at a given rate.
     *
     * @param rate The VAT rate as a decimal string.
     * @param ht   The amount excluding tax.
     * @param ttc  The amount including tax.
     * @return The valued item.
     */
    private BasketEvaluation.Item item(String rate, String ht, String ttc) {
        BasketEvaluation.Item it = new BasketEvaluation.Item();
        it.amount = new AmountEvaluation(new BigDecimal(ht), new BigDecimal(ttc), new BigDecimal(rate));
        return it;
    }

    /**
     * Builds an offer stub exposing given valued items and own amount.
     *
     * @param valued The valued items, may be empty.
     * @param amount The offer's own amount, may be null.
     * @return The offer application stub.
     */
    private OfferApplication offer(final List<BasketEvaluation.Item> valued, final AmountEvaluation amount) {
        return new OfferApplication() {
            /**
             * Returns the offer's own amount.
             *
             * @return The amount.
             */
            @Override
            public AmountEvaluation getAmount() {
                return amount;
            }
            /**
             * Returns the covered basket items.
             *
             * @return An empty collection.
             */
            @Override
            public Collection<Basket.Item> getItems() {
                return List.of();
            }
            /**
             * Returns the offer type label.
             *
             * @return A constant label.
             */
            @Override
            public String getType() {
                return "TEST";
            }
            /**
             * Returns the valued items.
             *
             * @return The configured valued items.
             */
            @Override
            public List<BasketEvaluation.Item> getValuedItems() {
                return valued;
            }
        };
    }

    /**
     * Builds a discount stub with a discount amount and an optional target offer.
     *
     * @param amt    The discount amount, may be null.
     * @param target The targeted offer, may be null.
     * @return The discount application stub.
     */
    private DiscountApplication discount(final AmountEvaluation amt, final OfferApplication target) {
        return new DiscountApplication() {
            /**
             * Returns the discount amount.
             *
             * @return The amount.
             */
            @Override
            public AmountEvaluation getDiscountAmount() {
                return amt;
            }
            /**
             * Returns the targeted offer.
             *
             * @return The offer, or null.
             */
            @Override
            public OfferApplication getOfferApplication() {
                return target;
            }
        };
    }

    /**
     * A null evaluation yields an empty breakdown.
     */
    @Test
    void computeNullEvaluationReturnsEmpty() {
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(null);
        assertTrue(lines.isEmpty());
    }

    /**
     * A single-rate offer reconciled with a total produces one exact line; a null-amount
     * valued item is skipped and a non-discount advantage is ignored.
     */
    @Test
    void singleRateOfferWithTotal() {
        List<BasketEvaluation.Item> valued = new ArrayList<>();
        valued.add(item("0.20", "10.00", "12.00"));
        valued.add(new BasketEvaluation.Item());
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(offer(valued, null));
        eval.getAdvantages().add(new AdvantageApplication() {
            /**
             * Returns no offer application.
             *
             * @return Always null.
             */
            @Override
            public OfferApplication getOfferApplication() {
                return null;
            }
        });
        eval.setTotalPrice(new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"),
                new BigDecimal("0.20")));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("0.2000"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("10.00"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("2.00"), lines.get(0).vatAmount);
        assertEquals(new BigDecimal("12.00"), lines.get(0).amountIncludingTax);
    }

    /**
     * An offer whose only own amount stands in for a product line (no valued items) still
     * lands in the rate it declares.
     */
    @Test
    void offerWithOwnAmountOnly() {
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(offer(List.of(),
                new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"))));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("0.2000"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("10.00"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("12.00"), lines.get(0).amountIncludingTax);
    }

    /**
     * A single-rate targeted discount is deducted from that rate.
     */
    @Test
    void singleRateTargetedDiscount() {
        OfferApplication off = offer(List.of(item("0.20", "10.00", "12.00")), null);
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(off);
        eval.getAdvantages().add(discount(
                new AmountEvaluation(new BigDecimal("4.00"), new BigDecimal("4.80"), new BigDecimal("0.20")), off));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("0.2000"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("6.00"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("7.20"), lines.get(0).amountIncludingTax);
    }

    /**
     * A discount with no target falls back on its own declared rate.
     */
    @Test
    void untargetedDiscountUsesOwnRate() {
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getAdvantages().add(discount(
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20")), null));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("0.2000"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("-5.00"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("-6.00"), lines.get(0).amountIncludingTax);
    }

    /**
     * A discount carrying a null amount is a no-op and leaves an empty breakdown.
     */
    @Test
    void discountWithNullAmountIsIgnored() {
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getAdvantages().add(discount(null, null));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertTrue(lines.isEmpty());
    }

    /**
     * A blended discount is spread over the target's rates by their tax-included weight.
     */
    @Test
    void blendedDiscountIsSpread() {
        OfferApplication off = offer(List.of(
                item("0.055", "10.00", "10.55"),
                item("0.20", "20.00", "24.00")), null);
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(off);
        eval.getAdvantages().add(discount(
                new AmountEvaluation(new BigDecimal("4.55"), new BigDecimal("5.00"), new BigDecimal("0.10")), off));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(2, lines.size());
        assertEquals(new BigDecimal("0.0550"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("8.55"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("0.47"), lines.get(0).vatAmount);
        assertEquals(new BigDecimal("9.02"), lines.get(0).amountIncludingTax);
        assertEquals(new BigDecimal("0.2000"), lines.get(1).vatRate);
        assertEquals(new BigDecimal("17.11"), lines.get(1).amountExcludingTax);
        assertEquals(new BigDecimal("3.42"), lines.get(1).vatAmount);
        assertEquals(new BigDecimal("20.53"), lines.get(1).amountIncludingTax);
    }

    /**
     * A blended discount whose target's tax-included weights cancel out is skipped, leaving
     * the offer's own lines untouched.
     */
    @Test
    void blendedDiscountWithZeroTotalWeightIsSkipped() {
        OfferApplication off = offer(List.of(
                item("0.05", "9.50", "10.00"),
                item("0.20", "-8.33", "-10.00")), null);
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(off);
        eval.getAdvantages().add(discount(
                new AmountEvaluation(new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("0.10")), off));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(2, lines.size());
        assertEquals(new BigDecimal("0.0500"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("9.50"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("10.00"), lines.get(0).amountIncludingTax);
        assertEquals(new BigDecimal("0.2000"), lines.get(1).vatRate);
        assertEquals(new BigDecimal("-8.33"), lines.get(1).amountExcludingTax);
        assertEquals(new BigDecimal("-10.00"), lines.get(1).amountIncludingTax);
    }

    /**
     * A valued item whose excluding-tax amount is null contributes nothing, and an empty
     * bucket set reconciles to an empty breakdown.
     */
    @Test
    void nullExcludingTaxAmountIsSkipped() {
        BasketEvaluation.Item it = new BasketEvaluation.Item();
        it.amount = new AmountEvaluation();
        it.amount.amountExcludingTax = null;
        it.amount.amountIncludingTax = new BigDecimal("5.00");
        it.amount.vatRate = new BigDecimal("0.20");
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(offer(List.of(it), null));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertTrue(lines.isEmpty());
    }

    /**
     * A valued item whose including-tax amount is null contributes nothing.
     */
    @Test
    void nullIncludingTaxAmountIsSkipped() {
        BasketEvaluation.Item it = new BasketEvaluation.Item();
        it.amount = new AmountEvaluation();
        it.amount.amountExcludingTax = new BigDecimal("5.00");
        it.amount.amountIncludingTax = null;
        it.amount.vatRate = new BigDecimal("0.20");
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(offer(List.of(it), null));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertTrue(lines.isEmpty());
    }

    /**
     * A valued item whose VAT rate is null is keyed to the zero-rate bucket.
     */
    @Test
    void nullVatRateIsKeyedToZero() {
        BasketEvaluation.Item it = new BasketEvaluation.Item();
        it.amount = new AmountEvaluation();
        it.amount.amountExcludingTax = new BigDecimal("5.00");
        it.amount.amountIncludingTax = new BigDecimal("5.00");
        it.amount.vatRate = null;
        BasketEvaluation eval = new BasketEvaluation(null);
        eval.getOffers().add(offer(List.of(it), null));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(eval);
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("0.0000"), lines.get(0).vatRate);
        assertEquals(new BigDecimal("5.00"), lines.get(0).amountExcludingTax);
        assertEquals(new BigDecimal("0.00"), lines.get(0).vatAmount);
        assertEquals(new BigDecimal("5.00"), lines.get(0).amountIncludingTax);
    }
}
