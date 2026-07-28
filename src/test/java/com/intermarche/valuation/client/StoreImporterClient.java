package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class StoreImporterClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String IMPORT_URL = BASE_URL + "/stores/import";

    // Identifiants définis dans application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    public static void main(String[] args) {
        String csvData = """
                code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude
                0101|Intermarché Test 1|1 Rue du Test|ZI Nord|59000|Lille|France|50.63|3.06
                0102|Intermarché Test 2|12 Avenue des Fleurs||33000|Bordeaux|France|44.83|-0.57
                0103|Intermarché Test 3|99 Boulevard de la Liberte|Etage 2|69000|Lyon|France|45.75|4.85
                0104|Intermarché Test 4|25 Rue de Rivoli||75001|Paris|France|48.86|2.33
                0105|Intermarché Test 5|8 Place de la Gare|Bat C|67000|Strasbourg|France|48.57|7.75
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