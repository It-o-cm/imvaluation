package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.engine.offers.SrpGuardFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Computes the display valuation of a batch of EANs (spec-valorisation-affichage-ecommerce).
 * <p>
 * It is the "build once, apply n times" of §2bis: it builds a single {@link DisplayContext} for the
 * whole call — resolving the store's in-force offers, their classification and the price fingerprint
 * once — then values each EAN as an independent one-unit mini-basket on its own pseudo-ticket,
 * evaluated against that shared context. The displayed price of a product is, to the cent, what
 * {@code /valuation} would charge a one-unit line of that product with no pushed price (rule 6):
 * the same engine is run, only its offer context is pre-built and its basket-dependent and
 * ticket-level offers are withheld from the one-unit line.
 * <p>
 * Strictly read-only: it never records a {@link com.intermarche.valuation.domain.ValuationTrace},
 * declares no consumption and writes nothing — the engine only reads the referential and the budget
 * counters (spec §2 rule 5, §7 criterion 3). The pseudo-tickets are synthetic, one per product, so
 * an offer's per-ticket ceiling never leaks from one product of the page to the next (§2bis,
 * §7 criterion 5).
 */
@ApplicationScoped
public class DisplayValuationService {

    /**
     * The valuation engine, run once per mini-basket against the shared context.
     */
    @Inject
    ValuationEngine engine;

    /**
     * Values a batch of EANs for display (spec §2, §2bis).
     * <p>
     * The request has already been checked for the EAN cap by the resource. This method resolves
     * the store and effective date (naming any gap), builds the shared context once, then values
     * each EAN in isolation: an unknown EAN is reported on its own entry and the batch continues.
     *
     * @param request the display valuation request (store, date, EANs, optional pushed card data).
     * @return one entry per requested EAN, in request order.
     * @throws DisplayValuationException on a request-level refusal (missing store code, unknown
     *                                   store, missing or unreadable date).
     */
    public List<DisplayValuationEntry> evaluate(DisplayValuationRequest request) {
        if (request.storeCode == null || request.storeCode.isBlank()) {
            throw new DisplayValuationException(400, "The 'storeCode' is required.");
        }
        Store store = Store.findByCode(request.storeCode);
        if (store == null) {
            throw new DisplayValuationException(422,
                    "Store not found for code '" + request.storeCode + "'.");
        }
        LocalDateTime at = resolveDate(request.valuationDate);
        validatePushedCardPromotions(request);
        List<String> eans = request.eans == null ? List.of() : request.eans;
        Set<StoreGroup> groups = StoreGroup.findAllStoreGroups(store);
        DisplayContext context = new DisplayContext(store, groups, at, eans);
        List<DisplayValuationEntry> entries = new ArrayList<>();
        for (String ean : eans) {
            entries.add(valueOne(ean, request, store, at, context));
        }
        return entries;
    }

    /**
     * Resolves the effective instant from the request's valuation date, the start of that day
     * (spec §2). A missing or unreadable date is a named request refusal.
     *
     * @param valuationDate the ISO {@code yyyy-MM-dd} date string.
     * @return the start-of-day instant used for the offer, price and guard context.
     * @throws DisplayValuationException 400 when the date is missing or unreadable.
     */
    private static LocalDateTime resolveDate(String valuationDate) {
        if (valuationDate == null || valuationDate.isBlank()) {
            throw new DisplayValuationException(400, "The 'valuationDate' is required.");
        }
        try {
            return LocalDate.parse(valuationDate.trim()).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new DisplayValuationException(400,
                    "Unreadable 'valuationDate' '" + valuationDate + "'. Expected ISO yyyy-MM-dd.");
        }
    }

