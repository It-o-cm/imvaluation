package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test ProductFamily bulk import.
 * <p>
 * This class sends a CSV payload containing a list of product families to the
 * {@code /product-families/import} endpoint using Java 11+ HttpClient.
 * <p>
 * CSV Format: code|description|flags|product_eans|family_codes
 */
public class ProductFamilyImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/product-families/import";

    // Identifiants définis dans application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute import process.
     * <p>
     * Prepares a CSV string containing sample data and sends it
     * via POST request to the configured {@link #IMPORT_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {code, description, flags, product_eans, family_codes}
        String csvData = """
                code|description|flags|product_eans|family_codes
                POMMES|Pommes à croquer|TRADITIONAL,RESTAURANT_VOUCHER_ELIGIBLE|3300000000001,3300000000004|
                RACINES|Légumes racines||3300000000017,3300000000018||
                FRUITS|Rayon Fruits||||POMMES
                LEGUMES|Rayon Légumes||||RACINES
                EAU_MINERALE|Eaux Minérales|RESTAURANT_VOUCHER_ELIGIBLE|3300000000007|
                SODAS|Sodas||3300000000011,3300000000012||
                BOISSONS|Rayon Boissons||||EAU_MINERALE,SODAS
                ALIMENTAIRE|Rayon Alimentaire||||FRUITS,LEGUMES,BOISSONS
                CUISSON|Instruments de Cuisine||3300000000031,3300000000032,3300000000033|
                """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Créer le header d'authentification Basic
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