package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class for {@link ValuationEngine}.
 * <p>
 * Tests the orchestration of basket evaluation, factory delegation,
 * price calculation, and applier sorting logic.
 */
@ExtendWith(MockitoExtension.class)
public class ValuationEngineTest {

    @Mock
    private Instance<AdvantageApplierFactory> discountFactories;

    @Mock
    private Instance<OfferApplierFactory> offerFactories;

    @Mock
    private AdvantageApplierFactory advantageFactory;

    @Mock
    private OfferApplierFactory offerFactory;

    @Mock
    private AdvantageApplier discountApplier;

    @Mock
    private OfferApplier offerApplier;

    @Mock
    private OfferApplication offerApp;

    @Mock
    private DiscountApplication discountApp;

    @Mock
    private BasketEvaluation evaluationContext;

    // MockedStatic for Store and StoreGroup (used by BasketEvaluation constructor)
    private MockedStatic<Store> mockedStore;
    private MockedStatic<StoreGroup> mockedStoreGroup;

    @InjectMocks
    private ValuationEngine engine;

    @BeforeEach
    void setUp() {
        mockedStore = mockStatic(Store.class);
        mockedStoreGroup = mockStatic(StoreGroup.class);
    }

    @AfterEach
    void tearDown() {
        mockedStore.close();
        mockedStoreGroup.close();
    }

    // --------------------------------------------------
    // Tests for evaluate() (Orchestration)
    // --------------------------------------------------

    /**
     * Tests the main {@link ValuationEngine#evaluate(Basket)} flow.
     * <p>
     * Verifies that factories are called to create appliers, discounts are applied,
     * and the final price is correctly calculated (Offers - Discounts).
     */
    @Test
    void testEvaluate_SuccessFlow() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";

