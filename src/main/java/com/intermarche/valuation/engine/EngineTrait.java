package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Trait providing common utilities for offer and advantage processing.
 * <p>
 * This interface acts as a mixin for various factories and appliers,
 * providing shared validation, database retrieval, and JSON parsing logic.
 */
public interface EngineTrait {

    /**
     * JSON Schema fragment for the optional {@code trigger} block (spec §3.1), injected into
     * every offer/advantage schema by {@link #processSpecification} — the single funnel, so
     * no factory is edited one by one.
     */
    String TRIGGER_PROPERTY_SCHEMA = """
        { "type": "object", "additionalProperties": false,
          "required": ["conditions"],
          "properties": {
            "conditions": { "type": "array", "minItems": 1,
              "items": { "$ref": "#/definitions/triggerCondition" } } } }""";

    /**
     * JSON Schema definition of a single trigger condition — the three kinds of spec §3.1.
     * The scope/eans cross-rules a schema cannot express are enforced in code by
     * {@link Trigger#of}.
     */
    String TRIGGER_CONDITION_DEFINITION = """
        { "oneOf": [
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "scope", "threshold"],
              "properties": {
                "kind": { "const": "MINIMUM_AMOUNT" },
                "scope": { "enum": ["TICKET", "ITEMS"] },
                "eans": { "type": "array", "minItems": 1,
                          "items": { "type": "string", "minLength": 1 } },
                "threshold": { "type": "number", "exclusiveMinimum": 0 } } },
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "eans", "threshold"],
              "properties": {
                "kind": { "const": "MINIMUM_QUANTITY" },
                "eans": { "type": "array", "minItems": 1,
                          "items": { "type": "string", "minLength": 1 } },
                "threshold": { "type": "number", "exclusiveMinimum": 0 } } },
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "code"],
              "properties": {
                "kind": { "const": "COUPON_CODE" },
                "code": { "type": "string", "minLength": 1 } } },
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "from", "to"],
              "properties": {
                "kind": { "const": "TIME_WINDOW" },
                "days": { "type": "array", "minItems": 1,
                          "items": { "enum": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
                                              "FRIDAY", "SATURDAY", "SUNDAY"] } },
                "from": { "type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$" },
                "to": { "type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$" } } } ] }""";

    /**
     * JSON Schema fragment for the optional {@code applicationMoment} (spec §3.6): declared
     * in C1, its effect delivered in C2.
     */
    String APPLICATION_MOMENT_SCHEMA = """
        { "enum": ["AT_TRIGGER", "AT_TOTAL"], "default": "AT_TOTAL" }""";

    /**
     * JSON Schema fragment for the optional {@code arbitration} block (spec §4.1): all
     * defaults reproduce the current behaviour — activation by data, never by code.
     */
    String ARBITRATION_SCHEMA = """
        { "type": "object", "additionalProperties": false,
          "properties": {
            "priority": { "type": "integer", "minimum": 0, "maximum": 1000, "default": 500 },
            "cumulable": { "type": "boolean", "default": true },
            "exclusionGroups": { "type": "array", "items": { "type": "string", "minLength": 1 } },
            "maxApplicationsPerTicket": { "type": "integer", "minimum": 1 },
            "maxApplicationsPerLine": { "type": "integer", "minimum": 1 },
            "consumesContributors": { "type": "boolean", "default": false } } }""";

    /**
     * JSON Schema fragment for the optional {@code budget} block (spec §3): the offer's own
     * generosity budget. At least one of {@code maxAmount} (cumulated euros of the advantage) or
     * {@code maxApplications} (number of tickets) must be present; {@code perStore} tells whether
     * the ceiling is understood per point of sale. It coexists with a budget group — the most
     * constrained of the two wins (spec §3, §5.6).
     */
    String BUDGET_SCHEMA = """
        { "type": "object", "additionalProperties": false,
          "anyOf": [ { "required": ["maxAmount"] }, { "required": ["maxApplications"] } ],
          "properties": {
            "maxAmount": { "type": "number", "exclusiveMinimum": 0 },
            "maxApplications": { "type": "integer", "exclusiveMinimum": 0 },
            "perStore": { "type": "boolean", "default": false } } }""";

