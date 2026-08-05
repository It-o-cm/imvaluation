package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class for {@link ItemValuation}.
 * <p>
 * The catalog price and VAT rate of each slice are injected through a mocked
 * {@link Basket.Item}: {@link AmountEvaluation#getAmount(Basket.Item, Store, PriceUsage)}
 * calls {@code getProduct()} and {@code getPrice()} on the item, so stubbing those two lets
 * the arithmetic of {@code distribute} be exercised without touching Panache.
 */
@ExtendWith(MockitoExtension.class)
public class ItemValuationTest {

    /** 5.5% VAT rate. */
    private static final BigDecimal RATE_5_5 = new BigDecimal("0.0550");
    /** 20% VAT rate. */
    private static final BigDecimal RATE_20 = new BigDecimal("0.2000");

    /**
     * Builds a mocked slice whose catalog TTC weight and VAT rate are fixed.
     * <p>
     * The catalog HT is irrelevant to {@code distribute} (which rebuilds HT from the rate),
     * so it is left at zero; only the including-tax figure and the rate drive the split.
     *
     * @param store       The store passed to the priced lookup.
     * @param lineId      The slice line identifier.
     * @param ean         The slice product EAN.
     * @param catalogTtc  The catalog including-tax amount used as the slice weight.
     * @param rate        The slice VAT rate.
     * @param sourceLines The slice source-line breakdown, possibly {@code null}.
     * @return The configured mock item.
     */
    private Basket.Item slice(Store store, String lineId, String ean, BigDecimal catalogTtc,
            BigDecimal rate, List<Basket.Item.SourceLine> sourceLines) {
        Basket.Item item = mock(Basket.Item.class);
        Product product = new Product();
        product.productType = ProductType.UNIT;
        Price price = new Price();
        price.priceExcludingTax = BigDecimal.ZERO;
        price.priceIncludingTax = catalogTtc;
        price.vatRate = rate;
        when(item.getProduct()).thenReturn(product);
        when(item.getPrice(store, PriceUsage.BASE_FOR_DISCOUNT)).thenReturn(price);
        item.quantity = 1.0;
        item.lineId = lineId;
        item.produceEan = ean;
        item.sourceLines = sourceLines;
        return item;
    }

    /**
     * Builds an offer total carrying only an including-tax figure, the sole field
     * {@code distribute} reads from it.
     *
     * @param ttc The offer total including tax.
     * @return The offer total evaluation.
     */
    private AmountEvaluation offer(String ttc) {
        return new AmountEvaluation(BigDecimal.ZERO, new BigDecimal(ttc), BigDecimal.ZERO);
    }

    /**
     * Verifies the private constructor is reachable and non-instantiating utility, for
     * complete line coverage of the class.
     *
     * @throws Exception If reflection fails.
     */
    @Test
    void testPrivateConstructor() throws Exception {
        Constructor<ItemValuation> constructor = ItemValuation.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertTrue(constructor.newInstance() instanceof ItemValuation);
    }

    /**
     * When the item collection is empty the first operand of the guard is true and an empty
     * result is returned without any pricing.
     */
    @Test
    void testDistribute_emptyItems_returnsEmpty() {
        Store store = mock(Store.class);
        List<BasketEvaluation.Item> result =
                ItemValuation.distribute(offer("10.00"), new ArrayList<>(), store);
        assertTrue(result.isEmpty());
    }

    /**
     * When the offer total is null the second operand of the guard is true (the collection
     * being non-empty) and an empty result is returned before any pricing.
     */
    @Test
    void testDistribute_nullOfferTotal_returnsEmpty() {
        Store store = mock(Store.class);
        List<Basket.Item> items = List.of(new Basket.Item());
        List<BasketEvaluation.Item> result = ItemValuation.distribute(null, items, store);
        assertTrue(result.isEmpty());
    }

    /**
     * When every slice has a zero catalog weight the total weight is zero and an empty result
     * is returned to avoid a division by zero.
     */
    @Test
    void testDistribute_zeroTotalWeight_returnsEmpty() {
        Store store = mock(Store.class);
        Basket.Item zeroWeight = slice(store, "L0", "E0", new BigDecimal("0.00"), RATE_20, null);
        List<BasketEvaluation.Item> result =
                ItemValuation.distribute(offer("40.00"), List.of(zeroWeight), store);
        assertTrue(result.isEmpty());
    }

    /**
     * A single slice with a null source-line breakdown is valued as its own line: the whole
     * offer total is attributed to it and its HT is rebuilt from the fixed rate.
     */
    @Test
    void testDistribute_singleSlice_nullSourceLines() {
        Store store = mock(Store.class);
        Basket.Item s = slice(store, "L0", "E0", new BigDecimal("50.00"), RATE_20, null);
        List<BasketEvaluation.Item> result =
                ItemValuation.distribute(offer("40.00"), List.of(s), store);
        assertEquals(1, result.size());
        BasketEvaluation.Item item = result.get(0);
        assertEquals("L0", item.lineId);
        assertEquals("E0", item.produceEan);
        assertEquals(1.0, item.quantity);
        assertEquals(new BigDecimal("33.33"), item.amount.amountExcludingTax);
        assertEquals(new BigDecimal("40.00"), item.amount.amountIncludingTax);
        assertEquals(RATE_20, item.amount.vatRate);
    }

    /**
     * A single slice with an empty (non-null) source-line list takes the second operand of
     * the breakdown guard and is likewise valued as its own line.
     */
    @Test
    void testDistribute_singleSlice_emptySourceLines() {
        Store store = mock(Store.class);
        Basket.Item s = slice(store, "L9", "E9", new BigDecimal("50.00"), RATE_20,
                new ArrayList<>());
        List<BasketEvaluation.Item> result =
                ItemValuation.distribute(offer("40.00"), List.of(s), store);
        assertEquals(1, result.size());
        BasketEvaluation.Item item = result.get(0);
        assertEquals("L9", item.lineId);
        assertEquals("E9", item.produceEan);
        assertEquals(1.0, item.quantity);
        assertEquals(new BigDecimal("33.33"), item.amount.amountExcludingTax);
        assertEquals(new BigDecimal("40.00"), item.amount.amountIncludingTax);
    }

    /**
     * A slice split across several source lines is distributed pro-rata to their quantities,
     * with the rounding residue carried by the last line so the lines sum to the slice.
     */
    @Test
    void testDistribute_multipleSourceLines_prorataWithResidueOnLast() {
        Store store = mock(Store.class);
        List<Basket.Item.SourceLine> sources = List.of(
                new Basket.Item.SourceLine("A", 1.0),
                new Basket.Item.SourceLine("B", 3.0));
        Basket.Item s = slice(store, "L0", "E0", new BigDecimal("100.00"), RATE_20, sources);
        List<BasketEvaluation.Item> result =
                ItemValuation.distribute(offer("30.00"), List.of(s), store);
        assertEquals(2, result.size());
        BasketEvaluation.Item first = result.get(0);
        assertEquals("A", first.lineId);
        assertEquals("E0", first.produceEan);
        assertEquals(1.0, first.quantity);
        assertEquals(new BigDecimal("6.25"), first.amount.amountExcludingTax);
        assertEquals(new BigDecimal("7.50"), first.amount.amountIncludingTax);
        assertEquals(RATE_20, first.amount.vatRate);
        BasketEvaluation.Item last = result.get(1);
        assertEquals("B", last.lineId);
        assertEquals("E0", last.produceEan);
        assertEquals(3.0, last.quantity);
        assertEquals(new BigDecimal("18.75"), last.amount.amountExcludingTax);
        assertEquals(new BigDecimal("22.50"), last.amount.amountIncludingTax);
        assertEquals(RATE_20, last.amount.vatRate);
    }

    /**
     * With two slices, the rounding residue is carried by the slice bearing the highest VAT
     * rate so the parts sum exactly to the offer total and the collected tax is never
     * understated. Each slice keeps its own real rate, never a blended one.
     */
    @Test
    void testDistribute_twoSlices_residueToHighestRate() {
        Store store = mock(Store.class);
        Basket.Item low = slice(store, "L0", "E0", new BigDecimal("100.00"), RATE_5_5,
                List.of(new Basket.Item.SourceLine("L0", 1.0)));
        Basket.Item high = slice(store, "L1", "E1", new BigDecimal("100.00"), RATE_20,
                List.of(new Basket.Item.SourceLine("L1", 1.0)));
        List<BasketEvaluation.Item> result =
                ItemValuation.distribute(offer("100.01"), List.of(low, high), store);
        assertEquals(2, result.size());
        BasketEvaluation.Item lowItem = result.get(0);
        assertEquals("L0", lowItem.lineId);
        assertEquals("E0", lowItem.produceEan);
        assertEquals(RATE_5_5, lowItem.amount.vatRate);
        assertEquals(new BigDecimal("50.01"), lowItem.amount.amountIncludingTax);
        assertEquals(new BigDecimal("47.40"), lowItem.amount.amountExcludingTax);
        BasketEvaluation.Item highItem = result.get(1);
        assertEquals("L1", highItem.lineId);
        assertEquals("E1", highItem.produceEan);
        assertEquals(RATE_20, highItem.amount.vatRate);
        assertEquals(new BigDecimal("50.00"), highItem.amount.amountIncludingTax);
        assertEquals(new BigDecimal("41.67"), highItem.amount.amountExcludingTax);
        BigDecimal sumTtc = lowItem.amount.amountIncludingTax.add(highItem.amount.amountIncludingTax);
        assertEquals(new BigDecimal("100.01"), sumTtc);
    }
}
