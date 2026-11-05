package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Store;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ReturnValuationService}, the stateless return valuation of spec §5:
 * the full refund (normative case 5), the partial refund with the discount spread and the
 * cent residue on the last line (case 6), the generosity taken back when a global offer is lost
 * (case 7), the forced price sources (case 8) and one refusal per guard leg (case 9).
 * <p>
 * The snapshot is hand-written, as the caller keeps it: seven article lines and a delivery
 * charge, a ticket discount of 1.30 spread over every line (0.10 per euro of gross) and a voucher
 * of 0.30 on line 1, so the lines were paid 2.40, 1.80, 1.80, 0.90 x 4 and the charge 1.80 (11.40
 * in all, 13.00 before discounts). The engine, the application mapper, the price helper and the
 * store finder are mocked; nothing is persisted, so there is nothing else to mock.
 */
class ReturnValuationServiceTest {

    /**
     * The basket the caller sent to {@code /valuation} at close.
     */
    private static final String REQUEST = "{'storeCode':'0101','createdAt':'2026-10-27T18:00','couponCodes':['WEB10'],'items':["
            + "{'lineId':'1','produceEan':'E1','quantity':3},"
            + "{'lineId':'2','produceEan':'E2','quantity':1,'pricePerUnitExclTax':1.90,'pricePerUnitInclTax':2.00,'vatRate':0.055},"
            + "{'lineId':'3','produceEan':'E3','quantity':2,'priceDate':'2026-01-01T00:00:00'},"
            + "{'lineId':'4','produceEan':'E4','quantity':1,'priceDate':' '},"
            + "{'lineId':'5','produceEan':'E5','quantity':1},"
            + "{'lineId':'6','produceEan':'E6','quantity':1},"
            + "{'lineId':'7','produceEan':'E7','quantity':1}]}";

    /**
     * The {@code /valuation} answer the caller received at close.
     */
    private static final String EVALUATION = "{'offers':["
            + "{'type':'Delivery: D','amount':{'amountExcludingTax':1.67,'amountIncludingTax':2.00,'vatRate':0.2}},"
            + item("Std 1", "1", "E1", "3", "3.00", "0.055")
            + item("Std 2", "2", "E2", "1", "2.00", "0.055")
            + item("Std 3", "3", "E3", "2", "2.00", "0.2")
            + item("Std 4", "4", "E4", "1", "1.00", "0.2")
            + item("Std 5", "5", "E5", "1", "1.00", "0.2")
            + item("Std 6", "6", "E6", "1", "1.00", "0.2")
            + item("Std 7", "7", "E7", "1", "1.00", "0.2").replaceAll(",$", "")
            + "],'advantages':["
            + "{'type':'Ticket Discount: T','discountAmount':{'amountIncludingTax':1.30}},"
            + "{'type':'Immediate Voucher Discount : V','offer':'Std 1','discountAmount':{'amountIncludingTax':0.30}}],"
            + "'totalPrice':{'amountExcludingTax':9.97,'amountIncludingTax':11.40}}";

    /**
     * Writes one single-item standard offer of the snapshot.
     *
     * @param type     the offer label.
     * @param lineId   the line identifier.
     * @param ean      the EAN.
     * @param quantity the quantity.
     * @param ttc      the amount tax included.
     * @param rate     the VAT rate.
     * @return the offer JSON, single-quoted, followed by a comma.
     */
    private static String item(String type, String lineId, String ean, String quantity, String ttc, String rate) {
        return "{'type':'" + type + "','amount':{'amountIncludingTax':" + ttc + "},'items':[{'lineId':'" + lineId
                + "','produceEan':'" + ean + "','label':'P" + lineId + "','quantity':" + quantity
                + ",'amount':{'amountIncludingTax':" + ttc + ",'vatRate':" + rate + "}}]},";
    }

