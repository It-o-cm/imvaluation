package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * Plain unit tests for {@link LineFamilies} (spec §3, normative case 4): the product finder and
 * the family query are mocked statically, the completion is asserted on a hand-written serialized
 * evaluation.
 */
class LineFamiliesTest {

    /**
     * Builds a family.
     *
     * @param code        the code.
     * @param description the label.
     * @return the family.
     */
    private static ProductFamily family(String code, String description) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = description;
        return family;
    }

    /**
     * Covers the private constructor of the utility class.
     *
     * @throws Exception when reflection fails.
     */
    @Test
    void privateConstructor_isReachable() throws Exception {
        Constructor<LineFamilies> constructor = LineFamilies.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertTrue(constructor.newInstance() instanceof LineFamilies);
    }

    /**
     * A known article gets its family of attachment (the first direct family by code) and keeps
     * its label; an article in no family, an unknown article and a line without EAN get null
     * fields, the label included when the engine gave none; each EAN is resolved once.
     */
    @Test
    void describe_completesEveryLine() {
        JsonNode evaluation = EvaluationReader.read(("{'offers':[{'items':[{'lineId':'1','produceEan':'E1','label':'Pommes'},"
                + "{'lineId':'2','produceEan':'E2','label':'Pain'}]},{'items':[{'lineId':'3','produceEan':'E1','label':'Pommes'},"
                + "{'lineId':'4','produceEan':'E9'},{'lineId':'5'}]},{'type':'Delivery: D'}]}").replace('\'', '"'),
                JsonNode.class);
        Product apples = new Product();
        apples.id = 1L;
        Product bread = new Product();
        bread.id = 2L;
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            products.when(() -> Product.findByEan("E1")).thenReturn(apples);
            products.when(() -> Product.findByEan("E2")).thenReturn(bread);
            products.when(() -> Product.findByEan("E9")).thenReturn(null);
            panache.when(() -> PanacheEntityBase.list(LineFamilies.DIRECT_FAMILIES, 1L))
                    .thenReturn(List.of(family("POMMES", "Pommes à croquer"), family("VRAC", "Vrac")));
            panache.when(() -> PanacheEntityBase.list(LineFamilies.DIRECT_FAMILIES, 2L)).thenReturn(List.of());
            LineFamilies.describe(evaluation);
            products.verify(() -> Product.findByEan("E1"), times(1));
        }
        JsonNode first = evaluation.path("offers").get(0).path("items");
        JsonNode second = evaluation.path("offers").get(1).path("items");
        assertEquals("{\"lineId\":\"1\",\"produceEan\":\"E1\",\"label\":\"Pommes\",\"familyCode\":\"POMMES\","
                + "\"familyLabel\":\"Pommes à croquer\"}", first.get(0).toString());
        assertEquals("{\"lineId\":\"2\",\"produceEan\":\"E2\",\"label\":\"Pain\",\"familyCode\":null,\"familyLabel\":null}",
                first.get(1).toString());
        assertEquals("POMMES", second.get(0).path("familyCode").asText());
        assertEquals("{\"lineId\":\"4\",\"produceEan\":\"E9\",\"label\":null,\"familyCode\":null,\"familyLabel\":null}",
                second.get(1).toString());
        assertEquals("{\"lineId\":\"5\",\"label\":null,\"familyCode\":null,\"familyLabel\":null}", second.get(2).toString());
    }
}
