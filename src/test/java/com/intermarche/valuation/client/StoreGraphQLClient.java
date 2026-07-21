package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class StoreGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        // 1. Prepare Data (5 Stores)
        String[][] stores = {
                {"0101", "Intermarché Test 1", "1 Rue du Test", "ZI Nord", "59000", "Lille", "France", "50.63", "3.06"},
                {"0102", "Intermarché Test 2", "12 Avenue des Fleurs", "", "33000", "Bordeaux", "France", "44.83", "-0.57"},
                {"0103", "Intermarché Test 3", "99 Boulevard de la Liberte", "Etage 2", "69000", "Lyon", "France", "45.75", "4.85"},
                {"0104", "Intermarché Test 4", "25 Rue de Rivoli", "", "75001", "Paris", "France", "48.86", "2.33"},
                {"0105", "Intermarché Test 5", "8 Place de la Gare", "Bat C", "67000", "Strasbourg", "France", "48.57", "7.75"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template (using Text Block for readability)
        // Note: \n in the query are actual newlines. We will remove them to make valid JSON string.
        String mutationTemplate = """
                mutation CreateStore($input: StoreRecordInput!) {
                  createStore(input: $input) {
                    id
                    code
                    name
                  }
                }
                """;

        // Remove newlines from the query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through stores and send mutations
        for (String[] store : stores) {
            try {
                // Using Text Block to build JSON variables clearly
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "code": "%s",
                            "name": "%s",
                            "streetLine1": "%s",
                            "streetLine2": "%s",
                            "postalCode": "%s",
                            "city": "%s",
                            "country": "%s",
                            "latitude": %s,
                            "longitude": %s
                          }
                        }
                        """,
                        store[0], store[1], store[2], store[3], store[4], store[5], store[6], store[7], store[8]
                );

                // Remove newlines from variables JSON as well to make it compact
                String compactVariables = variablesJson.replace("\n", "");

                // Build the full JSON payload using Text Block
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
                    System.out.println("Success for " + store[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + store[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + store[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static String getBasicAuthHeader() {
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        return "Basic " + encodedAuth;
    }
}