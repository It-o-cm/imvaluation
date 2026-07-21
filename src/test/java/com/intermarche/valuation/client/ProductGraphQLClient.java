package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test Product creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of products using Java 11+ HttpClient.
 * <p>
 * Includes 'brand' field and 'referenceVolume' field (Estimated for transport) in product creation payload.
 */
public class ProductGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    // Auth (Basic Auth for dev)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute product creation.
     * <p>
     * Prepares a list of 30 sample products and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     * <p>
     * <b>Note on Volume:</b> ReferenceVolume is estimated based on weight.
     * Liquids: Volume approx = Weight (Density ~1 kg/L).
     * Solids: Volume approx = Weight * 2.5 (Accounting for packaging/air, Density ~0.4 kg/L).
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data (30 Products)
        // Structure: {EAN, Name, Description, Brand, ReferenceWeight, ReferenceVolume, ProductType, UnitName, Active}
        String[][] products = {
                {"3300000000001", "Pommes Golden", "Pommes fraîches bio", "Brand A", "1.000", "2.500", "WEIGHT", "kg", "true"}, // Solide (volumineux)
                {"3300000000002", "Lait UHT 1L", "Lait demi-écrémé", "Brand A", "1.000", "1.000", "UNIT", "L", "true"},   // Liquide
                {"3300000000003", "Baguette Tradition", "Pain de tradition", "Brand B", "0.250", "0.600", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000004", "Café Grains 500g", "Café moulu arabica", "Brand C", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide (brique)
                {"3300000000005", "Pâtes Penne 500g", "Pâtes alimentaires", "Brand D", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000006", "Huile d'Olive 1L", "Huile vierge extra", "Brand E", "1.000", "1.000", "UNIT", "L", "true"},   // Liquide
                {"3300000000007", "Eau Minérale 1.5L", "Eau de source", "Brand F", "1.500", "1.500", "UNIT", "L", "true"},  // Liquide
                {"3300000000008", "Jambon Blanc 100g", "Tranches de jambon", "Brand G", "0.100", "0.250", "WEIGHT", "kg", "true"}, // Solide (barquette)
                {"3300000000009", "Beurre Doux 250g", "Motte de beurre", "Brand H", "0.250", "0.600", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000010", "Yaourt Nature 4x125g", "Pots de yaourt", "Brand I", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000011", "Coca-Cola 1.5L", "Boisson gazeuse", "Brand C", "1.500", "1.500", "UNIT", "L", "true"},  // Liquide
                {"3300000000012", "Orangina 1.25L", "Boisson aux agrumes", "Brand O", "1.250", "1.250", "UNIT", "L", "true"},  // Liquide
                {"3300000000013", "Biscuits Chocolat 200g", "Paquet de biscuits", "Brand P", "0.200", "0.500", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000014", "Chips Classiques 150g", "Chips de pomme de terre", "Brand L", "0.150", "0.400", "WEIGHT", "kg", "true"}, // Solide (soufflé)
                {"3300000000015", "Sauce Tomate 500g", "Sauce bolognaise", "Brand M", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000016", "Purée de Pomme de Terre 500g", "Purée instantanée", "Brand K", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000017", "Concombre", "Légume frais", "Brand V", "0.300", "0.750", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000018", "Tomates Cerises 500g", "Tomates rondes", "Brand W", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000019", "Oeufs Bio 6 unités", "Oeufs frais gros", "Brand X", "0.360", "0.900", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000020", "Poulet Rôti 1.2kg", "Poulet fermier", "Brand Y", "1.200", "3.000", "WEIGHT", "kg", "true"}, // Solide (volumineux)
                {"3300000000021", "Saumon Fume 200g", "Tranches de saumon", "Brand Z", "0.200", "0.500", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000022", "Riz Basmati 1kg", "Riz long grain", "Brand A", "1.000", "2.500", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000023", "Lentilles Vertes 500g", "Légumes secs", "Brand B", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000024", "Miel d'Acacia 500g", "Pot de miel", "Brand M", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000025", "Lessive Liquide 1.5L", "Lessive linge", "Brand L", "1.500", "1.500", "UNIT", "L", "true"},  // Liquide
                {"3300000000026", "Eponge Vaisselle 3 unités", "Eponges abrasives", "Brand S", "0.100", "0.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000027", "Coton Bio 500g", "Disques de coton", "Brand C", "0.500", "1.250", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000028", "Piles AA 4 unités", "Piles alcalines", "Brand D", "0.080", "0.200", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000029", "Chewing-Gum Menthe", "Pommes de menthe", "Brand C", "0.050", "0.125", "WEIGHT", "kg", "true"}, // Solide
                {"3300000000030", "Dentifrice Menthe 100ml", "Tube dentifrice", "Brand D", "0.100", "0.100", "UNIT", "L", "true"}   // Liquide
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template (using Text Block for readability)
        // Note: \n in query are actual newlines. We will remove them to make valid JSON string.
        String mutationTemplate = """
                mutation CreateProduct($input: ProductRecordInput!) {
                  createProduct(input: $input) {
                    id
                    ean
                    name
                  }
                }
                """;

        // Remove newlines from query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through products and send mutations
        for (String[] product : products) {
            try {
                // Using Text Block to build JSON variables clearly
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "ean": "%s",
                            "name": "%s",
                            "description": "%s",
                            "brand": "%s",
                            "referenceWeight": %s,
                            "referenceVolume": %s,
                            "productType": "%s",
                            "unitName": "%s",
                            "active": %s
                          }
                        }
                        """,
                        product[0], product[1], product[2], product[3], product[4], product[5], product[6], product[7], product[8]
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
                    System.out.println("Success for " + product[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + product[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + product[0] + ": " + e.getMessage());
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