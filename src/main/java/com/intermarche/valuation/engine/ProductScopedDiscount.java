package com.intermarche.valuation.engine;

/**
 * A {@link DiscountApplication} that reduces one identified product line rather than the whole
 * offer application (A3, report H2c).
 * <p>
 * When a later discount nets a single product's tranche within a multi-product application, it
 * must subtract only the discounts that actually fell on <em>that</em> product, not the whole
 * application's discounts prorated blindly. A product-scoped discount declares which EAN it hit so
 * {@link NetAmounts#netProductTtc} can attribute it precisely; discounts that are not product-scoped
 * (ticket-wide reductions) are prorated by the product's share instead.
 */
public interface ProductScopedDiscount {

    /**
     * Returns the EAN of the product line this discount reduced.
     *
     * @return the discounted product EAN, or {@code null} when unknown.
     */
    String discountedEan();
}
