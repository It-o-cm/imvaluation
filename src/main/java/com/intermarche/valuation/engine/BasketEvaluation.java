package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;

import java.util.*;

/**
 * Represents the evaluation state of a {@link Basket}.
 * <p>
 * This class holds a reference to the original basket, a map of items
 * (keyed by EAN) that remain to be processed, a list of offers
 * successfully applied so far, and the store context.
 */
public class BasketEvaluation {

    /**
     * The original basket being evaluated.
     * <p>
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Basket basket;

    /**
     * The store context for this evaluation.
     * <p>
     * Used to resolve prices and determine applicable offer hierarchies.
     * Retrieved automatically using the {@code storeCode} from the basket.
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Store store;

    /**
     * The set of all store groups the store belongs to.
     * <p>
     * This includes direct parent groups and ancestors in the hierarchy.
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Set<StoreGroup> storeGroups;

    /**
     * The map of items remaining to be evaluated.
     * <p>
     * Key: The Product EAN (String).
     * Value: The {@link Basket.Item} with its remaining quantity.
     * <p>
     * This map acts as a working set. Items are copied deeply from the original basket.
     * If multiple lines in the original basket share the same EAN, their quantities are
     * aggregated here to provide a global availability view for the valuation engine.
     * <p>
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Map<String, Basket.Item> toEvaluate;

    /**
     * The map of items available for upcell suggestions.
     */
    @JsonIgnore
    private Map<String, Basket.Item> availableToUpcell = new HashMap<>();;

    /**
     * The set of offer applications calculated during the evaluation.
     * <p>
     * Uses the concrete class {@link OfferApplication}
     */
    private Collection<OfferApplication> offers;

    /**
     * The set of discount applications calculated during the evaluation.
     * <p>
     * Uses the concrete class {@link AdvantageApplication}
     */
    private Collection<AdvantageApplication> advantages;

    /**
     * The total price evaluation of the basket after applying offers and discounts.
     */
    private AmountEvaluation totalPrice;

    /**
     * Constructs a new BasketEvaluation.
     * <p>
     * Initializes the evaluation with the provided basket and empty collections.
     * The store and its group hierarchy are resolved automatically using the
     * {@code storeCode} present in the basket.
     *
     * @param basket The basket to evaluate.
     */
    public BasketEvaluation(Basket basket) {
        this.basket = basket;
        this.toEvaluate = new HashMap<>();
        this.offers = new HashSet<>();
        this.advantages = new HashSet<>();
        // Resolve Store from Basket storeCode
        if (this.basket != null && this.basket.storeCode != null) {
            this.store = Store.findByCode(this.basket.storeCode);
        } else {
            this.store = null;
        }
        // Calculate store groups hierarchy
        if (this.store != null) {
            this.storeGroups = StoreGroup.findAllStoreGroups(this.store);
        } else {
            this.storeGroups = new HashSet<>();
        }
    }

    /**
     * Populates the {@code toEvaluate} map by performing a deep copy of items from the basket.
     * <p>
     * This method ensures that the evaluation engine works on independent copies of items.
     * If the original basket contains multiple lines with the same EAN, their quantities
     * are aggregated in the map to represent the total available stock for that product.
     *
     * @param basket The basket source.
     */
    public void feedFrom(Basket basket) {
        this.basket = basket;
        this.toEvaluate.clear();
        if (basket.items != null) {
            for (Basket.Item originalItem : basket.items) {
                // Check if EAN already exists in map (Aggregation)
                Basket.Item existingItem = toEvaluate.get(originalItem.produceEan);
                if (existingItem == null) {
                    // First occurrence: Create a deep copy and put in map
                    Basket.Item copy = new Basket.Item();
                    copy.lineId = originalItem.lineId;
                    copy.produceEan = originalItem.produceEan;
                    copy.quantity = originalItem.quantity;
                    toEvaluate.put(originalItem.produceEan, copy);
                } else {
                    // Duplicate EAN: Aggregate quantity to the existing item
                    existingItem.quantity += originalItem.quantity;
                }
            }
        }
    }

    // --------------------------------------------------
    // Logic Methods
    // --------------------------------------------------

