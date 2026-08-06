package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group F — GraphQL ({@code POST /graphql}, Basic) — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest} application,
 * posting a GraphQL document to {@code /graphql} with HTTP Basic credentials. The transport is
 * always a 200 (SmallRye renders both success and business errors inside the body): a granted,
 * successful operation answers with a {@code "data"} payload and no {@code "errors"} key, while a
 * denial or a business failure answers with an {@code "errors"} array and the operation field
 * resolving to {@code null}. The matrix scenarios assert on that {@code "errors"} key; the message
 * scenarios assert the LITERAL texts the catalog quotes in Q-C.
 * <p>
 * The group needs no mirror seed. Every scenario builds its own throwaway entities through the very
 * GraphQL mutations under test, each under a per-scenario code/EAN prefix ({@code F2…}, {@code F7…}),
 * so the scenarios stay isolated even though the H2 database lives for the whole JVM; assertions
 * never rely on absolute ids or on row counts of the shared tables (only on the presence/absence of
 * the scenario's own codes, found by predicate).
 * <p>
 * The default {@code admin/admin} account is bootstrapped with the three roles
 * ({@code VIEWER}, {@code MANAGER}, {@code ADMIN}), so it may run both queries (MANAGER) and
 * mutations (ADMIN); the single-role accounts needed by the F1 security matrix are created directly
 * through Panache, exactly as Group C does for C4.
 * <p>
 * CALIBRATION — SmallRye error rendering (F3, F4, F7, F8, F9, F11). The dividing line is NOT the
 * kind of error but WHERE it is caught. Only what {@code GraphQLTrait.execute()} itself converts to a
 * {@code GraphQLException} keeps its message:
 * <ul>
 *   <li>WRAPPED, surfaces VERBATIM in {@code errors[].message}: the {@code AlreadyExistsException}
 *       conflicts thrown before persist (F2) and the catch-all generics {@code An error occurred
 *       during <op>.} for exceptions raised INSIDE the lambda — no-target/self-reference/invalid-type
 *       (F4) and the null-start {@code updatePrice} NPE (F8). Asserted to the exact catalog text.</li>
 *   <li>MASKED to the generic string {@code "System error"} ({@code extensions.classification =
 *       DataFetchingException}, operation field {@code null}): everything that ESCAPES the lambda.
 *       That is (a) {@code NoSuchElementException}, deliberately re-thrown unwrapped — so, contrary to
 *       the catalog's hope, F3 not-found, F9's update not-found and F11's duplicate trap ALL render as
 *       {@code "System error"} (the F3 unknown, now pinned); and (b) commit-time failures the
 *       {@code PersistenceException} handler never sees because they fire after {@code execute()}
 *       returns — the F7 FK violation, which the catalog wrongly expected as the persistence generic.
 *       Each such scenario asserts the {@code "errors"} array plus the {@code "System error"} text and
 *       documents the écart with the catalog in its own Javadoc.</li>
 * </ul>
 * <p>
 * CALIBRATION — mandatory {@code Offer.specification}. Every {@code createOffer} that must SUCCEED
 * carries a {@code specification: "{}"}: the entity's {@code @NotBlank} fires at commit otherwise,
 * masking the intended outcome as {@code "System error"}. Only F4's no-target offer omits it (it fails
 * earlier, inside the lambda, on the target guard).
 * <p>
 * TRANSVERSE GUARD — HashSet membership: {@code Offer.stores}, {@code StoreGroup.storeGroups},
 * {@code ProductFamily.products} and {@code ProductFamily.productFamilies} are {@code HashSet}s;
 * every DB assertion below finds a link by predicate (its {@code code}/{@code ean}), never by index.
 */
@QuarkusTest
class GroupFIT {

    /**
     * Posts a GraphQL document with HTTP Basic credentials and asserts a 200 transport.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @param query    The GraphQL query or mutation document (its inner quotes already escaped for JSON).
     * @return The response body as a string, for {@code "errors"}/{@code "data"} assertions.
     */
    private String graphql(String username, String password, String query) {
        return given().auth().preemptive().basic(username, password)
                .contentType(ContentType.JSON)
                .body("{\"query\":\"" + query + "\"}")
                .when().post("/graphql")
                .then().statusCode(200)
                .extract().asString();
    }

    /**
     * Posts a GraphQL document as the fully-privileged bootstrap {@code admin/admin}.
     *
     * @param query The GraphQL query or mutation document.
     * @return The response body as a string.
     */
    private String admin(String query) {
        return graphql("admin", "admin", query);
    }

