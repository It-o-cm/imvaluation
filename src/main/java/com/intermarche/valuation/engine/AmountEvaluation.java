package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Result object containing detailed pricing information.
 * <p>
 * This structure defines the breakdown of a calculated price.
 */
public class AmountEvaluation {

    /**
     * Price Excluding Tax (Prix HT).
     */
    public BigDecimal amountExcludingTax;

    /**
     * Price Including Tax (Prix TTC).
     */
    public BigDecimal amountIncludingTax;

    /**
     * VAT Rate (Taux de TVA) applied (e.g., 0.200 for 20%).
     */
    public BigDecimal vatRate;

    /**
     * Creates an empty price evaluation with zeroed components.
     */
    public AmountEvaluation() {
        this(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Creates a price evaluation with all components.
     *
     * @param amountExcludingTax Price HT.
     * @param amountIncludingTax Price TTC.
     * @param vatRate          VAT rate.
     */
    public AmountEvaluation(BigDecimal amountExcludingTax, BigDecimal amountIncludingTax, BigDecimal vatRate) {
        this.amountExcludingTax = amountExcludingTax.setScale(2, RoundingMode.HALF_UP);
        this.amountIncludingTax = amountIncludingTax.setScale(2, RoundingMode.HALF_UP);
        this.vatRate = vatRate.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Creates a Amount evaluation from a Price entity.
     *
     * @param price The Price entity.
     */
    public AmountEvaluation(Price price) {
        this.amountExcludingTax = price.priceExcludingTax.setScale(2, RoundingMode.HALF_UP);
        this.amountIncludingTax = price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP);
        this.vatRate = price.vatRate.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Adds multiple AmountEvaluation instances together.
     *
     * @param others Other AmountEvaluation instances to add.
     * @return A new AmountEvaluation representing the total.
     */
    public AmountEvaluation add(AmountEvaluation... others) {
        BigDecimal totalExclTax = this.amountExcludingTax;
        BigDecimal totalInclTax = this.amountIncludingTax;
        for (AmountEvaluation other : others) {
            totalExclTax = totalExclTax.add(other.amountExcludingTax);
            totalInclTax = totalInclTax.add(other.amountIncludingTax);
        }
        if (totalExclTax.compareTo(BigDecimal.ZERO) == 0) {
            return new AmountEvaluation(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            );
        }
        return new AmountEvaluation(
            totalExclTax,
            totalInclTax,
            totalInclTax.divide(totalExclTax, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
        );
    }

    /**
     * Subtracts another AmountEvaluation from this one.
     * <p>
     * If the resulting amount is zero, the VAT rate defaults to zero to prevent division by zero errors.
     *
     * @param other The AmountEvaluation to subtract.
     * @return A new AmountEvaluation representing the difference.
     */
    public AmountEvaluation subtract(AmountEvaluation other) {
        BigDecimal diffExclTax = this.amountExcludingTax.subtract(other.amountExcludingTax);
        BigDecimal diffInclTax = this.amountIncludingTax.subtract(other.amountIncludingTax);
        // Guard clause: Avoid division by zero if the result is 0.00
        if (diffExclTax.compareTo(BigDecimal.ZERO) == 0) {
            return new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        // Calculate the implied VAT rate from the resulting amounts
        BigDecimal newVatRate = diffInclTax.divide(diffExclTax, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        return new AmountEvaluation(diffExclTax, diffInclTax, newVatRate);
    }

    /**
     * Multiplies the AmountEvaluation by a given quantity.
     *
     * @param quantity The quantity to multiply by.
     * @return A new AmountEvaluation representing the scaled Amount.
     */
    public AmountEvaluation multiply(BigDecimal quantity) {
        return new AmountEvaluation(
            this.amountExcludingTax.multiply(quantity).setScale(2, RoundingMode.HALF_UP),
            this.amountIncludingTax.multiply(quantity).setScale(2, RoundingMode.HALF_UP),
            this.vatRate
        );
    }

    /**
     * Multiplies the AmontEvaluation by a given efficiency factor.
     *
     * @param efficiency The efficiency factor to multiply by.
     * @return A new AmountEvaluation representing the scaled Amount.
     */
    public AmountEvaluation multiply(double efficiency) {
        BigDecimal factor = BigDecimal.valueOf(1.0 - efficiency);
        return new AmountEvaluation(
            this.amountExcludingTax.multiply(factor).setScale(2, RoundingMode.HALF_UP),
            this.amountIncludingTax.multiply(factor).setScale(2, RoundingMode.HALF_UP),
            this.vatRate
        );
    }

    /**
     * Default method to calculate the Amount evaluation for a given basket item in a given store.
     *
     * @param item  The basket item for which to calculate the Amount.
     * @param store The store context for the Amount calculation.
     * @return The {@link AmountEvaluation} object containing the calculated Amount details.
     */
    public static AmountEvaluation getAmount(Basket.Item item, Store store, PriceUsage priceUsage) {
        Price price = item.getPrice(store, priceUsage);
        // 3. Delegate to detailed Amount calculation
        return getAmount(item.getProduct(), price, item.quantity);
    }

    /**
     * Default method to calculate the total Amount evaluation for a collection of basket items in a given store.
     *
     * @param items Collection of basket items.
     * @param store The store context for the Amount calculation.
     * @return The {@link AmountEvaluation} object containing the total calculated Amount details.
     */
    public static AmountEvaluation getAmount(Collection<Basket.Item> items, Store store, PriceUsage priceUsage) {
        AmountEvaluation evaluation = new AmountEvaluation();
        for (Basket.Item item : items) {
            AmountEvaluation itemAmount = AmountEvaluation.getAmount(item, store, priceUsage);
            evaluation = evaluation.add(itemAmount);
        }
        return evaluation;
    }

    /**
     * Default method to calculate the total Amount evaluation for a specific product EAN
     * across a collection of basket items in a given store.
     *
     * @param items Collection of basket items.
     * @param ean   The EAN of the product to filter by.
     * @param store The store context for the Amount calculation.
     * @return The {@link AmountEvaluation} object containing the total calculated Amount details for the specified product.
     */
    public static AmountEvaluation getAmountForProduct(Collection<Basket.Item> items, String ean, Store store, PriceUsage priceUsage) {
        AmountEvaluation amount = new AmountEvaluation();
        for (Basket.Item item : items) {
            if (item.produceEan.equals(ean)) {
                amount = amount.add(AmountEvaluation.getAmount(item, store, priceUsage));
            }
        }
        return amount;
    }

    /**
     * Default method to calculate the amount evaluation for a given basket item in a given store.
     * @param product The product being purchased.
     * @param price   The price entity for the product in the store.
     * @param quantity The quantity being purchased.
     * @return The {@link AmountEvaluation} object containing the calculated Amount details.
     */
    public static AmountEvaluation getAmount(Product product, Price price, double quantity) {
        // Safety check for context (should not happen if factory works correctly)
        if (product == null|| price == null) {
            return new AmountEvaluation();
        }
        // 3. Calculation based on ProductType
        BigDecimal qty = BigDecimal.valueOf(quantity);
        // --- Logic for Weighted or Volume Products ---
        // Requirement: "La quantité est donnée en kg".
        if (product.productType == ProductType.WEIGHT) {
            // Check for Reference Weight presence
            if (product.referenceWeight == null || product.referenceWeight.compareTo(BigDecimal.ZERO) <= 0) {
                // ERROR: Missing Reference Weight
                throw new IllegalStateException(String.format(
                        "Configuration Error: Product '%s' (EAN: %s) is typed as %s but has no valid reference weight defined.",
                        product.name, product.ean, product.productType
                ));
            }
            BigDecimal refWeight = product.referenceWeight; // En Kg
            // Ratio = Quantité Achetée (Kg) / Poids de Référence (Kg)
            BigDecimal ratio = qty.divide(refWeight, 6, RoundingMode.HALF_UP);
            // Prix Final = Prix de l'unité de référence * Ratio
            return new AmountEvaluation(price).multiply(ratio);
        }
        else if (product.productType == ProductType.VOLUME) {
            // Check for Reference Weight presence
            if (product.referenceVolume == null || product.referenceVolume.compareTo(BigDecimal.ZERO) <= 0) {
                // ERROR: Missing Reference Weight
                throw new IllegalStateException(String.format(
                        "Configuration Error: Product '%s' (EAN: %s) is typed as %s but has no valid reference volume defined.",
                        product.name, product.ean, product.productType
                ));
            }
            BigDecimal refVolume = product.referenceVolume; // En Kg
            // Ratio = Quantité Achetée (Kg) / Poids de Référence (Kg)
            BigDecimal ratio = qty.divide(refVolume, 6, RoundingMode.HALF_UP);
            // Prix Final = Prix de l'unité de référence * Ratio
            return new AmountEvaluation(price).multiply(ratio);
        }
        // --- Logic for Standard Unit Products ---
        else {
            // Direct multiplication (Quantity is the number of items)
            // Price = Quantity * Unit Price
            return new AmountEvaluation(price).multiply(qty);
        }
    }

    /**
     * Default method to calculate the total amount evaluation for an array of basket items in a given store.
     *
     * @param items Array of basket items.
     * @param store The store context for the Amount calculation.
     * @return The {@link AmountEvaluation} object containing the total calculated Amount details.
     */
    public static AmountEvaluation getAmount(Basket.Item items[], Store store, PriceUsage priceUsage) {
        AmountEvaluation total = new AmountEvaluation();
        AmountEvaluation[] evaluations = new AmountEvaluation[items.length];
        for (int i = 0; i < items.length; i++) {
            evaluations[i] = getAmount(items[i], store, priceUsage);
        }
        return total.add(evaluations);
    }

}