    /**
     * JSON Schema fragment for the optional {@code compliance} block (spec-divers-offre §2):
     * regulatory exemptions the engine executes and traces. {@code egalimExempt} makes the
     * configuration's discounts count as excluded generosity in the EGAlim guard (like
     * anti-gaspi — the sales case); {@code srpExempt} exempts its discounted lines from the
     * future SRP guard. The legal use of these flags is the retailer's responsibility.
     */
    String COMPLIANCE_SCHEMA = """
        { "type": "object", "additionalProperties": false,
          "properties": {
            "egalimExempt": { "type": "boolean" },
            "srpExempt": { "type": "boolean" } } }""";

    /**
     * JSON Schema fragment for the optional {@code accountingCode} (spec-divers-offre §3): the
     * ventilation key copied verbatim to the output for the ticket accounting feed. The engine
     * does not interpret it.
     */
    String ACCOUNTING_CODE_SCHEMA = """
        { "type": "string", "minLength": 1 }""";

    /**
     * JSON Schema fragment for the optional {@code printMode} (spec-divers-offre §3): line or
     * ticket-foot printing, copied verbatim to the output. The engine does not interpret it.
     */
    String PRINT_MODE_SCHEMA = """
        { "enum": ["LINE", "FOOTER"] }""";

    /**
     * JSON Schema fragment for the optional {@code funding} (spec-exploitation §3): who funds the
     * promotion — the store, the banner, or an even split. Copied verbatim to the output, the
     * persisted consumption declaration and the financial exports, exactly like
     * {@code accountingCode}: the engine never interprets it.
     */
    String FUNDING_SCHEMA = """
        { "enum": ["STORE", "BANNER", "SPLIT_50_50"] }""";

    /**
     * JSON Schema fragment for the optional {@code targetFamilyCodes} (spec-ciblage-saisie §1):
     * a list of {@link com.intermarche.valuation.domain.ProductFamily} codes whose sub-tree of
     * articles joins the plate by union with {@code targetEans}. Injected ONLY into schemas that
     * already carry {@code targetEans}, and resolved at evaluation, never compiled at entry.
     */
    String TARGET_FAMILY_CODES_SCHEMA = """
        { "type": "array", "items": { "type": "string", "minLength": 1 } }""";

    /**
     * JSON Schema fragment for the optional {@code excludedEans} (spec-ciblage-saisie §2): a list
     * of EANs subtracted from the plate AFTER the targeting union — the exclusion always wins,
     * even over an explicitly targeted EAN. Injected ONLY into schemas that carry
     * {@code targetEans}.
     */
    String EXCLUDED_EANS_SCHEMA = """
        { "type": "array", "items": { "type": "string", "minLength": 1 } }""";

    /**
     * JSON Schema fragment for the optional {@code targetWhere} (spec-ciblage-where §3): a
     * free-form product targeting written as a condition on the product's own attributes
     * — {@code brand = 'DANONE' and not name like 'bio%'} — parsed by the closed grammar of
     * {@link TargetWhere}. Mutually exclusive with {@code targetEans} and
     * {@code targetFamilyCodes}; {@code excludedEans} still subtracts from it, the exclusion
     * always wins. Injected ONLY into schemas that carry {@code targetEans}.
     */
    String TARGET_WHERE_SCHEMA = """
        { "type": "string", "minLength": 3,
          "description": "Ciblage libre : condition sur les attributs du produit, ex. \\"brand = 'DANONE' and not name like 'bio%'\\". Champs : brand, ean, egalimRegime, name, productType, referenceVolume, referenceWeight. Exclu de targetEans/targetFamilyCodes.",
          "x-label": "Ciblage conditionnel" }""";