    /**
     * Validates the pushed card promotions once for the whole call, with the same cross-rules as
     * the basket (at most one promotion per EAN, a PERCENT value at most 1): a caller error is a
     * named 400 refusal, since imfid guarantees those invariants (spec §2).
     *
     * @param request the request carrying the optional pushed promotions.
     * @throws DisplayValuationException 400 when the pushed promotions violate a cross-rule.
     */
    private static void validatePushedCardPromotions(DisplayValuationRequest request) {
        if (request.cardPromotions == null) {
            return;
        }
        Basket probe = new Basket();
        probe.cardPromotions = request.cardPromotions;
        try {
            probe.validateCardPromotions();
        } catch (IllegalArgumentException e) {
            throw new DisplayValuationException(400, e.getMessage());
        }
    }

    /**
     * Values a single EAN as a one-unit mini-basket on its own pseudo-ticket (spec §2bis).
     * <p>
     * The mini-basket is closed (so the AT_TOTAL wave — and thus the EGAlim/SRP guards — runs),
     * carries the pushed card number and promotions (the card-promotion advantage matches by EAN,
     * a CAGNOTTE entry being ignored), and prices at the valuation date. A per-EAN referential gap
     * is caught and reported on the entry; the batch is not interrupted.
     *
     * @param ean     the product EAN.
     * @param request the request, for the pushed card data.
     * @param store   the resolved store.
     * @param at      the effective instant (also the price date).
     * @param context the shared, once-built context.
     * @return the valued entry, or an error entry naming the gap.
     */
    private DisplayValuationEntry valueOne(String ean, DisplayValuationRequest request,
                                           Store store, LocalDateTime at, DisplayContext context) {
        Basket basket = new Basket();
        basket.storeCode = request.storeCode;
        basket.closed = Boolean.TRUE;
        basket.cardNumber = request.cardNumber;
        basket.cardPromotions = request.cardPromotions;
        Basket.Item item = new Basket.Item();
        item.produceEan = ean;
        item.lineId = ean;
        item.quantity = BigDecimal.ONE;
        item.priceDate = at.toString();
        basket.items = List.of(item);
        BasketEvaluation evaluation;
        try {
            evaluation = engine.evaluate(basket, context);
        } catch (ReferentialException e) {
            return DisplayValuationEntry.error(ean, e.getMessage(), context.priceVersion());
        }
        if (evaluation.getToEvaluate() != null && !evaluation.getToEvaluate().isEmpty()) {
            return DisplayValuationEntry.error(ean,
                    "No offer could value EAN '" + ean + "'.", context.priceVersion());
        }
        return map(ean, evaluation, context);
    }

    /**
     * Maps a one-unit mini-basket evaluation to its display entry (spec §2 output, rules 2, 4, 6).
     * <p>
     * {@code basePrice} is the gross the offers posed for the line (the reference price when a
     * markdown repriced it, the current price otherwise); {@code displayedPrice} is the net total
     * of the mini-basket, which — the ticket-level and basket-dependent offers having been withheld
     * from the one-unit line, and the EGAlim/SRP corrections being folded into the total — is
     * exactly the product's displayed price with its legal floors respected.
     *
     * @param ean        the product EAN.
     * @param evaluation the mini-basket evaluation.
     * @param context    the shared context (badges, fingerprint).
     * @return the valued display entry.
     */
    private DisplayValuationEntry map(String ean, BasketEvaluation evaluation, DisplayContext context) {
        BigDecimal grossExcl = BigDecimal.ZERO;
        BigDecimal grossIncl = BigDecimal.ZERO;
        BigDecimal rate = BigDecimal.ZERO;
        boolean grossFound = false;
        for (OfferApplication application : evaluation.getOffers()) {
            for (BasketEvaluation.Item valued : application.getValuedItems()) {
                if (ean.equals(valued.produceEan) && valued.amount != null) {
                    grossExcl = grossExcl.add(valued.amount.amountExcludingTax);
                    grossIncl = grossIncl.add(valued.amount.amountIncludingTax);
                    if (valued.amount.vatRate != null) {
                        rate = valued.amount.vatRate;
                    }
                    grossFound = true;
                }
            }
        }
        if (!grossFound) {
            return DisplayValuationEntry.error(ean,
                    "No offer could value EAN '" + ean + "'.", context.priceVersion());
        }
        AmountEvaluation total = evaluation.getTotalPrice();
        BigDecimal netExcl = total == null ? grossExcl : total.amountExcludingTax;
        BigDecimal netIncl = total == null ? grossIncl : total.amountIncludingTax;
        DisplayValuationEntry entry = new DisplayValuationEntry();
        entry.ean = ean;
        entry.vatRate = rate.setScale(4, RoundingMode.HALF_UP);
        entry.basePrice = new AmountEvaluation(grossExcl, grossIncl, rate);
        entry.displayedPrice = new AmountEvaluation(netExcl, netIncl, rate);
        BigDecimal discountExcl = grossExcl.subtract(netExcl);
        BigDecimal discountIncl = grossIncl.subtract(netIncl);
        if (discountIncl.signum() != 0 || discountExcl.signum() != 0) {
            entry.immediateDiscount = new AmountEvaluation(discountExcl, discountIncl, rate);
        }
        entry.unitPriceMeasure = unitPriceMeasure(ean, netIncl);
        entry.offerBadges = context.badgesFor(ean);
        entry.floorGuard = floorGuard(ean, evaluation, netIncl);
        entry.priceVersion = context.priceVersion();
        return entry;
    }