    /**
     * Parses a single-quoted JSON document with exact decimals.
     *
     * @param json the document.
     * @return the node.
     */
    private static JsonNode json(String json) {
        return EvaluationReader.read(json.replace('\'', '"'), JsonNode.class);
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
     * The test harness: the service wired to mocks, and the static mocks it needs.
     */
    private static final class Harness implements AutoCloseable {

        /**
         * The service under test.
         */
        final ReturnValuationService service = new ReturnValuationService();

        /**
         * The mocked engine.
         */
        final ValuationEngine engine = mock(ValuationEngine.class);

        /**
         * The mocked application mapper.
         */
        final ObjectMapper mapper = mock(ObjectMapper.class);

        /**
         * The evaluation the engine answers for a kept basket.
         */
        final BasketEvaluation evaluation = mock(BasketEvaluation.class);

        /**
         * Static mock of the store finder.
         */
        final MockedStatic<Store> stores = mockStatic(Store.class);

        /**
         * Static mock of the price helper.
         */
        final MockedStatic<ReturnPricing> pricing = mockStatic(ReturnPricing.class);

        /**
         * Wires the service and the default stubs.
         */
        Harness() {
            service.engine = engine;
            service.mapper = mapper;
            when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
            when(evaluation.getToEvaluate()).thenReturn(new HashMap<>());
        }

        /**
         * Stubs the serialized valuations of the kept baskets, in call order.
         *
         * @param first  the first answer, single-quoted.
         * @param others the next answers, single-quoted.
         * @throws Exception never; declared for the mapper stub.
         */
        void kept(String first, String... others) throws Exception {
            String[] next = new String[others.length];
            for (int i = 0; i < others.length; i++) {
                next[i] = others[i].replace('\'', '"');
            }
            when(mapper.writeValueAsString(evaluation)).thenReturn(first.replace('\'', '"'), next);
        }

        /**
         * Closes the static mocks.
         */
        @Override
        public void close() {
            pricing.close();
            stores.close();
        }
    }

    /**
     * Builds the closing snapshot.
     *
     * @return the snapshot.
     */
    private static ReturnValuationService.Snapshot snapshot() {
        ReturnValuationService.Snapshot snapshot = new ReturnValuationService.Snapshot();
        snapshot.request = json(REQUEST);
        snapshot.evaluation = json(EVALUATION);
        return snapshot;
    }

    /**
     * Builds a return request.
     *
     * @param mode      the mode.
     * @param snapshot  the snapshot, or null.
     * @param storeCode the store code, or null.
     * @param lines     the lines, or null.
     * @return the request.
     */
    private static ReturnValuationService.ReturnRequest request(String mode, ReturnValuationService.Snapshot snapshot,
                                                                String storeCode, List<ReturnValuationService.ReturnLine> lines) {
        ReturnValuationService.ReturnRequest request = new ReturnValuationService.ReturnRequest();
        request.mode = mode;
        request.snapshot = snapshot;
        request.storeCode = storeCode;
        request.orderReference = "WEB-1";
        request.lines = lines;
        return request;
    }

    /**
     * Builds the earlier returns of the order.
     *
     * @param withdrawn the generosity already taken back, or null.
     * @param returned  alternating line identifiers and quantities.
     * @return the earlier returns.
     */
    private static ReturnValuationService.Previous previous(String withdrawn, String... returned) {
        ReturnValuationService.Previous previous = new ReturnValuationService.Previous();
        previous.withdrawnGenerosity = withdrawn == null ? null : d(withdrawn);
        for (int i = 0; i < returned.length; i += 2) {
            previous.returned.put(returned[i], d(returned[i + 1]));
        }
        return previous;
    }

    /**
     * Builds a returned line.
     *
     * @param lineId   the snapshot line, or null.
     * @param ean      the EAN, or null.
     * @param quantity the quantity, or null.
     * @param source   the price source, or null.
     * @return the line.
     */
    private static ReturnValuationService.ReturnLine line(String lineId, String ean, String quantity, String source) {
        ReturnValuationService.ReturnLine line = new ReturnValuationService.ReturnLine();
        line.lineId = lineId;
        line.produceEan = ean;
        line.quantity = quantity == null ? null : d(quantity);
        line.priceSource = source;
        return line;
    }

    /**
     * Asserts a refusal.
     *
     * @param status  the expected HTTP status.
     * @param message the expected message.
     * @param call    the refused call.
     */
    private static void refused(int status, String message, Runnable call) {
        ReturnValuationService.Refusal e = assertThrows(ReturnValuationService.Refusal.class, call::run);
        assertEquals(status, e.status);
        assertEquals(message, e.getMessage());
    }

    /**
     * Asserts a valued line.
     *
     * @param row       the row.
     * @param lineId    the expected line identifier.
     * @param reference the expected reference price.
     * @param discount  the expected discount taken back.
     * @param amount    the expected amount.
     * @param rate      the expected VAT rate.
     */
    private static void assertRow(ReturnValuationService.ReturnLineView row, String lineId, String reference,
                                  String discount, String amount, String rate) {
        assertEquals(lineId, row.lineId);
        assertEquals(d(reference), row.referencePrice);
        assertEquals(d(discount), row.discountTakenBack);
        assertEquals(d(amount), row.amount);
        assertEquals(0, d(rate).compareTo(row.vatRate));
    }

    /**
     * Asserts the VAT breakdown of a valuation.
     *
     * @param view     the valuation.
     * @param expected alternating rate, amount tax included and amount tax excluded.
     */
    private static void assertVat(ReturnValuationService.ReturnView view, String... expected) {
        assertEquals(expected.length / 3, view.vatBreakdown.size());
        for (int i = 0; i < expected.length; i += 3) {
            ReturnValuationService.VatLine vat = view.vatBreakdown.get(i / 3);
            assertEquals(0, d(expected[i]).compareTo(vat.vatRate));
            assertEquals(d(expected[i + 1]), vat.amountIncludingTax);
            assertEquals(d(expected[i + 2]), vat.amountExcludingTax);
        }
    }

    /**
     * Normative case 5: a FULL return refunds the snapshot line for line, exactly what it cashed,
     * and takes back every advantage of the order; the engine is not called.
     */
    @Test
    void full_refundsWhatWasCashed() {
        try (Harness h = new Harness()) {
            ReturnValuationService.ReturnRequest request = request(" full ", snapshot(), null, null);
            request.previousReturns = previous(null);
            ReturnValuationService.ReturnView view = h.service.valuate(request);
            assertEquals(8, view.lines.size());
            ReturnValuationService.ReturnLineView charge = view.lines.get(0);
            assertNull(charge.produceEan);
            assertRow(charge, null, "2.00", "0.20", "1.80", "0.2");
            assertRow(view.lines.get(1), "1", "1.00", "0.60", "2.40", "0.055");
            assertEquals("E1", view.lines.get(1).produceEan);
            assertEquals("ORDER", view.lines.get(1).priceSource);
            assertEquals(0, d("3").compareTo(view.lines.get(1).quantity));
            assertRow(view.lines.get(3), "3", "1.00", "0.20", "1.80", "0.2");
            assertRow(view.lines.get(7), "7", "1.00", "0.10", "0.90", "0.2");
            assertEquals(d("11.40"), view.totalRefund);
            assertVat(view, "0.055", "4.20", "3.98", "0.2", "7.20", "6.00");
            assertEquals(2, view.advantagesTakenBack.size());
            assertEquals("T", view.advantagesTakenBack.get(0).offerCode);
            assertEquals(d("1.30"), view.advantagesTakenBack.get(0).amount);
            assertEquals("V", view.advantagesTakenBack.get(1).offerCode);
            assertNull(view.withdrawnGenerosity);
            verify(h.engine, never()).evaluate(any());
        }
    }

    /**
     * A FULL refund equals the cashed total to the cent: a cent the lines miss goes to the last
     * line. A snapshot without request and earlier returns given as null count for nothing.
     */
    @Test
    void full_residueOnLastLine() {
        try (Harness h = new Harness()) {
            ReturnValuationService.Snapshot snapshot = snapshot();
            snapshot.request = null;
            ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.evaluation.path("totalPrice"))
                    .put("amountIncludingTax", d("11.41"));
            ReturnValuationService.ReturnRequest request = request("FULL", snapshot, null, null);
            ReturnValuationService.Previous none = previous(null);
            none.returned = null;
            request.previousReturns = none;
            ReturnValuationService.ReturnView view = h.service.valuate(request);
            assertRow(view.lines.get(7), "7", "1.00", "0.09", "0.91", "0.2");
            assertEquals(d("11.41"), view.totalRefund);
        }
    }

