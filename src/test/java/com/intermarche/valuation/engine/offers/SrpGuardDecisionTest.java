package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.engine.BasketEvaluation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for the pure SRP decision logic (spec-srp-guard §3.A/B/C): {@code decideLine},
 * {@code descentFraction}, {@code highestCrossed} and {@code parseWarningThresholds}.
 * <p>
 * JUnit 5 only, no {@code @QuarkusTest}, no database: the decision is a pure function of a line's
 * already-resolved figures, so every branch — including the null legs — is exercised in memory. Each
 * compound boolean condition of {@code decideLine} has its dedicated case: a line at the floor, below
 * the floor with and without a manual gesture, an overridden line, an exempt line, a warned line at
 * each palier, a line with no nominal, a guard with no palier, and the EGAlim-included net.
 */
public class SrpGuardDecisionTest {

    /**
     * The two warning paliers used across the warning cases: half-way and 80% down to the floor.
     */
    private static final List<BigDecimal> PALIERS =
            List.of(new BigDecimal("0.50"), new BigDecimal("0.80"));

    /**
     * Convenience wrapper over {@link SrpGuardFactory.SrpGuardApplier#decideLine} with fixed line id
     * and EAN, to keep the cases terse.
     *
     * @param floor            the floor price, tax included.
     * @param nominal          the sale price times quantity, tax included; may be null.
     * @param finalTtc         the net after every advantage.
     * @param thresholds       the configured warning paliers.
     * @param exempt           whether an srpExempt configuration discounts the line.
     * @param overridden       whether the line carries a per-line derogation.
     * @param overrideReason   the operator's override reason; may be null.
     * @param hasManualGesture whether the line carries a manual gesture.
     * @return the decision.
     */
    private SrpGuardFactory.LineDecision decide(String floor, String nominal, String finalTtc,
                                                List<BigDecimal> thresholds, boolean exempt,
                                                boolean overridden, String overrideReason,
                                                boolean hasManualGesture) {
        BigDecimal nominalValue = nominal == null ? null : new BigDecimal(nominal);
        return SrpGuardFactory.SrpGuardApplier.decideLine("L1", "111", new BigDecimal(floor),
                nominalValue, new BigDecimal(finalTtc), thresholds, exempt, overridden, overrideReason,
                hasManualGesture);
    }

    /**
     * Asserts a BigDecimal equals an expected value by numeric comparison (scale-independent).
     *
     * @param expected the expected value.
     * @param actual   the actual value.
     */
    private void assertAmount(String expected, BigDecimal actual) {
        assertTrue(actual != null && new BigDecimal(expected).compareTo(actual) == 0,
                "expected " + expected + " but was " + actual);
    }

