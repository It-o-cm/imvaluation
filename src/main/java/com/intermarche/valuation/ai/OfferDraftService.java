package com.intermarche.valuation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.i18n.Messages;
import com.intermarche.valuation.ui.OfferSchemaRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Turns a plain-language description of an offer or advantage into a draft specification,
 * by asking the Anthropic Messages API to pick one of the registered offer types and fill
 * its JSON Schema — the very same schemas the form generator and the valuation engine
 * enforce, so a draft can never describe something the engine could not run.
 * <p>
 * The model is constrained to a strict reply contract: a JSON object carrying either an
 * {@code ok} draft (type + specification + explanation + optional code suggestion) or an
 * {@code impossible} declaration (reason + optional close alternative). The reply is then
 * interpreted defensively: fenced output is tolerated, an invented offer type or a draft
 * that fails the engine's own validation is reported as an error rather than shown to the
 * user as a valid draft. Nothing is ever persisted here — the draft pre-fills the form and
 * the administrator reviews it before saving.
 */
@ApplicationScoped
public class OfferDraftService implements EngineTrait {

    /**
     * Upper bound on the user prompt, so a pasted document cannot blow up the call.
     */
    static final int MAX_PROMPT_CHARS = 2000;

    /**
     * Upper bound on the model calls for one draft: a refused reply is retried with its
     * refusal as a corrective note, never more than this many times.
     */
    static final int MAX_ATTEMPTS = 4;

    private static final Logger LOGGER = Logger.getLogger(OfferDraftService.class);

    /**
     * Mapper used to read the model's reply.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The Anthropic API client, never called when unconfigured.
     */
    @Inject
    AnthropicMessagesClient client;

    /**
     * The registry supplying the offer types and their schemas.
     */
    @Inject
    OfferSchemaRegistry schemaRegistry;

    /**
     * Localised interface labels for the error notices.
     */
    @Inject
    Messages messages;

    /**
     * Indicates whether the assistant can run, i.e. an API key is configured.
     *
     * @return {@code true} when the client is ready.
     */
    public boolean isAvailable() {
        return client.isConfigured();
    }

