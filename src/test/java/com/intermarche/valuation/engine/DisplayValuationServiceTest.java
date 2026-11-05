package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.engine.offers.SrpGuardFactory;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link DisplayValuationService}: request-level refusals (store, date, pushed
 * card cross-rules), the one-unit mini-basket mapping (basePrice/displayedPrice/immediateDiscount,
 * unit price of measure, floor guard) and the per-EAN error isolation. The engine is a Mockito mock
 * returning a crafted evaluation; every Panache finder is mocked statically — no database, no CDI.
 */
class DisplayValuationServiceTest {

    /**
     * The service under test, its injected engine replaced by a mock.
     */
    private DisplayValuationService service;

    /**
     * The mocked valuation engine.
     */
    private ValuationEngine engine;

    /**
     * Builds the service with a mocked engine.
     */
    @BeforeEach
    void setUp() {
        service = new DisplayValuationService();
        engine = mock(ValuationEngine.class);
        service.engine = engine;
    }

    /**
     * Builds a one-EAN request on store 0101 at a readable date.
     *
     * @param ean the EAN to value.
     * @return the request.
     */
    private static DisplayValuationRequest request(String ean) {
        DisplayValuationRequest request = new DisplayValuationRequest();
        request.storeCode = "0101";
        request.valuationDate = "2026-06-01";
        request.eans = new ArrayList<>(List.of(ean));
        return request;
    }

    /**
     * Builds a store with a code.
     *
     * @param code the store code.
     * @return the store.
     */
    private static Store store(String code) {
        Store store = new Store();
        store.code = code;
        return store;
    }

