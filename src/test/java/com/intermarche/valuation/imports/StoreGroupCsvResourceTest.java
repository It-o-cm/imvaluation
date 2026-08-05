package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.*;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link StoreGroupCsvResource}.
 * <p>
 * Tests the CSV import endpoint for StoreGroups, covering creation, updates, hierarchical linking,
 * and error handling under the simplified logic (assumes sorted CSV).
 */
@QuarkusTest
public class StoreGroupCsvResourceTest {

    /**
     * The StoreGroup CSV resource under test.
     */
    @Inject
    StoreGroupCsvResource resource;

    /**
     * The TransactionManager for manual transaction control in tests.
     */
    @Inject
    TransactionManager tm;

    /**
     * Cleans the database before each test to ensure isolation.
     * <p>
     * Every {@code @QuarkusTest} class shares one application and one database, so this
     * clears the whole reference set rather than only the entities this class handles: a
     * row left by an earlier class would otherwise survive and skew the assertions.
     * <p>
     * The order is the reverse of the dependencies. Prices hold a foreign key on both
     * stores and products, and offers on stores and store groups, so clearing a parent
     * before its children fails on a referential integrity violation.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        ProductCategoryStorage.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Tests the update of an existing group when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingGroup_NameChanged() {
        // 1. Setup
        withTransaction(() -> {
            StoreGroup g = new StoreGroup();
            g.code = "G_UPDATE";
            g.name = "Old Name";
            g.persist();
            return true;
        });

        // 2. Act
        String csvContent = "code|name|stores|groups\n" +
                "G_UPDATE|New Name||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        // 3. Assert
        StoreGroup updated = StoreGroup.findByCode("G_UPDATE");
        assertEquals("New Name", updated.name);
    }

    /**
     * Tests the update of an existing group when the incoming data is identical.
     * Verifies checksum optimization (no DB write for the entity itself).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingGroup_NameSame_NoUpdate() {
        // 1. Setup
        withTransaction(() -> {
            StoreGroup g = new StoreGroup();
            g.code = "G_SAME";
            g.name = "Same Name";
            g.persist();
            return true;
        });

        // 2. Act
        String csvContent = "code|name|stores|groups\n" +
                "G_SAME|Same Name||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the error handling when a referenced Sub-Group does not exist.
     * <p>
     * Since the simplified logic creates the group and links it in the same transaction,
     * a failure to link will cause the entire transaction for that line to roll back.
     * The group will not be persisted.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportGroup_SubGroupNotFound_Rollback() {
        String csvContent = "code|name|stores|groups\n" +
                "G_BAD_SUB|Bad||NON_EXISTENT_SUB";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0")) // Transaction rolled back
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("StoreGroup 'NON_EXISTENT_SUB' not found"));

        // Verify DB is clean (Group was not persisted due to rollback)
        StoreGroup g = StoreGroup.findByCode("G_BAD_SUB");
        assertNull(g);
    }

    /**
     * Tests the handling of empty lists for stores and sub-groups.
     * <p>
     * Scenario: The CSV contains valid parent groups but empty lists in columns 3 and 4.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportGroups_EmptyStoresAndGroupsLists() {
        String csvContent = "code|name|stores|groups\n" +
                "G01|Empty Lists|||\n" + // Empty stores (col 3) and groups (col 4)
                "G02|Also Empty|||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"));

        StoreGroup g1 = StoreGroup.findByCode("G01");
        assertNotNull(g1);
        assertTrue(g1.stores.isEmpty());   // Verify no stores linked
        assertTrue(g1.storeGroups.isEmpty()); // Verify no groups linked
    }

    /**
     * Tests the fallback mechanism when a linking error occurs.
     * <p>
     * Scenario: The CSV contains a valid line (G_OK) and an invalid line (G_BAD with bad store code).
     * Since they are in the same chunk, the initial transaction fails.
     * The fallback logic retries 1-by-1.
     * G_OK succeeds. G_BAD fails.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportGroups_FallbackLogic_OnLinkingError() {
        // Setup Store
        withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = new Adresse();
            s.address.city = "Paris";
            s.persist();
            return true;
        });

        String csvContent = "code|name|stores|groups\n" +
                "G_OK|Good Group|S001|\n" +
                "G_BAD|Bad Group|BAD_STORE|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1")) // Only G_OK succeeds
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));

        // Verify Good group has store and is persisted
        StoreGroup gOk = StoreGroup.findByCode("G_OK");
        assertNotNull(gOk);
        assertEquals(1, gOk.stores.size());

        // Verify Bad group is NOT in DB (transaction rolled back)
        StoreGroup gBad = StoreGroup.findByCode("G_BAD");
        assertNull(gBad);
    }

    /**
     * Tests the edge case of empty parsed lines.
     */
    @Test
    void testProcessChunkWithFallback_EmptyParsedLines() {
        List<ImporterCsvResource.LineData> parsedLines = Collections.emptyList();
        Set<String> targetCodes = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = resource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Tests the security configuration.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "code|name|stores|groups\n" +
                "G01|Name||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(401);
    }

