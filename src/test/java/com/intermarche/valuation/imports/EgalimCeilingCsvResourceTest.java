package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.EgalimCeiling;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the EGAlim-ceiling import endpoint — the {@code EGALIM_REGIMES} feed the store node
 * relays verbatim — covering creation, update by code, the checksum-optimised no-op re-import,
 * the uncapped regime, the code normalisation and the refused ceilings.
 */
@QuarkusTest
public class EgalimCeilingCsvResourceTest {

    /**
     * The JTA transaction manager, for the programmatic seeding transactions.
     */
    @Inject
    TransactionManager tm;

    /**
     * Runs a supplier inside a fresh committed transaction.
     *
     * @param runnable the logic to run.
     * @param <R>      the result type.
     * @return the supplier's result.
     */
    private <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (NotSupportedException | SystemException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            try {
                tm.setRollbackOnly();
            } catch (SystemException ex) {
                throw new RuntimeException(e);
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts a request carrying the admin credentials the import endpoints require.
     *
     * @return an authenticated request specification.
     */
    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("admin", "admin");
    }

    /**
     * Clears the ceilings before each test, so assertions do not depend on rows a committing
     * class left in the shared database.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        EgalimCeiling.deleteAll();
    }

    /**
     * Posts a CSV body to the EGAlim-ceiling import endpoint.
     *
     * @param csv the CSV body.
     * @return the RestAssured response validatable.
     */
    private io.restassured.response.ValidatableResponse importCeilings(String csv) {
        return authenticated().body(csv).contentType(ContentType.TEXT)
                .when().post("/egalim-regimes/import").then();
    }

    /**
     * Tests the creation of new ceilings, the file carrying the three real regime codes.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewCeilings_Created() {
        importCeilings("CODE|LABEL|CAP_RATE\n"
                + "FOOD_34|Alimentaire et petfood|0.3400\n"
                + "DPH_40|Droguerie parfumerie hygiène|0.4000")
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"));
        EgalimCeiling food = withTransaction(() -> EgalimCeiling.findByCode("FOOD_34"));
        assertNotNull(food);
        assertEquals(0, new BigDecimal("0.3400").compareTo(food.capRate));
        assertEquals("Alimentaire et petfood", food.label);
    }

    /**
     * Tests that a regime stated WITHOUT a ceiling is created uncapped, and that the empty cell
     * is not read as a zero: that is how the exempt category is declared.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportUncappedRegime_CreatedWithoutCeiling() {
        importCeilings("CODE|LABEL|CAP_RATE\nEXEMPT|Hors champ EGAlim|")
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));
        EgalimCeiling exempt = withTransaction(() -> EgalimCeiling.findByCode("EXEMPT"));
        assertNotNull(exempt);
        assertNull(exempt.capRate);
    }

    /**
     * Tests that the CAP_RATE column may be absent altogether: a file stating only codes and
     * labels declares regimes nobody caps.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportWithoutCapColumn_Accepted() {
        importCeilings("CODE|LABEL\nEXEMPT|Hors champ EGAlim")
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));
        assertNull(withTransaction(() -> EgalimCeiling.findByCode("EXEMPT")).capRate);
    }

    /**
     * Tests that a ceiling is updated by its code — the legal rate moves, the code does not.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateByCode() {
        withTransaction(() -> {
            new EgalimCeiling("DPH_40", "Droguerie", new BigDecimal("0.3400")).persist();
            return null;
        });
        importCeilings("CODE|LABEL|CAP_RATE\nDPH_40|Droguerie|0.4000")
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));
        EgalimCeiling dph = withTransaction(() -> EgalimCeiling.findByCode("DPH_40"));
        assertEquals(0, new BigDecimal("0.4000").compareTo(dph.capRate));
    }

    /**
     * Tests that a capped regime can be RELEASED by an update stating no ceiling: the empty
     * cell overwrites the stored rate rather than being ignored.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateToUncapped() {
        withTransaction(() -> {
            new EgalimCeiling("DPH_40", "Droguerie", new BigDecimal("0.4000")).persist();
            return null;
        });
        importCeilings("CODE|LABEL|CAP_RATE\nDPH_40|Droguerie|")
                .statusCode(200)
                .body(containsString("\"updatedCount\":1"));
        assertNull(withTransaction(() -> EgalimCeiling.findByCode("DPH_40")).capRate);
    }

    /**
     * Tests that re-importing an unchanged file reports zero updates.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportUnchanged_NoUpdate() {
        String csv = "CODE|LABEL|CAP_RATE\nFOOD_34|Alimentaire|0.3400";
        importCeilings(csv).statusCode(200).body(containsString("\"createdCount\":1"));
        importCeilings(csv).statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests that a code is stored upper-cased, whatever the file's hand: a referential
     * answering differently to {@code food_34} and {@code FOOD_34} would hold two categories
     * where the law has one, and no article would match the second.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testCodeIsNormalised() {
        importCeilings("CODE|LABEL|CAP_RATE\n  food_34 |Alimentaire|0.3400")
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));
        assertNotNull(withTransaction(() -> EgalimCeiling.findByCode("FOOD_34")));
        assertNull(withTransaction(() -> EgalimCeiling.findByCode("food_34")));
    }

    /**
     * Tests that a line with an empty CODE cell is rejected by the base class' key guard, the
     * file's other lines surviving.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testEmptyCode_Rejected() {
        importCeilings("CODE|LABEL|CAP_RATE\n|Sans code|0.3400\nFOOD_34|Alimentaire|0.3400")
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("empty key 'CODE'"));
    }

    /**
     * Tests the importer's own code guard, which the base class' key filter normally makes
     * unreachable: called directly with a keyless row — the way an overriding caller or the
     * one-by-one fallback could — the line refuses itself rather than persisting a row with no
     * code and letting the column's NOT NULL decide.
     */
    @Test
    void testProcessLineLogic_NullCodeRefused() {
        EgalimCeilingCsvResource resource = new EgalimCeilingCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(
                1, java.util.Map.of("CODE", 0), new String[]{"   "}, "CODE");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, new java.util.HashMap<>(), new int[2]));
        assertEquals("EGAlim regime code is mandatory.", refusal.getMessage());
    }

    /**
     * Tests that a ceiling above one is rejected: a file stating 34 instead of 0.34 would
     * declare a ceiling of three thousand four hundred percent, which is no ceiling at all.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testCapAboveOne_Rejected() {
        importCeilings("CODE|LABEL|CAP_RATE\nFOOD_34|Alimentaire|34")
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("is not a fraction between 0 and 1"));
        assertNull(withTransaction(() -> EgalimCeiling.findByCode("FOOD_34")));
    }

    /**
     * Tests that a negative ceiling is rejected — the other leg of the range guard.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testNegativeCap_Rejected() {
        importCeilings("CODE|LABEL|CAP_RATE\nFOOD_34|Alimentaire|-0.10")
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("is not a fraction between 0 and 1"));
    }

    /**
     * Tests that the two ends of the accepted range pass: a ceiling of zero allows no
     * generosity at all, a ceiling of one allows everything, and both are rules someone wrote.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testBoundaryCapsAccepted() {
        importCeilings("CODE|LABEL|CAP_RATE\nTEST_ZERO|Rien|0\nTEST_ONE|Tout|1")
                .statusCode(200)
                .body(containsString("\"createdCount\":2"));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                withTransaction(() -> EgalimCeiling.findByCode("TEST_ZERO")).capRate));
        assertEquals(0, BigDecimal.ONE.compareTo(
                withTransaction(() -> EgalimCeiling.findByCode("TEST_ONE")).capRate));
    }

    /**
     * Tests that a file whose columns this importer does not know is imported all the same:
     * that tolerance is what lets one shared feed serve several tools.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUnknownColumnsIgnored() {
        importCeilings("CODE|LABEL|CAP_RATE|COMMENT\nFOOD_34|Alimentaire|0.3400|loi Descrozaille")
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));
        assertEquals("Alimentaire", withTransaction(() -> EgalimCeiling.findByCode("FOOD_34")).label);
    }

    /**
     * Tests the one-by-one fallback's fresh lookup: it resolves a row by the NORMALISED code,
     * so a file written in the wrong case still updates the row it means instead of creating a
     * second category.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_UsesNormalisedCode() {
        withTransaction(() -> {
            new EgalimCeiling("FOOD_34", "Alimentaire", new BigDecimal("0.3400")).persist();
            return null;
        });
        EgalimCeilingCsvResource resource = new EgalimCeilingCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(
                1, java.util.Map.of("CODE", 0), new String[]{"food_34"}, "CODE");
        EgalimCeiling found = withTransaction(() -> (EgalimCeiling) resource.findEntityForLine(data));
        assertNotNull(found);
        assertEquals("FOOD_34", found.regimeCode);
    }
}
