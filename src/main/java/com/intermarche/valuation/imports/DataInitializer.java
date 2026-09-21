package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Store;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;

/**
 * Seeds the reference data (stores, groups, products, families, categories, prices and
 * offers) when the database is empty, so a freshly started instance is usable without any
 * manual import.
 * <p>
 * The database is in-memory and recreated at every start, so without this initializer every
 * restart (including a dev-mode live reload) would leave the application without a single
 * store and every valuation would fail. The seed reuses the CSV import endpoints
 * programmatically: the data loaded at startup is exactly the data those endpoints accept,
 * upserts included, and the payloads are the CSV files embedded under
 * {@code src/main/resources/seed/} (the same dataset as the manual import clients).
 * <p>
 * The initializer only ever runs when <b>no</b> store exists at all, and only when
 * {@code valuation.bootstrap.data.enabled} is {@code true} (the default enables it in the
 * dev profile only): production instances load their catalog through the real import
 * endpoints and must never be polluted with demonstration data.
 */
@Singleton
public class DataInitializer {

    private static final Logger LOGGER = Logger.getLogger(DataInitializer.class);

    /**
     * Classpath folder containing the embedded seed CSV files.
     */
    private static final String SEED_FOLDER = "/seed/";

    /**
     * Whether the reference data seed runs at startup.
     * <p>
     * Defaults to {@code false}: only profiles that explicitly opt in (dev) are seeded.
     */
    @ConfigProperty(name = "valuation.bootstrap.data.enabled", defaultValue = "false")
    boolean enabled;

    /**
     * Importer used to load the seed stores.
     */
    @Inject
    StoreCsvResource storeCsvResource;

    /**
     * Importer used to load the seed store groups.
     */
    @Inject
    StoreGroupCsvResource storeGroupCsvResource;

    /**
     * Importer used to load the seed products.
     */
    @Inject
    ProductCsvResource productCsvResource;

    /**
     * Importer used to load the seed product families.
     */
    @Inject
    ProductFamilyCsvResource productFamilyCsvResource;

    /**
     * Importer used to load the seed product category storages.
     */
    @Inject
    ProductCategoryStorageCsvResource productCategoryStorageCsvResource;

    /**
     * Importer used to load the seed VAT regimes.
     */
    @Inject
    VatRateCsvResource vatRateCsvResource;

    /**
     * Importer used to load the seed prices.
     */
    @Inject
    PriceCsvResource priceCsvResource;

    /**
     * Importer used to load the seed offers.
     */
    @Inject
    OfferCsvResource offerCsvResource;

