package com.intermarche.valuation.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ValuationTraceConfig}.
 * <p>
 * No {@code @QuarkusTest}, no database: the inherited Panache static finder (resolving to
 * {@link PanacheEntityBase} under plain {@code mvn test}) is mocked with
 * {@link org.mockito.Mockito#mockStatic}, and {@code persist()} on the freshly created row is
 * neutralized with {@link org.mockito.Mockito#mockConstruction}. Both arms of the {@code current()}
 * null guard and the checksum logic are exercised against in-memory instances with absolute
 * expected values.
 */
class ValuationTraceConfigTest {

    // --------------------------------------------------
    // current
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTraceConfig#current()} returns the existing row unchanged when the
     * finder already yields one, covering the {@code config != null} arm of the null guard: no new
     * instance is created and nothing is persisted.
     */
    @Test
    void current_shouldReturnExistingRow_whenPresent() {
        ValuationTraceConfig existing = new ValuationTraceConfig();
        existing.enabled = false;
        existing.retentionDays = 42;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ValuationTraceConfig> constructed = mockConstruction(ValuationTraceConfig.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ValuationTraceConfig> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            mocked.when(() -> PanacheEntityBase.find("order by id")).thenReturn(query);
            ValuationTraceConfig result = ValuationTraceConfig.current();
            assertSame(existing, result);
            assertTrue(constructed.constructed().isEmpty());
        }
    }

    /**
     * Tests that {@link ValuationTraceConfig#current()} creates the row with the defaults and
     * persists it when the finder yields nothing, covering the {@code config == null} arm of the
     * null guard: exactly one instance is constructed, enabled with the default retention, and
     * persisted once.
     */
    @Test
    void current_shouldCreateDefaultRow_whenAbsent() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ValuationTraceConfig> constructed = mockConstruction(ValuationTraceConfig.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ValuationTraceConfig> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            mocked.when(() -> PanacheEntityBase.find("order by id")).thenReturn(query);
            ValuationTraceConfig result = ValuationTraceConfig.current();
            assertEquals(1, constructed.constructed().size());
            assertSame(constructed.constructed().get(0), result);
            assertTrue(result.enabled);
            assertEquals(ValuationTraceConfig.DEFAULT_RETENTION_DAYS, result.retentionDays);
            verify(result, times(1)).persist();
        }
    }

    /**
     * Tests that {@link ValuationTraceConfig#DEFAULT_RETENTION_DAYS} is the deliberately short
     * one-day retention that the created row inherits.
     */
    @Test
    void defaultRetentionDays_shouldBeOneDay() {
        assertEquals(1, ValuationTraceConfig.DEFAULT_RETENTION_DAYS);
    }

    /**
     * Tests that a freshly instantiated {@link ValuationTraceConfig} carries the field defaults
     * declared on the entity (enabled, default retention), independently of the finder path.
     */
    @Test
    void newInstance_shouldCarryFieldDefaults() {
        ValuationTraceConfig config = new ValuationTraceConfig();
        assertTrue(config.enabled);
        assertEquals(ValuationTraceConfig.DEFAULT_RETENTION_DAYS, config.retentionDays);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    /**
     * Tests that {@link ValuationTraceConfig#getChecksum()} hashes exactly the enabled flag and the
     * retention days, in that order, for an enabled row.
     */
    @Test
    void getChecksum_shouldHashEnabledAndRetention_whenEnabled() {
        ValuationTraceConfig config = new ValuationTraceConfig();
        config.enabled = true;
        config.retentionDays = 7;
        int expected = Objects.hash(true, 7);
        assertEquals(expected, config.getChecksum());
    }

    /**
     * Tests that {@link ValuationTraceConfig#getChecksum()} reflects the disabled flag, producing a
     * different hash from the enabled row with the same retention.
     */
    @Test
    void getChecksum_shouldHashEnabledAndRetention_whenDisabled() {
        ValuationTraceConfig config = new ValuationTraceConfig();
        config.enabled = false;
        config.retentionDays = 7;
        int expected = Objects.hash(false, 7);
        assertEquals(expected, config.getChecksum());
        assertFalse(config.getChecksum() == Objects.hash(true, 7));
    }

    /**
     * Tests that {@link ValuationTraceConfig#current()} never persists when a row already exists,
     * pinning the read-only nature of the hit path.
     */
    @Test
    void current_shouldNotPersist_whenRowExists() {
        ValuationTraceConfig existing = new ValuationTraceConfig();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ValuationTraceConfig> constructed = mockConstruction(ValuationTraceConfig.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ValuationTraceConfig> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            mocked.when(() -> PanacheEntityBase.find("order by id")).thenReturn(query);
            ValuationTraceConfig.current();
            assertTrue(constructed.constructed().isEmpty());
            verify(query, never()).list();
        }
    }
}
