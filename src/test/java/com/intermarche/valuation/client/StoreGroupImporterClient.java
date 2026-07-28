package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Client for importing StoreGroup hierarchies via CSV.
 * <p>
 * Defines Parent Groups and links them to existing Stores (e.g., 0101) and
 * existing Sub-Groups (e.g., DEPT_59) using a CSV format.
 */
public class StoreGroupImporterClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String IMPORT_URL = BASE_URL + "/store-groups/import";

    // Credentials defined in application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        // CSV Data:
        // Column 1: group_code
        // Column 2: group_name
        // Column 3: store_codes (List separated by semicolon)
        // Column 4: store_group_codes (List separated by semicolon)
        String csvData = """
                group_code|group_name|store_codes|store_group_codes
                DEPT_59|Département du Nord|0104|
                DEPT_75|Département Paris||
                REGION_NORTH|Région Nord|0101;0102;0103|DEPT_59
                REGION_SUD|Région Sud|0105|DEPT_75
                """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Create Basic Authentication header
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