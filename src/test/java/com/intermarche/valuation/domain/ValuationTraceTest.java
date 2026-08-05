package com.intermarche.valuation.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ValuationTrace}.
 * <p>
 * No {@code @QuarkusTest}, no database: the inherited Panache static finders (resolving to
 * {@link PanacheEntityBase} under plain {@code mvn test}) are mocked with
 * {@link org.mockito.Mockito#mockStatic}, and the display and checksum logic is exercised
 * against in-memory instances with absolute expected values.
 */
class ValuationTraceTest {

    // --------------------------------------------------
    // deleteOlderThan
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTrace#deleteOlderThan(LocalDateTime)} delegates to the Panache
     * delete query with the {@code createdAt < ?1} clause and returns the deleted-row count.
     */
    @Test
    void deleteOlderThan_shouldDelegateToPanacheDelete() {
        LocalDateTime threshold = LocalDateTime.of(2026, 8, 5, 10, 0);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.delete("createdAt < ?1", threshold)).thenReturn(7L);
            assertEquals(7L, ValuationTrace.deleteOlderThan(threshold));
        }
    }

    // --------------------------------------------------
    // findLatest
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTrace#findLatest(int)} orders by creation date descending, pages
     * to the requested size and returns the query result list.
     */
    @Test
    void findLatest_shouldReturnPagedDescendingList() {
        List<ValuationTrace> expected = List.of(new ValuationTrace(), new ValuationTrace());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ValuationTrace> query = mock(PanacheQuery.class);
            when(query.page(any(Page.class))).thenReturn(query);
            when(query.list()).thenReturn(expected);
            mocked.when(() -> PanacheEntityBase.find("order by createdAt desc")).thenReturn(query);
            assertSame(expected, ValuationTrace.findLatest(5));
        }
    }

    // --------------------------------------------------
    // isSuccess
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTrace#isSuccess()} returns {@code true} when the status equals
     * {@link ValuationTrace#STATUS_SUCCESS} (equals branch true).
     */
    @Test
    void isSuccess_shouldReturnTrue_whenStatusIsSuccess() {
        ValuationTrace trace = new ValuationTrace();
        trace.status = ValuationTrace.STATUS_SUCCESS;
        assertTrue(trace.isSuccess());
    }

    /**
     * Tests that {@link ValuationTrace#isSuccess()} returns {@code false} for a non-success
     * status (equals branch false).
     */
    @Test
    void isSuccess_shouldReturnFalse_whenStatusIsFailed() {
        ValuationTrace trace = new ValuationTrace();
        trace.status = ValuationTrace.STATUS_FAILED;
        assertFalse(trace.isSuccess());
    }

    /**
     * Tests that {@link ValuationTrace#isSuccess()} returns {@code false} when the status is null
     * (equals branch false against a null argument).
     */
    @Test
    void isSuccess_shouldReturnFalse_whenStatusIsNull() {
        ValuationTrace trace = new ValuationTrace();
        trace.status = null;
        assertFalse(trace.isSuccess());
    }

    // --------------------------------------------------
    // getSummary
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTrace#getSummary()} keeps the store code and pluralizes the line
     * count, covering the non-null arm of the store ternary, the non-null arm of the count
     * ternary and the {@code count != 1} arm of the pluralization.
     */
    @Test
    void getSummary_shouldKeepStoreAndPluralize_whenSeveralLines() {
        ValuationTrace trace = new ValuationTrace();
        trace.storeCode = "ST1";
        trace.itemCount = 3;
        assertEquals("ST1 · 3 lines", trace.getSummary());
    }

    /**
     * Tests that {@link ValuationTrace#getSummary()} uses the singular form for exactly one line,
     * covering the {@code count == 1} arm of the pluralization.
     */
    @Test
    void getSummary_shouldUseSingular_whenOneLine() {
        ValuationTrace trace = new ValuationTrace();
        trace.storeCode = "ST1";
        trace.itemCount = 1;
        assertEquals("ST1 · 1 line", trace.getSummary());
    }

    /**
     * Tests that {@link ValuationTrace#getSummary()} falls back to placeholders when the store
     * code and item count are null, covering the null arms of both ternaries and the
     * {@code count != 1} arm with the defaulted zero.
     */
    @Test
    void getSummary_shouldFallBack_whenStoreAndCountNull() {
        ValuationTrace trace = new ValuationTrace();
        trace.storeCode = null;
        trace.itemCount = null;
        assertEquals("unknown store · 0 lines", trace.getSummary());
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTrace#getChecksum()} hashes exactly the declared traced-exchange
     * fields in order.
     */
    @Test
    void getChecksum_shouldHashTracedExchangeFields() {
        ValuationTrace trace = new ValuationTrace();
        trace.storeCode = "ST1";
        trace.customerCode = "CUST9";
        trace.status = ValuationTrace.STATUS_SUCCESS;
        trace.httpStatus = 200;
        trace.requestPayload = "{\"basket\":[]}";
        int expected = Objects.hash("ST1", "CUST9", ValuationTrace.STATUS_SUCCESS, 200, "{\"basket\":[]}");
        assertEquals(expected, trace.getChecksum());
    }
}
