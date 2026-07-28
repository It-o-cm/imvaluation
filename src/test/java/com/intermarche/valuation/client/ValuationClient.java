package com.intermarche.valuation.client;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.Basket.Item;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Client for basket valuation using Domain Entities.
 * <p>
 * This client instantiates {@link Store} and {@link Product} objects
 * using definitions found in the imports to construct a {@link Basket} JSON payload.
 */
public class ValuationClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String VALUATION_URL = BASE_URL + "/valuation";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {

        // Creation of address (Data from StoreImporterClient)
        // Located in Seclin, approx 11km South of Lille (Store 0101)
        Basket.Address deliveryAddress = new Basket.Address();
        deliveryAddress.streetLine1 = "12 Rue du Test";
        deliveryAddress.streetLine2 = "ZI Sud";
        deliveryAddress.postalCode = "59113"; // Seclin Postal Code
        deliveryAddress.city = "Seclin";      // Seclin City
        deliveryAddress.country = "France";
        deliveryAddress.latitude = 50.540;   // ~11km South of Lille center
        deliveryAddress.longitude = 3.030;     // ~11km South of Lille center

        // ... You can add all other products defined in your import ...

        // --------------------------------------------------
        // 3. Preparation of the Basket (Items)
        // --------------------------------------------------
        List<Item> items = new ArrayList<>();

        // Mapping Product 1 (Apples - Already weighted)
        Item item1 = new Item();
        item1.lineId = "1";
        item1.produceEan = "3300000000001"; // Pommes Golden
        item1.quantity = 4.0; // 4 units of 1kg
        items.add(item1);

        // Mapping Product 2 (Milk - Sold by unit)
        Item item2 = new Item();
        item2.lineId = "2";
        item2.produceEan = "3300000000002"; // Lait UHT
        item2.quantity = 1.0; // 1 bottle
        items.add(item2);

        // Mapping Product 3 (Ham - Adding a weighted product)
        // Type: WEIGHT, RefWeight: 0.100 kg, Price: 2.40 €/kg
        // Expected calculation: 0.5 * 2.40 = 1.20 € TTC
        Item item3 = new Item();
        item3.lineId = "3";
        item3.produceEan = "3300000000008"; // Jambon Blanc 100g
        item3.quantity = 0.5; // 0.5 kg
        items.add(item3);

        // --------------------------------------------------
        // Addition to test the "MixedBundle" offer (Coffee + Biscuits)
        // Offer: 1x Coffee + 1x Biscuits for 4.50€
        // --------------------------------------------------

        // 4 Items "3300000000004" (Café Grains 500g)
        Item item4 = new Item();
        item4.lineId = "4";
        item4.produceEan = "3300000000004";
        item4.quantity = 4.0;
        items.add(item4);

        // 2 Items "3300000000013" (Biscuits Chocolat 200g)
        Item item5 = new Item();
        item5.lineId = "5";
        item5.produceEan = "3300000000013";
        item5.quantity = 2.0;
        items.add(item5);

        // 1 Item "3300000000014" (Chips Classiques)
        // Note: 3300000000014 is a substitute for Coffee (3300000000004) in the bundle definition.
        // Total "Coffee" (Main + Substitutes) in basket = 5 (Item 4 + Item 6)
        // Total Biscuits in basket = 2 (Item 5)
        // Max bundles possible = min(5/1, 2/1) = 2 Bundles
        Item item6 = new Item();
        item6.lineId = "6";
        item6.produceEan = "3300000000014";
        item6.quantity = 1.0;
        items.add(item6);

        // --------------------------------------------------
        // Addition to test Vignette Discount
        // Product: Frying Pan (EAN 3300000000031), Price: 14.40 €
        // Offer: 5 vignettes = 50% discount
        // --------------------------------------------------
        Item item7 = new Item();
        item7.lineId = "7";
        item7.produceEan = "3300000000031"; // Poêle Antiadhésive 28cm
        item7.quantity = 1.0;
        items.add(item7);

        // Construction of items JSON string
        StringBuilder itemsJson = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            itemsJson.append(String.format("{\"lineId\": %d, \"produceEan\": \"%s\", \"quantity\": %s}",
                    item.lineId, item.produceEan, item.quantity));
            if (i < items.size() - 1) {
                itemsJson.append(",");
            }
        }
        itemsJson.append("]");

        // --------------------------------------------------
        // 4. Final JSON Construction
        // --------------------------------------------------
        String customerCode = "CUST-001";
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        String mode = "HOME_DELIVERY";

        // Vignettes configuration: 5 vignettes for the Frying Pan
        String vignettesJson = "{\"3300000000031\": 5}";

        String jsonPayload = String.format(
                """
                {
                  "customerCode": "%s",
                  "storeCode": "%s",
                  "createdAt": "%s",
                  "deliveryMode": "%s",
                  "deliveryAddress": {
                    "streetLine1": "%s",
                    "streetLine2": "%s",
                    "postalCode": "%s",
                    "city": "%s",
                    "country": "%s",
                    "latitude": %s,
                    "longitude": %s
                  },
                  "instructions": ["Deposit basket"],
                  "vignettes": %s,
                  "items": %s
                }
                """,
                customerCode, "0101", now, mode,
                deliveryAddress.streetLine1, deliveryAddress.streetLine2, deliveryAddress.postalCode, deliveryAddress.city, deliveryAddress.country,
                deliveryAddress.latitude, deliveryAddress.longitude,
                vignettesJson,
                itemsJson.toString()
        );

        // --------------------------------------------------
        // 5. Request Execution
        // --------------------------------------------------
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VALUATION_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", getBasicAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
            mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            Object jsonRequest = mapper.readValue(jsonPayload, Object.class);
            String formattedJsonRequest = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonRequest);
            System.out.println("Request content: " + formattedJsonRequest);
            System.out.println("--------------------------------------------------");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());
            Object jsonObject = mapper.readValue(response.body(), Object.class);
            String formattedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
            System.out.println("Server Response: " + formattedJson);

            if (response.statusCode() == 200) {
                System.out.println("Valuation successful!");
            } else {
                System.err.println("Error during processing.");
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Generates a Basic authentication header.
     *
     * @return The encoded "Basic" string.
     */
    private static String getBasicAuthHeader() {
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        return "Basic " + encodedAuth;
    }
}