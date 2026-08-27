package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link CouponGrantFactory}.
 * <p>
 * The shared mechanics are covered by {@link VoucherGrantFactoryTest}; this class only
 * proves the discriminator, the instrument name and the label of the coupon variant.
 */
@QuarkusTest
@TestTransaction
public class CouponGrantFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    CouponGrantFactory factory;

    /**
     * The store the offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by the test; called manually because
     * {@code @TestTransaction} rolls back between tests.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
    }

    /**
     * Tests that a coupon grant offer is picked up by its own discriminator and outputs
     * a COUPON instrument with the coupon label.
     */
    @Test
    void testApply_CouponDiscriminatorAndLabel() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("CG_01", store, "COUPON_GRANT", spec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Product product = new Product();
        product.ean = "1000000000001";
        evaluation.getOffers().add(new VoucherGrantFactoryTest.ProductApplication(product, 1.0, 60.00));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);

        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals("COUPON", grant.getGrant().instrument());
        assertEquals(new BigDecimal("5.00"), grant.getGrant().amount());
        assertTrue(grant.getType().startsWith("Coupon Grant: CG_01"));
    }
}
