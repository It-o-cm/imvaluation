package com.intermarche.valuation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Configuration of the valuation trace recorder, held in the database.
 * <p>
 * These settings are edited from the administration screens rather than a properties
 * file, so tracing can be turned off or its retention shortened on a running instance
 * without a restart.
 * <p>
 * Exactly one row exists: {@link #current()} creates it with the defaults on first
 * access, and every caller reads that same row.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "valuation_trace_config")
public class ValuationTraceConfig extends BaseEntity {

    /**
     * Retention applied when the configuration row is created.
     */
    public static final int DEFAULT_RETENTION_DAYS = 1;

    /**
     * Whether valuation requests are recorded at all.
     * <p>
     * Turning this off stops new traces from being written; existing ones stay until the
     * purge removes them.
     */
    @Column(name = "is_enabled", nullable = false)
    public boolean enabled = true;

    /**
     * Number of days a trace is kept before the purge deletes it.
     * <p>
     * Payloads are large, so the default is deliberately short.
     */
    @Column(name = "retention_days", nullable = false)
    public int retentionDays = DEFAULT_RETENTION_DAYS;

    /**
     * Returns the single configuration row, creating it with the defaults if absent.
     * <p>
     * Callers must run inside a transaction, since the first access inserts the row.
     *
     * @return The current configuration.
     */
    public static ValuationTraceConfig current() {
        ValuationTraceConfig config = find("order by id").firstResult();
        if (config == null) {
            config = new ValuationTraceConfig();
            config.enabled = true;
            config.retentionDays = DEFAULT_RETENTION_DAYS;
            config.persist();
        }
        return config;
    }

    /**
     * Calculates a checksum based on the configured values.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(enabled, retentionDays);
    }
}
