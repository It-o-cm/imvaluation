package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Client for importing Offers via CSV.
 * <p>
 * Defines Offers targeting specific Stores (using store_code) or StoreGroups (using store_group_code).
 * The specification column contains JSON data (e.g., discounts, dates).
 */
public class OfferImporterClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String IMPORT_URL = BASE_URL + "/offers/import";

    // Credentials defined in application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        // CSV Data:
        // Column 1: offer_code
        // Column 2: offer_type
        // Column 3: specification (JSON)
        // Column 4: store_code (Target Store)
        // Column 5: store_group_code (Target StoreGroup)
        String csvData = """
            offer_code|offer_type|specification|store_code|store_group_code
            PROMO_STORE_101|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer"], "targetEans": ["3300000000001"], "discountType": "PERCENTAGE", "value": 15.0}|0101|
            PROMO_STORE_102|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer"], "targetEans": ["3300000000004"], "discountType": "FIXED_AMOUNT", "value": 1.99}|0102|
            PROMO_GROUP_NORD|FREE_DELIVERY_THRESHOLD|{"tiers": [{"threshold": 30.0, "value": 100.0, "type": "PERCENTAGE"}]}||REGION_NORTH
            PROMO_2FOR1_3300|N+M|{"targetEans": ["3300000000001"], "quantityToPay": 2, "discountedQuantity": 1, "selectionStrategy": "CHEAPEST", "discountType": "PERCENTAGE", "discountValue": 100.0}|0101|
            PROMO_COFFEE_PACK|MIXED_BUNDLE|{"bundlePrice": 4.50, "vatRate": 0.20, "contents": [{"ean": "3300000000004", "quantity": 1.0}, {"ean": "3300000000013", "quantity": 1.0, "substituteEans": ["3300000000014"]}]}|0101|
            DELIVERY_HOME_0101|DELIVERY|{"tiers": [{"maxDistance": 8.0, "price": 5.90}, {"maxDistance": 16.0, "price": 9.90}], "vatRate": 0.20}|0101|
            BRI_APPLES_DISCOUNT|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer", "MixedBundleOffer"], "targetEans": ["3300000000001"], "discountType": "FIXED_AMOUNT", "value": 0.10}|0101|
            BASKET_CONSIGNMENT_0101|DEPOSIT_BASKET|{"basketVolume": 10.0, "basketPrice": 0.50, "vatRate": 0.20}|0101|
            FREE_DELIVERY_THRESHOLD_0101|FREE_DELIVERY_THRESHOLD|{"tiers": [{"threshold": 10.0, "value": 50.0, "type": "FIXED_AMOUNT"}, {"threshold": 20.0, "value": 100.0, "type": "FIXED_AMOUNT"}]}|0101|
            MEAL_VOUCHER_0101|MEAL_VOUCHER|{"flag": "RESTAURANT_VOUCHER_ELIGIBLE", "threshold": 25.00}|0101|
            VIGNETTE_CUISSON|VIGNETTE_DISCOUNT|{"catalog": [{"ean": "3300000000031", "vignettesRequired": 5, "discount": {"type": "PERCENTAGE", "value": 50.0}}, {"ean": "3300000000032", "vignettesRequired": 3, "discount": {"type": "FIXED_AMOUNT", "value": 2.00}}]}|0101|
            """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Create Basic Authentication header
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMPORT_URL))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Basic " + encodedAuth)
                .POST(HttpRequest.BodyPublishers.ofString(csvData))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Server Response: " + response.body());

            if (response.statusCode() == 200) {
                System.out.println("Import successful!");
            } else {
                System.err.println("Error during processing.");
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}