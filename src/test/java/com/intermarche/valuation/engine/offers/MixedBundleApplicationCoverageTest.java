package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @QuarkusTest} coverage tests for the discount-priced branch of
 * {@link MixedBundleOfferFactory.MixedBundleApplication}.
 * <p>
 * Every existing {@code MixedBundleOfferFactoryTest} scenario prices its bundle with a fixed
 * {@code bundlePrice}, so {@code getAmount} always takes the {@code computeFixedPriceTotal}
 * arm and {@code computeDiscountedTotal} stays red under quarkus-jacoco. These tests drive a
 * discount-mode offer (both {@code PERCENTAGE} and {@code FIXED_AMOUNT}) through the factory,
 * apply it, then call {@code getAmount} to execute the discount arm of the pricing ternary and
 * the whole {@code computeDiscountedTotal} body, including the negative-total floor.
 * <p>
 * The reference price is read with {@link PriceUsage#BASE_FOR_DISCOUNT}, so a real DB price is
 * seeded with {@link DomainUtils} and the application is produced by the real applier.
 */
@QuarkusTest
@TestTransaction
public class MixedBundleApplicationCoverageTest {

    @Inject
    MixedBundleOfferFactory factory;

    private Store store;
    private Product mainProduct;

    /**
     * Seeds a store and a single product priced 10.00 HT / 12.00 TTC (20% VAT) for both the
     * default and the base-for-discount usages.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.0, 2.0);
        mainProduct = DomainUtils.createAndPersistProduct("1000000000001", "Main Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(mainProduct, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(mainProduct, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));
    }

    /**
     * Builds the single application produced by a discount-mode bundle offer over one unit of
     * the main product.
     *
     * @param jsonSpec The MIXED_BUNDLE specification, priced by a discount.
     * @param code     The offer code.
     * @return The produced {@link MixedBundleOfferFactory.MixedBundleApplication}.
     */
    private MixedBundleOfferFactory.MixedBundleApplication buildApplication(String jsonSpec, String code) {
        DomainUtils.createAndPersistOffer(code, store, "MIXED_BUNDLE", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();
        return (MixedBundleOfferFactory.MixedBundleApplication) applier.apply(evaluation).iterator().next();
    }

    /**
     * Verifies discount pricing by a percentage.
     * <p>
     * Covers the discount arm of the {@code getAmount} ternary (line 465) and the
     * {@code "PERCENTAGE".equals(discountType)} branch of {@code computeDiscountedTotal}.
     * Reference TTC 12.00, minus 20% (2.40), equals 9.60 TTC / 8.00 HT.
     */
    @Test
    void testGetAmountDiscountPercentage() {
        setUpDatabase();
        String jsonSpec = "{ \"discount\": { \"type\": \"PERCENTAGE\", \"value\": 20.0 }, " +
                "\"vatRate\": 0.20, \"contents\": [ { \"ean\": \"1000000000001\", \"quantity\": 1.0 } ] }";
        MixedBundleOfferFactory.MixedBundleApplication app = buildApplication(jsonSpec, "DISC_PCT");
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("9.60"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("8.00"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Verifies discount pricing by a fixed amount.
     * <p>
     * Covers the {@code else} (FIXED_AMOUNT) branch of {@code computeDiscountedTotal}.
     * Reference TTC 12.00, minus 3.00 per bundle, equals 9.00 TTC / 7.50 HT.
     */
    @Test
    void testGetAmountDiscountFixedAmount() {
        setUpDatabase();
        String jsonSpec = "{ \"discount\": { \"type\": \"FIXED_AMOUNT\", \"value\": 3.00 }, " +
                "\"vatRate\": 0.20, \"contents\": [ { \"ean\": \"1000000000001\", \"quantity\": 1.0 } ] }";
        MixedBundleOfferFactory.MixedBundleApplication app = buildApplication(jsonSpec, "DISC_FIX");
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("9.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("7.50"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Verifies that a discount larger than the reference price floors the total at zero.
     * <p>
     * Covers the {@code total.max(BigDecimal.ZERO)} floor of {@code computeDiscountedTotal}
     * returning zero. Reference TTC 12.00, minus 100.00, floored to 0.00 TTC / 0.00 HT.
     */
    @Test
    void testGetAmountDiscountFloorsAtZero() {
        setUpDatabase();
        String jsonSpec = "{ \"discount\": { \"type\": \"FIXED_AMOUNT\", \"value\": 100.00 }, " +
                "\"vatRate\": 0.20, \"contents\": [ { \"ean\": \"1000000000001\", \"quantity\": 1.0 } ] }";
        MixedBundleOfferFactory.MixedBundleApplication app = buildApplication(jsonSpec, "DISC_FLOOR");
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("0.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.00"), amount.amountExcludingTax);
    }
}
