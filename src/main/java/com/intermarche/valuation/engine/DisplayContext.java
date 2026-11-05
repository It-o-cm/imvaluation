package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.engine.offers.AmountPerItemDiscountFactory;
import com.intermarche.valuation.engine.offers.EgalimGuardFactory;
import com.intermarche.valuation.engine.offers.MixedBundleOfferFactory;
import com.intermarche.valuation.engine.offers.NPlusMOfferFactory;
import com.intermarche.valuation.engine.offers.NewPriceDiscountFactory;
import com.intermarche.valuation.engine.offers.SrpGuardFactory;
import com.intermarche.valuation.engine.offers.TieredDiscountFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The valuation context of one {@code POST /display-valuation} call, built ONCE and applied to the
 * n per-product mini-baskets (spec-valorisation-affichage-ecommerce §2bis).
 * <p>
 * The display endpoint is a very special multiple basket: n mini-baskets of a single unit, each on
 * its own pseudo-ticket, evaluated against a single shared context so a page of n cards costs one
 * context construction rather than n. This object is that shared context. It resolves, for a given
 * {@code (store, valuationDate)}, every in-force offer of the store once — across every type — then
 * classifies each offer and serves subsets to the engine:
 * <ul>
 *   <li><b>PRICING</b> offers (unconditional product markdowns and the EGAlim/SRP guards) are
 *       served to {@link EngineTrait#getOffers} and shape {@code displayedPrice} exactly as
 *       {@code /valuation} would on a one-unit line (rule 6, rule 2);</li>
 *   <li><b>BADGE</b> offers (basket-composition-dependent: N+M, tiered, mixed bundle, and any offer
 *       carrying a {@code MINIMUM_QUANTITY}/{@code MINIMUM_AMOUNT} trigger) are ALSO served to the
 *       evaluation — so the engine runs its potential-promotion machinery (the upsell suggestions a
 *       normal ticket carries: "buy one more and get…") on each mini-basket, exactly as on a real
 *       basket. On a single unit they never fire a discount (their trigger/composition is not met),
 *       so they leave {@code displayedPrice} untouched and surface as {@code offerBadges} labels
 *       (rule 1, §2bis);</li>
 *   <li><b>IGNORED</b> offers (ticket-level discounts, anti-waste, instrument grants, loyalty,
 *       delivery, …) are withheld from the evaluation: they would otherwise reduce a one-unit price
 *       though they are not a product markdown (rule 2, rule 3). They shape neither the price nor a
 *       badge.</li>
 * </ul>
 * The same in-force resolution the engine finders perform is reused verbatim — the eligibility
 * logic is not duplicated, only executed once and shared.
 * <p>
 * The context is resolved against the caller's {@code valuationDate}, never the engine clock, so a
 * display valuation is reproducible for a past or future effective date. It holds NO basket state:
 * each mini-basket carries its own {@link BasketEvaluation}; this object is read-only and shared.
 */
public class DisplayContext {

    /**
     * Shared JSON mapper for reading offer specifications (trigger kinds, badge labels).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Offer types whose unconditional markdown applies to a single unit and must therefore be
     * reflected in {@code displayedPrice} (rule 2), plus the EGAlim and SRP guards whose floor must
     * constrain it (rule 4). Served to the engine for the mini-basket evaluation.
     */
    private static final Set<String> PRICING_TYPES = Set.of(
            NewPriceDiscountFactory.OFFER_TYPE,
            AmountPerItemDiscountFactory.OFFER_TYPE,
            EgalimGuardFactory.OFFER_TYPE,
            SrpGuardFactory.OFFER_TYPE);

    /**
     * Offer types whose effect depends on the basket composition and is unknowable on a single
     * unit: they are surfaced as badges, never priced (rule 1).
     */
    private static final Set<String> BADGE_TYPES = Set.of(
            NPlusMOfferFactory.OFFER_TYPE,
            TieredDiscountFactory.OFFER_TYPE,
            MixedBundleOfferFactory.OFFER_TYPE);

    /**
     * How one in-force offer contributes to the display valuation.
     */
    enum Classification {
        /** Served to the mini-basket evaluation; shapes {@code displayedPrice}. */
        PRICING,
        /** Served to the evaluation (so its potential-promotion machinery runs) and surfaced as an
         *  {@code offerBadge}; never fires a discount on a single unit, so never priced. */
        BADGE,
        /** Withheld from the evaluation; neither priced nor badged (ticket-level, grants, delivery, …). */
        IGNORED
    }

    /**
     * The store the whole call is scoped to.
     */
    private final Store store;

    /**
     * The store's resolved group hierarchy, the second porter of an offer's reach.
     */
    private final Set<StoreGroup> storeGroups;

    /**
     * The effective instant the context is resolved at — the caller's {@code valuationDate} at the
     * start of its day, never the engine clock.
     */
    private final LocalDateTime at;

    /**
     * The classification of every distinct in-force offer of the store, resolved once.
     */
    private final Map<Offer, Classification> classification = new IdentityHashMap<>();

    /**
     * The offers served store-wide (every classification but IGNORED), the base served to the
     * no-EAN {@link EngineTrait#getOffers(BasketEvaluation, String)} overload used by the guards.
     */
    private final List<Offer> storeWideServed = new ArrayList<>();

    /**
     * The offers served per EAN (every classification but IGNORED) that target at least one
     * requested EAN, the base served to the per-EAN
     * {@link EngineTrait#getOffers(BasketEvaluation, Collection, String)} overload. The
     * basket-dependent ones are included so the engine's potential-promotion machinery runs; they
     * do not fire a discount on a single unit.
     */
    private final List<Offer> eanServed = new ArrayList<>();

    /**
     * The BADGE offers that target at least one requested EAN, the source of the per-product
     * {@code offerBadges}.
     */
    private final List<Offer> eanBadges = new ArrayList<>();

    /**
     * The opaque price+offer fingerprint of {@code (store, valuationDate)}, computed once.
     */
    private final String priceVersion;

    /**
     * Builds the context for a whole call: resolves the store's in-force offers once across every
     * type, classifies them, and computes the fingerprint (spec §2bis, §2 rule 5).
     *
     * @param store         the store, already resolved by the caller (non-null).
     * @param storeGroups   the store's resolved group hierarchy (never null).
     * @param at            the effective instant (the caller's valuation date, start of day).
     * @param requestedEans the full set of EANs of the call, used to resolve the EAN-targeted
     *                      offers once for every product rather than once per product.
     */
    public DisplayContext(Store store, Set<StoreGroup> storeGroups, LocalDateTime at,
                          Collection<String> requestedEans) {
        this.store = store;
        this.storeGroups = storeGroups;
        this.at = at;
        Set<String> eans = new LinkedHashSet<>(requestedEans);
        Set<Offer> storeWide = new LinkedHashSet<>(Offer.findInForceByStore(store, at));
        storeWide.addAll(Offer.findInForceByStoreGroups(storeGroups, at));
        Set<Offer> eanTargeted = new LinkedHashSet<>(Offer.findInForceByEansAndStore(eans, store, at));
        eanTargeted.addAll(Offer.findInForceByEansAndStoreGroups(eans, storeGroups, at));
        Set<Offer> all = new LinkedHashSet<>(storeWide);
        all.addAll(eanTargeted);
        for (Offer offer : all) {
            classification.put(offer, classify(offer));
        }
        for (Offer offer : storeWide) {
            if (classification.get(offer) != Classification.IGNORED) {
                storeWideServed.add(offer);
            }
        }
        for (Offer offer : eanTargeted) {
            Classification kind = classification.get(offer);
            if (kind != Classification.IGNORED) {
                eanServed.add(offer);
            }
            if (kind == Classification.BADGE) {
                eanBadges.add(offer);
            }
        }
        this.priceVersion = computePriceVersion(store, at, all);
    }

    /**
     * Classifies one in-force offer (spec §2 rules 1-2): basket-composition offers and any
     * minimum-quantity/amount-triggered offer are badges, the unconditional product markdowns and
     * the EGAlim/SRP guards are pricing, everything else (ticket discounts, grants, loyalty,
     * delivery, …) is ignored by the display.
     *
     * @param offer the offer to classify.
     * @return its display classification.
     */
    private static Classification classify(Offer offer) {
        String type = offer.type;
        if (type != null && BADGE_TYPES.contains(type)) {
            return Classification.BADGE;
        }
        if (hasMinimumTrigger(offer)) {
            return Classification.BADGE;
        }
        if (type != null && PRICING_TYPES.contains(type)) {
            return Classification.PRICING;
        }
        return Classification.IGNORED;
    }

    /**
     * Tells whether an offer carries a {@code MINIMUM_QUANTITY} or {@code MINIMUM_AMOUNT} trigger
     * condition — the two basket-composition triggers whose satisfaction cannot be known on a
     * single display unit (spec §2 rule 1). A malformed or absent trigger reads as none.
     *
     * @param offer the offer whose specification is read.
     * @return {@code true} when a minimum-quantity/amount condition is present.
     */
    private static boolean hasMinimumTrigger(Offer offer) {
        if (offer.specification == null) {
            return false;
        }
        try {
            JsonNode trigger = MAPPER.readTree(offer.specification).get("trigger");
            if (trigger == null || trigger.get("conditions") == null) {
                return false;
            }
            for (JsonNode condition : trigger.get("conditions")) {
                JsonNode kind = condition.get("kind");
                if (kind != null && ("MINIMUM_QUANTITY".equals(kind.asText())
                        || "MINIMUM_AMOUNT".equals(kind.asText()))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Returns the served offers of a type reachable store-wide (every classification but IGNORED),
     * for the no-EAN {@link EngineTrait#getOffers(BasketEvaluation, String)} overload (guards and
     * other store-wide factories). The caller applies its own point-of-sale exclusion and ordering,
     * exactly as on the database path.
     *
     * @param type the offer type queried by a factory.
     * @return the matching served offers (every classification but IGNORED).
     */
    List<Offer> servedOffers(String type) {
        List<Offer> matching = new ArrayList<>();
        for (Offer offer : storeWideServed) {
            if (Objects.equals(type, offer.type)) {
                matching.add(offer);
            }
        }
        return matching;
    }

    /**
     * Returns the served offers of a type that target at least one of the given EANs, for the
     * per-EAN {@link EngineTrait#getOffers(BasketEvaluation, Collection, String)} overload. The
     * resolved full-set list is filtered in memory to the mini-basket's EANs, reproducing the
     * per-EAN finder without re-hitting the database. The basket-dependent offers are included so
     * the engine's potential-promotion machinery runs (they do not fire on a single unit).
     *
     * @param eans the mini-basket's EANs (one, in practice).
     * @param type the offer type queried by a factory.
     * @return the matching served offers.
     */
    List<Offer> servedOffers(Collection<String> eans, String type) {
        List<Offer> matching = new ArrayList<>();
        for (Offer offer : eanServed) {
            if (Objects.equals(type, offer.type) && targetsAny(offer, eans)) {
                matching.add(offer);
            }
        }
        return matching;
    }

    /**
     * Returns the basket-dependent offers targeting the given EAN, as display badges (rule 1):
     * each a {@code (type, label, code)} triple, never a price.
     *
     * @param ean the product EAN.
     * @return the badges for this product, in offer-code order for determinism.
     */
    public List<OfferBadge> badgesFor(String ean) {
        Map<String, OfferBadge> byCode = new LinkedHashMap<>();
        List<Offer> ordered = new ArrayList<>(eanBadges);
        ordered.sort(java.util.Comparator.comparing(o -> o.code,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        for (Offer offer : ordered) {
            if (targetsAny(offer, List.of(ean))) {
                byCode.putIfAbsent(offer.code, new OfferBadge(offer.type, badgeLabel(offer), offer.code));
            }
        }
        return new ArrayList<>(byCode.values());
    }

    /**
     * Derives a badge label from an offer: its specification's {@code label} when present,
     * otherwise its code — a stable, non-null display string.
     *
     * @param offer the badge offer.
     * @return the label.
     */
    private static String badgeLabel(Offer offer) {
        if (offer.specification != null) {
            try {
                JsonNode label = MAPPER.readTree(offer.specification).get("label");
                if (label != null && label.isTextual() && !label.asText().isBlank()) {
                    return label.asText();
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // A malformed specification carries no usable label; fall through to the code.
            }
        }
        return offer.code;
    }

    /**
     * Tells whether an offer directly targets any of the given EANs through its {@code eans}
     * element collection — the same reach the EAN finders filter on.
     *
     * @param offer the offer.
     * @param eans  the EANs to test.
     * @return {@code true} when the offer targets at least one.
     */
    private static boolean targetsAny(Offer offer, Collection<String> eans) {
        if (offer.eans == null) {
            return false;
        }
        for (String ean : eans) {
            if (offer.eans.contains(ean)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the opaque price+offer fingerprint of {@code (store, valuationDate)} (spec §2 rule 5):
     * stable for a given store and date, and changed by any update of an in-force price or offer of
     * that store at that date (their {@code updatedAt} moves forward).
     *
     * @return the fingerprint.
     */
    public String priceVersion() {
        return priceVersion;
    }

    /**
     * Computes the fingerprint from the latest modification instant and the count of the store's
     * in-force prices and offers at the date. Deterministic — it uses only persisted timestamps and
     * counts, never the engine clock — so the same {@code (store, date)} always yields the same
     * value and a modified price or offer always yields a new one.
     *
     * @param store the store.
     * @param at    the effective instant.
     * @param offers the store's in-force offers already resolved for the context.
     * @return the opaque fingerprint.
     */
    private static String computePriceVersion(Store store, LocalDateTime at, Collection<Offer> offers) {
        long maxOffer = 0L;
        for (Offer offer : offers) {
            if (offer.updatedAt != null) {
                long millis = offer.updatedAt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                maxOffer = Math.max(maxOffer, millis);
            }
        }
        List<Price> prices = Price.list(
                "store = ?1 and (startDateTime is null or startDateTime <= ?2) and (endDateTime is null or endDateTime > ?2)",
                store, at);
        long maxPrice = 0L;
        for (Price price : prices) {
            if (price.updatedAt != null) {
                long millis = price.updatedAt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                maxPrice = Math.max(maxPrice, millis);
            }
        }
        int hash = Objects.hash(store.code, at.toLocalDate().toString(),
                maxPrice, maxOffer, prices.size(), offers.size());
        return "pv-" + Integer.toHexString(hash);
    }

    /**
     * One display badge for a basket-dependent offer (spec §2): its type, a display label and the
     * offer code, never a unit price. Fields are public for JSON serialization.
     */
    public static class OfferBadge {

        /**
         * The offer type ({@code N+M}, {@code TIERED_DISCOUNT}, {@code MIXED_BUNDLE}, …).
         */
        public String type;

        /**
         * A human-readable label, from the offer specification or its code.
         */
        public String label;

        /**
         * The offer code, for reconciliation with the catalog.
         */
        public String code;

        /**
         * Default constructor for JSON serialization.
         */
        public OfferBadge() {
        }

        /**
         * Builds a badge.
         *
         * @param type  the offer type.
         * @param label the display label.
         * @param code  the offer code.
         */
        public OfferBadge(String type, String label, String code) {
            this.type = type;
            this.label = label;
            this.code = code;
        }
    }
}
