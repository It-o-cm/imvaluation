package com.intermarche.valuation;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.graphql.AlreadyExistsException;
import com.intermarche.valuation.ui.LookupResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for a handful of otherwise-unreached constructors, exercised inside a
 * {@code @QuarkusTest} so quarkus-jacoco attributes them: the utility-class guard
 * constructor, the two-argument business exception constructor, and the JSON default
 * constructor of the lookup suggestion.
 */
@QuarkusTest
public class MiscResidualCoverageTest {

    /**
     * Tests that the {@link DateTimeProvider} utility constructor refuses instantiation.
     *
     * @throws Exception if the reflective lookup fails.
     */
    @Test
    void testDateTimeProviderConstructorRefusesInstantiation() throws Exception {
        Constructor<DateTimeProvider> constructor = DateTimeProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(thrown.getCause() instanceof UnsupportedOperationException);
    }

    /**
     * Tests the cause-carrying constructor of {@link AlreadyExistsException}.
     */
    @Test
    void testAlreadyExistsExceptionWithCause() {
        Throwable cause = new IllegalStateException("root");
        AlreadyExistsException exception = new AlreadyExistsException("duplicate", cause);
        assertEquals("duplicate", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    /**
     * Tests the JSON default constructor of {@link LookupResource.Suggestion}.
     */
    @Test
    void testSuggestionDefaultConstructor() {
        LookupResource.Suggestion suggestion = new LookupResource.Suggestion();
        assertNull(suggestion.label);
        assertNull(suggestion.detail);
    }
}
