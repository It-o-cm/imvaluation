package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Determinism test for the offer-applier sort (report C2).
 * <p>
 * The sort orders appliers by efficiency score descending; two appliers of equal score must
 * keep a deterministic order driven by the stable tie-break key, never by the order in which
 * they happened to be built. These are plain unit tests: the sort is exercised directly on
 * in-memory stub appliers, no application boot and no database.
 */
public class OfferApplierSortDeterminismTest {

    /**
     * Minimal {@link OfferApplier} stub carrying a fixed tie-break key.
     * <p>
     * Only the sort is under test, so {@link #apply(BasketEvaluation)} and
     * {@link #getConfiguration()} return neutral values and the tie-break key is supplied by
     * the test.
     */
    private static final class StubApplier extends OfferApplier {

        /**
         * The tie-break key this stub reports.
         */
        private final String key;

        /**
         * Builds a stub with the given score and tie-break key.
         *
         * @param score The efficiency score to preset.
         * @param key   The tie-break key to report.
         */
        private StubApplier(double score, String key) {
            this.key = key;
            setEfficiencyScore(score);
        }

        /**
         * Returns no application: the stub exists only to be sorted.
         *
         * @param basketEvaluation The evaluation context (unused).
         * @return An empty list.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            return List.of();
        }

        /**
         * Returns no configuration: the stub is not born from a configuration row.
         *
         * @return Always null.
         */
        @Override
        public Offer getConfiguration() {
            return null;
        }

        /**
         * Returns the fixed tie-break key supplied at construction.
         *
         * @return The tie-break key.
         */
        @Override
        public String getTieBreakKey() {
            return key;
        }
    }

    /**
     * Higher efficiency scores sort first, regardless of the tie-break key.
     */
    @Test
    void sortsByScoreDescendingFirst() {
        OfferApplier low = new StubApplier(0.10, "aaa");
        OfferApplier high = new StubApplier(0.90, "zzz");
        List<OfferApplier> appliers = new ArrayList<>(List.of(low, high));
        new ValuationEngine.OfferApplierEvaluator().sort(appliers, null);
        assertEquals(high, appliers.get(0));
        assertEquals(low, appliers.get(1));
    }

    /**
     * Equal scores are ordered by ascending tie-break key, whatever the input order.
     */
    @Test
    void breaksScoreTiesByAscendingKey() {
        OfferApplier a = new StubApplier(0.50, "AAA");
        OfferApplier b = new StubApplier(0.50, "BBB");
        OfferApplier c = new StubApplier(0.50, "CCC");
        List<OfferApplier> appliers = new ArrayList<>(List.of(c, a, b));
        new ValuationEngine.OfferApplierEvaluator().sort(appliers, null);
        assertEquals("AAA", appliers.get(0).getTieBreakKey());
        assertEquals("BBB", appliers.get(1).getTieBreakKey());
        assertEquals("CCC", appliers.get(2).getTieBreakKey());
    }

    /**
     * The same equal-score inputs, given in a different order, sort to the same result — the
     * property the byte-identical acceptance test relies on.
     */
    @Test
    void sameInputsInAnyOrderProduceTheSameSort() {
        List<OfferApplier> first = new ArrayList<>(List.of(
                new StubApplier(0.50, "AAA"),
                new StubApplier(0.50, "BBB"),
                new StubApplier(0.50, "CCC")));
        List<OfferApplier> second = new ArrayList<>(List.of(
                new StubApplier(0.50, "CCC"),
                new StubApplier(0.50, "BBB"),
                new StubApplier(0.50, "AAA")));
        new ValuationEngine.OfferApplierEvaluator().sort(first, null);
        new ValuationEngine.OfferApplierEvaluator().sort(second, null);
        List<String> firstKeys = first.stream().map(OfferApplier::getTieBreakKey).toList();
        List<String> secondKeys = second.stream().map(OfferApplier::getTieBreakKey).toList();
        assertEquals(firstKeys, secondKeys);
        assertEquals(List.of("AAA", "BBB", "CCC"), firstKeys);
    }

    /**
     * The default tie-break key is the configuration code when the applier is born from a
     * configuration row, and the empty string otherwise.
     */
    @Test
    void defaultKeyIsConfigurationCodeOrEmpty() {
        OfferApplier configured = new OfferApplier() {
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return List.of();
            }

            @Override
            public Offer getConfiguration() {
                Offer offer = new Offer();
                offer.code = "OFFER_42";
                return offer;
            }
        };
        OfferApplier unconfigured = new OfferApplier() {
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return List.of();
            }

            @Override
            public Offer getConfiguration() {
                return null;
            }
        };
        assertEquals("OFFER_42", configured.getTieBreakKey());
        assertEquals("", unconfigured.getTieBreakKey());
    }
}