    /**
     * A snapshot line without quantity is refunded as one unit priced at its whole amount.
     */
    @Test
    void full_lineWithoutQuantity() {
        try (Harness h = new Harness()) {
            ReturnValuationService.Snapshot snapshot = snapshot();
            snapshot.evaluation = json("{'offers':[{'type':'G','items':[{'lineId':'1','amount':{'amountIncludingTax':1.50,"
                    + "'vatRate':0.2}}]}],'totalPrice':{'amountIncludingTax':1.50}}");
            ReturnValuationService.ReturnView view = h.service.valuate(request("FULL", snapshot, null, null));
            assertRow(view.lines.get(0), "1", "1.50", "0.00", "1.50", "0.2");
        }
    }

    /**
     * A FULL return is refused once the order has returns, and on a snapshot without valued line.
     */
    @Test
    void full_refusals() {
        try (Harness h = new Harness()) {
            ReturnValuationService.ReturnRequest request = request("FULL", snapshot(), null, null);
            request.previousReturns = previous("0.00", "1", "1");
            refused(400, "The order already has returns: return the remaining lines with PARTIAL.",
                    () -> h.service.valuate(request));
            ReturnValuationService.Snapshot empty = snapshot();
            empty.evaluation = json("{'offers':[]}");
            refused(400, "snapshot.evaluation holds no valued line.", () -> h.service.valuate(request("FULL", empty, null, null)));
        }
    }

