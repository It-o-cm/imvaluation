package com.intermarche.valuation.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Simple HTTP client to test Product bulk import.
 * <p>
 * This class sends a CSV payload containing a list of products to
 * {@code /products/import} endpoint using Java 11+ HttpClient.
 * <p>
 * Includes 'brand' field and 'referenceVolume' field (Estimated for transport) in the CSV format.
 */
public class ProductImporterClient {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String IMPORT_URL = BASE_URL + "/products/import";

    // Identifiants définis dans application-dev.properties
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    /**
     * Main method to execute import process.
     * <p>
     * Prepares a CSV string containing sample data and sends it
     * via POST request to the configured {@link #IMPORT_URL}.
     * The CSV format includes the new 'referenceVolume' field and Cookware products.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {ean, name, description, brand, referenceWeight, referenceVolume, productType, unitName, active}
        String csvData = """
                ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active
                3300000000001|Pommes Golden|Pommes fraîches bio|Brand A|1.000|2.500|WEIGHT|kg|true
                3300000000002|Lait UHT 1L|Lait demi-écrémé|Brand B|1.000|1.000|UNIT|L|true
                3300000000003|Baguette Tradition|Pain de tradition|Brand C|0.250|0.600|UNIT|kg|true
                3300000000004|Café Grains 500g|Café moulu arabica|Brand D|0.500|1.250|UNIT|kg|true
                3300000000005|Pâtes Penne 500g|Pâtes alimentaires|Brand E|0.500|1.250|WEIGHT|kg|true
                3300000000006|Huile d'Olive 1L|Huile vierge extra|Brand F|1.000|1.000|UNIT|L|true
                3300000000007|Eau Minérale 1.5L|Eau de source|Brand G|1.500|1.500|UNIT|L|true
                3300000000008|Jambon Blanc 100g|Tranches de jambon|Brand H|0.100|0.250|WEIGHT|kg|true
                3300000000009|Beurre Doux 250g|Motte de beurre|Brand I|0.250|0.600|WEIGHT|kg|true
                3300000000010|Yaourt Nature 4x125g|Pots de yaourt|Brand J|0.500|1.250|UNIT|kg|true
                3300000000011|Coca-Cola 1.5L|Boisson gazeuse|Brand K|1.500|1.500|UNIT|L|true
                3300000000012|Orangina 1.25L|Boisson aux agrumes|Brand L|1.250|1.250|UNIT|L|true
                3300000000013|Biscuits Chocolat 200g|Paquet de biscuits|Brand M|0.200|0.500|UNIT|kg|true
                3300000000014|Chips Classiques 150g|Chips de pomme de terre|Brand N|0.150|0.400|UNIT|kg|true
                3300000000015|Sauce Tomate 500g|Sauce bolognaise|Brand O|0.500|1.250|UNIT|kg|true
                3300000000016|Purée de Pomme de Terre 500g|Purée instantanée|Brand P|0.500|1.250|UNIT|kg|true
                3300000000017|Concombre|Légume frais|Brand Q|0.300|0.750|WEIGHT|kg|true
                3300000000018|Tomates Cerises 500g|Tomates rondes|Brand R|0.500|1.250|WEIGHT|kg|true
                3300000000019|Oeufs Bio 6 unités|Oeufs frais gros|Brand S|0.360|0.900|UNIT|kg|true
                3300000000020|Poulet Rôti 1.2kg|Poulet fermier|Brand T|1.200|3.000|WEIGHT|kg|true
                3300000000021|Saumon Fume 200g|Tranches de saumon|Brand U|0.200|0.500|WEIGHT|kg|true
                3300000000022|Riz Basmati 1kg|Riz long grain|Brand V|1.000|2.500|UNIT|kg|true
                3300000000023|Lentilles Vertes 500g|Légumes secs|Brand W|0.500|1.250|UNIT|kg|true
                3300000000024|Miel d'Acacia 500g|Pot de miel|Brand X|0.500|1.250|UNIT|kg|true
                3300000000025|Lessive Liquide 1.5L|Lessive linge|Brand Y|1.500|1.500|UNIT|L|true
                3300000000026|Eponge Vaisselle 3 unités|Eponges abrasives|Brand Z|0.100|0.250|UNIT|kg|true
                3300000000027|Coton Bio 500g|Disques de coton|Brand A1|0.500|1.250|UNIT|kg|true
                3300000000028|Piles AA 4 unités|Piles alcalines|Brand B1|0.080|0.200|UNIT|kg|true
                3300000000029|Chewing-Gum Menthe|Pommes de menthe|Brand C1|0.050|0.125|UNIT|kg|true
                3300000000030|Dentifrice Menthe 100ml|Tube dentifrice|Brand D1|0.100|0.100|UNIT|L|true
                3300000000031|Poêle Antiadhésive 28cm|Poêle fonte alum|Tefal|0.800|0.000|UNIT|pcs|true
                3300000000032|Casserole Inox 20cm|Casserole acier inox|Staub|1.200|0.000|UNIT|pcs|true
                3300000000033|Set de Couteaux Chef|Couteaux acier inox|Sabatier|0.500|0.000|UNIT|pcs|true
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