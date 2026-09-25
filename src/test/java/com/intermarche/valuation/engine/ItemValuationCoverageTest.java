package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-oriented @QuarkusTest for {@link ItemValuation}.
 * <p>
 * Each slice carries an explicit manual price (excl/incl/rate) so
 * {@link Basket.Item#getPrice} short-circuits to that price without a database lookup, and
 * its cached {@code product} is set directly (both classes live in this package), so
 * {@link Basket.Item#getProduct} never queries either. The store argument is therefore
 * irrelevant and passed as {@code null}.
 */
@QuarkusTest
class ItemValuationCoverageTest {

    /**
     * Builds a detached UNIT product with a given EAN.
     *
     * @param ean The product EAN.
     * @return The product.
     */
    private Product product(String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Test " + ean;
        product.productType = ProductType.UNIT;
        return product;
    }

    /**
     * Builds a mono-price consumed slice bound to its product.
     *
     * @param lineId  The source line identifier.
     * @param product The product this slice prices against.
     * @param qty     The consumed quantity.
     * @param ht      The unit price excluding tax.
     * @param ttc     The unit price including tax.
     * @param rate    The VAT rate.
     * @return The slice.
     */
    private Basket.Item slice(String lineId, Product product, double qty, String ht, String ttc, String rate) {
        Basket.Item item = new Basket.Item();
        item.lineId = lineId;
        item.produceEan = product.ean;
        item.quantity = BigDecimal.valueOf(qty);
        item.pricePerUnitExclTax = new BigDecimal(ht);
        item.pricePerUnitInclTax = new BigDecimal(ttc);
        item.vatRate = new BigDecimal(rate);
        item.product = product;
        return item;
    }

    /**
     * An empty item collection produces no valued lines.
     */
    @Test
    void emptyItemsYieldNothing() {
        List<BasketEvaluation.Item> result = ItemValuation.distribute(
                new AmountEvaluation(new BigDecimal("1.00"), new BigDecimal("1.20"), new BigDecimal("0.20")),
                List.of(), null);
        assertTrue(result.isEmpty());
    }

    /**
     * A null offer total produces no valued lines.
     */
    @Test
    void nullOfferTotalYieldsNothing() {
        List<BasketEvaluation.Item> result = ItemValuation.distribute(
                null, List.of(slice("L1", product("EAN1"), 1.0, "1.00", "1.20", "0.20")), null);
        assertTrue(result.isEmpty());
    }

    /**
     * Slices whose catalog weight sums to zero produce no valued lines.
     */
    @Test
    void zeroTotalWeightYieldsNothing() {
        List<BasketEvaluation.Item> result = ItemValuation.distribute(
                new AmountEvaluation(new BigDecimal("1.00"), new BigDecimal("1.20"), new BigDecimal("0.20")),
                List.of(slice("L1", product("EAN1"), 1.0, "0.00", "0.00", "0.20")), null);
        assertTrue(result.isEmpty());
    }

    /**
     * A two-slice offer with a higher-rate second slice distributes pro-rata, splits the
     * first slice across its two source lines, and lets the last source line carry the
     * residue.
     */
    @Test
    void distributesAndSplitsBySourceLine() {
        Product pa = product("EANA");
        Product pb = product("EANB");
        Basket.Item a = slice("A", pa, 1.0, "10.00", "11.00", "0.10");
        a.sourceLines = new java.util.ArrayList<>();
        a.sourceLines.add(new Basket.Item.SourceLine("L1", BigDecimal.valueOf(0.75)));
        a.sourceLines.add(new Basket.Item.SourceLine("L2", BigDecimal.valueOf(0.25)));
        Basket.Item b = slice("B", pb, 1.0, "20.00", "24.00", "0.20");
        AmountEvaluation total = new AmountEvaluation(new BigDecimal("30.00"), new BigDecimal("35.00"),
                new BigDecimal("0.1667"));
        List<BasketEvaluation.Item> result = ItemValuation.distribute(total, List.of(a, b), null);
        assertEquals(3, result.size());
        BasketEvaluation.Item l1 = result.get(0);
        assertEquals("L1", l1.lineId);
        assertEquals("EANA", l1.produceEan);
        assertEquals(0, l1.quantity.compareTo(BigDecimal.valueOf(0.75)));
        assertEquals(new BigDecimal("7.50"), l1.amount.amountExcludingTax);
        assertEquals(new BigDecimal("8.25"), l1.amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.1000"), l1.amount.vatRate);
        BasketEvaluation.Item l2 = result.get(1);
        assertEquals("L2", l2.lineId);
        assertEquals(0, l2.quantity.compareTo(BigDecimal.valueOf(0.25)));
        assertEquals(new BigDecimal("2.50"), l2.amount.amountExcludingTax);
        assertEquals(new BigDecimal("2.75"), l2.amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.1000"), l2.amount.vatRate);
        BasketEvaluation.Item lb = result.get(2);
        assertEquals("B", lb.lineId);
        assertEquals("EANB", lb.produceEan);
        assertEquals(0, lb.quantity.compareTo(BigDecimal.valueOf(1.0)));
        assertEquals(new BigDecimal("20.00"), lb.amount.amountExcludingTax);
        assertEquals(new BigDecimal("24.00"), lb.amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.2000"), lb.amount.vatRate);
    }
}
