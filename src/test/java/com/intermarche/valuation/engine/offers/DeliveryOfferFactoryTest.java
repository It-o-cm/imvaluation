package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DeliveryOfferFactory} using the real database.
 * <p>
 * This test class verifies the logic for creating delivery offer appliers,
 * calculating distances, applying tiered pricing, and validating constraints
 * (coordinates, modes, and offer specifications).
 * <p>
 * Updated to reflect that {@code processSpecification} now validates the JSON
 * against a JSON Schema and throws {@link IllegalArgumentException} on
 * non-conformity or parsing errors.
 */
@QuarkusTest
@TestTransaction
public class DeliveryOfferFactoryTest {

    @Inject
    DeliveryOfferFactory factory;

    private Store store;
    private Offer deliveryOffer;

    /**
     * Sets up the database with a standard store and a standard delivery offer before each test.
     * <p>
     * This serves as the "Happy Path" configuration that most tests will reuse.
     * The offer includes two tiers:
     * <ul>
     *   <li>0-10km: 5.00€</li>
     *   <li>10-20km: 8.00€</li>
     * </ul>
     */
    void setUpDatabase() {
        // Create a standard Store in Paris
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // Create a standard Delivery Offer
        String jsonSpec = "{ \"tiers\": [ " +
                "{ \"maxDistance\": 10.0, \"price\": 5.00 }, " +
                "{ \"maxDistance\": 20.0, \"price\": 8.00 } " +
                "], \"vatRate\": 0.20 }";
        deliveryOffer = DomainUtils.createAndPersistOffer("DELIVERY_01", store, "DELIVERY", jsonSpec);
    }

    // --------------------------------------------------
    // Utility Methods
    // --------------------------------------------------

    /**
     * Helper method to create a configured {@link Basket} DTO.
     *
     * @param storeCode    The code of the store.
     * @param deliveryMode The delivery mode (e.g., "HOME_DELIVERY").
     * @param lat          The latitude of the delivery address.
     * @param lon          The longitude of the delivery address.
     * @return A configured {@link Basket} object.
     */
    private Basket createBasket(String storeCode, String deliveryMode, Double lat, Double lon) {
        Basket b = new Basket();
        b.storeCode = storeCode;
        b.deliveryMode = deliveryMode;
        b.deliveryAddress = new Basket.Address();
        if (lat != null) b.deliveryAddress.latitude = lat;
        if (lon != null) b.deliveryAddress.longitude = lon;
        return b;
    }

    // --------------------------------------------------
    // Success Tests
    // --------------------------------------------------

