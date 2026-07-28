package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test Price bulk import.
 * <p>
 * This class sends a CSV payload containing a list of prices to the
 * {@code /prices/import} endpoint using Java 11+ HttpClient.
 * <p>
 * Authentication is handled via Basic Auth using credentials defined in the class.
 */
public class PriceImporterClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String IMPORT_URL = BASE_URL + "/prices/import";

    // Identifiants définis dans application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute import process.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {ean, storeCode, priceExcludingTax, priceIncludingTax, vatRate, priceUsage, priority, startDateTime, endDateTime}
        // Start Date: 12 Jan 2026
        String startDate = "2026-01-12T00:00:00";

        String csvData = """
            ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime
            3300000000001|0101|1.00|1.20|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000001|0101|1.10|1.32|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000001|0101|0.90|1.08|0.2000|DEFAULT|1|<<START_DATE>>|
            3300000000001|0101|0.99|1.19|0.2000|BASE_FOR_DISCOUNT|1|<<START_DATE>>|
            3300000000002|0101|2.50|3.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000002|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000002|0101|2.30|2.76|0.2000|DEFAULT|1|<<START_DATE>>|
            3300000000002|0101|2.53|3.04|0.2000|BASE_FOR_DISCOUNT|1|<<START_DATE>>|
            3300000000003|0101|0.80|0.96|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000003|0101|0.88|1.06|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000004|0101|3.50|4.20|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000004|0101|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000005|0101|1.20|1.44|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000005|0102|1.20|1.44|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000005|0101|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000005|0102|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000006|0101|5.00|6.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000006|0102|5.00|6.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000006|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000006|0102|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000007|0101|0.50|0.60|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000007|0102|0.50|0.60|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000007|0101|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000007|0102|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000008|0101|2.00|2.40|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000008|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000009|0101|2.50|3.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000009|0102|2.50|3.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000009|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000009|0102|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000010|0101|1.50|1.80|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000010|0102|1.50|1.80|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000010|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000010|0102|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000011|0101|1.80|2.16|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000011|0102|1.80|2.16|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000011|0101|1.98|2.38|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000011|0102|1.98|2.38|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000012|0101|1.90|2.28|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000012|0102|1.90|2.28|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000012|0101|2.09|2.51|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000012|0102|2.09|2.51|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000013|0101|2.00|2.40|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000013|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000014|0101|1.50|1.80|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000014|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000015|0101|1.80|2.16|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000015|0101|1.98|2.38|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000016|0101|2.00|2.40|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000016|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000017|0101|3.00|3.60|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000017|0102|3.00|3.60|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000017|0101|3.30|3.96|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000017|0102|3.30|3.96|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000018|0101|4.00|4.80|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000018|0101|4.40|5.28|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000019|0101|3.50|4.20|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000019|0102|3.50|4.20|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000019|0101|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000019|0102|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000020|0101|10.00|10.55|0.0550|DEFAULT|0|<<START_DATE>>|
            3300000000020|0101|11.00|11.61|0.0550|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000020|0101|9.50|10.02|0.0550|DEFAULT|1|<<START_DATE>>|
            3300000000020|0101|10.45|11.02|0.0550|BASE_FOR_DISCOUNT|1|<<START_DATE>>|
            3300000000020|0102|10.00|10.55|0.0550|DEFAULT|0|<<START_DATE>>|
            3300000000020|0102|11.00|11.61|0.0550|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000021|0101|8.00|9.60|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000021|0101|8.80|10.56|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000022|0101|2.50|2.64|0.0550|DEFAULT|0|<<START_DATE>>|
            3300000000022|0101|2.75|2.90|0.0550|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000023|0101|2.00|2.11|0.0550|DEFAULT|0|<<START_DATE>>|
            3300000000023|0101|2.20|2.32|0.0550|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000024|0101|5.00|6.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000024|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000025|0101|3.00|3.60|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000025|0101|3.30|3.96|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000026|0101|2.00|2.40|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000026|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000027|0101|4.00|4.80|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000027|0101|4.40|5.28|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000028|0101|6.00|7.20|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000028|0101|6.60|7.92|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000029|0101|1.50|1.80|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000029|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000030|0101|2.50|3.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000030|0102|2.50|3.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000030|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000030|0102|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000031|0101|12.00|14.40|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000031|0101|13.20|15.84|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000032|0101|15.00|18.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000032|0101|16.50|19.80|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            3300000000033|0101|25.00|30.00|0.2000|DEFAULT|0|<<START_DATE>>|
            3300000000033|0101|27.50|33.00|0.2000|BASE_FOR_DISCOUNT|0|<<START_DATE>>|
            """;

        // Replace placeholders with actual date
        csvData = csvData.replace("<<START_DATE>>", startDate);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Créer le header d'authentification Basic
        String auth = USERNAME + ":" + PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMPORT_URL))
                .header("Content-Type", "text/plain")
                // On remplace "Bearer" par "Basic"
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