    /**
     * Seeds the reference data on startup when enabled and the database is empty.
     * <p>
     * The imports run in dependency order (stores before groups, products before families
     * and prices, offers last), each one managing its own transactions exactly as the HTTP
     * endpoints do. This method is deliberately not transactional: the importers drive the
     * transaction manager themselves and would refuse to start inside an enclosing
     * transaction.
     * <p>
     * The seed calls {@link ImporterCsvResource#importCsvStream(InputStream, String, java.util.List)}
     * directly rather than the endpoint methods: those carry {@code @RolesAllowed("ADMIN")}, and the
     * startup thread has no authenticated identity, so going through them would fail with an
     * {@code UnauthorizedException}. The key and required columns mirror the ones each endpoint
     * passes, so the seed enforces the same header contract as the HTTP imports.
     *
     * @param event The startup event that triggers the initialization.
     */
    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            return;
        }
        if (isAlreadySeeded()) {
            LOGGER.info("Reference data already present: startup seed skipped.");
            return;
        }
        LOGGER.info("Empty database: seeding the reference data from the embedded CSV files.");
        try {
            seed("stores.csv", stream -> storeCsvResource.importCsvStream(
                    stream, StoreCsvResource.COL_CODE, StoreCsvResource.REQUIRED_COLUMNS));
            seed("store-groups.csv", stream -> storeGroupCsvResource.importCsvStream(
                    stream, StoreGroupCsvResource.COL_CODE, StoreGroupCsvResource.REQUIRED_COLUMNS));
            seed("products.csv", stream -> productCsvResource.importCsvStream(
                    stream, ProductCsvResource.COL_EAN, ProductCsvResource.REQUIRED_COLUMNS));
            seed("product-families.csv", stream -> productFamilyCsvResource.importCsvStream(
                    stream, ProductFamilyCsvResource.COL_CODE, ProductFamilyCsvResource.REQUIRED_COLUMNS));
            seed("product-category-storages.csv", stream -> productCategoryStorageCsvResource.importCsvStream(
                    stream, ProductCategoryStorageCsvResource.COL_EAN, ProductCategoryStorageCsvResource.REQUIRED_COLUMNS));
            // VAT regimes are loaded BEFORE prices: a price attaches to the regime that carries its
            // rate, so the referential must already hold the five regimes when the price feed runs.
            seed("vat-rates.csv", stream -> vatRateCsvResource.importCsvStream(
                    stream, VatRateCsvResource.COL_VAT_NUMBER, VatRateCsvResource.REQUIRED_COLUMNS));
            seed("prices.csv", stream -> priceCsvResource.importCsvStream(
                    stream, PriceCsvResource.COL_EAN, PriceCsvResource.REQUIRED_COLUMNS));
            seed("offers.csv", stream -> offerCsvResource.importCsvStream(
                    stream, OfferCsvResource.COL_CODE, OfferCsvResource.REQUIRED_COLUMNS));
            LOGGER.info("Reference data seed completed.");
        } catch (Exception e) {
            // Report §3: a partial seed must not survive. The guard on the next boot is
            // Store.count() > 0, so a run that seeds stores then fails a later file would lock in a
            // permanently partial database. Wipe everything so the next startup re-seeds cleanly.
            LOGGER.error("Reference data seed failed; wiping the partial seed so the next startup retries.", e);
            wipeSeededData();
        }
    }

    /**
     * Deletes every seeded reference row, in reverse dependency order, so a failed seed leaves no
     * partial state behind (report §3). Runs in its own transaction, like the seed itself.
     */
    private void wipeSeededData() {
        QuarkusTransaction.requiringNew().run(() -> {
            com.intermarche.valuation.domain.Offer.deleteAll();
            com.intermarche.valuation.domain.Price.deleteAll();
            com.intermarche.valuation.domain.ProductCategoryStorage.deleteAll();
            com.intermarche.valuation.domain.ProductFamily.deleteAll();
            com.intermarche.valuation.domain.VatRate.deleteAll();
            com.intermarche.valuation.domain.Product.deleteAll();
            com.intermarche.valuation.domain.StoreGroup.deleteAll();
            Store.deleteAll();
        });
    }

    /**
     * Tells whether the reference data is already present.
     * <p>
     * The check runs in its own transaction because the startup event fires outside any
     * request scope. Stores are the root of every dependency chain, so their presence is a
     * reliable witness of a previous seed or import.
     *
     * @return {@code true} when at least one store exists.
     */
    private boolean isAlreadySeeded() {
        return QuarkusTransaction.requiringNew().call(() -> Store.count() > 0);
    }

    /**
     * Runs one embedded CSV file through the matching importer, failing the whole seed on any
     * problem (report §3).
     * <p>
     * A missing file or a non-200 response throws, so {@link #onStart} can wipe the partial seed:
     * a half-loaded referential must not be locked in by the {@code Store.count() > 0} guard.
     *
     * @param fileName The name of the CSV file under the seed folder.
     * @param importer The importer invocation to feed with the file content.
     * @throws IOException           if the file cannot be read.
     * @throws IllegalStateException if the file is missing or the import returns a non-200 status.
     */
    private void seed(String fileName, java.util.function.Function<InputStream, Response> importer)
            throws IOException {
        try (InputStream stream = DataInitializer.class.getResourceAsStream(SEED_FOLDER + fileName)) {
            if (stream == null) {
                throw new IllegalStateException("Seed file not found on the classpath: " + SEED_FOLDER + fileName);
            }
            Response response = importer.apply(stream);
            if (response.getStatus() == 200) {
                LOGGER.infof("Seeded %s: %s", fileName, response.getEntity());
            } else {
                throw new IllegalStateException("Seed of " + fileName + " failed with status "
                        + response.getStatus() + ": " + response.getEntity());
            }
        }
    }
}
