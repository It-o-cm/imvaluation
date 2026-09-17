package com.intermarche.valuation.engine;

import com.intermarche.valuation.engine.offers.TieredDiscountFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the central injection of the shared {@code trigger}, {@code applicationMoment} and
 * {@code arbitration} fragments by {@link EngineTrait#processSpecification} (spec §3.5), and
 * the cross-field rejections of spec §3.8 raised at creation.
 * <p>
 * A real factory schema ({@link TieredDiscountFactory}, whose top level is
 * {@code additionalProperties: false}) is used so that the blocks would be rejected without
 * the injection — proving the funnel is central and effective, not per factory.
 */
public class EngineTraitTriggerInjectionTest {

    /**
     * The trait under test, an anonymous implementation of the interface.
     */
    private final EngineTrait trait = new EngineTrait() {
    };

    /**
     * The real TIERED_DISCOUNT schema, top-level {@code additionalProperties: false}.
     */
    private final String schema = new TieredDiscountFactory().getSchema();

    /**
     * A valid base TIERED_DISCOUNT specification, without any new block.
     */
    private static final String BASE =
            "\"scope\": \"TICKET\", \"metric\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                    + "\"tiers\": [ { \"threshold\": 50, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5 } } ]";

    /**
     * Validates a specification and reports whether the consumer ran (i.e. it passed).
     *
     * @param spec the specification JSON.
     * @return {@code true} when validation passed and the consumer was invoked.
     */
    private boolean accepts(String spec) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        trait.processSpecification(schema, spec, node -> accepted.set(true));
        return accepted.get();
    }

    /**
     * Tests that a spec without any new block still validates (backward compatibility).
     */
    @Test
    void testBaseSpecStillValid() {
        assertTrue(accepts("{ " + BASE + " }"));
    }

    /**
     * Tests that the injected {@code trigger}, {@code applicationMoment} and
     * {@code arbitration} blocks are accepted despite the schema's
     * {@code additionalProperties: false}.
     */
    @Test
    void testInjectedBlocksAccepted() {
        String spec = "{ " + BASE + ", "
                + "\"applicationMoment\": \"AT_TRIGGER\", "
                + "\"arbitration\": { \"priority\": 100, \"cumulable\": false, "
                + "\"exclusionGroups\": [\"G1\"], \"maxApplicationsPerTicket\": 2, "
                + "\"consumesContributors\": true }, "
                + "\"trigger\": { \"conditions\": [ "
                + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"ITEMS\", \"eans\": [\"1\"], \"threshold\": 40 }, "
                + "{ \"kind\": \"COUPON_CODE\", \"code\": \"HIVER24\" } ] } }";
        assertTrue(accepts(spec));
    }

    /**
     * Tests that scope TICKET with eans is rejected at creation (spec §3.8.2), through the
     * existing "Error validating offer: " channel.
     */
    @Test
    void testTicketWithEansRejected() {
        String spec = "{ " + BASE + ", \"trigger\": { \"conditions\": [ "
                + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"eans\": [\"1\"], \"threshold\": 40 } ] } }";
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> accepts(spec));
        assertTrue(error.getMessage().startsWith("Error validating offer: "));
        assertTrue(error.getMessage().contains("TICKET"));
    }

    /**
     * Tests that scope ITEMS without eans is rejected at creation (spec §3.8.1).
     */
    @Test
    void testItemsWithoutEansRejected() {
        String spec = "{ " + BASE + ", \"trigger\": { \"conditions\": [ "
                + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"ITEMS\", \"threshold\": 40 } ] } }";
        assertThrows(IllegalArgumentException.class, () -> accepts(spec));
    }

    /**
     * Tests that an empty conditions array is rejected by the injected schema (spec §3.8.3).
     */
    @Test
    void testEmptyConditionsRejected() {
        String spec = "{ " + BASE + ", \"trigger\": { \"conditions\": [] } }";
        assertThrows(IllegalArgumentException.class, () -> accepts(spec));
    }

    /**
     * Tests that an unknown condition kind is rejected by the injected schema (spec §3.8.4).
     */
    @Test
    void testUnknownKindRejected() {
        String spec = "{ " + BASE + ", \"trigger\": { \"conditions\": [ { \"kind\": \"MYSTERY\" } ] } }";
        assertThrows(IllegalArgumentException.class, () -> accepts(spec));
    }

    /**
     * Tests that an out-of-enum applicationMoment is rejected by the injected schema
     * (spec §3.8.5).
     */
    @Test
    void testApplicationMomentEnumRejected() {
        String spec = "{ " + BASE + ", \"applicationMoment\": \"WHENEVER\" }";
        assertThrows(IllegalArgumentException.class, () -> accepts(spec));
    }

    /**
     * Tests that an arbitration priority beyond its range is rejected by the injected schema.
     */
    @Test
    void testArbitrationPriorityRangeRejected() {
        String spec = "{ " + BASE + ", \"arbitration\": { \"priority\": 2000 } }";
        assertThrows(IllegalArgumentException.class, () -> accepts(spec));
    }

    /**
     * Tests that the basket schema is left untouched by the injection: a basket carrying an
     * (offer-only) trigger block is still validated by its own permissive schema, and its own
     * required fields still govern.
     */
    @Test
    void testBasketSchemaNotInjected() {
        AtomicBoolean accepted = new AtomicBoolean(false);
        String basket = "{ \"storeCode\": \"S1\", \"items\": [ { \"produceEan\": \"1\", \"quantity\": 1 } ], "
                + "\"couponCodes\": [\"HIVER24\"] }";
        trait.processSpecification(Basket.BASKET_SCHEMA, basket, node -> accepted.set(true));
        assertTrue(accepted.get());
        // A basket missing its required storeCode is still rejected by the basket schema,
        // proving the basket schema itself is intact.
        assertThrows(IllegalArgumentException.class, () -> trait.processSpecification(
                Basket.BASKET_SCHEMA,
                "{ \"items\": [ { \"produceEan\": \"1\", \"quantity\": 1 } ] }",
                node -> {
                }));
    }

    /**
     * Tests that a duplicate EAN in a condition is tolerated and validates (spec §3.8.6).
     */
    @Test
    void testDuplicateEansTolerated() {
        String spec = "{ " + BASE + ", \"trigger\": { \"conditions\": [ "
                + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"1\", \"1\", \"2\"], \"threshold\": 3 } ] } }";
        assertTrue(accepts(spec));
    }

    /**
     * Tests that a coupon condition with a blank code is rejected by the injected schema.
     */
    @Test
    void testBlankCouponCodeRejected() {
        String spec = "{ " + BASE + ", \"trigger\": { \"conditions\": [ "
                + "{ \"kind\": \"COUPON_CODE\", \"code\": \"\" } ] } }";
        assertThrows(IllegalArgumentException.class, () -> accepts(spec));
    }

    /**
     * Tests that the injection does not alter the schema's own validation: a base spec with a
     * genuinely unknown property is still rejected.
     */
    @Test
    void testUnknownPropertyStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> accepts("{ " + BASE + ", \"mystery\": 1 }"));
    }
}
