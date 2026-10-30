package com.intermarche.valuation.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Entity representing an Offer with specific specifications.
 * <p>
 * An Offer can be linked to multiple {@link Store} entities and multiple {@link StoreGroup} entities.
 * <p>
 * This entity extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "offers",
        indexes = {
                @Index(name = "idx_offer_code", columnList = "code"),
                @Index(name = "idx_offer_type", columnList = "type"),
                @Index(name = "idx_offer_active", columnList = "is_active")
        }
)
@Cacheable
public class Offer extends BaseEntity {

    /**
     * A static and immutable instance of {@code ObjectMapper} to handle
     * JSON serialization and deserialization throughout the application.
     *
     * The {@code ObjectMapper} is provided by the Jackson library and is
     * used to convert Java objects to JSON and vice-versa. This is a
     * thread-safe instance intended for reuse to improve performance
     * and reduce overhead of creating multiple instances.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    // --------------------------------------------------
    // Offer Details
    // --------------------------------------------------

    /**
     * The unique code of the offer (e.g., "PROMO_SUMMER_2024").
     * Serves as the primary business key.
     */
    @Column(unique = true, nullable = false, length = 50)
    @NotBlank(message = "Offer code is mandatory")
    public String code;

    /**
     * The type of the offer (e.g., "PROMO", "DISCOUNT", "PRICE_DROP").
     * Used for filtering offers efficiently.
     */
    @Column(nullable = false, length = 50)
    @NotBlank(message = "Offer type is mandatory")
    public String type;

    /**
     * The specification of the offer stored as a JSON string.
     * <p>
     * In Java, this is a simple String. In the database, this is mapped
     * to a JSON column (e.g., JSONB in PostgreSQL).
     */
    @Column(nullable = false, length = 1000)
    @NotBlank(message = "Offer specification is mandatory")
    public String specification;

    /**
     * Whether the offer is active; an inactive offer is never loaded by the engine.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    /**
     * Start of the validity window (inclusive); null means no lower bound, so an
     * offer without a window stays permanently in force (backward compatibility
     * with pre-existing configurations that carry no dates).
     */
    @Column(name = "valid_from")
    public java.time.LocalDateTime validFrom;

    /**
     * End of the validity window (exclusive); null leaves the window open-ended.
     */
    @Column(name = "valid_to")
    public java.time.LocalDateTime validTo;

    /**
     * The list of EANs extracted from the specification.
     * <p>
     * This list is automatically populated when the entity is persisted or updated,
     * based on keys ending with "ean" (single value) or "eans" (list of values)
     * found in the JSON specification.
     * <p>
     * Stored in a separate table 'offer_eans' with an index on the 'ean' column.
     */
    @ElementCollection
    @CollectionTable(name = "offer_eans",
            joinColumns = @JoinColumn(name = "offer_id"),
            indexes = @Index(columnList = "ean", name = "idx_offer_ean_value"))
    @Column(name = "ean", length = 13)
    @OrderColumn(name = "list_index")
    public List<String> eans = new ArrayList<>();

    // --------------------------------------------------
    // Relations (Targeting)
    // --------------------------------------------------

    /**
     * The set of Stores this offer applies to.
     * Unidirectional relationship managed via a join table.
     */
    @ManyToMany
    @JoinTable(
            name = "offer_stores",
            joinColumns = @JoinColumn(name = "offer_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id")
    )
    public Set<Store> stores = new HashSet<>();

    /**
     * The set of StoreGroups this offer applies to.
     * Unidirectional relationship managed via a join table.
     */
    @ManyToMany
    @JoinTable(
            name = "offer_store_groups",
            joinColumns = @JoinColumn(name = "offer_id"),
            inverseJoinColumns = @JoinColumn(name = "store_group_id")
    )
    public Set<StoreGroup> storeGroups = new HashSet<>();

    /**
     * The set of Stores this offer is EXCLUDED from (spec-ciblage-saisie §3).
     * <p>
     * Targeting-level, not specification-level: the in-force finders subtract these after the
     * store-group resolution, so an offer served through a targeted group is still withheld from an
     * excluded store. Empty by default — an offer without exclusions behaves exactly as today.
     */
    @ManyToMany
    @JoinTable(
            name = "offer_excluded_stores",
            joinColumns = @JoinColumn(name = "offer_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id")
    )
    public Set<Store> excludedStores = new HashSet<>();

    /**
     * The set of StoreGroups this offer is EXCLUDED from (spec-ciblage-saisie §3): a store in any
     * of these groups is not served the offer, even if another targeted group contains it. Empty by
     * default.
     */
    @ManyToMany
    @JoinTable(
            name = "offer_excluded_store_groups",
            joinColumns = @JoinColumn(name = "offer_id"),
            inverseJoinColumns = @JoinColumn(name = "store_group_id")
    )
    public Set<StoreGroup> excludedStoreGroups = new HashSet<>();

