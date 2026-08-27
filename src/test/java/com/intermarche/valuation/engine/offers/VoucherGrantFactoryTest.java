package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link VoucherGrantFactory} using the real database.
 * <p>
 * Every scenario goes through {@code buildAppliers} with a persisted offer, so the schema,
 * the cross-field validations and the applier computation are exercised together, then
 * feeds hand-built product-aware applications into the evaluation.
 */
@QuarkusTest
@TestTransaction
public class VoucherGrantFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    VoucherGrantFactory factory;

    /**
     * The store the offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by every test; called manually at the start of each test
     * because {@code @TestTransaction} rolls back between tests.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
    }

    /**
     * Builds an evaluation on a basket attached to the seeded store.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        return new BasketEvaluation(basket);
    }

    /**
     * Persists a voucher grant offer, builds its applier and applies it on an evaluation
     * holding one merchandise application of the given amount.
     *
     * @param spec     the offer specification.
     * @param ttcPrice the merchandise amount fed into the evaluation, tax included.
     * @return the resulting advantage applications.
     */
    private Collection<AdvantageApplication> applyOn(String spec, double ttcPrice) {
        DomainUtils.createAndPersistOffer("VG_RUN", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        Product product = new Product();
        product.ean = "1000000000001";
        evaluation.getOffers().add(new ProductApplication(product, 1.0, ttcPrice));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        return appliers.iterator().next().apply(evaluation);
    }

    /**
     * Tests the successful creation of an applier from a valid specification.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("VG_01", store, "VOUCHER_GRANT", spec);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());

        assertEquals(1, appliers.size());
        assertFalse(appliers.iterator().next().isApplicable(null));
    }

    /**
     * Tests that the ITEMS scope without target EANs is rejected.
     */
    @Test
    void testBuildAppliers_ItemsScopeWithoutTargetsRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("VG_02", store, "VOUCHER_GRANT", spec);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a VAT_AMOUNT award outside the HIGHEST_REACHED mode is rejected.
     */
    @Test
    void testBuildAppliers_VatAmountOutsideHighestRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"VAT_AMOUNT\" } } ] }";
        DomainUtils.createAndPersistOffer("VG_03", store, "VOUCHER_GRANT", spec);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests the highest-reached percentage on the ticket: a 120€ base reaches the 100€
     * tier and grants a 12€ voucher, without any impact on the basket total.
     */
    @Test
    void testApply_HighestReached_Percentage() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"usage\": { \"validityDays\": 30 }, "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } }, "
                + "{ \"threshold\": 100.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } ] }";

        Collection<AdvantageApplication> grants = applyOn(spec, 120.00);

        assertEquals(1, grants.size());
        AdvantageApplication advantage = grants.iterator().next();
        assertFalse(advantage instanceof DiscountApplication);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) advantage;
        assertEquals(new BigDecimal("12.00"), grant.getGrant().amount());
        assertEquals("VOUCHER", grant.getGrant().instrument());
        assertEquals("EUR", grant.getGrant().unit());
        assertNotNull(grant.getGrant().usage());
        assertEquals(30, grant.getGrant().usage().get("validityDays").asInt());
        assertTrue(grant.getType().contains("Voucher Grant: VG_RUN (tier 100"));
        assertNull(grant.getOfferApplication());
    }

    /**
     * Tests the VAT_AMOUNT award: the grant equals the VAT of the assiette
     * (120€ at 20% VAT carries 20€ of VAT).
     */
    @Test
    void testApply_VatAmount() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"VAT_AMOUNT\" } } ] }";

        Collection<AdvantageApplication> grants = applyOn(spec, 120.00);

        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("20.00"), grant.getGrant().amount());
    }

    /**
     * Tests the per-multiple points grant: 55€ with a 10€ step and a 1-point award grant
     * 5 whole points.
     */
    @Test
    void testApply_PerMultiple_Points() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PER_MULTIPLE\", "
                + "\"unit\": \"POINTS\", "
                + "\"every\": { \"step\": 10.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 1 } } }";

        Collection<AdvantageApplication> grants = applyOn(spec, 55.00);

        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("5"), grant.getGrant().amount());
        assertEquals("POINTS", grant.getGrant().unit());
        assertTrue(grant.getType().contains("x5"));
    }

    /**
     * Tests that a base below the first threshold grants nothing.
     */
    @Test
    void testApply_BelowFirstTier() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } } ] }";

        assertTrue(applyOn(spec, 30.00).isEmpty());
    }

    /**
     * Tests the ITEMS scope: only the amounts attributed to the targeted products feed
     * the assiette.
     */
    @Test
    void testApply_ItemsScope_FiltersContributions() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } ] }";
        Product target = DomainUtils.createAndPersistProduct(
                "1000000000001", "Target", com.intermarche.valuation.domain.ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_ITEMS", store, "VOUCHER_GRANT", spec);
        Product other = new Product();
        other.ean = "9999999999999";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(target, 1.0, 60.00));
        evaluation.getOffers().add(new ProductApplication(other, 1.0, 500.00));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);

        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        // 10% of the 60€ targeted assiette only — the 500€ foreign product is ignored.
        assertEquals(new BigDecimal("6.00"), grant.getGrant().amount());
    }

    /**
     * Tests the progressive percentage on the ticket: 120€ against floors 0 (5%), 50 (10%)
     * and 100 (15%) grants 2.50 + 5.00 + 3.00 = 10.50€.
     */
    @Test
    void testApply_Progressive_Percentage() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } }, "
                + "{ \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } }, "
                + "{ \"threshold\": 100.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 15.0 } } ] }";
        Collection<AdvantageApplication> grants = applyOn(spec, 120.00);
        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("10.50"), grant.getGrant().amount());
        assertTrue(grant.getType().contains("progressive"));
    }

    /**
     * Tests the progressive AMOUNT_PER_ITEM award on the QUANTITY trigger in ITEMS scope:
     * floors 0 (0.10) and 3 (0.20) over 5 units grant 3×0.10 + 2×0.20 = 0.70€.
     */
    @Test
    void testApply_Progressive_AmountPerItem_Quantity() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.10 } }, "
                + "{ \"threshold\": 3.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.20 } } ] }";
        Product target = DomainUtils.createAndPersistProduct("1000000000001", "Target", ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_PAPI", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(target, 5.0, 20.00));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);
        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("0.70"), grant.getGrant().amount());
    }

    /**
     * Tests the VAT_AMOUNT award in ITEMS scope with a multi-rate assiette: a 20% line
     * (20€ of VAT) and a 5.5% line (5.50€ of VAT) grant 25.50€.
     */
    @Test
    void testApply_VatAmount_ItemsScope_MultiRate() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\", \"1000000000002\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"VAT_AMOUNT\" } } ] }";
        Product standard = DomainUtils.createAndPersistProduct("1000000000001", "Standard", ProductType.UNIT);
        Product reduced = DomainUtils.createAndPersistProduct("1000000000002", "Reduced", ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_VAT", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(standard, 1.0, 100.00, 120.00, 0.20));
        evaluation.getOffers().add(new ProductApplication(reduced, 1.0, 100.00, 105.50, 0.055));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);
        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("25.50"), grant.getGrant().amount());
    }

    /**
     * Tests that a POINTS grant is rounded to the whole point: 10% of 54€ is 5.40, rounded
     * to 5 points.
     */
    @Test
    void testApply_Points_RoundsToWholePoint() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"unit\": \"POINTS\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } ] }";
        Collection<AdvantageApplication> grants = applyOn(spec, 54.00);
        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("5"), grant.getGrant().amount());
        assertEquals("POINTS", grant.getGrant().unit());
    }

    /**
     * Tests that an absent usage block leaves the grant usage null and drops it from the
     * serialized JSON (the record is annotated NON_NULL).
     *
     * @throws Exception if the grant cannot be serialized.
     */
    @Test
    void testApply_UsageAbsent_NullAndNotSerialized() throws Exception {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        Collection<AdvantageApplication> grants = applyOn(spec, 60.00);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertNull(grant.getGrant().usage());
        String json = new ObjectMapper().writeValueAsString(grant.getGrant());
        assertFalse(json.contains("usage"));
    }

    /**
     * Tests that an offer attached to a store group containing the store is picked up
     * through the group resolution.
     */
    @Test
    void testApply_StoreGroupScope() {
        setUpDatabase();
        StoreGroup group = DomainUtils.createAndPersistStoreGroup("GROUP_01");
        group.stores.add(store);
        group.persist();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        com.intermarche.valuation.domain.Offer offer = new com.intermarche.valuation.domain.Offer();
        offer.code = "VG_GROUP";
        offer.type = "VOUCHER_GRANT";
        offer.specification = spec;
        offer.storeGroups.add(group);
        offer.persist();
        BasketEvaluation evaluation = newEvaluation();
        Product product = new Product();
        product.ean = "1000000000001";
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 60.00));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);
        assertEquals(1, grants.size());
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("5.00"), grant.getGrant().amount());
        assertTrue(grant.getType().startsWith("Voucher Grant: VG_GROUP"));
    }

    /**
     * Tests that an empty assiette (no product-aware application at all) grants nothing.
     */
    @Test
    void testApply_EmptyAssiette_NoApplication() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("VG_EMPTY", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next().apply(evaluation).isEmpty());
    }

    /**
     * Tests that a non-blank schema is exposed by the factory.
     */
    @Test
    void testGetSchema() {
        assertTrue(factory.getSchema().contains("Instrument Grant Offer Specification"));
    }

    /**
     * Tests that a non-VAT award without a value is rejected.
     */
    @Test
    void testBuildAppliers_AmountWithoutValueRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\" } } ] }";
        DomainUtils.createAndPersistOffer("VG_NOVAL", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an AMOUNT award in PROGRESSIVE mode is rejected.
     */
    @Test
    void testBuildAppliers_ProgressiveAmountRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("VG_PROGA", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an AMOUNT_PER_ITEM award in PROGRESSIVE mode on the AMOUNT trigger is
     * rejected.
     */
    @Test
    void testBuildAppliers_ProgressiveAmountPerItemAmountTriggerRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.5 } } ] }";
        DomainUtils.createAndPersistOffer("VG_PAPIAT", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an AMOUNT_PER_ITEM award in PER_MULTIPLE mode is rejected.
     */
    @Test
    void testBuildAppliers_PerMultipleAmountPerItemRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PER_MULTIPLE\", "
                + "\"every\": { \"step\": 2.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.5 } } }";
        DomainUtils.createAndPersistOffer("VG_PMAPI", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that the TICKET scope combined with the QUANTITY trigger is rejected.
     */
    @Test
    void testBuildAppliers_TicketQuantityRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"QUANTITY\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 3.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("VG_TQ", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an AMOUNT_PER_ITEM award listed under the TICKET scope is rejected by the
     * cross-field validation.
     */
    @Test
    void testBuildAppliers_TicketAmountPerItemRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.5 } } ] }";
        DomainUtils.createAndPersistOffer("VG_TAPI", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a PER_MULTIPLE specification carrying 'tiers' instead of 'every' is rejected.
     */
    @Test
    void testBuildAppliers_PerMultipleWithoutEveryRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PER_MULTIPLE\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("VG_PMNE", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a HIGHEST_REACHED specification carrying 'every' instead of 'tiers' is
     * rejected.
     */
    @Test
    void testBuildAppliers_HighestWithoutTiersRejected() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"every\": { \"step\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } }";
        DomainUtils.createAndPersistOffer("VG_HNT", store, "VOUCHER_GRANT", spec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a progressive resolution whose base is below the first floor grants nothing.
     */
    @Test
    void testApply_Progressive_BelowFirstFloor_NoGrant() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } ] }";
        assertTrue(applyOn(spec, 30.00).isEmpty());
    }

    /**
     * Tests the progressive PERCENTAGE award on the QUANTITY trigger in ITEMS scope: floors
     * 0 (5%) and 3 (10%) over 5 units at a 4€ average grant 3×4×5% + 2×4×10% = 1.40€.
     */
    @Test
    void testApply_Progressive_Percentage_QuantityTrigger() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } }, "
                + "{ \"threshold\": 3.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } ] }";
        Product target = DomainUtils.createAndPersistProduct("1000000000001", "Target", ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_PPQ", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(target, 5.0, 20.00));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("1.40"), grant.getGrant().amount());
    }

    /**
     * Tests that a per-multiple resolution whose base is below the step grants nothing.
     */
    @Test
    void testApply_PerMultiple_BelowStep_NoGrant() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PER_MULTIPLE\", "
                + "\"every\": { \"step\": 100.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } }";
        assertTrue(applyOn(spec, 30.00).isEmpty());
    }

    /**
     * Tests the per-multiple PERCENTAGE award on the AMOUNT trigger: 120€ with a 50€ step
     * covers 100€ and a 10% award grants 10€.
     */
    @Test
    void testApply_PerMultiple_Percentage_AmountTrigger() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"PER_MULTIPLE\", "
                + "\"every\": { \"step\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } }";
        Collection<AdvantageApplication> grants = applyOn(spec, 120.00);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("10.00"), grant.getGrant().amount());
    }

    /**
     * Tests the per-multiple PERCENTAGE award on the QUANTITY trigger in ITEMS scope: 5 units
     * at a 24€ average, a 2-unit step, cover 4 units (96€), a 10% award grants 9.60€.
     */
    @Test
    void testApply_PerMultiple_Percentage_QuantityTrigger() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PER_MULTIPLE\", "
                + "\"every\": { \"step\": 2.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 10.0 } } }";
        Product target = DomainUtils.createAndPersistProduct("1000000000001", "Target", ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_PMPQ", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(target, 5.0, 120.00));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("9.60"), grant.getGrant().amount());
    }

    /**
     * Tests the AMOUNT_PER_ITEM award in HIGHEST_REACHED mode on the QUANTITY trigger in
     * ITEMS scope: 5 units at 0.50€ each grant 2.50€.
     */
    @Test
    void testApply_HighestReached_AmountPerItem() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 3.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.50 } } ] }";
        Product target = DomainUtils.createAndPersistProduct("1000000000001", "Target", ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_API", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(target, 5.0, 20.00));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        Collection<AdvantageApplication> grants = appliers.iterator().next().apply(evaluation);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(new BigDecimal("2.50"), grant.getGrant().amount());
    }

    /**
     * Tests that a POINTS grant rounding down to zero yields no application: 0.5% of 50€ is
     * 0.25, rounded to 0 points.
     */
    @Test
    void testApply_Points_RoundsToZero_NoGrant() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"unit\": \"POINTS\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 0.5 } } ] }";
        assertTrue(applyOn(spec, 50.00).isEmpty());
    }

    /**
     * Tests that {@code getOffer} echoes the application type for a grant, shielding the
     * default resolution from the null target application.
     */
    @Test
    void testApply_GetOfferEchoesType() {
        setUpDatabase();
        String spec = "{ \"scope\": \"TICKET\", \"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        Collection<AdvantageApplication> grants = applyOn(spec, 60.00);
        InstrumentGrantFactory.InstrumentGrantApplication grant =
                (InstrumentGrantFactory.InstrumentGrantApplication) grants.iterator().next();
        assertEquals(grant.getType(), grant.getOffer());
    }

    /**
     * Tests that a targeted product covered with a positive quantity but a null amount is
     * skipped when gathering the assiette, so nothing is granted.
     */
    @Test
    void testApply_ItemsScope_NullProductAmount_NoGrant() {
        setUpDatabase();
        String spec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 1.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistProduct("1000000000001", "Target", ProductType.UNIT);
        DomainUtils.createAndPersistOffer("VG_NULLAMT", store, "VOUCHER_GRANT", spec);
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new NullAmountApplication("1000000000001", 2.0));
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.iterator().next().apply(evaluation).isEmpty());
    }

    /**
     * A product-aware application that reports a positive quantity but a null per-product
     * amount, used to drive the null-amount skip when gathering the assiette.
     */
    public static class NullAmountApplication implements ProductAwareOfferApplication {

        /**
         * The covered EAN.
         */
        final String ean;

        /**
         * The reported quantity.
         */
        final double quantity;

        /**
         * Builds the stub.
         *
         * @param ean      the covered EAN.
         * @param quantity the reported quantity.
         */
        public NullAmountApplication(String ean, double quantity) {
            this.ean = ean;
            this.quantity = quantity;
        }

        /**
         * Returns a null overall amount: this stub is only probed per product.
         *
         * @return {@code null}.
         */
        @Override
        public AmountEvaluation getAmount() {
            return null;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "NullAmount";
        }

        /**
         * Reports no amount for any product.
         *
         * @param product the product being asked about.
         * @return {@code null}, always.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return null;
        }

        /**
         * Reports the covered quantity for the matching product.
         *
         * @param product the product being asked about.
         * @return the covered quantity when the EAN matches, zero otherwise.
         */
        @Override
        public double getProductQuantity(Product product) {
            return (product != null && ean.equals(product.ean)) ? quantity : 0.0;
        }
    }

    /**
     * Minimal product-aware application: one product, one quantity, one TTC amount at a
     * 20% VAT rate.
     */
    public static class ProductApplication implements ProductAwareOfferApplication {

        /**
         * The covered product.
         */
        final Product product;

        /**
         * The covered quantity in standard units.
         */
        final double quantity;

        /**
         * The attributed amount.
         */
        final AmountEvaluation amount;

        /**
         * Builds the application.
         *
         * @param product  the covered product.
         * @param quantity the covered quantity.
         * @param ttcPrice the attributed amount, tax included (20% VAT assumed).
         */
        public ProductApplication(Product product, double quantity, double ttcPrice) {
            this.product = product;
            this.quantity = quantity;
            BigDecimal ttc = BigDecimal.valueOf(ttcPrice);
            BigDecimal ht = ttc.divide(BigDecimal.valueOf(1.20), 2, RoundingMode.HALF_UP);
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.20"));
        }

        /**
         * Builds the application with an explicit tax breakdown, so a caller can mix VAT
         * rates within one assiette.
         *
         * @param product  the covered product.
         * @param quantity the covered quantity.
         * @param htPrice  the attributed amount, tax excluded.
         * @param ttcPrice the attributed amount, tax included.
         * @param rate     the VAT rate carried by the amount.
         */
        public ProductApplication(Product product, double quantity, double htPrice, double ttcPrice, double rate) {
            this.product = product;
            this.quantity = quantity;
            this.amount = new AmountEvaluation(
                    BigDecimal.valueOf(htPrice), BigDecimal.valueOf(ttcPrice), BigDecimal.valueOf(rate));
        }

        /**
         * Returns the attributed amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list; unused by the grants.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "Product";
        }

        /**
         * Returns the amount attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the amount when the EAN matches, null otherwise.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return (product != null && this.product.ean.equals(product.ean)) ? amount : null;
        }

        /**
         * Returns the quantity attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the quantity when the EAN matches, zero otherwise.
         */
        @Override
        public double getProductQuantity(Product product) {
            return (product != null && this.product.ean.equals(product.ean)) ? quantity : 0.0;
        }
    }
}
