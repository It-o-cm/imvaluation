package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Client for creating ProductFamily entities via GraphQL API.
 * <p>
 * Sends mutation requests to create families and links them using
 * existing Product EANs (Strings) and ProductFamily Codes (Strings).
 */
public class ProductFamilyGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8090";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        // 1. Prepare Data
        // Format: {Code, Description, JSON array of Product EANs, JSON array of Sub-Family Codes}
        // These Codes/EANs correspond to the entities created by the Importer (Strings).
        String[][] families = {
                {"FRUITS", "Rayon Fruits", "[\"3300000000001\"]", "[]"},
                {"DRINKS", "Rayon Boissons", "[\"3300000000002\", \"3300000000006\"]", "[]"},
                {"PROMO_FAMILY", "Famille Promo", "[]", "[\"FRUITS\", \"DRINKS\"]"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateProductFamily($input: ProductFamilyRecordInput!) {
                  createProductFamily(input: $input) {
                    id
                    code
                    description
                  }
                }
                """;

        // Remove newlines for valid JSON string
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through families and send mutations
        for (String[] family : families) {
            try {
                // Using Text Block to build JSON variables clearly
                // Note: We directly use the JSON formatted strings from the array for the lists
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "code": "%s",
                            "description": "%s",
                            "productEans": %s,
                            "productFamilyCodes": %s
                          }
                        }
                        """,
                        family[0], family[1], family[2], family[3]
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
                    System.out.println("Success for " + family[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + family[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + family[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
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