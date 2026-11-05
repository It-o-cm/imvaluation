package com.intermarche.valuation.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link CsrfFilter}, complementing the HTTP-level {@link CsrfFilterTest}:
 * every exemption (the stateless {@code /returns} valuation included), every
 * place the token is read from, and every outcome of the double-submit comparison, against a
 * mocked {@link ContainerRequestContext}.
 */
class CsrfFilterUnitTest {

    /**
     * Builds an enabled filter.
     *
     * @return the filter.
     */
    private static CsrfFilter filter() {
        CsrfFilter filter = new CsrfFilter();
        filter.enabled = true;
        return filter;
    }

    /**
     * Builds a mocked request.
     *
     * @param method    the HTTP method.
     * @param path      the request path, possibly null.
     * @param cookie    the CSRF cookie value, or null for none.
     * @param header    the CSRF header value, or null.
     * @param query     the CSRF query parameter value, or null.
     * @param mediaType the body media type, or null.
     * @param body      the body stream, or null.
     * @return the mocked request.
     */
    private static ContainerRequestContext request(String method, String path, String cookie, String header,
                                                   String query, MediaType mediaType, InputStream body) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getMethod()).thenReturn(method);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        if (query != null) {
            parameters.add(CsrfTokenManager.FIELD_NAME, query);
        }
        when(uriInfo.getQueryParameters()).thenReturn(parameters);
        Map<String, Cookie> cookies = new HashMap<>();
        if (cookie != null) {
            cookies.put(CsrfTokenManager.COOKIE_NAME, new Cookie(CsrfTokenManager.COOKIE_NAME, cookie));
        }
        when(context.getCookies()).thenReturn(cookies);
        when(context.getHeaderString(CsrfTokenManager.HEADER_NAME)).thenReturn(header);
        when(context.getMediaType()).thenReturn(mediaType);
        when(context.getEntityStream()).thenReturn(body);
        return context;
    }

    /**
     * Builds a url-encoded form body.
     *
     * @param form the form text.
     * @return the body stream.
     */
    private static InputStream form(String form) {
        return new ByteArrayInputStream(form.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Asserts the request passed the filter.
     *
     * @param context the request.
     */
    private static void assertPassed(ContainerRequestContext context) {
        verify(context, never()).abortWith(any());
    }

    /**
     * Asserts the request was refused with a 403.
     *
     * @param context the request.
     */
    private static void assertRefused(ContainerRequestContext context) {
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertEquals(403, response.getValue().getStatus());
        assertEquals("Invalid or missing CSRF token.", response.getValue().getEntity());
    }

    /**
     * A disabled filter and a safe method let everything through without reading the request.
     */
    @Test
    void disabledOrSafeMethod_passes() {
        CsrfFilter disabled = new CsrfFilter();
        ContainerRequestContext post = request("POST", "/ui/x", null, null, null, null, null);
        disabled.filter(post);
        assertPassed(post);
        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            ContainerRequestContext safe = request(method, "/ui/x", null, null, null, null, null);
            filter().filter(safe);
            assertPassed(safe);
        }
    }

    /**
     * The login post and the Basic-auth machine APIs are exempt, the return valuation included,
     * with or without a leading slash.
     */
    @Test
    void machineApis_areExempt() {
        for (String path : List.of("/j_security_check", "/graphql", "/valuation", "/prices/import", "/consumptions",
                "/consumptions/export", "/returns/valuation", "returns/valuation")) {
            ContainerRequestContext context = request("POST", path, null, null, null, null, null);
            filter().filter(context);
            assertPassed(context);
        }
    }

    /**
     * A state-changing UI request without cookie, without token, or with a mismatched token of
     * the same or another length is refused; a null path is read as the empty path.
     */
    @Test
    void uiRequest_withoutValidToken_isRefused() {
        ContainerRequestContext noCookie = request("POST", "/ui/x", null, "T", null, null, null);
        filter().filter(noCookie);
        assertRefused(noCookie);
        ContainerRequestContext noToken = request("POST", null, "T", null, null, null, null);
        filter().filter(noToken);
        assertRefused(noToken);
        ContainerRequestContext otherLength = request("POST", "/ui/x", "TOKEN", "TOK", null, null, null);
        filter().filter(otherLength);
        assertRefused(otherLength);
        ContainerRequestContext sameLength = request("POST", "/ui/x", "TOKEN", "TOKEX", null, null, null);
        filter().filter(sameLength);
        assertRefused(sameLength);
        ContainerRequestContext returnsPrefix = request("POST", "/returns", "T", null, null, null, null);
        filter().filter(returnsPrefix);
        assertRefused(returnsPrefix);
    }

    /**
     * A matching token is accepted from the header, then the query string, then the form body,
     * blank values falling through to the next place.
     */
    @Test
    void matchingToken_isAcceptedFromEveryPlace() {
        ContainerRequestContext header = request("POST", "/ui/x", "T", "T", null, null, null);
        filter().filter(header);
        assertPassed(header);
        ContainerRequestContext query = request("POST", "ui/x", "T", " ", "T", null, null);
        filter().filter(query);
        assertPassed(query);
        ContainerRequestContext body = request("POST", "/ui/x", "T", null, " ",
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, form("flag&name=a&csrf-token=T"));
        filter().filter(body);
        assertPassed(body);
        verify(body).setEntityStream(any(InputStream.class));
    }

    /**
     * A form body without the field, with a blank field, a body that is not a form, a request
     * without media type and an unreadable body all yield no token.
     *
     * @throws IOException never; declared for the failing stream stub.
     */
    @Test
    void formBody_withoutUsableToken_isRefused() throws IOException {
        ContainerRequestContext missing = request("POST", "/ui/x", "T", null, null,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, form("name=a"));
        filter().filter(missing);
        assertRefused(missing);
        ContainerRequestContext blank = request("POST", "/ui/x", "T", null, null,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, form("csrf-token=+"));
        filter().filter(blank);
        assertRefused(blank);
        ContainerRequestContext json = request("POST", "/ui/x", "T", null, null,
                MediaType.APPLICATION_JSON_TYPE, form("csrf-token=T"));
        filter().filter(json);
        assertRefused(json);
        ContainerRequestContext noType = request("POST", "/ui/x", "T", null, null, null, null);
        filter().filter(noType);
        assertRefused(noType);
        InputStream failing = mock(InputStream.class);
        when(failing.readAllBytes()).thenThrow(new IOException("closed"));
        ContainerRequestContext unreadable = request("POST", "/ui/x", "T", null, null,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, failing);
        filter().filter(unreadable);
        assertRefused(unreadable);
    }
}
