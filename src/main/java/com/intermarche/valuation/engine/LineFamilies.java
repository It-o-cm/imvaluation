package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Completes every valued line of a serialized evaluation with the product's label and family
 * (digital sheet DG-01-01-01, spec §3): {@code label}, {@code familyCode} and
 * {@code familyLabel}.
 * <p>
 * The family is the article's family of attachment in the engine's nomenclature: the family
 * that lists the product directly (the first by code when several do). An article unknown to the
 * referential, or in no family, gets {@code null} fields, never an error. The families are read
 * once per EAN of the answer, after the valuation, so the engine's arithmetic never pays for
 * them. Nothing is stored.
 */
public final class LineFamilies {

    /**
     * The query listing the families that hold a product directly, by code.
     */
    static final String DIRECT_FAMILIES = "select pf from ProductFamily pf join pf.products p where p.id = ?1 order by pf.code";

    /**
     * Not instantiable.
     */
    private LineFamilies() {
    }

    /**
     * Completes every valued line of {@code offers[].items[]} with {@code label},
     * {@code familyCode} and {@code familyLabel}, {@code null} when unknown.
     *
     * @param evaluation the serialized evaluation, completed in place.
     */
    public static void describe(JsonNode evaluation) {
        Map<String, ProductFamily> byEan = new HashMap<>();
        for (JsonNode offer : evaluation.path("offers")) {
            for (JsonNode node : offer.path("items")) {
                ObjectNode item = (ObjectNode) node;
                String ean = EvaluationReader.text(item, "produceEan");
                ProductFamily family = null;
                if (ean != null) {
                    if (!byEan.containsKey(ean)) {
                        byEan.put(ean, familyOf(ean));
                    }
                    family = byEan.get(ean);
                }
                if (!item.has("label")) {
                    item.putNull("label");
                }
                item.put("familyCode", family == null ? null : family.code);
                item.put("familyLabel", family == null ? null : family.description);
            }
        }
    }

    /**
     * Resolves the family of attachment of a product.
     *
     * @param ean the product EAN.
     * @return the first family holding the product directly, by code, or null when the product is
     *         unknown or in no family.
     */
    private static ProductFamily familyOf(String ean) {
        Product product = Product.findByEan(ean);
        if (product == null) {
            return null;
        }
        List<ProductFamily> direct = ProductFamily.list(DIRECT_FAMILIES, product.id);
        return direct.isEmpty() ? null : direct.get(0);
    }
}