    /**
     * The budget group this offer belongs to, or null when the offer carries no group (spec §2).
     * <p>
     * An offer out of any group is exactly what it is today. A grouped offer shares the group's
     * common envelope and is bounded by the group's period — the finders below stop serving it
     * once the group is out of period (spec §5.4). Joined by numeric id, resolved by business
     * code at import time.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_group_id")
    public BudgetGroup budgetGroup;

    // --------------------------------------------------
    // Lifecycle Callbacks
    // --------------------------------------------------

    /**
     * Automatically updates the list of EANs from the specification before persisting.
     */
    @Override
    @PrePersist
    public void onCreate() {
        updateEansFromSpecification(); // 1. Ma logique (avant)
        super.onCreate();               // 2. La logique parente (après)
    }

    /**
     * Automatically updates the list of EANs from the specification before updating.
     */
    @Override
    @PreUpdate
    public void onUpdate() {
        updateEansFromSpecification(); // 1. Ma logique (avant)
        super.onUpdate();              // 2. La logique parente (après)
    }

    /**
     * Parses the JSON specification and extracts all EANs.
     * It looks for keys ending with "ean" (String value) or "eans" (Array value).
     */
    private void updateEansFromSpecification() {
        this.eans.clear();
        if (this.specification == null || this.specification.isBlank()) {
            return;
        }
        try {
            JsonNode rootNode = mapper.readTree(this.specification);
            Set<String> extractedEans = new HashSet<>();
            extractEansRecursively(rootNode, extractedEans);
            // Deterministic order (report §3): the extracted EANs are sorted before populating the
            // @OrderColumn-indexed collection. A HashSet iteration order would otherwise write a
            // different offer_eans index layout on every rebuild, marking the collection dirty on
            // each update and churning the join table for no real change.
            List<String> sortedEans = new ArrayList<>(extractedEans);
            java.util.Collections.sort(sortedEans);
            this.eans.addAll(sortedEans);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse specification for Offer " + this.code + ": " + e.getMessage());
        }
    }

    /**
     * Helper method to recursively traverse the JSON tree and find EANs.
     *
     * @param node       The current JSON node.
     * @param eanSet     The set to accumulate found EANs.
     */
    void extractEansRecursively(JsonNode node, Set<String> eanSet) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase();
                JsonNode valueNode = entry.getValue();

                // Case 1: Key ends with "ean" (typically singular) and value is a String
                // Note: "targetEans" ends with "ean" is false, so this handles singular keys safely.
                if (key.endsWith("ean") && valueNode.isTextual()) {
                    eanSet.add(valueNode.asText());
                }
                // Case 2: Key ends with "eans" (plural)
                else if (key.endsWith("eans")) {
                    if (valueNode.isArray()) {
                        // Handle Array of strings
                        for (JsonNode item : valueNode) {
                            if (item.isTextual()) {
                                eanSet.add(item.asText());
                            }
                        }
                    } else if (valueNode.isTextual()) {
                        // Handle Single string for plural key (fix for the failing test)
                        eanSet.add(valueNode.asText());
                    }
                }

