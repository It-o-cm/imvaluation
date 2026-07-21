package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test ProductCategoryStorage creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of product-category links using Java 11+ HttpClient.
 */
public class ProductCategoryStorageGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute the creation of product-category links.
     * <p>
     * Prepares a list of sample storages and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data (10 ProductCategoryStorages)
        // Structure: {ProductId, Level1, Level2, Level3, Level4, Level5}
        String[][] storages = {
                {"1", "Food", "Fresh", "Fruits & Vegetables", "Local", "Organic"},
                {"2", "Food", "Fresh", "Dairy", "Yogurts", "Fruit"},
                {"3", "Food", "Fresh", "Bakery", "Traditional", "Baguettes"},
                {"4", "Food", "Pantry", "Beverages", "Hot", "Coffee"},
                {"5", "Food", "Pantry", "Groceries", "Pasta", "Italy"},
                {"6", "Food", "Pantry", "Oils & Vinegars", "Olive", "Extra Virgin"},
                {"7", "Food", "Pantry", "Beverages", "Cold", "Water"},
                {"8", "Food", "Fresh", "Deli", "Meats", "Hams"},
                {"9", "Food", "Fresh", "Dairy", "Butter", "Spreadable"},
                {"10", "Food", "Fresh", "Dairy", "Yogurts", "Nature"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template (using Text Block for readability)
        String mutationTemplate = """
                mutation CreateProductCategoryStorage($input: ProductCategoryStorageRecordInput!) {
                  createProductCategoryStorage(input: $input) {
                    id
                    level1
                  }
                }
                """;

        // Remove newlines from the query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through storages and send mutations
        for (String[] storage : storages) {
            try {
                // Using Text Block to build JSON variables clearly
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "productId": %s,
                            "level1": "%s",
                            "level2": "%s",
                            "level3": "%s",
                            "level4": "%s",
                            "level5": "%s"
                          }
                        }
                        """,
                        storage[0], storage[1], storage[2], storage[3], storage[4], storage[5]
                );

                // Remove newlines from variables JSON as well to make it compact
                String compactVariables = variablesJson.replace("\n", "");

                // Build of full JSON payload using Text Block
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
                    System.out.println("Success for Product " + storage[0] + ": " + response.body());
                } else {
                    System.err.println("Error for Product " + storage[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for Product " + storage[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Generates a Base64 encoded Basic Authentication header.
     *
     * @return The "Basic {encoded}" string ready for HTTP headers.
     */
    private static String getBasicAuthHeader() {
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        return "Basic " + encodedAuth;
    }
}