    /**
     * Normative cases 6 and 7: the ORDER lines are refunded at their paid price, net of their
     * share of the order's discounts; the kept basket, re-valued at the order's instant (pushed
     * prices and price dates kept), no longer meets the ticket discount's condition, so the
     * generosity it no longer deserves is taken back pro-rata over the returned lines, the cent
     * residue on the last, and the lost offer is listed. One answer line per requested line, in
     * request order, a repeated line included.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void partial_takesBackLostGenerosity() throws Exception {
        try (Harness h = new Harness()) {
            h.kept("{'advantages':[{'type':'Immediate Voucher Discount : V','discountAmount':{'amountIncludingTax':0.10}}],"
                    + "'totalPrice':{'amountIncludingTax':8.10}}");
            ReturnValuationService.ReturnView view = h.service.valuate(request("PARTIAL", snapshot(), null, List.of(
                    line("1", null, "1", null), line("3", null, "1", "order"), line("7", null, "1", null),
                    line("1", null, "1", null))));
            ArgumentCaptor<Basket> kept = ArgumentCaptor.forClass(Basket.class);
            verify(h.engine).evaluate(kept.capture());
            Basket keptBasket = kept.getValue();
            assertEquals(Boolean.TRUE, keptBasket.closed);
            assertEquals(List.of("WEB10"), keptBasket.couponCodes);
            assertEquals(6, keptBasket.items.size());
            assertEquals(0, d("1").compareTo(keptBasket.items.get(0).quantity));
            assertEquals("2026-10-27T18:00", keptBasket.items.get(0).priceDate);
            assertNull(keptBasket.items.get(1).priceDate);
            assertEquals("2026-01-01T00:00:00", keptBasket.items.get(2).priceDate);
            assertEquals("2026-10-27T18:00", keptBasket.items.get(3).priceDate);
            assertEquals("2026-10-27T18:00", keptBasket.items.get(5).priceDate);
            assertEquals(4, view.lines.size());
            assertRow(view.lines.get(0), "1", "1.00", "0.22", "0.78", "0.055");
            assertRow(view.lines.get(1), "3", "1.00", "0.13", "0.87", "0.2");
            assertRow(view.lines.get(2), "7", "1.00", "0.13", "0.87", "0.2");
            assertRow(view.lines.get(3), "1", "1.00", "0.22", "0.78", "0.055");
            assertEquals(d("3.30"), view.totalRefund);
            assertVat(view, "0.055", "1.56", "1.48", "0.2", "1.74", "1.45");
            assertEquals(d("0.10"), view.withdrawnGenerosity);
            assertEquals(1, view.advantagesTakenBack.size());
            assertEquals("T", view.advantagesTakenBack.get(0).offerCode);
            assertEquals(d("1.30"), view.advantagesTakenBack.get(0).amount);
        }
    }

    /**
     * Extension: a later PARTIAL return, told what earlier returns took, values the basket kept
     * before it to know what was still earned, gives back the generosity taken earlier once the
     * basket left no longer deserves less, and lists the offer lost on the way.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void partial_laterReturn_givesGenerosityBack() throws Exception {
        try (Harness h = new Harness()) {
            h.kept("{'advantages':[{'type':'Immediate Voucher Discount : V','discountAmount':{'amountIncludingTax':0.10}}],"
                    + "'totalPrice':{'amountIncludingTax':8.10}}", "{'advantages':[],'totalPrice':{'amountIncludingTax':5.00}}");
            ReturnValuationService.ReturnRequest request = request("PARTIAL", snapshot(), null, List.of(line("1", null, "1", null)));
            request.previousReturns = previous("0.10", "1", "2", "3", "1", "7", "1");
            ReturnValuationService.ReturnView view = h.service.valuate(request);
            ArgumentCaptor<Basket> kept = ArgumentCaptor.forClass(Basket.class);
            verify(h.engine, times(2)).evaluate(kept.capture());
            assertEquals(0, d("1").compareTo(kept.getAllValues().get(0).items.get(0).quantity));
            assertEquals(5, kept.getAllValues().get(1).items.size());
            assertEquals(d("-0.10"), view.withdrawnGenerosity);
            assertRow(view.lines.get(0), "1", "1.00", "0.10", "0.90", "0.055");
            assertEquals("V", view.advantagesTakenBack.get(0).offerCode);
            assertEquals(d("0.10"), view.advantagesTakenBack.get(0).amount);
        }
    }

    /**
     * Without order instant the kept lines keep their own price base; earlier returns given
     * without figures count as none; a kept basket that still earns everything takes nothing
     * back.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void partial_nothingLost_takesNothingBack() throws Exception {
        try (Harness h = new Harness()) {
            h.kept("{'advantages':[{'type':'Ticket Discount: T','discountAmount':{'amountIncludingTax':1.00}},"
                    + "{'type':'Immediate Voucher Discount : V','discountAmount':{'amountIncludingTax':0.20}}],"
                    + "'totalPrice':{'amountIncludingTax':9.00}}");
            ReturnValuationService.Snapshot snapshot = snapshot();
            ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.request).remove("createdAt");
            ReturnValuationService.ReturnRequest request = request("PARTIAL", snapshot, null, List.of(line("7", null, "1", null)));
            ReturnValuationService.Previous blank = previous(null);
            blank.returned = null;
            request.previousReturns = blank;
            ReturnValuationService.ReturnView view = h.service.valuate(request);
            ArgumentCaptor<Basket> kept = ArgumentCaptor.forClass(Basket.class);
            verify(h.engine).evaluate(kept.capture());
            assertNull(kept.getValue().items.get(0).priceDate);
            assertTrue(view.advantagesTakenBack.isEmpty());
            assertEquals(d("0.00"), view.withdrawnGenerosity);
            assertRow(view.lines.get(0), "7", "1.00", "0.10", "0.90", "0.2");
        }
    }

    /**
     * A blank order instant is no instant: the kept lines keep their own price base.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void partial_blankInstant_isIgnored() throws Exception {
        try (Harness h = new Harness()) {
            h.kept("{'advantages':[],'totalPrice':{'amountIncludingTax':9.00}}");
            ReturnValuationService.Snapshot snapshot = snapshot();
            ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.request).put("createdAt", " ");
            h.service.valuate(request("PARTIAL", snapshot, null, List.of(line("7", null, "1", null))));
            ArgumentCaptor<Basket> kept = ArgumentCaptor.forClass(Basket.class);
            verify(h.engine).evaluate(kept.capture());
            assertNull(kept.getValue().items.get(0).priceDate);
        }
    }

    /**
     * Returning the last line of an order whose request carries no line values an empty kept
     * basket at zero without the engine, and takes back every offer.
     */
    @Test
    void partial_emptyKeptBasket_isWorthZero() {
        try (Harness h = new Harness()) {
            ReturnValuationService.Snapshot snapshot = snapshot();
            snapshot.request = json("{'storeCode':'0101'}");
            ReturnValuationService.ReturnView view = h.service.valuate(request("PARTIAL", snapshot, null,
                    List.of(line("7", null, "1", null))));
            verify(h.engine, never()).evaluate(any());
            assertEquals(2, view.advantagesTakenBack.size());
        }
    }

