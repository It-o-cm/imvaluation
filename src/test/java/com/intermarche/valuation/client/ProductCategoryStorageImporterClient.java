package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test ProductCategoryStorage bulk import.
 * <p>
 * This class sends a CSV payload containing a list of product-category links to the
 * {@code /product-category-storages/import} endpoint using Java 11+ HttpClient.
 * <p>
 * Authentication is handled via Basic Auth using credentials defined in the class.
 * <p>
 * Note: In this test, all products (EANs) are linked to category paths.
 * The endpoint now expects Product EANs instead of Database IDs.
 */
public class ProductCategoryStorageImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/product-category-storages/import";

    // Credentials defined in application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute the import process.
     * <p>
     * Prepares a CSV string mapping Product EANs to Category Hierarchy levels and sends it
     * via POST request to the configured {@link #IMPORT_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {productEan|level1|level2|level3|level4|level5}
        // Note: Using Product EANs (13-digit codes) instead of IDs.
        // We use dummy EANs here for the test. Ensure these Products exist in your DB.
        String csvData = """
                productEan|level1|level2|level3|level4|level5
                3300000000001|Food|Fresh|Fruits & Vegetables|Local|Organic
                3300000000002|Food|Fresh|Fruits & Vegetables|Local|Organic
                3300000000003|Food|Fresh|Bakery|Traditional|Baguettes
                3300000000004|Food|Pantry|Beverages|Hot|Coffee
                3300000000005|Food|Pantry|Groceries|Pasta|Italy
                3300000000006|Food|Pantry|Oils & Vinegars|Olive|Extra Virgin
                3300000000007|Food|Pantry|Beverages|Cold|Water
                3300000000008|Food|Fresh|Deli|Meats|Hams
                3300000000009|Food|Fresh|Dairy|Butter|Spreadable
                3300000000010|Food|Fresh|Dairy|Yogurts|Fruit
                3300000000011|Food|Pantry|Beverages|Sodas|Cola
                3300000000012|Food|Pantry|Beverages|Sodas|Orange Juice
                3300000000013|Food|Pantry|Snacks|Biscuits|Chocolate
                3300000000014|Food|Pantry|Snacks|Crisps|Classic
                3300000000015|Food|Pantry|Sauces|Tomato|Bolognaise
                3300000000016|Food|Pantry|Groceries|Potatoes|Flakes
                3300000000017|Food|Fresh|Fruits & Vegetables|Vegetables|Cucumber
                3300000000018|Food|Fresh|Fruits & Vegetables|Vegetables|Tomatoes
                3300000000019|Food|Fresh|Dairy|Eggs|Farm
                3300000000020|Food|Fresh|Meat|Poultry|Roasted
                3300000000021|Food|Fresh|Seafood|Fish|Smoked
                3300000000022|Food|Pantry|Groceries|Rice|Long Grain
                3300000000023|Food|Pantry|Groceries|Legumes|Green
                3300000000024|Food|Pantry|Groceries|Honey|Acacia
                3300000000025|Household|Cleaning|Liquids|Detergent|
                3300000000026|Household|Cleaning|Sponges|Kitchen|
                3300000000027|Health & Beauty|Hair|Accessories|Cotton|Pads
                3300000000028|Electronics|Small Appliances|Batteries|AA|
                3300000000029|Food|Pantry|Confectionery|Gum|Mint
                3300000000030|Health & Beauty|Toothpaste|Oral Care|Mint|
                """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Create Basic Auth header
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