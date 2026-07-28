package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Factory for creating Appliers for "DELIVERY" type offers.
 * <p>
 * Offers are retrieved from the database where the type is "DELIVERY".
 * <b>Constraint:</b> Only one DELIVERY offer is allowed per store.
 * If multiple are found, an {@link IllegalStateException} is thrown.
 * <p>
 * The JSON specification must contain a list of tiers:
 * <pre>
 * {
 *   "tiers": [
 *     { "maxDistance": 10.0, "price": 5.00 },
 *     { "maxDistance": 20.0, "price": 8.00 }
 *   ],
 *   "vatRate": 0.20
 * }
 * </pre>
 * <p>
 * The Applier calculates the distance between the Store's embedded address
 * and the Delivery Address using the Haversine formula and applies the matching price tier.
 */
@ApplicationScoped
public class DeliveryOfferFactory implements OfferApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "DELIVERY";

    /**
     * JSON Schema definition for validating Delivery specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Delivery Offer Specification",
      "description": "Defines the pricing tiers for delivery based on distance.",
      "type": "object",
      "required": [
        "tiers",
        "vatRate"
      ],
      "properties": {
        "vatRate": {
          "type": "number",
          "description": "The VAT rate applied to the delivery price.",
          "minimum": 0,
          "x-widget": "rate",
          "x-label": "VAT rate"
        },
        "tiers": {
          "type": "array",
          "minItems": 1,
          "x-widget": "object-list",
          "x-label": "Distance tiers",
          "x-item-label": "tier",
          "items": {
            "type": "object",
            "required": [
              "maxDistance",
              "price"
            ],
            "properties": {
              "maxDistance": {
                "type": "number",
                "description": "Maximum distance in kilometers for this tier.",
                "exclusiveMinimum": 0,
                "x-widget": "distance",
                "x-label": "Up to"
              },
              "price": {
                "type": "number",
                "description": "The price (TTC) for this tier.",
                "exclusiveMinimum": 0,
                "x-widget": "money",
                "x-label": "Price (incl. tax)"
              }
            }
          }
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return The "DELIVERY" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the delivery specification.
     *
     * @return The JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds a collection of offer appliers based on the provided basket.
     * <p>
     * Only creates an applier if:
     * <ul>
     *   <li>Delivery mode is "HOME_DELIVERY".</li>
     *   <li>Store and Delivery Address contain valid coordinates.</li>
     *   <li><b>Exactly one</b> DELIVERY offer is defined for the store.</li>
     * </ul>
     *
     * @param basketEvaluation The evaluation of the basket containing items and address details.
     * @return A collection containing at most one {@link DeliveryOfferApplier}.
     * @throws IllegalStateException if more than one DELIVERY offer is found for the store.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create delivery appliers without a valid basket context.");
        // Only applicable for Home Delivery
        if (!"HOME_DELIVERY".equalsIgnoreCase(basket.deliveryMode)) {
            return appliers;
        }
        // Check if Address and Coordinates are present
        if (basket.deliveryAddress == null || basket.deliveryAddress.latitude == null || basket.deliveryAddress.longitude == null) {
            throw new IllegalStateException("Delivery address or coordinates missing for basket " + basket.customerCode);
        }
        Store store = basketEvaluation.getStore();
        // Check if Store has an address and coordinates
        if (store.address.latitude == null || store.address.longitude == null) {
            throw new IllegalStateException("Store address or coordinates missing for store " + store.code);
        }
        // Retrieve all "DELIVERY" type offers for this store
        List<Offer> offers = Offer.findByStoreAndType(store, "DELIVERY");
        // Constraint: Only one delivery offer allowed per store
        if (offers.size() > 1) {
            throw new IllegalStateException(String.format(
                    "Configuration Error: Multiple DELIVERY offers found for store '%s'. Expected 1, found %d.",
                    store.code, offers.size()
            ));
        }
        // Since we checked size > 1, we check for existence (size == 1)
        if (!offers.isEmpty()) {
            Offer offer = offers.get(0); // Directly access the unique offer
            processOffer(offer, appliers, store, basket);
        }
        return appliers;
    }

    /**
     * Processes a single DELIVERY offer and adds the corresponding applier to the list.
     *
     * @param offer    The DELIVERY offer to process.
     * @param appliers The list to which the created applier will be added.
     * @param store    The store context for the offer.
     * @param basket   The basket context containing delivery address details.
     */
    private void processOffer(Offer offer, List<OfferApplier> appliers, Store store, Basket basket) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            BigDecimal vatRate = spec.get("vatRate").decimalValue();
            JsonNode tiersNode = spec.get("tiers");
            List<DeliveryTier> tiers = new ArrayList<>();
            for (JsonNode tierNode : tiersNode) {
                double maxDist = tierNode.get("maxDistance").asDouble();
                BigDecimal price = tierNode.get("price").decimalValue();
                tiers.add(new DeliveryTier(maxDist, price));
            }
            // Sort tiers by maxDistance ascending to find the first matching tier
            tiers.sort(Comparator.comparingDouble(t -> t.maxDistance));
            appliers.add(new DeliveryOfferApplier(offer.code, store, basket.deliveryAddress, vatRate, tiers));
        });
    }

    /**
     * Helper class to store a delivery pricing tier.
     */
    private static class DeliveryTier {
        double maxDistance; // in km
        BigDecimal priceTTC;

        /**
         * Constructs a new DeliveryTier.
         *
         * @param maxDistance The maximum distance for this tier.
         * @param priceTTC    The price including tax.
         */
        DeliveryTier(double maxDistance, BigDecimal priceTTC) {
            this.maxDistance = maxDistance;
            this.priceTTC = priceTTC;
        }
    }

    /**
     * Applier for a specific Delivery Offer.
     */
    public static class DeliveryOfferApplier extends OfferApplier {
        private final String offerCode;
        private final Store store;
        private final Basket.Address deliveryAddress;
        private final BigDecimal vatRate;
        private final List<DeliveryTier> tiers;

        /**
         * Constructs a new DeliveryOfferApplier.
         *
         * @param offerCode      The offer code.
         * @param store          The store context.
         * @param deliveryAddress The delivery address.
         * @param vatRate        The VAT rate.
         * @param tiers          The sorted list of pricing tiers.
         */
        public DeliveryOfferApplier(String offerCode, Store store, Basket.Address deliveryAddress, BigDecimal vatRate, List<DeliveryTier> tiers) {
            this.offerCode = offerCode;
            this.store = store;
            this.deliveryAddress = deliveryAddress;
            this.vatRate = vatRate;
            this.tiers = tiers;
        }

        /**
         * Calculates the distance and applies the corresponding price tier.
         *
         * @param evaluation The evaluation context.
         * @return A collection of offer applications.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) {
            List<OfferApplication> applications = new ArrayList<>();
            // 1. Calculate Distance using Haversine formula
            double distanceKm = calculateDistance(
                    store.address.latitude, store.address.longitude,
                    deliveryAddress.latitude, deliveryAddress.longitude
            );
            BigDecimal deliveryPrice = null;
            for (DeliveryTier tier : tiers) {
                if (distanceKm <= tier.maxDistance) {
                    deliveryPrice = tier.priceTTC;
                    break; // Stop at first matching tier (tiers are sorted)
                }
            }
            // 3. If a tier was found, create an application
            if (deliveryPrice != null) {
                applications.add(new DeliveryApplication(offerCode, deliveryPrice, vatRate, distanceKm));
            } else {
                System.err.println("Delivery distance " + distanceKm + " km exceeds all defined tiers for offer " + offerCode);
            }
            return applications;
        }

        /**
         * Delivery offers usually have 0 efficiency as they are costs, not discounts.
         *
         * @param basket The basket context.
         * @return 0.0.
         */
        @Override
        public double computeEfficiencyScore(Basket basket) {
            return 0.0;
        }

        /**
         * Calculates distance between two coordinates in kilometers using the Haversine formula.
         *
         * @param lat1 Latitude of the first point.
         * @param lon1 Longitude of the first point.
         * @param lat2 Latitude of the second point.
         * @param lon2 Longitude of the second point.
         * @return Distance in kilometers.
         */
        private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
            final int R = 6371; // Radius of the earth in km
            double latDistance = Math.toRadians(lat2 - lat1);
            double lonDistance = Math.toRadians(lon2 - lon1);
            double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return R * c;
        }
    }

    /**
     * Result of applying a Delivery Offer.
     */
    public static class DeliveryApplication implements OfferApplication {
        private final String offerCode;
        private final BigDecimal priceTTC;
        private final BigDecimal vatRate;
        private final double distanceKm;

        /**
         * Constructs a new DeliveryApplication.
         *
         * @param offerCode  The offer code.
         * @param priceTTC   The price including tax.
         * @param vatRate    The VAT rate.
         * @param distanceKm The calculated distance.
         */
        public DeliveryApplication(String offerCode, BigDecimal priceTTC, BigDecimal vatRate, double distanceKm) {
            this.offerCode = offerCode;
            this.priceTTC = priceTTC;
            this.vatRate = vatRate;
            this.distanceKm = distanceKm;
        }

        /**
         * Calculates the price evaluation (HT and TTC).
         *
         * @return The amount evaluation.
         */
        @Override
        public AmountEvaluation getAmount() {
            // HT = TTC / (1 + TVA)
            BigDecimal divisor = BigDecimal.ONE.add(vatRate);
            BigDecimal priceHT = priceTTC.divide(divisor, 2, RoundingMode.HALF_UP);
            return new AmountEvaluation(priceHT, priceTTC, vatRate);
        }

        /**
         * Delivery is a service, not a physical item.
         * Returns null as no items are consumed from the inventory.
         *
         * @return null.
         */
        @Override
        @JsonIgnore
        public Collection<Basket.Item> getItems() {
            return null;
        }

        /**
         * Delivery covers no basket product, so there is nothing to value per line.
         *
         * @return An empty list.
         */
        @Override
        @JsonProperty("items")
        public java.util.List<BasketEvaluation.Item> getValuedItems() {
            return java.util.List.of();
        }

        /**
         * Returns a descriptive string of the delivery offer application.
         *
         * @return The type string.
         */
        @Override
        public String getType() {
            return "Delivery: " + offerCode + " (" + String.format("%.2f", distanceKm) + " km) for " + priceTTC + "€";
        }

    }
}
