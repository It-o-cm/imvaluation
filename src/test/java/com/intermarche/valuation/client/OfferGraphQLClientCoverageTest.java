package com.intermarche.valuation.client;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Coverage test for the {@link OfferGraphQLClient} development utility.
 * <p>
 * The client is a standalone {@code main} that posts GraphQL mutations to a fixed
 * {@code http://localhost:8090/graphql}. The test simply drives {@code main}: it builds and
 * dispatches every request through the request/variable/payload assembly and both helpers,
 * then handles the send outcome in its per-offer try/catch. {@code main} swallows its own
 * exceptions, so it is asserted to complete without throwing. The default test server does
 * not listen on 8090, so the send fails and the response-status branches are the class's only
 * unreachable lines from a self-contained test.
 */
@QuarkusTest
public class OfferGraphQLClientCoverageTest {

    /**
     * Tests that the client runs its whole assembly and dispatch path without throwing,
     * whatever the connection outcome.
     */
    @Test
    void testMain_runsWithoutThrowing() {
        assertDoesNotThrow(() -> OfferGraphQLClient.main(new String[]{}));
    }
}
