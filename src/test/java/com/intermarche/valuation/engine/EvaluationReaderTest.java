package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for {@link EvaluationReader}: every view is asserted to the cent on
 * hand-written serialized evaluations, without any engine or database.
 */
class EvaluationReaderTest {

    /**
     * Mapper reading the test documents with exact decimals.
     */
    private static final ObjectMapper MAPPER = EvaluationReader.MAPPER;

    /**
     * Parses a JSON document written with single quotes.
     *
     * @param json the document, single-quoted.
     * @return the node.
     * @throws Exception when the document is malformed.
     */
    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json.replace('\'', '"'));
    }

    /**
     * Builds a decimal.
     *
     * @param value the decimal text.
     * @return the decimal.
     */
    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /**
     * Covers the private constructor of the utility class.
     *
     * @throws Exception when reflection fails.
     */
    @Test
    void privateConstructor_isReachable() throws Exception {
        Constructor<EvaluationReader> constructor = EvaluationReader.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertTrue(constructor.newInstance() instanceof EvaluationReader);
    }

    /**
     * {@link EvaluationReader#decimal} reads a number and refuses a null node and a text node.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void decimal_readsNumbersOnly() throws Exception {
        assertEquals(d("1.20"), EvaluationReader.decimal(json("{'a':1.20}").get("a")));
        assertNull(EvaluationReader.decimal(null));
        assertNull(EvaluationReader.decimal(json("{'a':'x'}").get("a")));
    }

    /**
     * {@link EvaluationReader#text} trims a text and reads a missing, non-textual or blank field
     * as absent.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void text_readsTrimmedNonBlankText() throws Exception {
        JsonNode node = json("{'a':' x ','b':1,'c':'  '}");
        assertEquals("x", EvaluationReader.text(node, "a"));
        assertNull(EvaluationReader.text(node, "b"));
        assertNull(EvaluationReader.text(node, "c"));
        assertNull(EvaluationReader.text(node, "z"));
    }

    /**
     * The totals are read at scale 2, and default to zero when absent.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void totals_readAtScaleTwoOrZero() throws Exception {
        JsonNode evaluation = json("{'totalPrice':{'amountExcludingTax':9.2,'amountIncludingTax':11.04}}");
        assertEquals(d("9.20"), EvaluationReader.totalExcludingTax(evaluation));
        assertEquals(d("11.04"), EvaluationReader.totalIncludingTax(evaluation));
        assertEquals(d("0.00"), EvaluationReader.totalExcludingTax(json("{}")));
        assertEquals(d("0.00"), EvaluationReader.totalIncludingTax(json("{}")));
    }

    /**
     * The offer code is the explicit field first, else the token after the label's colon, cut at
     * the first space; a node without label or without colon names no code.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void offerCodeOf_explicitThenLabel() throws Exception {
        assertEquals("MEAL", EvaluationReader.offerCodeOf(json("{'offerCode':'MEAL','type':'X: Y'}")));
        assertEquals("TIERED_RICE", EvaluationReader.offerCodeOf(json("{'type':'Tiered Discount: TIERED_RICE (5%)'}")));
        assertEquals("PROMO_1", EvaluationReader.offerCodeOf(json("{'type':'Immediate Voucher Discount : PROMO_1'}")));
        assertNull(EvaluationReader.offerCodeOf(json("{'type':'MEAL_VOUCHER'}")));
        assertNull(EvaluationReader.offerCodeOf(json("{}")));
    }

    /**
     * The applied codes list the offers then the advantages, once each, skipping nodes without
     * code.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void appliedCodes_offersThenAdvantagesWithoutDuplicates() throws Exception {
        JsonNode evaluation = json("{'offers':[{'type':'MixedBundle: PACK x1'},{'type':'Standard'}],"
                + "'advantages':[{'type':'Ticket Discount: WEB10'},{'offerCode':'PACK'}]}");
        Set<String> codes = EvaluationReader.appliedCodes(evaluation);
        assertEquals(List.of("PACK", "WEB10"), List.copyOf(codes));
    }

    /**
     * Only the advantages naming an offer and carrying a tax-included discount are declared, as
     * absolute amounts, with their funding and their per-article split over the targeted offer.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void advantagesOf_declaresDiscountsWithLines() throws Exception {
        JsonNode evaluation = json("{'offers':[{'type':'Standard A','items':["
                + "{'produceEan':'E1','amount':{'amountIncludingTax':2.00}},"
                + "{'produceEan':'E2','amount':{'amountIncludingTax':1.00}},"
                + "{'amount':{'amountIncludingTax':5.00}},"
                + "{'produceEan':'E3'}]},"
                + "{'type':'Other','items':[{'produceEan':'E9','amount':{'amountIncludingTax':9.00}}]}],"
                + "'advantages':["
                + "{'type':'Ticket Discount: T1','offer':'Standard A','funding':'STORE',"
                + "'discountAmount':{'amountIncludingTax':-1.00}},"
                + "{'type':'Coupon Grant: G1 (x1)'},"
                + "{'type':'MEAL_VOUCHER','discountAmount':{'amountIncludingTax':1.00}}]}");
        List<ConsumptionService.Advantage> advantages = EvaluationReader.advantagesOf(evaluation);
        assertEquals(1, advantages.size());
        ConsumptionService.Advantage advantage = advantages.get(0);
        assertEquals("T1", advantage.offerCode);
        assertEquals(d("1.00"), advantage.amount);
        assertEquals("STORE", advantage.funding);
        assertEquals(2, advantage.lines.size());
        assertEquals("E1", advantage.lines.get(0).ean);
        assertEquals(d("0.67"), advantage.lines.get(0).amount);
        assertEquals("E2", advantage.lines.get(1).ean);
        assertEquals(d("0.33"), advantage.lines.get(1).amount);
    }

    /**
     * An advantage without targeted offer, or whose offer has no priced article, is declared
     * without per-article lines.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void advantagesOf_withoutPricedOffer_hasNoLines() throws Exception {
        JsonNode evaluation = json("{'offers':[{'type':'Delivery: D'}],'advantages':["
                + "{'type':'Ticket Discount: T1','discountAmount':{'amountIncludingTax':1.00}},"
                + "{'type':'Free Delivery Threshold Discount: F','offer':'Delivery: D',"
                + "'discountAmount':{'amountIncludingTax':0.50}}]}");
        List<ConsumptionService.Advantage> advantages = EvaluationReader.advantagesOf(evaluation);
        assertEquals(2, advantages.size());
        assertNull(advantages.get(0).lines);
        assertNull(advantages.get(1).lines);
        assertNull(advantages.get(0).funding);
    }

    /**
     * The earned amounts are summed per offer code.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void earnedByOffer_sumsPerCode() throws Exception {
        JsonNode evaluation = json("{'advantages':["
                + "{'type':'Ticket Discount: W','discountAmount':{'amountIncludingTax':1.83}},"
                + "{'type':'Ticket Discount: W','discountAmount':{'amountIncludingTax':0.42}},"
                + "{'type':'Tiered Discount: T','discountAmount':{'amountIncludingTax':0.10}}]}");
        Map<String, BigDecimal> earned = EvaluationReader.earnedByOffer(evaluation);
        assertEquals(d("2.25"), earned.get("W"));
        assertEquals(d("0.10"), earned.get("T"));
        assertEquals(2, earned.size());
    }

    /**
     * The pro-rata split gives the rounding residue to the last share, as the sale does, so the
     * parts sum back to the amount.
     */
    @Test
    void prorate_residueToLastShare() {
        List<BigDecimal> parts = EvaluationReader.prorate(d("1.00"), List.of(d("1"), d("1"), d("4")));
        assertEquals(List.of(d("0.17"), d("0.17"), d("0.66")), parts);
        List<BigDecimal> even = EvaluationReader.prorate(d("0.10"), List.of(d("1"), d("1"), d("1")));
        assertEquals(List.of(d("0.03"), d("0.03"), d("0.04")), even);
    }

    /**
     * The paid lines group the valued portions of each request line, spread a targeted discount
     * over the lines of its offer and an untargeted one over every line, and turn a priced offer
     * without articles into a charge line.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void paidLines_spreadsDiscountsAndKeepsCharges() throws Exception {
        JsonNode evaluation = json("{'offers':["
                + "{'type':'N+M: P','items':["
                + "{'lineId':'1','produceEan':'E1','label':'Milk','quantity':2,"
                + "'amount':{'amountExcludingTax':5.00,'amountIncludingTax':6.00,'vatRate':0.2}},"
                + "{'lineId':'1','produceEan':'E1','quantity':1,"
                + "'amount':{'amountExcludingTax':0,'amountIncludingTax':0}}]},"
                + "{'type':'Standard B','items':[{'produceEan':'E2','quantity':1,"
                + "'amount':{'amountExcludingTax':3.33,'amountIncludingTax':4.00,'vatRate':0.2}}]},"
                + "{'type':'Delivery: D','amount':{'amountExcludingTax':5.00,'amountIncludingTax':6.00,'vatRate':0.2}},"
                + "{'type':'Ghost'}],"
                + "'advantages':["
                + "{'type':'Immediate Voucher Discount : V','offer':'Standard B','discountAmount':{'amountIncludingTax':1.00}},"
                + "{'type':'Ticket Discount: T','discountAmount':{'amountIncludingTax':1.60}},"
                + "{'type':'Ghost Discount: G','offer':'Ghost','discountAmount':{'amountIncludingTax':0.00}},"
                + "{'type':'Message: M'}]}");
        Map<String, EvaluationReader.PaidLine> lines = EvaluationReader.paidLines(evaluation);
        assertEquals(List.of("1", "E2", "offer:Delivery: D"), List.copyOf(lines.keySet()));
        EvaluationReader.PaidLine milk = lines.get("1");
        assertEquals("1", milk.lineId);
        assertEquals("E1", milk.ean);
        assertEquals("Milk", milk.label);
        assertFalse(milk.charge);
        assertEquals(0, d("3").compareTo(milk.quantity));
        assertEquals(d("6.00"), milk.grossTtc);
        assertEquals(d("0.2"), milk.vatRate);
        assertEquals(d("5.40"), milk.paidTtc());
        EvaluationReader.PaidLine other = lines.get("E2");
        assertNull(other.lineId);
        assertEquals(d("2.60"), other.paidTtc());
        EvaluationReader.PaidLine delivery = lines.get("offer:Delivery: D");
        assertTrue(delivery.charge);
        assertNull(delivery.ean);
        assertEquals("Delivery: D", delivery.label);
        assertEquals(d("5.40"), delivery.paidTtc());
    }

    /**
     * A discount met before any line exists is skipped, and a discount over lines that all weigh
     * zero is left unallocated.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void paidLines_skipsUnallocatableDiscounts() throws Exception {
        JsonNode noLines = json("{'advantages':[{'type':'Ticket Discount: T','discountAmount':{'amountIncludingTax':1}}]}");
        assertTrue(EvaluationReader.paidLines(noLines).isEmpty());
        JsonNode zero = json("{'offers':[{'type':'Free','items':[{'quantity':1,'amount':{}}]}],"
                + "'advantages':[{'type':'Ticket Discount: T','discountAmount':{'amountIncludingTax':1}}]}");
        Map<String, EvaluationReader.PaidLine> lines = EvaluationReader.paidLines(zero);
        EvaluationReader.PaidLine free = lines.get("null");
        assertNull(free.ean);
        assertEquals(d("0.00"), free.paidTtc());
        assertEquals(0, BigDecimal.ZERO.compareTo(free.vatRate));
    }

    /**
     * {@link EvaluationReader.PaidLine#paidFor} returns the whole paid amount for the whole
     * quantity or a zero quantity, and a pro-rata share otherwise; the tax-excluded figure is
     * rebuilt from the line's rate.
     */
    @Test
    void paidLine_paidForAndExcludingTax() {
        EvaluationReader.PaidLine line = new EvaluationReader.PaidLine("1", "E", null, false);
        line.add(d("3"), d("8.33"), d("10.00"), d("0.2"));
        assertEquals(d("10.00"), line.paidFor(d("3")));
        assertEquals(d("3.33"), line.paidFor(d("1")));
        assertEquals(d("8.33"), line.excludingTax(d("10.00")));
        EvaluationReader.PaidLine empty = new EvaluationReader.PaidLine("2", "E", null, false);
        empty.add(null, null, d("4.00"), null);
        assertEquals(d("4.00"), empty.paidFor(d("1")));
    }

    /**
     * The views read a missing evaluation as empty.
     */
    @Test
    void views_onMissingEvaluation_areEmpty() {
        JsonNode missing = MissingNode.getInstance();
        assertTrue(EvaluationReader.advantagesOf(missing).isEmpty());
        assertTrue(EvaluationReader.appliedCodes(missing).isEmpty());
        assertTrue(EvaluationReader.paidLines(missing).isEmpty());
    }

    /**
     * The document helpers write and read with exact decimals, convert a node to a type, and
     * wrap their failures.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void documentHelpers_roundTripAndWrapFailures() throws Exception {
        assertEquals("{\"a\":1.50}", EvaluationReader.write(json("{'a':1.50}")));
        Basket basket = EvaluationReader.convert(json("{'storeCode':'0101','items':[{'lineId':'1','quantity':2.50}]}"), Basket.class);
        assertEquals(d("2.50"), basket.items.get(0).quantity);
        Object unserializable = new Object() {
            /**
             * A getter that always fails.
             *
             * @return never.
             */
            public String getValue() {
                throw new IllegalStateException("no");
            }
        };
        assertEquals("Cannot serialize the document.",
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> EvaluationReader.write(unserializable)).getMessage());
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> EvaluationReader.read("{x", Basket.class)).getMessage().startsWith("Cannot read the document: "));
    }

    /**
     * Advantages of the same offer merge into one: amounts summed, lines concatenated (an advantage
     * without lines adding none), the first funding kept; distinct offers stay apart, in order.
     *
     * @throws Exception when the document is malformed.
     */
    @Test
    void perOffer_mergesPerCode() throws Exception {
        JsonNode evaluation = json("{'offers':[{'type':'Std A','items':[{'produceEan':'E1','amount':{'amountIncludingTax':2.00}}]}],"
                + "'advantages':["
                + "{'type':'Ticket Discount: W','discountAmount':{'amountIncludingTax':1.83}},"
                + "{'type':'Ticket Discount: W','offer':'Std A','funding':'STORE','discountAmount':{'amountIncludingTax':0.42}},"
                + "{'type':'Ticket Discount: W','offer':'Std A','discountAmount':{'amountIncludingTax':0.10}},"
                + "{'type':'Tiered Discount: T','discountAmount':{'amountIncludingTax':0.10}}]}");
        List<ConsumptionService.Advantage> merged = EvaluationReader.perOffer(EvaluationReader.advantagesOf(evaluation));
        assertEquals(2, merged.size());
        assertEquals("W", merged.get(0).offerCode);
        assertEquals(d("2.35"), merged.get(0).amount);
        assertNull(merged.get(0).funding);
        assertEquals(2, merged.get(0).lines.size());
        assertEquals("T", merged.get(1).offerCode);
        assertNull(merged.get(1).lines);
    }
}
