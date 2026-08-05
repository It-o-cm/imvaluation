package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating Appliers for "N+M" type offers (e.g., "Buy 2, get 1 cheapest 50% off").
 * <p>
 * This factory retrieves offers of type "N+M" from the database and builds corresponding
 * {@link NPlusMOfferApplier} instances.
 * <p>
 * The JSON specification must contain:
 * <ul>
 *   <li>"targetEans": List of EAN strings included in the offer (or a single String).</li>
 *   <li>"quantityToPay" (N): The quantity of items to pay for at full price.</li>
 *   <li>"discountedQuantity" (M): The quantity of items to apply the discount to.</li>
 *   <li>"selectionStrategy": "CHEAPEST" or "MOST_EXPENSIVE" (Determines which items get the discount).</li>
 *   <li>"discountType": "PERCENTAGE" or "FIXED_AMOUNT".</li>
 *   <li>"discountValue": The value of the discount.
 *       <ul>
 *         <li>If PERCENTAGE: value is 50.0 for 50%.</li>
 *         <li>If FIXED_AMOUNT: value is the total discount applied to the whole bundle of discounted items (e.g., 5.00).</li>
 *       </ul>
 *   </li>
 * </ul>
 */
@ApplicationScoped
public class NPlusMOfferFactory implements OfferApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "N+M";

    /**
     * Returns the offer type handled by this factory.
     *
     * @return The "N+M" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the N+M specification.
     *
     * @return The JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "N Plus M Offer Specification",
      "description": "Defines the rules for N+M type offers.",
      "type": "object",
      "required": [
        "targetEans",
        "quantityToPay",
        "discountedQuantity",
        "selectionStrategy",
        "discountType",
        "discountValue"
      ],
      "properties": {
        "targetEans": {
          "oneOf": [
            { "type": "string" },
            { "type": "array", "items": { "type": "string" } }
          ],
          "description": "List of EANs included in the offer.",
          "x-widget": "ean-list",
          "x-label": "Eligible products"
        },
        "quantityToPay": {
          "type": "integer",
          "description": "The quantity N to pay for.",
          "minimum": 0,
          "x-widget": "quantity",
          "x-label": "Quantity to pay (N)"
        },
        "discountedQuantity": {
          "type": "integer",
          "description": "The quantity M to discount.",
          "minimum": 0,
          "x-widget": "quantity",
          "x-label": "Discounted quantity (M)"
        },
        "selectionStrategy": {
          "type": "string",
          "enum": ["CHEAPEST", "MOST_EXPENSIVE"],
          "description": "Strategy to select items for discount.",
          "x-label": "Selection strategy"
        },
        "discountType": {
          "type": "string",
          "enum": ["PERCENTAGE", "FIXED_AMOUNT"],
          "description": "Type of discount calculation.",
          "x-label": "Discount type"
        },
        "discountValue": {
          "type": "number",
          "description": "The discount value.",
          "x-widget": "discount-value",
          "x-label": "Discount value",
          "x-unit-from": "discountType"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Enumeration for the strategy used to select which items in the bundle receive the discount.
     */
    public enum SelectionStrategy {
        /** Selects the items with the lowest unit price to apply the discount. */
        CHEAPEST,
        /** Selects the items with the highest unit price to apply the discount. */
        MOST_EXPENSIVE
    }

    /**
     * Enumeration for the type of discount calculation.
     */
    public enum DiscountType {
        /** Discount calculated as a percentage of the unit price. */
        PERCENTAGE,
        /** Discount calculated as a fixed monetary amount for the whole lot of discounted items. */
        FIXED_AMOUNT
    }

    /**
     * Builds a collection of offer appliers based on the provided basket evaluation.
     * <p>
     * Optimizes performance by checking basket content beforehand and only creating appliers
     * for offers targeting products present in the basket.
     *
     * @param basketEvaluation The evaluation context containing the basket.
     * @return A collection of {@link NPlusMOfferApplier} instances relevant to the basket.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create N+M appliers without a valid basket context.");

        // Optimization: Pre-calculate EANs present in the basket.
        Map<String, Basket.Item> basketItems = basket.items.stream()
                .collect(Collectors.toMap(item -> item.produceEan, item -> item, (first, second) -> first));

        Store store = basketEvaluation.getStore();
        Collection<Offer> offers = getOffers(basketEvaluation, basketItems.keySet(), "N+M");

        for (Offer offer : offers) {
            processOffer(offer, basketItems, appliers, store);
        }
        return appliers;
    }

    /**
     * Processes a single offer and adds a corresponding applier if applicable.
     *
     * @param offer       The offer to process.
     * @param basketItems The map of EANs to basket items present in the basket.
     * @param appliers    The list to which new appliers will be added.
     * @param store       The store context.
     */
    private void processOffer(Offer offer, Map<String, Basket.Item> basketItems, List<OfferApplier> appliers, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            // Parse List of Target EANs
            Map<String, Basket.Item> targetItems = new HashMap<>();
            JsonNode eansNode = spec.get("targetEans");

            if (eansNode.isTextual()) {
                String ean = eansNode.asText();
                if (basketItems.containsKey(ean)) {
                    targetItems.put(ean, basketItems.get(ean));
                }
            } else {
                for (JsonNode node : eansNode) {
                    String ean = node.asText();
                    if (basketItems.containsKey(ean)) {
                        targetItems.put(ean, basketItems.get(ean));
                    }
                }
            }

            // Parse Numeric and Enum Values
            int quantityToPay = spec.get("quantityToPay").asInt();
            int discountedQuantity = spec.get("discountedQuantity").asInt();
            String strategyStr = spec.get("selectionStrategy").asText();
            String typeStr = spec.get("discountType").asText();
            double value = spec.get("discountValue").asDouble();

            if (!targetItems.isEmpty()) {
                SelectionStrategy strategy = SelectionStrategy.valueOf(strategyStr);
                DiscountType discountType = DiscountType.valueOf(typeStr);
                appliers.add(new NPlusMOfferApplier(
                        offer.code,
                        targetItems,
                        quantityToPay,
                        discountedQuantity,
                        strategy,
                        discountType,
                        value,
                        store
                ));
            }
        });
    }

    /**
     * Specific applier for a single N+M offer configuration with mixed products.
     */
    public static class NPlusMOfferApplier extends OfferApplier implements ProductAwareOfferApplier {

        private final String code;
        private final Map<String, Basket.Item> targetItems;
        private final int quantityToPay;
        private final int discountedQuantity;
        private final SelectionStrategy selectionStrategy;
        private final DiscountType discountType;
        private final double discountValue;
        private final Store store;

        /** Cache for prices to avoid repeated DB lookups during sorting */
        private final Map<String, Price> priceCache = new HashMap<>();

        /**
         * Constructs a new N+M Offer Applier.
         *
         * @param code               The offer code.
         * @param targetItems        The items to evaluate.
         * @param quantityToPay      The quantity N to pay for.
         * @param discountedQuantity The quantity M to discount.
         * @param selectionStrategy  The strategy for selecting items to discount.
         * @param discountType       The type of discount.
         * @param discountValue      The value of the discount.
         * @param store              The store context.
         */
        public NPlusMOfferApplier(String code, Map<String, Basket.Item> targetItems, int quantityToPay, int discountedQuantity,
                                  SelectionStrategy selectionStrategy, DiscountType discountType, double discountValue, Store store) {
            this.code = code;
            this.targetItems = targetItems;
            this.quantityToPay = quantityToPay;
            this.discountedQuantity = discountedQuantity;
            this.selectionStrategy = selectionStrategy;
            this.discountType = discountType;
            this.discountValue = discountValue;
            this.store = store;
            // Pre-load prices for all target products for efficiency and sorting
            for (Basket.Item item : this.targetItems.values()) {
                Product product = item.getProduct();
                Price price = item.getPrice(store, PriceUsage.BASE_FOR_DISCOUNT);
                priceCache.put(product.ean, price);
            }
        }

        /**
         * Applies the offer to the evaluation context using a bulk optimization strategy.
         * <p>
         * Maximizes efficiency and gain by processing all items globally:
         * <ol>
         *   <li>Gathers and sorts all candidate items.</li>
         *   <li>Picks the total required quantity in a single pass.</li>
         *   <li>Distributes items into bundles and creates applications.</li>
         * </ol>
         *
         * @param evaluation The current state of the basket evaluation.
         * @return A collection of {@link NPlusMApplication} representing the applied bundles.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) {
            // 1. Gather and Sort Items Globally
            List<Basket.Item> sortedCandidates = getSortedCandidates(evaluation);
            if (sortedCandidates.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. Calculate Max Bundles on the LIVE pool. A candidate may have been consumed
            //    already by a higher-priority offer (e.g. a manual gesture), so counting the
            //    frozen target quantities would over-count and build an empty application.
            double totalAvailableQty = 0.0;
            for (Basket.Item candidate : sortedCandidates) {
                totalAvailableQty += evaluation.remainingQuantity(candidate.produceEan);
            }
            int bundleSize = quantityToPay + discountedQuantity;

            int maxBundles = (int) (totalAvailableQty / bundleSize);
            if (maxBundles == 0) {
                return Collections.emptyList();
            }
            double totalQtyToConsume = maxBundles * bundleSize;

            // 3. Bulk Pick: Consume items from the evaluation
            List<Basket.Item> pickedPool = pickItemsFromEvaluation(evaluation, sortedCandidates, totalQtyToConsume);

            // 4. Nothing actually consumed: emit no application rather than an empty one.
            if (pickedPool.isEmpty()) {
                return Collections.emptyList();
            }

            // 5. Construct Applications (Batch Construction)
            return createApplicationsFromPool(pickedPool);
        }

        /**
         * Retrieves and sorts items available in the basket based on the selection strategy.
         *
         * @param evaluation The basket evaluation.
         * @return A sorted list of candidate items.
         */
        List<Basket.Item> getSortedCandidates(BasketEvaluation evaluation) {
            List<Basket.Item> candidates = new ArrayList<>();
            for (Basket.Item item : targetItems.values()) {
                candidates.add(item);
            }
            if (candidates.isEmpty()) {
                return candidates;
            }
            // Sort by Unit Price
            Comparator<Basket.Item> comparator = Comparator.comparingDouble(item -> {
                Price p = priceCache.get(item.produceEan);
                return p.priceExcludingTax.doubleValue();
            });
            if (selectionStrategy == SelectionStrategy.MOST_EXPENSIVE) {
                comparator = comparator.reversed();
            }
            candidates.sort(comparator);
            return candidates;
        }

        /**
         * Picks the specified quantity from the evaluation map following the order of the sorted candidates.
         *
         * @param evaluation        The basket evaluation.
         * @param sortedCandidates  The list of items defining the priority order.
         * @param totalQtyToConsume The total quantity required.
         * @return A list of successfully picked items.
         */
        List<Basket.Item> pickItemsFromEvaluation(BasketEvaluation evaluation, List<Basket.Item> sortedCandidates, double totalQtyToConsume) {
            List<Basket.Item> pickedPool = new ArrayList<>();
            double remainingToConsume = totalQtyToConsume;
            for (Basket.Item candidate : sortedCandidates) {
                // Live remaining quantity for this EAN, across its price entries.
                double liveQty = evaluation.remainingQuantity(candidate.produceEan);
                if (liveQty <= 0) continue;
                double take = Math.min(liveQty, remainingToConsume);
                // pick may split across several prices of the same EAN; keep every slice.
                List<Basket.Item> slices = evaluation.pick(take, candidate.produceEan);
                for (Basket.Item slice : slices) {
                    pickedPool.add(slice);
                    remainingToConsume -= slice.quantity;
                }
            }
            return pickedPool;
        }

        /**
         * Distributes the picked pool of items into individual bundle applications.
         * <p>
         * Items are allocated to "Discounted" slots first, then "Paid" slots, cycling through
         * bundles until the pool is exhausted.
         *
         * @param pickedPool The list of items picked from the basket.
         * @return A collection of offer applications.
         */
        private Collection<OfferApplication> createApplicationsFromPool(List<Basket.Item> pickedPool) {
            List<OfferApplication> applications = new ArrayList<>();
            List<Basket.Item> currentPaidItems = new ArrayList<>();
            List<Basket.Item> currentDiscountedItems = new ArrayList<>();
            int neededDisc = discountedQuantity;
            int neededPaid = quantityToPay;
            for (Basket.Item item : pickedPool) {
                double remainingItemQty = item.quantity;
                // Distribute the quantity of the current item across available slots
                while (remainingItemQty > 0) {
                    if (neededDisc > 0) {
                        // Fill Discounted Slot
                        double take = Math.min(remainingItemQty, neededDisc);
                        addItemToBundle(currentDiscountedItems, item, take);
                        neededDisc -= take;
                        remainingItemQty -= take;
                    } else if (neededPaid > 0) {
                        // Fill Paid Slot
                        double take = Math.min(remainingItemQty, neededPaid);
                        addItemToBundle(currentPaidItems, item, take);
                        neededPaid -= take;
                        remainingItemQty -= take;
                    } else {
                        // Bundle is complete, add it and reset counters
                        applications.add(new NPlusMApplication(code, currentPaidItems, currentDiscountedItems, store, discountType, discountValue));
                        currentPaidItems.clear();
                        currentDiscountedItems.clear();
                        neededDisc = discountedQuantity;
                        neededPaid = quantityToPay;
                    }
                }
            }
            // Add the final bundle if it exists
            applications.add(new NPlusMApplication(code, currentPaidItems, currentDiscountedItems, store, discountType, discountValue));
            return applications;
        }

        /**
         * Helper to create a split item and add it to a bundle list.
         *
         * @param bundleList The list to add the item to.
         * @param source     The original item.
         * @param quantity   The quantity to take.
         */
        private void addItemToBundle(List<Basket.Item> bundleList, Basket.Item source, double quantity) {
            Basket.Item split = new Basket.Item();
            split.produceEan = source.produceEan;
            split.lineId = source.lineId;
            split.quantity = quantity;
            bundleList.add(split);
        }

        /**
         * Determines if this applier is applicable to the given product.
         *
         * @param product The product to check applicability against.
         * @return True if this applier can be applied to the provided product; false otherwise.
         */
        @Override
        public boolean isApplicable(Product product) {
            return targetItems.get(product.ean) != null;
        }
    }

    /**
     * Represents the result of applying an N+M offer to a specific bundle of items.
     * Handles both paid items and discounted items using the engine's {@link AmountEvaluation} utilities.
     */
    public static class NPlusMApplication implements ProductAwareOfferApplication {
        private final String code;
        private final List<Basket.Item> paidItems;
        private final List<Basket.Item> discountedItems;
        private final Store store;
        private final DiscountType discountType;
        private final double discountValue;

        /**
         * Constructs an application for a specific bundle.
         *
         * @param code            The offer code.
         * @param paidItems       The items paid at full price.
         * @param discountedItems The items receiving the discount.
         * @param store           The store context.
         * @param discountType    The type of discount to apply.
         * @param discountValue   The value of the discount.
         */
        public NPlusMApplication(String code, List<Basket.Item> paidItems, List<Basket.Item> discountedItems,
                                 Store store, DiscountType discountType, double discountValue) {
            this.code = code;
            this.paidItems = paidItems;
            this.discountedItems = discountedItems;
            this.store = store;
            this.discountType = discountType;
            this.discountValue = discountValue;
        }

        /**
         * Calculates the total price for the applied offer.
         * <p>
         * Utilizes {@link AmountEvaluation} helper methods to handle Unit vs. Weighted logic
         * and VAT calculations automatically.
         *
         * @return A {@link AmountEvaluation} containing the calculated prices.
         */
        @Override
        public AmountEvaluation getAmount() {
            // 1. Calculate Full Price for Paid Items
            AmountEvaluation totalPaid = AmountEvaluation.getAmount(paidItems, store, PriceUsage.BASE_FOR_DISCOUNT);
            AmountEvaluation totalDiscountedFull = AmountEvaluation.getAmount(discountedItems, store, PriceUsage.BASE_FOR_DISCOUNT);

            // 2. Apply Discount to the Discounted Block
            AmountEvaluation totalDiscountedNet;
            if (totalDiscountedFull.amountIncludingTax.compareTo(BigDecimal.ZERO) > 0) {
                if (discountType == DiscountType.PERCENTAGE) {
                    // PriceEvaluation.multiply(efficiency) returns price * (1 - efficiency)
                    totalDiscountedNet = totalDiscountedFull.multiply(discountValue / 100.0);
                } else { // FIXED_AMOUNT
                    // The discount applies to the whole block.
                    BigDecimal discountTtc = BigDecimal.valueOf(discountValue);
                    // Cap discount at the total price of the block
                    if (discountTtc.compareTo(totalDiscountedFull.amountIncludingTax) > 0) {
                        discountTtc = totalDiscountedFull.amountIncludingTax;
                    }
                    // Calculate HT using the block's average VAT rate
                    BigDecimal multiplier = BigDecimal.ONE.add(totalDiscountedFull.vatRate);
                    BigDecimal discountHt = discountTtc.divide(multiplier, 2, RoundingMode.HALF_UP);
                    AmountEvaluation discountObj = new AmountEvaluation(discountHt, discountTtc, totalDiscountedFull.vatRate);
                    // Subtract discount from full price
                    totalDiscountedNet = totalDiscountedFull.subtract(discountObj);
                }
            } else {
                totalDiscountedNet = totalDiscountedFull;
            }
            // 3. Sum Paid and Discounted Net
            return totalPaid.add(totalDiscountedNet);
        }

        /**
         * Returns the list of all items covered by this offer application.
         *
         * @return A list containing both paid and discounted items.
         */
        @Override
        @JsonIgnore
        public Collection<Basket.Item> getItems() {
            List<Basket.Item> allItems = new ArrayList<>(paidItems);
            allItems.addAll(discountedItems);
            return allItems;
        }

        /**
         * Values this offer's items, honouring the paid / discounted split.
         * <p>
         * N+M selects items by price, so a single pro-rata over all items would misstate the
         * result: the paid items keep their full catalog price, while the discounted items
         * share the net discounted amount. Each block is therefore distributed on its own —
         * the paid block over its full price, the discounted block over its net price — so
         * every item's amount reflects whether it was paid or discounted. The two blocks
         * together sum to {@link #getAmount()}.
         *
         * @return The valued items, one per source line.
         */
        @Override
        @JsonProperty("items")
        public java.util.List<BasketEvaluation.Item> getValuedItems() {
            java.util.List<BasketEvaluation.Item> valued = new ArrayList<>();

            if (!paidItems.isEmpty()) {
                AmountEvaluation paidTotal =
                        AmountEvaluation.getAmount(paidItems, store, PriceUsage.BASE_FOR_DISCOUNT);
                valued.addAll(ItemValuation.distribute(paidTotal, paidItems, store));
            }

            if (!discountedItems.isEmpty()) {
                // Net amount of the discounted block = full offer total minus the paid block.
                AmountEvaluation paidTotal =
                        AmountEvaluation.getAmount(paidItems, store, PriceUsage.BASE_FOR_DISCOUNT);
                AmountEvaluation discountedNet = getAmount().subtract(paidTotal);
                valued.addAll(ItemValuation.distribute(discountedNet, discountedItems, store));
            }

            return valued;
        }

        /**
         * Returns a string representation of the offer type and parameters.
         *
         * @return A descriptive string of the offer application.
         */
        @Override
        public String getType() {
            return "Mixed Bundle Promo: " + code;
        }

        /**
         * Retrieves the price evaluation for a specific product within this offer application.
         * <p>
         * If the product is in the paid list, returns full price.
         * If in the discounted list, applies the discount logic (percentage or pro-rata fixed amount).
         *
         * @param product The product for which to retrieve the amount evaluation.
         * @return The {@link AmountEvaluation} for the product, or null if not present.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            // Check Paid Items (Full Price)
            AmountEvaluation paidPart = AmountEvaluation.getAmountForProduct(paidItems, product.ean, store, PriceUsage.BASE_FOR_DISCOUNT);
            if (paidPart.amountIncludingTax.compareTo(BigDecimal.ZERO) != 0) {
                return paidPart;
            }

            // Check Discounted Items
            AmountEvaluation productFullPrice = AmountEvaluation.getAmountForProduct(discountedItems, product.ean, store, PriceUsage.BASE_FOR_DISCOUNT);
            if (productFullPrice.amountIncludingTax.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }

            // Apply Discount
            if (discountType == DiscountType.PERCENTAGE) {
                return productFullPrice.multiply(discountValue / 100.0);
            } else { // FIXED_AMOUNT
                // We need to distribute the global fixed discount pro-rata
                // 1. Calculate the total full price of the discounted block
                AmountEvaluation blockFullPrice = AmountEvaluation.getAmount(discountedItems, store, PriceUsage.BASE_FOR_DISCOUNT);

                // 2. Calculate Total Discount Object for the block
                BigDecimal totalDiscTtc = BigDecimal.valueOf(discountValue);
                if (totalDiscTtc.compareTo(blockFullPrice.amountIncludingTax) > 0) {
                    totalDiscTtc = blockFullPrice.amountIncludingTax;
                }
                BigDecimal multiplier = BigDecimal.ONE.add(blockFullPrice.vatRate);
                BigDecimal totalDiscHt = totalDiscTtc.divide(multiplier, 2, RoundingMode.HALF_UP);
                AmountEvaluation totalDiscount = new AmountEvaluation(totalDiscHt, totalDiscTtc, blockFullPrice.vatRate);

                // 3. Calculate Pro-rata discount for this specific product
                BigDecimal ratioHt = productFullPrice.amountExcludingTax.divide(blockFullPrice.amountExcludingTax, 6, RoundingMode.HALF_UP);
                BigDecimal ratioTtc = productFullPrice.amountIncludingTax.divide(blockFullPrice.amountIncludingTax, 6, RoundingMode.HALF_UP);
                BigDecimal productDiscHt = totalDiscount.amountExcludingTax.multiply(ratioHt).setScale(2, RoundingMode.HALF_UP);
                BigDecimal productDiscTtc = totalDiscount.amountIncludingTax.multiply(ratioTtc).setScale(2, RoundingMode.HALF_UP);
                AmountEvaluation productDiscount = new AmountEvaluation(productDiscHt, productDiscTtc, productFullPrice.vatRate);

                return productFullPrice.subtract(productDiscount);
            }
        }

        /**
         * Retrieves the total quantity of a specific product covered by this offer application.
         *
         * @param product The product for which to retrieve the quantity.
         * @return The quantity of the specified product.
         */
        @Override
        public double getProductQuantity(Product product) {
            double qty = 0.0;
            for (Basket.Item item : paidItems) {
                if (item.produceEan.equals(product.ean)) qty += item.quantity;
            }
            for (Basket.Item item : discountedItems) {
                if (item.produceEan.equals(product.ean)) qty += item.quantity;
            }
            return qty;
        }
    }


}