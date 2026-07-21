package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Service Provider Interface (SPI) for applying offers that are aware of product details.
 * <p>
 * This interface extends the concept of offer application by incorporating
 * product-specific discount logic. Implementations can provide custom
 * mechanisms to calculate discount efficiency scores based on the products
 * involved in the offer application.
 */
public interface ProductAwareOfferApplier {

    /**
     * Determines if this offer applier is applicable to the given product.
     *
     * @param product The product to check applicability against.
     * @return True if this offer applier can be applied to the provided product; false otherwise.
     */
    boolean isApplicable(Product product);
}
