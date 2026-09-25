package com.intermarche.valuation.engine;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Jackson serializer for a quantity restituted in the response (spec A5 §1.4).
 * <p>
 * A quantity is normalized to at most three decimals (the gram, the precision of the
 * scales) with {@code setScale(3, HALF_UP)}, its useless trailing zeros stripped, and
 * written in plain notation. The plain notation matters: {@code stripTrailingZeros()} on a
 * round value such as {@code 100} yields the internal form {@code 1E+2}, and writing the
 * raw numeric token from {@link BigDecimal#toPlainString()} guarantees the response carries
 * {@code 100} rather than {@code 1E+2}, independently of any global mapper flag.
 */
public class QuantitySerializer extends JsonSerializer<BigDecimal> {

    /**
     * Serializes a quantity as a normalized, plain-notation JSON number.
     *
     * @param value       The quantity to serialize.
     * @param gen         The generator to write to.
     * @param serializers The provider (unused).
     * @throws IOException When the underlying generator fails.
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (value.signum() == 0) {
            gen.writeNumber("0");
            return;
        }
        gen.writeNumber(value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    }
}
