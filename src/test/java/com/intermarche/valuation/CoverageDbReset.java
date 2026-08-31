package com.intermarche.valuation;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import com.intermarche.valuation.domain.PasswordResetToken;
import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * Shared teardown clearing every mutable reference row a {@code @QuarkusTest} coverage class
 * might leave behind in the process-wide in-memory database.
 * <p>
 * The coverage tests share one H2 instance across classes, and each seeds its own stores,
 * products, groups, users and traces. Cleaning only before each test is not enough: the rows
 * of the last test of a class survive into the next class and collide with tests that assume
 * an empty catalog (a store code is unique). Calling {@link #resetAll()} from an
 * {@code @AfterAll} makes every coverage class leave the database as clean as it found it.
 */
public final class CoverageDbReset {

    /**
     * Prevents instantiation of this static utility.
     */
    private CoverageDbReset() {
    }

    /**
     * Deletes every mutable reference row in reverse dependency order, in its own committed
     * transaction, preserving the bootstrap {@code admin} account.
     */
    public static void resetAll() {
        QuarkusTransaction.requiringNew().run(() -> {
            Price.deleteAll();
            Offer.deleteAll();
            ProductFamily.deleteAll();
            ProductCategoryStorage.deleteAll();
            Product.deleteAll();
            StoreGroup.deleteAll();
            Store.deleteAll();
            ValuationTrace.deleteAll();
            ValuationTraceConfig.deleteAll();
            PasswordResetToken.deleteAll();
            AppUser.delete("username like ?1", "test_%");
        });
    }
}
