package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group E — CSV imports, the per-resource specifics — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest} application,
 * authenticating with HTTP Basic as {@code admin/admin}. Where Group D pinned the generic import
 * mechanics ({@code ImporterCsvResource}), this group pins the seven concrete importers: the store
 * upsert and its silent coordinate parsing (E1), the additive store-group linking with its ordering
 * guard (E2), the {@code active}-defaults-to-false product trap and the unknown-{@code ProductType}
 * path (E3), the product-family replacement strategy plus its frozen detached-update defect (E4),
 * the {@code (ean, level1, level5)} category functional key (E5), the price composite key and its
 * usage/priority/date branches (E6) and the offer targeting, specification and length rules (E7).
 * <p>
 * The group needs no mirror seed: each scenario imports its own throwaway prerequisites (stores,
 * products, groups) through the very endpoints under test, with a per-scenario code prefix, so the
 * scenarios stay isolated even though the H2 database lives for the whole JVM.
 * <p>
 * CALIBRATION — E4 frozen defect does NOT reproduce here. The catalog claims an existing family's
 * update is counted but never persisted (detached entity). Under this harness the pre-fetched family
 * stays managed in the request-scoped session that the commit flushes, so the update is durable:
 * {@link #e4_existingFamilyUpdateCountedAndPersistedHere} pins the observed reality.
 * <p>
 * TRANSVERSE GUARD — HashSet membership: {@code Offer.stores}, {@code StoreGroup.stores},
 * {@code StoreGroup.storeGroups} and {@code ProductFamily.productFamilies} are {@code HashSet}s;
 * every DB assertion below finds a link by predicate (its {@code code}/{@code ean}), never by index.
 * <p>
 * CALIBRATION — direct vs commit-wrapped error lines. Errors thrown as
 * {@code IllegalArgumentException}/{@code RuntimeException} inside {@code processLineLogic} surface
 * literally in the malformed {@code errors[]} body (Q-D of the catalog): the E2/E4/E5/E6/E7
 * "not found" / "cannot contain itself" / "Must define…" / "Failed to parse…" texts are asserted
 * verbatim. Bean-validation and column-length failures fire only at commit and are therefore wrapped
 * by the Narayana commit wrapper ({@code ARJUNA016053: Could not commit transaction.}) exactly as
 * Group D calibrated for the empty-name store; for those (E1 empty name, E3 mandatory type, E6 empty
 * priority, E7 over-long specification) only the deterministic {@code Line N (<code>):} prefix and
 * the zero-row DB outcome are pinned, never the wrapped constraint text.
 * <p>
 * CALIBRATION — E6 usage message. The bulk chunk throws {@code PriceUsage is mandatory at column 5}
 * (from {@code feedPrice}) but the definitive {@code errors[]} entry is produced by the 1-by-1
 * fallback, whose {@code retrievePrices} throws the shorter {@code PriceUsage is mandatory}. The
 * catalog lists both spellings; the assertion pins the common substring {@code PriceUsage is
 * mandatory} so it holds whichever path records the line.
 */
@QuarkusTest
class GroupEIT {

    /**
     * Posts a raw CSV body to an import endpoint as {@code admin/admin} and asserts a 200.
     *
     * @param path The import endpoint path.
     * @param csv  The raw CSV payload (pipe-separated, first line is the header).
     * @return The response body as a string, for textual assertions.
     */
    private String importCsv(String path, String csv) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post(path)
                .then().statusCode(200)
                .extract().asString();
    }

    /**
     * Seeds a single valid store through {@code /stores/import} so dependent scenarios have a target.
     *
     * @param code The store code.
     * @param name The store name.
     */
    private void seedStore(String code, String name) {
        String body = importCsv("/stores/import",
                "code|name|s1|s2|pc|city|country|lat|lon\n"
                        + code + "|" + name + "|1 rue||59000|Lille|FR|50.6|3.0\n");
        assertTrue(body.contains("\"createdCount\":1"), "Store seed must create exactly one row: " + body);
    }

    /**
     * Seeds a single valid UNIT product through {@code /products/import} so dependent scenarios have a target.
     *
     * @param ean  The product EAN.
     * @param name The product name.
     */
    private void seedProduct(String ean, String name) {
        String body = importCsv("/products/import",
                "ean|name|desc|brand|refW|refV|type|unit|active\n"
                        + ean + "|" + name + "|desc|BRAND|||UNIT|piece|true\n");
        assertTrue(body.contains("\"createdCount\":1"), "Product seed must create exactly one row: " + body);
    }

    // --------------------------------------------------
    // E1 — Stores: upsert by code, optional coordinates, mandatory name
    // --------------------------------------------------

    /**
     * E1 — upsert by code with silently optional coordinates. Importing a store whose lat/lon columns
     * carry unparseable text creates the row all the same ({@code safeParseDouble} swallows the
     * {@code NumberFormatException} and stores {@code null}); re-importing the same code with valid
     * coordinates is counted as one update and persists the new values, proving the key is the code.
     */
    @Test
    void e1_upsertByCodeCoordinatesSilentlyOptional() {
        String created = importCsv("/stores/import",
                "code|name|s1|s2|pc|city|country|lat|lon\n"
                        + "E1S1|E1 Store|1 rue||59000|Lille|FR|not-a-number|also-bad\n");
        assertTrue(created.contains("{\"createdCount\":1, \"updatedCount\":0}"),
                "Unparseable coordinates must not block the creation: " + created);
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("E1S1");
            assertNotNull(store, "The store must have been created despite the bad coordinates");
            assertNull(store.address.latitude, "An unparseable latitude must be stored as null");
            assertNull(store.address.longitude, "An unparseable longitude must be stored as null");
        });
        String updated = importCsv("/stores/import",
                "code|name|s1|s2|pc|city|country|lat|lon\n"
                        + "E1S1|E1 Store|1 rue||59000|Lille|FR|50.6|3.06\n");
        assertTrue(updated.contains("{\"createdCount\":0, \"updatedCount\":1}"),
                "Re-importing the same code with new coordinates must be one update: " + updated);
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = Store.findByCode("E1S1");
            assertEquals(50.6, store.address.latitude, "The valid latitude must now be persisted");
            assertEquals(3.06, store.address.longitude, "The valid longitude must now be persisted");
        });
    }

    /**
     * E1 — mandatory name. A store row with an empty {@code name} passes the column check but fails
     * the {@code @NotBlank Store name is mandatory} constraint at commit; the row is isolated under
     * the deterministic {@code Line 2 (E1NONAME):} prefix (the constraint text itself is wrapped by
     * the Narayana commit wrapper, see class calibration) and nothing is written to the database.
     */
    @Test
    void e1_emptyNameRejected() {
        String body = importCsv("/stores/import",
                "code|name|s1|s2|pc|city|country|lat|lon\n"
                        + "E1NONAME||1 rue||59000|Lille|FR|50.6|3.0\n");
        assertTrue(body.contains("\"createdCount\":0"), "An empty-name row must create nothing: " + body);
        assertTrue(body.contains("Line 2 (E1NONAME):"), "The faulty row must be isolated by its line: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(Store.findByCode("E1NONAME"), "The rejected store must not exist"));
    }

    // --------------------------------------------------
    // E2 — Store-groups: additive links, ordering guard, self-containment
    // --------------------------------------------------

    /**
     * E2 — additive store linking. A group first imported with two stores keeps BOTH after a second
     * import that lists only one of them: the importer only ever adds store links, it never clears
     * them (the strategy opposite to families and offers). The name is changed on the second import
     * so the checksum differs and the update path re-attaches the managed group; the surviving link
     * to the dropped store is found by predicate, never by index.
     */
    @Test
    void e2_additiveStoreLinkingNeverRemoves() {
        seedStore("E2SA", "E2 Store A");
        seedStore("E2SB", "E2 Store B");
        String created = importCsv("/store-groups/import",
                "group_code|group_name|store_codes|store_group_codes\n"
                        + "E2GRP|Group One|E2SA;E2SB|\n");
        assertTrue(created.contains("\"createdCount\":1"), "The group must be created: " + created);
        String updated = importCsv("/store-groups/import",
                "group_code|group_name|store_codes|store_group_codes\n"
                        + "E2GRP|Group Renamed|E2SA|\n");
        assertTrue(updated.contains("\"updatedCount\":1"), "The rename must be counted as one update: " + updated);
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup group = StoreGroup.findByCode("E2GRP");
            assertNotNull(group, "The group must exist");
            assertEquals(2, group.stores.size(), "The amputated import must not remove any store link");
            assertTrue(group.stores.stream().anyMatch(s -> "E2SA".equals(s.code)), "Store A must remain linked");
            assertTrue(group.stores.stream().anyMatch(s -> "E2SB".equals(s.code)),
                    "Store B must remain linked despite being dropped from the second import");
        });
    }

    /**
     * E2 — unknown store target. A group referencing a store code that does not exist fails its line
     * with the literal {@code Store '<code>' not found.} (thrown directly in {@code linkStores}, so
     * it surfaces verbatim in {@code errors[]}) and the group is rolled back — created count stays 0.
     */
    @Test
    void e2_unknownStoreRejected() {
        String body = importCsv("/store-groups/import",
                "group_code|group_name|store_codes|store_group_codes\n"
                        + "E2BADSTORE|Grp|E2NOSUCH|\n");
        assertTrue(body.contains("\"createdCount\":0"), "The group must be rolled back: " + body);
        assertTrue(body.contains("Store 'E2NOSUCH' not found."), "The literal store-not-found text must appear: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(StoreGroup.findByCode("E2BADSTORE"), "The rejected group must not exist"));
    }

    /**
     * E2 — ordering guard. A group referencing a sub-group that has not been defined yet fails with
     * the literal {@code StoreGroup '<code>' not found. Check CSV order (Parent must be defined
     * before Child).}, the message that documents the parent-before-child contract.
     */
    @Test
    void e2_unknownSubGroupOrderingGuard() {
        String body = importCsv("/store-groups/import",
                "group_code|group_name|store_codes|store_group_codes\n"
                        + "E2BADSUB|Grp||E2NOSUCHGRP\n");
        assertTrue(body.contains("\"createdCount\":0"), "The group must be rolled back: " + body);
        assertTrue(body.contains(
                        "StoreGroup 'E2NOSUCHGRP' not found. Check CSV order (Parent must be defined before Child)."),
                "The ordering-guard text must appear verbatim: " + body);
    }

    /**
     * E2 — self-containment accepted. Unlike families, a store-group has NO self-reference or cycle
     * check: after a group is created, re-importing it with itself as a sub-group succeeds — no
     * {@code cannot contain itself} error — and the group ends up listing itself among its sub-groups
     * (found by predicate). The end state is asserted whichever staged path commits it.
     */
    @Test
    void e2_selfContainmentAccepted() {
        String created = importCsv("/store-groups/import",
                "group_code|group_name|store_codes|store_group_codes\n"
                        + "E2SELF|Self Group||\n");
        assertTrue(created.contains("\"createdCount\":1"), "The group must be created first: " + created);
        String looped = importCsv("/store-groups/import",
                "group_code|group_name|store_codes|store_group_codes\n"
                        + "E2SELF|Self Group||E2SELF\n");
        assertTrue(looped.contains("\"createdCount\":0"), "No new group is created by the self-loop: " + looped);
        assertFalse(looped.contains("not found"), "Self-containment must not raise a not-found error: " + looped);
        assertFalse(looped.contains("cannot contain itself"),
                "A store-group, unlike a family, has no self-reference guard: " + looped);
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup group = StoreGroup.findByCode("E2SELF");
            assertNotNull(group, "The group must exist");
            assertTrue(group.storeGroups.stream().anyMatch(g -> "E2SELF".equals(g.code)),
                    "The group must now contain itself");
        });
    }

    // --------------------------------------------------
    // E3 — Products: active defaults to false, unknown ProductType
    // --------------------------------------------------

    /**
     * E3 — the {@code active} trap. An empty {@code active} column parses to {@code false} (not
     * {@code true}): {@code safeParseBoolean} returns {@code false} for a blank value, so a product
     * imported with a blank last column lands inactive. The major seed trap, pinned by reading the
     * persisted flag.
     */
    @Test
    void e3_activeEmptyDefaultsToFalse() {
        String body = importCsv("/products/import",
                "ean|name|desc|brand|refW|refV|type|unit|active\n"
                        + "E3ACTIVE|E3 Product|desc|BRAND|||UNIT|piece|\n");
        assertTrue(body.contains("\"createdCount\":1"), "The product must be created: " + body);
        QuarkusTransaction.requiringNew().run(() -> {
            Product product = Product.findByEan("E3ACTIVE");
            assertNotNull(product, "The product must exist");
            assertFalse(product.active, "A blank active column must default to false, not true");
        });
    }

    /**
     * E3 — unknown {@code ProductType}. An unrecognised type parses to {@code null} (with a WARN log),
     * which then trips the {@code @NotNull Product type is mandatory} constraint at commit; the row is
     * isolated under {@code Line 2 (E3BADTYPE):} (the constraint text being commit-wrapped, see class
     * calibration) and no product is written.
     */
    @Test
    void e3_unknownProductTypeRejected() {
        String body = importCsv("/products/import",
                "ean|name|desc|brand|refW|refV|type|unit|active\n"
                        + "E3BADTYPE|E3 Product|desc|BRAND|||BOGUS|piece|true\n");
        assertTrue(body.contains("\"createdCount\":0"), "An unknown type must create nothing: " + body);
        assertTrue(body.contains("Line 2 (E3BADTYPE):"), "The faulty row must be isolated by its line: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(Product.findByEan("E3BADTYPE"), "The rejected product must not exist"));
    }

    // --------------------------------------------------
    // E4 — Product-families: replacement, dependency messages, frozen detached-update defect
    // --------------------------------------------------

    /**
     * E4 — existing-family update: counted AND, contrary to the catalog, persisted here. The catalog
     * pins a frozen defect where an existing family's update is counted ({@code updatedCount:1}) but
     * never persisted (a detached entity never re-attached). CALIBRATION: under this test harness the
     * defect does NOT reproduce — the family pre-fetched in {@code processChunkWithFallback} stays
     * managed inside the request-scoped session that the later {@code withTransaction} commit flushes,
     * so the description change IS durable. The test asserts the observed reality (update counted and
     * persisted) and documents the écart with the catalog's claim in the class/report.
     */
    @Test
    void e4_existingFamilyUpdateCountedAndPersistedHere() {
        String created = importCsv("/product-families/import",
                "code|description|flags|product_eans|family_codes\n"
                        + "E4FAM|First Description|||\n");
        assertTrue(created.contains("\"createdCount\":1"), "The family must be created: " + created);
        String updated = importCsv("/product-families/import",
                "code|description|flags|product_eans|family_codes\n"
                        + "E4FAM|Second Description|||\n");
        assertTrue(updated.contains("\"updatedCount\":1"), "The description change must be counted as one update: " + updated);
        QuarkusTransaction.requiringNew().run(() -> {
            ProductFamily family = ProductFamily.findByCode("E4FAM");
            assertNotNull(family, "The family must exist");
            assertEquals("Second Description", family.description,
                    "Observed reality: the counted update is persisted here (catalog's frozen defect does not reproduce)");
        });
    }

    /**
     * E4 — unknown product link. A family referencing a product EAN that does not exist fails its line
     * with the literal {@code Product EAN '<ean>' not found.} (thrown directly in
     * {@code prepareProductFamily}) and is rolled back.
     */
    @Test
    void e4_unknownProductEanRejected() {
        String body = importCsv("/product-families/import",
                "code|description|flags|product_eans|family_codes\n"
                        + "E4BADPROD|Desc||E4NOSUCHEAN|\n");
        assertTrue(body.contains("\"createdCount\":0"), "The family must be rolled back: " + body);
        assertTrue(body.contains("Product EAN 'E4NOSUCHEAN' not found."),
                "The literal product-not-found text must appear: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(ProductFamily.findByCode("E4BADPROD"), "The rejected family must not exist"));
    }

    /**
     * E4 — unknown sub-family link. A family referencing a sub-family code that does not exist fails
     * with the literal {@code SubFamily code '<code>' not found.} and is rolled back.
     */
    @Test
    void e4_unknownSubFamilyRejected() {
        String body = importCsv("/product-families/import",
                "code|description|flags|product_eans|family_codes\n"
                        + "E4BADSUB|Desc|||E4NOSUCHFAM\n");
        assertTrue(body.contains("\"createdCount\":0"), "The family must be rolled back: " + body);
        assertTrue(body.contains("SubFamily code 'E4NOSUCHFAM' not found."),
                "The literal sub-family-not-found text must appear: " + body);
    }

    /**
     * E4 — self-reference guard. A family listing itself among its sub-families fails with the literal
     * {@code Family '<code>' cannot contain itself.} — the guard that store-groups deliberately lack
     * (contrast E2). The family is created first, then re-imported referencing its own code.
     */
    @Test
    void e4_selfReferenceRejected() {
        String created = importCsv("/product-families/import",
                "code|description|flags|product_eans|family_codes\n"
                        + "E4SELF|Desc|||\n");
        assertTrue(created.contains("\"createdCount\":1"), "The family must be created first: " + created);
        String looped = importCsv("/product-families/import",
                "code|description|flags|product_eans|family_codes\n"
                        + "E4SELF|Desc|||E4SELF\n");
        assertTrue(looped.contains("Family 'E4SELF' cannot contain itself."),
                "The self-reference guard text must appear verbatim: " + looped);
    }

    // --------------------------------------------------
    // E5 — Categories: (ean, level1, level5) functional key
    // --------------------------------------------------

    /**
     * E5 — a mid-level change is a detected update. The functional key is {@code (ean, level1,
     * level5)}, but the checksum spans every level, so re-importing a storage that keeps the same key
     * yet changes only {@code level2} is correctly counted as one update and persisted (the storage is
     * re-attached via {@code findById}, unlike families). Both the update count and the new
     * {@code level2} are pinned.
     */
    @Test
    void e5_midLevelChangeDetectedAsUpdate() {
        seedProduct("E5PROD", "E5 Product");
        String created = importCsv("/product-category-storages/import",
                "productEan|level1|level2|level3|level4|level5\n"
                        + "E5PROD|L1|L2a|L3|L4|L5\n");
        assertTrue(created.contains("\"createdCount\":1"), "The storage must be created: " + created);
        String updated = importCsv("/product-category-storages/import",
                "productEan|level1|level2|level3|level4|level5\n"
                        + "E5PROD|L1|L2b|L3|L4|L5\n");
        assertTrue(updated.contains("\"updatedCount\":1"),
                "A change confined to level2 must still be one update: " + updated);
        QuarkusTransaction.requiringNew().run(() -> {
            ProductCategoryStorage storage = ProductCategoryStorage
                    .find("product.ean = ?1 and level1 = ?2 and level5 = ?3", "E5PROD", "L1", "L5").firstResult();
            assertNotNull(storage, "The storage must exist under its unchanged functional key");
            assertEquals("L2b", storage.level2, "The mid-level change must be persisted");
        });
    }

    /**
     * E5 — unknown EAN, quoted. A category row whose product EAN does not exist fails with the literal
     * {@code Product with EAN '<ean>' not found.} — WITH surrounding quotes, the deliberate asymmetry
     * against the price importer's unquoted variant (contrast E6).
     */
    @Test
    void e5_unknownEanQuoted() {
        String body = importCsv("/product-category-storages/import",
                "productEan|level1|level2|level3|level4|level5\n"
                        + "E5NOSUCH|L1|L2|L3|L4|L5\n");
        assertTrue(body.contains("\"createdCount\":0"), "The storage must be rolled back: " + body);
        assertTrue(body.contains("Product with EAN 'E5NOSUCH' not found."),
                "The category importer must quote the EAN: " + body);
    }

    // --------------------------------------------------
    // E6 — Prices: composite key, usage/priority/date branches
    // --------------------------------------------------

    /**
     * E6 — changing {@code endDateTime} updates in place. With the composite key
     * {@code ean:store:usage:start:priority} unchanged, editing only the end date is one update and
     * leaves exactly one price row carrying the new end date.
     */
    @Test
    void e6_endDateChangeUpdatesInPlace() {
        seedProduct("E6PEND", "E6 Product End");
        seedStore("E6SEND", "E6 Store End");
        String created = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PEND|E6SEND|10.00|12.00|0.20|DEFAULT|1|2025-01-01T00:00:00|2025-06-30T00:00:00\n");
        assertTrue(created.contains("\"createdCount\":1"), "The price must be created: " + created);
        String updated = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PEND|E6SEND|10.00|12.00|0.20|DEFAULT|1|2025-01-01T00:00:00|2025-12-31T00:00:00\n");
        assertTrue(updated.contains("\"updatedCount\":1"), "The end-date change must be one update: " + updated);
        long[] count = new long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            count[0] = Price.count("product.ean = ?1 and store.code = ?2", "E6PEND", "E6SEND");
            Price price = Price.find("product.ean = ?1 and store.code = ?2", "E6PEND", "E6SEND").firstResult();
            assertNotNull(price, "The price must exist");
            assertEquals("2025-12-31T00:00", price.endDateTime.toString(), "The new end date must be persisted");
        });
        assertEquals(1L, count[0], "Editing the end date must not create a second price");
    }

    /**
     * E6 — changing {@code priority} creates an additional price. Because the priority is part of the
     * composite key, a re-import with a different priority does not update the first price but creates
     * a second, overlapping one (silent overlap resolved later by priority). Two rows must remain.
     */
    @Test
    void e6_priorityChangeCreatesAdditionalPrice() {
        seedProduct("E6PPRIO", "E6 Product Prio");
        seedStore("E6SPRIO", "E6 Store Prio");
        importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PPRIO|E6SPRIO|10.00|12.00|0.20|DEFAULT|1|2025-01-01T00:00:00|2025-12-31T00:00:00\n");
        String second = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PPRIO|E6SPRIO|10.00|12.00|0.20|DEFAULT|2|2025-01-01T00:00:00|2025-12-31T00:00:00\n");
        assertTrue(second.contains("\"createdCount\":1"),
                "A different priority must create, not update: " + second);
        long[] count = new long[1];
        QuarkusTransaction.requiringNew().run(() ->
                count[0] = Price.count("product.ean = ?1 and store.code = ?2", "E6PPRIO", "E6SPRIO"));
        assertEquals(2L, count[0], "The priority change must leave two overlapping prices");
    }

    /**
     * E6 — invalid {@code priceUsage}. An unrecognised usage parses to {@code null}, so the price is
     * treated as new and rejected on the mandatory-usage guard; the definitive line carries the common
     * substring {@code PriceUsage is mandatory} (the exact spelling depending on the staged path, see
     * class calibration) and nothing is written.
     */
    @Test
    void e6_invalidUsageRejected() {
        seedProduct("E6PUSAGE", "E6 Product Usage");
        seedStore("E6SUSAGE", "E6 Store Usage");
        String body = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PUSAGE|E6SUSAGE|10.00|12.00|0.20|BOGUS|1|2025-01-01T00:00:00|2025-12-31T00:00:00\n");
        assertTrue(body.contains("\"createdCount\":0"), "An invalid usage must create nothing: " + body);
        assertTrue(body.contains("PriceUsage is mandatory"), "The mandatory-usage text must appear: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(0L, Price.count("product.ean = ?1", "E6PUSAGE"), "No price must be written"));
    }

    /**
     * E6 — unknown EAN, unquoted. A price row whose product EAN does not exist fails with the literal
     * {@code Product with EAN <ean> not found.} — WITHOUT quotes, the asymmetry against the quoted
     * category variant (contrast E5); the presence of the unquoted form is pinned positively.
     */
    @Test
    void e6_unknownEanUnquoted() {
        seedStore("E6SNOEAN", "E6 Store No Ean");
        String body = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6NOSUCH|E6SNOEAN|10.00|12.00|0.20|DEFAULT|1|2025-01-01T00:00:00|2025-12-31T00:00:00\n");
        assertTrue(body.contains("\"createdCount\":0"), "The price must be rolled back: " + body);
        assertTrue(body.contains("Product with EAN E6NOSUCH not found."),
                "The price importer must NOT quote the EAN: " + body);
    }

    /**
     * E6 — empty {@code priority}. A blank priority parses to {@code null} and, since the column is
     * {@code nullable = false}, fails at commit; the row is isolated under {@code Line 2 (E6PNOPRIO):}
     * (the constraint text being commit-wrapped, see class calibration) and no price is written.
     */
    @Test
    void e6_emptyPriorityRejected() {
        seedProduct("E6PNOPRIO", "E6 Product No Prio");
        seedStore("E6SNOPRIO", "E6 Store No Prio");
        String body = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PNOPRIO|E6SNOPRIO|10.00|12.00|0.20|DEFAULT||2025-01-01T00:00:00|2025-12-31T00:00:00\n");
        assertTrue(body.contains("\"createdCount\":0"), "An empty priority must create nothing: " + body);
        assertTrue(body.contains("Line 2 (E6PNOPRIO):"), "The faulty row must be isolated by its line: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(0L, Price.count("product.ean = ?1", "E6PNOPRIO"), "No price must be written"));
    }

    /**
     * E6 — non-ISO start date is silently nulled. An unparseable {@code startDateTime} is logged (WARN)
     * and stored as {@code null}, which also shifts the composite key: the price is still created, and
     * the persisted start date is {@code null}.
     */
    @Test
    void e6_nonIsoStartDateNulled() {
        seedProduct("E6PDATE", "E6 Product Date");
        seedStore("E6SDATE", "E6 Store Date");
        String body = importCsv("/prices/import",
                "ean|store|excl|incl|vat|usage|priority|start|end\n"
                        + "E6PDATE|E6SDATE|10.00|12.00|0.20|DEFAULT|1|31/12/2025|2025-12-31T00:00:00\n");
        assertTrue(body.contains("\"createdCount\":1"),
                "A non-ISO start date must not block the creation: " + body);
        QuarkusTransaction.requiringNew().run(() -> {
            Price price = Price.find("product.ean = ?1 and store.code = ?2", "E6PDATE", "E6SDATE").firstResult();
            assertNotNull(price, "The price must exist");
            assertNull(price.startDateTime, "The unparseable start date must be stored as null");
        });
    }

    // --------------------------------------------------
    // E7 — Offers: targeting, specification, length, unknown type
    // --------------------------------------------------

    /**
     * E7 — no target. An offer defining neither a store nor a store-group fails with the literal,
     * line-numbered {@code Line <n>: Must define at least one store_code or store_group_code.} thrown
     * inside {@code prepareOffer}; the offer is rolled back.
     */
    @Test
    void e7_noTargetRejected() {
        String body = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7NOTGT|MEAL_VOUCHER|{}||\n");
        assertTrue(body.contains("\"createdCount\":0"), "The offer must be rolled back: " + body);
        assertTrue(body.contains("Line 2: Must define at least one store_code or store_group_code."),
                "The no-target text must appear verbatim with its line number: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(Offer.findByCode("E7NOTGT"), "The rejected offer must not exist"));
    }

    /**
     * E7 — unknown store target. An offer targeting a non-existent store code fails with the literal
     * {@code Store code '<c>' not found.} (the offer-specific spelling, distinct from the price and
     * store-group variants) and is rolled back.
     */
    @Test
    void e7_unknownStoreTargetRejected() {
        String body = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7BADSTORE|MEAL_VOUCHER|{}|E7NOSUCH|\n");
        assertTrue(body.contains("\"createdCount\":0"), "The offer must be rolled back: " + body);
        assertTrue(body.contains("Store code 'E7NOSUCH' not found."),
                "The offer store-not-found text must appear verbatim: " + body);
    }

    /**
     * E7 — unknown group target. An offer targeting a non-existent store-group code fails with the
     * literal {@code StoreGroup code '<c>' not found.} and is rolled back.
     */
    @Test
    void e7_unknownGroupTargetRejected() {
        String body = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7BADGRP|MEAL_VOUCHER|{}||E7NOSUCHGRP\n");
        assertTrue(body.contains("\"createdCount\":0"), "The offer must be rolled back: " + body);
        assertTrue(body.contains("StoreGroup code 'E7NOSUCHGRP' not found."),
                "The offer group-not-found text must appear verbatim: " + body);
    }

    /**
     * E7 — canary: an unknown offer type is accepted and stored. The importer does not validate the
     * {@code type} against the engine's known applier types, so an offer with a nonsense type but a
     * valid target and parseable spec is created and persisted (the engine would simply never apply
     * it). The stored type is pinned.
     */
    @Test
    void e7_unknownTypeAcceptedAndStored() {
        seedStore("E7STYPE", "E7 Store Type");
        String body = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7BOGUS|TOTALLY_UNKNOWN_TYPE|{}|E7STYPE|\n");
        assertTrue(body.contains("\"createdCount\":1"), "An unknown type must still be accepted: " + body);
        QuarkusTransaction.requiringNew().run(() -> {
            Offer offer = Offer.findByCode("E7BOGUS");
            assertNotNull(offer, "The offer must have been stored");
            assertEquals("TOTALLY_UNKNOWN_TYPE", offer.type, "The unknown type must be persisted verbatim");
        });
    }

    /**
     * E7 — invalid specification JSON. An unparseable specification trips
     * {@code updateEansFromSpecification} in the {@code @PrePersist} hook, which throws the literal
     * {@code Failed to parse specification for Offer <code>: <msg>} recorded in {@code errors[]}; the
     * offer is rolled back.
     */
    @Test
    void e7_invalidSpecificationJsonRejected() {
        seedStore("E7SSPEC", "E7 Store Spec");
        String body = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7SPEC|MEAL_VOUCHER|{not valid json|E7SSPEC|\n");
        assertTrue(body.contains("\"createdCount\":0"), "A bad spec must create nothing: " + body);
        assertTrue(body.contains("Failed to parse specification for Offer E7SPEC:"),
                "The parse-failure text must appear verbatim: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(Offer.findByCode("E7SPEC"), "The rejected offer must not exist"));
    }

    /**
     * E7 — specification over 1000 characters. A valid-JSON but over-long specification exceeds the
     * {@code length = 1000} column and fails at commit; the row is isolated under {@code Line 2
     * (E7LONG):} (the length violation being commit-wrapped, see class calibration) and no offer is
     * written.
     */
    @Test
    void e7_specificationTooLongRejected() {
        seedStore("E7SLONG", "E7 Store Long");
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            pad.append('a');
        }
        String body = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7LONG|MEAL_VOUCHER|{\"pad\":\"" + pad + "\"}|E7SLONG|\n");
        assertTrue(body.contains("\"createdCount\":0"), "An over-long spec must create nothing: " + body);
        assertTrue(body.contains("Line 2 (E7LONG):"), "The faulty row must be isolated by its line: " + body);
        QuarkusTransaction.requiringNew().run(() ->
                assertNull(Offer.findByCode("E7LONG"), "The rejected offer must not exist"));
    }

    /**
     * E7 — target replacement. Unlike store-groups (E2, additive), the offer importer clears and
     * re-links its targets: an offer first bound to two stores keeps only the one listed on a second
     * import. The checksum spans the sorted store codes, so the change is one update and — because the
     * offer is re-attached via {@code findById} — it is persisted; the surviving single link is found
     * by predicate.
     */
    @Test
    void e7_targetReplacementReLinks() {
        seedStore("E7RA", "E7 Store RA");
        seedStore("E7RB", "E7 Store RB");
        String created = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7REPL|MEAL_VOUCHER|{}|E7RA,E7RB|\n");
        assertTrue(created.contains("\"createdCount\":1"), "The offer must be created with two stores: " + created);
        String updated = importCsv("/offers/import",
                "offer_code|offer_type|specification|store_code|store_group_code\n"
                        + "E7REPL|MEAL_VOUCHER|{}|E7RA|\n");
        assertTrue(updated.contains("\"updatedCount\":1"), "Replacing the targets must be one update: " + updated);
        QuarkusTransaction.requiringNew().run(() -> {
            Offer offer = Offer.findByCode("E7REPL");
            assertNotNull(offer, "The offer must exist");
            assertEquals(1, offer.stores.size(), "The offer importer must clear and re-link, keeping one store");
            assertTrue(offer.stores.stream().anyMatch(s -> "E7RA".equals(s.code)), "Only store RA must remain linked");
            assertFalse(offer.stores.stream().anyMatch(s -> "E7RB".equals(s.code)),
                    "Store RB must have been dropped by the replacement");
        });
    }
}