    /**
     * Builds a crafted evaluation for one line: an offer application valuing the EAN at the given
     * gross, a net total, and optional EGAlim/SRP records.
     *
     * @param ean       the valued EAN.
     * @param grossExcl the offer gross, tax excluded.
     * @param grossIncl the offer gross, tax included.
     * @param netExcl   the net total, tax excluded.
     * @param netIncl   the net total, tax included.
     * @param egalim    the EGAlim record, or null.
     * @param srp       the SRP record, or null.
     * @return the mocked evaluation.
     */
    private static BasketEvaluation evaluation(String ean, String grossExcl, String grossIncl,
                                               String netExcl, String netIncl,
                                               List<BasketEvaluation.EgalimLine> egalim,
                                               List<BasketEvaluation.SrpLine> srp) {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.produceEan = ean;
        item.amount = new AmountEvaluation(new BigDecimal(grossExcl), new BigDecimal(grossIncl), new BigDecimal("0.20"));
        OfferApplication application = mock(OfferApplication.class);
        when(application.getValuedItems()).thenReturn(List.of(item));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getToEvaluate()).thenReturn(new HashMap<>());
        when(evaluation.getOffers()).thenReturn(List.of(application));
        when(evaluation.getTotalPrice()).thenReturn(
                new AmountEvaluation(new BigDecimal(netExcl), new BigDecimal(netIncl), BigDecimal.ZERO));
        when(evaluation.getEgalim()).thenReturn(egalim);
        when(evaluation.getSrp()).thenReturn(srp);
        return evaluation;
    }

    /**
     * Runs {@code service.evaluate(request)} with every Panache finder mocked: the store resolves to
     * {@code resolvedStore}, groups are empty, no offer or price is in force, the product resolves to
     * {@code product}, and the engine returns {@code result} for any mini-basket.
     *
     * @param request       the request.
     * @param resolvedStore the store {@code Store.findByCode} returns (may be null).
     * @param product       the product {@code Product.findByEan} returns (may be null).
     * @param result        the evaluation the engine returns, or null to leave the engine unstubbed.
     * @return the produced entries.
     */
    private List<DisplayValuationEntry> run(DisplayValuationRequest request, Store resolvedStore,
                                            Product product, BasketEvaluation result) {
        try (MockedStatic<Store> mockedStore = mockStatic(Store.class);
             MockedStatic<StoreGroup> mockedGroup = mockStatic(StoreGroup.class);
             MockedStatic<Offer> mockedOffer = mockStatic(Offer.class);
             MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<PanacheEntityBase> mockedBase = mockStatic(PanacheEntityBase.class)) {
            mockedStore.when(() -> Store.findByCode(any())).thenReturn(resolvedStore);
            mockedGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.<StoreGroup>of());
            mockedOffer.when(() -> Offer.findInForceByStore(any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByStoreGroups(any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByEansAndStore(any(), any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByEansAndStoreGroups(any(), any(), any())).thenReturn(new ArrayList<>());
            mockedProduct.when(() -> Product.findByEan(any())).thenReturn(product);
            mockedBase.when(() -> PanacheEntityBase.list(anyString(), any(Store.class), any(java.time.LocalDateTime.class))).thenReturn(new ArrayList<Product>());
            if (result != null) {
                when(engine.evaluate(any(Basket.class), any())).thenReturn(result);
            }
            return service.evaluate(request);
        }
    }

    /**
     * A product with a markdown: basePrice is the gross, displayedPrice the net, immediateDiscount
     * their difference, and the unit price of measure is computed from the weight reference.
     */
    @Test
    void mapsMarkdownAndWeightMeasure() {
        Product product = new Product();
        product.referenceWeight = new BigDecimal("0.500");
        product.unitName = "kg";
        BasketEvaluation evaluation = evaluation("E1", "2.50", "3.00", "1.67", "2.00", null, null);
        List<DisplayValuationEntry> entries = run(request("E1"), store("0101"), product, evaluation);
        DisplayValuationEntry entry = entries.get(0);
        assertNull(entry.error);
        assertEquals(0, new BigDecimal("3.00").compareTo(entry.basePrice.amountIncludingTax));
        assertEquals(0, new BigDecimal("2.00").compareTo(entry.displayedPrice.amountIncludingTax));
        assertEquals(0, new BigDecimal("1.00").compareTo(entry.immediateDiscount.amountIncludingTax));
        assertEquals(0, new BigDecimal("0.2000").compareTo(entry.vatRate));
        assertNotNull(entry.unitPriceMeasure);
        assertEquals(0, new BigDecimal("4.00").compareTo(entry.unitPriceMeasure.value), "2.00 / 0.500");
        assertEquals("kg", entry.unitPriceMeasure.unit);
        assertNull(entry.floorGuard);
        assertNotNull(entry.priceVersion);
    }

    /**
     * No markdown: gross equals net, so immediateDiscount is absent. A product with only a volume
     * reference prices per litre.
     */
    @Test
    void mapsNoDiscountAndVolumeMeasure() {
        Product product = new Product();
        product.referenceVolume = new BigDecimal("1.500");
        product.unitName = "L";
        BasketEvaluation evaluation = evaluation("E1", "1.25", "1.50", "1.25", "1.50", null, null);
        DisplayValuationEntry entry = run(request("E1"), store("0101"), product, evaluation).get(0);
        assertNull(entry.immediateDiscount, "no discount when gross equals net");
        assertEquals(0, new BigDecimal("1.00").compareTo(entry.unitPriceMeasure.value), "1.50 / 1.500");
        assertEquals("L", entry.unitPriceMeasure.unit);
    }

    /**
     * A product with no measure reference, and an unknown product, both omit the unit price of
     * measure.
     */
    @Test
    void omitsMeasureWhenNoReferenceOrUnknownProduct() {
        Product noRef = new Product();
        DisplayValuationEntry withoutRef = run(request("E1"), store("0101"), noRef,
                evaluation("E1", "2.00", "2.40", "2.00", "2.40", null, null)).get(0);
        assertNull(withoutRef.unitPriceMeasure, "no reference omits the measure");
        DisplayValuationEntry unknownProduct = run(request("E1"), store("0101"), null,
                evaluation("E1", "2.00", "2.40", "2.00", "2.40", null, null)).get(0);
        assertNull(unknownProduct.unitPriceMeasure, "an unknown product omits the measure");
    }

    /**
     * When the SRP guard clamped the line, the floor guard names SRP and carries the floor price;
     * the SRP guard is preferred over EGAlim since it locks last.
     */
    @Test
    void floorGuardPrefersSrpWhenClamped() {
        BasketEvaluation.SrpLine srp = new BasketEvaluation.SrpLine();
        srp.ean = "E1";
        srp.floorPrice = new BigDecimal("1.80");
        srp.reasonCode = SrpGuardFactory.REASON_FLOOR_CLAMPED;
        BasketEvaluation.EgalimLine egalim = new BasketEvaluation.EgalimLine();
        egalim.ean = "E1";
        egalim.correction = new BigDecimal("0.30");
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(),
                evaluation("E1", "2.50", "3.00", "1.50", "1.80", List.of(egalim), List.of(srp))).get(0);
        assertNotNull(entry.floorGuard);
        assertEquals("SRP", entry.floorGuard.type);
        assertEquals(0, new BigDecimal("1.80").compareTo(entry.floorGuard.floorPrice));
    }

    /**
     * When only EGAlim corrected the line, the floor guard names EGALIM with the clamped displayed
     * price as the floor; an SRP line without the clamp reason does not bind.
     */
    @Test
    void floorGuardFallsBackToEgalim() {
        BasketEvaluation.SrpLine srpNoClamp = new BasketEvaluation.SrpLine();
        srpNoClamp.ean = "E1";
        srpNoClamp.reasonCode = "SRP_MARGIN_WARNING";
        BasketEvaluation.EgalimLine egalim = new BasketEvaluation.EgalimLine();
        egalim.ean = "E1";
        egalim.correction = new BigDecimal("0.30");
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(),
                evaluation("E1", "1.00", "1.20", "0.67", "0.80", List.of(egalim), List.of(srpNoClamp))).get(0);
        assertNotNull(entry.floorGuard);
        assertEquals("EGALIM", entry.floorGuard.type);
        assertEquals(0, new BigDecimal("0.80").compareTo(entry.floorGuard.floorPrice), "the clamped displayed price");
    }

    /**
     * No clamp on either guard leaves the floor guard absent, including an EGAlim line whose
     * correction is null.
     */
    @Test
    void floorGuardAbsentWhenNoClamp() {
        BasketEvaluation.EgalimLine egalimNoCorrection = new BasketEvaluation.EgalimLine();
        egalimNoCorrection.ean = "E1";
        egalimNoCorrection.correction = null;
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(),
                evaluation("E1", "2.00", "2.40", "2.00", "2.40", List.of(egalimNoCorrection), new ArrayList<>())).get(0);
        assertNull(entry.floorGuard);
    }

    /**
     * When the engine yields a null total (and a null {@code toEvaluate}), the displayed price falls
     * back to the offer gross and no immediate discount is reported.
     */
    @Test
    void nullTotalFallsBackToGross() {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.produceEan = "E1";
        item.amount = new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.40"), new BigDecimal("0.20"));
        OfferApplication application = mock(OfferApplication.class);
        when(application.getValuedItems()).thenReturn(List.of(item));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getToEvaluate()).thenReturn(null);
        when(evaluation.getOffers()).thenReturn(List.of(application));
        when(evaluation.getTotalPrice()).thenReturn(null);
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(), evaluation).get(0);
        assertNull(entry.error);
        assertEquals(0, new BigDecimal("2.40").compareTo(entry.displayedPrice.amountIncludingTax), "falls back to the gross");
        assertNull(entry.immediateDiscount, "gross equals displayed, so no discount");
    }

    /**
     * An EAN no offer could value (the offers value a different EAN) yields an error entry, not a
     * price.
     */
    @Test
    void grossNotFoundYieldsErrorEntry() {
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(),
                evaluation("OTHER", "2.00", "2.40", "2.00", "2.40", null, null)).get(0);
        assertNotNull(entry.error);
        assertNull(entry.displayedPrice);
    }

    /**
     * A valued item carrying no amount is skipped when summing the gross; a sibling item that does
     * carry an amount still prices the line.
     */
    @Test
    void skipsValuedItemsWithoutAmount() {
        BasketEvaluation.Item noAmount = new BasketEvaluation.Item();
        noAmount.produceEan = "E1";
        noAmount.amount = null;
        BasketEvaluation.Item good = new BasketEvaluation.Item();
        good.produceEan = "E1";
        good.amount = new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.40"), new BigDecimal("0.20"));
        OfferApplication application = mock(OfferApplication.class);
        when(application.getValuedItems()).thenReturn(List.of(noAmount, good));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getToEvaluate()).thenReturn(new HashMap<>());
        when(evaluation.getOffers()).thenReturn(List.of(application));
        when(evaluation.getTotalPrice()).thenReturn(new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.40"), BigDecimal.ZERO));
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(), evaluation).get(0);
        assertNull(entry.error);
        assertEquals(0, new BigDecimal("2.40").compareTo(entry.basePrice.amountIncludingTax), "only the item with an amount counts");
    }

    /**
     * A remaining {@code toEvaluate} item (no offer processed the line) yields an error entry.
     */
    @Test
    void unprocessedItemYieldsErrorEntry() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        Map<String, List<Basket.Item>> toEvaluate = new HashMap<>();
        toEvaluate.put("E1", List.of(new Basket.Item()));
        when(evaluation.getToEvaluate()).thenReturn(toEvaluate);
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(), evaluation).get(0);
        assertNotNull(entry.error);
        assertNull(entry.displayedPrice);
    }

    /**
     * A per-EAN referential gap (the engine throws {@link ReferentialException}) is reported on the
     * entry while the batch continues; its message is carried.
     */
    @Test
    void referentialGapIsIsolatedToItsEntry() {
        try (MockedStatic<Store> mockedStore = mockStatic(Store.class);
             MockedStatic<StoreGroup> mockedGroup = mockStatic(StoreGroup.class);
             MockedStatic<Offer> mockedOffer = mockStatic(Offer.class);
             MockedStatic<Product> mockedProduct = mockStatic(Product.class);
             MockedStatic<PanacheEntityBase> mockedBase = mockStatic(PanacheEntityBase.class)) {
            mockedStore.when(() -> Store.findByCode(any())).thenReturn(store("0101"));
            mockedGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.<StoreGroup>of());
            mockedOffer.when(() -> Offer.findInForceByStore(any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByStoreGroups(any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByEansAndStore(any(), any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByEansAndStoreGroups(any(), any(), any())).thenReturn(new ArrayList<>());
            mockedProduct.when(() -> Product.findByEan(any())).thenReturn(null);
            mockedBase.when(() -> PanacheEntityBase.list(anyString(), any(Store.class), any(java.time.LocalDateTime.class))).thenReturn(new ArrayList<Product>());
            when(engine.evaluate(any(Basket.class), any())).thenThrow(new ReferentialException("Product not found for EAN 'E1'"));
            List<DisplayValuationEntry> entries = service.evaluate(request("E1"));
            assertEquals("Product not found for EAN 'E1'", entries.get(0).error);
            assertNull(entries.get(0).displayedPrice);
            assertNotNull(entries.get(0).priceVersion);
        }
    }

    /**
     * References of zero (present but not strictly positive) omit the unit price of measure, like an
     * absent reference.
     */
    @Test
    void zeroReferencesOmitMeasure() {
        Product product = new Product();
        product.referenceWeight = BigDecimal.ZERO;
        product.referenceVolume = BigDecimal.ZERO;
        DisplayValuationEntry entry = run(request("E1"), store("0101"), product,
                evaluation("E1", "2.00", "2.40", "2.00", "2.40", null, null)).get(0);
        assertNull(entry.unitPriceMeasure, "a zero reference is not a measure");
    }

    /**
     * The floor guard binds only to the valued EAN and only to a real clamp: an SRP clamp on another
     * EAN, an EGAlim correction on another EAN, and a zero EGAlim correction on this EAN all leave
     * the floor guard absent.
     */
    @Test
    void floorGuardIgnoresOtherEanAndZeroCorrection() {
        BasketEvaluation.SrpLine otherSrp = new BasketEvaluation.SrpLine();
        otherSrp.ean = "OTHER";
        otherSrp.reasonCode = SrpGuardFactory.REASON_FLOOR_CLAMPED;
        BasketEvaluation.EgalimLine zeroHere = new BasketEvaluation.EgalimLine();
        zeroHere.ean = "E1";
        zeroHere.correction = BigDecimal.ZERO;
        BasketEvaluation.EgalimLine otherEgalim = new BasketEvaluation.EgalimLine();
        otherEgalim.ean = "OTHER";
        otherEgalim.correction = new BigDecimal("0.30");
        DisplayValuationEntry entry = run(request("E1"), store("0101"), new Product(),
                evaluation("E1", "2.00", "2.40", "2.00", "2.40", List.of(zeroHere, otherEgalim), List.of(otherSrp))).get(0);
        assertNull(entry.floorGuard, "no clamp binds to E1");
    }

    /**
     * A missing store code is refused 400 before any lookup; a null store code and a blank one are
     * the same refusal.
     */
    @Test
    void missingStoreCodeIsRefused400() {
        DisplayValuationRequest blank = request("E1");
        blank.storeCode = " ";
        assertEquals(400, assertThrows(DisplayValuationException.class, () -> service.evaluate(blank)).getStatus());
        DisplayValuationRequest nullCode = request("E1");
        nullCode.storeCode = null;
        assertEquals(400, assertThrows(DisplayValuationException.class, () -> service.evaluate(nullCode)).getStatus());
    }

    /**
     * A blank (non-null) date is refused 400, like a null one, and a null EAN list yields an empty
     * batch rather than failing.
     */
    @Test
    void blankDateRefusedAndNullEansYieldsEmptyBatch() {
        DisplayValuationRequest blankDate = request("E1");
        blankDate.valuationDate = "   ";
        assertEquals(400, assertThrows(DisplayValuationException.class,
                () -> run(blankDate, store("0101"), null, null)).getStatus());
        DisplayValuationRequest noEans = request("E1");
        noEans.eans = null;
        assertTrue(run(noEans, store("0101"), null, null).isEmpty(), "a null EAN list values nothing");
    }

    /**
     * An unknown store is refused 422 naming the code.
     */
    @Test
    void unknownStoreIsRefused422() {
        DisplayValuationRequest request = request("E1");
        request.storeCode = "9999";
        DisplayValuationException thrown = assertThrows(DisplayValuationException.class,
                () -> run(request, null, null, null));
        assertEquals(422, thrown.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("9999"));
    }

    /**
     * A missing then an unreadable date are both refused 400.
     */
    @Test
    void missingAndUnreadableDateAreRefused400() {
        DisplayValuationRequest missing = request("E1");
        missing.valuationDate = null;
        assertEquals(400, assertThrows(DisplayValuationException.class,
                () -> run(missing, store("0101"), null, null)).getStatus());
        DisplayValuationRequest unreadable = request("E1");
        unreadable.valuationDate = "not-a-date";
        DisplayValuationException thrown = assertThrows(DisplayValuationException.class,
                () -> run(unreadable, store("0101"), null, null));
        assertEquals(400, thrown.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("valuationDate"));
    }

    /**
     * A pushed card promotion that violates a cross-rule (a duplicate EAN) is refused 400.
     */
    @Test
    void invalidPushedCardPromotionsRefused400() {
        DisplayValuationRequest request = request("E1");
        Basket.CardPromotion first = new Basket.CardPromotion();
        first.ean = "E1";
        first.promotionType = "AMOUNT";
        first.value = new BigDecimal("1.00");
        Basket.CardPromotion duplicate = new Basket.CardPromotion();
        duplicate.ean = "E1";
        duplicate.promotionType = "AMOUNT";
        duplicate.value = new BigDecimal("2.00");
        request.cardPromotions = List.of(first, duplicate);
        DisplayValuationException thrown = assertThrows(DisplayValuationException.class,
                () -> run(request, store("0101"), null, null));
        assertEquals(400, thrown.getStatus());
    }

    /**
     * Valid pushed card promotions pass the cross-rule check and the valuation proceeds.
     */
    @Test
    void validPushedCardPromotionsPass() {
        DisplayValuationRequest request = request("E1");
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = "E1";
        promotion.promotionType = "PERCENT";
        promotion.value = new BigDecimal("0.10");
        request.cardPromotions = List.of(promotion);
        List<DisplayValuationEntry> entries = run(request, store("0101"), new Product(),
                evaluation("E1", "2.00", "2.40", "2.16", "2.40", null, null));
        assertEquals(1, entries.size());
        assertNull(entries.get(0).error);
    }
}
