package com.intermarche.valuation.ai;

import com.intermarche.valuation.ui.OfferSchemaRegistry;
import org.junit.jupiter.api.Test;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OfferDraftService}.
 * <p>
 * The Anthropic client and the schema registry are Mockito mocks on the package-private
 * fields; the localized notices resolve through a real {@link com.intermarche.valuation.i18n.Messages}
 * (French default, no CDI needed), so every error status is also exercised against the
 * actual bundle keys. The validation path runs for real: the draft is checked through
 * {@code processSpecification}, the very same funnel the engine uses.
 * <p>
 * Branches covered, arm by arm:
 * <ul>
 *   <li>{@code draft}: the null guard, the blank guard, the length guard, the
 *       configuration guard, the call catch, and every {@code interpret} outcome.</li>
 *   <li>{@code interpret}: malformed JSON (with and without a fence), the unknown status,
 *       the {@code impossible} passthrough (with and without a suggestion), the invented
 *       type, the missing and non-object specification, the draft that fails the schema,
 *       the blank code collapse, and the successful draft.</li>
 *   <li>{@code isAvailable}: the delegation to the client.</li>
 * </ul>
 */
class OfferDraftServiceTest {

    /**
     * A minimal JSON Schema the mocked registry serves under one type; it survives the
     * shared-fragment injection because it carries a top-level {@code properties} object.
     */
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"percent\":{\"type\":\"number\"}},"
                    + "\"required\":[\"percent\"]}";

    /**
     * The type the schema is registered under.
     */
    private static final String TYPE = "PERCENT_OFFER";

    /**
     * The Panache base static, mocked for the whole class: every draft call builds the
     * system prompt, which reads the store catalogue through it.
     */
    private static MockedStatic<PanacheEntityBase> mockedBase;

    /**
     * Opens the Panache static with empty catalogues by default.
     */
    @org.junit.jupiter.api.BeforeEach
    void openPanache() {
        mockedBase = org.mockito.Mockito.mockStatic(
                io.quarkus.hibernate.orm.panache.PanacheEntityBase.class);
        java.util.List<String> queries = java.util.List.of(
                "from StoreGroup g order by g.code", "from Store s order by s.code");
        for (String query : queries) {
            mockedBase.when(() -> PanacheEntityBase.list(query)).thenReturn(java.util.List.of());
        }
    }

    /**
     * Closes the Panache static.
     */
    @org.junit.jupiter.api.AfterEach
    void closePanache() {
        mockedBase.close();
    }

    /**
     * Builds a service wired with the given client and a registry serving {@link #SCHEMA}.
     *
     * @param client The client mock.
     * @return The wired service.
     */
    private OfferDraftService service(AnthropicMessagesClient client) {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        Map<String, String> schemas = new LinkedHashMap<>();
        schemas.put(TYPE, SCHEMA);
        when(registry.getAllSchemas()).thenReturn(schemas);
        // Stubbed in this order so the specific type wins over the catch-all.
        when(registry.getSchema(anyString())).thenReturn(null);
        when(registry.getSchema(TYPE)).thenReturn(SCHEMA);
        OfferDraftService service = new OfferDraftService();
        service.client = client;
        service.schemaRegistry = registry;
        service.messages = new com.intermarche.valuation.i18n.Messages();
        return service;
    }

