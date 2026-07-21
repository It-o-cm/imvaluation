package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DepositBasketOfferFactory} using the real database.
 * <p>
 * This class tests the logic for deposit basket offers, including instruction validation,
 * volume calculation, and basket counting.
 * <p>
 * Updated to reflect that {@code processSpecification} now validates against a JSON Schema
 * and throws {@link IllegalArgumentException} on non-conformity.
 */
@QuarkusTest
@TestTransaction
public class DepositBasketOfferFactoryTest {

    @Inject
    DepositBasketOfferFactory factory;

    private Store store;
    private Product productWithVolume; // e.g., liquid product
    private Product productNoVolume; // e.g., loose item

    /**
     * Sets up the database with a Store and Products before each test.
     * <p>
     * This method must be called manually within test methods as it is not annotated with {@link org.junit.jupiter.api.BeforeEach}.
     */
    void setUpDatabase() {
        // 1. Create Store
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
        // 2. Create Products
        // Product A: Has volume (5.0 Liters)
        productWithVolume = DomainUtils.createAndPersistProduct("1111111111111", "Product A", ProductType.UNIT);
        // Product B: No volume (refVolume is null)
        productNoVolume = DomainUtils.createAndPersistProduct("2222222222222", "Product B", ProductType.UNIT);
    }

    // --------------------------------------------------
    // Utility Methods
    // --------------------------------------------------

    /**
     * Helper method to create a Basket DTO with specific instructions and items.
     *
     * @param storeCode    The code of the store.
     * @param instructions The list of instruction strings.
     * @param items        The items to include in the basket.
     * @return A configured {@link Basket} object.
     */
    private Basket createBasket(String storeCode, List<String> instructions, Basket.Item... items) {
        Basket b = new Basket();
        b.storeCode = storeCode;
        b.instructions = instructions;
        b.items = Arrays.asList(items);
        return b;
    }

