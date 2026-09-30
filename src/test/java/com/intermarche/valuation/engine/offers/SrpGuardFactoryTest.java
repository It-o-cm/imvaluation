package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.EgalimRegime;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.ValuationEngine;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SrpGuardFactory} (spec-srp-guard §4).
 * <p>
 * Applier-level tests seed real prices (a {@code DEFAULT} shelf price and, where a floor applies,
 * an {@code SRP_FLOOR} row) but feed hand-built offer and discount applications into the evaluation,
 * so the per-line arithmetic — floor, final, margin, correction — is asserted to the cent without
 * the arbitration in the way. End-to-end tests drive {@link ValuationEngine#evaluate} for the
 * in-force wiring, the open-basket suppression, the no-configuration regression, the EGAlim
 * interaction and the determinism.
 */
@QuarkusTest
@TestTransaction
public class SrpGuardFactoryTest {

    /**
     * The engine, injected for the end-to-end scenarios.
     */
    @Inject
    ValuationEngine engine;

    /**
     * Mapper used to assert determinism.
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
        store = DomainUtils.createAndPersistStore("STORE_SRP", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product with a single DEFAULT price (excl. = incl. at 0% VAT).
     *
     * @param ean   the product EAN.
     * @param price the shelf price, tax excluded and included alike.
     * @return the persisted product.
     */
    private Product seedProduct(String ean, String price) {
        Product product = DomainUtils.createAndPersistProduct(ean, "Label " + ean, ProductType.UNIT);
        BigDecimal p = new BigDecimal(price);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, p, p, BigDecimal.ZERO);
        return product;
    }

    /**
     * Seeds an {@code SRP_FLOOR} unit price for a product (0% VAT).
     *
     * @param product the product.
     * @param floor   the floor unit price, tax excluded and included alike.
     */
    private void seedFloor(Product product, String floor) {
        BigDecimal f = new BigDecimal(floor);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.SRP_FLOOR, f, f, BigDecimal.ZERO);
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
        basket.storeCode = "STORE_SRP";
        basket.items = new ArrayList<>(List.of(items));
        return new BasketEvaluation(basket);
    }

    /**
     * Builds a valued result item at 0% VAT.
     *
     * @param lineId the source line id.
     * @param ean    the product EAN.
     * @param ttc    the attributed amount, tax included.
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
     * Builds the guard applier under test.
     *
     * @return the applier.
     */
    private SrpGuardFactory.SrpGuardApplier applier() {
        return new SrpGuardFactory.SrpGuardApplier("SRP", store, java.util.List.of());
    }

    /**
     * Finds the SRP record line for a line id.
     *
     * @param evaluation the evaluation.
     * @param lineId     the line id to find.
     * @return the record line, or null when absent.
     */
    private BasketEvaluation.SrpLine recordFor(BasketEvaluation evaluation, String lineId) {
        if (evaluation.getSrp() == null) {
            return null;
        }
        return evaluation.getSrp().stream().filter(l -> lineId.equals(l.lineId)).findFirst().orElse(null);
    }

    // --------------------------------------------------
    // §4 — applier arithmetic (real prices, stubbed offers/advantages)
    // --------------------------------------------------

    /**
     * §4.1: a line with a floor of 11.00 whose discounts bring the net to 10.00 is corrected by
     * +1.00, so the final ticket line reaches the floor; the record is complete.
     */
    @Test
    void testApply_BelowFloor_Corrected() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "11.00");
        BasketEvaluation evaluation = evaluationOf(line("L1", "SRP_A", 1));
        EgalimGuardFactoryTest.StubOffer offer = new EgalimGuardFactoryTest.StubOffer(valued("L1", "SRP_A", "20.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new EgalimGuardFactoryTest.StubDiscount("D", offer, "10.00", null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.SrpLine record = recordFor(evaluation, "L1");
        assertEquals(new BigDecimal("11.00"), record.floorPrice);
        assertEquals(new BigDecimal("10.00"), record.finalPrice);
        assertEquals(new BigDecimal("0.00"), record.marginAboveFloor);
        assertEquals(new BigDecimal("1.00"), record.correction);
        assertEquals("SRP_A", record.ean);
        assertEquals(1, result.size());
        AmountEvaluation amount = ((DiscountApplication) result.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("-1.00"), amount.amountIncludingTax);
    }

    /**
     * §4.2: a net above the floor is not corrected; the margin above the floor is recorded.
     */
    @Test
    void testApply_AboveFloor_NoCorrection() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "11.00");
        BasketEvaluation evaluation = evaluationOf(line("L1", "SRP_A", 1));
        EgalimGuardFactoryTest.StubOffer offer = new EgalimGuardFactoryTest.StubOffer(valued("L1", "SRP_A", "20.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new EgalimGuardFactoryTest.StubDiscount("D", offer, "5.00", null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.SrpLine record = recordFor(evaluation, "L1");
        assertEquals(new BigDecimal("15.00"), record.finalPrice);
        assertEquals(new BigDecimal("4.00"), record.marginAboveFloor);
        assertNull(record.correction);
        assertTrue(result.isEmpty());
    }

    /**
     * §4.3: an article with no {@code SRP_FLOOR} row is not controlled — it is absent from the
     * record and never corrected.
     */
    @Test
    void testApply_NoFloor_NotControlled() {
        setUpDatabase();
        seedProduct("SRP_A", "20.00");
        BasketEvaluation evaluation = evaluationOf(line("L1", "SRP_A", 1));
        EgalimGuardFactoryTest.StubOffer offer = new EgalimGuardFactoryTest.StubOffer(valued("L1", "SRP_A", "20.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new EgalimGuardFactoryTest.StubDiscount("D", offer, "18.00", null));
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        assertNull(recordFor(evaluation, "L1"), "an article without a floor is absent from the record");
        assertTrue(evaluation.getSrp().isEmpty());
        assertTrue(result.isEmpty());
    }

    /**
     * §4.4: a line discounted by a {@code compliance.srpExempt} configuration is traced but never
     * lifted to the floor, even below it (the sales case).
     */
    @Test
    void testApply_SrpExempt_TracedNotCorrected() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "11.00");
        BasketEvaluation evaluation = evaluationOf(line("L1", "SRP_A", 1));
        EgalimGuardFactoryTest.StubOffer offer = new EgalimGuardFactoryTest.StubOffer(valued("L1", "SRP_A", "20.00"));
        evaluation.getOffers().add(offer);
        EgalimGuardFactoryTest.StubDiscount exempt = new EgalimGuardFactoryTest.StubDiscount("SOLDES", offer, "10.00", null);
        exempt.setSrpExempt(true);
        evaluation.getAdvantages().add(exempt);
        Collection<AdvantageApplication> result = applier().apply(evaluation);
        BasketEvaluation.SrpLine record = recordFor(evaluation, "L1");
        assertNotNull(record, "an exempt line is still traced");
        assertEquals(new BigDecimal("10.00"), record.finalPrice);
        assertNull(record.correction, "an exempt line is never corrected");
        assertTrue(result.isEmpty());
    }

    /**
     * §3.1: the floor is the floor unit price times the quantity — a two-unit line at a 11.00 unit
     * floor has a 22.00 line floor.
     */
    @Test
    void testApply_FloorScalesWithQuantity() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "11.00");
        BasketEvaluation evaluation = evaluationOf(line("L1", "SRP_A", 2));
        EgalimGuardFactoryTest.StubOffer offer = new EgalimGuardFactoryTest.StubOffer(valued("L1", "SRP_A", "40.00"));
        evaluation.getOffers().add(offer);
        evaluation.getAdvantages().add(new EgalimGuardFactoryTest.StubDiscount("D", offer, "20.00", null));
        applier().apply(evaluation);
        BasketEvaluation.SrpLine record = recordFor(evaluation, "L1");
        assertEquals(new BigDecimal("22.00"), record.floorPrice);
        assertEquals(new BigDecimal("20.00"), record.finalPrice);
        assertEquals(new BigDecimal("2.00"), record.correction);
    }

    // --------------------------------------------------
    // §4 — end-to-end wiring
    // --------------------------------------------------

    /**
     * §4.6: with no in-force {@code SRP_GUARD} configuration, no correction happens and the
     * {@code srp[]} block is omitted (byte-for-byte regression-free).
     */
    @Test
    void testEndToEnd_NoConfiguration_NoBlock() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "30.00");
        BasketEvaluation evaluation = engine.evaluate(srpBasket());
        assertNull(evaluation.getSrp(), "no guard configuration leaves the srp block absent");
    }

    /**
     * §4.6: an in-force guard on an OPEN basket does not run (AT_TOTAL wave skipped), so there is
     * neither correction nor {@code srp[]} block.
     */
    @Test
    void testEndToEnd_OpenBasket_NoBlock() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "30.00");
        DomainUtils.createAndPersistOffer("SRP_G", store, "SRP_GUARD", "{}");
        Basket basket = srpBasket();
        basket.closed = false;
        BasketEvaluation evaluation = engine.evaluate(basket);
        assertNull(evaluation.getSrp(), "an open basket does not run the guard");
    }

    /**
     * §4.1 end to end: a floored line driven below its floor by a card promotion is lifted back to
     * the floor by the in-force guard, and the {@code srp[]} block reports it.
     */
    @Test
    void testEndToEnd_BelowFloor_Corrected() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "15.00");
        DomainUtils.createAndPersistOffer("SRP_G", store, "SRP_GUARD", "{}");
        BasketEvaluation evaluation = engine.evaluate(srpBasket());
        BasketEvaluation.SrpLine record = recordFor(evaluation, "L1");
        assertNotNull(record);
        assertEquals(new BigDecimal("15.00"), record.floorPrice);
        assertEquals(new BigDecimal("10.00"), record.finalPrice);
        assertEquals(new BigDecimal("5.00"), record.correction);
        assertTrue(evaluation.getAdvantages().stream()
                .anyMatch(a -> "SRP Adjustment: SRP_G".equals(a.getOffer())));
    }

    /**
     * §4.5: EGAlim corrects the generosity first (to 34%), then SRP locks the resulting price to the
     * floor — the two records coherent on the same ticket. FOOD 10.00, 50% promotion: EGAlim lifts
     * the net from 5.00 to 6.60 (ceiling 3.40), then SRP lifts 6.60 to the 8.00 floor (+1.40).
     */
    @Test
    void testEndToEnd_EgalimThenSrp() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("FOOD", "Label FOOD", ProductType.UNIT);
        product.egalimRegime = EgalimRegime.FOOD_34;
        product.persist();
        BigDecimal ten = new BigDecimal("10.00");
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, ten, ten, BigDecimal.ZERO);
        seedFloor(product, "8.00");
        DomainUtils.createAndPersistOffer("EG_G", store, "EGALIM_GUARD", "{}");
        DomainUtils.createAndPersistOffer("SRP_G", store, "SRP_GUARD", "{}");
        BasketEvaluation evaluation = engine.evaluate(promoBasket("FOOD"));
        BasketEvaluation.EgalimLine egalim = evaluation.getEgalim().stream()
                .filter(l -> "FOOD".equals(l.ean)).findFirst().orElse(null);
        assertNotNull(egalim);
        assertEquals(new BigDecimal("1.60"), egalim.correction, "EGAlim corrects the generosity first");
        BasketEvaluation.SrpLine srp = recordFor(evaluation, "L1");
        assertNotNull(srp);
        assertEquals(new BigDecimal("8.00"), srp.floorPrice);
        assertEquals(new BigDecimal("6.60"), srp.finalPrice, "the SRP net includes the EGAlim reprise");
        assertEquals(new BigDecimal("1.40"), srp.correction, "SRP locks the resulting price to the floor");
        assertEquals(new BigDecimal("0.00"), srp.marginAboveFloor);
    }

    /**
     * §4.8: the same guarded basket evaluates to a byte-identical response fifty times.
     *
     * @throws Exception if serialization fails.
     */
    @Test
    void testEndToEnd_DeterministicOverFiftyRuns() throws Exception {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "15.00");
        DomainUtils.createAndPersistOffer("SRP_G", store, "SRP_GUARD", "{}");
        String reference = MAPPER.writeValueAsString(engine.evaluate(srpBasket()));
        for (int run = 0; run < 50; run++) {
            assertEquals(reference, MAPPER.writeValueAsString(engine.evaluate(srpBasket())));
        }
    }

    /**
     * With several in-force guards, the first (by code) applies and the others are traced as
     * skipped configurations.
     */
    @Test
    void testEndToEnd_SeveralGuards_FirstApplies() {
        setUpDatabase();
        Product product = seedProduct("SRP_A", "20.00");
        seedFloor(product, "15.00");
        DomainUtils.createAndPersistOffer("SRP_A_G", store, "SRP_GUARD", "{}");
        DomainUtils.createAndPersistOffer("SRP_B_G", store, "SRP_GUARD", "{}");
        BasketEvaluation evaluation = engine.evaluate(srpBasket());
        assertNotNull(evaluation.getSrp());
        assertTrue(evaluation.getSkippedConfigurations().stream()
                .anyMatch(m -> m.contains("SRP_B_G") && m.contains("already in force")));
    }

    /**
     * Builds a closed basket carrying one SRP_A line and a 50% card promotion driving it to 10.00.
     *
     * @return the basket.
     */
    private Basket srpBasket() {
        return promoBasket("SRP_A");
    }

    /**
     * Builds a closed basket with one line of the given EAN and a 50% card promotion on it.
     *
     * @param ean the product EAN.
     * @return the basket.
     */
    private Basket promoBasket(String ean) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_SRP";
        basket.items = new ArrayList<>(List.of(line("L1", ean, 1)));
        Basket.CardPromotion promotion = new Basket.CardPromotion();
        promotion.ean = ean;
        promotion.promotionType = "PERCENT";
        promotion.value = new BigDecimal("0.50");
        basket.cardPromotions = new ArrayList<>(List.of(promotion));
        return basket;
    }
}
