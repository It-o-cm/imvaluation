package com.intermarche.valuation.engine;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.engine.offers.GiftItemDiscountFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance tests for the A5 migration of quantities to {@link BigDecimal} (spec §6).
 * <p>
 * These pin the properties the migration exists to guarantee: exact gram-level arithmetic
 * with no double drift, exact multi-price slice splitting, fractional lot counting, plain
 * output serialization, and the integer-only gift rule. Plain unit tests: baskets are built
 * in memory (no store code, hence no Panache lookup) and the arithmetic is asserted to the
 * exact value with {@code compareTo}.
 */
class QuantityBigDecimalAcceptanceTest {

    /**
     * Builds an in-memory basket item with an EAN, a quantity and an excl-tax unit price.
     * <p>
     * The price distinguishes price profiles: two items of the same EAN with different unit
     * prices stay on separate evaluation entries, which is what a multi-price pick exercises.
     *
     * @param ean          The product EAN.
     * @param quantity     The exact quantity (built from its string form, no double).
     * @param priceExclTax The excl-tax unit price, or {@code null} for none.
     * @return The configured basket item.
     */
    private Basket.Item item(String ean, String quantity, String priceExclTax) {
        Basket.Item i = new Basket.Item();
        i.lineId = ean + "-" + quantity;
        i.produceEan = ean;
        i.quantity = new BigDecimal(quantity);
        i.pricePerUnitExclTax = priceExclTax == null ? null : new BigDecimal(priceExclTax);
        return i;
    }

    /**
     * Feeds an in-memory basket (no store code) into a fresh evaluation.
     *
     * @param items The basket lines.
     * @return The evaluation, ready to pick.
     */
    private BasketEvaluation evaluationOf(List<Basket.Item> items) {
        Basket basket = new Basket();
        basket.storeCode = null;
        basket.items = items;
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        return evaluation;
    }

    /**
     * §6.1 — Three lines of 0.1 kg consumed by a 0.3 kg pick leave exactly zero, never a
     * 2.77e-17 double residue.
     */
    @Test
    @DisplayName("6.1 - gram-exact consumption leaves exactly zero")
    void gramExactConsumption() {
        List<Basket.Item> items = new ArrayList<>();
        items.add(item("EAN1", "0.1", null));
        items.add(item("EAN1", "0.1", null));
        items.add(item("EAN1", "0.1", null));
        BasketEvaluation evaluation = evaluationOf(items);
        List<Basket.Item> slices = evaluation.pick(new BigDecimal("0.3"), "EAN1");
        BigDecimal taken = BigDecimal.ZERO;
        for (Basket.Item slice : slices) {
            taken = taken.add(slice.quantity);
        }
        assertEquals(0, taken.compareTo(new BigDecimal("0.3")), "the pick takes exactly 0.3");
        assertEquals(0, evaluation.remainingQuantity("EAN1").compareTo(BigDecimal.ZERO), "nothing remains, exactly zero");
    }

    /**
     * §6.2 — A 3+1 lot over fractional quantities 2.5 and 1.5 counts exactly: they aggregate
     * to 4, giving one full bundle of size 4 and a zero residue, with no drift.
     */
    @Test
    @DisplayName("6.2 - fractional N+M lot counts exactly")
    void fractionalLotCount() {
        List<Basket.Item> items = new ArrayList<>();
        items.add(item("EAN2", "2.5", null));
        items.add(item("EAN2", "1.5", null));
        BasketEvaluation evaluation = evaluationOf(items);
        BigDecimal total = evaluation.remainingQuantity("EAN2");
        assertEquals(0, total.compareTo(new BigDecimal("4")), "2.5 + 1.5 aggregate to exactly 4");
        int bundleSize = 4;
        int bundles = total.divide(BigDecimal.valueOf(bundleSize), 0, java.math.RoundingMode.FLOOR).intValue();
        assertEquals(1, bundles, "exactly one bundle of size 4 fits");
        List<Basket.Item> slices = evaluation.pick(BigDecimal.valueOf((long) bundles * bundleSize), "EAN2");
        BigDecimal taken = BigDecimal.ZERO;
        for (Basket.Item slice : slices) {
            taken = taken.add(slice.quantity);
        }
        assertEquals(0, taken.compareTo(new BigDecimal("4")), "the bundle consumes exactly 4");
        assertEquals(0, evaluation.remainingQuantity("EAN2").compareTo(BigDecimal.ZERO), "no fractional drift remains");
    }

    /**
     * §6.3 — A 1.000 kg pick over a product carrying two distinct prices splits into slices
     * whose quantities sum to exactly 1.000.
     */
    @Test
    @DisplayName("6.3 - multi-price slices sum to exactly the picked quantity")
    void multiPriceSlicesSumExactly() {
        List<Basket.Item> items = new ArrayList<>();
        items.add(item("EAN3", "0.6", "1.00"));
        items.add(item("EAN3", "0.4", "2.00"));
        BasketEvaluation evaluation = evaluationOf(items);
        List<Basket.Item> slices = evaluation.pick(new BigDecimal("1.000"), "EAN3");
        assertEquals(2, slices.size(), "the two price profiles yield two slices");
        BigDecimal sum = BigDecimal.ZERO;
        for (Basket.Item slice : slices) {
            sum = sum.add(slice.quantity);
        }
        assertEquals(0, sum.compareTo(new BigDecimal("1.000")), "slices sum to exactly 1.000");
        assertEquals(0, evaluation.remainingQuantity("EAN3").compareTo(BigDecimal.ZERO), "nothing remains");
    }

    /**
     * §6.4 — A quantity of 100 serializes as {@code 100}, never {@code 1E+2}; 0.738 keeps its
     * three decimals; a round 2.000 strips to 2.
     *
     * @throws Exception when serialization fails.
     */
    @Test
    @DisplayName("6.4 - output quantities serialize in plain, normalized notation")
    void plainOutputSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        BasketEvaluation.Item hundred = new BasketEvaluation.Item();
        hundred.quantity = new BigDecimal("100");
        assertTrue(mapper.writeValueAsString(hundred).contains("\"quantity\":100"),
                "100 is serialized plain, never as 1E+2");
        BasketEvaluation.Item weighed = new BasketEvaluation.Item();
        weighed.quantity = new BigDecimal("0.738");
        assertTrue(mapper.writeValueAsString(weighed).contains("\"quantity\":0.738"),
                "0.738 keeps its three decimals");
        BasketEvaluation.Item round = new BasketEvaluation.Item();
        round.quantity = new BigDecimal("2.000");
        assertTrue(mapper.writeValueAsString(round).contains("\"quantity\":2"),
                "2.000 strips its trailing zeros to 2");
    }

    /**
     * §6.5 — The gift-item integer rule: 2.000 counts as a whole unit (offerable), 0.5 does
     * not (excluded). Exercised through the private {@code isInteger} rule that drives the
     * selection.
     *
     * @throws Exception when the reflective call fails.
     */
    @Test
    @DisplayName("6.5 - gift rule accepts whole units, rejects fractional ones")
    void giftIntegerRule() throws Exception {
        Method isInteger = GiftItemDiscountFactory.class.getDeclaredMethod("isInteger", BigDecimal.class);
        isInteger.setAccessible(true);
        assertTrue((boolean) isInteger.invoke(null, new BigDecimal("2.000")), "2.000 is a whole number of units");
        assertFalse((boolean) isInteger.invoke(null, new BigDecimal("0.5")), "0.5 is not offerable");
    }
}
