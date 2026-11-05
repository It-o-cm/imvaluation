package com.intermarche.valuation;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Architecture test of the digital sheet (spec §1, normative case 10): imvaluation keeps no
 * basket, so no Panache entity is added. The compiled classes of the module are scanned for
 * {@link Entity} and compared with the persistence model as it stood before the lot; a new
 * entity — a basket, a basket event, a closing snapshot, a return — fails the build.
 */
class StatelessArchitectureTest {

    /**
     * The entities of the module before the digital sheet, the only persistence it may have.
     */
    private static final List<String> ENTITIES = List.of(
            "AppUser", "BudgetGroup", "ConsumptionCounter", "ConsumptionRecord", "EgalimCeiling", "Offer",
            "PasswordResetToken", "Price", "Product", "ProductCategoryStorage", "ProductFamily", "Store", "StoreGroup",
            "ValuationTrace", "ValuationTraceConfig", "VatRate");

    /**
     * Lists the simple names of the compiled classes annotated with {@link Entity}.
     *
     * @return the sorted entity names.
     * @throws IOException        when the class directory cannot be walked.
     * @throws URISyntaxException when the class directory cannot be located.
     */
    private static TreeSet<String> entities() throws IOException, URISyntaxException {
        URL root = StatelessArchitectureTest.class.getClassLoader().getResource("com/intermarche/valuation/domain/Product.class");
        assertNotNull(root, "the compiled domain classes are on the classpath");
        Path classes = Paths.get(root.toURI()).getParent().getParent().getParent().getParent().getParent();
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classes.resolve("com/intermarche/valuation"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = classes.relativize(file).toString().replace('/', '.').replace(".class", "");
                try {
                    Class<?> type = Class.forName(name, false, StatelessArchitectureTest.class.getClassLoader());
                    if (type.isAnnotationPresent(Entity.class)) {
                        names.add(type.getSimpleName());
                    }
                } catch (ClassNotFoundException | LinkageError e) {
                    throw new IllegalStateException("Cannot load " + name, e);
                }
            }
        }
        return names;
    }

    /**
     * The module's entities are exactly those it had before the lot: no basket is persisted.
     *
     * @throws Exception when the classes cannot be scanned.
     */
    @Test
    void noNewEntity() throws Exception {
        assertEquals(new TreeSet<>(ENTITIES), entities());
    }
}