                // Recurse into nested objects
                if (valueNode.isObject() || valueNode.isArray()) {
                    extractEansRecursively(valueNode, eanSet);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode arrayItem : node) {
                extractEansRecursively(arrayItem, eanSet);
            }
        }
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds an offer by its unique code.
     *
     * @param code The offer code.
     * @return The Offer entity or null if not found.
     */
    public static Offer findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Finds offers linked to a specific store and of a specific type.
     *
     * @param store The store entity.
     * @param type  The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByStoreAndType(Store store, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.stores s where s = ?1) and o.type = ?2", store, type);
    }

    /**
     * Finds offers linked to any of the provided store groups and of a specific type.
     *
     * @param storeGroups A list of StoreGroup entities.
     * @param type        The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByStoreGroupsAndType(Collection<StoreGroup> storeGroups, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.storeGroups g where g in ?1) and o.type = ?2", storeGroups, type);
    }

    /**
     * Finds offers applicable to a list of specific EANs, linked to a specific store, and of a specific type.
     *
     * @param eans  The list of EANs the offer must target.
     * @param store The store entity.
     * @param type  The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByEansAndStoreAndType(Collection<String> eans, Store store, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.stores s where s = ?2) and o.type = ?3", eans, store, type);
    }

    /**
     * Finds offers applicable to a list of specific EANs, linked to store groups, and of a specific type.
     *
     * @param eans        The list of EANs the offer must target.
     * @param storeGroups A list of StoreGroup entities.
     * @param type        The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByEansAndStoreGroupsAndType(Collection<String> eans, Collection<StoreGroup> storeGroups, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.storeGroups g where g in ?2) and o.type = ?3", eans, storeGroups, type);
    }

    /**
     * Indicates whether the offer is in force at the given instant: active and inside
     * its validity window (validFrom inclusive, validTo exclusive). A null bound is
     * open — unlike imfid's rules, an offer without validFrom is in force, because
     * pre-existing configurations carry no window.
     *
     * @param at The instant to test; when null the current engine time is used.
     * @return true when the offer is active and its window contains the instant.
     */
    public boolean isInForceAt(java.time.LocalDateTime at) {
        java.time.LocalDateTime moment = at != null ? at : com.intermarche.valuation.domain.util.DateTimeProvider.now();
        if (!active || (validFrom != null && moment.isBefore(validFrom))) {
            return false;
        }
        return validTo == null || moment.isBefore(validTo);
    }

    /**
     * The in-force predicate shared by the engine finders: active, inside the offer's own
     * validity window, AND — when the offer belongs to a budget group — inside the group's
     * period at the given instant (null bounds are open). The group period bounds the offer's
     * period, never the other way round (spec §5.4); the group is reached through the {@code bg}
     * alias every finder LEFT JOINs, so an offer without a group ({@code bg is null}) is never
     * filtered out.
     */
    private static final String IN_FORCE = " and o.active = true"
            + " and (o.validFrom is null or o.validFrom <= ?IN_FORCE_AT)"
            + " and (o.validTo is null or o.validTo > ?IN_FORCE_AT)"
            + " and (bg is null or ((bg.validFrom is null or bg.validFrom <= ?IN_FORCE_AT)"
            + " and (bg.validTo is null or bg.validTo > ?IN_FORCE_AT)))";

    /**
     * Finds the offers of a type linked to a store and in force at the given instant —
     * the engine-facing variant of {@link #findByStoreAndType(Store, String)}.
     *
     * @param store The store entity.
     * @param type  The type of the offer.
     * @param at    The instant the offers must be in force at.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findInForceByStoreAndType(Store store, String type, java.time.LocalDateTime at) {
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.stores s where s = ?1) and o.type = ?2"
                + IN_FORCE.replace("?IN_FORCE_AT", "?3"), store, type, at);
    }

    /**
     * Finds the offers of a type linked to any of the store groups and in force at the
     * given instant — the engine-facing variant of {@link #findByStoreGroupsAndType(Collection, String)}.
     *
     * @param storeGroups A list of StoreGroup entities.
     * @param type        The type of the offer.
     * @param at          The instant the offers must be in force at.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findInForceByStoreGroupsAndType(Collection<StoreGroup> storeGroups, String type, java.time.LocalDateTime at) {
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.storeGroups g where g in ?1) and o.type = ?2"
                + IN_FORCE.replace("?IN_FORCE_AT", "?3"), storeGroups, type, at);
    }

    /**
     * Finds the offers of a type targeting any of the EANs, linked to a store and in
     * force at the given instant — the engine-facing variant of
     * {@link #findByEansAndStoreAndType(Collection, Store, String)}.
     *
     * @param eans  The list of EANs the offer must target.
     * @param store The store entity.
     * @param type  The type of the offer.
     * @param at    The instant the offers must be in force at.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findInForceByEansAndStoreAndType(Collection<String> eans, Store store, String type, java.time.LocalDateTime at) {
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.stores s where s = ?2) and o.type = ?3"
                + IN_FORCE.replace("?IN_FORCE_AT", "?4"), eans, store, type, at);
    }

    /**
     * Finds the offers of a type targeting any of the EANs, linked to store groups and
     * in force at the given instant — the engine-facing variant of
     * {@link #findByEansAndStoreGroupsAndType(Collection, Collection, String)}.
     *
     * @param eans        The list of EANs the offer must target.
     * @param storeGroups A list of StoreGroup entities.
     * @param type        The type of the offer.
     * @param at          The instant the offers must be in force at.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findInForceByEansAndStoreGroupsAndType(Collection<String> eans, Collection<StoreGroup> storeGroups, String type, java.time.LocalDateTime at) {
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.storeGroups g where g in ?2) and o.type = ?3"
                + IN_FORCE.replace("?IN_FORCE_AT", "?4"), eans, storeGroups, type, at);
    }

    /**
     * Finds every in-force offer linked to a store, whatever its type — the type-agnostic variant
     * of {@link #findInForceByStoreAndType(Store, String, java.time.LocalDateTime)}.
     * <p>
     * Introduced for the display valuation context (spec-valorisation-affichage-ecommerce §2bis):
     * that endpoint resolves the whole in-force offer set of a store ONCE per call — across every
     * type — then classifies and applies it to each product, instead of letting each offer type run
     * its own finder per product. The in-force predicate is the very one the typed finders use.
     *
     * @param store The store entity.
     * @param at    The instant the offers must be in force at.
     * @return A list of every matching Offer entity, all types confounded.
     */
    public static List<Offer> findInForceByStore(Store store, java.time.LocalDateTime at) {
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.stores s where s = ?1)"
                + IN_FORCE.replace("?IN_FORCE_AT", "?2"), store, at);
    }

    /**
     * Finds every in-force offer linked to any of the store groups, whatever its type — the
     * type-agnostic variant of
     * {@link #findInForceByStoreGroupsAndType(Collection, String, java.time.LocalDateTime)}.
     *
     * @param storeGroups A list of StoreGroup entities.
     * @param at          The instant the offers must be in force at.
     * @return A list of every matching Offer entity, all types confounded.
     */
    public static List<Offer> findInForceByStoreGroups(Collection<StoreGroup> storeGroups, java.time.LocalDateTime at) {
        if (storeGroups == null || storeGroups.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.storeGroups g where g in ?1)"
                + IN_FORCE.replace("?IN_FORCE_AT", "?2"), storeGroups, at);
    }

    /**
     * Finds every in-force offer targeting any of the EANs and linked to a store, whatever its
     * type — the type-agnostic variant of
     * {@link #findInForceByEansAndStoreAndType(Collection, Store, String, java.time.LocalDateTime)}.
     *
     * @param eans  The list of EANs the offer must target.
     * @param store The store entity.
     * @param at    The instant the offers must be in force at.
     * @return A list of every matching Offer entity, all types confounded.
     */
    public static List<Offer> findInForceByEansAndStore(Collection<String> eans, Store store, java.time.LocalDateTime at) {
        if (eans == null || eans.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.stores s where s = ?2)"
                + IN_FORCE.replace("?IN_FORCE_AT", "?3"), eans, store, at);
    }

    /**
     * Finds every in-force offer targeting any of the EANs and linked to any of the store groups,
     * whatever its type — the type-agnostic variant of
     * {@link #findInForceByEansAndStoreGroupsAndType(Collection, Collection, String, java.time.LocalDateTime)}.
     *
     * @param eans        The list of EANs the offer must target.
     * @param storeGroups A list of StoreGroup entities.
     * @param at          The instant the offers must be in force at.
     * @return A list of every matching Offer entity, all types confounded.
     */
    public static List<Offer> findInForceByEansAndStoreGroups(Collection<String> eans, Collection<StoreGroup> storeGroups, java.time.LocalDateTime at) {
        if (eans == null || eans.isEmpty() || storeGroups == null || storeGroups.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return list("select distinct o from Offer o left join o.budgetGroup bg where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.storeGroups g where g in ?2)"
                + IN_FORCE.replace("?IN_FORCE_AT", "?3"), eans, storeGroups, at);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the offer's key attributes and its targets.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String storeCodes = stores.stream()
                .map(s -> s.code)
                .sorted()
                .collect(Collectors.joining("|"));
        String groupCodes = storeGroups.stream()
                .map(g -> g.code)
                .sorted()
                .collect(Collectors.joining("|"));
        String excludedStoreCodes = String.join("|", getExcludedStoreCodes());
        String excludedGroupCodes = String.join("|", getExcludedStoreGroupCodes());
        return Objects.hash(code, type, specification, storeCodes, groupCodes, active, validFrom, validTo,
                getBudgetGroupCode(), excludedStoreCodes, excludedGroupCodes);
    }

    /**
     * Returns the code of the budget group this offer belongs to, flattened for the API and the
     * checksum, or null when the offer carries no group.
     *
     * @return the budget group code, or null.
     */
    public String getBudgetGroupCode() {
        return budgetGroup == null ? null : budgetGroup.code;
    }

    /**
     * Returns the codes of the stores this offer is excluded from (spec-ciblage-saisie §3),
     * flattened for the API in a deterministic sorted order.
     *
     * @return the sorted excluded store codes, possibly empty.
     */
    public List<String> getExcludedStoreCodes() {
        return excludedStores.stream().map(s -> s.code).sorted().collect(java.util.stream.Collectors.toList());
    }

    /**
     * Returns the codes of the store groups this offer is excluded from (spec-ciblage-saisie §3),
     * flattened for the API in a deterministic sorted order.
     *
     * @return the sorted excluded store group codes, possibly empty.
     */
    public List<String> getExcludedStoreGroupCodes() {
        return excludedStoreGroups.stream().map(g -> g.code).sorted().collect(java.util.stream.Collectors.toList());
    }
}