    /**
     * A PARTIAL return may also carry lines priced on another source (spec §5.1): they are valued
     * on that source, the store coming from the snapshot's request, and take nothing back.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void partial_withOtherSources() throws Exception {
        try (Harness h = new Harness()) {
            h.kept("{'advantages':[{'type':'Ticket Discount: T','discountAmount':{'amountIncludingTax':1.00}},"
                    + "{'type':'Immediate Voucher Discount : V','discountAmount':{'amountIncludingTax':0.20}}],"
                    + "'totalPrice':{'amountIncludingTax':9.00}}");
            Store store = new Store();
            h.stores.when(() -> Store.findByCode("0101")).thenReturn(store);
            AmountEvaluation unit = new AmountEvaluation(d("0.80"), d("0.96"), d("0.2"));
            h.pricing.when(() -> ReturnPricing.referential("E9", d("2"), "2025-06-15", store))
                    .thenReturn(new ReturnPricing.Priced(unit, unit.multiply(d("2")), "X"));
            ReturnValuationService.ReturnLine dated = line(null, "E9", "2", "DATE");
            dated.priceDate = "2025-06-15";
            ReturnValuationService.ReturnView view = h.service.valuate(request("PARTIAL", snapshot(), null,
                    List.of(line("7", null, "1", null), dated)));
            assertRow(view.lines.get(1), null, "0.96", "0.00", "1.92", "0.2");
            assertEquals("DATE", view.lines.get(1).priceSource);
            assertEquals(d("2.82"), view.totalRefund);
        }
    }

    /**
     * A PARTIAL return with no ORDER line recomputes no kept basket.
     */
    @Test
    void partial_withoutOrderLines_recomputesNothing() {
        try (Harness h = new Harness()) {
            AmountEvaluation unit = new AmountEvaluation(d("1.50"), d("1.80"), d("0.2"));
            h.pricing.when(() -> ReturnPricing.pushed(d("1"), d("1.50"), d("1.80"), d("0.20")))
                    .thenReturn(new ReturnPricing.Priced(unit, unit, null));
            ReturnValuationService.ReturnLine pushed = line(null, "E9", "1", "PUSHED");
            pushed.pricePerUnitExclTax = d("1.50");
            pushed.pricePerUnitInclTax = d("1.80");
            pushed.vatRate = d("0.20");
            ReturnValuationService.ReturnView view = h.service.valuate(request("PARTIAL", snapshot(), null, List.of(pushed)));
            verify(h.engine, never()).evaluate(any());
            assertNull(view.withdrawnGenerosity);
            assertEquals(d("1.80"), view.totalRefund);
        }
    }

