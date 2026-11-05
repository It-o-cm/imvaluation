package com.intermarche.valuation.engine;

import com.intermarche.valuation.engine.offers.InstrumentGrantFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain unit tests for the {@code grantTotals} assembly block of {@link BasketEvaluation}
 * (spec-plafonds-attribution §3, §5).
 * <p>
 * The block is a pure sum over the applied advantages, so every scenario builds a
 * store-less {@link BasketEvaluation} in memory, feeds it hand-built grant applications, and
 * asserts the totals to the unit — no database, no engine run, no Panache.
 */
public class BasketEvaluationGrantTotalsTest {

    /**
     * Builds a store-less evaluation: a basket with no store code resolves to no store, so the
     * constructor touches no database.
     *
     * @return a fresh evaluation with empty offers and advantages.
     */
    private BasketEvaluation newEvaluation() {
        return new BasketEvaluation(new Basket());
    }

    /**
     * Builds an instrument-grant application carrying a grant of the given unit and amount, with
     * no clip trace.
     *
     * @param offerCode the offer code.
     * @param unit      the grant unit ("EUR", "POINTS" or "VIGNETTES").
     * @param amount    the granted amount.
     * @return the informational grant application.
     */
    private InstrumentGrantFactory.InstrumentGrantApplication grant(String offerCode, String unit, String amount) {
        InstrumentGrantFactory.Grant instrument =
                new InstrumentGrantFactory.Grant("VOUCHER", unit, new BigDecimal(amount), null);
        return new InstrumentGrantFactory.InstrumentGrantApplication(
                "Voucher Grant", offerCode, "detail", instrument, null);
    }

    /**
     * §4 case 3 — two attribution mechanics: {@code grantTotals} is the sum across all of them
     * (two points mechanics add up, a vignettes mechanic feeds the vignettes total).
     */
    @Test
    void testGrantTotals_TwoMechanisms_Summed() {
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getAdvantages().add(grant("PTS_A", "POINTS", "10"));
        evaluation.getAdvantages().add(grant("PTS_B", "POINTS", "5"));
        evaluation.getAdvantages().add(grant("VIG_A", "VIGNETTES", "3"));
        BasketEvaluation.GrantTotals totals = evaluation.getGrantTotals();
        assertNotNull(totals);
        assertEquals(0, new BigDecimal("15").compareTo(totals.points));
        assertEquals(0, new BigDecimal("3").compareTo(totals.vignettes));
    }

    /**
     * §4 case 3 — a single attribution mechanic: {@code grantTotals} carries its amount and a
     * zero for the untouched kind (both keys are always present).
     */
    @Test
    void testGrantTotals_SingleMechanism_PointsOnly() {
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getAdvantages().add(grant("PTS_A", "POINTS", "7"));
        BasketEvaluation.GrantTotals totals = evaluation.getGrantTotals();
        assertNotNull(totals);
        assertEquals(0, new BigDecimal("7").compareTo(totals.points));
        assertEquals(0, BigDecimal.ZERO.compareTo(totals.vignettes));
    }

    /**
     * §4 case 3 — no attribution mechanic: the {@code grantTotals} key is absent (null), the
     * bit-for-bit regression criterion.
     */
    @Test
    void testGrantTotals_NoMechanism_KeyAbsent() {
        BasketEvaluation evaluation = newEvaluation();
        assertNull(evaluation.getGrantTotals());
    }

    /**
     * §4 case 3 — a monetary voucher/coupon is not a point or a vignette: an EUR grant alone
     * yields no {@code grantTotals} key.
     */
    @Test
    void testGrantTotals_EurGrantOnly_KeyAbsent() {
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getAdvantages().add(grant("VCH_EUR", "EUR", "5.00"));
        assertNull(evaluation.getGrantTotals());
    }

    /**
     * §4 case 5 — the vignettes the basket brought in (incoming collection) and the vignettes the
     * ticket attributes (outgoing) never mix: {@code grantTotals.vignettes} counts only the
     * attributed 3, never the 7 carried in the basket, which is left untouched.
     */
    @Test
    void testGrantTotals_IncomingAndAttributedVignettes_DoNotMix() {
        Basket basket = new Basket();
        basket.vignettes = new HashMap<>();
        basket.vignettes.put("1234567890123", 7);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.getAdvantages().add(grant("VIG_OUT", "VIGNETTES", "3"));
        BasketEvaluation.GrantTotals totals = evaluation.getGrantTotals();
        assertNotNull(totals);
        assertEquals(0, new BigDecimal("3").compareTo(totals.vignettes));
        assertEquals(0, BigDecimal.ZERO.compareTo(totals.points));
        Map<String, Integer> incoming = evaluation.getBasket().vignettes;
        assertEquals(1, incoming.size());
        assertEquals(Integer.valueOf(7), incoming.get("1234567890123"));
    }

    /**
     * §4 case 6 — determinism: the {@code grantTotals} sum is a pure function of the advantages,
     * so 50 successive reads of the same evaluation return the very same totals.
     */
    @Test
    void testGrantTotals_Deterministic_FiftyReads() {
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getAdvantages().add(grant("PTS_A", "POINTS", "10"));
        evaluation.getAdvantages().add(grant("PTS_B", "POINTS", "5"));
        evaluation.getAdvantages().add(grant("VIG_A", "VIGNETTES", "3"));
        for (int read = 0; read < 50; read++) {
            BasketEvaluation.GrantTotals totals = evaluation.getGrantTotals();
            assertEquals(0, new BigDecimal("15").compareTo(totals.points));
            assertEquals(0, new BigDecimal("3").compareTo(totals.vignettes));
        }
    }
}
