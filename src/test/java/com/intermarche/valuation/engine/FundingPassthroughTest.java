package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@code funding} passthrough on the valuation output (spec-exploitation §3,
 * §5.3, §5.5).
 * <p>
 * {@code funding} is a field of the shared offer-spec fragment, copied verbatim (like
 * {@code accountingCode}) onto every {@link DiscountApplication} of the output. These tests drive a
 * real {@code TICKET_DISCOUNT} through {@link ValuationEngine#evaluate} and assert the value reaches
 * the discount and its serialized JSON, that its absence leaves the output unchanged, and that the
 * evaluation is deterministic over 50 runs.
 */
@QuarkusTest
@TestTransaction
public class FundingPassthroughTest {

    /**
     * The engine under test.
     */
    @Inject
    ValuationEngine engine;

    /**
     * The JSON mapper, to assert the serialized output.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The store the offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by every test.
     */
    private void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_FUND", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a DEFAULT and a BASE_FOR_DISCOUNT price at zero VAT.
     *
     * @param ean   the product EAN.
     * @param price the price, tax excluded and included alike.
     */
    private void seedProduct(String ean, String price) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        BigDecimal amount = new BigDecimal(price);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, amount, amount, BigDecimal.ZERO);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT, amount, amount, BigDecimal.ZERO);
    }

    /**
     * Builds a closed basket on the seeded store from the given lines.
     *
     * @param items the basket lines.
     * @return the basket.
     */
    private Basket basket(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_FUND";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Returns the first discount application of an evaluation.
     *
     * @param evaluation the evaluation.
     * @return the first discount application, or null when there is none.
     */
    private DiscountApplication firstDiscount(BasketEvaluation evaluation) {
        for (AdvantageApplication advantage : evaluation.getAdvantages()) {
            if (advantage instanceof DiscountApplication discount) {
                return discount;
            }
        }
        return null;
    }

    /**
     * §5.3: a {@code funding} declared on the offer reaches the discount application and its
     * serialized JSON, copied verbatim.
     *
     * @throws Exception if the evaluation cannot be serialized.
     */
    @Test
    void testFundingPresent_CarriedOntoDiscountAndJson() throws Exception {
        setUpDatabase();
        seedProduct("FUND_G", "50.00");
        DomainUtils.createAndPersistOffer("FUND_5", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"AMOUNT\", \"value\": 5, \"funding\": \"BANNER\", "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"threshold\": 50 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("FUND_G", 1.0)));
        DiscountApplication discount = firstDiscount(evaluation);
        assertEquals("BANNER", discount.getFunding());
        String json = MAPPER.writeValueAsString(evaluation);
        assertTrue(json.contains("\"funding\":\"BANNER\""), "the serialized discount must carry funding");
    }

    /**
     * §5.3 / §4 case 3: an offer without {@code funding} leaves the discount funding null and drops it
     * from the serialized output — bit-for-bit identical to before the feature.
     *
     * @throws Exception if the evaluation cannot be serialized.
     */
    @Test
    void testFundingAbsent_BitForBitIdentical() throws Exception {
        setUpDatabase();
        seedProduct("FUND_G", "50.00");
        DomainUtils.createAndPersistOffer("FUND_NO", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"AMOUNT\", \"value\": 5, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"threshold\": 50 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("FUND_G", 1.0)));
        DiscountApplication discount = firstDiscount(evaluation);
        assertNull(discount.getFunding());
        String json = MAPPER.writeValueAsString(evaluation);
        assertFalse(json.contains("funding"), "no funding key must appear when the offer declares none");
    }

    /**
     * §5.5 (determinism): the same basket, valued 50 times, yields byte-for-byte identical output —
     * the funding fragment crossing the evaluation introduces no non-determinism.
     *
     * @throws Exception if an evaluation cannot be serialized.
     */
    @Test
    void testDeterministic_FiftyRuns() throws Exception {
        setUpDatabase();
        seedProduct("FUND_G", "50.00");
        DomainUtils.createAndPersistOffer("FUND_5", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"AMOUNT\", \"value\": 5, \"funding\": \"SPLIT_50_50\", "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"threshold\": 50 } ] } }");
        String reference = MAPPER.writeValueAsString(
                engine.evaluate(basket(DomainUtils.createItem("FUND_G", 1.0))));
        assertTrue(reference.contains("\"funding\":\"SPLIT_50_50\""));
        for (int run = 0; run < 50; run++) {
            String json = MAPPER.writeValueAsString(
                    engine.evaluate(basket(DomainUtils.createItem("FUND_G", 1.0))));
            assertEquals(reference, json, "run " + run + " must match the reference output");
        }
    }
}