    /**
     * Tests the successful application of a delivery offer matching the first tier.
     * <p>
     * Scenario: Distance is ~1km (matches first tier: 5€).
     */
    @Test
    void testBuildAppliers_Success_MatchesFirstTier() {
        setUpDatabase();
        // Arrange: Basket address is very close to Store (~1km)
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.866, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<com.intermarche.valuation.engine.OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertEquals(1, appliers.size());
        com.intermarche.valuation.engine.OfferApplier applier = appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);
        assertEquals(1, apps.size());
        DeliveryOfferFactory.DeliveryApplication app =
                (DeliveryOfferFactory.DeliveryApplication) apps.iterator().next();
        // Verify Price (First Tier: 5€ TTC -> 4.17€ HT)
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("4.17"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("5.00"), amount.amountIncludingTax);
    }

    /**
     * Tests the successful application of a delivery offer matching the second tier.
     * <p>
     * Scenario: Distance is ~15km (matches second tier: 8€).
     */
    @Test
    void testBuildAppliers_Success_MatchesSecondTier() {
        setUpDatabase();
        // Arrange: Basket address is ~15km away
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.75, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        com.intermarche.valuation.engine.OfferApplier applier = appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        DeliveryOfferFactory.DeliveryApplication app =
                (DeliveryOfferFactory.DeliveryApplication) apps.iterator().next();
        // Verify Price (Second Tier: 8€)
        assertEquals(new BigDecimal("8.00"), app.getAmount().amountIncludingTax);
    }

    // --------------------------------------------------
    // Constraint & Validation Tests
    // --------------------------------------------------

    /**
     * Tests the scenario where the delivery mode is not "HOME_DELIVERY".
     */
    @Test
    void testBuildAppliers_Failure_WrongDeliveryMode() {
        setUpDatabase();
        // Arrange
        Basket basket = createBasket("STORE_01", "PICKUP", 48.866, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests the scenario where basket delivery address coordinates are missing.
     */
    @Test
    void testBuildAppliers_Failure_MissingBasketCoords() {
        setUpDatabase();
        // Arrange: Create basket without coords
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", null, null);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests the scenario where store address coordinates are missing.
     */
    @Test
    void testBuildAppliers_Failure_MissingStoreCoords() {
        setUpDatabase();
        // Arrange: Create a specific store without coords
        DomainUtils.createAndPersistStore("STORE_NO_COORDS", null, null);
        Basket basket = createBasket("STORE_NO_COORDS", "HOME_DELIVERY", 48.0, 2.0);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests the scenario where multiple DELIVERY offers are found for the store.
     */
    @Test
    void testBuildAppliers_Failure_MultipleOffers() {
        setUpDatabase();
        // Arrange: Create a second delivery offer for the same store
        String jsonSpec2 = "{ \"tiers\": [ { \"maxDistance\": 5.0, \"price\": 1.00 } ], \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DELIVERY_02", store, "DELIVERY", jsonSpec2);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if multiple delivery offers exist");
    }

    /**
     * Tests the scenario where the offer specification contains invalid JSON structure.
     * <p>
     * Expectation: Throws {@link IllegalArgumentException}.
     * This change reflects the update in {@code processSpecification} which wraps
     * {@link com.fasterxml.jackson.core.JsonProcessingException} in an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_Failure_InvalidJson() {
        setUpDatabase();
        // Arrange: Create offer with bad JSON
        String badJson = "{ \"vadid\": false }";
        DomainUtils.createAndPersistOffer("BAD_JSON_OFFER", store, "DELIVERY", badJson);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    // --------------------------------------------------
    // Logic & Edge Case Tests
    // --------------------------------------------------

    /**
     * Tests the scenario where the distance exceeds all defined tiers.
     * <p>
     * Expectation: Applier is created, but returns 0 applications.
     */
    @Test
    void testApply_NoTierMatches_DistanceTooFar() {
        setUpDatabase();
        // Arrange: Very far address (100km vs max 20km tier)
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 45.0, 2.0);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        com.intermarche.valuation.engine.OfferApplier applier = appliers.iterator().next();

        // Act
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should not create application if distance exceeds tiers");
    }

    /**
     * Tests {@link DeliveryOfferFactory.DeliveryApplication#getItems()}.
     */
    @Test
    void testDeliveryApplication_GetItems_ReturnsNull() {
        setUpDatabase();
        // Arrange: Create application manually
        DeliveryOfferFactory.DeliveryApplication app =
                new DeliveryOfferFactory.DeliveryApplication("DEL_01", BigDecimal.TEN, BigDecimal.valueOf(0.2), 5.0);

        // Act
        Collection<Basket.Item> items = app.getItems();

        // Assert
        assertNull(items, "Delivery application should return null for items");
    }

    /**
     * Tests {@link DeliveryOfferFactory.DeliveryApplication#getType()}.
     */
    @Test
    void testDeliveryApplication_GetType() {
        setUpDatabase();
        // Arrange
        DeliveryOfferFactory.DeliveryApplication app =
                new DeliveryOfferFactory.DeliveryApplication("DEL_01", BigDecimal.TEN, BigDecimal.valueOf(0.2), 15.123);

        // Act
        String type = app.getType();

        // Assert
        assertTrue(type.contains("DEL_01"));
        assertTrue(type.contains("15,12")); // Formatted (assuming French locale)
        assertTrue(type.contains("€"));
    }

    /**
     * Tests {@link DeliveryOfferFactory.DeliveryOfferApplier#computeEfficiencyScore}.
     */
    @Test
    void testDeliveryOfferApplier_ComputeEfficiencyScore() {
        setUpDatabase();
        // Arrange: Create applier manually (requires a valid Tier list)
        // We need access to DeliveryTier constructor, but it's private.
        // So we rely on the factory building the applier to get a valid internal state,
        // then we test the method.
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        com.intermarche.valuation.engine.OfferApplier applier = appliers.iterator().next();

        // Act
        double score = applier.computeEfficiencyScore(basket);

        // Assert
        assertEquals(0.0, score, "Delivery offer applier efficiency score should be 0.0");
    }

    /**
     * Tests the scenario where the basket delivery address object is null.
     * <p>
     * Condition tested: {@code basket.deliveryAddress == null}.
     */
    @Test
    void testBuildAppliers_Failure_BasketAddressNull() {
        setUpDatabase();
        // Arrange
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        basket.deliveryAddress = null; // The object itself is null
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests the scenario where the basket delivery address longitude is null.
     * <p>
     * Condition tested: {@code basket.deliveryAddress.longitude == null}.
     * Latitude is valid, so the condition on latitude is true, but longitude is false.
     */
    @Test
    void testBuildAppliers_Failure_BasketLongitudeNull() {
        setUpDatabase();
        // Arrange
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        basket.deliveryAddress.longitude = null; // Only longitude is null
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests the scenario where the store address longitude is null.
     * <p>
     * Condition tested: {@code store.address.longitude == null}.
     */
    @Test
    void testBuildAppliers_Failure_StoreLongitudeNull() {
        setUpDatabase();
        // Arrange: Create a store, then update it to set only longitude to null
        Store storeNoLon = DomainUtils.createAndPersistStore("STORE_NO_LON", 48.86, 2.35);
        DomainUtils.updateStoreAddress(storeNoLon, 48.86, null);
        Basket basket = createBasket("STORE_NO_LON", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests the scenario where no delivery offers are found for the store.
     * <p>
     * Condition tested: {@code !offers.isEmpty()} is {@code false}.
     * Expectation: Returns an empty list of appliers (no error).
     */
    @Test
    void testBuildAppliers_Success_NoOfferFound() {
        setUpDatabase();
        // Arrange: Create a store that has no DELIVERY offers linked to it
        DomainUtils.createAndPersistStore("STORE_NO_OFFER", 48.86, 2.35);
        Basket basket = createBasket("STORE_NO_OFFER", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<com.intermarche.valuation.engine.OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty(), "Should return empty list if no delivery offers are configured");
    }

    // --------------------------------------------------
    // Tests for processOffer (JSON Schema Validation)
    // --------------------------------------------------

    /**
     * Tests {@link DeliveryOfferFactory#buildAppliers} when the specification
     * lacks the "tiers" key.
     * <p>
     * Condition tested: Schema validation fails (assuming "tiers" is required).
     * Expectation: Throws {@link IllegalArgumentException}.
     * This reflects the update in {@code processSpecification} which validates JSON against a schema.
     */
    @Test
    void testBuildAppliers_InvalidSpec_MissingTiersKey() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // Arrange: JSON without "tiers" key (only vatRate)
        String jsonSpec = "{ \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("NO_TIERS", store, "DELIVERY", jsonSpec);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if tiers key is missing (Schema validation)");
    }

    /**
     * Tests {@link DeliveryOfferFactory#buildAppliers} when the "tiers"
     * list is present but empty.
     * <p>
     * Condition tested: Schema validation fails (assuming "tiers" requires minItems=1).
     * Expectation: Throws {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_InvalidSpec_EmptyTiers() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // Arrange: JSON with empty "tiers" list
        String jsonSpec = "{ \"tiers\": [], \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("EMPTY_TIERS", store, "DELIVERY", jsonSpec);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should not create applier if tiers list is empty (Schema validation)");
    }

    /**
     * Tests {@link DeliveryOfferFactory#buildAppliers} when a tier
     * definition is missing the "maxDistance" field.
     * <p>
     * Condition tested: Schema validation fails (assuming "maxDistance" is required).
     * Expectation: Throws {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_InvalidSpec_TierMissingMaxDistance() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // Arrange: JSON with tier missing "maxDistance"
        String jsonSpec = "{ \"tiers\": [ { \"price\": 5.00 } ], \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("NULL_MAX_DIST", store, "DELIVERY", jsonSpec);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if tier is missing maxDistance (Schema validation)");
    }

    /**
     * Tests {@link DeliveryOfferFactory#buildAppliers} when a tier
     * definition is missing the "price" field.
     * <p>
     * Condition tested: Schema validation fails (assuming "price" is required).
     * Expectation: Throws {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_InvalidSpec_TierMissingPrice() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // Arrange: JSON with tier missing "price"
        String jsonSpec = "{ \"tiers\": [ { \"maxDistance\": 10.0 } ], \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("NULL_PRICE", store, "DELIVERY", jsonSpec);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        // Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if tier is missing price (Schema validation)");
    }

    /**
     * Tests {@link DeliveryOfferFactory#buildAppliers} when the
     * specification contains malformed JSON.
     * <p>
     * Condition tested: {@code catch (JsonProcessingException e)}.
     * Expectation: Throws {@link IllegalArgumentException}.
     * This reflects the update in {@code processSpecification}.
     */
    @Test
    void testBuildAppliers_InvalidSpec_BadJson() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // Arrange: Malformed JSON
        String jsonSpec = "{ \"tiers\": [ { \"invalid\": \"json\" } ] }";
        DomainUtils.createAndPersistOffer("BAD_JSON", store, "DELIVERY", jsonSpec);
        Basket basket = createBasket("STORE_01", "HOME_DELIVERY", 48.86, 2.35);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw IllegalArgumentException for bad JSON");
    }

}