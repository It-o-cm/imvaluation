package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Test class for {@link ValuationTraceService}.
 * <p>
 * The service reads its behaviour from {@link ValuationTraceConfig#current()} and writes
 * through the {@link ValuationTrace} active-record entity. Both are mocked: the static
 * domain methods with {@link org.mockito.Mockito#mockStatic}, and the {@code new
 * ValuationTrace().persist()} write with {@link org.mockito.Mockito#mockConstruction}, so
 * the pure branching logic is exercised without a database.
 * <p>
 * Enumerated branches (11 total, 22 arms):
 * <ul>
 *   <li>{@code record}: the {@code try/catch} guard;</li>
 *   <li>{@code record}: {@code enabled} true/false;</li>
 *   <li>{@code record}: {@code basket != null} true/false;</li>
 *   <li>{@code record}: {@code items == null} ternary, both arms;</li>
 *   <li>{@code record}: {@code evaluation != null} true/false;</li>
 *   <li>{@code record}: {@code getTotalPrice() != null} true/false;</li>
 *   <li>{@code truncate}: {@code message == null} arm, short arm, long arm;</li>
 *   <li>{@code purgeExpired}: the {@code try/catch} guard;</li>
 *   <li>{@code purgeExpired}: {@code deleted > 0} true/false;</li>
 *   <li>{@code purgeAll}: straight-line, no branch.</li>
 * </ul>
 */
public class ValuationTraceServiceTest {

    /**
     * Builds a configuration row with the supplied values, used as the return of the mocked
     * {@link ValuationTraceConfig#current()}.
     *
     * @param enabled       Whether tracing is enabled.
     * @param retentionDays Retention window in days.
     * @return A detached configuration instance.
     */
    private ValuationTraceConfig config(boolean enabled, int retentionDays) {
        ValuationTraceConfig config = new ValuationTraceConfig();
        config.enabled = enabled;
        config.retentionDays = retentionDays;
        return config;
    }

    /**
     * Builds a basket carrying the given context and item count.
     *
     * @param storeCode    The store code.
     * @param customerCode The customer code.
     * @param itemCount    The number of lines to add, or a negative value to leave the list null.
     * @return The populated basket.
     */
    private Basket basket(String storeCode, String customerCode, int itemCount) {
        Basket basket = new Basket();
        basket.storeCode = storeCode;
        basket.customerCode = customerCode;
        if (itemCount >= 0) {
            List<Basket.Item> items = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                items.add(new Basket.Item());
            }
            basket.items = items;
        }
        return basket;
    }

    /**
     * Verifies that no trace is written when tracing is disabled: the method returns before
     * constructing a {@link ValuationTrace}.
     */
    @Test
    void testRecordSkipsWhenTracingDisabled() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(false, 1));
            new ValuationTraceService().record("payload", basket("S1", "C1", 2), null,
                    200, ValuationTrace.STATUS_SUCCESS, null, 12L);
            assertTrue(traces.constructed().isEmpty());
        }
    }

    /**
     * Verifies the full happy path: every column is filled, the payload and total price are
     * serialized from a non-null evaluation, and the trace is persisted exactly once.
     */
    @Test
    void testRecordPersistsFullTraceWhenEnabled() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            BasketEvaluation evaluation = new BasketEvaluation(null);
            evaluation.setTotalPrice(new AmountEvaluation(
                    new BigDecimal("10.00"), new BigDecimal("12.50"), new BigDecimal("0.2000")));
            new ValuationTraceService().record("the-payload", basket("S1", "C1", 3), evaluation,
                    200, ValuationTrace.STATUS_SUCCESS, "short message", 42L);
            assertEquals(1, traces.constructed().size());
            ValuationTrace trace = traces.constructed().get(0);
            assertEquals("the-payload", trace.requestPayload);
            assertEquals(Integer.valueOf(200), trace.httpStatus);
            assertEquals(ValuationTrace.STATUS_SUCCESS, trace.status);
            assertEquals("short message", trace.errorMessage);
            assertEquals(Long.valueOf(42L), trace.durationMs);
            assertEquals("S1", trace.storeCode);
            assertEquals("C1", trace.customerCode);
            assertEquals(Integer.valueOf(3), trace.itemCount);
            assertEquals(new BigDecimal("12.50"), trace.totalIncludingTax);
            assertEquals("{\"offers\":[],\"advantages\":[],\"totalPrice\":{\"amountExcludingTax\":10.00,"
                    + "\"amountIncludingTax\":12.50,\"vatRate\":0.2000},\"vatBreakdown\":[]}",
                    trace.responsePayload);
            verify(trace).persist();
        }
    }

    /**
     * Verifies that a null basket leaves the context columns unset while the outcome columns
     * are still filled and the trace is persisted.
     */
    @Test
    void testRecordWithNullBasketLeavesContextUnset() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            new ValuationTraceService().record("payload", null, null,
                    500, ValuationTrace.STATUS_FAILED, null, 7L);
            ValuationTrace trace = traces.constructed().get(0);
            assertNull(trace.storeCode);
            assertNull(trace.customerCode);
            assertNull(trace.itemCount);
            assertNull(trace.errorMessage);
            assertNull(trace.responsePayload);
            assertNull(trace.totalIncludingTax);
            assertEquals(ValuationTrace.STATUS_FAILED, trace.status);
            verify(trace).persist();
        }
    }

    /**
     * Verifies the {@code items == null} arm of the item-count ternary: a non-null basket
     * with a null item list records a count of zero.
     */
    @Test
    void testRecordWithNullBasketItemsCountsZero() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            new ValuationTraceService().record("payload", basket("S2", "C2", -1), null,
                    200, ValuationTrace.STATUS_SUCCESS, null, 5L);
            ValuationTrace trace = traces.constructed().get(0);
            assertEquals("S2", trace.storeCode);
            assertEquals("C2", trace.customerCode);
            assertEquals(Integer.valueOf(0), trace.itemCount);
            verify(trace).persist();
        }
    }

    /**
     * Verifies the {@code evaluation == null} arm: no response payload nor total price is set.
     */
    @Test
    void testRecordWithNullEvaluationLeavesResponseUnset() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            new ValuationTraceService().record("payload", basket("S3", "C3", 1), null,
                    200, ValuationTrace.STATUS_SUCCESS, "msg", 9L);
            ValuationTrace trace = traces.constructed().get(0);
            assertNull(trace.responsePayload);
            assertNull(trace.totalIncludingTax);
            assertEquals(Integer.valueOf(1), trace.itemCount);
            verify(trace).persist();
        }
    }

    /**
     * Verifies the {@code getTotalPrice() == null} arm: a non-null evaluation with no total
     * price serializes the response payload but leaves the total-including-tax column unset.
     */
    @Test
    void testRecordWithEvaluationButNoTotalPrice() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            BasketEvaluation evaluation = new BasketEvaluation(null);
            new ValuationTraceService().record("payload", basket("S4", "C4", 1), evaluation,
                    200, ValuationTrace.STATUS_SUCCESS, "msg", 3L);
            ValuationTrace trace = traces.constructed().get(0);
            assertEquals("{\"offers\":[],\"advantages\":[],\"totalPrice\":null,\"vatBreakdown\":[]}",
                    trace.responsePayload);
            assertNull(trace.totalIncludingTax);
            verify(trace).persist();
        }
    }

    /**
     * Verifies the long arm of {@code truncate}: a message longer than 2000 characters is cut
     * to 1997 characters followed by an ellipsis, for a total of 2000 characters.
     */
    @Test
    void testRecordTruncatesLongErrorMessage() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            String longMessage = "x".repeat(2001);
            new ValuationTraceService().record("payload", null, null,
                    500, ValuationTrace.STATUS_FAILED, longMessage, 1L);
            ValuationTrace trace = traces.constructed().get(0);
            assertEquals("x".repeat(1997) + "...", trace.errorMessage);
            assertEquals(2000, trace.errorMessage.length());
            verify(trace).persist();
        }
    }

    /**
     * Verifies the boundary short arm of {@code truncate}: a message of exactly 2000
     * characters is kept verbatim.
     */
    @Test
    void testRecordKeepsErrorMessageAtBoundary() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            String boundaryMessage = "y".repeat(2000);
            new ValuationTraceService().record("payload", null, null,
                    500, ValuationTrace.STATUS_FAILED, boundaryMessage, 1L);
            ValuationTrace trace = traces.constructed().get(0);
            assertEquals(boundaryMessage, trace.errorMessage);
            verify(trace).persist();
        }
    }

    /**
     * Verifies that any failure during recording is swallowed: when {@code current()} throws,
     * the method returns normally and no trace is constructed.
     */
    @Test
    void testRecordSwallowsExceptions() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedConstruction<ValuationTrace> traces = mockConstruction(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenThrow(new RuntimeException("boom"));
            new ValuationTraceService().record("payload", basket("S5", "C5", 1), null,
                    200, ValuationTrace.STATUS_SUCCESS, null, 1L);
            assertTrue(traces.constructed().isEmpty());
        }
    }

    /**
     * Verifies the {@code deleted > 0} arm of {@code purgeExpired}: the retention threshold is
     * computed from the configured window and passed to the delete query.
     */
    @Test
    void testPurgeExpiredDeletesAndLogsWhenRowsRemoved() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedStatic<ValuationTrace> traces = mockStatic(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 3));
            traces.when(() -> ValuationTrace.deleteOlderThan(any())).thenReturn(4L);
            LocalDateTime expected = LocalDateTime.now().minusDays(3);
            new ValuationTraceService().purgeExpired();
            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            traces.verify(() -> ValuationTrace.deleteOlderThan(captor.capture()));
            LocalDateTime threshold = captor.getValue();
            assertTrue(threshold.isAfter(expected.minusMinutes(1)));
            assertTrue(threshold.isBefore(expected.plusMinutes(1)));
        }
    }

    /**
     * Verifies the {@code deleted == 0} arm of {@code purgeExpired}: the query still runs but
     * nothing is logged, and the method returns normally.
     */
    @Test
    void testPurgeExpiredDoesNothingWhenNoRowsRemoved() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedStatic<ValuationTrace> traces = mockStatic(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 2));
            traces.when(() -> ValuationTrace.deleteOlderThan(any())).thenReturn(0L);
            new ValuationTraceService().purgeExpired();
            traces.verify(() -> ValuationTrace.deleteOlderThan(any()));
        }
    }

    /**
     * Verifies that a failure during the purge is swallowed: when the delete query throws, the
     * method returns normally rather than propagating the exception.
     */
    @Test
    void testPurgeExpiredSwallowsExceptions() {
        try (MockedStatic<ValuationTraceConfig> cfg = mockStatic(ValuationTraceConfig.class);
             MockedStatic<ValuationTrace> traces = mockStatic(ValuationTrace.class)) {
            cfg.when(ValuationTraceConfig::current).thenReturn(config(true, 1));
            traces.when(() -> ValuationTrace.deleteOlderThan(any()))
                    .thenThrow(new RuntimeException("boom"));
            new ValuationTraceService().purgeExpired();
            traces.verify(() -> ValuationTrace.deleteOlderThan(any()));
        }
    }

    /**
     * Verifies that {@code purgeAll} deletes every trace and returns the count reported by the
     * entity.
     */
    @Test
    void testPurgeAllDeletesEveryTraceAndReturnsCount() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::deleteAll).thenReturn(9L);
            long deleted = new ValuationTraceService().purgeAll();
            assertEquals(9L, deleted);
            panache.verify(PanacheEntityBase::deleteAll);
        }
    }
}
