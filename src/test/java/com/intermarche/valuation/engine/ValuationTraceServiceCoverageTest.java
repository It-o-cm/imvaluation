package com.intermarche.valuation.engine;

import com.intermarche.valuation.CoverageDbReset;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coverage tests for {@link ValuationTraceService}, exercised through {@code @QuarkusTest} so
 * the container attributes the executed lines: real configuration and trace rows are
 * committed, and the {@code @Transactional} service methods run against them.
 */
@QuarkusTest
public class ValuationTraceServiceCoverageTest {

    /**
     * The service under test, injected as the transactional CDI proxy.
     */
    @Inject
    ValuationTraceService service;

    /**
     * Clears the trace and configuration rows after this class so they cannot leak to another
     * coverage class in the shared in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }

    /**
     * Removes every trace and configuration row before each test so each starts from an empty
     * recorder.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        ValuationTrace.deleteAll();
        ValuationTraceConfig.deleteAll();
    }

    /**
     * Commits a single configuration row with the given values.
     *
     * @param enabled       Whether recording is enabled.
     * @param retentionDays The retention window in days.
     */
    private void seedConfig(boolean enabled, int retentionDays) {
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTraceConfig config = new ValuationTraceConfig();
            config.enabled = enabled;
            config.retentionDays = retentionDays;
            config.persist();
        });
    }

    /**
     * Builds a basket carrying the given context and either an item list of the requested size
     * or a null list.
     *
     * @param storeCode The store code.
     * @param itemCount The number of items, or a negative value to leave the list null.
     * @return The populated basket.
     */
    private Basket basket(String storeCode, int itemCount) {
        Basket basket = new Basket();
        basket.storeCode = storeCode;
        basket.customerCode = "C1";
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
     * Counts the committed trace rows.
     *
     * @return The number of persisted traces.
     */
    private long traceCount() {
        return QuarkusTransaction.requiringNew().call(() -> ValuationTrace.count());
    }

    /**
     * Tests that {@link ValuationTraceService#record} persists a full trace when recording is
     * enabled, covering the item-count non-null arm and the evaluation serialization.
     */
    @Test
    void testRecordPersistsWhenEnabled() {
        seedConfig(true, 1);
        BasketEvaluation evaluation = new BasketEvaluation(null);
        evaluation.setTotalPrice(new AmountEvaluation(
                new BigDecimal("10.00"), new BigDecimal("12.50"), new BigDecimal("0.2000")));
        service.record("payload", basket("S1", 2), evaluation,
                200, ValuationTrace.STATUS_SUCCESS, "short", 42L);
        assertEquals(1L, traceCount());
    }

    /**
     * Tests that {@link ValuationTraceService#record} records a count of zero when a non-null
     * basket carries a null item list, covering the null arm of the item-count ternary.
     */
    @Test
    void testRecordWithNullItemsCountsZero() {
        seedConfig(true, 1);
        service.record("payload", basket("S2", -1), null,
                200, ValuationTrace.STATUS_SUCCESS, null, 5L);
        Integer count = QuarkusTransaction.requiringNew().call(() ->
                ((ValuationTrace) ValuationTrace.findAll().firstResult()).itemCount);
        assertEquals(Integer.valueOf(0), count);
    }

    /**
     * Tests that {@link ValuationTraceService#record} writes nothing when recording is
     * disabled, covering the early return.
     */
    @Test
    void testRecordSkipsWhenDisabled() {
        seedConfig(false, 1);
        service.record("payload", basket("S3", 1), null,
                200, ValuationTrace.STATUS_SUCCESS, null, 1L);
        assertEquals(0L, traceCount());
    }

    /**
     * Tests that {@link ValuationTraceService#record} truncates an over-long error message to
     * exactly 2000 characters before persisting it.
     */
    @Test
    void testRecordTruncatesLongError() {
        seedConfig(true, 1);
        service.record("payload", null, null,
                500, ValuationTrace.STATUS_FAILED, "x".repeat(2001), 1L);
        String message = QuarkusTransaction.requiringNew().call(() ->
                ((ValuationTrace) ValuationTrace.findAll().firstResult()).errorMessage);
        assertEquals("x".repeat(1997) + "...", message);
        assertEquals(2000, message.length());
    }

    /**
     * Tests that {@link ValuationTraceService#purgeExpired()} deletes a trace older than the
     * retention window, covering the deleted-rows arm.
     */
    @Test
    void testPurgeExpiredDeletesOldTrace() {
        seedConfig(true, 1);
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.now().minusDays(10));
            QuarkusTransaction.requiringNew().run(() -> {
                ValuationTrace trace = new ValuationTrace();
                trace.status = ValuationTrace.STATUS_SUCCESS;
                trace.requestPayload = "old";
                trace.persist();
            });
        } finally {
            DateTimeProvider.clear();
        }
        service.purgeExpired();
        assertEquals(0L, traceCount());
    }

    /**
     * Tests that {@link ValuationTraceService#purgeAll()} deletes every trace and returns the
     * removed count.
     */
    @Test
    void testPurgeAllDeletesEveryTrace() {
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace first = new ValuationTrace();
            first.status = ValuationTrace.STATUS_SUCCESS;
            first.persist();
            ValuationTrace second = new ValuationTrace();
            second.status = ValuationTrace.STATUS_FAILED;
            second.persist();
        });
        long deleted = service.purgeAll();
        assertEquals(2L, deleted);
        assertEquals(0L, traceCount());
    }
}
