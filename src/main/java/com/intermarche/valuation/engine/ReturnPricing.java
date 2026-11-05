package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prices a returned article on a FORCED price source (digital sheet DG-01-03-04, -07, -08).
 * <p>
 * A sale lets {@link Basket.Item#getPrice} infer the price base: the pushed trio when complete,
 * else the referential at the line's price date, else at the current instant. A return names its
 * source explicitly, so this helper forces it instead of inferring it, while reusing that very
 * method for the lookup itself:
 * <ul>
 *   <li>{@code CURRENT}: the referential price in force now (no historical snapshot to rely on);</li>
 *   <li>{@code DATE}: the referential price in force at the date the platform gives;</li>
 *   <li>{@code PUSHED}: the platform's trio, with no referential read at all.</li>
 * </ul>
 * The {@code ORDER} source reads the order snapshot the caller sends and is handled by
 * {@link ReturnValuationService}. No promotion is applied: a return price is a reference price,
 * not a sale.
 */
public final class ReturnPricing {

    /**
     * Not instantiable.
     */
    private ReturnPricing() {
    }

    /**
     * Prices a line on the platform's pushed trio, without reading the referential
     * (DG-01-03-08).
     *
     * @param quantity the returned quantity.
     * @param excl     the unit price tax excluded.
     * @param incl     the unit price tax included.
     * @param vatRate  the VAT rate.
     * @return the unit price and the amount.
     * @throws ReturnValuationService.Refusal 400 naming the missing price fields.
     */
    public static Priced pushed(BigDecimal quantity, BigDecimal excl, BigDecimal incl, BigDecimal vatRate) {
        List<String> missing = new ArrayList<>();
        if (excl == null) {
            missing.add("pricePerUnitExclTax");
        }
        if (incl == null) {
            missing.add("pricePerUnitInclTax");
        }
        if (vatRate == null) {
            missing.add("vatRate");
        }
        if (!missing.isEmpty()) {
            throw new ReturnValuationService.Refusal(400,
                    "A PUSHED price requires " + String.join(", ", missing) + ".");
        }
        Basket.Item item = new Basket.Item();
        item.pricePerUnitExclTax = excl;
        item.pricePerUnitInclTax = incl;
        item.vatRate = vatRate;
        AmountEvaluation unit = new AmountEvaluation(item.getPrice(null, PriceUsage.DEFAULT));
        return new Priced(unit, unit.multiply(quantity), null);
    }

    /**
     * Prices a line on the referential at a date, or now when no date is given (DG-01-03-07,
     * DG-01-03-04).
     *
     * @param ean       the product EAN.
     * @param quantity  the returned quantity.
     * @param priceDate the ISO-8601 date or date-time to look the price up at, or null for now.
     * @param store     the store whose prices apply.
     * @return the unit price, the amount and the product label.
     * @throws ReturnValuationService.Refusal 400 when the EAN is missing or the date
     *                                                   unreadable, 422 naming the EAN when the
     *                                                   referential has no product or no price.
     */
    public static Priced referential(String ean, BigDecimal quantity, String priceDate, Store store) {
        if (ean == null || ean.isBlank()) {
            throw new ReturnValuationService.Refusal(400,
                    "produceEan is required for a CURRENT or DATE price.");
        }
        Basket.Item item = new Basket.Item();
        item.produceEan = ean;
        item.priceDate = priceDate == null ? null : dateTime(priceDate);
        try {
            Price price = item.getPrice(store, PriceUsage.DEFAULT);
            Product product = item.getProduct();
            return new Priced(new AmountEvaluation(price), AmountEvaluation.getAmount(product, price, quantity),
                    product.name);
        } catch (IllegalStateException e) {
            throw new ReturnValuationService.Refusal(422, "Article " + ean + ": " + e.getMessage());
        }
    }

    /**
     * Reads a price date as an ISO-8601 date-time, a bare date standing for the start of that day.
     *
     * @param priceDate the date, {@code yyyy-MM-dd} or {@code yyyy-MM-ddTHH:mm[:ss]}.
     * @return the date-time, ISO-8601.
     * @throws ReturnValuationService.Refusal 400 when the date is unreadable.
     */
    static String dateTime(String priceDate) {
        try {
            return LocalDate.parse(priceDate).atStartOfDay().toString();
        } catch (DateTimeParseException notADate) {
            try {
                return LocalDateTime.parse(priceDate).toString();
            } catch (DateTimeParseException e) {
                throw new ReturnValuationService.Refusal(400,
                        "Invalid priceDate '" + priceDate + "': expected an ISO-8601 date or date-time.");
            }
        }
    }

    /**
     * A priced return line: the unit reference price, the amount for the quantity and the label.
     */
    public static final class Priced {

        /**
         * The unit reference price.
         */
        public final AmountEvaluation unit;

        /**
         * The amount for the returned quantity, positive.
         */
        public final AmountEvaluation amount;

        /**
         * The product label, or null when the referential was not read.
         */
        public final String label;

        /**
         * Builds a priced line.
         *
         * @param unit   the unit reference price.
         * @param amount the amount for the quantity.
         * @param label  the product label, or null.
         */
        public Priced(AmountEvaluation unit, AmountEvaluation amount, String label) {
            this.unit = unit;
            this.amount = amount;
            this.label = label;
        }
    }
}
