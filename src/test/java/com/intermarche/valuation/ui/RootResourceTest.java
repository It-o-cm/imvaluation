package com.intermarche.valuation.ui;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link RootResource}.
 * <p>
 * The resource has a single branchless method: {@code root()} always returns a 303 See
 * Other redirect pointing at the offer list. The test asserts the status code and the
 * exact {@code Location} target down to the character.
 */
class RootResourceTest {

    /**
     * Verifies that {@code root()} returns a 303 See Other response whose {@code Location}
     * header points at {@code /ui/offers}.
     */
    @Test
    void rootRedirectsToOfferList() {
        RootResource resource = new RootResource();
        Response response = resource.root();
        assertNotNull(response);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(URI.create("/ui/offers"), response.getLocation());
    }
}
