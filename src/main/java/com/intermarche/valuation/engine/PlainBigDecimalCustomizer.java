package com.intermarche.valuation.engine;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;

import jakarta.inject.Singleton;

/**
 * Serializes every {@link java.math.BigDecimal} in plain notation (spec A5 §1.4).
 * <p>
 * Since the A5 migration, quantities are {@link java.math.BigDecimal} and a normalized one
 * can carry the internal scientific form ({@code 100} becomes {@code 1E+2} after
 * {@code stripTrailingZeros()}). Enabling {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN}
 * globally guarantees the till never receives scientific notation for any decimal — amounts
 * (already at scale 2) are unaffected, as they never reach scientific form.
 */
@Singleton
public class PlainBigDecimalCustomizer implements ObjectMapperCustomizer {

    /**
     * Enables plain {@link java.math.BigDecimal} notation on the application mapper.
     *
     * @param objectMapper The mapper to customize.
     */
    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
