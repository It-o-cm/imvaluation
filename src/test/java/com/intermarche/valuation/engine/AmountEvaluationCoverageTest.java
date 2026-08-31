package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage-oriented @QuarkusTest for {@link AmountEvaluation}, focused on the static
 * {@code getAmount(Product, Price, double)} branches (UNIT / WEIGHT / VOLUME and their
 * configuration guards) plus the {@code add}/{@code subtract}/{@code multiply} arithmetic.
 * <p>
 * No database is touched: {@link Product} and {@link Price} entities are constructed in
 * memory and passed directly to the pure calculation methods.
 */
@QuarkusTest
class AmountEvaluationCoverageTest {

    /**
     * Builds a detached price entity.
     *
     * @param ht  The amount excluding tax.
     * @param ttc The amount including tax.
     * @param vat The VAT rate.
     * @return The price.
     */
    private Price price(String ht, String ttc, String vat) {
        Price p = new Price();
        p.priceExcludingTax = new BigDecimal(ht);
        p.priceIncludingTax = new BigDecimal(ttc);
        p.vatRate = new BigDecimal(vat);
        return p;
    }

    /**
     * Builds a detached product of a given type.
     *
     * @param type The product type.
     * @return The product.
     */
    private Product product(ProductType type) {
        Product product = new Product();
        product.ean = "EAN0001";
        product.name = "Test";
        product.productType = type;
        return product;
    }