    /**
     * Case 9: a PARTIAL return refuses missing lines, a non-positive quantity, an unknown line or
     * a charge, more than the snapshot holds, and a snapshot without request.
     */
    @Test
    void partial_refusals() {
        try (Harness h = new Harness()) {
            refused(400, "lines are required in PARTIAL and LINES mode.", () -> h.service.valuate(request("PARTIAL", snapshot(), null, null)));
            refused(400, "lines are required in PARTIAL and LINES mode.", () -> h.service.valuate(request("PARTIAL", snapshot(), null, List.of())));
            refused(400, "quantity must be strictly positive.",
                    () -> h.service.valuate(request("PARTIAL", snapshot(), null, List.of(line("1", null, null, null)))));
            refused(400, "quantity must be strictly positive.",
                    () -> h.service.valuate(request("PARTIAL", snapshot(), null, List.of(line("1", null, "0", null)))));
            refused(400, "lineId null is not a line of the snapshot.",
                    () -> h.service.valuate(request("PARTIAL", snapshot(), null, List.of(line(null, null, "1", null)))));
            refused(400, "lineId 9 is not a line of the snapshot.",
                    () -> h.service.valuate(request("PARTIAL", snapshot(), null, List.of(line("9", null, "1", null)))));
            refused(400, "lineId offer:Delivery: D is not a line of the snapshot.",
                    () -> h.service.valuate(request("PARTIAL", snapshot(), null, List.of(line("offer:Delivery: D", null, "1", null)))));
            refused(400, "Line 1: quantity 2 exceeds the 1 left in the snapshot.", () -> h.service.valuate(
                    request("PARTIAL", snapshot(), null, List.of(line("1", null, "2", null), line("1", null, "2", null)))));
            ReturnValuationService.ReturnRequest exceeding = request("PARTIAL", snapshot(), null, List.of(line("1", null, "2", null)));
            exceeding.previousReturns = previous("0.00", "1", "2");
            refused(400, "Line 1: quantity 2 exceeds the 1 left in the snapshot.", () -> h.service.valuate(exceeding));
            ReturnValuationService.Snapshot noRequest = snapshot();
            noRequest.request = null;
            refused(400, "snapshot.request is required to return part of the order: the basket valued at close.",
                    () -> h.service.valuate(request("PARTIAL", noRequest, null, List.of(line("1", null, "1", null)))));
            ReturnValuationService.Snapshot textRequest = snapshot();
            textRequest.request = json("'basket'");
            refused(400, "snapshot.request is required to return part of the order: the basket valued at close.",
                    () -> h.service.valuate(request("PARTIAL", textRequest, null, List.of(line("1", null, "1", null)))));
        }
    }

    /**
     * The valuation of the kept basket refuses a basket breaking the schema (400), an engine
     * failure (422), articles left unvalued (422) and an unserializable evaluation (500).
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void evaluate_refusals() throws Exception {
        try (Harness h = new Harness()) {
            ReturnValuationService.Snapshot broken = snapshot();
            broken.request = json("{'storeCode':'0101','items':[{'lineId':'1','produceEan':'E1','quantity':3,'pricePerUnitInclTax':1.00}]}");
            ReturnValuationService.Refusal schema = assertThrows(ReturnValuationService.Refusal.class,
                    () -> h.service.valuate(request("PARTIAL", broken, null, List.of(line("1", null, "1", null)))));
            assertEquals(400, schema.status);
            assertTrue(schema.getMessage().startsWith("The kept basket breaks the basket schema: "), schema.getMessage());
            when(h.engine.evaluate(any(Basket.class))).thenThrow(new IllegalStateException("Store not found"));
            refused(422, "Store not found", () -> h.service.valuate(request("PARTIAL", snapshot(), null,
                    List.of(line("1", null, "1", null)))));
        }
        try (Harness h = new Harness()) {
            Map<String, List<Basket.Item>> left = new HashMap<>();
            left.put("E1", List.of(new Basket.Item()));
            when(h.evaluation.getToEvaluate()).thenReturn(left);
            refused(422, ReturnValuationService.UNPROCESSED_MESSAGE, () -> h.service.valuate(
                    request("PARTIAL", snapshot(), null, List.of(line("1", null, "1", null)))));
        }
        try (Harness h = new Harness()) {
            when(h.mapper.writeValueAsString(h.evaluation)).thenThrow(new IllegalStateException("boom"));
            refused(500, "Cannot serialize the evaluation: boom", () -> h.service.valuate(
                    request("PARTIAL", snapshot(), null, List.of(line("1", null, "1", null)))));
        }
    }

    /**
     * Case 9: the request checks refuse a missing body, an unknown mode, a missing snapshot in
     * FULL and PARTIAL, and a snapshot without evaluation.
     */
    @Test
    void request_refusals() {
        try (Harness h = new Harness()) {
            refused(400, "A return body is required.", () -> h.service.valuate(null));
            refused(400, "mode must be FULL, PARTIAL or LINES.", () -> h.service.valuate(request(null, snapshot(), null, null)));
            refused(400, "mode must be FULL, PARTIAL or LINES.", () -> h.service.valuate(request("SWAP", snapshot(), null, null)));
            String noSnapshot = "snapshot is required in FULL and PARTIAL mode: the request and the evaluation kept at close.";
            refused(400, noSnapshot, () -> h.service.valuate(request("FULL", null, "0101", null)));
            refused(400, noSnapshot, () -> h.service.valuate(request("PARTIAL", null, "0101", List.of(line("1", null, "1", null)))));
            ReturnValuationService.Snapshot noEvaluation = snapshot();
            noEvaluation.evaluation = null;
            refused(400, "snapshot.evaluation is required: the /valuation answer kept at close.",
                    () -> h.service.valuate(request("FULL", noEvaluation, null, null)));
            ReturnValuationService.Snapshot arrayEvaluation = snapshot();
            arrayEvaluation.evaluation = json("[]");
            refused(400, "snapshot.evaluation is required: the /valuation answer kept at close.",
                    () -> h.service.valuate(request("LINES", arrayEvaluation, null, List.of(line("1", null, "1", "ORDER")))));
        }
    }