    /**
     * Retrieves the {@link Basket} from the evaluation context.
     *
     * @param basketEvaluation the evaluation context containing the basket.
     * @param errorMessage     the error message to throw if the basket is null.
     * @return the validated Basket.
     * @throws IllegalStateException if the basket is null.
     */
    default Basket getBasket(BasketEvaluation basketEvaluation, String errorMessage) {
        Basket basket = basketEvaluation.getBasket();
        if (basket == null) {
            throw new IllegalStateException(errorMessage);
        }
        return basket;
    }

    /**
     * Retrieves a {@link Product} by its EAN code.
     *
     * @param produceEan    the EAN code of the product to retrieve.
     * @param errorMessage    the error message to throw if the product is not found.
     * @return the validated Product.
     * @throws IllegalStateException if the product is not found.
     */
    default Product getProduct(String produceEan, String errorMessage) {
        Product product = Product.findByEan(produceEan);
        if (product == null) {
            throw new IllegalStateException(String.format(
                    errorMessage, produceEan
            ));
        }
        return product;
    }

    /**
     * Retrieves offers by type for the given basket evaluation context.
     * <p>
     * This method fetches offers that match the provided type,
     * considering both direct store offers and offers associated with the store's groups.
     *
     * @param basketEvaluation the basket evaluation context containing store and group information.
     * @param type             the type of offers to retrieve.
     * @return a collection of matching offers.
     */
    default Collection<Offer> getOffers(BasketEvaluation basketEvaluation, String type) {
        // Display valuation (spec-valorisation-affichage-ecommerce §2bis): serve the offers the
        // shared context already resolved ONCE for the whole call, keeping only those that may
        // shape a one-unit displayed price (the guards, here). The point-of-sale exclusion and the
        // deterministic ordering are applied exactly as on the database path.
        DisplayContext display = basketEvaluation.getDisplayContext();
        if (display != null) {
            return sortedByCode(removePdvExcluded(display.servedOffers(type), basketEvaluation));
        }
        java.time.LocalDateTime at = DateTimeProvider.now();
        Set<Offer> offers = new LinkedHashSet<>(Offer.findInForceByStoreAndType(basketEvaluation.getStore(), type, at));
        offers.addAll(Offer.findInForceByStoreGroupsAndType(basketEvaluation.getStoreGroups(), type, at));
        return sortedByCode(removePdvExcluded(offers, basketEvaluation));
    }

    /**
     * Retrieves offers by EANs and type for the given basket evaluation context.
     * <p>
     * This method fetches offers that match the provided EANs and type,
     * considering both direct store offers and offers associated with the store's groups.
     *
     * @param basketEvaluation the basket evaluation context containing store and group information.
     * @param eans             the collection of product EANs to search offers for.
     * @param type             the type of offers to retrieve.
     * @return a collection of matching offers.
     */
    default Collection<Offer> getOffers(BasketEvaluation basketEvaluation, Collection<String> eans, String type) {
        // Display valuation (spec-valorisation-affichage-ecommerce §2bis): serve, from the once-
        // resolved shared context, the PRICING offers of this type that target the mini-basket's
        // EANs — the basket-dependent (badge) and ticket-level offers are deliberately withheld so
        // they never shape a one-unit displayed price (rules 1-2).
        DisplayContext display = basketEvaluation.getDisplayContext();
        if (display != null) {
            return sortedByCode(removePdvExcluded(display.servedOffers(eans, type), basketEvaluation));
        }
        java.time.LocalDateTime at = DateTimeProvider.now();
        Set<Offer> offers = new LinkedHashSet<>(Offer.findInForceByEansAndStoreAndType(eans, basketEvaluation.getStore(), type, at));
        offers.addAll(Offer.findInForceByEansAndStoreGroupsAndType(eans, basketEvaluation.getStoreGroups(), type, at));
        return sortedByCode(removePdvExcluded(offers, basketEvaluation));
    }

