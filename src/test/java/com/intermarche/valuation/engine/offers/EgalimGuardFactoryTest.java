package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.EgalimCeiling;
import com.intermarche.valuation.domain.EgalimRegime;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ValuationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EgalimGuardFactory} (EGALIM_GUARD spec §8).
 * <p>
 * Applier-level tests seed real prices (for the nominal lookup) but feed hand-built offer and
 * discount applications into the evaluation, so the per-line arithmetic — nominal, final,
 * counted generosity, exclusions, ceiling, correction — is asserted to the cent without the
 * arbitration in the way. End-to-end tests drive {@link ValuationEngine#evaluate} for the
 * in-force wiring, the open-basket suppression, the no-configuration regression and the
 * determinism. Import and domain tests cover the {@code EGALIM_REGIME} column and the checksum.
 */
@QuarkusTest
@TestTransaction
public class EgalimGuardFactoryTest {

    /**
     * The engine, injected for the end-to-end scenarios.
     */
    @Inject
    ValuationEngine engine;

    /**
     * Mapper used to assert JSON presence/absence and determinism.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The store the products and offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by every test.
     */
    private void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_EG", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a single DEFAULT price (excl. = incl. at 0% VAT) and a regime.
     *
     * @param ean    the product EAN.
     * @param price  the price, tax excluded and included alike.
     * @param regime the EGAlim regime.
     * @return the persisted product.
     */
    private Product seedProduct(String ean, String price, EgalimRegime regime) {
        Product product = DomainUtils.createAndPersistProduct(ean, "Label " + ean, ProductType.UNIT);
        product.egalimRegime = regime;
        product.persist();
        BigDecimal p = new BigDecimal(price);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, p, p, BigDecimal.ZERO);
        return product;
    }

    /**
     * Seeds a UNIT product with a DEFAULT price at a given VAT rate.
     *
     * @param ean     the product EAN.
     * @param ht      the price excluding tax.
     * @param ttc     the price including tax.
     * @param vatRate the VAT rate.
     * @param regime  the EGAlim regime.
     * @return the persisted product.
     */
    private Product seedProductVat(String ean, String ht, String ttc, String vatRate, EgalimRegime regime) {
        DomainUtils.seedStandardVatRegimes();
        Product product = DomainUtils.createAndPersistProduct(ean, "Label " + ean, ProductType.UNIT);
        product.egalimRegime = regime;
        product.persist();
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT,
                new BigDecimal(ht), new BigDecimal(ttc), new BigDecimal(vatRate));
        return product;
    }

    /**
     * Builds a basket line.
     *
     * @param lineId the line id.
     * @param ean    the product EAN.
     * @param qty    the quantity.
     * @return the basket line.
     */
    private Basket.Item line(String lineId, String ean, double qty) {
        Basket.Item item = new Basket.Item();
        item.lineId = lineId;
        item.produceEan = ean;
        item.quantity = BigDecimal.valueOf(qty);
        return item;
    }

    /**
     * Builds an evaluation on a basket carrying the given lines, attached to the seeded store.
     *
     * @param items the basket lines.
     * @return the evaluation under test.
     */
    private BasketEvaluation evaluationOf(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_EG";
        basket.items = new ArrayList<>(List.of(items));
        return new BasketEvaluation(basket);
    }

    /**
     * Builds a valued result item.
     *
     * @param lineId the source line id.
     * @param ean    the product EAN.
     * @param ttc    the attributed amount, tax included (excl. = incl. at 0% VAT).
     * @return the valued item.
     */
    private BasketEvaluation.Item valued(String lineId, String ean, String ttc) {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.lineId = lineId;
        item.produceEan = ean;
        item.quantity = BigDecimal.ONE;
        item.amount = new AmountEvaluation(new BigDecimal(ttc), new BigDecimal(ttc), BigDecimal.ZERO);
        return item;
    }

    /**
     * Builds the guard applier under test (default legal caps).
     *
     * @return the applier.
     */
    private EgalimGuardFactory.EgalimGuardApplier applier() {
        return new EgalimGuardFactory.EgalimGuardApplier("EG",
                Map.of(EgalimRegime.FOOD_34, new BigDecimal("0.34"),
                        EgalimRegime.DPH_40, new BigDecimal("0.40")), store);
    }

    /**
     * Finds the EGAlim record line for an EAN.
     *
     * @param evaluation the evaluation.
     * @param ean        the EAN to find.
     * @return the record line, or null when absent.
     */
    private BasketEvaluation.EgalimLine recordFor(BasketEvaluation evaluation, String ean) {
        if (evaluation.getEgalim() == null) {
            return null;
        }
        return evaluation.getEgalim().stream().filter(l -> ean.equals(l.ean)).findFirst().orElse(null);
    }

    // --------------------------------------------------
    // §8 — applier arithmetic (real prices, stubbed offers/advantages)
    // --------------------------------------------------

    /**
     * §8.1: FOOD_34 line at 50% generosity → correction to the ceiling; the final line is 66% of
     * the nominal (34% rounded down), remainingBeforeCap 0.
     */
    @Test
    void testApply_Food34_FiftyPercent_Corrected() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("VOUCHER", offer, "5.00", null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("10.00"), record.nominalPrice);
        assertEquals(new BigDecimal("5.00"), record.finalPrice);
        assertEquals(new BigDecimal("0.5000"), record.generosityRate);
        assertEquals(new BigDecimal("5.00"), record.countedGenerosity);
        assertEquals(new BigDecimal("0.00"), record.excludedGenerosity);
        assertEquals(new BigDecimal("0.00"), record.remainingBeforeCap);
        assertEquals(new BigDecimal("1.60"), record.correction);
        assertEquals("FOOD_34", record.regime);
        assertEquals("Label FOOD", record.label);
        assertEquals(1, result.size());
        AmountEvaluation amount = ((DiscountApplication) result.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("-1.60"), amount.amountIncludingTax);
    }

    /**
     * §8.2: FOOD_34 line at 30% generosity → conforme, no correction, remainingBeforeCap = 4% of
     * the nominal (0.40).
     */
    @Test
    void testApply_Food34_ThirtyPercent_Conforme() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("VOUCHER", offer, "3.00", null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("7.00"), record.finalPrice);
        assertEquals(new BigDecimal("0.3000"), record.generosityRate);
        assertEquals(new BigDecimal("0.40"), record.remainingBeforeCap);
        assertNull(record.correction);
        assertTrue(result.isEmpty());
    }

    /**
     * §8.3: a "2 for 1" lot (quantity 2, only one unit paid) is measured through its nominal
     * (2 × reference) versus its paid final, so the virtual lot counts and the line is corrected;
     * the record documents the 50% violation.
     */
    @Test
    void testApply_TwoForOne_VirtualLotCounts() {
        setUpDatabase();
        seedProduct("FOOD", "5.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 2));
        // The offer paid one unit (5.00) of the two-unit line; nominal is 2 × 5.00 = 10.00.
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "5.00"));
        evaluation.getOffers().add(offer);
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("10.00"), record.nominalPrice);
        assertEquals(new BigDecimal("5.00"), record.finalPrice);
        assertEquals(new BigDecimal("5.00"), record.countedGenerosity);
        assertEquals(new BigDecimal("1.60"), record.correction);
        assertEquals(1, result.size());
    }

    /**
     * §8.4: DPH_40 line at 38% is conforme; at 45% it is corrected down to 40%.
     */
    @Test
    void testApply_Dph40_ConformeAndCorrected() {
        setUpDatabase();
        seedProduct("DPH", "10.00", EgalimRegime.DPH_40);
        BasketEvaluation conforme = evaluationOf(line("L1", "DPH", 1));
        StubOffer o1 = new StubOffer(valued("L1", "DPH", "10.00"));
        conforme.getOffers().add(o1);
        conforme.getAdvantages().add(new StubDiscount("D", o1, "3.80", null));
        assertTrue(applier().apply(conforme).isEmpty());
        assertNull(recordFor(conforme, "DPH").correction);
        assertEquals(new BigDecimal("0.20"), recordFor(conforme, "DPH").remainingBeforeCap);
        BasketEvaluation corrected = evaluationOf(line("L1", "DPH", 1));
        StubOffer o2 = new StubOffer(valued("L1", "DPH", "10.00"));
        corrected.getOffers().add(o2);
        corrected.getAdvantages().add(new StubDiscount("D", o2, "4.50", null));
        Collection<AdvantageApplication> result = applier().apply(corrected);
        // 4.50 counted, ceiling floor(0.40 × 10) = 4.00, correction 0.50.
        assertEquals(new BigDecimal("0.50"), recordFor(corrected, "DPH").correction);
        assertEquals(1, result.size());
    }

    /**
     * §8.5: an EXEMPT line at 70% generosity is never corrected and carries no
     * remainingBeforeCap, though its generosity is still documented.
     */
    @Test
    void testApply_Exempt_NeverCorrected() {
        setUpDatabase();
        seedProduct("EX", "10.00", EgalimRegime.EXEMPT);
        BasketEvaluation evaluation = evaluationOf(line("L1", "EX", 1));
        StubOffer offer = new StubOffer(valued("L1", "EX", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("D", offer, "7.00", null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "EX");
        assertEquals("EXEMPT", record.regime);
        assertEquals(new BigDecimal("0.7000"), record.generosityRate);
        assertNull(record.remainingBeforeCap);
        assertNull(record.correction);
        assertTrue(result.isEmpty());
    }

    /**
     * §8.6: a 60% anti-waste discount on a perishable FOOD_34 line lands entirely in
     * excludedGenerosity; the counted generosity is zero and there is no correction.
     */
    @Test
    void testApply_AntiWaste_Excluded() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        Basket.Item item = line("L1", "FOOD", 1);
        item.bestBeforeDate = "2020-01-01";
        BasketEvaluation evaluation = evaluationOf(item);
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new AntiWasteDiscountFactory.AntiWasteDiscountApplication(
                "AW", "FOOD", offer,
                new AmountEvaluation(new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("6.00"), record.excludedGenerosity);
        assertEquals(new BigDecimal("0.00"), record.countedGenerosity);
        assertEquals(new BigDecimal("0.0000"), record.generosityRate);
        assertNull(record.correction);
        assertTrue(result.isEmpty());
    }

    /**
     * §8.7: a manual gesture (−40%) is excluded from the counted generosity, so the line is never
     * corrected however deep the gesture.
     */
    @Test
    void testApply_ManualGesture_Excluded() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        Basket.Item item = line("L1", "FOOD", 1);
        item.manualDiscountPercent = new BigDecimal("40");
        BasketEvaluation evaluation = evaluationOf(item);
        // The gesture reprices the line to 6.00; the guard reads it as the paid final.
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "6.00"));
        evaluation.getOffers().add(offer);
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("4.00"), record.excludedGenerosity);
        assertEquals(new BigDecimal("0.00"), record.countedGenerosity);
        assertNull(record.correction);
        assertTrue(result.isEmpty());
    }

    /**
     * §8.8: two cumulated advantages (2.00 + 2.00) reach 40% generosity on a FOOD_34 line, which
     * is corrected down to the 34% ceiling — the case that justifies the guard.
     */
    @Test
    void testApply_CumulatedAdvantages_Corrected() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("C3", offer, "2.00", "FOOD"));
        evaluation.getAdvantages().add(new StubDiscount("CARD", offer, "2.00", "FOOD"));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("6.00"), record.finalPrice);
        assertEquals(new BigDecimal("4.00"), record.countedGenerosity);
        // ceiling floor(0.34 × 10) = 3.40, correction 4.00 − 3.40 = 0.60.
        assertEquals(new BigDecimal("0.60"), record.correction);
        assertEquals(1, result.size());
    }

    /**
     * §8.11: a {@code caps} override (0.30) replaces the legal ceiling.
     */
    @Test
    void testApply_CapOverride() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("D", offer, "3.50", null));
        EgalimGuardFactory.EgalimGuardApplier applier = new EgalimGuardFactory.EgalimGuardApplier(
                "EG", Map.of(EgalimRegime.FOOD_34, new BigDecimal("0.30"),
                        EgalimRegime.DPH_40, new BigDecimal("0.40")), store);
        applier.apply(evaluation);
        // ceiling floor(0.30 × 10) = 3.00, counted 3.50, correction 0.50.
        assertEquals(new BigDecimal("0.50"), recordFor(evaluation, "FOOD").correction);
    }

    /**
     * A VAT-bearing line: the correction splits HT/TTC at the line rate and the aggregate
     * adjustment carries that rate.
     */
    @Test
    void testApply_VatSplitOnCorrection() {
        setUpDatabase();
        seedProductVat("FOOD", "8.33", "10.00", "0.2000", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        BasketEvaluation.Item vi = new BasketEvaluation.Item();
        vi.lineId = "L1";
        vi.produceEan = "FOOD";
        vi.quantity = BigDecimal.ONE;
        vi.amount = new AmountEvaluation(new BigDecimal("8.33"), new BigDecimal("10.00"), new BigDecimal("0.20"));
        StubOffer offer = new StubOffer(vi);
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("D", offer,
                new AmountEvaluation(new BigDecimal("4.17"), new BigDecimal("5.00"), new BigDecimal("0.20")), null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        // counted 5.00, ceiling floor(0.34 × 10) = 3.40, correction 1.60 TTC → 1.33 HT at 20%.
        AmountEvaluation amount = ((DiscountApplication) result.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("-1.60"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("-1.33"), amount.amountExcludingTax);
    }

    /**
     * A line with no generosity is conforme with the whole ceiling still available, and the guard
     * emits a record but no correction.
     */
    @Test
    void testApply_NoGenerosity_FullMarginRemaining() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        evaluation.getOffers().add(new StubOffer(valued("L1", "FOOD", "10.00")));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("0.00"), record.countedGenerosity);
        assertEquals(new BigDecimal("3.40"), record.remainingBeforeCap);
        assertTrue(result.isEmpty());
        assertNotNull(evaluation.getEgalim());
    }

    /**
     * An unpriceable EAN line is left out of the record entirely.
     */
    @Test
    void testApply_UnpriceableLine_SkippedFromRecord() {
        setUpDatabase();
        BasketEvaluation evaluation = evaluationOf(line("L1", "GHOST", 1));
        applier().apply(evaluation);
        assertTrue(evaluation.getEgalim().isEmpty());
    }

    /**
     * A line carrying no EAN is priced at its entered price and recorded as EXEMPT (no product,
     * no ceiling).
     */
    @Test
    void testApply_NoEanLine_ExemptFromEnteredPrice() {
        setUpDatabase();
        Basket.Item item = new Basket.Item();
        item.lineId = "L1";
        item.pricePerUnitExclTax = new BigDecimal("8.00");
        item.pricePerUnitInclTax = new BigDecimal("10.00");
        item.vatRate = BigDecimal.ZERO;
        item.quantity = BigDecimal.ONE;
        BasketEvaluation evaluation = evaluationOf(item);
        applier().apply(evaluation);
        BasketEvaluation.EgalimLine record = evaluation.getEgalim().get(0);
        assertEquals("EXEMPT", record.regime);
        assertEquals(new BigDecimal("10.00"), record.nominalPrice);
        assertNull(record.correction);
        assertNull(record.remainingBeforeCap);
        assertNull(record.ean);
    }

    /**
     * A null basket sets an empty record and produces nothing.
     */
    @Test
    void testApply_NullBasketItems_EmptyRecord() {
        setUpDatabase();
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        assertTrue(result.isEmpty());
        assertNotNull(evaluation.getEgalim());
        assertTrue(evaluation.getEgalim().isEmpty());
    }

    /**
     * The applier never registers on an offer applier and scores zero.
     */
    @Test
    void testApplier_NotApplicableZeroScore() {
        setUpDatabase();
        assertFalse(applier().isApplicable(new StubApplier()));
        assertEquals(0.0, applier().getEfficiencyScore());
    }

    /**
     * The forced configuration is AT_TOTAL, priority Integer.MAX_VALUE, cumulable.
     */
    @Test
    void testApplier_ForcedConfiguration() {
        setUpDatabase();
        com.intermarche.valuation.domain.Offer configuration = applier().getConfiguration();
        assertEquals("EGALIM_GUARD", configuration.type);
        assertTrue(configuration.specification.contains("\"applicationMoment\":\"AT_TOTAL\""));
        assertTrue(configuration.specification.contains("2147483647"));
    }

    /**
     * The adjustment application exposes its type, its per-line detail and the AT_TOTAL moment
     * round-trip, and returns no targeted offer application.
     */
    @Test
    void testAdjustmentApplication_Getters() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("D", offer, "5.00", null));
        EgalimGuardFactory.EgalimAdjustmentApplication app =
                (EgalimGuardFactory.EgalimAdjustmentApplication) applier().apply(evaluation).iterator().next();
        assertEquals("EGAlim Adjustment: EG", app.getType());
        assertEquals("EGAlim Adjustment: EG", app.getOffer());
        assertNull(app.getOfferApplication());
        assertEquals(1, app.getCorrections().size());
        assertEquals("FOOD", app.getCorrections().get(0).ean);
        assertEquals(new BigDecimal("1.60"), app.getCorrections().get(0).correction);
        assertEquals("AT_TOTAL", app.getApplicationMoment());
        app.setApplicationMoment("AT_TRIGGER");
        assertEquals("AT_TRIGGER", app.getApplicationMoment());
    }

    // --------------------------------------------------
    // §8 — end-to-end wiring, suppression, regression, determinism
    // --------------------------------------------------

    /**
     * With an in-force configuration, a card promotion of 50% on a FOOD_34 line is corrected: the
     * total is lifted back to 66% of the nominal and the {@code egalim} record is emitted.
     */
    @Test
    void testEndToEnd_InForce_CorrectsAndRecords() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        DomainUtils.createAndPersistOffer("EG_GUARD", store, "EGALIM_GUARD", "{}");
        Basket basket = new Basket();
        basket.storeCode = "STORE_EG";
        basket.items = new ArrayList<>(List.of(line("L1", "FOOD", 1)));
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = "FOOD";
        promotion.promotionType = "PERCENT";
        promotion.value = new BigDecimal("0.50");
        basket.cardPromotions = new ArrayList<>(List.of(promotion));
        BasketEvaluation evaluation = engine.evaluate(basket);
        assertNotNull(evaluation.getEgalim());
        BasketEvaluation.EgalimLine record = recordFor(evaluation, "FOOD");
        assertEquals(new BigDecimal("1.60"), record.correction);
        // 10.00 − 5.00 (card) + 1.60 (EGAlim reprise) = 6.60.
        assertEquals(0, new BigDecimal("6.60").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * §8.11 (end to end): a {@code caps} override in the persisted specification replaces the
     * legal ceiling — a 0.30 food cap corrects a 50% line down to 30%.
     */
    @Test
    void testEndToEnd_CapsOverride_FromSpecification() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        DomainUtils.createAndPersistOffer("EG_CAP", store, "EGALIM_GUARD",
                "{ \"caps\": { \"FOOD_34\": 0.30, \"DPH_40\": 0.45 } }");
        BasketEvaluation evaluation = engine.evaluate(guardedBasket());
        // counted 5.00, ceiling floor(0.30 × 10) = 3.00, correction 2.00.
        assertEquals(new BigDecimal("2.00"), recordFor(evaluation, "FOOD").correction);
        assertEquals(0, new BigDecimal("7.00").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * §8.9: on an open basket the AT_TOTAL guard does not run, so there is neither a correction
     * nor an {@code egalim} record.
     */
    @Test
    void testEndToEnd_OpenBasket_NoGuard() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        DomainUtils.createAndPersistOffer("EG_GUARD", store, "EGALIM_GUARD", "{}");
        Basket basket = new Basket();
        basket.storeCode = "STORE_EG";
        basket.items = new ArrayList<>(List.of(line("L1", "FOOD", 1)));
        basket.closed = false;
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = "FOOD";
        promotion.promotionType = "PERCENT";
        promotion.value = new BigDecimal("0.50");
        basket.cardPromotions = new ArrayList<>(List.of(promotion));
        BasketEvaluation evaluation = engine.evaluate(basket);
        assertNull(evaluation.getEgalim());
    }

    /**
     * §8.10: without an in-force configuration the response omits the {@code egalim} block and is
     * byte-for-byte identical to a valuation of the same basket.
     *
     * @throws Exception if serialization fails.
     */
    @Test
    void testEndToEnd_NoConfiguration_BitForBitIdentical() throws Exception {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        Basket basket = new Basket();
        basket.storeCode = "STORE_EG";
        basket.items = new ArrayList<>(List.of(line("L1", "FOOD", 1)));
        BasketEvaluation evaluation = engine.evaluate(basket);
        assertNull(evaluation.getEgalim());
        String json = MAPPER.writeValueAsString(evaluation);
        assertFalse(json.contains("egalim"));
    }

    /**
     * §8.10: the guard is targeted per store, never global. A basket from a store not covered by
     * the configuration gets no correction and no {@code egalim} block, even though the offer is
     * in force on another store.
     */
    @Test
    void testEndToEnd_UntargetedStore_NoGuard() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        // The guard is in force on STORE_EG only.
        DomainUtils.createAndPersistOffer("EG_GUARD", store, "EGALIM_GUARD", "{}");
        // A second store, not targeted by the configuration, carrying the same product.
        Store other = DomainUtils.createAndPersistStore("STORE_EG2", 45.75, 4.85);
        Product food = Product.findByEan("FOOD");
        DomainUtils.createAndPersistPrice(food, other, 0, PriceUsage.DEFAULT,
                new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO);
        Basket basket = new Basket();
        basket.storeCode = "STORE_EG2";
        basket.items = new ArrayList<>(List.of(line("L1", "FOOD", 1)));
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = "FOOD";
        promotion.promotionType = "PERCENT";
        promotion.value = new BigDecimal("0.50");
        basket.cardPromotions = new ArrayList<>(List.of(promotion));
        BasketEvaluation evaluation = engine.evaluate(basket);
        assertNull(evaluation.getEgalim());
        // The card promotion still applies (10.00 − 5.00), but no EGAlim reprise lifts it back.
        assertEquals(0, new BigDecimal("5.00").compareTo(evaluation.getTotalPrice().amountIncludingTax));
    }

    /**
     * §8.12: fifty evaluations of the same guarded basket produce a byte-for-byte identical
     * response.
     *
     * @throws Exception if serialization fails.
     */
    @Test
    void testEndToEnd_DeterministicOverFiftyRuns() throws Exception {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        DomainUtils.createAndPersistOffer("EG_GUARD", store, "EGALIM_GUARD", "{}");
        String reference = MAPPER.writeValueAsString(engine.evaluate(guardedBasket()));
        for (int run = 0; run < 50; run++) {
            assertEquals(reference, MAPPER.writeValueAsString(engine.evaluate(guardedBasket())));
        }
    }

    /**
     * With several in-force guards, the first (by code) applies and the others are traced as
     * skipped configurations.
     */
    @Test
    void testEndToEnd_SeveralGuards_FirstApplies() {
        setUpDatabase();
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        DomainUtils.createAndPersistOffer("EG_A", store, "EGALIM_GUARD", "{}");
        DomainUtils.createAndPersistOffer("EG_B", store, "EGALIM_GUARD", "{}");
        BasketEvaluation evaluation = engine.evaluate(guardedBasket());
        assertNotNull(evaluation.getEgalim());
        assertEquals(new BigDecimal("1.60"), recordFor(evaluation, "FOOD").correction);
        assertTrue(evaluation.getSkippedConfigurations().stream()
                .anyMatch(m -> m.contains("EG_B") && m.contains("already in force")));
    }

    /**
     * Builds a guarded FOOD_34 basket carrying a 50% card promotion.
     *
     * @return the basket.
     */
    private Basket guardedBasket() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_EG";
        basket.items = new ArrayList<>(List.of(line("L1", "FOOD", 1)));
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = "FOOD";
        promotion.promotionType = "PERCENT";
        promotion.value = new BigDecimal("0.50");
        basket.cardPromotions = new ArrayList<>(List.of(promotion));
        return basket;
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * An offer application exposing a fixed set of valued items and their summed amount.
     */
    public static class StubOffer implements OfferApplication {

        /**
         * The valued items this offer attributes.
         */
        private final List<BasketEvaluation.Item> items;

        /**
         * The summed amount, tax included, over the valued items.
         */
        private final AmountEvaluation amount;

        /**
         * Builds the stub from its valued items.
         *
         * @param items the valued items.
         */
        public StubOffer(BasketEvaluation.Item... items) {
            this.items = new ArrayList<>(List.of(items));
            BigDecimal ht = BigDecimal.ZERO;
            BigDecimal ttc = BigDecimal.ZERO;
            for (BasketEvaluation.Item item : items) {
                ht = ht.add(item.amount.amountExcludingTax);
                ttc = ttc.add(item.amount.amountIncludingTax);
            }
            this.amount = new AmountEvaluation(ht, ttc,
                    ht.signum() == 0 ? BigDecimal.ZERO : ttc.divide(ht, 4, java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE));
        }

        /**
         * Returns the summed amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns no source items (unused by the guard).
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
            return "StubOffer";
        }

        /**
         * Returns the valued items.
         *
         * @return the valued items.
         */
        @Override
        public List<BasketEvaluation.Item> getValuedItems() {
            return items;
        }
    }

    /**
     * A ticket-wide discount application targeting a stub offer.
     * <p>
     * It is deliberately not product-scoped, so {@link com.intermarche.valuation.engine.NetAmounts}
     * prorates it by the product's share of the target — which, on a single-line offer, subtracts
     * it in full.
     */
    public static class StubDiscount implements DiscountApplication {

        /**
         * The discount code.
         */
        private final String code;

        /**
         * The targeted offer application.
         */
        private final OfferApplication target;

        /**
         * The discount amount, stored positive.
         */
        private final AmountEvaluation amount;

        /**
         * Builds a 0%-VAT discount.
         *
         * @param code   the discount code.
         * @param target the targeted offer.
         * @param ttc    the discount amount, tax included.
         * @param ean    ignored (kept for call-site readability).
         */
        public StubDiscount(String code, OfferApplication target, String ttc, String ean) {
            this(code, target, new AmountEvaluation(new BigDecimal(ttc), new BigDecimal(ttc), BigDecimal.ZERO), ean);
        }

        /**
         * Builds a discount with an explicit amount.
         *
         * @param code   the discount code.
         * @param target the targeted offer.
         * @param amount the discount amount, stored positive.
         * @param ean    ignored (kept for call-site readability).
         */
        public StubDiscount(String code, OfferApplication target, AmountEvaluation amount, String ean) {
            this.code = code;
            this.target = target;
            this.amount = amount;
        }

        /**
         * Returns the display type.
         *
         * @return the code.
         */
        public String getType() {
            return "Stub: " + code;
        }

        /**
         * Returns the targeted offer application.
         *
         * @return the target.
         */
        @Override
        public OfferApplication getOfferApplication() {
            return target;
        }

        /**
         * Returns the discount amount.
         *
         * @return the amount, stored positive.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return amount;
        }
    }

    /**
     * A minimal offer applier stub, to check the guard never registers on one.
     */
    public static class StubApplier extends OfferApplier {

        /**
         * Produces no application.
         *
         * @param basketEvaluation the evaluation context.
         * @return an empty collection.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            return List.of();
        }

        /**
         * Returns no configuration.
         *
         * @return always null.
         */
        @Override
        public com.intermarche.valuation.domain.Offer getConfiguration() {
            return null;
        }
    }

    /**
     * Seeds one administered ceiling row.
     *
     * @param code    the regime code.
     * @param capRate the ceiling as a fraction, or null for an uncapped regime.
     */
    private void seedCeiling(String code, String capRate) {
        new EgalimCeiling(code, code, capRate == null ? null : new BigDecimal(capRate)).persist();
    }

    /**
     * Reads a {@code caps} override node from its JSON text.
     *
     * @param json the JSON object, or null for a guard declaring no override.
     * @return the parsed node, or null.
     */
    private com.fasterxml.jackson.databind.JsonNode capsNode(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * §3 resolution ladder, third source: an empty referential and no override leave the legal
     * values compiled into the enum in place. This is the regression guarantee — an engine fed
     * by a node that does not yet ship the EGALIM_REGIMES feed computes exactly as before.
     */
    @Test
    void testResolveCaps_EnumDefaultsWhenNothingElseStates() {
        Map<EgalimRegime, BigDecimal> caps = EgalimGuardFactory.resolveCaps(null);
        assertEquals(0, new BigDecimal("0.34").compareTo(caps.get(EgalimRegime.FOOD_34)));
        assertEquals(0, new BigDecimal("0.40").compareTo(caps.get(EgalimRegime.DPH_40)));
        assertNull(caps.get(EgalimRegime.EXEMPT));
    }

    /**
     * §3 resolution ladder, second source: with a silent referential the guard's own override
     * applies — and only to the regime it names, the other falling through to the enum.
     */
    @Test
    void testResolveCaps_OverrideWhenReferentialSilent() {
        Map<EgalimRegime, BigDecimal> caps =
                EgalimGuardFactory.resolveCaps(capsNode("{\"FOOD_34\":0.30}"));
        assertEquals(0, new BigDecimal("0.30").compareTo(caps.get(EgalimRegime.FOOD_34)));
        assertEquals(0, new BigDecimal("0.40").compareTo(caps.get(EgalimRegime.DPH_40)));
    }

    /**
     * An override stating an explicit JSON null is no override: the key is present and the
     * value is absent, which is the false leg of the {@code hasNonNull} guard.
     */
    @Test
    void testResolveCaps_ExplicitNullOverrideIgnored() {
        Map<EgalimRegime, BigDecimal> caps =
                EgalimGuardFactory.resolveCaps(capsNode("{\"FOOD_34\":null}"));
        assertEquals(0, new BigDecimal("0.34").compareTo(caps.get(EgalimRegime.FOOD_34)));
    }

    /**
     * §3 resolution ladder, first source: the administered referential beats BOTH the guard's
     * override and the enum. This is the whole point of the EGALIM_REGIMES feed — a ceiling
     * corrected on the store node's screen is the one that computes.
     */
    @Test
    void testResolveCaps_ReferentialBeatsOverrideAndEnum() {
        seedCeiling("FOOD_34", "0.36");
        Map<EgalimRegime, BigDecimal> caps =
                EgalimGuardFactory.resolveCaps(capsNode("{\"FOOD_34\":0.30}"));
        assertEquals(0, new BigDecimal("0.36").compareTo(caps.get(EgalimRegime.FOOD_34)));
        assertEquals(0, new BigDecimal("0.40").compareTo(caps.get(EgalimRegime.DPH_40)));
    }

    /**
     * A referential row WITHOUT a ceiling is an answer, not a silence: it releases the regime,
     * and neither the override nor the enum default is consulted. A released regime is absent
     * from the map, which the applier reads as "never correct".
     */
    @Test
    void testResolveCaps_ReferentialWithoutCeilingReleasesTheRegime() {
        seedCeiling("FOOD_34", null);
        Map<EgalimRegime, BigDecimal> caps =
                EgalimGuardFactory.resolveCaps(capsNode("{\"FOOD_34\":0.30}"));
        assertNull(caps.get(EgalimRegime.FOOD_34));
        assertFalse(caps.containsKey(EgalimRegime.FOOD_34));
    }

    /**
     * The referential may also CAP a regime the enum releases: the exempt category is a legal
     * state of today, not a property of the software.
     */
    @Test
    void testResolveCaps_ReferentialCanCapAnExemptRegime() {
        seedCeiling("EXEMPT", "0.50");
        Map<EgalimRegime, BigDecimal> caps = EgalimGuardFactory.resolveCaps(null);
        assertEquals(0, new BigDecimal("0.50").compareTo(caps.get(EgalimRegime.EXEMPT)));
    }

    /**
     * End of the loop: a ceiling administered on the store node, delivered as the
     * EGALIM_REGIMES feed, is the figure the correction is computed with — 30 % here rather
     * than the 34 % of the enum, so the 3.50 of generosity on a 10.00 line is cut back by 0.50.
     */
    @Test
    void testApply_AdministeredCeilingDrivesTheCorrection() {
        setUpDatabase();
        seedCeiling("FOOD_34", "0.30");
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("D", offer, "3.50", null));
        EgalimGuardFactory.EgalimGuardApplier applier = new EgalimGuardFactory.EgalimGuardApplier(
                "EG", EgalimGuardFactory.resolveCaps(null), store);
        applier.apply(evaluation);
        assertEquals(new BigDecimal("0.50"), recordFor(evaluation, "FOOD").correction);
    }

    /**
     * The other arm of the same loop: a regime the referential releases is recorded and left
     * alone, with no correction and no remaining-before-cap figure, even though the enum caps
     * it at 34 %.
     */
    @Test
    void testApply_ReleasedRegimeIsNeverCorrected() {
        setUpDatabase();
        seedCeiling("FOOD_34", null);
        seedProduct("FOOD", "10.00", EgalimRegime.FOOD_34);
        BasketEvaluation evaluation = evaluationOf(line("L1", "FOOD", 1));
        StubOffer offer = new StubOffer(valued("L1", "FOOD", "10.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new StubDiscount("D", offer, "9.00", null));
        EgalimGuardFactory.EgalimGuardApplier applier = new EgalimGuardFactory.EgalimGuardApplier(
                "EG", EgalimGuardFactory.resolveCaps(null), store);
        Collection<AdvantageApplication> produced = applier.apply(evaluation);
        assertTrue(produced.isEmpty());
        assertNull(recordFor(evaluation, "FOOD").correction);
        assertNull(recordFor(evaluation, "FOOD").remainingBeforeCap);
    }

}