    /**
     * Normative case 8: LINES without order — the PUSHED price with no referential read
     * (DG-01-03-08), the DATE price at the given date (DG-01-03-07) and the CURRENT price now
     * (DG-01-03-04); the store is resolved once; nothing is taken back.
     */
    @Test
    void lines_forcedSources_withoutOrder() {
        try (Harness h = new Harness()) {
            Store store = new Store();
            h.stores.when(() -> Store.findByCode("0101")).thenReturn(store);
            h.pricing.when(() -> ReturnPricing.pushed(d("1"), d("1.50"), d("1.80"), d("0.20")))
                    .thenReturn(new ReturnPricing.Priced(new AmountEvaluation(d("1.50"), d("1.80"), d("0.2")),
                            new AmountEvaluation(d("1.50"), d("1.80"), d("0.2")), null));
            h.pricing.when(() -> ReturnPricing.referential("E1", d("1"), "2025-06-15T10:00:00", store))
                    .thenReturn(new ReturnPricing.Priced(new AmountEvaluation(d("0.80"), d("0.96"), d("0.2")),
                            new AmountEvaluation(d("0.80"), d("0.96"), d("0.2")), "Lait"));
            h.pricing.when(() -> ReturnPricing.referential("E1", d("1"), null, store))
                    .thenReturn(new ReturnPricing.Priced(new AmountEvaluation(d("2.30"), d("2.76"), d("0.2")),
                            new AmountEvaluation(d("2.30"), d("2.76"), d("0.2")), "Lait"));
            ReturnValuationService.ReturnLine pushed = line(null, "E1", "1", "pushed");
            pushed.pricePerUnitExclTax = d("1.50");
            pushed.pricePerUnitInclTax = d("1.80");
            pushed.vatRate = d("0.20");
            ReturnValuationService.ReturnLine dated = line(null, "E1", "1", "DATE");
            dated.priceDate = "2025-06-15T10:00:00";
            ReturnValuationService.ReturnView view = h.service.valuate(request("LINES", null, "0101",
                    List.of(pushed, dated, line(null, "E1", "1", " current "))));
            assertEquals(3, view.lines.size());
            assertEquals("PUSHED", view.lines.get(0).priceSource);
            assertRow(view.lines.get(0), null, "1.80", "0.00", "1.80", "0.2");
            assertEquals("DATE", view.lines.get(1).priceSource);
            assertRow(view.lines.get(1), null, "0.96", "0.00", "0.96", "0.2");
            assertEquals("CURRENT", view.lines.get(2).priceSource);
            assertRow(view.lines.get(2), null, "2.76", "0.00", "2.76", "0.2");
            assertEquals("E1", view.lines.get(2).produceEan);
            assertEquals(d("5.52"), view.totalRefund);
            assertVat(view, "0.2", "5.52", "4.60");
            assertTrue(view.advantagesTakenBack.isEmpty());
            h.stores.verify(() -> Store.findByCode("0101"), times(1));
        }
    }

    /**
     * A CURRENT line resolves the store first and a later DATE line reuses it.
     */
    @Test
    void lines_currentThenDate_resolvesStoreOnce() {
        try (Harness h = new Harness()) {
            Store store = new Store();
            h.stores.when(() -> Store.findByCode("0101")).thenReturn(store);
            AmountEvaluation unit = new AmountEvaluation(d("1"), d("1.2"), d("0.2"));
            h.pricing.when(() -> ReturnPricing.referential(any(), any(), any(), eq(store)))
                    .thenReturn(new ReturnPricing.Priced(unit, unit, "X"));
            ReturnValuationService.ReturnLine dated = line(null, "E1", "1", "DATE");
            dated.priceDate = "2025-06-15";
            ReturnValuationService.ReturnView view = h.service.valuate(request("LINES", null, "0101",
                    List.of(line(null, "E1", "1", "CURRENT"), dated)));
            assertEquals(2, view.lines.size());
            h.stores.verify(() -> Store.findByCode("0101"), times(1));
        }
    }

