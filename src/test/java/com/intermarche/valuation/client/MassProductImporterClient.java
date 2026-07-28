package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * HTTP client to test import of 80000 generic products via CSV.
 * <p>
 * Generates a CSV file programmatically with a loop and sends it
 * to {@code /products/import} endpoint.
 * <p>
 * Includes 'brand' field and 'referenceVolume' field (Estimated for transport) in CSV generation.
 */
public class MassProductImporterClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String IMPORT_URL = BASE_URL + "/products/import";

    // Identifiants définis dans application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";
    private static final int NB_PRODUCTS = 80000;

    /**
     * Main method to generate 80000 products and send them via CSV.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        System.out.println("Enter import");
        StringBuilder csvBuilder = new StringBuilder();

        // 1. Header (Updated to include referenceVolume)
        // Structure: {ean, name, description, brand, referenceWeight, referenceVolume, productType, unitName, active}
        csvBuilder.append("ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active\n");

        // 2. Generate 80000 lines of generic products
        for (int i = 1; i <= NB_PRODUCTS; i++) {
            // Format: 3300000000001 ...
            String ean = String.format("3300%07d", i);

            String name = "Produit Generique " + String.format("%04d", i);
            String description = "Description generique pour le numero " + i;
            String brand = "MarqueGenerique";

            // Estimated Volume for Logistics: 2.5L per kg (average for solids/packaging)
            // Weight is 1.000 in the generation, so Volume is 2.500 L
            String volume = "2.500";

            // Alternate Weight/Unit to simulate variety
            if (i % 2 == 0) {
                csvBuilder.append(String.format("%s|%s|%s|%s|%s|%s|WEIGHT|kg|true\n", ean, name, description, brand, "1.000", volume));
            } else {
                csvBuilder.append(String.format("%s|%s|%s|%s|%s|%s|UNIT|pcs|true\n", ean, name, description, brand, "1.000", volume));
            }
        }

        String csvData = csvBuilder.toString();

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

            // On n'affiche pas tout le body car c'est trop gros (80000 lignes)
            System.out.println("Server Response Length: " + response.body());

            if (response.statusCode() == 200) {
                System.out.println("Import of "+NB_PRODUCTS+" products successful!");
            } else {
                System.err.println("Error during processing.");
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}