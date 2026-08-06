package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.util.List;

/**
 * Per-class isolation for the e2e {@code Group*IT} suite.
 * <p>
 * Every {@code @QuarkusTest} class in the suite shares ONE H2 in-memory instance (the application
 * boots once and {@code drop-and-create} runs only at that boot), so the extra offers and prices a
 * class seeds through the import endpoints ACCUMULATE across classes. When the whole suite runs in a
 * single JVM (as opposed to the per-group campaign command {@code -Dit.test=Group<letter>IT}), an
 * offer one class pins to a shared catalog store bleeds into a sibling's valuations and breaks its
 * assertions. This helper wipes the two mutable tables so each class can re-seed the mirror catalog
 * plus its own extras from a clean slate, making the suite order-independent.
 */
final class CatalogReset {

    /**
     * Non-instantiable utility holder.
     */
    private CatalogReset() {
    }

    /**
     * Deletes every offer and price so the caller can re-seed a pristine mutable catalog. Offers are
     * removed one entity at a time (not by a bulk {@code deleteAll}) so Hibernate also clears their
     * owned join rows &mdash; {@code offer_eans}, {@code offer_stores}, {@code offer_store_groups}
     * &mdash; which a bulk HQL delete would leave dangling against their foreign keys. Nothing
     * references a price, so prices go by a plain bulk delete. Runs in its own transaction.
     */
    static void resetMutableCatalog() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<Offer> offers = Offer.listAll();
            for (Offer offer : offers) {
                offer.delete();
            }
            Price.deleteAll();
        });
    }
}