    // --------------------------------------------------
    // Tests for buildAppliers (Factory Logic)
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier.
     * <p>
     * Scenario:
     * <ul>
     *   <li>Basket has the "Deposit basket" instruction.</li>
     *   <li>A valid offer exists in the database.</li>
     *   <li>Product volume fits perfectly into the basket.</li>
     * </ul>
     * Expectation: One applier is created.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        // Arrange: Offer (Cap 10L, Price 5€, VAT 20%)
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        // Basket: 1 item (5L)
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0)); // 5L
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertEquals(1, appliers.size());
    }

    /**
     * Tests the scenario where the basket lacks the required instruction.
     * <p>
     * Expectation: Returns an empty list.
     */
    @Test
    void testBuildAppliers_Failure_MissingInstruction() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Pickup"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests the scenario where the instructions list is null.
     * <p>
     * Expectation: Returns an empty list.
     */
    @Test
    void testBuildAppliers_Failure_NullInstructions() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.instructions = null;
        basket.items = List.of(createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests {@link DepositBasketOfferFactory#buildAppliers} when the offer
     * specification is missing the "basketVolume" field.
     * <p>
     * Condition tested: Schema validation fails or value is null.
     * Expectation: {@link IllegalArgumentException} is thrown (via processSpecification).
     */
    @Test
    void testBuildAppliers_Failure_MissingBasketVolume() {
        setUpDatabase(); // Ensure the store exists
        // Arrange: JSON without "basketVolume"
        String jsonSpec = "{ \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("NO_VOL", store, "DEPOSIT_BASKET", jsonSpec);
        // Arrange Basket
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if basketVolume is missing (Schema validation or Null check)");
    }

    /**
     * Tests {@link DepositBasketOfferFactory#buildAppliers} when the offer
     * specification is missing the "basketPrice" field.
     * <p>
     * Condition tested: Schema validation fails or value is null.
     * Expectation: {@link IllegalArgumentException} is thrown (via processSpecification).
     */
    @Test
    void testBuildAppliers_Failure_MissingBasketPrice() {
        setUpDatabase();
        // Arrange: JSON without "basketPrice"
        String jsonSpec = "{ \"basketVolume\": 10.0, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("NO_PRICE", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if basketPrice is missing (Schema validation or Null check)");
    }

    /**
     * Tests {@link DepositBasketOfferFactory#buildAppliers} when the offer
     * specification is missing the "vatRate" field.
     * <p>
     * Condition tested: Schema validation fails or value is null.
     * Expectation: {@link IllegalArgumentException} is thrown (via processSpecification).
     */
    @Test
    void testBuildAppliers_Failure_MissingVatRate() {
        setUpDatabase();
        // Arrange: JSON without "vatRate"
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00 }";
        DomainUtils.createAndPersistOffer("NO_VAT", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if vatRate is missing (Schema validation or Null check)");
    }

    /**
     * Tests the scenario where no offers are found for the store.
     * <p>
     * Expectation: Returns an empty list.
     */
    @Test
    void testBuildAppliers_Failure_NoOffersFound() {
        setUpDatabase();
        // No offer created for this store
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests the scenario where multiple offers are found for the store.
     * <p>
     * Expectation: Throws {@link IllegalStateException}.
     */
    @Test
    void testBuildAppliers_Failure_MultipleOffers() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        DomainUtils.createAndPersistOffer("DEPOSIT_02", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests the scenario where the offer specification is invalid (JSON parsing error or schema validation error).
     * <p>
     * Expectation: Throws {@link IllegalArgumentException}.
     * This change reflects the update in {@code processSpecification} which wraps
     * {@link JsonProcessingException} and schema validation errors in an {@link IllegalArgumentException}.
     */
    @Test
    void testBuildAppliers_Failure_InvalidJson() {
        setUpDatabase();
        // This JSON is structurally invalid for the offer schema (missing required fields)
        String badJson = "{ \"invalid\" : \"true\" }";
        DomainUtils.createAndPersistOffer("BAD_JSON_OFFER", store, "DEPOSIT_BASKET", badJson);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation));
    }

    // --------------------------------------------------
    // Tests for DepositBasketOfferApplier
    // --------------------------------------------------

    /**
     * Tests the application logic when 1 basket is needed.
     * <p>
     * Scenario: 1 item (5L). Basket Cap 10L.
     * Result: 1 Basket applied.
     */
    @Test
    void testApply_OneBasket() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0)); // 5L
        DomainUtils.setProductCharacteristics("1111111111111", BigDecimal.valueOf(5.0), BigDecimal.valueOf(5.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();

        // Act
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size());
        DepositBasketOfferFactory.DepositBasketApplication app =
                (DepositBasketOfferFactory.DepositBasketApplication) apps.iterator().next();
        // 5€ TTC / 1.2 = 4.17€ HT
        assertEquals(new BigDecimal("4.17"), app.getAmount().amountExcludingTax);
        assertEquals(new BigDecimal("5.00"), app.getAmount().amountIncludingTax);
    }

    /**
     * Tests the application logic when 2 baskets are needed.
     * <p>
     * Scenario: 2 items (Total 10L). Basket Cap 5L.
     * Result: 2 Baskets applied.
     */
    @Test
    void testApply_TwoBaskets() {
        setUpDatabase();
        DomainUtils.setProductCharacteristics("1111111111111", BigDecimal.valueOf(5.0), BigDecimal.valueOf(5.0));
        String jsonSpec = "{ \"basketVolume\": 5.0, \"basketPrice\": 2.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        Basket.Item item1 = createItem("1111111111111", 1.0); // 5L
        Basket.Item item2 = createItem("1111111111111", 1.0); // 5L
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"), item1, item2);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();

        // Act
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size());
        DepositBasketOfferFactory.DepositBasketApplication app =
                (DepositBasketOfferFactory.DepositBasketApplication) apps.iterator().next();
        // 2 * 2€ = 4€ TTC.
        assertEquals(new BigDecimal("4.00"), app.getAmount().amountIncludingTax);
    }

    /**
     * Tests the application logic when a product has no reference volume.
     * <p>
     * Scenario: Item with null referenceVolume.
     * Result: Total volume is 0L. No application created.
     */
    @Test
    void testApply_NoVolume() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("2222222222222", 1.0)); // No volume
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();

        // Act
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty());
    }

    // --------------------------------------------------
    // Tests for DepositBasketApplication
    // --------------------------------------------------

    /**
     * Tests {@link DepositBasketOfferFactory.DepositBasketApplication#getAmount()}.
     */
    @Test
    void testDepositBasketApplication_GetAmount() {
        // Arrange: Manually create application (3 baskets, 5€)
        DepositBasketOfferFactory.DepositBasketApplication app =
                new DepositBasketOfferFactory.DepositBasketApplication("OFFER_01", 3, BigDecimal.valueOf(5.00), BigDecimal.valueOf(0.2));

        // Act
        AmountEvaluation amount = app.getAmount();

        // Assert
        // 3 * 5€ = 15€ TTC. 15 / 1.2 = 12.50€ HT
        assertEquals(new BigDecimal("12.50"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("15.00"), amount.amountIncludingTax);
    }

    /**
     * Tests {@link DepositBasketOfferFactory.DepositBasketApplication#getItems()}.
     */
    @Test
    void testDepositBasketApplication_GetItems() {
        DepositBasketOfferFactory.DepositBasketApplication app =
                new DepositBasketOfferFactory.DepositBasketApplication("OFFER_01", 1, BigDecimal.ONE, BigDecimal.ONE);

        // Act
        Collection<Basket.Item> items = app.getItems();

        // Assert
        assertTrue(items.isEmpty());
    }

    /**
     * Tests {@link DepositBasketOfferFactory.DepositBasketApplication#getType()}.
     */
    @Test
    void testDepositBasketApplication_GetType() {
        DepositBasketOfferFactory.DepositBasketApplication app =
                new DepositBasketOfferFactory.DepositBasketApplication("OFFER_01", 2, BigDecimal.TEN, BigDecimal.valueOf(0.2));

        // Act
        String type = app.getType();

        // Assert
        assertTrue(type.contains("Deposit Basket"));
        assertTrue(type.contains("2"));
        assertTrue(type.contains("10"));
    }

    /**
     * Tests {@link DepositBasketOfferFactory.DepositBasketOfferApplier#computeEfficiencyScore}.
     */
    @Test
    void testDepositBasketOfferApplier_ComputeEfficiencyScore() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("2222222222222", 1.0)); // No volume
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();

        // Act
        double score = applier.computeEfficiencyScore(basket);

        // Assert
        assertEquals(0.0, score);
    }

    // --------------------------------------------------
    // Tests for processOffer (JSON Field Validation)
    // --------------------------------------------------

    /**
     * Tests {@link DepositBasketOfferFactory#buildAppliers} when the offer
     * specification lacks the "basketVolume" key.
     * <p>
     * Condition tested: {@code basketVolumeNum != null} is {@code false} (due to missing key).
     * Expectation: {@link IllegalArgumentException} is thrown (Schema validation failure).
     */
    @Test
    void testBuildAppliers_Failure_MissingBasketVolumeKey() {
        setUpDatabase();
        // Arrange: Create a new offer with bad spec
        String jsonSpec = "{ \"basketPrice\": 5.00, \"vatRate\": 0.20 }"; // Missing basketVolume
        DomainUtils.createAndPersistOffer("NO_VOL_KEY", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if basketVolume key is missing (Schema validation)");
    }

    /**
     * Tests {@link DepositBasketOfferFactory#buildAppliers} when the offer
     * specification lacks the "basketPrice" key.
     * <p>
     * Condition tested: {@code basketPriceNum != null} is {@code false} (due to missing key).
     * Expectation: {@link IllegalArgumentException} is thrown (Schema validation failure).
     */
    @Test
    void testBuildAppliers_Failure_MissingBasketPriceKey() {
        setUpDatabase();
        // Arrange: Create a new offer with bad spec
        String jsonSpec = "{ \"basketVolume\": 10.0, \"vatRate\": 0.20 }"; // Missing basketPrice
        DomainUtils.createAndPersistOffer("NO_PRICE_KEY", store, "DEPOSIT_BASKET", jsonSpec);
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildAppliers(evaluation),
                "Should throw if basketPrice key is missing (Schema validation)");
    }

    /**
     * Tests {@link DepositBasketOfferFactory.DepositBasketOfferApplier#apply} when the basket items list is null.
     * <p>
     * Condition tested: {@code basket.items != null} is {@code false}.
     * Expectation: Returns empty list (no application created).
     */
    @Test
    void testApply_NullItems() {
        setUpDatabase();
        String jsonSpec = "{ \"basketVolume\": 10.0, \"basketPrice\": 5.00, \"vatRate\": 0.20 }";
        DomainUtils.createAndPersistOffer("DEPOSIT_01", store, "DEPOSIT_BASKET", jsonSpec);
        // Arrange: Create basket, then set items to null
        Basket basket = createBasket("STORE_01", Arrays.asList("Deposit basket"),
                createItem("1111111111111", 1.0));
        basket.items = null; // Set items to null manually
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();

        // Act
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should return empty list if basket items is null");
    }
}