    /**
     * Drops the offers whose point-of-sale exclusion covers this basket (spec-ciblage-saisie §3),
     * applied AFTER the store-group resolution the finders performed: an offer is removed when the
     * basket's store is in its {@code excludedStores}, or when any of the store's resolved groups is
     * in its {@code excludedStoreGroups}. The exclusion always wins — an offer served through a
     * targeted group is still dropped when that group (or the store) is excluded.
     *
     * @param offers           the merged, in-force offers for the basket.
     * @param basketEvaluation the evaluation, carrying the store and its resolved groups.
     * @return the offers minus those excluded for this basket's point of sale.
     */
    private Collection<Offer> removePdvExcluded(Collection<Offer> offers, BasketEvaluation basketEvaluation) {
        Store store = basketEvaluation.getStore();
        Collection<StoreGroup> groups = basketEvaluation.getStoreGroups();
        Set<Long> groupIds = new HashSet<>();
        if (groups != null) {
            for (StoreGroup group : groups) {
                groupIds.add(group.id);
            }
        }
        List<Offer> kept = new ArrayList<>();
        for (Offer offer : offers) {
            if (store != null && offer.excludedStores.stream().anyMatch(s -> s.id.equals(store.id))) {
                continue;
            }
            if (offer.excludedStoreGroups.stream().anyMatch(g -> groupIds.contains(g.id))) {
                continue;
            }
            kept.add(offer);
        }
        return kept;
    }

    /**
     * Resolves the plate of an offer's EAN targeting (spec-ciblage-saisie §1, §2): the union of
     * {@code targetEans} (an array or a single string) and the articles of every
     * {@code targetFamilyCodes} sub-tree, minus every {@code excludedEans}. The single
     * implementation shared by all factories that target EANs.
     * <p>
     * Resolution is done here, at evaluation, against the live catalog: an article added to a
     * targeted family after the offer was saved is covered without touching the offer. An unknown
     * family code is a fail-closed {@link ConfigurationException} for this configuration, traced
     * through the engine's usual per-configuration skip. With neither {@code targetFamilyCodes} nor
     * {@code excludedEans} present, the returned set is exactly the {@code targetEans} set, in
     * declaration order — the byte-for-byte regression the spec §6 requires.
     *
     * @param spec      the validated offer specification node.
     * @param offerCode the offer code, for the fail-closed message.
     * @return the resolved, de-duplicated, order-stable set of targeted EANs.
     */
    default Set<String> resolveTargetEans(JsonNode spec, String offerCode) {
        Set<String> plate = new LinkedHashSet<>();
        JsonNode eansNode = spec.get("targetEans");
        if (eansNode != null) {
            if (eansNode.isArray()) {
                for (JsonNode ean : eansNode) {
                    plate.add(ean.asText());
                }
            } else if (eansNode.isTextual()) {
                plate.add(eansNode.asText());
            }
        }
        JsonNode familiesNode = spec.get("targetFamilyCodes");
        if (familiesNode != null && familiesNode.isArray()) {
            for (JsonNode familyCode : familiesNode) {
                String code = familyCode.asText();
                ProductFamily family = ProductFamily.findByCode(code);
                if (family == null) {
                    throw new ConfigurationException(
                            "Unknown target family code '" + code + "' for offer " + offerCode + ".");
                }
                collectFamilyEans(family, plate, new HashSet<>());
            }
        }
        JsonNode whereNode = spec.get("targetWhere");
        if (whereNode != null && whereNode.isTextual() && !whereNode.asText().isBlank()) {
            // The free-form targeting (spec-ciblage-where §4): one parameterized query whose
            // text carries only whitelisted identifiers, resolved here exactly as the family
            // codes above — the plate stays an EAN set for the factories.
            plate.addAll(TargetWhere.eansOf(whereNode.asText()));
        }
        JsonNode excludedNode = spec.get("excludedEans");
        if (excludedNode != null && excludedNode.isArray()) {
            for (JsonNode excluded : excludedNode) {
                plate.remove(excluded.asText());
            }
        }
        return plate;
    }

