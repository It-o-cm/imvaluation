package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.security.UserBootstrap;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group A — Startup &amp; bootstrapping ({@code [D]}) — of e2e-scenarios.md.
 * <p>
 * Every scenario runs against the in-JVM {@code @QuarkusTest} application. A1/A2 read the
 * bootstrap account through Panache under {@code QuarkusTransaction}; A4 drives the offers
 * screen over HTTP with a form session; A5 replays the seven CSV imports through the HTTP
 * import endpoints (Basic admin/admin) and proves the mirror seed is idempotent by checksum.
 * <p>
 * The single in-memory database is shared for the whole JVM life, which is exactly the subject
 * of this group (boot on an empty base, observe the amnesia, re-seed). The scenarios are kept
 * order-independent nonetheless: only A5 ever seeds the referential (so those tables are empty
 * whenever A5's first pass runs), and A4 clears the offer table itself before asserting the
 * empty-catalog hint, so neither depends on the other's execution order.
 * <p>
 * A3 is a {@code [P]} scenario (prod-like startup failures on missing secrets): with no
 * prod-like harness able to boot a second, deliberately-misconfigured application context, it
 * is disabled and reported as justified residue.
 */
@QuarkusTest
class GroupAIT {

    /**
     * Name of the session cookie set by form authentication.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    // --------------------------------------------------
    // A1 — boot on an empty base
    // --------------------------------------------------

    /**
     * A1 — boot on a virgin base: the empty user table triggers the bootstrap, which creates
     * the {@code admin} account with the three roles in canonical order
     * ({@code VIEWER,MANAGER,ADMIN}), a {@code Bootstrap administrator} display name, an active
     * flag, and a pending forced password change. The account is read straight from the
     * database, which is the observable outcome of the startup WARN the scenario describes.
     */
    @Test
    void a1_bootstrapAdministratorCreatedOnEmptyBase() {
        String[] captured = new String[3];
        boolean[] flags = new boolean[2];
        List<String> roleOrder = new java.util.ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser admin = AppUser.findByUsername("admin");
            assertNotNull(admin);
            captured[0] = admin.roles;
            captured[1] = admin.displayName;
            captured[2] = admin.getLabel();
            flags[0] = admin.active;
            flags[1] = admin.mustChangePassword;
            roleOrder.addAll(admin.getRoleSet());
        });
        assertEquals("VIEWER,MANAGER,ADMIN", captured[0]);
        assertEquals("Bootstrap administrator", captured[1]);
        assertEquals("Bootstrap administrator", captured[2]);
        assertTrue(flags[0]);
        assertTrue(flags[1]);
        assertEquals(List.of("VIEWER", "MANAGER", "ADMIN"), roleOrder);
    }

    // --------------------------------------------------
    // A2 — conditional bootstrap
    // --------------------------------------------------

    /**
     * A2 — conditional bootstrap: within one JVM life the base cannot be truly emptied (an
     * in-memory restart re-creates the admin), so the guard is exercised directly. The
     * bootstrap already ran at startup (the admin exists), so {@code AppUser.count() > 0}; a
     * second invocation of {@code UserBootstrap.onStart} must therefore be a pure no-op — the
     * user count is unchanged and no duplicate {@code admin} appears.
     *
     * @throws Exception When the reflective invocation of the package-private observer fails.
     */
    @Test
    void a2_bootstrapDoesNotRecreateWhenUsersExist() throws Exception {
        long[] before = new long[1];
        QuarkusTransaction.requiringNew().run(() -> before[0] = AppUser.count());
        assertTrue(before[0] > 0);
        UserBootstrap bootstrap = Arc.container().instance(UserBootstrap.class).get();
        assertNotNull(bootstrap);
        Method onStart = UserBootstrap.class.getDeclaredMethod("onStart", StartupEvent.class);
        onStart.setAccessible(true);
        QuarkusTransaction.requiringNew().run(() -> {
            try {
                onStart.invoke(bootstrap, new StartupEvent());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        long[] after = new long[2];
        QuarkusTransaction.requiringNew().run(() -> {
            after[0] = AppUser.count();
            after[1] = AppUser.count("username", "admin");
        });
        assertEquals(before[0], after[0]);
        assertEquals(1L, after[1]);
    }

    // --------------------------------------------------
    // A3 — incomplete prod startup [P]
    // --------------------------------------------------

    /**
     * A3 — incomplete prod startup ({@code [P]} residue): booting the prod profile without
     * {@code VALUATION_ADMIN_PASSWORD} or without {@code VALUATION_SESSION_KEY} must fail with
     * no silent fallback. Reproducing it needs a second application context booted with a
     * deliberately missing secret; no such prod-like harness exists, so it is disabled.
     */
    @Test
    @Disabled("[P] requires a prod-like boot harness able to start with a missing secret")
    void a3_prodStartupFailsWithoutSecrets() {
    }

    // --------------------------------------------------
    // A4 — assumed amnesia
    // --------------------------------------------------

    /**
     * A4 — assumed amnesia: with no catalog, the offers screen states that the in-memory
     * database is reset at every restart and that the CSV imports must be replayed. The offer
     * table is cleared first so the empty state is reached whatever the method order, then the
     * authenticated screen is asserted to carry both the empty-filter line and the amnesia
     * hint.
     */
    @Test
    void a4_emptyCatalogShowsAmnesiaHint() {
        QuarkusTransaction.requiringNew().run(() -> Offer.deleteAll());
        String session = signIn("admin", "admin");
        given().cookie(SESSION_COOKIE, session)
                .when().get("/ui/offers")
                .then().statusCode(200)
                .body(containsString("No offer matches the current filters."))
                .body(containsString("The in-memory database is reset at every restart"))
                .body(containsString("run the CSV imports to load a catalog."));
    }

    // --------------------------------------------------
    // A5 — mirror seed & idempotence
    // --------------------------------------------------

    /**
     * A5 — mirror seed &amp; idempotence: the seven imports are replayed in the mandated order
     * (Stores → StoreGroups → Products → ProductFamilies → Categories → Prices → Offers). The
     * first pass creates every row ({@code updatedCount:0}); an immediate second pass is a pure
     * no-op ({@code createdCount:0, updatedCount:0}) everywhere, idempotence being driven by
     * the checksum. The store seed includes {@code 0106}, a store with no address at all: its
     * symmetric address checksum (an absent address normalised to an empty one before hashing)
     * keeps the re-import a no-op too.
     */
    @Test
    void a5_mirrorSeedIsIdempotentByChecksum() {
        // First pass: an empty referential, so every line is created and nothing updated.
        importSeed("/stores/import", STORES_CSV, expectedCreated(STORES_CSV), 0);
        importSeed("/store-groups/import", STORE_GROUPS_CSV, expectedCreated(STORE_GROUPS_CSV), 0);
        importSeed("/products/import", PRODUCTS_CSV, expectedCreated(PRODUCTS_CSV), 0);
        importSeed("/product-families/import", PRODUCT_FAMILIES_CSV, expectedCreated(PRODUCT_FAMILIES_CSV), 0);
        importSeed("/product-category-storages/import", CATEGORIES_CSV, expectedCreated(CATEGORIES_CSV), 0);
        importSeed("/prices/import", PRICES_CSV, expectedCreated(PRICES_CSV), 0);
        importSeed("/offers/import", OFFERS_CSV, expectedCreated(OFFERS_CSV), 0);
        // Second pass immediately: the checksum makes every re-import a no-op.
        importSeed("/stores/import", STORES_CSV, 0, 0);
        importSeed("/store-groups/import", STORE_GROUPS_CSV, 0, 0);
        importSeed("/products/import", PRODUCTS_CSV, 0, 0);
        importSeed("/product-families/import", PRODUCT_FAMILIES_CSV, 0, 0);
        importSeed("/product-category-storages/import", CATEGORIES_CSV, 0, 0);
        importSeed("/prices/import", PRICES_CSV, 0, 0);
        importSeed("/offers/import", OFFERS_CSV, 0, 0);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Signs in through form authentication and returns the session cookie value.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @return The value of the {@code quarkus-credential} cookie issued on success.
     */
    private String signIn(String username, String password) {
        return given().redirects().follow(false)
                .formParam("j_username", username)
                .formParam("j_password", password)
                .when().post("/j_security_check")
                .then().statusCode(302)
                .extract().cookie(SESSION_COOKIE);
    }

    /**
     * Posts a CSV payload to an import endpoint (Basic admin/admin) and asserts the created and
     * updated counts, with no line-level errors reported.
     *
     * @param path            The import endpoint path.
     * @param csv             The CSV payload including its header line.
     * @param expectedCreated The expected number of created rows.
     * @param expectedUpdated The expected number of updated rows.
     */
    private void importSeed(String path, String csv, long expectedCreated, long expectedUpdated) {
        given().auth().preemptive().basic("admin", "admin")
                .contentType("text/plain")
                .body(csv)
                .when().post(path)
                .then().statusCode(200)
                .body("createdCount", equalTo((int) expectedCreated))
                .body("updatedCount", equalTo((int) expectedUpdated))
                .body("errors", nullValue());
    }

    /**
     * Counts the data rows a CSV payload will feed to an import, mirroring the importer's own
     * parsing: blank lines are ignored and the first non-blank line is the header.
     *
     * @param csv The CSV payload.
     * @return The number of non-blank data lines (header excluded).
     */
    private static long expectedCreated(String csv) {
        return csv.lines().map(String::trim).filter(line -> !line.isEmpty()).count() - 1;
    }

    // --------------------------------------------------
    // Mirror seed payloads (replayed by A5)
    // --------------------------------------------------

    /**
     * Stores seed: the five addressed test stores plus {@code 0106}, a deliberately
     * address-less store exercising the symmetric address checksum.
     */
    private static final String STORES_CSV = """
            code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude
            0101|Intermarché Test 1|1 Rue du Test|ZI Nord|59000|Lille|France|50.63|3.06
            0102|Intermarché Test 2|12 Avenue des Fleurs||33000|Bordeaux|France|44.83|-0.57
            0103|Intermarché Test 3|99 Boulevard de la Liberte|Etage 2|69000|Lyon|France|45.75|4.85
            0104|Intermarché Test 4|25 Rue de Rivoli||75001|Paris|France|48.86|2.33
            0105|Intermarché Test 5|8 Place de la Gare|Bat C|67000|Strasbourg|France|48.57|7.75
            0106|Intermarché Sans Adresse|||||||
            """;

    /**
     * Store groups seed, referencing the addressed stores.
     */
    private static final String STORE_GROUPS_CSV = """
            group_code|group_name|store_codes|store_group_codes
            DEPT_59|Département du Nord|0104|
            DEPT_75|Département Paris||
            REGION_NORTH|Région Nord|0101;0102;0103|DEPT_59
            REGION_SUD|Région Sud|0105|DEPT_75
            """;

    /**
     * Products seed.
     */
    private static final String PRODUCTS_CSV = """
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

    /**
     * Product families seed, referencing products and sub-families.
     */
    private static final String PRODUCT_FAMILIES_CSV = """
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

    /**
     * Product category storage seed (one storage path per product).
     */
    private static final String CATEGORIES_CSV = """
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

    /**
     * Prices seed (DEFAULT and BASE_FOR_DISCOUNT usages, priorities, fixed start date).
     */
    private static final String PRICES_CSV = """
            ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime
            3300000000001|0101|1.00|1.20|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000001|0101|1.10|1.32|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000001|0101|0.90|1.08|0.2000|DEFAULT|1|2026-01-12T00:00:00|
            3300000000001|0101|0.99|1.19|0.2000|BASE_FOR_DISCOUNT|1|2026-01-12T00:00:00|
            3300000000002|0101|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000002|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000002|0101|2.30|2.76|0.2000|DEFAULT|1|2026-01-12T00:00:00|
            3300000000002|0101|2.53|3.04|0.2000|BASE_FOR_DISCOUNT|1|2026-01-12T00:00:00|
            3300000000003|0101|0.80|0.96|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000003|0101|0.88|1.06|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000004|0101|3.50|4.20|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000004|0101|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000005|0101|1.20|1.44|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000005|0102|1.20|1.44|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000005|0101|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000005|0102|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000006|0101|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000006|0102|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000006|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000006|0102|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000007|0101|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000007|0102|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000007|0101|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000007|0102|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000008|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000008|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000009|0101|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000009|0102|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000009|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000009|0102|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000010|0101|1.50|1.80|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000010|0102|1.50|1.80|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000010|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000010|0102|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000011|0101|1.80|2.16|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000011|0102|1.80|2.16|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000011|0101|1.98|2.38|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000011|0102|1.98|2.38|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000012|0101|1.90|2.28|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000012|0102|1.90|2.28|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000012|0101|2.09|2.51|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000012|0102|2.09|2.51|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000013|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000013|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000014|0101|1.50|1.80|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000014|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000015|0101|1.80|2.16|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000015|0101|1.98|2.38|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000016|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000016|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000017|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000017|0102|3.00|3.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000017|0101|3.30|3.96|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000017|0102|3.30|3.96|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000018|0101|4.00|4.80|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000018|0101|4.40|5.28|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000019|0101|3.50|4.20|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000019|0102|3.50|4.20|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000019|0101|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000019|0102|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000020|0101|10.00|10.55|0.0550|DEFAULT|0|2026-01-12T00:00:00|
            3300000000020|0101|11.00|11.61|0.0550|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000020|0101|9.50|10.02|0.0550|DEFAULT|1|2026-01-12T00:00:00|
            3300000000020|0101|10.45|11.02|0.0550|BASE_FOR_DISCOUNT|1|2026-01-12T00:00:00|
            3300000000020|0102|10.00|10.55|0.0550|DEFAULT|0|2026-01-12T00:00:00|
            3300000000020|0102|11.00|11.61|0.0550|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000021|0101|8.00|9.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000021|0101|8.80|10.56|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000022|0101|2.50|2.64|0.0550|DEFAULT|0|2026-01-12T00:00:00|
            3300000000022|0101|2.75|2.90|0.0550|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000023|0101|2.00|2.11|0.0550|DEFAULT|0|2026-01-12T00:00:00|
            3300000000023|0101|2.20|2.32|0.0550|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000024|0101|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000024|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000025|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000025|0101|3.30|3.96|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000026|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000026|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000027|0101|4.00|4.80|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000027|0101|4.40|5.28|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000028|0101|6.00|7.20|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000028|0101|6.60|7.92|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000029|0101|1.50|1.80|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000029|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000030|0101|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000030|0102|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000030|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000030|0102|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000031|0101|12.00|14.40|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000031|0101|13.20|15.84|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000032|0101|15.00|18.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000032|0101|16.50|19.80|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            3300000000033|0101|25.00|30.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|
            3300000000033|0101|27.50|33.00|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|
            """;

    /**
     * Offers seed (one offer per supported type).
     */
    private static final String OFFERS_CSV = """
            offer_code|offer_type|specification|store_code|store_group_code
            PROMO_STORE_101|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer"], "targetEans": ["3300000000001"], "discountType": "PERCENTAGE", "value": 15.0}|0101|
            PROMO_STORE_102|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer"], "targetEans": ["3300000000004"], "discountType": "FIXED_AMOUNT", "value": 1.99}|0102|
            PROMO_GROUP_NORD|FREE_DELIVERY_THRESHOLD|{"tiers": [{"threshold": 30.0, "value": 100.0, "type": "PERCENTAGE"}]}||REGION_NORTH
            PROMO_2FOR1_3300|N+M|{"targetEans": ["3300000000001"], "quantityToPay": 2, "discountedQuantity": 1, "selectionStrategy": "CHEAPEST", "discountType": "PERCENTAGE", "discountValue": 100.0}|0101|
            PROMO_COFFEE_PACK|MIXED_BUNDLE|{"bundlePrice": 4.50, "vatRate": 0.20, "contents": [{"ean": "3300000000004", "quantity": 1.0}, {"ean": "3300000000013", "quantity": 1.0, "substituteEans": ["3300000000014"]}]}|0101|
            DELIVERY_HOME_0101|DELIVERY|{"tiers": [{"maxDistance": 8.0, "price": 5.90}, {"maxDistance": 16.0, "price": 9.90}], "vatRate": 0.20}|0101|
            BRI_APPLES_DISCOUNT|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer", "MixedBundleOffer"], "targetEans": ["3300000000001"], "discountType": "FIXED_AMOUNT", "value": 0.10}|0101|
            BASKET_CONSIGNMENT_0101|DEPOSIT_BASKET|{"basketVolume": 10.0, "basketPrice": 0.50, "vatRate": 0.20}|0101|
            FREE_DELIVERY_THRESHOLD_0101|FREE_DELIVERY_THRESHOLD|{"tiers": [{"threshold": 10.0, "value": 50.0, "type": "FIXED_AMOUNT"}, {"threshold": 20.0, "value": 100.0, "type": "FIXED_AMOUNT"}]}|0101|
            MEAL_VOUCHER_0101|MEAL_VOUCHER|{"flag": "RESTAURANT_VOUCHER_ELIGIBLE", "threshold": 25.00}|0101|
            VIGNETTE_CUISSON|VIGNETTE_DISCOUNT|{"catalog": [{"ean": "3300000000031", "vignettesRequired": 5, "discount": {"type": "PERCENTAGE", "value": 50.0}}, {"ean": "3300000000032", "vignettesRequired": 3, "discount": {"type": "FIXED_AMOUNT", "value": 2.00}}]}|0101|
            """;
}
