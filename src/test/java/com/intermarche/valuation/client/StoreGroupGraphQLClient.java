package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Client for creating StoreGroup entities via GraphQL API.
 * <p>
 * Sends mutation requests to create groups and links them using
 * existing Store Codes (Strings) and StoreGroup Codes (Strings).
 */
public class StoreGroupGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8090";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        // 1. Prepare Data
        // Format: {Code, Name, JSON array of Store Codes, JSON array of StoreGroup Codes}
        // These Codes correspond to the entities created by the Importer (Strings).
        String[][] groups = {
                {"REGION_NORTH", "Region North", "[\"0104\", \"0105\"]", "[]"},
                {"REGION_SOUTH", "Region South", "[]", "[]"},
                {"NATIONAL", "National Group", "[\"0101\", \"0102\", \"0103\"]", "[\"REGION_NORTH\", \"REGION_SOUTH\"]"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateStoreGroup($input: StoreGroupRecordInput!) {
                  createStoreGroup(input: $input) {
                    id
                    code
                    name
                  }
                }
                """;

        // Remove newlines for valid JSON string
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through groups and send mutations
        for (String[] group : groups) {
            try {
                // Using Text Block to build JSON variables clearly
                // Note: We directly use the JSON formatted strings from the array for the lists
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "code": "%s",
                            "name": "%s",
                            "storeCodes": %s,
                            "storeGroupCodes": %s
                          }
                        }
                        """,
                        group[0], group[1], group[2], group[3]
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
                    System.out.println("Success for " + group[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + group[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + group[0] + ": " + e.getMessage());
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