    /**
     * Collects, into {@code plate}, the EANs of a family's whole sub-tree (its own products plus
     * every descendant family's products), guarding against family cycles with a visited set.
     *
     * @param family  the family whose sub-tree is walked.
     * @param plate   the accumulating set of EANs (mutated).
     * @param visited the family ids already walked, to break cycles.
     */
    private void collectFamilyEans(ProductFamily family, Set<String> plate, Set<Long> visited) {
        if (family == null || !visited.add(family.id)) {
            return;
        }
        for (Product product : family.products) {
            if (product.ean != null) {
                plate.add(product.ean);
            }
        }
        for (ProductFamily child : family.productFamilies) {
            collectFamilyEans(child, plate, visited);
        }
    }

    /**
     * Returns the offers in a deterministic order: by configuration {@code code} ascending,
     * {@code null} codes last (report C2).
     * <p>
     * The two in-force queries merge into a set whose iteration order is not guaranteed by the
     * database, so the appliers built from these configurations — and therefore every tie-break
     * that later depends on their build order — would otherwise vary between runs and between
     * nodes. Sorting on the naturally-unique offer code removes that source of nondeterminism at
     * the source.
     *
     * @param offers the merged, de-duplicated offers.
     * @return a new list of the offers, ordered by code.
     */
    private List<Offer> sortedByCode(Collection<Offer> offers) {
        List<Offer> sorted = new ArrayList<>(offers);
        sorted.sort(Comparator.comparing((Offer o) -> o.code,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    /**
     * Validates the offer specification JSON against a provided JSON schema and processes it if the validation is successful.
     * <p>
     * This method parses the offer specification, validates it against the provided schema.
     * If validation succeeds, the parsed {@link JsonNode} is passed to the provided
     * {@link Consumer} for further processing. If validation fails, an {@link IllegalArgumentException}
     * is thrown containing the validation error details.
     *
     * @param schemaSpecification the JSON schema definition (as a string or URI).
     * @param offerSpecification the offer specification JSON string to validate and process.
     * @param process            the consumer logic to execute on the parsed JSON node if validation passes.
     * @throws IllegalArgumentException if the JSON is invalid, validation fails, or a parsing error occurs.
     */
    /**
     * Offer-aware variant of {@link #processSpecification(String, String, Consumer)}: validates
     * the offer's specification and, on rejection, carries the offer code in the message —
     * {@code Error validating offer: [CODE] ...} — so a failing configuration is identifiable
     * among the store's offers. The canonical prefixes are preserved verbatim.
     *
     * @param schemaSpecification The factory's JSON schema.
     * @param offer               The configuration whose specification is validated.
     * @param process             The consumer invoked with the validated specification node.
     */
    default void processSpecification(String schemaSpecification, Offer offer, Consumer<JsonNode> process) {
        try {
            processSpecification(schemaSpecification, offer.specification, process);
        } catch (IllegalArgumentException e) {
            String prefix = "Error validating offer: ";
            String message = e.getMessage();
            if (message != null && message.startsWith(prefix)) {
                throw new ConfigurationException(prefix + "[" + offer.code + "] " + message.substring(prefix.length()), e);
            }
            throw new ConfigurationException("[" + offer.code + "] " + message, e);
        }
    }

    default void processSpecification(String schemaSpecification, String offerSpecification, Consumer<JsonNode> process) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            // 0. Inject the shared trigger / applicationMoment / arbitration fragments once,
            // centrally, into every offer/advantage schema (spec §3.5). The basket schema is
            // left untouched.
            String effectiveSchema = injectSharedFragments(mapper, schemaSpecification);
            // 1. Configure the factory for the desired schema version (V7, V2019-09, V2020-12, etc.)
            // Here we use Draft 7 (widely used).
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            // 2. Load the schema
            JsonSchema schema = factory.getSchema(effectiveSchema);
            // 3. Parse JSON content into JsonNode (Jackson)
            JsonNode jsonNode = mapper.readTree(offerSpecification);
            // 4. Perform validation
            Set<ValidationMessage> errors = schema.validate(jsonNode);
            // 5. Analyze results
            if (errors.isEmpty()) {
                // Cross-field trigger rules a schema cannot express (spec §3.8), rejected at
                // creation through the very same channel.
                validateTriggerCrossRules(jsonNode);
                // Free-form targeting cross rules (spec-ciblage-where §3): exclusivity with the
                // enumerated targeting, and the fragment must parse before the offer exists.
                validateTargetingCrossRules(jsonNode);
                process.accept(jsonNode);
            } else {
                // Print errors for debugging
                String message = errors.stream().map(err -> err.getMessage()).collect(Collectors.joining(", "));
                throw new ConfigurationException("Error validating offer: "+message);
            }
        } catch (JsonProcessingException e) {
            throw new ConfigurationException("Error parsing offer.", e);
        }
    }

    /**
     * Injects the shared {@code trigger}, {@code applicationMoment} and {@code arbitration}
     * fragments (spec §3.5, §4.1) into an offer/advantage schema, centrally and once.
     * <p>
     * This is the single funnel the spec requires: no factory schema is edited on its own,
     * and a future type inherits the blocks for free. The basket schema is deliberately left
     * untouched — it is validated through the same method but must not carry these offer-only
     * blocks. A schema without a top-level {@code properties} object is returned unchanged.
     * <p>
     * Static and public because {@code OfferSchemaRegistry} applies the very same funnel at
     * registration time, so the administration form, the AI drafting prompt and the
     * validation all see one schema — the enriched one. Idempotent: applying it twice
     * replaces the same properties and finds the {@code required} list already freed.
     *
     * @param mapper              the shared object mapper.
     * @param schemaSpecification the raw factory schema.
     * @return the schema augmented with the three optional blocks, or the input unchanged
     *         when it is the basket schema, is malformed, or carries no {@code properties}.
     */
    static String injectSharedFragments(ObjectMapper mapper, String schemaSpecification) {
        if (Basket.BASKET_SCHEMA.equals(schemaSpecification)) {
            return schemaSpecification;
        }
        try {
            JsonNode root = mapper.readTree(schemaSpecification);
            if (!(root instanceof ObjectNode rootObject)
                    || !(root.get("properties") instanceof ObjectNode properties)) {
                return schemaSpecification;
            }
            properties.set("trigger", mapper.readTree(TRIGGER_PROPERTY_SCHEMA));
            properties.set("applicationMoment", mapper.readTree(APPLICATION_MOMENT_SCHEMA));
            properties.set("arbitration", mapper.readTree(ARBITRATION_SCHEMA));
            properties.set("budget", mapper.readTree(BUDGET_SCHEMA));
            properties.set("compliance", mapper.readTree(COMPLIANCE_SCHEMA));
            properties.set("accountingCode", mapper.readTree(ACCOUNTING_CODE_SCHEMA));
            properties.set("printMode", mapper.readTree(PRINT_MODE_SCHEMA));
            properties.set("funding", mapper.readTree(FUNDING_SCHEMA));
            // Family targeting and EAN exclusion (spec-ciblage-saisie §1, §2) are offered only to
            // schemas that already target EANs — the union/subtraction has no meaning otherwise.
            if (properties.has("targetEans")) {
                properties.set("targetFamilyCodes", mapper.readTree(TARGET_FAMILY_CODES_SCHEMA));
                properties.set("excludedEans", mapper.readTree(EXCLUDED_EANS_SCHEMA));
                properties.set("targetWhere", mapper.readTree(TARGET_WHERE_SCHEMA));
                // With a family able to fill the plate on its own, targetEans is no longer strictly
                // required: drop it from the schema's required list if present, so a family-only
                // offer validates. Offers that still carry targetEans are unaffected.
                if (root.get("required") instanceof com.fasterxml.jackson.databind.node.ArrayNode required) {
                    for (int i = required.size() - 1; i >= 0; i--) {
                        if ("targetEans".equals(required.get(i).asText())) {
                            required.remove(i);
                        }
                    }
                }
            }
            ObjectNode definitions = root.get("definitions") instanceof ObjectNode existing
                    ? existing : mapper.createObjectNode();
            definitions.set("triggerCondition", mapper.readTree(TRIGGER_CONDITION_DEFINITION));
            rootObject.set("definitions", definitions);
            return mapper.writeValueAsString(rootObject);
        } catch (JsonProcessingException e) {
            // A malformed factory schema is a coding error, not a user input error: keep the
            // original so the downstream validation surfaces it exactly as it did before.
            return schemaSpecification;
        }
    }

    /**
     * Applies the trigger cross-field rules of spec §3.8 that a JSON Schema cannot express —
     * scope {@code ITEMS} requires {@code eans}, scope {@code TICKET} forbids them — by
     * parsing the trigger block through {@link Trigger#of}.
     *
     * @param specification the validated specification node.
     * @throws IllegalArgumentException through the existing "Error validating offer: "
     *                                  channel when a cross-field rule is violated.
     */
    private void validateTriggerCrossRules(JsonNode specification) {
        if (specification != null && specification.has("trigger")) {
            try {
                Trigger.of(specification.get("trigger"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Error validating offer: " + e.getMessage());
            }
        }
    }

    /**
     * Applies the product-targeting cross-field rules of spec-ciblage-where §3, which a JSON
     * Schema cannot express.
     * <p>
     * The scope rules carry the same semantics as the trigger's (spec §3.8), applied to the
     * product targeting: a specification whose {@code scope} is {@code ITEMS} must carry one
     * targeting — {@code targetEans}, {@code targetFamilyCodes} or {@code targetWhere} — or
     * it silently applies to nothing; a specification whose scope is {@code TICKET} cannot
     * carry one, the whole ticket being the target.
     * <p>
     * {@code targetWhere} replaces the enumerated targeting, so it cannot cohabit with
     * {@code targetEans} or {@code targetFamilyCodes}; and the fragment must parse through the
     * closed grammar now, so an invalid condition is refused at the door rather than skipped at
     * valuation. {@code excludedEans} remains compatible: the exclusion always wins.
     *
     * @param specification the validated specification node.
     * @throws IllegalArgumentException through the existing "Error validating offer: " channel.
     */
    private void validateTargetingCrossRules(JsonNode specification) {
        if (specification == null) {
            return;
        }
        JsonNode scope = specification.get("scope");
        if (scope != null && "ITEMS".equals(scope.asText()) && !carriesTargeting(specification)) {
            throw new IllegalArgumentException("Error validating offer: a scope ITEMS "
                    + "specification requires a product targeting: targetEans, "
                    + "targetFamilyCodes or targetWhere.");
        }
        if (scope != null && "TICKET".equals(scope.asText()) && carriesTargeting(specification)) {
            throw new IllegalArgumentException("Error validating offer: a scope TICKET "
                    + "specification cannot carry a product targeting.");
        }
        JsonNode where = specification.get("targetWhere");
        if (where == null || !where.isTextual() || where.asText().isBlank()) {
            return;
        }
        if (specification.hasNonNull("targetEans") || specification.hasNonNull("targetFamilyCodes")) {
            throw new IllegalArgumentException("Error validating offer: targetWhere cannot be "
                    + "combined with targetEans or targetFamilyCodes.");
        }
        try {
            TargetWhere.parse(where.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Error validating offer: " + e.getMessage());
        }
    }

    /**
     * Indicates whether a specification carries any product targeting, ignoring a blank
     * free-form condition.
     *
     * @param specification the validated specification node.
     * @return {@code true} when at least one targeting is present.
     */
    private static boolean carriesTargeting(JsonNode specification) {
        if (specification.hasNonNull("targetEans") || specification.hasNonNull("targetFamilyCodes")) {
            return true;
        }
        JsonNode where = specification.get("targetWhere");
        return where != null && where.isTextual() && !where.asText().isBlank();
    }
}