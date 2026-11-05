package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.ValuationEngine;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MessageAdvantageFactory} (spec-messages-editique §2): a MESSAGE advantage says
 * something at the till, resolves its variables, and never touches the money.
 */
@QuarkusTest
@TestTransaction
public class MessageAdvantageFactoryTest {

    /**
     * The engine, driven end to end.
     */
    @Inject
    ValuationEngine engine;

    /**
     * A plain mapper for the determinism assertion.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The store the products and messages are attached to.
     */
    private Store store;

    /**
     * Seeds the store and a single 50.00 product (0% VAT), so a one-unit basket totals 50.00.
     */
    private void setUp() {
        store = DomainUtils.createAndPersistStore("MSG_STORE", 48.0, 2.0);
        Product product = DomainUtils.createAndPersistProduct("MSGP", "Message Product", ProductType.UNIT);
        BigDecimal price = new BigDecimal("50.00");
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, price, price, BigDecimal.ZERO);
    }

    /**
     * Builds a MESSAGE specification.
     *
     * @param text        the message text.
     * @param showWhen    the show mode, or null for the default.
     * @param trigger     the trigger JSON, or null.
     * @param arbitration the arbitration JSON, or null.
     * @return the specification.
     */
    private String spec(String text, String showWhen, String trigger, String arbitration) {
        StringBuilder s = new StringBuilder("{ \"text\": \"" + text + "\"");
        if (showWhen != null) {
            s.append(", \"showWhen\": \"").append(showWhen).append("\"");
        }
        if (trigger != null) {
            s.append(", \"trigger\": ").append(trigger);
        }
        if (arbitration != null) {
            s.append(", \"arbitration\": ").append(arbitration);
        }
        return s.append(" }").toString();
    }

    /**
     * A TICKET minimum-amount trigger of the given threshold.
     *
     * @param threshold the threshold.
     * @return the trigger JSON.
     */
    private String ticketTrigger(String threshold) {
        return "{ \"conditions\": [ { \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", "
                + "\"threshold\": " + threshold + " } ] }";
    }

    /**
     * Builds a closed basket of a given quantity of the seeded product.
     *
     * @param quantity the quantity.
     * @return the basket.
     */
    private Basket basket(double quantity) {
        Basket basket = new Basket();
        basket.storeCode = "MSG_STORE";
        basket.items = new ArrayList<>(List.of(DomainUtils.createItem("MSGP", quantity)));
        return basket;
    }

    /**
     * §2.1: WHEN_TRIGGERED — the message shows with resolved variables when the threshold is reached.
     */
    @Test
    void testWhenTriggered_present() {
        setUp();
        DomainUtils.createAndPersistOffer("MSG1", store, "MESSAGE",
                spec("Total {{total}} at {{storeName}}", null, ticketTrigger("40"), null));
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        List<BasketEvaluation.MessageLine> messages = evaluation.getMessages();
        assertNotNull(messages, "a triggered message must be present");
        assertEquals(1, messages.size());
        assertEquals("MSG1", messages.get(0).code);
        assertEquals("Total 50.00 at " + store.name, messages.get(0).text);
    }

    /**
     * §2.1: WHEN_TRIGGERED — the message is absent when the threshold is not reached.
     */
    @Test
    void testWhenTriggered_absentBelowThreshold() {
        setUp();
        DomainUtils.createAndPersistOffer("MSG1", store, "MESSAGE",
                spec("Total {{total}}", null, ticketTrigger("100"), null));
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        assertNull(evaluation.getMessages(), "an unsatisfied WHEN_TRIGGERED message is absent");
    }

    /**
     * §2.2: BELOW_TRIGGER — below the threshold the message shows the exact amount still missing.
     */
    @Test
    void testBelowTrigger_showsMissingAmount() {
        setUp();
        DomainUtils.createAndPersistOffer("MSG_INCITE", store, "MESSAGE",
                spec("Plus que {{missingAmount}} EUR", "BELOW_TRIGGER", ticketTrigger("100"), null));
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        List<BasketEvaluation.MessageLine> messages = evaluation.getMessages();
        assertNotNull(messages);
        assertEquals("Plus que 50.00 EUR", messages.get(0).text, "100.00 threshold minus 50.00 total");
    }

    /**
     * §2.2: BELOW_TRIGGER — at or above the threshold the incentive message is absent.
     */
    @Test
    void testBelowTrigger_absentAboveThreshold() {
        setUp();
        DomainUtils.createAndPersistOffer("MSG_INCITE", store, "MESSAGE",
                spec("Plus que {{missingAmount}} EUR", "BELOW_TRIGGER", ticketTrigger("40"), null));
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        assertNull(evaluation.getMessages(), "above the threshold a BELOW_TRIGGER message is absent");
    }

    /**
     * §2.3: an unknown variable fails the configuration closed — it is skipped and traced, and the
     * evaluation is otherwise intact (the total is unchanged, no message emitted).
     */
    @Test
    void testUnknownVariable_configurationSkipped() {
        setUp();
        DomainUtils.createAndPersistOffer("MSG_BAD", store, "MESSAGE",
                spec("Hello {{mystery}}", null, ticketTrigger("40"), null));
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        assertNull(evaluation.getMessages(), "a message with an unknown variable is not emitted");
        assertEquals(0, new BigDecimal("50.00").compareTo(evaluation.getTotalPrice().amountIncludingTax),
                "the evaluation is intact");
        assertTrue(evaluation.getSkippedConfigurations().stream().anyMatch(m -> m.contains("MSG_BAD")),
                "the faulty configuration is traced");
    }

    /**
     * §2.4: a basket with no applicable message carries no {@code messages} block, and the monetary
     * {@code advantages} are untouched by the message machinery.
     */
    @Test
    void testNoMessage_noBlock() {
        setUp();
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        assertNull(evaluation.getMessages());
        assertTrue(evaluation.getVisibleAdvantages().isEmpty(), "no advantage on a plain basket");
    }

    /**
     * §2.5: a MESSAGE carries no monetary effect — the total is the shelf total, the message is not in
     * the visible advantages, and its printMode is restituted.
     */
    @Test
    void testMessage_noMonetaryEffectAndPrintMode() {
        setUp();
        DomainUtils.createAndPersistOffer("MSG_FOOT", store, "MESSAGE",
                "{ \"text\": \"Thanks\", \"printMode\": \"FOOTER\", " + "\"trigger\": " + ticketTrigger("40") + " }");
        BasketEvaluation evaluation = engine.evaluate(basket(1.0));
        assertEquals(0, new BigDecimal("50.00").compareTo(evaluation.getTotalPrice().amountIncludingTax),
                "a message never changes the total");
        assertTrue(evaluation.getVisibleAdvantages().isEmpty(), "a message is not a monetary advantage");
        List<BasketEvaluation.MessageLine> messages = evaluation.getMessages();
        assertNotNull(messages);
        assertEquals("FOOTER", messages.get(0).printMode);
    }

    /**
     * §2.5: the same basket evaluates to a byte-identical response fifty times.
     *
     * @throws Exception if serialization fails.
     */
    @Test
    void testDeterministicOverFiftyRuns() throws Exception {
        setUp();
        DomainUtils.createAndPersistOffer("MSG1", store, "MESSAGE",
                spec("Plus que {{missingAmount}} EUR", "BELOW_TRIGGER", ticketTrigger("100"), null));
        String reference = MAPPER.writeValueAsString(engine.evaluate(basket(1.0)));
        for (int run = 0; run < 50; run++) {
            assertEquals(reference, MAPPER.writeValueAsString(engine.evaluate(basket(1.0))));
        }
        assertFalse(reference.isEmpty());
    }
}