    /**
     * A line comfortably above its floor, no palier configured: granted without reserve — a margin,
     * no reason code, no correction (spec-srp-guard §3, OK level).
     */
    @Test
    void testGrantedWithoutReserve() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "15.00", List.of(),
                false, false, null, false);
        assertAmount("4.00", decision.line.marginAboveFloor);
        assertNull(decision.line.correction);
        assertNull(decision.line.reasonCode);
        assertNull(decision.line.descentFraction);
        assertAmount("0", decision.correctionTtc);
    }

    /**
     * A line exactly at its floor with paliers configured: not clamped (final is not strictly below
     * the floor), margin zero, but the descent reached 100% so the highest palier fires
     * (spec-srp-guard §3.B).
     */
    @Test
    void testAtFloorExactWarnsButDoesNotClamp() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "11.00", PALIERS,
                false, false, null, false);
        assertNull(decision.line.correction);
        assertAmount("0.00", decision.line.marginAboveFloor);
        assertEquals(SrpGuardFactory.REASON_MARGIN_WARNING, decision.line.reasonCode);
        assertAmount("0.80", decision.line.warningThreshold);
        assertAmount("1.0000", decision.line.descentFraction);
        assertAmount("0", decision.correctionTtc);
    }

    /**
     * A line below its floor with no manual gesture: clamped up to the floor, reason
     * {@code SRP_FLOOR_CLAMPED}, margin zero, no requested/granted (there is no operator gesture to
     * report) (spec-srp-guard §3.A).
     */
    @Test
    void testBelowFloorNoGestureClamps() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "10.00", List.of(),
                false, false, null, false);
        assertAmount("1.00", decision.line.correction);
        assertAmount("0.00", decision.line.marginAboveFloor);
        assertEquals(SrpGuardFactory.REASON_FLOOR_CLAMPED, decision.line.reasonCode);
        assertNull(decision.line.requestedDiscount);
        assertNull(decision.line.grantedDiscount);
        assertAmount("1.00", decision.correctionTtc);
    }

    /**
     * A line below its floor carrying a manual gesture: clamped, and the record explains why not more
     * — 10.00 asked (20 − 10), 9.00 granted (20 − 11) (spec-srp-guard §3.A).
     */
    @Test
    void testBelowFloorWithGestureReportsRequestedAndGranted() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "10.00", List.of(),
                false, false, null, true);
        assertAmount("1.00", decision.line.correction);
        assertEquals(SrpGuardFactory.REASON_FLOOR_CLAMPED, decision.line.reasonCode);
        assertAmount("10.00", decision.line.requestedDiscount);
        assertAmount("9.00", decision.line.grantedDiscount);
    }

    /**
     * A clamped manual-gesture line whose nominal is unknown: still clamped, but no requested/granted
     * can be computed without a sale-price reference (the null-nominal leg of §3.A).
     */
    @Test
    void testBelowFloorWithGestureButNullNominal() {
        SrpGuardFactory.LineDecision decision = decide("11.00", null, "10.00", List.of(),
                false, false, null, true);
        assertAmount("1.00", decision.line.correction);
        assertEquals(SrpGuardFactory.REASON_FLOOR_CLAMPED, decision.line.reasonCode);
        assertNull(decision.line.requestedDiscount);
        assertNull(decision.line.grantedDiscount);
    }

    /**
     * An overridden line below its floor: the floor is bypassed — no correction even though the net is
     * under the floor — and the derogation is traced with its reason (spec-srp-guard §3.C).
     */
    @Test
    void testOverriddenBelowFloorNotCorrected() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "5.00", List.of(),
                false, true, "Damaged packaging", false);
        assertNull(decision.line.correction);
        assertEquals(SrpGuardFactory.REASON_OVERRIDE, decision.line.reasonCode);
        assertEquals("Damaged packaging", decision.line.overrideReason);
        assertAmount("-6.00", decision.line.marginAboveFloor);
        assertAmount("0", decision.correctionTtc);
    }

    /**
     * An overridden line whose declaration carried no reason: still bypassed and flagged, with no
     * override reason echoed (the blank-reason leg of §3.C).
     */
    @Test
    void testOverriddenWithoutReason() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "5.00", List.of(),
                false, true, "  ", false);
        assertEquals(SrpGuardFactory.REASON_OVERRIDE, decision.line.reasonCode);
        assertNull(decision.line.overrideReason);
    }

    /**
     * An override wins over an srpExempt flag on the same line: the override branch is checked first,
     * so the line is traced as an override (both-true leg).
     */
    @Test
    void testOverrideTakesPrecedenceOverExempt() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "5.00", List.of(),
                true, true, "Damaged", false);
        assertEquals(SrpGuardFactory.REASON_OVERRIDE, decision.line.reasonCode);
        assertNull(decision.line.correction);
    }

    /**
     * An exempt line below its floor: traced with its (negative) margin but never corrected and
     * carrying no reason code, exactly as before this lot (the sales case).
     */
    @Test
    void testExemptBelowFloorNotCorrected() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "8.00", List.of(),
                true, false, null, false);
        assertNull(decision.line.correction);
        assertNull(decision.line.reasonCode);
        assertAmount("-3.00", decision.line.marginAboveFloor);
        assertAmount("0", decision.correctionTtc);
    }

    /**
     * A line whose descent crosses the lower palier only: flagged at 0.50, uncorrected, with the exact
     * descent fraction reported (spec-srp-guard §3.B). Room 20 → 11 = 9; descent 20 − 14 = 6; 6/9 =
     * 0.6667 ≥ 0.50 but &lt; 0.80.
     */
    @Test
    void testWarningCrossesLowerPalier() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "14.00", PALIERS,
                false, false, null, false);
        assertNull(decision.line.correction);
        assertEquals(SrpGuardFactory.REASON_MARGIN_WARNING, decision.line.reasonCode);
        assertAmount("0.50", decision.line.warningThreshold);
        assertAmount("0.6667", decision.line.descentFraction);
    }

    /**
     * A line whose descent crosses the higher palier: flagged at the highest reached, 0.80
     * (spec-srp-guard §3.B). Descent 20 − 12.5 = 7.5; 7.5/9 = 0.8333 ≥ 0.80.
     */
    @Test
    void testWarningCrossesHigherPalier() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "12.50", PALIERS,
                false, false, null, false);
        assertEquals(SrpGuardFactory.REASON_MARGIN_WARNING, decision.line.reasonCode);
        assertAmount("0.80", decision.line.warningThreshold);
    }

    /**
     * A line whose descent stays below every palier: no warning, but the fraction is still reported so
     * the till can show the gauge (the not-crossed leg of §3.B). Descent 20 − 18 = 2; 2/9 = 0.2222.
     */
    @Test
    void testDescentBelowAllPpaliersNoWarning() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "18.00", PALIERS,
                false, false, null, false);
        assertNull(decision.line.reasonCode);
        assertAmount("0.2222", decision.line.descentFraction);
    }

    /**
     * The EGAlim-included net: the decision reads the net AFTER the EGAlim reprise, so a line that
     * EGAlim raised back above its floor is granted (or warned) on that raised net, never clamped
     * (spec-srp-guard §3.2 crossing). Here the EGAlim-raised net 11.00 sits exactly at the floor.
     */
    @Test
    void testEgalimIncludedNetIsHonoured() {
        SrpGuardFactory.LineDecision decision = decide("11.00", "20.00", "11.00", List.of(),
                false, false, null, false);
        assertNull(decision.line.correction);
        assertAmount("0.00", decision.line.marginAboveFloor);
        assertAmount("0", decision.correctionTtc);
    }

    /**
     * {@code descentFraction} returns null on each of its guard legs: no palier configured, a null
     * nominal, and a non-positive room (sale price at or below the floor).
     */
    @Test
    void testDescentFractionNullLegs() {
        assertNull(SrpGuardFactory.SrpGuardApplier.descentFraction(
                new BigDecimal("11.00"), new BigDecimal("20.00"), new BigDecimal("15.00"), List.of()));
        assertNull(SrpGuardFactory.SrpGuardApplier.descentFraction(
                new BigDecimal("11.00"), null, new BigDecimal("15.00"), PALIERS));
        assertNull(SrpGuardFactory.SrpGuardApplier.descentFraction(
                new BigDecimal("11.00"), new BigDecimal("11.00"), new BigDecimal("11.00"), PALIERS));
    }

    /**
     * {@code highestCrossed} returns null when the fraction reaches no palier, the single palier when
     * one is reached, and the highest when several are reached.
     */
    @Test
    void testHighestCrossed() {
        assertNull(SrpGuardFactory.SrpGuardApplier.highestCrossed(PALIERS, new BigDecimal("0.10")));
        assertAmount("0.50", SrpGuardFactory.SrpGuardApplier.highestCrossed(PALIERS, new BigDecimal("0.60")));
        assertAmount("0.80", SrpGuardFactory.SrpGuardApplier.highestCrossed(PALIERS, new BigDecimal("0.95")));
    }

    /**
     * {@code parseWarningThresholds} reads the array and sorts it ascending, and returns an empty list
     * on each empty leg: absent field, non-array field, and a null spec.
     *
     * @throws Exception if the test JSON cannot be parsed.
     */
    @Test
    void testParseWarningThresholds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode withParliers = mapper.readTree("{\"warningThresholds\":[0.8,0.5]}");
        List<BigDecimal> parsed = SrpGuardFactory.parseWarningThresholds(withParliers);
        assertEquals(2, parsed.size());
        assertAmount("0.5", parsed.get(0));
        assertAmount("0.8", parsed.get(1));
        assertTrue(SrpGuardFactory.parseWarningThresholds(mapper.readTree("{}")).isEmpty());
        assertTrue(SrpGuardFactory.parseWarningThresholds(
                mapper.readTree("{\"warningThresholds\":\"nope\"}")).isEmpty());
        assertTrue(SrpGuardFactory.parseWarningThresholds(null).isEmpty());
    }

    /**
     * Guards against a silent record-shape regression: a granted-without-reserve line carries none of
     * the new fields, so an unaffected SRP record stays byte-for-byte as before this lot.
     */
    @Test
    void testUnaffectedLineHasNoNewFields() {
        BasketEvaluation.SrpLine line = decide("11.00", "20.00", "15.00", List.of(),
                false, false, null, false).line;
        assertNull(line.reasonCode);
        assertNull(line.descentFraction);
        assertNull(line.warningThreshold);
        assertNull(line.requestedDiscount);
        assertNull(line.grantedDiscount);
        assertNull(line.overrideReason);
        assertNull(line.correction);
    }
}
