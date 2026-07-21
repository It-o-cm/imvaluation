package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Client for creating Offer entities via GraphQL API.
 * <p>
 * Sends mutation requests to create offers and link them using
 * Business Codes of existing Stores or StoreGroups.
 * <p>
 * The input requires linking to a target:
 * Use {@code storeCode} (String) to target a specific Store.
 * Use {@code storeGroupCode} (String) to target a StoreGroup.
 * Only one of the two codes should be provided; the other must be null.
 */
public class OfferGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        // 1. Prepare Data
        // Format: {Code, Type, Specification JSON, Store Code, StoreGroup Code}
        // Note: Use valid String Codes from your database (e.g., "0101", "REGION_NORD").

        String[][] offers = {
                {"PROMO_XMAS", "DISCOUNT", "{\"rate\": 20, \"unit\": \"PERCENT\"}", "0101", "null"},
                {"PROMO_FLASH", "FLASH_SALE", "{\"newPrice\": 5.99}", "0102", "null"},
                {"PROMO_VIP", "SPECIAL", "{\"description\": \"VIP access\"}", "null", "REGION_NORTH"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateOffer($input: OfferRecordInput!) {
                  createOffer(input: $input) {
                    id
                    code
                    type
                  }
                }
                """;

        // Remove newlines for valid JSON string
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through offers and send mutations
        for (String[] offer : offers) {
            try {
                // Formatting values
                String specValue = jsonStringValue(offer[2]);
                String codeValue = jsonStringValue(offer[0]);
                String typeValue = jsonStringValue(offer[1]);

                // Handling Code fields
                // If input is "null", we send literal null. Otherwise, send quoted string.
                String storeCodeValue = "null".equals(offer[3]) ? "null" : jsonStringValue(offer[3]);
                String groupCodeValue = "null".equals(offer[4]) ? "null" : jsonStringValue(offer[4]);

                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "code": %s,
                            "type": %s,
                            "specification": %s,
                            "storeCode": %s,
                            "storeGroupCode": %s
                          }
                        }
                        """,
                        codeValue, typeValue, specValue, storeCodeValue, groupCodeValue
                );

                // Remove newlines from variables JSON
                String compactVariables = variablesJson.replace("\n", "");

                // Build the full JSON payload
                String jsonPayload = String.format(
                        """
                        {
                          "query": "%s",
                          "variables": %s
                        }
                        """,
                        compactMutation, compactVariables
                );

                // Create Request
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GRAPHQL_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", getBasicAuthHeader())
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                // Send Request
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // Print Result
                if (response.statusCode() == 200) {
                    System.out.println("Success for " + offer[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + offer[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + offer[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Helper to escape a string value as a valid JSON String.
     * <p>
     * Handles escaping of quotes and backslashes.
     *
     * @param s The string input.
     * @return A string enclosed in quotes and properly escaped.
     */
    private static String jsonStringValue(String s) {
        if (s == null) return "null";
        // Escape backslashes and double quotes
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    /**
     * Generates a Basic Authentication header string.
     *
     * @return The encoded "Basic" auth string.
     */
    private static String getBasicAuthHeader() {
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        return "Basic " + encodedAuth;
    }

}