    /**
     * LINES on the snapshot: an ORDER line is refunded at what was paid for it, its share of the
     * order's discounts taken back (DG-01-03-06), and no kept basket is recomputed.
     */
    @Test
    void lines_orderSource_usesPaidPrice() {
        try (Harness h = new Harness()) {
            ReturnValuationService.ReturnView view = h.service.valuate(request("LINES", snapshot(), null,
                    List.of(line("3", null, "1", "ORDER"), line("3", null, "1", "ORDER"))));
            assertRow(view.lines.get(0), "3", "1.00", "0.10", "0.90", "0.2");
            assertEquals("E3", view.lines.get(0).produceEan);
            assertRow(view.lines.get(1), "3", "1.00", "0.10", "0.90", "0.2");
            assertEquals(d("1.80"), view.totalRefund);
            assertNull(view.withdrawnGenerosity);
            verify(h.engine, never()).evaluate(any());
        }
    }

    /**
     * Case 9: LINES refuses missing lines, a missing or unknown source, a DATE without date, an
     * ORDER without snapshot, a CURRENT or DATE price without store (no snapshot, or a snapshot
     * without request) and an unknown store; the store falls back to the snapshot's request.
     */
    @Test
    void lines_refusals() {
        try (Harness h = new Harness()) {
            refused(400, "lines are required in PARTIAL and LINES mode.", () -> h.service.valuate(request("LINES", null, "0101", null)));
            refused(400, "priceSource must be ORDER, CURRENT, DATE or PUSHED.",
                    () -> h.service.valuate(request("LINES", null, "0101", List.of(line(null, "E1", "1", null)))));
            refused(400, "priceSource must be ORDER, CURRENT, DATE or PUSHED.",
                    () -> h.service.valuate(request("LINES", null, "0101", List.of(line(null, "E1", "1", "LIST")))));
            refused(400, "priceDate is required for a DATE price.",
                    () -> h.service.valuate(request("LINES", null, "0101", List.of(line(null, "E1", "1", "DATE")))));
            ReturnValuationService.ReturnLine blank = line(null, "E1", "1", "DATE");
            blank.priceDate = " ";
            refused(400, "priceDate is required for a DATE price.",
                    () -> h.service.valuate(request("LINES", null, "0101", List.of(blank))));
            refused(400, "An ORDER price requires the snapshot.",
                    () -> h.service.valuate(request("LINES", null, "0101", List.of(line("1", null, "1", "ORDER")))));
            refused(400, "storeCode is mandatory for a CURRENT or DATE price.",
                    () -> h.service.valuate(request("LINES", null, null, List.of(line(null, "E1", "1", "CURRENT")))));
            ReturnValuationService.Snapshot noRequest = snapshot();
            noRequest.request = null;
            refused(400, "storeCode is mandatory for a CURRENT or DATE price.",
                    () -> h.service.valuate(request("LINES", noRequest, null, List.of(line(null, "E1", "1", "CURRENT")))));
            refused(400, "Unknown store: 0101",
                    () -> h.service.valuate(request("LINES", snapshot(), null, List.of(line(null, "E1", "1", "CURRENT")))));
        }
    }

    /**
     * Spreading over lines that were paid nothing gives every line a zero share; otherwise the
     * cent residue goes to the last line.
     */
    @Test
    void spread_residueOnLastOrZero() {
        assertEquals(List.of(d("0.00"), d("0.00")), ReturnValuationService.spread(d("0.10"), List.of(d("0.00"), d("0.00"))));
        assertEquals(List.of(d("0.03"), d("0.03"), d("0.04")),
                ReturnValuationService.spread(d("0.10"), List.of(d("1"), d("1"), d("1"))));
    }

    /**
     * The request, snapshot, earlier-returns, line and answer classes carry their defaults.
     */
    @Test
    void dataClasses_haveDefaults() {
        ReturnValuationService.Previous previous = new ReturnValuationService.Previous();
        assertEquals(d("0.00"), previous.withdrawnGenerosity);
        assertTrue(previous.returned.isEmpty());
        assertNull(new ReturnValuationService.ReturnRequest().mode);
        assertNull(new ReturnValuationService.Snapshot().evaluation);
        assertNull(new ReturnValuationService.ReturnLine().priceSource);
        ReturnValuationService.ReturnView view = new ReturnValuationService.ReturnView();
        assertTrue(view.lines.isEmpty());
        assertTrue(view.vatBreakdown.isEmpty());
        assertNull(new ReturnValuationService.ReturnLineView().amount);
        assertNull(new ReturnValuationService.VatLine().vatRate);
        ReturnValuationService.Refusal refusal = new ReturnValuationService.Refusal(409, "no");
        assertEquals(409, refusal.status);
        assertEquals("no", refusal.getMessage());
    }
}