    /**
     * Builds a configured client mock: ready to call, its reply set by the caller.
     *
     * @param reply The text the client returns, may be null for "not stubbed".
     * @return The client mock.
     */
    private AnthropicMessagesClient configuredClient(String reply) {
        AnthropicMessagesClient client = mock(AnthropicMessagesClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(), anyString())).thenReturn(reply);
        return client;
    }

    /**
     * The null prompt takes the first guard and yields the empty-prompt error.
     */
    @Test
    void draftRejectsNullPrompt() {
        OfferDraftResult result = service(configuredClient(null)).draft(null);
        assertEquals(OfferDraftResult.ERROR, result.status);
        assertNotNull(result.message);
    }

    /**
     * A blank prompt takes the same guard.
     */
    @Test
    void draftRejectsBlankPrompt() {
        OfferDraftResult result = service(configuredClient(null)).draft("   ");
        assertEquals(OfferDraftResult.ERROR, result.status);
    }

    /**
     * An oversized prompt takes the length guard and never reaches the client.
     */
    @Test
    void draftRejectsOversizedPrompt() {
        AnthropicMessagesClient client = configuredClient(null);
        String prompt = "a".repeat(OfferDraftService.MAX_PROMPT_CHARS + 1);
        OfferDraftResult result = service(client).draft(prompt);
        assertEquals(OfferDraftResult.ERROR, result.status);
        verify(client, org.mockito.Mockito.never()).complete(anyString(), anyString());
    }

    /**
     * An unconfigured client takes the configuration guard and is never called.
     */
    @Test
    void draftRejectsUnconfiguredClient() {
        AnthropicMessagesClient client = mock(AnthropicMessagesClient.class);
        when(client.isConfigured()).thenReturn(false);
        OfferDraftResult result = service(client).draft("une remise");
        assertEquals(OfferDraftResult.ERROR, result.status);
        assertTrue(result.message.contains("ANTHROPIC_API_KEY"));
        verify(client, org.mockito.Mockito.never()).complete(anyString(), anyString());
    }

    /**
     * A failing call takes the catch arm and yields the call-failed error, with the
     * underlying reason substituted.
     */
    @Test
    void draftReportsAClientFailure() {
        AnthropicMessagesClient client = configuredClient(null);
        when(client.complete(anyString(), anyString()))
                .thenThrow(new IllegalStateException("HTTP 500."));
        OfferDraftResult result = service(client).draft("une remise");
        assertEquals(OfferDraftResult.ERROR, result.status);
        assertTrue(result.message.contains("HTTP 500."));
    }

    /**
     * A coherent ok reply becomes a validated ok result, with the system prompt carrying
     * the schema catalogue and the user prompt the description verbatim.
     */
    @Test
    void draftProducesAOfferFromACoherentReply() {
        AnthropicMessagesClient client = configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"code\":\"  PROMO_1  \","
                        + "\"specification\":{\"percent\":0.2},\"explanation\":\"vingt pour cent\"}");
        OfferDraftResult result = service(client).draft("  -20 pour cent  ");
        assertEquals(OfferDraftResult.OK, result.status);
        assertEquals(TYPE, result.type);
        assertEquals("PROMO_1", result.code);
        assertEquals("{\"percent\":0.2}", result.specification);
        assertEquals("vingt pour cent", result.explanation);
        assertNull(result.validFrom);
        assertNull(result.validTo);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).complete(system.capture(), user.capture());
        assertTrue(system.getValue().contains(TYPE));
        assertTrue(system.getValue().contains("\"percent\""));
        // The reply contract carries the validity window: the model can report the dates
        // the description expressed, and the prompt teaches the calendar/recurring split.
        assertTrue(system.getValue().contains("validFrom"));
        assertTrue(system.getValue().contains("validTo"));
        assertTrue(system.getValue().contains("n'est pas une période"));
        // The empty-required-array guard: a draft must never carry an empty mandatory array,
        // and a missing mandatory input must steer the model to another type or to an
        // impossibility naming what is missing.
        assertTrue(system.getValue().contains("ne renvoie jamais"));
        assertTrue(system.getValue().contains("un tableau obligatoire vide"));
        assertTrue(system.getValue().contains("ce qui manque"));
        // The dropped-targeting guard: a demand aimed at products without EANs must steer the
        // model to the free-form condition, never to a scope ITEMS specification with no
        // targeting at all.
        assertTrue(system.getValue().contains("plutôt que d'abandonner le ciblage"));
        assertTrue(system.getValue().contains("sans ciblage est refusée"));
        assertEquals("-20 pour cent", user.getValue());
    }

    /**
     * A validity window expressed as full ISO date-times passes through verbatim, and a
     * date-only bound is read as the start of that day — the shape the form's
     * datetime-local inputs expect.
     */
    @Test
    void draftCarriesTheValidityWindow() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"code\":\"PROMO_2\","
                        + "\"validFrom\":\"2026-12-12T09:30:00\",\"validTo\":\"2026-12-25\","
                        + "\"specification\":{\"percent\":0.2}}"))
                .draft("du 12 au 25 decembre");
        assertEquals(OfferDraftResult.OK, result.status);
        assertEquals("2026-12-12T09:30", result.validFrom);
        assertEquals("2026-12-25T00:00", result.validTo);
    }

    /**
     * A blank or null bound is treated as absent: the window stays open on that side.
     */
    @Test
    void draftTreatsABlankBoundAsAbsent() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"validFrom\":\"  \","
                        + "\"specification\":{\"percent\":0.2}}"))
                .draft("une remise");
        assertEquals(OfferDraftResult.OK, result.status);
        assertNull(result.validFrom);
        assertNull(result.validTo);
    }

    /**
     * An unreadable bound takes the bad-window arm: the draft is refused rather than
     * showing the user dates the form could not carry.
     */
    @Test
    void draftRefusesAnUnreadableWindow() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"validFrom\":\"apres Noel\","
                        + "\"specification\":{\"percent\":0.2}}"))
                .draft("une remise");
        assertEquals(OfferDraftResult.ERROR, result.status);
        assertNotNull(result.message);
        OfferDraftResult toEnd = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"validTo\":\"bientot\","
                        + "\"specification\":{\"percent\":0.2}}"))
                .draft("une remise");
        assertEquals(OfferDraftResult.ERROR, toEnd.status);
    }

    /**
     * A regional demand can resolve to a store group: the prompt carries the referential
     * catalogue — groups and stores — so the model can only suggest codes that exist, and
     * the suggested codes pass through to the form.
     */
    @Test
    void draftCarriesTheStoreCatalogueAndPassesTheTargetingThrough() {
        com.intermarche.valuation.domain.StoreGroup group =
                new com.intermarche.valuation.domain.StoreGroup();
        group.code = "IDF";
        group.name = "Region parisienne";
        com.intermarche.valuation.domain.Store store = new com.intermarche.valuation.domain.Store();
        store.code = "0101";
        store.name = "Paris Rivoli";
        mockedBase.when(() -> PanacheEntityBase.list("from StoreGroup g order by g.code"))
                .thenReturn(java.util.List.of(group));
        mockedBase.when(() -> PanacheEntityBase.list("from Store s order by s.code"))
                .thenReturn(java.util.List.of(store));
        AnthropicMessagesClient client = configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"storeGroupCodes\":[\" IDF \",\"\",\"05\"],"
                        + "\"storeCodes\":[\"0101\"],\"specification\":{\"percent\":0.2}}");
        OfferDraftService service = service(client);
        OfferDraftResult result = service.draft("pour tous les PDV de la region parisienne");
        assertEquals(OfferDraftResult.OK, result.status);
        assertEquals(java.util.List.of("IDF", "05"), result.storeGroupCodes);
        assertEquals(java.util.List.of("0101"), result.storeCodes);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(client).complete(system.capture(), anyString());
        assertTrue(system.getValue().contains("IDF — Region parisienne"),
                "The prompt must carry the store group catalogue.");
        assertTrue(system.getValue().contains("0101 — Paris Rivoli"),
                "The prompt must carry the store catalogue.");
        assertTrue(system.getValue().contains("n'en invente jamais"));
    }

    /**
     * A reply without any store suggestion yields empty targeting lists, never nulls.
     */
    @Test
    void draftWithoutStoresYieldsEmptyTargeting() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"specification\":{\"percent\":0.2}}"))
                .draft("une remise");
        assertEquals(OfferDraftResult.OK, result.status);
        assertEquals(java.util.List.of(), result.storeCodes);
        assertEquals(java.util.List.of(), result.storeGroupCodes);
    }

    /**
     * A malformed reply is retried with its refusal as a corrective note, and the model's
     * second answer is returned: the loop repairs a formatting failure, not a decision.
     */
    @Test
    void retriesAMalformedReplyUntilTheModelProducesAValidDraft() {
        AnthropicMessagesClient client = mock(AnthropicMessagesClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(), anyString()))
                .thenReturn("Je pense que...")
                .thenReturn("{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"code\":\"PROMO_1\","
                        + "\"specification\":{\"percent\":0.2},\"explanation\":\"exp\"}");
        OfferDraftResult result = service(client).draft("une remise");
        assertEquals(OfferDraftResult.OK, result.status);
        assertEquals("PROMO_1", result.code);
        verify(client, times(2)).complete(anyString(), anyString());
        // The second user prompt carries the first refusal back to the model.
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(anyString(), user.capture());
        assertTrue(user.getAllValues().get(1).contains("réponse précédente a été refusée"));
    }

    /**
     * The retries are capped: a model that never produces a valid draft costs exactly
     * MAX_ATTEMPTS calls, and the error carries the last refusal.
     */
    @Test
    void capsTheRetriesAtFourAttempts() {
        AnthropicMessagesClient client = mock(AnthropicMessagesClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(), anyString())).thenReturn("toujours pas du JSON");
        OfferDraftResult result = service(client).draft("une remise");
        assertEquals(OfferDraftResult.ERROR, result.status);
        verify(client, times(OfferDraftService.MAX_ATTEMPTS)).complete(anyString(), anyString());
        assertTrue(result.message.contains("Après " + OfferDraftService.MAX_ATTEMPTS
                + " tentatives"), "The exhausted notice must carry the cap: " + result.message);
        assertTrue(result.message.contains("Dernier refus"));
    }

    /**
     * A deliberate impossibility is a well-formed result — the model's own answer, not a
     * formatting failure: it is returned on the first attempt, never retried.
     */
    @Test
    void neverRetriesADeliberateImpossibility() {
        AnthropicMessagesClient client = mock(AnthropicMessagesClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(), anyString()))
                .thenReturn("{\"status\":\"impossible\",\"reason\":\"aucun type ne colle\"}");
        OfferDraftResult result = service(client).draft("un pari sur la météo");
        assertEquals(OfferDraftResult.IMPOSSIBLE, result.status);
        verify(client, times(1)).complete(anyString(), anyString());
    }

    /**
     * A reply without a code collapses the code to null instead of carrying a blank.
     */
    @Test
    void draftCollapsesABlankCodeToNull() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"code\":\"   \","
                        + "\"specification\":{\"percent\":0.1},\"explanation\":null}"))
                .draft("dix pour cent");
        assertEquals(OfferDraftResult.OK, result.status);
        assertNull(result.code);
        assertNull(result.explanation);
    }

    /**
     * A real engine type carries a behavioural description, and it lands in the system
     * prompt next to the schema: the model chooses on behaviour, not on field names.
     */
    @Test
    void draftCarriesTheBehaviourDescriptionOfARealType() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        Map<String, String> schemas = new LinkedHashMap<>();
        schemas.put("TIERED_DISCOUNT", SCHEMA);
        when(registry.getAllSchemas()).thenReturn(schemas);
        when(registry.getSchema("TIERED_DISCOUNT")).thenReturn(SCHEMA);
        AnthropicMessagesClient client = configuredClient(
                "{\"status\":\"ok\",\"type\":\"TIERED_DISCOUNT\","
                        + "\"specification\":{\"percent\":0.2}}");
        OfferDraftService service = new OfferDraftService();
        service.client = client;
        service.schemaRegistry = registry;
        service.messages = new com.intermarche.valuation.i18n.Messages();
        service.draft("remise par paliers");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(client).complete(system.capture(), anyString());
        assertTrue(system.getValue().contains("tranches marginales"),
                "The prompt must carry the behavioural description of the type.");
        assertTrue(system.getValue().contains("--- TIERED_DISCOUNT ---"));
    }

    /**
     * A fenced reply is tolerated: the fence is stripped before the JSON is read.
     */
    @Test
    void draftToleratesAFencedReply() {
        OfferDraftResult result = service(configuredClient(
                "```json\n{\"status\":\"ok\",\"type\":\"" + TYPE + "\",\"code\":\"PROMO_2\","
                        + "\"specification\":{\"percent\":0.5},\"explanation\":\"exp\"}\n```"))
                .draft("cinquante pour cent");
        assertEquals(OfferDraftResult.OK, result.status);
        assertEquals("PROMO_2", result.code);
    }

    /**
     * An invented offer type is refused: the registry has no schema for it.
     */
    @Test
    void draftRefusesAnInventedType() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"NO_SUCH_TYPE\",\"specification\":{\"percent\":0.1}}"))
                .draft("quelque chose");
        assertEquals(OfferDraftResult.ERROR, result.status);
        assertTrue(result.message.contains("NO_SUCH_TYPE"));
    }

    /**
     * An ok reply without a specification object takes the bad-reply arm.
     */
    @Test
    void draftRefusesAMissingSpecification() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\"}")).draft("quelque chose");
        assertEquals(OfferDraftResult.ERROR, result.status);
    }

    /**
     * An ok reply whose specification fails the schema takes the invalid-draft arm, with
     * the engine's own validation message substituted.
     */
    @Test
    void draftRefusesASpecificationThatFailsTheSchema() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"ok\",\"type\":\"" + TYPE + "\","
                        + "\"specification\":{\"percent\":\"beaucoup\"}}"))
                .draft("une grosse remise");
        assertEquals(OfferDraftResult.ERROR, result.status);
        assertNotNull(result.message);
    }

    /**
     * An impossible reply passes the reason and the suggestion through verbatim.
     */
    @Test
    void draftPassesAnImpossibilityThrough() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"impossible\",\"reason\":\"aucun type ne colle\","
                        + "\"suggestion\":\"une remise simple s'en rapprocherait\"}"))
                .draft("un pari sur la météo");
        assertEquals(OfferDraftResult.IMPOSSIBLE, result.status);
        assertEquals("aucun type ne colle", result.reason);
        assertEquals("une remise simple s'en rapprocherait", result.suggestion);
    }

    /**
     * An impossible reply without a suggestion leaves the suggestion null.
     */
    @Test
    void draftLeavesTheSuggestionAbsentWhenOmitted() {
        OfferDraftResult result = service(configuredClient(
                "{\"status\":\"impossible\",\"reason\":\"aucun type ne colle\"}"))
                .draft("un pari sur la météo");
        assertEquals(OfferDraftResult.IMPOSSIBLE, result.status);
        assertNull(result.suggestion);
    }

    /**
     * A reply carrying no known status takes the bad-reply arm.
     */
    @Test
    void draftRefusesAnUnknownStatus() {
        OfferDraftResult result = service(configuredClient("{\"status\":\"maybe\"}"))
                .draft("quelque chose");
        assertEquals(OfferDraftResult.ERROR, result.status);
    }

    /**
     * A reply that is not JSON at all takes the bad-reply arm.
     */
    @Test
    void draftRefusesANonJsonReply() {
        OfferDraftResult result = service(configuredClient("Je pense que..."))
                .draft("quelque chose");
        assertEquals(OfferDraftResult.ERROR, result.status);
    }

    /**
     * The availability flag delegates to the client, both arms.
     */
    @Test
    void isAvailableDelegatesToTheClient() {
        assertTrue(service(configuredClient(null)).isAvailable());
        AnthropicMessagesClient off = mock(AnthropicMessagesClient.class);
        when(off.isConfigured()).thenReturn(false);
        assertFalse(service(off).isAvailable());
    }
}