    /**
     * Runs a setup mutation as {@code admin/admin}, asserts it succeeded and returns the created id.
     *
     * @param query    The GraphQL mutation document, selecting at least {@code id}.
     * @param dataPath The JsonPath to the returned id (e.g. {@code data.createStore.id}).
     * @return The database identifier of the entity the mutation created.
     */
    private long createReturningId(String query, String dataPath) {
        String body = admin(query);
        assertFalse(body.contains("\"errors\""), "The setup mutation must succeed: " + body);
        Long id = JsonPath.from(body).getLong(dataPath);
        assertNotNull(id, "The setup mutation must return an id: " + body);
        return id;
    }

    /**
     * Ensures a single-role account exists, creating it through Panache when absent.
     * <p>
     * Idempotent: a second call with an already-present username is a no-op, keeping the scenarios
     * independent of their execution order.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @param roles    The roles granted to the account.
     */
    private void ensureUser(String username, String password, Set<String> roles) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.findByUsername(username) != null) {
                return;
            }
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(password);
            user.setRoleSet(roles);
            user.displayName = username;
            user.active = true;
            user.mustChangePassword = false;
            user.persist();
        });
    }

    // --------------------------------------------------
    // F1 — security matrix: queries = MANAGER, mutations = ADMIN
    // --------------------------------------------------

    /**
     * F1 — the security matrix and both senses of the non-hierarchical C4 trap on {@code allStores}
     * and {@code createStore}. A MANAGER-only account is granted the query but denied the mutation; an
     * ADMIN-only account is granted the mutation but denied the query (roles are flat: ADMIN does not
     * imply MANAGER). Assertions are on the {@code "errors"} key, never on a fragile message.
     */
    @Test
    void f1_securityMatrixQueriesManagerMutationsAdmin() {
        ensureUser("f1manager", "managerpass1", Set.of(AppUser.ROLE_MANAGER));
        ensureUser("f1admin", "adminpass1", Set.of(AppUser.ROLE_ADMIN));
        String managerQuery = graphql("f1manager", "managerpass1", "{ allStores { id code } }");
        assertFalse(managerQuery.contains("\"errors\""), "A MANAGER must be allowed the allStores query: " + managerQuery);
        assertTrue(managerQuery.contains("allStores"), "The allStores field must be resolved for a MANAGER: " + managerQuery);
        String managerMutation = graphql("f1manager", "managerpass1",
                "mutation { createStore(input: {code: \\\"F1M\\\", name: \\\"F1 Manager Store\\\"}) { id } }");
        assertTrue(managerMutation.contains("\"errors\""), "A MANAGER must be denied the createStore mutation: " + managerMutation);
        String adminQuery = graphql("f1admin", "adminpass1", "{ allStores { id code } }");
        assertTrue(adminQuery.contains("\"errors\""), "An ADMIN must be denied the allStores query (flat role model): " + adminQuery);
        String adminMutation = graphql("f1admin", "adminpass1",
                "mutation { createStore(input: {code: \\\"F1A\\\", name: \\\"F1 Admin Store\\\"}) { id code } }");
        assertFalse(adminMutation.contains("\"errors\""), "An ADMIN must be allowed the createStore mutation: " + adminMutation);
        assertTrue(adminMutation.contains("F1A"), "The granted mutation must return the created store code: " + adminMutation);
    }

    // --------------------------------------------------
    // F2 — creation conflicts: the exact Q-C messages
    // --------------------------------------------------

    /**
     * F2 — the exact creation-conflict messages across every entity. Each entity is created once, then
     * a colliding create is attempted: a duplicate key (code/EAN) and, where the resource enforces it
     * in business logic, a duplicate name/description. Every {@code AlreadyExistsException} transits
     * intact through the {@code GraphQLException} and is asserted to the literal catalog text — including
     * the store name treated as unique without a DB constraint and the product-family description
     * uniqueness. The price and storage conflicts reuse a throwaway product/store created here.
     */
    @Test
    void f2_creationConflictsExactMessages() {
        // Store: code then name
        admin("mutation { createStore(input: {code: \\\"F2STORE\\\", name: \\\"F2 Store Name\\\"}) { id } }");
        String dupStoreCode = admin("mutation { createStore(input: {code: \\\"F2STORE\\\", name: \\\"F2 Other\\\"}) { id } }");
        assertTrue(dupStoreCode.contains("Store with code 'F2STORE' already exists."), "Store code conflict: " + dupStoreCode);
        String dupStoreName = admin("mutation { createStore(input: {code: \\\"F2STORE2\\\", name: \\\"F2 Store Name\\\"}) { id } }");
        assertTrue(dupStoreName.contains("Store with name 'F2 Store Name' already exists."), "Store name conflict: " + dupStoreName);
        // Product: ean then name
        admin("mutation { createProduct(input: {ean: \\\"F2PROD\\\", name: \\\"F2 Product\\\", productType: \\\"UNIT\\\"}) { id } }");
        String dupProdEan = admin("mutation { createProduct(input: {ean: \\\"F2PROD\\\", name: \\\"F2 Other Prod\\\", productType: \\\"UNIT\\\"}) { id } }");
        assertTrue(dupProdEan.contains("Product with ean 'F2PROD' already exists."), "Product ean conflict: " + dupProdEan);
        String dupProdName = admin("mutation { createProduct(input: {ean: \\\"F2PROD2\\\", name: \\\"F2 Product\\\", productType: \\\"UNIT\\\"}) { id } }");
        assertTrue(dupProdName.contains("Product with name 'F2 Product' already exists."), "Product name conflict: " + dupProdName);
        // StoreGroup: code then name
        admin("mutation { createStoreGroup(input: {code: \\\"F2GRP\\\", name: \\\"F2 Group\\\"}) { id } }");
        String dupGrpCode = admin("mutation { createStoreGroup(input: {code: \\\"F2GRP\\\", name: \\\"F2 Other Grp\\\"}) { id } }");
        assertTrue(dupGrpCode.contains("StoreGroup with code 'F2GRP' already exists."), "StoreGroup code conflict: " + dupGrpCode);
        String dupGrpName = admin("mutation { createStoreGroup(input: {code: \\\"F2GRP2\\\", name: \\\"F2 Group\\\"}) { id } }");
        assertTrue(dupGrpName.contains("StoreGroup with name 'F2 Group' already exists."), "StoreGroup name conflict: " + dupGrpName);
        // ProductFamily: code then description
        admin("mutation { createProductFamily(input: {code: \\\"F2FAM\\\", description: \\\"F2 Family Desc\\\"}) { id } }");
        String dupFamCode = admin("mutation { createProductFamily(input: {code: \\\"F2FAM\\\", description: \\\"F2 Other Desc\\\"}) { id } }");
        assertTrue(dupFamCode.contains("ProductFamily with code 'F2FAM' already exists."), "ProductFamily code conflict: " + dupFamCode);
        String dupFamDesc = admin("mutation { createProductFamily(input: {code: \\\"F2FAM2\\\", description: \\\"F2 Family Desc\\\"}) { id } }");
        assertTrue(dupFamDesc.contains("ProductFamily with description 'F2 Family Desc' already exists."), "ProductFamily description conflict: " + dupFamDesc);
        // Offer: code (needs a target)
        admin("mutation { createStore(input: {code: \\\"F2OSTORE\\\", name: \\\"F2 Offer Store\\\"}) { id } }");
        admin("mutation { createOffer(input: {code: \\\"F2OFF\\\", type: \\\"MEAL_VOUCHER\\\", specification: \\\"{}\\\", storeCodes: [\\\"F2OSTORE\\\"]}) { id } }");
        String dupOffer = admin("mutation { createOffer(input: {code: \\\"F2OFF\\\", type: \\\"MEAL_VOUCHER\\\", specification: \\\"{}\\\", storeCodes: [\\\"F2OSTORE\\\"]}) { id } }");
        assertTrue(dupOffer.contains("Offer with code 'F2OFF' already exists."), "Offer code conflict: " + dupOffer);
        // Price: composite key (needs product + store ids)
        long pProd = createReturningId("mutation { createProduct(input: {ean: \\\"F2PPRICE\\\", name: \\\"F2 Price Product\\\", productType: \\\"UNIT\\\"}) { id } }", "data.createProduct.id");
        long pStore = createReturningId("mutation { createStore(input: {code: \\\"F2SPRICE\\\", name: \\\"F2 Price Store\\\"}) { id } }", "data.createStore.id");
        String createPrice = "mutation { createPrice(input: {productId: " + pProd + ", storeId: " + pStore
                + ", priceUsage: DEFAULT, priceExcludingTax: 10.00, priceIncludingTax: 12.00, vatRate: 0.20, priority: 1, startDateTime: \\\"2025-01-01T00:00:00\\\", endDateTime: \\\"2025-12-31T00:00:00\\\"}) { id } }";
        String priceOk = admin(createPrice);
        assertFalse(priceOk.contains("\"errors\""), "The first price must be created: " + priceOk);
        String dupPrice = admin(createPrice);
        assertTrue(dupPrice.contains("A price with the same priority, start date, and usage already exists for this product and store."),
                "Price composite-key conflict: " + dupPrice);
        // Storage link: (product, level1, level5) key
        long sProd = createReturningId("mutation { createProduct(input: {ean: \\\"F2PSTOR\\\", name: \\\"F2 Storage Product\\\", productType: \\\"UNIT\\\"}) { id } }", "data.createProduct.id");
        String createStorage = "mutation { createProductCategoryStorage(input: {productId: " + sProd
                + ", level1: \\\"L1\\\", level2: \\\"L2\\\", level3: \\\"L3\\\", level4: \\\"L4\\\", level5: \\\"L5\\\"}) { id } }";
        String storageOk = admin(createStorage);
        assertFalse(storageOk.contains("\"errors\""), "The first storage link must be created: " + storageOk);
        String dupStorage = admin(createStorage);
        assertTrue(dupStorage.contains("A storage link for this product and category path already exists."),
                "Storage-link conflict: " + dupStorage);
    }

    // --------------------------------------------------
    // F3 — not-found: SmallRye rendering of the unwrapped NoSuchElementException
    // --------------------------------------------------

    /**
     * F3 — not-found rendering, CALIBRATED. {@code GraphQLTrait} re-throws {@code NoSuchElementException}
     * UNWRAPPED — but, contrary to the catalog's hope of a "clean specific message", SmallRye does NOT
     * pass it through: because it is not a {@code GraphQLException}, its message is MASKED and the client
     * receives the generic {@code "System error"} with an {@code extensions.classification} of
     * {@code "DataFetchingException"} and the operation field resolved to {@code null}. This is the F3
     * unknown, now pinned: every unwrapped not-found (id, code, product id) renders identically as
     * {@code "System error"}. The clean business text lives only in the server log.
     */
    @Test
    void f3_notFoundRenderingCalibrated() {
        String byId = admin("{ store(id: 999999) { id } }");
        assertTrue(byId.contains("\"errors\""), "A missing id must answer with an errors array: " + byId);
        assertTrue(byId.contains("\"data\":{\"store\":null}"), "The missing store must resolve to null: " + byId);
        assertTrue(byId.contains("System error"), "F3 not-found (id) is masked as the generic: " + byId);
        assertFalse(byId.contains("Store with id 999999 not found"), "The clean message must NOT reach the client: " + byId);
        String byCode = admin("{ storeByCode(code: \\\"F3NOPE\\\") { id } }");
        assertTrue(byCode.contains("System error"), "F3 not-found (code) is masked as the generic: " + byCode);
        String product = admin("{ product(id: 888888) { id } }");
        assertTrue(product.contains("System error"), "F3 not-found (product id) is masked as the generic: " + product);
    }

    // --------------------------------------------------
    // F4 — masked messages: the catch-all generic hides the real cause
    // --------------------------------------------------

    /**
     * F4 — masked messages. Four business failures are swallowed by the {@code GraphQLTrait} catch-all
     * and re-emitted as the generic {@code An error occurred during <op>.}: an offer without any target
     * ({@code createOffer}, the {@code Offer must be linked…} cause lost), a group and a family that
     * reference themselves on update ({@code updateStoreGroup} / {@code updateProductFamily}), and an
     * invalid {@code productType} whose {@code valueOf} throws ({@code createProduct}). Each generic is
     * asserted verbatim.
     */
    @Test
    void f4_maskedMessages() {
        String noTarget = admin("mutation { createOffer(input: {code: \\\"F4NOTGT\\\", type: \\\"MEAL_VOUCHER\\\"}) { id } }");
        assertTrue(noTarget.contains("An error occurred during createOffer."), "Offer-without-target masked message: " + noTarget);
        assertFalse(noTarget.contains("Offer must be linked to at least one Store"), "The real cause must be hidden: " + noTarget);
        long grpId = createReturningId("mutation { createStoreGroup(input: {code: \\\"F4GRP\\\", name: \\\"F4 Group\\\"}) { id } }", "data.createStoreGroup.id");
        String grpSelf = admin("mutation { updateStoreGroup(id: " + grpId + ", input: {storeGroupCodes: [\\\"F4GRP\\\"]}) { id } }");
        assertTrue(grpSelf.contains("An error occurred during updateStoreGroup."), "Group self-reference masked message: " + grpSelf);
        long famId = createReturningId("mutation { createProductFamily(input: {code: \\\"F4FAM\\\", description: \\\"F4 Family\\\"}) { id } }", "data.createProductFamily.id");
        String famSelf = admin("mutation { updateProductFamily(id: " + famId + ", input: {productFamilyCodes: [\\\"F4FAM\\\"]}) { id } }");
        assertTrue(famSelf.contains("An error occurred during updateProductFamily."), "Family self-reference masked message: " + famSelf);
        String badType = admin("mutation { createProduct(input: {ean: \\\"F4PT\\\", name: \\\"F4 Bad Type\\\", productType: \\\"BOGUS\\\"}) { id } }");
        assertTrue(badType.contains("An error occurred during createProduct."), "Invalid productType masked message: " + badType);
    }

    // --------------------------------------------------
    // F5 — partial updates: null = unchanged, [] = emptying
    // --------------------------------------------------

    /**
     * F5 — partial updates. A {@code null} field means "unchanged" (a value can never be reset to null):
     * updating only a store's name leaves its code and city intact. An empty list means "emptied":
     * {@code updateOffer(storeCodes: [])} clears the targets with NO revalidation, producing a
     * targetless offer — the assumed incoherence with creation (a negative test). Both end states are
     * read through Panache, the surviving/absent links found by predicate.
     */
    @Test
    void f5_partialUpdates() {
        long storeId = createReturningId(
                "mutation { createStore(input: {code: \\\"F5STORE\\\", name: \\\"F5 Original\\\", city: \\\"Lille\\\", country: \\\"FR\\\"}) { id } }",
                "data.createStore.id");
        String renamed = admin("mutation { updateStore(id: " + storeId + ", input: {name: \\\"F5 Renamed\\\"}) { id } }");
        assertFalse(renamed.contains("\"errors\""), "The partial update must succeed: " + renamed);
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("F5STORE");
            assertNotNull(store, "The store must exist");
            assertEquals("F5 Renamed", store.name, "The provided name must be updated");
            assertEquals("F5STORE", store.code, "A null code must leave the code unchanged");
            assertEquals("Lille", store.address.city, "A null city must leave the city unchanged");
        });
        long offerStore = createReturningId("mutation { createStore(input: {code: \\\"F5OSTORE\\\", name: \\\"F5 Offer Store\\\"}) { id } }", "data.createStore.id");
        assertTrue(offerStore > 0, "The offer target store must be created");
        long offerId = createReturningId(
                "mutation { createOffer(input: {code: \\\"F5OFF\\\", type: \\\"MEAL_VOUCHER\\\", specification: \\\"{}\\\", storeCodes: [\\\"F5OSTORE\\\"]}) { id } }",
                "data.createOffer.id");
        String emptied = admin("mutation { updateOffer(id: " + offerId + ", input: {storeCodes: []}) { id } }");
        assertFalse(emptied.contains("\"errors\""), "Emptying the targets must succeed with no revalidation: " + emptied);
        QuarkusTransaction.requiringNew().run(() -> {
            Offer offer = Offer.findByCode("F5OFF");
            assertNotNull(offer, "The offer must still exist");
            assertTrue(offer.stores.isEmpty(), "The empty list must have cleared the store targets");
            assertTrue(offer.storeGroups.isEmpty(), "The offer ends up with no target at all (assumed incoherence)");
        });
    }

    // --------------------------------------------------
    // F6 — immutable keys: code is never modified on update
    // --------------------------------------------------

    /**
     * F6 — immutable keys. On {@code updateOffer}, {@code updateStoreGroup} and {@code updateProductFamily}
     * the input {@code code} field is ignored: submitting a new code alongside a legitimate change leaves
     * the code untouched while the other field (type / name / description) is applied. Proven through
     * Panache on the unchanged code and the changed sibling field.
     */
    @Test
    void f6_immutableKeys() {
        admin("mutation { createStore(input: {code: \\\"F6STORE\\\", name: \\\"F6 Store\\\"}) { id } }");
        long offerId = createReturningId(
                "mutation { createOffer(input: {code: \\\"F6OFF\\\", type: \\\"TYPE_A\\\", specification: \\\"{}\\\", storeCodes: [\\\"F6STORE\\\"]}) { id } }",
                "data.createOffer.id");
        String offerUpd = admin("mutation { updateOffer(id: " + offerId + ", input: {code: \\\"F6IGNORED\\\", type: \\\"TYPE_B\\\"}) { id } }");
        assertFalse(offerUpd.contains("\"errors\""), "The offer update must succeed: " + offerUpd);
        long grpId = createReturningId("mutation { createStoreGroup(input: {code: \\\"F6GRP\\\", name: \\\"F6 Group Original\\\"}) { id } }", "data.createStoreGroup.id");
        String grpUpd = admin("mutation { updateStoreGroup(id: " + grpId + ", input: {code: \\\"F6IGNORED\\\", name: \\\"F6 Group New\\\"}) { id } }");
        assertFalse(grpUpd.contains("\"errors\""), "The group update must succeed: " + grpUpd);
        long famId = createReturningId("mutation { createProductFamily(input: {code: \\\"F6FAM\\\", description: \\\"F6 Desc Original\\\"}) { id } }", "data.createProductFamily.id");
        String famUpd = admin("mutation { updateProductFamily(id: " + famId + ", input: {code: \\\"F6IGNORED\\\", description: \\\"F6 Desc New\\\"}) { id } }");
        assertFalse(famUpd.contains("\"errors\""), "The family update must succeed: " + famUpd);
        QuarkusTransaction.requiringNew().run(() -> {
            Offer offer = Offer.findByCode("F6OFF");
            assertNotNull(offer, "The offer must still be reachable under its original code");
            assertEquals("TYPE_B", offer.type, "The type must be updated");
            assertNull(Offer.findByCode("F6IGNORED"), "The submitted code must have been ignored for the offer");
            StoreGroup group = StoreGroup.findByCode("F6GRP");
            assertNotNull(group, "The group must still be reachable under its original code");
            assertEquals("F6 Group New", group.name, "The group name must be updated");
            assertNull(StoreGroup.findByCode("F6IGNORED"), "The submitted code must have been ignored for the group");
            ProductFamily family = ProductFamily.findByCode("F6FAM");
            assertNotNull(family, "The family must still be reachable under its original code");
            assertEquals("F6 Desc New", family.description, "The family description must be updated");
            assertNull(ProductFamily.findByCode("F6IGNORED"), "The submitted code must have been ignored for the family");
        });
    }

    // --------------------------------------------------
    // F7 — deletions: false, FK violation, destructive cascade
    // --------------------------------------------------

    /**
     * F7 — deletions. A non-existent id deletes to {@code false} with no exception. A store referenced by
     * a price fails on the foreign key — CALIBRATION: the catalog expects the persistence generic
     * {@code Database error while performing deleteStore. Please check your data.}, but the constraint
     * only fires at COMMIT, after {@code execute()} has already returned, so its {@code PersistenceException}
     * handler never catches it; the {@code RollbackException} escapes and SmallRye masks it as
     * {@code "System error"} (asserted as observed). And the most dangerous behaviour of the API:
     * {@code deleteProductFamily} cascades {@code ALL} — deleting a throwaway parent family removes its
     * linked PRODUCTS and its sub-families too, proven through Panache on a disposable set.
     */
    @Test
    void f7_deletions() {
        String falseDelete = admin("mutation { deleteStore(id: 987654) }");
        assertFalse(falseDelete.contains("\"errors\""), "Deleting a missing id must not raise an exception: " + falseDelete);
        assertTrue(falseDelete.contains("false"), "Deleting a missing id must answer false: " + falseDelete);
        // Store referenced by a price -> FK violation on delete
        long fkProd = createReturningId("mutation { createProduct(input: {ean: \\\"F7PFK\\\", name: \\\"F7 FK Product\\\", productType: \\\"UNIT\\\"}) { id } }", "data.createProduct.id");
        long fkStore = createReturningId("mutation { createStore(input: {code: \\\"F7SFK\\\", name: \\\"F7 FK Store\\\"}) { id } }", "data.createStore.id");
        admin("mutation { createPrice(input: {productId: " + fkProd + ", storeId: " + fkStore
                + ", priceUsage: DEFAULT, priceExcludingTax: 10.00, priceIncludingTax: 12.00, vatRate: 0.20, priority: 1, startDateTime: \\\"2025-01-01T00:00:00\\\", endDateTime: \\\"2025-12-31T00:00:00\\\"}) { id } }");
        String fkDelete = admin("mutation { deleteStore(id: " + fkStore + ") }");
        assertTrue(fkDelete.contains("\"errors\""), "Deleting a referenced store must fail: " + fkDelete);
        assertTrue(fkDelete.contains("System error"),
                "The commit-time FK violation escapes execute() and is masked as the generic: " + fkDelete);
        // deleteProductFamily cascade ALL -> products and sub-families are destroyed
        admin("mutation { createProduct(input: {ean: \\\"F7CPROD\\\", name: \\\"F7 Cascade Product\\\", productType: \\\"UNIT\\\"}) { id } }");
        admin("mutation { createProductFamily(input: {code: \\\"F7CSUB\\\", description: \\\"F7 Cascade Sub\\\"}) { id } }");
        long parentId = createReturningId(
                "mutation { createProductFamily(input: {code: \\\"F7CPARENT\\\", description: \\\"F7 Cascade Parent\\\", productEans: [\\\"F7CPROD\\\"], productFamilyCodes: [\\\"F7CSUB\\\"]}) { id } }",
                "data.createProductFamily.id");
        String cascade = admin("mutation { deleteProductFamily(id: " + parentId + ") }");
        assertFalse(cascade.contains("\"errors\""), "The cascade delete must succeed: " + cascade);
        assertTrue(cascade.contains("true"), "The cascade delete must answer true: " + cascade);
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ProductFamily.findByCode("F7CPARENT"), "The parent family must be gone");
            assertNull(ProductFamily.findByCode("F7CSUB"), "The sub-family must have been cascade-deleted");
            assertNull(Product.findByEan("F7CPROD"), "The linked product must have been cascade-deleted (most dangerous behaviour)");
        });
    }

    // --------------------------------------------------
    // F8 — price vicious cases
    // --------------------------------------------------

    /**
     * F8 — the vicious price cases. {@code currentPrice} with no active price answers {@code null} plus a
     * WARN, never an exception. A duplicate whose {@code startDateTime} is null goes UNDETECTED (the JPQL
     * {@code = null} never matches), so two identical prices are created. And {@code updatePrice} of a
     * price whose start is null trips a masked NPE ({@code targetStart.equals(currentStart)} on a null
     * target) surfacing as {@code An error occurred during updatePrice.}.
     */
    @Test
    void f8_priceViciousCases() {
        long prod = createReturningId("mutation { createProduct(input: {ean: \\\"F8PROD\\\", name: \\\"F8 Product\\\", productType: \\\"UNIT\\\"}) { id } }", "data.createProduct.id");
        long store = createReturningId("mutation { createStore(input: {code: \\\"F8STORE\\\", name: \\\"F8 Store\\\"}) { id } }", "data.createStore.id");
        // currentPrice without any active price -> null, no error
        String current = admin("{ currentPrice(productId: " + prod + ", storeId: " + store + ") { id } }");
        assertFalse(current.contains("\"errors\""), "currentPrice with no active price must not raise: " + current);
        assertTrue(current.contains("\"currentPrice\":null") || current.contains("\"currentPrice\": null"),
                "currentPrice with no active price must resolve to null: " + current);
        // Duplicate with a null startDateTime is NOT detected -> two identical prices created
        String nullStartPrice = "mutation { createPrice(input: {productId: " + prod + ", storeId: " + store
                + ", priceUsage: DEFAULT, priceExcludingTax: 10.00, priceIncludingTax: 12.00, vatRate: 0.20, priority: 1, endDateTime: \\\"2025-12-31T00:00:00\\\"}) { id } }";
        String first = admin(nullStartPrice);
        assertFalse(first.contains("\"errors\""), "The first null-start price must be created: " + first);
        String second = admin(nullStartPrice);
        assertFalse(second.contains("\"errors\""), "The duplicate null-start price must slip past the '= null' check: " + second);
        long[] count = new long[1];
        long[] nullStartId = new long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            count[0] = Price.count("product.ean = ?1 and store.code = ?2 and startDateTime is null", "F8PROD", "F8STORE");
            Price p = Price.find("product.ean = ?1 and store.code = ?2 and startDateTime is null", "F8PROD", "F8STORE").firstResult();
            nullStartId[0] = p != null ? p.id : -1L;
        });
        assertEquals(2L, count[0], "Two identical null-start prices must coexist (duplicate undetected)");
        // updatePrice of a null-start price -> masked NPE
        String npe = admin("mutation { updatePrice(id: " + nullStartId[0] + ", input: {priceExcludingTax: 9.99}) { id } }");
        assertTrue(npe.contains("An error occurred during updatePrice."), "Updating a null-start price must surface the masked NPE: " + npe);
    }

    // --------------------------------------------------
    // F9 — createStoreGroup asymmetry: create vs update
    // --------------------------------------------------

    /**
     * F9 — the {@code createStoreGroup} asymmetry, CALIBRATED. An unknown sub-group code on CREATE is NOT
     * checked: {@code findByCode} returns null and the code path does {@code group.storeGroups.add(child)}
     * with that null. CALIBRATION — the catalog expects "a null added to the Set → generic error", but
     * observed reality is milder: the create SUCCEEDS silently (Hibernate simply skips the null element),
     * so no sub-group link is persisted and no error is raised. The very same unknown code on UPDATE IS
     * checked explicitly and throws a {@code NoSuchElementException}; but, exactly like F3, that unwrapped
     * exception is MASKED by SmallRye as {@code "System error"} (the catalog's "clean message" does not
     * reproduce). Both observed branches are pinned.
     */
    @Test
    void f9_createStoreGroupAsymmetry() {
        String createUnknown = admin("mutation { createStoreGroup(input: {code: \\\"F9CRT\\\", name: \\\"F9 Create\\\", storeGroupCodes: [\\\"F9NOSUCH\\\"]}) { id } }");
        assertFalse(createUnknown.contains("\"errors\""),
                "Observed reality: an unknown sub-group on create is silently accepted, not rejected: " + createUnknown);
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup created = StoreGroup.findByCode("F9CRT");
            assertNotNull(created, "The group must have been created despite the unknown sub-group");
            assertTrue(created.storeGroups.isEmpty(), "The null child must have been dropped, leaving no sub-group link");
        });
        long grpId = createReturningId("mutation { createStoreGroup(input: {code: \\\"F9UPD\\\", name: \\\"F9 Update\\\"}) { id } }", "data.createStoreGroup.id");
        String updateUnknown = admin("mutation { updateStoreGroup(id: " + grpId + ", input: {storeGroupCodes: [\\\"F9NOSUCH\\\"]}) { id } }");
        assertTrue(updateUnknown.contains("\"errors\""), "An unknown sub-group on update must fail: " + updateUnknown);
        assertTrue(updateUnknown.contains("System error"),
                "The update-path not-found is masked as the generic (catalog's clean message does not reproduce): " + updateUnknown);
    }

    // --------------------------------------------------
    // F10 — indirect cycles accepted
    // --------------------------------------------------

    /**
     * F10 — indirect cycles are accepted. Building A→B and then B→A through two updates is accepted
     * ({@code wouldCreateCycle} is never called; only the DIRECT self-reference is blocked), and
     * {@code allStoreGroups} still returns without looping (the {@code visited} sets protect the read).
     * The two directional links are found by predicate through Panache.
     */
    @Test
    void f10_indirectCyclesAccepted() {
        long aId = createReturningId("mutation { createStoreGroup(input: {code: \\\"F10A\\\", name: \\\"F10 Group A\\\"}) { id } }", "data.createStoreGroup.id");
        long bId = createReturningId("mutation { createStoreGroup(input: {code: \\\"F10B\\\", name: \\\"F10 Group B\\\"}) { id } }", "data.createStoreGroup.id");
        String aToB = admin("mutation { updateStoreGroup(id: " + aId + ", input: {storeGroupCodes: [\\\"F10B\\\"]}) { id } }");
        assertFalse(aToB.contains("\"errors\""), "A containing B must be accepted: " + aToB);
        String bToA = admin("mutation { updateStoreGroup(id: " + bId + ", input: {storeGroupCodes: [\\\"F10A\\\"]}) { id } }");
        assertFalse(bToA.contains("\"errors\""), "B containing A (indirect cycle) must be accepted: " + bToA);
        String all = admin("{ allStoreGroups { id code } }");
        assertFalse(all.contains("\"errors\""), "allStoreGroups must survive the cycle (visited sets): " + all);
        assertTrue(all.contains("F10A") && all.contains("F10B"), "Both cyclic groups must be listed: " + all);
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup a = StoreGroup.findByCode("F10A");
            StoreGroup b = StoreGroup.findByCode("F10B");
            assertNotNull(a, "Group A must exist");
            assertNotNull(b, "Group B must exist");
            assertTrue(a.storeGroups.stream().anyMatch(g -> "F10B".equals(g.code)), "A must contain B");
            assertTrue(b.storeGroups.stream().anyMatch(g -> "F10A".equals(g.code)), "B must contain A");
        });
    }

    // --------------------------------------------------
    // F11 — offersByStoresAndType: duplicate code trap
    // --------------------------------------------------

    /**
     * F11 — the {@code offersByStoresAndType} duplicate trap. A duplicated code in {@code storeCodes}
     * makes the distinct-store lookup smaller than the requested list ({@code stores.size() !=
     * storeCodes.size()}), so the size comparison mistakes a duplicate for a missing store and throws the
     * {@code NoSuchElementException One or more Store codes provided do not exist.}. CALIBRATION — as in
     * F3/F9, that unwrapped exception is MASKED by SmallRye as {@code "System error"}; the trap is proven
     * by the error being raised at all (a real match would have returned {@code data} with an empty list),
     * and the generic rendering is pinned as observed.
     */
    @Test
    void f11_offersByStoresAndTypeDuplicate() {
        admin("mutation { createStore(input: {code: \\\"F11STORE\\\", name: \\\"F11 Store\\\"}) { id } }");
        String body = admin("{ offersByStoresAndType(storeCodes: [\\\"F11STORE\\\", \\\"F11STORE\\\"], type: \\\"MEAL_VOUCHER\\\") { id } }");
        assertTrue(body.contains("\"errors\""), "A duplicate code must be mistaken for a missing store: " + body);
        assertTrue(body.contains("System error"),
                "The size-comparison trap raises an error, masked by SmallRye as the generic: " + body);
    }
}
