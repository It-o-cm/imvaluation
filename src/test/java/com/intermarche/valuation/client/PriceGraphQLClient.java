package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test Price creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of prices using Java 11+ HttpClient.
 * <p>
 * All prices start on Jan 12, 2026.
 * Prices are created for Store 0101 and 0102 (IDs 1 and 2).
 * <p>
 * Data Logic:
 * - For every standard price (DEFAULT), a corresponding reference price (BASE_FOR_DISCOUNT) is created.
 * - The BASE_FOR_DISCOUNT price is 10% higher than DEFAULT.
 * - Priority and dates remain identical between the two.
 */
public class PriceGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute Price creation.
     * <p>
     * Prepares a list of prices (some products have multiple entries) and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data (Prices)
        // Structure: {ProductId, StoreId, PriceExcludingTax, PriceIncludingTax, VatRate, PriceUsage, Priority, StartDateTime, EndDateTime}
        // Assumptions: Product IDs 1-30 exist. Store IDs 1 (0101) and 2 (0102) exist.
        // Date: 2026-01-12T00:00:00
        String startDate = "2026-01-12T00:00:00";

        String[][] prices = {
                // Product 1: Standard & Promo in Store 1 (and their Base Prices)
                {"1", "1", "1.00", "1.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"1", "1", "1.10", "1.32", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"1", "1", "0.90", "1.08", "0.2000", "DEFAULT", "1", startDate, null}, // Promo
                {"1", "1", "0.99", "1.19", "0.2000", "BASE_FOR_DISCOUNT", "1", startDate, null},
                // Product 1: Standard in Store 2
                {"1", "2", "1.00", "1.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"1", "2", "1.10", "1.32", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                // Product 2: Standard
                {"2", "1", "1.00", "1.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"2", "1", "1.10", "1.32", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"2", "2", "1.00", "1.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"2", "2", "1.10", "1.32", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                // Product 3 to 10
                {"3", "1", "0.80", "0.96", "0.2000", "DEFAULT", "0", startDate, null},
                {"3", "1", "0.88", "1.06", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"3", "2", "0.80", "0.96", "0.2000", "DEFAULT", "0", startDate, null},
                {"3", "2", "0.88", "1.06", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"4", "1", "3.50", "4.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"4", "1", "3.85", "4.62", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"4", "2", "3.50", "4.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"4", "2", "3.85", "4.62", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"5", "1", "1.20", "1.44", "0.2000", "DEFAULT", "0", startDate, null},
                {"5", "1", "1.32", "1.58", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"5", "2", "1.20", "1.44", "0.2000", "DEFAULT", "0", startDate, null},
                {"5", "2", "1.32", "1.58", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"6", "1", "5.00", "6.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"6", "1", "5.50", "6.60", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"6", "2", "5.00", "6.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"6", "2", "5.50", "6.60", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"7", "1", "0.50", "0.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"7", "1", "0.55", "0.66", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"7", "2", "0.50", "0.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"7", "2", "0.55", "0.66", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"8", "1", "2.00", "2.40", "0.2000", "DEFAULT", "0", startDate, null},
                {"8", "1", "2.20", "2.64", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"8", "2", "2.00", "2.40", "0.2000", "DEFAULT", "0", startDate, null},
                {"8", "2", "2.20", "2.64", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"9", "1", "2.50", "3.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"9", "1", "2.75", "3.30", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"9", "2", "2.50", "3.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"9", "2", "2.75", "3.30", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"10", "1", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"10", "1", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"10", "2", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"10", "2", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                // Product 11: Standard & Promo
                {"11", "1", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"11", "1", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"11", "1", "1.20", "1.44", "0.2000", "DEFAULT", "1", startDate, null}, // Promo
                {"11", "1", "1.32", "1.58", "0.2000", "BASE_FOR_DISCOUNT", "1", startDate, null},
                {"11", "2", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"11", "2", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                // Product 12 to 20
                {"12", "1", "1.25", "1.50", "0.2000", "DEFAULT", "0", startDate, null},
                {"12", "1", "1.38", "1.65", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"12", "2", "1.25", "1.50", "0.2000", "DEFAULT", "0", startDate, null},
                {"12", "2", "1.38", "1.65", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"13", "1", "2.00", "2.40", "0.2000", "DEFAULT", "0", startDate, null},
                {"13", "1", "2.20", "2.64", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"13", "2", "2.00", "2.40", "0.2000", "DEFAULT", "0", startDate, null},
                {"13", "2", "2.20", "2.64", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"14", "1", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"14", "1", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"14", "2", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"14", "2", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"15", "1", "0.50", "0.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"15", "1", "0.55", "0.66", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"15", "2", "0.50", "0.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"15", "2", "0.55", "0.66", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"16", "1", "2.00", "2.40", "0.2000", "DEFAULT", "0", startDate, null},
                {"16", "1", "2.20", "2.64", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"16", "2", "2.00", "2.40", "0.2000", "DEFAULT", "0", startDate, null},
                {"16", "2", "2.20", "2.64", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"17", "1", "3.00", "3.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"17", "1", "3.30", "3.96", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"17", "2", "3.00", "3.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"17", "2", "3.30", "3.96", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"18", "1", "5.00", "6.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"18", "1", "5.50", "6.60", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"18", "2", "5.00", "6.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"18", "2", "5.50", "6.60", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"19", "1", "3.60", "4.32", "0.2000", "DEFAULT", "0", startDate, null},
                {"19", "1", "3.96", "4.75", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"19", "2", "3.60", "4.32", "0.2000", "DEFAULT", "0", startDate, null},
                {"19", "2", "3.96", "4.75", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"20", "1", "12.00", "14.40", "0.0550", "DEFAULT", "0", startDate, null},
                {"20", "1", "13.20", "15.84", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"20", "1", "9.50", "11.40", "0.0550", "DEFAULT", "1", startDate, null}, // Promo
                {"20", "1", "10.45", "12.54", "0.0550", "BASE_FOR_DISCOUNT", "1", startDate, null},
                {"20", "2", "12.00", "14.40", "0.0550", "DEFAULT", "0", startDate, null},
                {"20", "2", "13.20", "15.84", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},

                // Product 21 to 30
                {"21", "1", "8.00", "9.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"21", "1", "8.80", "10.56", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"21", "2", "8.00", "9.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"21", "2", "8.80", "10.56", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"22", "1", "2.50", "3.00", "0.0550", "DEFAULT", "0", startDate, null},
                {"22", "1", "2.75", "3.30", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"22", "2", "2.50", "3.00", "0.0550", "DEFAULT", "0", startDate, null},
                {"22", "2", "2.75", "3.30", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"23", "1", "2.00", "2.40", "0.0550", "DEFAULT", "0", startDate, null},
                {"23", "1", "2.20", "2.64", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"23", "2", "2.00", "2.40", "0.0550", "DEFAULT", "0", startDate, null},
                {"23", "2", "2.20", "2.64", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"24", "1", "5.00", "6.00", "0.0550", "DEFAULT", "0", startDate, null},
                {"24", "1", "5.50", "6.60", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"24", "2", "5.00", "6.00", "0.0550", "DEFAULT", "0", startDate, null},
                {"24", "2", "5.50", "6.60", "0.0550", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"25", "1", "3.00", "3.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"25", "1", "3.30", "3.96", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"25", "2", "3.00", "3.60", "0.2000", "DEFAULT", "0", startDate, null},
                {"25", "2", "3.30", "3.96", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"26", "1", "1.00", "1.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"26", "1", "1.10", "1.32", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"26", "2", "1.00", "1.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"26", "2", "1.10", "1.32", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"27", "1", "5.00", "6.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"27", "1", "5.50", "6.60", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"27", "2", "5.00", "6.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"27", "2", "5.50", "6.60", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"28", "1", "6.00", "7.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"28", "1", "6.60", "7.92", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"28", "2", "6.00", "7.20", "0.2000", "DEFAULT", "0", startDate, null},
                {"28", "2", "6.60", "7.92", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"29", "1", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"29", "1", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"29", "2", "1.50", "1.80", "0.2000", "DEFAULT", "0", startDate, null},
                {"29", "2", "1.65", "1.98", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},

                {"30", "1", "2.50", "3.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"30", "1", "2.75", "3.30", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null},
                {"30", "2", "2.50", "3.00", "0.2000", "DEFAULT", "0", startDate, null},
                {"30", "2", "2.75", "3.30", "0.2000", "BASE_FOR_DISCOUNT", "0", startDate, null}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreatePrice($input: PriceRecordInput!) {
                  createPrice(input: $input) {
                    id
                    priceExcludingTax
                  }
                }
                """;

        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through prices and send mutations
        for (String[] price : prices) {
            try {
                // Handling endDateTime null case
                // Array indices: 0=id, 1=store, 2=priceEx, 3=priceInc, 4=vat, 5=usage, 6=prio, 7=start, 8=end
                String endDateTimeJson = price[8] != null ? "\"" + price[8] + "\"" : "null";

                // Building JSON variables with PriceUsage
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "productId": %s,
                            "storeId": %s,
                            "priceUsage": "%s",
                            "priceExcludingTax": %s,
                            "priceIncludingTax": %s,
                            "vatRate": %s,
                            "priority": %s,
                            "startDateTime": "%s",
                            "endDateTime": %s
                          }
                        }
                        """,
                        price[0], price[1], price[5], price[2], price[3], price[4], price[6], price[7], endDateTimeJson
                );

                String compactVariables = variablesJson.replace("\n", "");

                // Build of full JSON payload
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
                    System.out.println("Success for Product " + price[0] + "/Store " + price[1] + " (" + price[5] + "): " + response.body());
                } else {
                    System.err.println("Error for Product " + price[0] + "/Store " + price[1] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for Product " + price[0] + "/Store " + price[1] + ": " + e.getMessage());
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