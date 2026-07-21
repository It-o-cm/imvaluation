package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Test class for {@link AmountEvaluation}.
 */
@ExtendWith(MockitoExtension.class)
public class AmountEvaluationTest {

    // --------------------------------------------------
    // Constants for Test Readability
    // --------------------------------------------------
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");
    private static final BigDecimal ONE_HUNDRED_TTC_20 = new BigDecimal("120.00"); // 100 + 20%
    private static final BigDecimal TAX_RATE_20 = new BigDecimal("0.2000");
    private static final BigDecimal TAX_RATE_10 = new BigDecimal("0.1000");
    private static final BigDecimal TAX_RATE_5_5 = new BigDecimal("0.0550");

    // --------------------------------------------------
    // Constructor Tests
    // --------------------------------------------------

    /**
     * Tests the default constructor.
     * Verifies that all fields (amountExcludingTax, amountIncludingTax, vatRate) are initialized to ZERO.
     */
    @Test
    void testDefaultConstructor() {
        AmountEvaluation amount = new AmountEvaluation();

        assertEquals(ZERO.setScale(2), amount.amountExcludingTax);
        assertEquals(ZERO.setScale(2), amount.amountIncludingTax);
        assertEquals(ZERO.setScale(4), amount.vatRate);
    }

    /**
     * Tests the parameterized constructor.
     * Verifies that fields are initialized with the provided values.
     */
    @Test
    void testParameterizedConstructor() {
        AmountEvaluation amount = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);