    /**
     * Picks a specified quantity of a product identified by its EAN from the items to evaluate.
     * <p>
     * Uses the Map structure for O(1) access time.
     * This method searches the {@code toEvaluate} map for an item matching the provided EAN.
     * It reduces the quantity of the found item by the requested amount.
     * If the resulting quantity reaches zero, the item is removed from the map.
     * <p>
     * If the requested quantity exceeds the available quantity, the available quantity
     * is taken entirely (partial consumption).
     *
     * @param quantityToPick The quantity to remove (consume).
     * @param ean            The EAN code of the product to pick.
     * @return A new {@link Basket.Item} describing what was actually taken (with the taken quantity),
     *         or {@code null} if no matching item was found.
     */
    public Basket.Item pick(Double quantityToPick, String ean) {
        if (quantityToPick == null || ean == null) {
            return null;
        }
        // Direct O(1) lookup by EAN
        Basket.Item item = toEvaluate.get(ean);
        if (item != null) {
            double available = item.quantity;
            double actualPick = Math.min(quantityToPick, available);
            // Create a return Item describing what was taken
            Basket.Item pickedItem = new Basket.Item();
            pickedItem.lineId = item.lineId; // Keep original lineId for traceability
            pickedItem.produceEan = ean;
            pickedItem.quantity = actualPick;
            // Update the map
            if (actualPick < available) {
                // Partial consumption: reduce the quantity in the working map
                item.quantity = available - actualPick;
            } else {
                // Full consumption: remove the item from the working map
                toEvaluate.remove(ean);
            }
            return pickedItem;
        }

        // Item not found
        return null;
    }

    /**
     * Gets the original basket.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     *
     * @return The basket.
     */
    @JsonIgnore
    public Basket getBasket() {
        return basket;
    }

    /**
     * Gets the store context.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     *
     * @return The store entity.
     */
    @JsonIgnore
    public Store getStore() {
        return store;
    }

    /**
     * Gets the set of store groups the store belongs to.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     *
     * @return The set of store groups.
     */
    @JsonIgnore
    public Set<StoreGroup> getStoreGroups() {
        return storeGroups;
    }

    /**
     * Gets the map of items remaining to be evaluated.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     * Note: The resource checks this map to signal an error if it is not empty.
     *
     * @return The map of items (EAN -> Item).
     */
    @JsonIgnore
    public Map<String, Basket.Item> getToEvaluate() {
        return toEvaluate;
    }

    /**
     * Gets the set of applied offers.
     * <p>
     * This is visible in the JSON response.
     *
     * @return The set of offer applications.
     */
    public Collection<OfferApplication> getOffers() {
        return offers;
    }

    /**
     * Gets the set of applied discounts.
     * <p>
     * This is visible in the JSON response.
     *
     * @return The set of discount applications.
     */
    public Collection<AdvantageApplication> getAdvantages() {
        return advantages;
    }

    /**
     * Sets the total price evaluation of the basket after applying offers and discounts.
     *
     * @param totalPrice The total price evaluation to set.
     */
    public void setTotalPrice(AmountEvaluation totalPrice) {
        this.totalPrice = totalPrice;
    }

    /**
     * Gets the total price evaluation of the basket after applying offers and discounts.
     *
     * @return The total price evaluation.
     */
    public AmountEvaluation getTotalPrice() {
        return totalPrice;
    }

    /**
     * Gets the map of items available for upcell suggestions.
     *
     * @return The map of items (EAN -> Item).
     */
    public Map<String, Basket.Item> getAvailableToUpcell() {
        return availableToUpcell;
    }

    /**
     * Adds a picked item to the availableToUpcell map.
     * <p>
     * If an item with the same EAN already exists in the map,
     * its quantity is aggregated with the new item's quantity.
     *
     * @param pickedItem The picked item to add.
     */
    public void addAvailableToUpcell(Basket.Item pickedItem) {
        if (pickedItem != null) {
            Basket.Item existingItem = availableToUpcell.get(pickedItem.produceEan);
            if (existingItem == null) {
                // First occurrence: Add to map
                Basket.Item copy = new Basket.Item();
                copy.lineId = pickedItem.lineId;
                copy.produceEan = pickedItem.produceEan;
                copy.quantity = pickedItem.quantity;
                availableToUpcell.put(pickedItem.produceEan, copy);
            } else {
                // Duplicate EAN: Aggregate quantity to the existing item
                existingItem.quantity += pickedItem.quantity;
            }
        }
    }
}