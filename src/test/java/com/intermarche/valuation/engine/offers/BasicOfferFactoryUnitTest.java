package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.VatRate;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link BasicOfferFactory} and its {@link BasicOfferFactory.BasicOfferApplier},
 * complementing the database-backed {@code BasicOfferFactoryTest}: one applier per EAN, and every
 * slice of a product valued at its own price profile — the defect fixed with the digital sheet,
 * where a line at a price date or at the referential price took the pushed price of the
 * product's first line.
 */
class BasicOfferFactoryUnitTest {

    /**
     * Builds a price.
     *
     * @param ttc the price tax included, 20% VAT.
     * @return the price.
     */
    private static Price price(String ttc) {
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal(ttc);
        price.priceExcludingTax = new BigDecimal(ttc).divide(new BigDecimal("1.2"), 2, java.math.RoundingMode.HALF_UP);
        price.vat = new VatRate(null, new BigDecimal("0.2000"), null);
        return price;
    }

    /**
     * Builds a mocked basket line whose prices and product are stubbed.
     *
     * @param ean       the EAN, or null.
     * @param product   the product.
     * @param store     the store.
     * @param price     the DEFAULT price.
     * @param reference the BASE_FOR_DISCOUNT price.
     * @return the line.
     */
    private static Basket.Item line(String ean, Product product, Store store, Price price, Price reference) {
        Basket.Item item = mock(Basket.Item.class);
        item.produceEan = ean;
        item.quantity = BigDecimal.ONE;
        when(item.getProduct()).thenReturn(product);
        when(item.getPrice(store, PriceUsage.DEFAULT)).thenReturn(price);
        when(item.getPrice(store, PriceUsage.BASE_FOR_DISCOUNT)).thenReturn(reference);
        return item;
    }

    /**
     * Builds a product.
     *
     * @param ean the EAN.
     * @return the product.
     */
    private static Product product(String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "P" + ean;
        product.productType = com.intermarche.valuation.domain.ProductType.UNIT;
        return product;
    }

    /**
     * The factory builds one applier per distinct EAN, skipping a line without EAN, and resolves
     * no price at build time.
     */
    @Test
    void buildAppliers_onePerEan() {
        Store store = new Store();
        Product milk = product("E1");
        Product bread = product("E2");
        Basket.Item first = line("E1", milk, store, price("1.80"), price("1.80"));
        Basket.Item second = line("E1", milk, store, price("2.76"), price("3.04"));
        Basket.Item third = line("E2", bread, store, price("0.96"), price("1.06"));
        Basket.Item unknown = line(null, null, store, null, null);
        Basket basket = new Basket();
        basket.items = List.of(first, second, unknown, third);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getBasket()).thenReturn(basket);
        when(evaluation.getStore()).thenReturn(store);
        Collection<OfferApplier> appliers = new BasicOfferFactory().buildAppliers(evaluation);
        assertEquals(2, appliers.size());
        List<String> keys = new ArrayList<>();
        for (OfferApplier applier : appliers) {
            keys.add(((BasicOfferFactory.BasicOfferApplier) applier).getTieBreakKey());
        }
        assertEquals(List.of("E1", "E2"), keys);
        verify(first, never()).getPrice(any(), any());
    }

    /**
     * Each slice picked for the product is valued at its own price: the pushed price of one line
     * does not spread to a line of the same product at a price date.
     */
    @Test
    void apply_valuesEachSliceAtItsOwnPrice() {
        Store store = new Store();
        Product milk = product("E1");
        Basket.Item pushed = line("E1", milk, store, price("1.80"), price("1.80"));
        Basket.Item dated = line("E1", milk, store, price("0.96"), price("0.96"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.remainingQuantity("E1")).thenReturn(new BigDecimal("2"));
        when(evaluation.pick(new BigDecimal("2"), "E1")).thenReturn(List.of(pushed, dated));
        BasicOfferFactory.BasicOfferApplier applier = new BasicOfferFactory.BasicOfferApplier(store, milk);
        List<OfferApplication> applications = new ArrayList<>(applier.apply(evaluation));
        assertEquals(2, applications.size());
        assertEquals(new BigDecimal("1.80"), applications.get(0).getAmount().amountIncludingTax);
        assertEquals(new BigDecimal("0.96"), applications.get(1).getAmount().amountIncludingTax);
        verify(evaluation).addAvailableToUpcell(pushed);
        verify(evaluation).addAvailableToUpcell(dated);
    }

    /**
     * When the product is already consumed by another offer, nothing is picked.
     */
    @Test
    void apply_nothingRemaining_isEmpty() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.remainingQuantity("E1")).thenReturn(BigDecimal.ZERO);
        BasicOfferFactory.BasicOfferApplier applier = new BasicOfferFactory.BasicOfferApplier(new Store(), product("E1"));
        assertTrue(applier.apply(evaluation).isEmpty());
        verify(evaluation, never()).pick(any(), any());
    }

    /**
     * The applier carries no configuration, its EAN as tie-break key, and applies to its own
     * product only.
     */
    @Test
    void applier_identity() {
        BasicOfferFactory.BasicOfferApplier applier = new BasicOfferFactory.BasicOfferApplier(new Store(), product("E1"));
        assertNull(applier.getConfiguration());
        assertEquals("E1", applier.getTieBreakKey());
        assertTrue(applier.isApplicable(product("E1")));
        assertFalse(applier.isApplicable(product("E2")));
    }
}
