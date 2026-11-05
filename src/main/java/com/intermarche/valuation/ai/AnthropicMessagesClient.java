package com.intermarche.valuation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal client for the Anthropic Messages API, used by the natural-language offer
 * draft (spec: describe an offer in plain language, get a draft specification back).
 * <p>
 * The call is a plain {@code POST /v1/messages} with the {@code x-api-key} and
 * {@code anthropic-version} headers; no SDK is pulled in, so the application keeps its
 * current dependency set and the client stays trivially testable against a local
 * HTTP stub. Only the text blocks of the reply are returned — the caller enforces its
 * own response contract on them.
 * <p>
 * The API key is deliberately not stored in the repository: it comes from the
 * {@code ai.anthropic.api-key} property, usually fed by the {@code ANTHROPIC_API_KEY}
 * environment variable. The feature is reported unavailable while the key is blank.
 */
@ApplicationScoped
public class AnthropicMessagesClient {

    /**
     * Version of the Messages API this client speaks.
     */
    static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final Logger LOGGER = Logger.getLogger(AnthropicMessagesClient.class);

    /**
     * Base URL of the Anthropic API, overridable to point at a stub.
     */
    @ConfigProperty(name = "ai.anthropic.base-url", defaultValue = "https://api.anthropic.com")
    String baseUrl;

    /**
     * The API key, empty until the environment provides one. An {@link Optional} because
     * {@code ${ANTHROPIC_API_KEY:}} resolves to the empty string, which SmallRye converts
     * to null and a raw String field would then reject at startup.
     */
    @ConfigProperty(name = "ai.anthropic.api-key")
    java.util.Optional<String> apiKey;

    /**
     * The model producing the drafts.
     */
    @ConfigProperty(name = "ai.anthropic.model", defaultValue = "claude-sonnet-4-5")
    String model;

    /**
     * Upper bound on the reply tokens, generous enough for a full specification.
     */
    @ConfigProperty(name = "ai.anthropic.max-tokens", defaultValue = "4096")
    int maxTokens;

    /**
     * Budget for one call, covering the round trip and the generation.
     */
    @ConfigProperty(name = "ai.anthropic.timeout-seconds", defaultValue = "60")
    int timeoutSeconds;

    /**
     * The HTTP client, built once the configuration is injected.
     */
    private HttpClient httpClient;

    /**
     * Mapper used to build the request and read the reply.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Builds the HTTP client once the configuration is available.
     */
    @PostConstruct
    void buildClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Indicates whether the API key is present, i.e. whether the feature can call out.
     *
     * @return {@code true} when a non-blank key is configured.
     */
    public boolean isConfigured() {
        return apiKey.isPresent() && !apiKey.get().isBlank();
    }

    /**
     * Sends one completion request and returns the text of the reply.
     *
     * @param system The system prompt framing the reply.
     * @param user   The user prompt, verbatim.
     * @return The concatenated text blocks of the assistant reply.
     * @throws IllegalStateException when the key is missing, the call fails, or the
     *                                reply carries no text.
     */
    public String complete(String system, String user) {
        if (!isConfigured()) {
            throw new IllegalStateException("No Anthropic API key configured (ai.anthropic.api-key).");
        }
        String key = apiKey.get();
        String body = buildRequestBody(system, user);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("x-api-key", key)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("The Anthropic API could not be reached: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            LOGGER.warnf("Anthropic API returned HTTP %d: %s", response.statusCode(), abbreviate(response.body()));
            throw new IllegalStateException("The Anthropic API returned HTTP " + response.statusCode() + ".");
        }
        return extractText(response.body());
    }

    /**
     * Builds the JSON body of a Messages API call.
     *
     * @param system The system prompt.
     * @param user   The user prompt.
     * @return The serialized request body.
     */
    private String buildRequestBody(String system, String user) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("system", system);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", user);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Serializing a freshly built tree of simple values cannot fail.
            throw new IllegalStateException("Could not serialise the request body.", e);
        }
    }

    /**
     * Reads the text blocks out of a Messages API reply.
     *
     * @param responseBody The raw reply body.
     * @return The concatenated text of every {@code text} block.
     * @throws IllegalStateException when the reply is unreadable or carries no text.
     */
    private String extractText(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("The Anthropic API reply is not valid JSON.", e);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if (text.length() == 0) {
            throw new IllegalStateException("The Anthropic API reply carries no text.");
        }
        return text.toString();
    }

    /**
     * Keeps a log line readable when a reply body is unexpectedly large.
     *
     * @param value The raw body, may be null.
     * @return At most the first 500 characters, ellipsised.
     */
    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }
}
