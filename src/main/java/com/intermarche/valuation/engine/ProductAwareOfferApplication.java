package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Product;

/**
 * Service Provider Interface (SPI) for applying offers that are aware of product details.
 * <p>
 * This interface can be implemented to create offer application logic that takes into account
 * specific product attributes, categories, or other relevant information during the offer evaluation process.
 */
public interface ProductAwareOfferApplication extends OfferApplication {

    /**
     * Retrieves the product amount evaluation for a specific product within this offer application.
     *
     * @param product The product for which to retrieve the amount evaluation.
     * @return The {@link AmountEvaluation} object containing the amount details for the specified product.
     */
    AmountEvaluation getProductAmount(Product product);

    /**
     * Retrieves the quantity of a specific product covered by this offer application.
     *
     * @param product The product for which to retrieve the quantity.
     * @return The quantity of the specified product.
     */
    double getProductQuantity(Product product);
}