    /**
     * Produces a draft specification for a plain-language offer description.
     * <p>
     * A model reply occasionally comes out malformed — text around the JSON, an invented
     * offer type, a draft the schema refuses. The reply is verified after every call
     * ({@link #interpret}) and a refused one is retried, capped at {@link #MAX_ATTEMPTS}
     * attempts, each retry carrying the refusal back to the model as a corrective note.
     * A deliberate {@code impossible} reply is a well-formed result — the model's own
     * answer, not a formatting failure — and is returned on the first attempt, never
     * retried; only the {@code error} outcomes are.
     *
     * @param prompt The description in natural language, may be null or blank.
     * @return The outcome, never null; see {@link OfferDraftResult} for the statuses.
     */
    public OfferDraftResult draft(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return OfferDraftResult.error(messages.get("offers.ai.error.empty"));
        }
        String trimmed = prompt.trim();
        if (trimmed.length() > MAX_PROMPT_CHARS) {
            return OfferDraftResult.error(messages.format("offers.ai.error.tooLong", MAX_PROMPT_CHARS));
        }
        if (!client.isConfigured()) {
            return OfferDraftResult.error(messages.get("offers.ai.error.noKey"));
        }
        String system = systemPrompt();
        OfferDraftResult result = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
            try {
                result = interpret(client.complete(system, trimmed));
            } catch (Exception e) {
                LOGGER.error("The offer draft call failed (attempt " + attempt + ")", e);
                result = OfferDraftResult.error(
                        messages.format("offers.ai.error.callFailed", e.getMessage()));
            }
            // ok and impossible are the model's well-formed answers: returned at once.
            if (!OfferDraftResult.ERROR.equals(result.status)) {
                return result;
            }
            LOGGER.warnf("Draft attempt %d/%d refused: %s", attempt, MAX_ATTEMPTS, result.message);
            if (attempt < MAX_ATTEMPTS) {
                trimmed = trimmed + "\n\nTa réponse précédente a été refusée : « "
                        + result.message + " ». Réponds à nouveau en respectant strictement le "
                        + "contrat : uniquement un objet JSON valide, sans texte autour ni bloc "
                        + "de code.";
            }
        }
        return OfferDraftResult.error(
                messages.format("offers.ai.error.exhausted", MAX_ATTEMPTS, result.message));
    }

    /**
     * Reads the model's reply against the response contract.
     * <p>
     * A fenced reply is tolerated because models commonly wrap JSON in a code block
     * despite the instruction not to. Anything that is not a coherent {@code ok} or
     * {@code impossible} object becomes an error; an {@code ok} draft is re-validated
     * through the engine's own path before it is handed out.
     *
     * @param reply The raw text of the model reply.
     * @return The interpreted outcome.
     */
    private OfferDraftResult interpret(String reply) {
        JsonNode root;
        try {
            root = MAPPER.readTree(stripFences(reply));
        } catch (Exception e) {
            LOGGER.warn("The offer draft reply was not valid JSON");
            return OfferDraftResult.error(messages.get("offers.ai.error.badReply"));
        }
        String status = root.path("status").asText("");
        if (OfferDraftResult.IMPOSSIBLE.equals(status)) {
            return OfferDraftResult.impossible(root.path("reason").asText(null),
                    root.path("suggestion").asText(null));
        }
        if (!OfferDraftResult.OK.equals(status)) {
            LOGGER.warn("The offer draft reply carried no known status");
            return OfferDraftResult.error(messages.get("offers.ai.error.badReply"));
        }
        String type = root.path("type").asText(null);
        String schema = schemaRegistry.getSchema(type);
        if (schema == null) {
            LOGGER.warnf("The offer draft reply invented offer type '%s'", type);
            return OfferDraftResult.error(messages.format("offers.ai.error.unknownType", type));
        }
        JsonNode specification = root.get("specification");
        if (specification == null || !specification.isObject()) {
            return OfferDraftResult.error(messages.get("offers.ai.error.badReply"));
        }
        String specificationJson = specification.toString();
        try {
            // The engine's own validation, shared fragments and cross-field rules
            // included: a draft shown to the user is a draft the engine would accept.
            this.processSpecification(schema, specificationJson, node -> {
            });
        } catch (IllegalArgumentException e) {
            LOGGER.warn("The offer draft reply failed schema validation: " + e.getMessage());
            return OfferDraftResult.error(messages.format("offers.ai.error.invalidDraft", e.getMessage()));
        }
        String code = root.path("code").asText(null);
        String validFrom = readWindow(root.get("validFrom"));
        if (validFrom != null && validFrom.isEmpty()) {
            return OfferDraftResult.error(messages.get("offers.ai.error.badWindow"));
        }
        String validTo = readWindow(root.get("validTo"));
        if (validTo != null && validTo.isEmpty()) {
            return OfferDraftResult.error(messages.get("offers.ai.error.badWindow"));
        }
        java.util.List<String> storeCodes = readCodeArray(root.get("storeCodes"));
        java.util.List<String> storeGroupCodes = readCodeArray(root.get("storeGroupCodes"));
        return OfferDraftResult.ok(type, (code == null || code.isBlank()) ? null : code.trim(),
                emptyToNull(validFrom), emptyToNull(validTo),
                storeCodes, storeGroupCodes,
                specificationJson, root.path("explanation").asText(null));
    }

    /**
     * Reads a code array from the model reply, keeping only the non-blank trimmed entries —
     * an empty or absent array is a legitimate "no store suggestion", not a failure.
     *
     * @param node The array as the model reported it, may be null or absent.
     * @return The trimmed codes, empty when none.
     */
    private static java.util.List<String> readCodeArray(JsonNode node) {
        java.util.List<String> codes = new java.util.ArrayList<>();
        if (node == null || !node.isArray()) {
            return codes;
        }
        for (JsonNode entry : node) {
            String code = entry.asText("");
            if (!code.isBlank()) {
                codes.add(code.trim());
            }
        }
        return codes;
    }

    /**
     * Reads one validity bound, normalising what the model produced to the ISO local
     * date-time the form's {@code datetime-local} inputs expect.
     *
     * @param node The bound as the model reported it, may be null or absent.
     * @return The normalised bound, {@code null} when the reply carries none, or the
     *         empty string when the value is present but unreadable.
     */
    private static String readWindow(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(raw.trim()).toString();
        } catch (Exception e) {
            // Fall through: a date alone is accepted, read as the start of the day.
        }
        try {
            return java.time.LocalDate.parse(raw.trim()).atStartOfDay().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Collapses an empty normalised value to a plain null for the result.
     *
     * @param value The normalised value.
     * @return The value, or null when blank.
     */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Unwraps a fenced code block around a JSON reply, if any.
     *
     * @param reply The raw model text.
     * @return The text with any fence removed.
     */
    private static String stripFences(String reply) {
        String trimmed = reply == null ? "" : reply.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int fenceEnd = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && fenceEnd > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, fenceEnd).trim();
            }
        }
        return trimmed;
    }

    /**
     * Builds the system prompt framing the call: the role, the reply contract, and the
     * catalogue of offer types — each with its behavioural description
     * ({@link OfferTypeDescriptions}, what the type <em>does</em>) followed by its JSON
     * Schema (how the specification is <em>filled</em>) — the exact schemas the engine
     * enforces, so the model cannot describe an unbuildable offer without being caught.
     *
     * @return The system prompt.
     */
    private String systemPrompt() {
        Map<String, String> schemas = schemaRegistry.getAllSchemas();
        StringBuilder prompt = new StringBuilder()
                .append("Tu es l'assistant de configuration du moteur promotionnel d'une enseigne de distribution. ")
                .append("L'utilisateur décrit une offre ou un avantage promotionnel en langage naturel ; ")
                .append("tu choisis le type d'offre le plus proche parmi les types disponibles et tu produis ")
                .append("sa spécification JSON. Chaque type est présenté par son comportement puis par son ")
                .append("schéma JSON : choisis sur le comportement, remplis sur le schéma.\n\n")
                .append("Types disponibles :\n");
        schemas.forEach((type, schema) -> {
            prompt.append("--- ").append(type).append(" ---\n");
            String description = OfferTypeDescriptions.description(type);
            if (!description.isBlank()) {
                prompt.append(description).append('\n');
            }
            prompt.append(schema).append('\n');
        });
        appendStoreCatalogue(prompt);
        prompt.append("\nRègles de réponse :\n")
                .append("- Réponds UNIQUEMENT par un objet JSON valide, sans texte autour, sans bloc de code.\n")
                .append("- Si la demande correspond à un type disponible, réponds exactement :\n")
                .append("  {\"status\":\"ok\",\"type\":\"<un des types>\",\"code\":\"<code court suggéré, en MAJUSCULES avec des tirets bas>\",")
                .append("\"validFrom\":\"<AAAA-MM-JJTHH:MM, seulement si la demande fixe un début>\",")
                .append("\"validTo\":\"<AAAA-MM-JJTHH:MM, borne exclue, seulement si la demande fixe une fin>\",")
                .append("\"storeCodes\":[<codes de magasins tirés de la liste fournie, seulement si la demande les nomme>],")
                .append("\"storeGroupCodes\":[<codes de groupes tirés de la liste fournie, seulement si la demande vise une zone>],")
                .append("\"specification\":{<conforme au schéma du type>},\"explanation\":\"<une ou deux phrases en français expliquant le choix>\"}\n")
                .append("- La spécification doit être valide contre le schéma du type choisi, champs ")
                .append("obligatoires compris ; n'invente aucun champ hors schéma et ne renvoie jamais ")
                .append("un tableau obligatoire vide.\n")
                .append("- Quand la demande vise des produits sans en donner les EAN — une catégorie, ")
                .append("une marque, un type de produit — remplis le champ targetWhere du type choisi ")
                .append("par une condition sur les attributs (par exemple name like 'pomme%' ou ")
                .append("brand = 'DANONE') plutôt que d'abandonner le ciblage : une offre scope ITEMS ")
                .append("sans ciblage est refusée. Un type dont le scope est TICKET ne cible aucun ")
                .append("produit : ne le choisis pas pour une demande qui vise des produits précis, ")
                .append("et déclare l'impossible si aucun type ciblant ne peut la représenter.\n")
                .append("- Période : une date de début ou de fin calendaire va dans validFrom / validTo, ")
                .append("au format ISO heure locale ; validTo est exclue, donc « jusqu'au 24 décembre inclus » ")
                .append("se dit 2026-12-25T00:00. Une fenêtre récurrente (le week-end, entre 18h et 20h) ")
                .append("n'est pas une période : elle va dans le bloc trigger de la spécification. Ne mets ")
                .append("validFrom ou validTo que si la demande les exprime explicitement.\n")
                .append("- Ciblage magasins : la demande peut viser des magasins ou une zone. Ne renvoie ")
                .append("QUE des codes présents dans les listes de groupes et de magasins ci-dessus — ")
                .append("n'en invente jamais. Pour une zone ou une région, choisis le groupe ")
                .append("correspondant dans storeGroupCodes ; pour des magasins précis, leurs codes ")
                .append("dans storeCodes. Si rien ne correspond, laisse les deux listes vides et ")
                .append("signale-le dans l'explication.\n")
                .append("- Si aucune combinaison raisonnable des types disponibles ne peut représenter la ")
                .append("demande, ou qu'une information obligatoire fait défaut sans alternative, ")
                .append("réponds exactement :\n")
                .append("  {\"status\":\"impossible\",\"reason\":\"<pourquoi, ou ce qui manque, en français>\",")
                .append("\"suggestion\":\"<le cas échéant, comment compléter la demande ou une reformulation ")
                .append("qui s'en approcherait, sinon une chaîne vide>\"}\n")
                .append("- Ne promets jamais un comportement que les schémas ne permettent pas d'exprimer.\n");
        return prompt.toString();
    }

    /**
     * Appends the store referential to the prompt: the store groups, whole, and the stores,
     * capped — the model can only suggest codes it was shown, so a regional demand resolves
     * to the group that carries it, never to an invented code. The referential is data, so
     * the catalogue is rebuilt at every call and a catalogue read that fails degrades to an
     * empty section rather than failing the draft.
     *
     * @param prompt The prompt being built.
     */
    private static void appendStoreCatalogue(StringBuilder prompt) {
        prompt.append("\nCiblage magasins disponible :\n");
        try {
            prompt.append("Groupes de magasins (code — nom) :\n");
            java.util.List<com.intermarche.valuation.domain.StoreGroup> groups =
                    com.intermarche.valuation.domain.StoreGroup.list("from StoreGroup g order by g.code");
            for (com.intermarche.valuation.domain.StoreGroup group : groups) {
                prompt.append(group.code).append(" — ")
                        .append(group.name == null ? "" : group.name).append('\n');
            }
        } catch (Exception e) {
            LOGGER.warn("The store group catalogue could not be read: " + e.getMessage());
        }
        try {
            prompt.append("Magasins (code — nom), 300 premiers :\n");
            java.util.List<com.intermarche.valuation.domain.Store> stores =
                    com.intermarche.valuation.domain.Store.list("from Store s order by s.code");
            for (int i = 0; i < Math.min(stores.size(), 300); i += 1) {
                com.intermarche.valuation.domain.Store store = stores.get(i);
                prompt.append(store.code).append(" — ")
                        .append(store.name == null ? "" : store.name).append('\n');
            }
        } catch (Exception e) {
            LOGGER.warn("The store catalogue could not be read: " + e.getMessage());
        }
    }
}