        assertEquals(ONE_HUNDRED, amount.amountExcludingTax);
        assertEquals(ONE_HUNDRED_TTC_20, amount.amountIncludingTax);
        assertEquals(TAX_RATE_20, amount.vatRate);
    }

    /**
     * Tests the constructor that accepts a Price entity.
     * Verifies that values are correctly copied from the Price object.
     */
    @Test
    void testConstructorFromPrice() {
        Price price = new Price();
        price.priceExcludingTax = ONE_HUNDRED;
        price.priceIncludingTax = ONE_HUNDRED_TTC_20;
        price.vatRate = TAX_RATE_20;

        AmountEvaluation amount = new AmountEvaluation(price);

        assertEquals(ONE_HUNDRED, amount.amountExcludingTax);
        assertEquals(ONE_HUNDRED_TTC_20, amount.amountIncludingTax);
        assertEquals(TAX_RATE_20, amount.vatRate);
    }

    // --------------------------------------------------
    // Arithmetic Tests
    // --------------------------------------------------

    /**
     * Tests the add method with two amounts having the same VAT rate.
     */
    @Test
    void testAdd_SameRate() {
        AmountEvaluation a1 = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);
        AmountEvaluation a2 = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);

        AmountEvaluation result = a1.add(a2);

        assertEquals(new BigDecimal("200.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("240.00"), result.amountIncludingTax);
        assertEquals(TAX_RATE_20, result.vatRate);
    }

    /**
     * Tests the add method with amounts having different VAT rates.
     * Verifies that the resulting VAT rate is the effective rate of the total.
     */
    @Test
    void testAdd_DifferentRates_CalculatesEffectiveRate() {
        // Item 1: 100 HT, 120 TTC (20%)
        AmountEvaluation a1 = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);
        // Item 2: 100 HT, 110 TTC (10%)
        AmountEvaluation a2 = new AmountEvaluation(ONE_HUNDRED, new BigDecimal("110.00"), TAX_RATE_10);

        AmountEvaluation result = a1.add(a2);

        // Total: 200 HT, 230 TTC
        assertEquals(new BigDecimal("200.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("230.00"), result.amountIncludingTax);

        // Effective VAT = (230 / 200) - 1 = 0.15
        assertEquals(new BigDecimal("0.1500"), result.vatRate);
    }

    /**
     * Tests the add method with multiple inputs.
     */
    @Test
    void testAdd_MultipleItems() {
        AmountEvaluation a1 = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);
        AmountEvaluation a2 = new AmountEvaluation(ONE_HUNDRED, new BigDecimal("110.00"), TAX_RATE_10);
        AmountEvaluation a3 = new AmountEvaluation(ONE_HUNDRED, new BigDecimal("105.50"), TAX_RATE_5_5);

        AmountEvaluation result = a1.add(a2, a3);

        // Total: 300 HT, 120 + 110 + 105.5 = 335.5 TTC
        assertEquals(new BigDecimal("300.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("335.50"), result.amountIncludingTax);
        // Effective VAT = (335.5 / 300) - 1 = 0.11833... -> 0.1183 (Scale 4, HALF_UP)
        assertEquals(new BigDecimal("0.1183"), result.vatRate);
    }

    /**
     * Tests the specific edge case in add() where the total Excluding Tax is zero.
     * Verifies that the method returns an empty AmountEvaluation object.
     */
    @Test
    void testAdd_TotalExclTaxIsZero() {
        AmountEvaluation zero1 = new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        AmountEvaluation zero2 = new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        AmountEvaluation result = zero1.add(zero2);

        assertEquals(BigDecimal.ZERO.setScale(2), result.amountExcludingTax);
        assertEquals(BigDecimal.ZERO.setScale(2), result.amountIncludingTax);
        assertEquals(BigDecimal.ZERO.setScale(4), result.vatRate);
    }

    /**
     * Tests adding a zero amount to a non-zero amount.
     * Verifies that the normal calculation flow is used (not the zero shortcut).
     */
    @Test
    void testAdd_TotalZero() {
        AmountEvaluation a1 = new AmountEvaluation(ZERO, ZERO, ZERO);
        AmountEvaluation a2 = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);

        AmountEvaluation result = a1.add(a2);
        // Not zero, so normal flow
        assertNotEquals(ZERO, result.amountExcludingTax);
    }

    /**
     * Tests the subtract method.
     */
    @Test
    void testSubtract() {
        // Total: 200 HT, 230 TTC
        AmountEvaluation total = new AmountEvaluation(new BigDecimal("200.00"), new BigDecimal("230.00"), new BigDecimal("0.1500"));
        // Sub: 100 HT, 120 TTC (20%)
        AmountEvaluation sub = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);

        AmountEvaluation result = total.subtract(sub);

        assertEquals(new BigDecimal("100.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("110.00"), result.amountIncludingTax);
        // VAT: (110/100) - 1 = 0.10
        assertEquals(new BigDecimal("0.1000"), result.vatRate);
    }

    /**
     * Tests multiplying the amount by a quantity.
     */
    @Test
    void testMultiplyQuantity() {
        AmountEvaluation amount = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);
        BigDecimal quantity = new BigDecimal("2.5");

        AmountEvaluation result = amount.multiply(quantity);

        assertEquals(new BigDecimal("250.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("300.00"), result.amountIncludingTax);
        assertEquals(TAX_RATE_20, result.vatRate);
    }

    /**
     * Tests multiplying the amount by an efficiency factor.
     */
    @Test
    void testMultiplyEfficiency() {
        // Efficiency 0.10 implies keeping 90% (e.g., yield loss or discount)
        AmountEvaluation amount = new AmountEvaluation(ONE_HUNDRED, ONE_HUNDRED_TTC_20, TAX_RATE_20);
        double efficiency = 0.10;

        AmountEvaluation result = amount.multiply(efficiency);

        // 100 * 0.9 = 90
        assertEquals(new BigDecimal("90.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("108.00"), result.amountIncludingTax);
        assertEquals(TAX_RATE_20, result.vatRate);
    }

    // --------------------------------------------------
    // Static Method Logic Tests
    // --------------------------------------------------

    /**
     * Tests getAmount for a UNIT product type.
     * Verifies standard multiplication of unit price by quantity.
     */
    @Test
    void testGetAmount_UnitProduct() {
        Product product = new Product();
        product.productType = ProductType.UNIT;

        Price price = new Price();
        price.priceExcludingTax = new BigDecimal("10.00");
        price.priceIncludingTax = new BigDecimal("12.00");
        price.vatRate = TAX_RATE_20;

        double quantity = 3.0;

        AmountEvaluation result = AmountEvaluation.getAmount(product, price, quantity);

        assertEquals(new BigDecimal("30.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("36.00"), result.amountIncludingTax);
    }

    /**
     * Tests getAmount for a WEIGHT product type with valid configuration.
     * Verifies calculation using the ratio of purchased weight to reference weight.
     */
    @Test
    void testGetAmount_WeightProduct() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = new BigDecimal("0.500"); // 500g per unit price

        Price price = new Price();
        price.priceExcludingTax = new BigDecimal("10.00"); // Price per 500g
        price.priceIncludingTax = new BigDecimal("12.00");
        price.vatRate = TAX_RATE_20;

        double quantityKg = 1.500; // Buying 1.5kg

        AmountEvaluation result = AmountEvaluation.getAmount(product, price, quantityKg);

        // Ratio = 1.5 / 0.5 = 3 units
        // Total = 3 * 10 = 30
        assertEquals(new BigDecimal("30.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("36.00"), result.amountIncludingTax);
    }

    /**
     * Tests getAmount for a VOLUME product type with valid configuration.
     */
    @Test
    void testGetAmount_VolumeProduct() {
        Product product = new Product();
        product.productType = ProductType.VOLUME;
        product.referenceVolume = new BigDecimal("1.0"); // 1 Liter

        Price price = new Price();
        price.priceExcludingTax = new BigDecimal("5.00"); // Price per Liter
        price.priceIncludingTax = new BigDecimal("6.00");
        price.vatRate = TAX_RATE_20;

        double quantityLiters = 10.0;

        AmountEvaluation result = AmountEvaluation.getAmount(product, price, quantityLiters);

        assertEquals(new BigDecimal("50.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("60.00"), result.amountIncludingTax);
    }

    /**
     * Tests the safety check when Product is null.
     * Verifies that a zero-valued AmountEvaluation is returned.
     */
    @Test
    void testGetAmount_NullInputs_ReturnsZero() {
        AmountEvaluation result = AmountEvaluation.getAmount(null, null, 1.0);
        assertEquals(BigDecimal.ZERO.setScale(2), result.amountExcludingTax);
        assertEquals(BigDecimal.ZERO.setScale(2), result.amountIncludingTax);
    }

    /**
     * Tests the safety check when Price is null.
     * Verifies that a zero-valued AmountEvaluation is returned.
     */
    @Test
    void testGetAmount_PriceIsNull() {
        Product product = new Product();
        product.productType = ProductType.UNIT;

        AmountEvaluation result = AmountEvaluation.getAmount(product, null, 2.0);

        // Expect an empty object (zero) according to safety logic
        assertEquals(BigDecimal.ZERO.setScale(2), result.amountExcludingTax);
        assertEquals(BigDecimal.ZERO.setScale(2), result.amountIncludingTax);
    }

    /**
     * Tests validation for WEIGHT products when referenceWeight is null.
     * Expects an IllegalStateException.
     */
    @Test
    void testGetAmount_WeightProduct_MissingRefWeight_ThrowsException() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = null; // Missing config

        Price price = new Price();
        price.priceExcludingTax = new BigDecimal("10.00");

        assertThrows(IllegalStateException.class, () -> {
            AmountEvaluation.getAmount(product, price, 1.0);
        });
    }

    /**
     * Tests validation for WEIGHT products when referenceWeight is zero or negative.
     * Expects an IllegalStateException.
     */
    @Test
    void testGetAmount_WeightProduct_ReferenceWeightIsZeroOrNegative() {
        Product productZero = new Product();
        productZero.productType = ProductType.WEIGHT;
        productZero.referenceWeight = BigDecimal.ZERO; // Equal to 0

        Product productNegative = new Product();
        productNegative.productType = ProductType.WEIGHT;
        productNegative.referenceWeight = new BigDecimal("-1.00"); // Negative

        Price price = new Price();
        price.priceExcludingTax = new BigDecimal("10.00");
        price.priceIncludingTax = new BigDecimal("12.00");

        // Test with reference = 0
        assertThrows(IllegalStateException.class, () -> {
            AmountEvaluation.getAmount(productZero, price, 1.0);
        });

        // Test with negative reference
        assertThrows(IllegalStateException.class, () -> {
            AmountEvaluation.getAmount(productNegative, price, 1.0);
        });
    }

    /**
     * Tests validation for VOLUME products when referenceVolume is invalid (null, zero, or negative).
     * Expects an IllegalStateException.
     */
    @Test
    void testGetAmount_VolumeProduct_ReferenceVolumeIsInvalid() {
        Product product = new Product();
        product.productType = ProductType.VOLUME;

        Price price = new Price();

        // Case 1: referenceVolume is null
        product.referenceVolume = null;

        IllegalStateException exceptionNull = assertThrows(IllegalStateException.class, () -> {
            AmountEvaluation.getAmount(product, price, 1.0);
        });

        // Verify that the message matches the configuration error for Volume
        assertTrue(exceptionNull.getMessage().contains("no valid reference volume defined"));

        // Case 2: referenceVolume is less than or equal to 0
        product.referenceVolume = BigDecimal.ZERO;
        assertThrows(IllegalStateException.class, () -> {
            AmountEvaluation.getAmount(product, price, 1.0);
        });

        product.referenceVolume = new BigDecimal("-1.0");
        assertThrows(IllegalStateException.class, () -> {
            AmountEvaluation.getAmount(product, price, 1.0);
        });
    }

    // --------------------------------------------------
    // Integration Tests with Basket.Item (Mocked)
    // --------------------------------------------------

    /**
     * Tests getAmount using a Basket.Item context.
     */
    @Test
    void testGetAmount_BasketItem(@Mock Basket.Item item, @Mock Store store, @Mock Product product, @Mock Price price) {
        // Setup Mocks
        when(item.getProduct()).thenReturn(product);
        when(item.getPrice(store, PriceUsage.DEFAULT)).thenReturn(price);

        // Setup public fields via mock (or assume they are accessible)
        item.quantity = 2.0;

        // Setup Domain Objects
        product.productType = ProductType.UNIT;
        price.priceExcludingTax = new BigDecimal("10.00");
        price.priceIncludingTax = new BigDecimal("12.00");
        price.vatRate = TAX_RATE_20;

        // Execute
        AmountEvaluation result = AmountEvaluation.getAmount(item, store, PriceUsage.DEFAULT);

        // Assert
        assertEquals(new BigDecimal("20.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("24.00"), result.amountIncludingTax);
    }

    /**
     * Tests getAmount for a collection of Basket.Items.
     * Verifies summation of multiple items.
     */
    @Test
    void testGetAmount_CollectionOfItems(@Mock Basket.Item item1, @Mock Basket.Item item2, @Mock Store store) {
        List<Basket.Item> items = Arrays.asList(item1, item2);

        // Mock Item 1
        Product p1 = new Product(); p1.productType = ProductType.UNIT;
        Price pr1 = new Price();
        pr1.priceExcludingTax = new BigDecimal("10.00");
        pr1.priceIncludingTax = new BigDecimal("11.00"); // 10% tax
        pr1.vatRate = TAX_RATE_10;

        when(item1.getProduct()).thenReturn(p1);
        when(item1.getPrice(store, PriceUsage.DEFAULT)).thenReturn(pr1);
        item1.quantity = 1.0;

        // Mock Item 2
        Product p2 = new Product(); p2.productType = ProductType.UNIT;
        Price pr2 = new Price();
        pr2.priceExcludingTax = new BigDecimal("20.00");
        pr2.priceIncludingTax = new BigDecimal("22.00"); // 10% tax
        pr2.vatRate = TAX_RATE_10;

        when(item2.getProduct()).thenReturn(p2);
        when(item2.getPrice(store, PriceUsage.DEFAULT)).thenReturn(pr2);
        item2.quantity = 1.0;

        AmountEvaluation result = AmountEvaluation.getAmount(items, store, PriceUsage.DEFAULT);

        // Total: 30 HT, 33 TTC
        assertEquals(new BigDecimal("30.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("33.00"), result.amountIncludingTax);
        assertEquals(TAX_RATE_10, result.vatRate);
    }

    /**
     * Tests getAmountForProduct to ensure filtering by EAN works correctly.
     */
    @Test
    void testGetAmountForProduct_Filtering(@Mock Basket.Item item1, @Mock Basket.Item item2, @Mock Store store) {
        List<Basket.Item> items = Arrays.asList(item1, item2);
        String targetEan = "123456";

        // Item 1: Matching EAN
        Product p1 = new Product(); p1.productType = ProductType.UNIT;
        Price pr1 = new Price();
        pr1.priceExcludingTax = new BigDecimal("10.00");
        pr1.priceIncludingTax = new BigDecimal("12.00");
        pr1.vatRate = TAX_RATE_20;

        when(item1.getProduct()).thenReturn(p1);
        when(item1.getPrice(store, PriceUsage.DEFAULT)).thenReturn(pr1);
        item1.quantity = 1.0;
        item1.produceEan = targetEan; // Matching field

        // Item 2: Different EAN
        Product p2 = new Product(); p2.productType = ProductType.UNIT;
        Price pr2 = new Price();
        pr2.priceExcludingTax = new BigDecimal("50.00");
        pr2.priceIncludingTax = new BigDecimal("60.00");

        item2.quantity = 1.0;
        item2.produceEan = "99999"; // Not matching

        AmountEvaluation result = AmountEvaluation.getAmountForProduct(items, targetEan, store, PriceUsage.DEFAULT);

        // Should only include Item 1
        assertEquals(new BigDecimal("10.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("12.00"), result.amountIncludingTax);
    }

    /**
     * Tests getAmount for an array of Basket.Items.
     */
    @Test
    void testGetAmount_ArrayOfItems(@Mock Basket.Item item, @Mock Store store) {
        Basket.Item[] items = new Basket.Item[]{item};

        Product p = new Product(); p.productType = ProductType.UNIT;
        Price pr = new Price();
        pr.priceExcludingTax = new BigDecimal("10.00");
        pr.priceIncludingTax = new BigDecimal("12.00");
        pr.vatRate = TAX_RATE_20;

        when(item.getProduct()).thenReturn(p);
        when(item.getPrice(store, PriceUsage.DEFAULT)).thenReturn(pr);
        item.quantity = 1.0;

        AmountEvaluation result = AmountEvaluation.getAmount(items, store, PriceUsage.DEFAULT);

        assertEquals(new BigDecimal("10.00"), result.amountExcludingTax);
    }
}