        // Setup static dependencies for BasketEvaluation construction
        Store store = new Store();
        store.id = 1L;
        mockedStore.when(() -> Store.findByCode("STORE_01")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // Setup Instance mocks (Iterables)
        Iterator<OfferApplierFactory> offerIterator = Collections.singletonList(offerFactory).iterator();
        when(offerFactories.iterator()).thenReturn(offerIterator);

        Iterator<AdvantageApplierFactory> discountIterator = Collections.singletonList(advantageFactory).iterator();
        when(discountFactories.iterator()).thenReturn(discountIterator);

        // Setup Factory behavior
        when(offerFactory.buildAppliers(any())).thenReturn(List.of(offerApplier));
        when(advantageFactory.buildAppliers(any())).thenReturn(List.of(discountApplier));

        // Setup Applier behavior
        when(discountApplier.isApplicable(offerApplier)).thenReturn(true);
        when(offerApplier.computeEfficiencyScore(any())).thenReturn(0.5); // Efficiency score

        // Setup Application results
        // Offer: 120.00 TTC
        AmountEvaluation offerAmount = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"));
        when(offerApplier.apply(any())).thenReturn(List.of(offerApp));
        when(offerApp.getAmount()).thenReturn(offerAmount);

        // Discount: 12.00 TTC
        AmountEvaluation discountAmount = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));
        when(discountApplier.apply(any())).thenReturn(List.of(discountApp));
        when(discountApp.getDiscountAmount()).thenReturn(discountAmount);

        // Act
        BasketEvaluation result = engine.evaluate(basket);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getTotalPrice());

        // 120 (Offer) - 12 (Discount) = 108
        assertEquals(new BigDecimal("108.00"), result.getTotalPrice().amountIncludingTax);
        assertEquals(new BigDecimal("90.00"), result.getTotalPrice().amountExcludingTax);
    }

    /**
     * Tests {@link ValuationEngine#evaluate(Basket)} when no factories are available.
     * <p>
     * Verifies that the evaluation completes without crashing, though resulting in zero total price
     * if the basket itself is empty or no applications generated.
     */
    @Test
    void testEvaluate_NoFactories() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_02";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_02")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        when(offerFactories.iterator()).thenReturn(Collections.emptyListIterator());
        when(discountFactories.iterator()).thenReturn(Collections.emptyListIterator());

        BasketEvaluation result = engine.evaluate(basket);

        assertNotNull(result);
        // Since no offers were added, and basket items were not populated in this test scenario,
        // check calculation doesn't crash. Result depends on internal default of AmountEvaluation (0.0).
        // If basket had items but no offers matching, logic handles it.
        // Here we just verify execution.
    }

    // --------------------------------------------------
    // Tests for Utility Methods
    // --------------------------------------------------

    /**
     * Tests {@link ValuationEngine#calculateTotalHorsDiscount(BasketEvaluation)}.
     * <p>
     * Verifies summation of offer amounts including tax.
     */
    @Test
    void testCalculateTotalHorsDiscount() {
        // Arrange
        AmountEvaluation amt1 = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), BigDecimal.ZERO);
        AmountEvaluation amt2 = new AmountEvaluation(new BigDecimal("20.00"), new BigDecimal("24.00"), BigDecimal.ZERO);

        when(offerApp.getAmount()).thenReturn(amt1);
        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp, offerApp)); // Reuse mock for simplicity

        // Act
        BigDecimal total = engine.calculateTotalHorsDiscount(evaluationContext);

        // Assert
        assertEquals(new BigDecimal("24.00"), total); // 12 + 12
    }

    /**
     * Tests {@link ValuationEngine#calculateTotalDiscount(BasketEvaluation)}.
     * <p>
     * Verifies summation of discount amounts including tax.
     */
    @Test
    void testCalculateTotalDiscount() {
        // Arrange
        AmountEvaluation discountAmt = new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), BigDecimal.ZERO);
        when(discountApp.getDiscountAmount()).thenReturn(discountAmt);
        when(evaluationContext.getAdvantages()).thenReturn(List.of(discountApp));

        // Act
        BigDecimal total = engine.calculateTotalDiscount(evaluationContext);

        // Assert
        assertEquals(new BigDecimal("6.00"), total);
    }

    /**
     * Tests {@link ValuationEngine#calculateRealTotal(BasketEvaluation)}.
     * <p>
     * Verifies calculation: Total Hors Discount - Total Discount.
     */
    @Test
    void testCalculateRealTotal() {
        // Arrange
        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp));
        when(evaluationContext.getAdvantages()).thenReturn(List.of(discountApp));

        AmountEvaluation offerAmt = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), BigDecimal.ZERO);
        when(offerApp.getAmount()).thenReturn(offerAmt);

        AmountEvaluation discountAmt = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), BigDecimal.ZERO);
        when(discountApp.getDiscountAmount()).thenReturn(discountAmt);

        // Act
        BigDecimal realTotal = engine.calculateRealTotal(evaluationContext);

        // Assert
        assertEquals(new BigDecimal("108.00"), realTotal); // 120 - 12
    }

    /**
     * Tests {@link ValuationEngine#calculateRealTotal(BasketEvaluation)} when an advantage is not a discount.
     * <p>
     * Verifies that non-discount advantages are ignored in the total calculation.
     */
    @Test
    void testCalculateRealTotal_IgnoresNonDiscountAdvantages() {
        // Arrange
        AdvantageApplication nonDiscount = mock(AdvantageApplication.class);

        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp));
        // Mix of discount and non-discount
        when(evaluationContext.getAdvantages()).thenReturn(Arrays.asList(discountApp, nonDiscount));

        AmountEvaluation offerAmt = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), BigDecimal.ZERO);
        when(offerApp.getAmount()).thenReturn(offerAmt);

        AmountEvaluation discountAmt = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), BigDecimal.ZERO);
        when(discountApp.getDiscountAmount()).thenReturn(discountAmt);

        // Act
        BigDecimal realTotal = engine.calculateRealTotal(evaluationContext);

        // Assert
        assertEquals(new BigDecimal("108.00"), realTotal); // 120 - 12
    }

    // --------------------------------------------------
    // Tests for Inner Evaluators
    // --------------------------------------------------

    /**
     * Tests {@link ValuationEngine.OfferApplierEvaluator#sort(List, BasketEvaluation)}.
     * <p>
     * Verifies that appliers are sorted by efficiency score in descending order.
     */
    @Test
    void testOfferApplierEvaluator_Sort() {
        // Arrange
        OfferApplier lowScore = mock(OfferApplier.class);
        when(lowScore.getEfficiencyScore()).thenReturn(0.1);

        OfferApplier highScore = mock(OfferApplier.class);
        when(highScore.getEfficiencyScore()).thenReturn(0.9);

        OfferApplier midScore = mock(OfferApplier.class);
        when(midScore.getEfficiencyScore()).thenReturn(0.5);

        List<OfferApplier> appliers = Arrays.asList(lowScore, midScore, highScore);

        ValuationEngine.OfferApplierEvaluator evaluator = new ValuationEngine.OfferApplierEvaluator();

        // Act
        evaluator.sort(appliers, evaluationContext);

        // Assert
        assertEquals(highScore, appliers.get(0));
        assertEquals(midScore, appliers.get(1));
        assertEquals(lowScore, appliers.get(2));
    }

    /**
     * Tests {@link ValuationEngine.DiscountApplierEvaluator#sort(List)}.
     * <p>
     * Verifies that appliers are sorted by efficiency score in descending order.
     */
    @Test
    void testDiscountApplierEvaluator_Sort() {
        // Arrange
        AdvantageApplier applier1 = mock(AdvantageApplier.class);
        when(applier1.getEfficiencyScore()).thenReturn(0.1);

        AdvantageApplier applier2 = mock(AdvantageApplier.class);
        when(applier2.getEfficiencyScore()).thenReturn(1.0);

        List<AdvantageApplier> appliers = Arrays.asList(applier1, applier2);

        ValuationEngine.DiscountApplierEvaluator evaluator = new ValuationEngine.DiscountApplierEvaluator();

        // Act
        evaluator.sort(appliers);

        // Assert
        assertEquals(applier2, appliers.get(0)); // Higher score first
        assertEquals(applier1, appliers.get(1));
    }

    // --------------------------------------------------
    // Tests for calculateAmountEvaluation (Private Method via Reflection)
    // --------------------------------------------------

    /**
     * Helper method to invoke the private static method {@code calculateAmountEvaluation}
     * using Java Reflection.
     *
     * @param evaluation The basket evaluation context.
     * @return The calculated amount evaluation.
     * @throws Exception If reflection fails.
     */
    private AmountEvaluation invokeCalculateAmountEvaluation(BasketEvaluation evaluation) throws Exception {
        java.lang.reflect.Method method = ValuationEngine.class.getDeclaredMethod("calculateAmountEvaluation", BasketEvaluation.class);
        method.setAccessible(true);
        return (AmountEvaluation) method.invoke(null, evaluation);
    }

    /**
     * Tests the branch {@code if (evaluation.getOffers() != null)} is false.
     * <p>
     * Verifies that when {@link BasketEvaluation#getOffers()} returns null,
     * the method does not attempt to iterate and starts from zero.
     */
    @Test
    void testCalculateAmountEvaluation_OffersListIsNull() throws Exception {
        // Arrange
        when(evaluationContext.getOffers()).thenReturn(null);
        when(evaluationContext.getAdvantages()).thenReturn(Collections.emptyList());

        // Act
        AmountEvaluation result = invokeCalculateAmountEvaluation(evaluationContext);

        // Assert
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), result.amountExcludingTax);
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), result.amountIncludingTax);
    }

    /**
     * Tests the branch {@code if (price != null)} is false inside the Offers loop.
     * <p>
     * Verifies that if an {@link OfferApplication} exists but returns a null AmountEvaluation,
     * the calculation safely skips it without throwing a NullPointerException.
     */
    @Test
    void testCalculateAmountEvaluation_OfferAmountIsNull() throws Exception {
        // Arrange
        // Create a mock application that returns null for its amount
        when(offerApp.getAmount()).thenReturn(null);
        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp));
        when(evaluationContext.getAdvantages()).thenReturn(Collections.emptyList());

        // Act
        AmountEvaluation result = invokeCalculateAmountEvaluation(evaluationContext);

        // Assert
        // Total should be zero as the offer contributed nothing
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), result.amountExcludingTax);
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), result.amountIncludingTax);
    }

    /**
     * Tests the branch {@code if (evaluation.getAdvantages() != null)} is false.
     * <p>
     * Verifies that when {@link BasketEvaluation#getAdvantages()} returns null,
     * the method skips the discount subtraction loop entirely.
     * <p>
     * Scenario: An offer generates a total of 100.00, but the advantages list is null.
     * Expected: Total remains 100.00.
     */
    @Test
    void testCalculateAmountEvaluation_AdvantagesListIsNull() throws Exception {
        // Arrange
        AmountEvaluation offerAmt = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), BigDecimal.ZERO);
        when(offerApp.getAmount()).thenReturn(offerAmt);

        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp));
        when(evaluationContext.getAdvantages()).thenReturn(null); // Null list

        // Act
        AmountEvaluation result = invokeCalculateAmountEvaluation(evaluationContext);

        // Assert
        // Total should be the offer amount (120), as discounts were not processed
        assertEquals(new BigDecimal("100.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("120.00"), result.amountIncludingTax);
    }

    /**
     * Tests the branch {@code if (price != null)} is false inside the Advantages loop.
     * <p>
     * Verifies that if a {@link DiscountApplication} is present but returns a null discount amount,
     * the subtraction logic is skipped safely.
     * <p>
     * Scenario: Offer total is 100.00. A discount application exists but returns null.
     * Expected: Total remains 100.00 (no subtraction occurred).
     */
    @Test
    void testCalculateAmountEvaluation_DiscountAmountIsNull() throws Exception {
        // Arrange
        AmountEvaluation offerAmt = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), BigDecimal.ZERO);
        when(offerApp.getAmount()).thenReturn(offerAmt);

        // Discount app returns null for the amount
        when(discountApp.getDiscountAmount()).thenReturn(null);

        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp));
        when(evaluationContext.getAdvantages()).thenReturn(List.of(discountApp));

        // Act
        AmountEvaluation result = invokeCalculateAmountEvaluation(evaluationContext);

        // Assert
        // Total should be 120, because the discount was null and thus not subtracted
        assertEquals(new BigDecimal("100.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("120.00"), result.amountIncludingTax);
    }

    // --------------------------------------------------
    // Tests for Missing Branches & Edge Cases
    // --------------------------------------------------

    /**
     * Tests the branch {@code if (offerFactories != null)} is false in {@code createOfferAppliers}.
     * <p>
     * Uses reflection to set the {@code offerFactories} field to null.
     * Also ensures {@code discountFactories} returns an empty list to prevent a NPE
     * in the previous step of the evaluation flow.
     */
    @Test
    void testEvaluate_OfferFactoriesIsNull() throws Exception {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_NULL_FACT";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_NULL_FACT")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // FIX: Ensure discount factories are safely empty so they don't crash before we test offerFactories
        when(discountFactories.iterator()).thenReturn(Collections.emptyListIterator());

        // Use reflection to force the field to null to test the condition
        java.lang.reflect.Field field = ValuationEngine.class.getDeclaredField("offerFactories");
        field.setAccessible(true);
        field.set(engine, null);

        // Act
        BasketEvaluation result = engine.evaluate(basket);

        // Assert
        assertNotNull(result);
        // Since factories were null, no offers could be created.
        // The main goal is to ensure no NullPointerException was thrown during the execution flow.
    }

    /**
     * Tests the branch {@code if (discountApplier.isApplicable(applier))} is false.
     * <p>
     * Verifies that if a discount applier determines it is not applicable to an offer applier,
     * it is not registered to that offer applier.
     */
    @Test
    void testCreateOfferAppliers_DiscountNotApplicable() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_NOT_APPLICABLE";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_NOT_APPLICABLE")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // Setup Factories
        Iterator<OfferApplierFactory> offerIterator = Collections.singletonList(offerFactory).iterator();
        when(offerFactories.iterator()).thenReturn(offerIterator);

        Iterator<AdvantageApplierFactory> discountIterator = Collections.singletonList(advantageFactory).iterator();
        when(discountFactories.iterator()).thenReturn(discountIterator);

        // Setup Appliers
        when(offerFactory.buildAppliers(any())).thenReturn(List.of(offerApplier));
        when(advantageFactory.buildAppliers(any())).thenReturn(List.of(discountApplier));

        // KEY: isApplicable returns FALSE
        when(discountApplier.isApplicable(offerApplier)).thenReturn(false);
        when(offerApplier.computeEfficiencyScore(any())).thenReturn(0.5);

        when(offerApplier.apply(any())).thenReturn(Collections.emptyList());

        // Act
        engine.evaluate(basket);

        // Assert
        // Verify that registerDiscountApplier was NEVER called because isApplicable was false
        verify(offerApplier, never()).registerDiscountApplier(discountApplier);
    }

    /**
     * Tests the {@code catch (Exception e)} block in {@code createOfferAppliers}.
     * <p>
     * Simulates a factory throwing an exception during the build process.
     * The code re-throws the exception wrapped in a RuntimeException.
     * <p>
     * Note: We ensure discountFactories returns an empty list to avoid NPE in the previous step.
     */
    @Test
    void testEvaluate_FactoryThrowsException() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_EXCEPTION";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_EXCEPTION")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // FIX: Ensure discount factories don't cause a NPE before reaching the offer factory
        Iterator<AdvantageApplierFactory> discountIterator = Collections.emptyListIterator();
        when(discountFactories.iterator()).thenReturn(discountIterator);

        // Setup Offer Factory to throw exception
        Iterator<OfferApplierFactory> offerIterator = Collections.singletonList(offerFactory).iterator();
        when(offerFactories.iterator()).thenReturn(offerIterator);
        when(offerFactory.buildAppliers(any())).thenThrow(new RuntimeException("Factory Failure"));

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            engine.evaluate(basket);
        });

        assertTrue(ex.getMessage().contains("Error building appliers from factory"));
        assertTrue(ex.getMessage().contains("Factory Failure"));
    }

    /**
     * Tests the branch {@code if (app instanceof DiscountApplication)} is false
     * inside the private method {@code calculateAmountEvaluation}.
     * <p>
     * Verifies that a generic {@link AdvantageApplication} (which is not a discount)
     * is present in the list but does not trigger a subtraction logic, nor causes a ClassCastException.
     */
    @Test
    void testCalculateAmountEvaluation_InstanceOfDiscountIsFalse() throws Exception {
        // Arrange
        // Create a mock that implements AdvantageApplication but is NOT a DiscountApplication
        AdvantageApplication genericAdvantage = mock(AdvantageApplication.class);

        // Create a valid offer to ensure the total isn't just zero
        AmountEvaluation offerAmt = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), BigDecimal.ZERO);
        when(offerApp.getAmount()).thenReturn(offerAmt);

        // Configure evaluation context
        // Returns a list containing the generic advantage
        when(evaluationContext.getAdvantages()).thenReturn(List.of(genericAdvantage));
        when(evaluationContext.getOffers()).thenReturn(List.of(offerApp));

        // Act
        AmountEvaluation result = invokeCalculateAmountEvaluation(evaluationContext);

        // Assert
        // The result should be exactly the offer amount (120.00), because the generic advantage was ignored.
        assertEquals(new BigDecimal("100.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("120.00"), result.amountIncludingTax);
    }

    /**
     * Tests the branch {@code if (builtAppliers != null)} is false in {@code createOfferAppliers}.
     * <p>
     * Verifies that when a factory returns {@code null} instead of a collection,
     * the engine safely skips processing that factory's result without throwing a NullPointerException.
     */
    @Test
    void testEvaluate_OfferFactoryReturnsNull() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_NULL_OFFERS";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_NULL_OFFERS")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // Setup Discount Factories as empty to isolate the Offer Factory logic
        Iterator<AdvantageApplierFactory> discountIterator = Collections.emptyListIterator();
        when(discountFactories.iterator()).thenReturn(discountIterator);

        // Setup Offer Factory to return null (Simulating the false branch)
        Iterator<OfferApplierFactory> offerIterator = Collections.singletonList(offerFactory).iterator();
        when(offerFactories.iterator()).thenReturn(offerIterator);
        when(offerFactory.buildAppliers(any())).thenReturn(null);

        // Act
        BasketEvaluation result = engine.evaluate(basket);

        // Assert
        assertNotNull(result);
        // Offers list should be empty because the factory returned null and logic was skipped
        assertTrue(result.getOffers().isEmpty());
    }

    /**
     * Tests the branch {@code if (builtAppliers != null)} is false in {@code createDiscountAppliers}.
     * <p>
     * Verifies that when a discount factory returns {@code null},
     * the engine safely skips processing that factory's result.
     */
    @Test
    void testEvaluate_DiscountFactoryReturnsNull() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_NULL_DISCOUNTS";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_NULL_DISCOUNTS")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // Setup Offer Factories as empty to isolate the Discount Factory logic
        Iterator<OfferApplierFactory> offerIterator = Collections.emptyListIterator();
        when(offerFactories.iterator()).thenReturn(offerIterator);

        // Setup Discount Factory to return null (Simulating the false branch)
        Iterator<AdvantageApplierFactory> discountIterator = Collections.singletonList(advantageFactory).iterator();
        when(discountFactories.iterator()).thenReturn(discountIterator);
        when(advantageFactory.buildAppliers(any())).thenReturn(null);

        // Act
        BasketEvaluation result = engine.evaluate(basket);

        // Assert
        assertNotNull(result);
        // Advantages list should be empty
        assertTrue(result.getAdvantages().isEmpty());
    }

    // --------------------------------------------------
    // Tests for createOfferApplications (Public Method)
    // --------------------------------------------------

    /**
     * Tests the normal flow of {@link ValuationEngine#createOfferApplications(List, BasketEvaluation)}.
     * <p>
     * Verifies that appliers are sorted and applied.
     * Uses a Spy on a concrete implementation to handle the {@code final} getEfficiencyScore method
     * and the abstract {@code apply} method.
     * Uses a mutable ArrayList to allow in-place sorting.
     */
    @Test
    void testCreateOfferApplications_Success() {
        // Arrange
        // Create a concrete instance to handle the abstract class and final method issues
        OfferApplier realApplier = new OfferApplier() {
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return Collections.emptyList();
            }
        };
        realApplier.setEfficiencyScore(1.0); // Set score for sorting logic

        // Spy the real instance to control the return value of apply()
        OfferApplier applier = spy(realApplier);

        // Create a MUTABLE list (ArrayList) instead of List.of() which is immutable
        List<OfferApplier> appliers = new ArrayList<>();
        appliers.add(applier);

        Collection<OfferApplication> apps = List.of(offerApp);
        doReturn(apps).when(applier).apply(any());

        BasketEvaluation eval = mock(BasketEvaluation.class);
        Set<OfferApplication> offerSet = new HashSet<>();
        when(eval.getOffers()).thenReturn(offerSet);

        // Act
        engine.createOfferApplications(appliers, eval);

        // Assert
        assertEquals(1, offerSet.size());
        assertTrue(offerSet.contains(offerApp));
    }

    /**
     * Tests the branch {@code if (applications != null)} is false.
     * <p>
     * Verifies that if an applier returns a null collection, the engine
     * does not attempt to add it to the evaluation context.
     */
    @Test
    void testCreateOfferApplications_ApplicationsResultIsNull() {
        // Arrange
        OfferApplier realApplier = new OfferApplier() {
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return Collections.emptyList();
            }
        };
        realApplier.setEfficiencyScore(1.0);

        OfferApplier applier = spy(realApplier);
        List<OfferApplier> appliers = new ArrayList<>();
        appliers.add(applier);

        // The applier returns null
        doReturn(null).when(applier).apply(any());

        BasketEvaluation eval = mock(BasketEvaluation.class);
        Set<OfferApplication> offerSet = new HashSet<>();
        lenient().when(eval.getOffers()).thenReturn(offerSet);

        // Act
        engine.createOfferApplications(appliers, eval);

        // Assert
        // The set remains empty because the result was null
        assertTrue(offerSet.isEmpty());
    }

    /**
     * Tests the {@code catch (Exception e)} block.
     * <p>
     * Verifies that an exception thrown by an applier is caught and re-thrown.
     */
    @Test
    void testCreateOfferApplications_ApplierThrowsException() {
        // Arrange
        OfferApplier realApplier = new OfferApplier() {
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return Collections.emptyList();
            }
        };
        realApplier.setEfficiencyScore(1.0);

        OfferApplier applier = spy(realApplier);
        List<OfferApplier> appliers = new ArrayList<>();
        appliers.add(applier);

        // The applier throws an exception
        doThrow(new IllegalStateException("Applier crashed")).when(applier).apply(any());

        BasketEvaluation eval = mock(BasketEvaluation.class);

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            engine.createOfferApplications(appliers, eval);
        });

        assertTrue(ex.getMessage().contains("Error applying offer logic"));
        assertTrue(ex.getMessage().contains("Applier crashed"));
    }

    // --------------------------------------------------
    // Tests for createDiscountApplications (Public Method)
    // --------------------------------------------------

    /**
     * Tests the normal flow.
     * Uses a direct Mock to avoid compilation errors if AdvantageApplier is an Interface
     * or has complex abstract methods.
     */
    @Test
    void testCreateDiscountApplications_Success() {
        // Arrange
        // On crée un mock direct de l'interface/classe
        AdvantageApplier applier = mock(AdvantageApplier.class);

        // On crée une liste mutable (pour le tri interne) et on y ajoute le mock
        List<AdvantageApplier> appliers = new ArrayList<>();
        appliers.add(applier);

        // On crée le mock de l'application résultat (ex: advantageApp)
        AdvantageApplication advantageApp = mock(AdvantageApplication.class);
        Collection<AdvantageApplication> apps = List.of(advantageApp);

        // On stub l'appel à apply
        when(applier.apply(any())).thenReturn(apps);

        // Mock de l'évaluation
        BasketEvaluation eval = mock(BasketEvaluation.class);
        // On doit retourner une vraie collection mutable pour le .addAll()
        Collection<AdvantageApplication> advantageSet = new HashSet<>();
        when(eval.getAdvantages()).thenReturn(advantageSet);

        // Act
        engine.createDiscountApplications(appliers, eval);

        // Assert
        assertEquals(1, advantageSet.size());
        assertTrue(advantageSet.contains(advantageApp));
    }

    /**
     * Tests the branch {@code if (applications != null)} is false.
     */
    @Test
    void testCreateDiscountApplications_ApplicationsResultIsNull() {
        // Arrange
        AdvantageApplier applier = mock(AdvantageApplier.class);

        List<AdvantageApplier> appliers = new ArrayList<>();
        appliers.add(applier);

        // L'applier retourne null
        when(applier.apply(any())).thenReturn(null);

        BasketEvaluation eval = mock(BasketEvaluation.class);
        Collection<AdvantageApplication> advantageSet = new HashSet<>();
        lenient().when(eval.getAdvantages()).thenReturn(advantageSet);

        // Act
        engine.createDiscountApplications(appliers, eval);

        // Assert
        assertTrue(advantageSet.isEmpty());
    }

    /**
     * Tests the {@code catch (Exception e)} block.
     */
    @Test
    void testCreateDiscountApplications_ApplierThrowsException() {
        // Arrange
        AdvantageApplier applier = mock(AdvantageApplier.class);

        List<AdvantageApplier> appliers = new ArrayList<>();
        appliers.add(applier);

        // L'applier lance une exception
        doThrow(new IllegalStateException("Applier crashed")).when(applier).apply(any());

        BasketEvaluation eval = mock(BasketEvaluation.class);

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            engine.createDiscountApplications(appliers, eval);
        });

        assertTrue(ex.getMessage().contains("Error applying discount logic"));
        assertTrue(ex.getMessage().contains("Applier crashed"));
    }

    /**
     * Tests the {@code catch (Exception e)} block in {@code createDiscountAppliers}.
     * <p>
     * Simulates a discount factory throwing an exception during the build process.
     * The code re-throws the exception wrapped in a RuntimeException.
     */
    @Test
    void testEvaluate_DiscountFactoryThrowsException() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_DISC_EX";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_DISC_EX")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        // FIX: Ensure offer factories don't cause a NPE before reaching the discount factory
        lenient().when(offerFactories.iterator()).thenReturn(Collections.emptyListIterator());

        // Setup Discount Factory to throw exception
        Iterator<AdvantageApplierFactory> discountIterator = Collections.singletonList(advantageFactory).iterator();
        when(discountFactories.iterator()).thenReturn(discountIterator);
        when(advantageFactory.buildAppliers(any())).thenThrow(new RuntimeException("Discount Factory Failure"));

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            engine.evaluate(basket);
        });

        assertTrue(ex.getMessage().contains("Error building appliers from factory"));
        assertTrue(ex.getMessage().contains("Discount Factory Failure"));
    }

    /**
     * Tests the branch {@code if (discountFactories != null)} is false
     * inside {@link ValuationEngine#createDiscountAppliers(BasketEvaluation)}.
     * <p>
     * Uses reflection to invoke the private method directly after forcing
     * the {@code discountFactories} field to null.
     */
    @Test
    void testCreateDiscountAppliers_DiscountFactoriesIsNull() throws Exception {
        // Arrange
        BasketEvaluation basketEvaluation = mock(BasketEvaluation.class);

        // Use reflection to force the field to null to test the condition directly
        java.lang.reflect.Field field = ValuationEngine.class.getDeclaredField("discountFactories");
        field.setAccessible(true);
        field.set(engine, null);

        // Use reflection to invoke the private method
        java.lang.reflect.Method method = ValuationEngine.class.getDeclaredMethod("createDiscountAppliers", BasketEvaluation.class);
        method.setAccessible(true);

        // Act
        List<AdvantageApplier> result = (List<AdvantageApplier>) method.invoke(engine, basketEvaluation);

        // Assert
        // The method should return an empty list when the factories instance is null
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests the branch {@code if (app.getAmount() != null)} is false
     * inside {@link ValuationEngine#calculateTotalHorsDiscount(BasketEvaluation)}.
     * <p>
     * Verifies that if an {@link OfferApplication} returns a null amount,
     * it is safely ignored during the summation.
     */
    @Test
    void testCalculateTotalHorsDiscount_WithNullAmount() {
        // Arrange
        OfferApplication app1 = mock(OfferApplication.class);
        OfferApplication app2 = mock(OfferApplication.class);

        // Configure app1 to return a valid amount
        AmountEvaluation validAmount = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), BigDecimal.ZERO);
        when(app1.getAmount()).thenReturn(validAmount);

        // Configure app2 to return null (Triggering the false branch)
        when(app2.getAmount()).thenReturn(null);

        // Add both apps to the evaluation context
        when(evaluationContext.getOffers()).thenReturn(List.of(app1, app2));

        // Act
        BigDecimal total = engine.calculateTotalHorsDiscount(evaluationContext);

        // Assert
        // Total should only include the amount from app1 (12.00).
        // app2 contributed nothing but should not have caused a NullPointerException.
        assertEquals(new BigDecimal("12.00"), total);
    }

    /**
     * Tests the branch {@code if (discount.getDiscountAmount() != null)} is false
     * inside {@link ValuationEngine#calculateTotalDiscount(BasketEvaluation)}.
     * <p>
     * Verifies that if a {@link DiscountApplication} returns a null discount amount,
     * it is safely ignored during the summation.
     */
    @Test
    void testCalculateTotalDiscount_WithNullAmount() {
        // Arrange
        DiscountApplication validDiscount = mock(DiscountApplication.class);
        DiscountApplication nullDiscount = mock(DiscountApplication.class);

        // Configure validDiscount to return a valid amount
        AmountEvaluation validAmt = new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), BigDecimal.ZERO);
        when(validDiscount.getDiscountAmount()).thenReturn(validAmt);

        // Configure nullDiscount to return null (Triggering the false branch)
        when(nullDiscount.getDiscountAmount()).thenReturn(null);

        // Add both discounts to the evaluation context
        when(evaluationContext.getAdvantages()).thenReturn(List.of(validDiscount, nullDiscount));

        // Act
        BigDecimal total = engine.calculateTotalDiscount(evaluationContext);

        // Assert
        // Total should only include the amount from validDiscount (6.00).
        // nullDiscount contributed nothing but should not have caused a NullPointerException.
        assertEquals(new BigDecimal("6.00"), total);
    }
}
