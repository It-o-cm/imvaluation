package com.intermarche.valuation;

import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.ui.OfferSchemaRegistry;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe asserting that the valuation referential is servable.
 * <p>
 * The standard {@code /q/health} endpoint is the ecosystem contract: impos polls it to
 * detect a degraded engine (circuit breaker, "VALORISATION INDISPONIBLE" banner) and the
 * qualification harness uses it as a deployment probe. Beyond the datasource — already
 * covered by the extension's automatic check, which this class does not duplicate — the
 * engine is only truly usable once two conditions hold:
 * <ul>
 *   <li>at least one {@link Store} exists, so the referential can resolve a basket's site;</li>
 *   <li>the offer-factory registry is populated, i.e. it exposes at least one schema.</li>
 * </ul>
 * When either is missing the check reports {@code DOWN} and names the gap, so the caller
 * distinguishes "database up but not yet loaded" from a raw connectivity failure.
 */
@Readiness
@ApplicationScoped
public class ReferentialReadinessCheck implements HealthCheck {

    /**
     * The name published for this readiness check in the aggregated health payload.
     */
    static final String NAME = "imvaluation-ready";

    /**
     * Registry aggregating every offer/advantage factory schema, populated at startup.
     */
    @Inject
    OfferSchemaRegistry offerSchemaRegistry;

    /**
     * Evaluates whether the referential is servable and builds the readiness response.
     * <p>
     * The store count is read in its own transaction, the only way a Panache query can run
     * from a health check that carries no ambient transaction. The registry is application
     * scoped and needs none.
     *
     * @return {@code UP} when a store exists and a schema is registered, otherwise {@code
     *         DOWN} carrying the name of the first missing piece.
     */
    @Override
    public HealthCheckResponse call() {
        long storeCount = QuarkusTransaction.requiringNew().call(() -> Store.count());
        int schemaCount = offerSchemaRegistry.getKnownTypes().size();
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(NAME)
                .withData("stores", storeCount)
                .withData("offerSchemas", schemaCount);
        if (storeCount == 0) {
            return builder.withData("missing", "stores").down().build();
        }
        if (schemaCount == 0) {
            return builder.withData("missing", "offerSchemas").down().build();
        }
        return builder.up().build();
    }
}