    /**
     * Tests {@link StoreGroupCsvResource#parseSemicolonCodes(String)} with a null input.
     * <p>
     * Ensures the method handles null safely and returns an empty array.
     */
    @Test
    void testParseSemicolonCodes_NullInput_ReturnsEmptyArray() {
        // Act
        String[] result = resource.parseSemicolonCodes(null);

        // Assert
        assertNotNull(result, "Result should not be null");
        assertEquals(0, result.length, "Result array should be empty");
        assertArrayEquals(new String[0], result, "Result should be an empty array");
    }

    /**
     * Tests {@link StoreGroupCsvResource#getCodesFromColumn(List, int)} with valid data.
     * <p>
     * Verifies that codes from multiple lines are correctly extracted, split by semicolon,
     * and aggregated into a single Set (handling duplicates automatically).
     */
    @Test
    void testGetCodesFromColumn_PartsNotNull_AggregatesCorrectly() {
        // Arrange
        // Line 1 contains "CODE_A" and "CODE_B" in column index 2
        ImporterCsvResource.LineData line1 = new ImporterCsvResource.LineData(
                1,
                "PARENT_1",
                new String[]{"ignore", "ignore", "CODE_A;CODE_B", "ignore"}
        );
        // Line 2 contains "CODE_B" (duplicate) and "CODE_C" in column index 2
        ImporterCsvResource.LineData line2 = new ImporterCsvResource.LineData(
                2,
                "PARENT_2",
                new String[]{"ignore", "ignore", "CODE_B;CODE_C", "ignore"}
        );
        List<ImporterCsvResource.LineData> lines = List.of(line1, line2);
        int columnIndex = 2;
        // Act
        Set<String> result = resource.getCodesFromColumn(lines, columnIndex);
        // Assert
        assertNotNull(result);
        assertEquals(3, result.size(), "Should contain 3 unique codes: A, B, and C");
        assertTrue(result.contains("CODE_A"));
        assertTrue(result.contains("CODE_B"));
        assertTrue(result.contains("CODE_C"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testPrepareContextForLine_CalledDuringFallback() {
        // 1. Setup : On crée les données qui existent déjà en base
        String existingGroupCode = "EXISTING_GROUP";
        String validStoreCode = "STORE_001";

        withTransaction(() -> {
            // Le groupe cible (doit être trouvé par findEntityForLine)
            StoreGroup group = new StoreGroup();
            group.code = existingGroupCode;
            group.name = "Ancien Nom";
            group.persist();

            // Un store valide (pour que la ligne "bonne" fonctionne)
            Store store = new Store();
            store.code = validStoreCode;
            store.name = "Store Valide";
            store.address = new Adresse();
            store.address.city = "Paris";
            store.persist();
            return true;
        });

        // 2. Construction du CSV "Piégé"
        // Ligne 1 : VALIDE. Met à jour 'EXISTING_GROUP'.
        // Ligne 2 : INVALIDE. Référence un store qui n'existe pas -> va faire planter le Bulk.
        String csvContent = "code|name|stores|groups\n" +
                existingGroupCode + "|Nouveau Nom|" + validStoreCode + "|\n" + // Ligne 1
                "ERROR_GROUP|Nom Error|STORE_INCONNU|";                       // Ligne 2 (Provoque l'erreur)

        // 3. Appel
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200) // Le endpoint gère les erreurs et renvoie 200
                .body(containsString("\"errors\"")) // On vérifie qu'une erreur a bien été remontée (preuve du fallback)
                .body(containsString("STORE_INCONNU"));

        // 4. Vérification
        // Si le code est passé dans 'prepareContextForLine' (Fallback 1-by-1),
        // le groupe EXISTING_GROUP a été mis à jour correctement.

        StoreGroup updatedGroup = StoreGroup.findByCode(existingGroupCode);

        assertNotNull(updatedGroup, "Le groupe doit exister");

        // Si le fallback a marché et que le groupe a été trouvé (bloc if exécuté),
        // la mise à jour s'applique.
        assertEquals("Nouveau Nom", updatedGroup.name, "Le nom doit avoir été mis à jour");

        // On vérifie aussi que le lien store a été fait
        assertEquals(1, updatedGroup.stores.size());
        assertEquals(validStoreCode, updatedGroup.stores.iterator().next().code);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testPrepareContextForLine_LoopFillsChildGroupMap() {
        // 1. Setup : Créer le SOUS-GROUPE cible en base
        // C'est indispensable pour que la requête "IN" retourne des résultats et remplisse la liste 'groups'.
        String childGroupCode = "CHILD_GRP_01";

        withTransaction(() -> {
            StoreGroup child = new StoreGroup();
            child.code = childGroupCode;
            child.name = "Child Group Name";
            child.persist();
            return true;
        });

        // 2. Construction du CSV
        // Ligne 1 (Cible) : Crée un PARENT qui lie le CHILD_GRP_01.
        // Ligne 2 (Bombe) : Provoque une erreur (Store manquant) pour forcer le fallback.
        String csvContent = "code|name|stores|groups\n" +
                "PARENT_01|Parent Name||" + childGroupCode + "\n" + // On lie ici
                "ERROR_GROUP|Error Name|MISSING_STORE|";           // Erreur ici

        // 3. Action
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/store-groups/import")
                .then()
                .statusCode(200)
                .body(containsString("\"errors\""))
                .body(containsString("MISSING_STORE"));

        // 4. Assertion : Vérifier que le lien existe
        // Si la boucle 'for (StoreGroup g : groups)' n'avait pas tourné (ou si la liste était vide),
        // alors 'childGroupMap' serait vide.
        // Dans 'linkSubGroups', le code ferait : childGroupMap.get(...) -> null.
        // Cela lancerait une "IllegalArgumentException: StoreGroup 'CHILD_GRP_01' not found",
        // et le fallback aurait marqué cette ligne comme erreur.

        StoreGroup parent = StoreGroup.findByCode("PARENT_01");
        assertNotNull(parent, "Le parent aurait dû être créé par le fallback");

        // Si on est là, c'est qu'il n'y a PAS eu d'erreur sur PARENT_01.
        // Donc childGroupMap contenait bien l'entrée.
        assertEquals(1, parent.storeGroups.size(), "Le parent devrait être lié à 1 enfant");

        StoreGroup linkedChild = parent.storeGroups.iterator().next();
        assertEquals(childGroupCode, linkedChild.code, "L'enfant lié doit être celui créé dans le setup");
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Helper method to manually execute logic within a transaction context.
     */
    public <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (Throwable t) {
            try {
                tm.rollback();
            } catch (Exception ex) {
                t.addSuppressed(ex);
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t);
            }
        }
    }

}