    /**
     * Computes the price per unit of measure from the displayed price and the product's measure
     * reference (spec §4): {@code referenceWeight} first, then {@code referenceVolume}; absent when
     * the product carries neither. Rounded to the cent HALF_UP, expressed in the product's
     * {@code unitName}.
     *
     * @param ean      the product EAN.
     * @param netIncl  the displayed price, tax included.
     * @return the unit price of measure, or {@code null} when the product has no reference.
     */
    private static DisplayValuationEntry.UnitPrice unitPriceMeasure(String ean, BigDecimal netIncl) {
        Product product = Product.findByEan(ean);
        if (product == null) {
            return null;
        }
        BigDecimal reference = null;
        if (product.referenceWeight != null && product.referenceWeight.signum() > 0) {
            reference = product.referenceWeight;
        } else if (product.referenceVolume != null && product.referenceVolume.signum() > 0) {
            reference = product.referenceVolume;
        }
        if (reference == null) {
            return null;
        }
        BigDecimal value = netIncl.divide(reference, 2, RoundingMode.HALF_UP);
        return new DisplayValuationEntry.UnitPrice(value, product.unitName);
    }

    /**
     * Resolves which legal floor, if any, held the displayed price (spec §2 rule 4): the SRP floor
     * when the guard clamped the line (it locks last, after EGAlim), otherwise the EGAlim ceiling
     * when it corrected the line. The displayed price is already clamped — this only reports which
     * floor bound it and the floor value.
     *
     * @param ean        the product EAN.
     * @param evaluation the mini-basket evaluation carrying the guard records.
     * @param netIncl    the displayed price, tax included (the EGAlim-bound floor).
     * @return the floor guard, or {@code null} when no floor constrained the price.
     */
    private static DisplayValuationEntry.FloorGuard floorGuard(String ean, BasketEvaluation evaluation,
                                                               BigDecimal netIncl) {
        if (evaluation.getSrp() != null) {
            for (BasketEvaluation.SrpLine line : evaluation.getSrp()) {
                if (ean.equals(line.ean) && SrpGuardFactory.REASON_FLOOR_CLAMPED.equals(line.reasonCode)) {
                    return new DisplayValuationEntry.FloorGuard("SRP", line.floorPrice);
                }
            }
        }
        if (evaluation.getEgalim() != null) {
            for (BasketEvaluation.EgalimLine line : evaluation.getEgalim()) {
                if (ean.equals(line.ean) && line.correction != null && line.correction.signum() > 0) {
                    return new DisplayValuationEntry.FloorGuard("EGALIM",
                            netIncl.setScale(2, RoundingMode.HALF_UP));
                }
            }
        }
        return null;
    }
}
