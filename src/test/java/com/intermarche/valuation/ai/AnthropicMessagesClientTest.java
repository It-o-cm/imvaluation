package com.intermarche.valuation.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnthropicMessagesClient}, run against a local
 * {@code com.sun.net.httpserver} stub so the real wire format is asserted — the
 * {@code x-api-key} and {@code anthropic-version} headers, the model, the max token
 * budget, the system and user prompts — without any network access.
 * <p>
 * Branches covered, arm by arm:
 * <ul>
 *   <li>{@code isConfigured}: the blank and present keys.</li>
 *   <li>{@code complete}: the unconfigured guard, the happy path with the request shape
 *       pinned and the text blocks assembled (non-text blocks skipped), the non-200 arm,
 *       the text-less reply arm, and the unreachable endpoint arm.</li>
 * </ul>
 */
class AnthropicMessagesClientTest {

    /**
     * The stub server, listening on an ephemeral port.
     */
    private HttpServer server;

    /**
     * The last request headers received by the stub.
     */
    private Map<String, String> lastHeaders;

    /**
     * The last request body received by the stub.
     */
    private String lastBody;

    /**
     * The status the stub replies with.
     */
    private int replyStatus = 200;

    /**
     * The body the stub replies with.
     */
    private String replyBody =
            "{\"content\":[{\"type\":\"text\",\"text\":\"Hello \"},"
                    + "{\"type\":\"tool_use\"},{\"type\":\"text\",\"text\":\"world\"}]}";

    /**
     * Stops the stub after each test.
     *
     * @throws IOException when the server cannot be stopped.
     */
    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts the stub server and returns a client pointed at it.
     *
     * @return The configured client.
     * @throws IOException when the port cannot be bound.
     */
    private AnthropicMessagesClient clientAgainstStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastHeaders = new HashMap<>();
                for (Map.Entry<String, java.util.List<String>> entry
                        : exchange.getRequestHeaders().entrySet()) {
                    lastHeaders.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
                }
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] payload = replyBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(replyStatus, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        server.start();
        AnthropicMessagesClient client = new AnthropicMessagesClient();
        client.baseUrl = "http://localhost:" + server.getAddress().getPort();
        client.apiKey = java.util.Optional.of("test-key");
        client.model = "test-model";
        client.maxTokens = 512;
        client.timeoutSeconds = 10;
        client.buildClient();
        return client;
    }

    /**
     * An absent or blank key leaves the client unconfigured.
     */
    @Test
    void aBlankKeyLeavesTheClientUnconfigured() {
        AnthropicMessagesClient client = new AnthropicMessagesClient();
        client.apiKey = java.util.Optional.empty();
        assertFalse(client.isConfigured());
        client.apiKey = java.util.Optional.of("   ");
        assertFalse(client.isConfigured());
        client.apiKey = java.util.Optional.of("sk-ant");
        assertTrue(client.isConfigured());
    }

    /**
     * The happy path pins the wire format and assembles the text blocks of the reply,
     * skipping the non-text ones.
     *
     * @throws IOException when the stub cannot be started.
     */
    @Test
    void completeSendsTheWireFormatAndAssemblesTheText()
            throws IOException {
        AnthropicMessagesClient client = clientAgainstStub();
        String text = client.complete("system prompt", "user prompt");
        assertEquals("Hello world", text);
        assertEquals("test-key", lastHeaders.get("x-api-key"));
        assertEquals(AnthropicMessagesClient.ANTHROPIC_VERSION, lastHeaders.get("anthropic-version"));
        assertEquals("application/json", lastHeaders.get("content-type"));
        assertTrue(lastBody.contains("\"model\":\"test-model\""));
        assertTrue(lastBody.contains("\"max_tokens\":512"));
        assertTrue(lastBody.contains("\"system\":\"system prompt\""));
        assertTrue(lastBody.contains("\"role\":\"user\""));
        assertTrue(lastBody.contains("\"content\":\"user prompt\""));
    }

    /**
     * A non-200 reply surfaces as an IllegalStateException naming the status.
     *
     * @throws IOException when the stub cannot be started.
     */
    @Test
    void completeRefusesANonOkReply() throws IOException {
        AnthropicMessagesClient client = clientAgainstStub();
        replyStatus = 500;
        replyBody = "{\"error\":\"boom\"}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.complete("s", "u"));
        assertTrue(error.getMessage().contains("HTTP 500"));
    }

    /**
     * A reply carrying no text block surfaces as an IllegalStateException.
     *
     * @throws IOException when the stub cannot be started.
     */
    @Test
    void completeRefusesATextLessReply() throws IOException {
        AnthropicMessagesClient client = clientAgainstStub();
        replyBody = "{\"content\":[]}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.complete("s", "u"));
        assertTrue(error.getMessage().contains("no text"));
    }

    /**
     * A reply that is not JSON surfaces as an IllegalStateException.
     *
     * @throws IOException when the stub cannot be started.
     */
    @Test
    void completeRefusesANonJsonReply() throws IOException {
        AnthropicMessagesClient client = clientAgainstStub();
        replyBody = "<html>not json</html>";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.complete("s", "u"));
        assertTrue(error.getMessage().contains("not valid JSON"));
    }

    /**
     * An unconfigured client refuses before any call goes out.
     */
    @Test
    void completeRefusesAnUnconfiguredClient() {
        AnthropicMessagesClient client = new AnthropicMessagesClient();
        client.apiKey = java.util.Optional.empty();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.complete("s", "u"));
        assertTrue(error.getMessage().contains("No Anthropic API key"));
    }

    /**
     * An unreachable endpoint surfaces as an IllegalStateException, not a raw IO error.
     */
    @Test
    void completeReportsAnUnreachableEndpoint() {
        AnthropicMessagesClient client = new AnthropicMessagesClient();
        client.baseUrl = "http://localhost:1";
        client.apiKey = java.util.Optional.of("test-key");
        client.timeoutSeconds = 2;
        client.buildClient();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.complete("s", "u"));
        assertTrue(error.getMessage().contains("could not be reached"));
    }
}
