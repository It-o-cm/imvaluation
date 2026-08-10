package com.intermarche.valuation.client;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OfferGraphQLClient}.
 * <p>
 * The class is a command-line client whose only collaborator is the JDK
 * {@link HttpClient}. Tests mock the static {@code HttpClient.newBuilder()}
 * factory and the returned client so that {@code main} can be exercised
 * without any network access, covering the HTTP-status branch and the
 * exception-handling branch. The private {@code jsonStringValue} and
 * {@code getBasicAuthHeader} helpers are reached by reflection to cover both
 * arms of the null guard.
 */
public class OfferGraphQLClientTest {

    /**
     * Builds a mocked {@link HttpClient} whose builder chain returns the
     * supplied client, registered on the static {@code HttpClient.newBuilder()}
     * factory within the given static mock.
     *
     * @param mockedHttpClient The active static mock for {@link HttpClient}.
     * @param client           The client instance the builder must return.
     */
    private void stubBuilderChain(MockedStatic<HttpClient> mockedHttpClient, HttpClient client) {
        HttpClient.Builder builder = mock(HttpClient.Builder.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        mockedHttpClient.when(HttpClient::newBuilder).thenReturn(builder);
    }

    /**
     * Verifies that a 200 response drives the success branch, printing a
     * "Success" line to standard output for each offer and never touching
     * standard error.
     *
     * @throws Exception If the mocked send declares a checked exception.
     */
    @Test
    void testMainPrintsSuccessOnHttp200() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuffer));
        System.setErr(new PrintStream(errBuffer));
        try (MockedStatic<HttpClient> mockedHttpClient = mockStatic(HttpClient.class)) {
            HttpClient client = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"data\":\"ok\"}");
            doReturn(response).when(client).send(any(HttpRequest.class), any());
            stubBuilderChain(mockedHttpClient, client);
            OfferGraphQLClient.main(new String[]{});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        String out = outBuffer.toString();
        assertTrue(out.contains("Success for PROMO_XMAS: {\"data\":\"ok\"}"));
        assertTrue(out.contains("Success for PROMO_FLASH: {\"data\":\"ok\"}"));
        assertTrue(out.contains("Success for PROMO_VIP: {\"data\":\"ok\"}"));
        assertEquals("", errBuffer.toString());
    }

    /**
     * Verifies that a non-200 response drives the error branch, printing an
     * "Error" line with the status code and body to standard error.
     *
     * @throws Exception If the mocked send declares a checked exception.
     */
    @Test
    void testMainPrintsErrorOnNon200() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuffer));
        System.setErr(new PrintStream(errBuffer));
        try (MockedStatic<HttpClient> mockedHttpClient = mockStatic(HttpClient.class)) {
            HttpClient client = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(400);
            when(response.body()).thenReturn("bad request");
            doReturn(response).when(client).send(any(HttpRequest.class), any());
            stubBuilderChain(mockedHttpClient, client);
            OfferGraphQLClient.main(new String[]{});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        String err = errBuffer.toString();
        assertTrue(err.contains("Error for PROMO_XMAS (400): bad request"));
        assertTrue(err.contains("Error for PROMO_FLASH (400): bad request"));
        assertTrue(err.contains("Error for PROMO_VIP (400): bad request"));
        assertEquals("", outBuffer.toString());
    }

    /**
     * Verifies that an exception thrown by the HTTP send drives the catch
     * branch, printing an "Exception" line to standard error.
     *
     * @throws Exception If the mocked send declares a checked exception.
     */
    @Test
    void testMainHandlesSendException() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuffer));
        System.setErr(new PrintStream(errBuffer));
        try (MockedStatic<HttpClient> mockedHttpClient = mockStatic(HttpClient.class)) {
            HttpClient client = mock(HttpClient.class);
            when(client.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));
            stubBuilderChain(mockedHttpClient, client);
            OfferGraphQLClient.main(new String[]{});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        String err = errBuffer.toString();
        assertTrue(err.contains("Exception for PROMO_XMAS: connection refused"));
        assertTrue(err.contains("Exception for PROMO_FLASH: connection refused"));
        assertTrue(err.contains("Exception for PROMO_VIP: connection refused"));
        assertEquals("", outBuffer.toString());
    }

    /**
     * Verifies that {@code jsonStringValue} returns the literal {@code null}
     * token for a null input, covering the null arm of the guard.
     *
     * @throws Exception If reflection fails to invoke the private helper.
     */
    @Test
    void testJsonStringValueWithNull() throws Exception {
        Method method = OfferGraphQLClient.class.getDeclaredMethod("jsonStringValue", String.class);
        method.setAccessible(true);
        Object result = method.invoke(null, (Object) null);
        assertEquals("null", result);
    }

    /**
     * Verifies that {@code jsonStringValue} escapes backslashes and double
     * quotes and wraps the value in quotes, covering the non-null arm.
     *
     * @throws Exception If reflection fails to invoke the private helper.
     */
    @Test
    void testJsonStringValueEscapesSpecialCharacters() throws Exception {
        Method method = OfferGraphQLClient.class.getDeclaredMethod("jsonStringValue", String.class);
        method.setAccessible(true);
        Object result = method.invoke(null, "a\\b\"c");
        assertEquals("\"a\\\\b\\\"c\"", result);
    }

    /**
     * Verifies that {@code getBasicAuthHeader} produces the Base64-encoded
     * "admin:admin" credentials prefixed with "Basic ".
     *
     * @throws Exception If reflection fails to invoke the private helper.
     */
    @Test
    void testGetBasicAuthHeader() throws Exception {
        Method method = OfferGraphQLClient.class.getDeclaredMethod("getBasicAuthHeader");
        method.setAccessible(true);
        Object result = method.invoke(null);
        assertEquals("Basic YWRtaW46YWRtaW4=", result);
    }
}
