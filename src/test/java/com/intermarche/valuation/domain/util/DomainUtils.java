package com.intermarche.valuation.domain.util;

import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.engine.Basket;

import java.math.BigDecimal;
import java.util.HashSet;

public class DomainUtils {

    public static Product createAndPersistProduct(String ean, String name, ProductType type) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.productType = type;
        product.persist();
        return product;
    }

    public static void setProductCharacteristics(String ean, BigDecimal referenceWeight, BigDecimal referenceVolume) {
        Product product = Product.findByEan(ean);
        product.referenceWeight = referenceWeight;
        product.referenceVolume = referenceVolume;
        product.persist();
    }

    public static Price createAndPersistPrice(Product product, Store store, int priority, PriceUsage usage, BigDecimal priceExcludingTax, BigDecimal priceIncludingTax, BigDecimal vatRate) {
        Price price = new Price();
        price.product = product;
        price.store = store;
        price.priority = priority;
        price.priceUsage = usage;
        price.priceExcludingTax = priceExcludingTax;
        price.priceIncludingTax = priceIncludingTax;
        price.vatRate = vatRate;
        price.persist();
        return price;
    }

    /**
     * Creates and persists a ProductFamily entity with the given code.
     * <p>
     * The 'description' is set to the code by default.
     * Initializes the 'products' and 'productFamilies' sets to avoid NullPointerException.
     *
     * @param code The unique code (and description) of the family.
     * @return The created and persisted ProductFamily entity.
     */
    public static ProductFamily createAndPersistProductFamily(String code) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = code; // Default description to code
        // Initialize Sets to prevent NullPointerException during relationship operations
        if (family.products == null) family.products = new HashSet<>();
        if (family.productFamilies == null) family.productFamilies = new HashSet<>();

        family.persist();
        return family;
    }

    /**
     * Helper to create and persist a Store with coordinates.
     */
    public static Store createAndPersistStore(String code, Double lat, Double lon) {
        Store s = new Store();
        s.code = code;
        s.name = "Store " + code;
        s.address = new Adresse();
        if (lat != null) s.address.latitude = lat;
        if (lon != null) s.address.longitude = lon;
        s.persist();
        return s;
    }

    /**
     * Helper to create and persist a Delivery Offer linked to a store.
     */
    public static Offer createAndPersistOffer(String code, Store store, String type, String specification) {
        Offer o = new Offer();
        o.code = code;
        o.type = type;
        o.specification = specification;
        o.stores.add(store);
        o.persist();
        return o;
    }

    /**
     * Creates and persists a StoreGroup entity with the given code.
     * <p>
     * The 'name' is set to the code by default.
     * Initializes the 'stores' and 'storeGroups' sets to avoid NullPointerException.
     *
     * @param code The unique code (and name) of the group.
     * @return The created and persisted StoreGroup entity.
     */
    public static StoreGroup createAndPersistStoreGroup(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = code; // Default name to code
        // Initialize Sets to prevent NullPointerException during relationship operations
        if (group.stores == null) group.stores = new HashSet<>();
        if (group.storeGroups == null) group.storeGroups = new HashSet<>();

        group.persist();
        return group;
    }

    /**
     * Helper to update a store's address coordinates.
     * Useful to bypass @PrePersist constraints and test null values.
     */
    public static void updateStoreAddress(Store store, Double lat, Double lon) {
        if (lat != null || lon != null) {
            // Update coordinates if not null
            store.address = new Adresse(); // Re-create to ensure object exists if coords are not null
            store.address.latitude = lat;
            store.address.longitude = lon;
        } else {
            // Set to null explicitly
            store.address = null;
        }
        store.persist(); // Update the entity in DB
    }

    /**
     * Helper method to create a {@link Basket.Item} with specified EAN and quantity.
     * <p>
     * Sets manual price fields. Since we are testing with a real DB,
     * the {@link Basket.Item#getPrice(Store, PriceUsage)} method
     * will override these manual prices if it finds an active DB price.
     *
     * @param ean      The EAN code for the item.
     * @param quantity The quantity of the item.
     * @return A configured Basket.Item instance.
     */
    public static Basket.Item createItem(String ean, Double quantity) {
        Basket.Item item = new Basket.Item();
        item.produceEan = ean;
        item.quantity = quantity;
        return item;
    }

}