    /**
     * A null product yields a zeroed evaluation.
     */
    @Test
    void nullProductYieldsZero() {
        AmountEvaluation result = AmountEvaluation.getAmount(null, price("1.00", "1.20", "0.20"), 1.0);
        assertEquals(new BigDecimal("0.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("0.00"), result.amountIncludingTax);
        assertEquals(new BigDecimal("0.0000"), result.vatRate);
    }

    /**
     * A null price yields a zeroed evaluation.
     */
    @Test
    void nullPriceYieldsZero() {
        AmountEvaluation result = AmountEvaluation.getAmount(product(ProductType.UNIT), null, 1.0);
        assertEquals(new BigDecimal("0.00"), result.amountExcludingTax);
    }

    /**
     * A UNIT product multiplies the unit price by the quantity.
     */
    @Test
    void unitProductMultipliesByQuantity() {
        AmountEvaluation result = AmountEvaluation.getAmount(product(ProductType.UNIT),
                price("4.00", "4.40", "0.10"), 3.0);
        assertEquals(new BigDecimal("12.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("13.20"), result.amountIncludingTax);
        assertEquals(new BigDecimal("0.1000"), result.vatRate);
    }

    /**
     * A WEIGHT product with no reference weight is a configuration error.
     */
    @Test
    void weightProductWithoutReferenceWeightThrows() {
        Product p = product(ProductType.WEIGHT);
        p.referenceWeight = null;
        assertThrows(IllegalStateException.class,
                () -> AmountEvaluation.getAmount(p, price("4.00", "4.40", "0.10"), 1.0));
    }

    /**
     * A WEIGHT product with a non-positive reference weight is a configuration error.
     */
    @Test
    void weightProductWithZeroReferenceWeightThrows() {
        Product p = product(ProductType.WEIGHT);
        p.referenceWeight = BigDecimal.ZERO;
        assertThrows(IllegalStateException.class,
                () -> AmountEvaluation.getAmount(p, price("4.00", "4.40", "0.10"), 1.0));
    }

    /**
     * A WEIGHT product prices by the quantity-to-reference ratio.
     */
    @Test
    void weightProductPricesByRatio() {
        Product p = product(ProductType.WEIGHT);
        p.referenceWeight = new BigDecimal("2.0");
        AmountEvaluation result = AmountEvaluation.getAmount(p, price("4.00", "4.40", "0.10"), 3.0);
        assertEquals(new BigDecimal("6.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("6.60"), result.amountIncludingTax);
        assertEquals(new BigDecimal("0.1000"), result.vatRate);
    }

    /**
     * A VOLUME product with no reference volume is a configuration error.
     */
    @Test
    void volumeProductWithoutReferenceVolumeThrows() {
        Product p = product(ProductType.VOLUME);
        p.referenceVolume = null;
        assertThrows(IllegalStateException.class,
                () -> AmountEvaluation.getAmount(p, price("3.00", "3.30", "0.10"), 1.0));
    }

    /**
     * A VOLUME product with a non-positive reference volume is a configuration error.
     */
    @Test
    void volumeProductWithZeroReferenceVolumeThrows() {
        Product p = product(ProductType.VOLUME);
        p.referenceVolume = BigDecimal.ZERO;
        assertThrows(IllegalStateException.class,
                () -> AmountEvaluation.getAmount(p, price("3.00", "3.30", "0.10"), 1.0));
    }

    /**
     * A VOLUME product prices by the quantity-to-reference ratio.
     */
    @Test
    void volumeProductPricesByRatio() {
        Product p = product(ProductType.VOLUME);
        p.referenceVolume = new BigDecimal("1.5");
        AmountEvaluation result = AmountEvaluation.getAmount(p, price("3.00", "3.30", "0.10"), 3.0);
        assertEquals(new BigDecimal("6.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("6.60"), result.amountIncludingTax);
        assertEquals(new BigDecimal("0.1000"), result.vatRate);
    }

    /**
     * Adding two non-zero evaluations rebuilds the blended VAT rate.
     */
    @Test
    void addRebuildsBlendedRate() {
        AmountEvaluation a = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("11.00"),
                new BigDecimal("0.10"));
        AmountEvaluation b = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"),
                new BigDecimal("0.20"));
        AmountEvaluation sum = a.add(b);
        assertEquals(new BigDecimal("20.00"), sum.amountExcludingTax);
        assertEquals(new BigDecimal("23.00"), sum.amountIncludingTax);
        assertEquals(new BigDecimal("0.1500"), sum.vatRate);
    }

    /**
     * Adding evaluations that sum to a zero excluding-tax total yields a zeroed result.
     */
    @Test
    void addZeroExcludingTaxYieldsZero() {
        AmountEvaluation a = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("11.00"),
                new BigDecimal("0.10"));
        AmountEvaluation b = new AmountEvaluation(new BigDecimal("-10.00"), new BigDecimal("-11.00"),
                new BigDecimal("0.10"));
        AmountEvaluation sum = a.add(b);
        assertEquals(new BigDecimal("0.00"), sum.amountExcludingTax);
        assertEquals(new BigDecimal("0.0000"), sum.vatRate);
    }

    /**
     * Subtracting rebuilds the implied VAT rate from the difference.
     */
    @Test
    void subtractRebuildsRate() {
        AmountEvaluation a = new AmountEvaluation(new BigDecimal("20.00"), new BigDecimal("24.00"),
                new BigDecimal("0.20"));
        AmountEvaluation b = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"),
                new BigDecimal("0.20"));
        AmountEvaluation diff = a.subtract(b);
        assertEquals(new BigDecimal("10.00"), diff.amountExcludingTax);
        assertEquals(new BigDecimal("12.00"), diff.amountIncludingTax);
        assertEquals(new BigDecimal("0.2000"), diff.vatRate);
    }

    /**
     * Subtracting to a zero excluding-tax difference guards against division by zero.
     */
    @Test
    void subtractZeroExcludingTaxYieldsZero() {
        AmountEvaluation a = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"),
                new BigDecimal("0.20"));
        AmountEvaluation diff = a.subtract(a);
        assertEquals(new BigDecimal("0.00"), diff.amountExcludingTax);
        assertEquals(new BigDecimal("0.0000"), diff.vatRate);
    }

    /**
     * Multiplying by an efficiency factor scales down both amounts and keeps the rate.
     */
    @Test
    void multiplyByEfficiencyScalesDown() {
        AmountEvaluation a = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"),
                new BigDecimal("0.20"));
        AmountEvaluation scaled = a.multiply(0.25);
        assertEquals(new BigDecimal("7.50"), scaled.amountExcludingTax);
        assertEquals(new BigDecimal("9.00"), scaled.amountIncludingTax);
        assertEquals(new BigDecimal("0.2000"), scaled.vatRate);
    }
}
