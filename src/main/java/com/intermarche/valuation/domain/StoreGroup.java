package com.intermarche.valuation.domain;

import io.quarkus.panache.common.Page;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Entity representing a logical grouping of Stores.
 * <p>
 * Supports a hierarchical (or graph) structure where a StoreGroup can contain:
 * <ul>
 *   <li>A list of {@link Store} entities (Leaves).</li>
 *   <li>A list of other {@link StoreGroup} entities (Sub-groups/Children).</li>
 * </ul>
 * <p>
 * The relationship is unidirectional: the Parent StoreGroup holds the lists.
 * The Child (Store or StoreGroup) does not hold a reference to the Parent.
 * <p>
 * <b>Multiple Parents:</b> A group (or a store) can belong to multiple parent groups.
 * This requires traversing the structure as a Directed Acyclic Graph (DAG), which is why
 * the child relationship uses a join table rather than a foreign key: a single column
 * could only ever record one parent per group.
 */
@Entity
@Table(name = "store_groups",
        uniqueConstraints = @UniqueConstraint(columnNames = "code")
)
@Cacheable
public class StoreGroup extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Group code is mandatory")
    public String code;

    @Column(name = "name", nullable = false, length = 100)
    @NotBlank(message = "Group name is mandatory")
    public String name;

    // --------------------------------------------------
    // Relations: Direct Stores
    // --------------------------------------------------

    /**
     * The list of physical stores directly belonging to this group.
     * <p>
     * Unidirectional relationship materialised by a join table. No cascade is declared:
     * a store outlives the groups referencing it, and several groups may share it.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "store_group_stores",
            joinColumns = @JoinColumn(name = "store_group_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id")
    )
    public Set<Store> stores = new HashSet<>();

    // --------------------------------------------------
    // Relations: Child Groups (Hierarchy)
    // --------------------------------------------------

    /**
     * The list of sub-groups (StoreGroups) contained within this group.
     * <p>
     * Unidirectional relationship materialised by a join table, so a group can be listed
     * as a child of several parents at once.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "store_group_children",
            joinColumns = @JoinColumn(name = "parent_group_id"),
            inverseJoinColumns = @JoinColumn(name = "child_group_id")
    )
    public Set<StoreGroup> storeGroups = new HashSet<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a StoreGroup by its unique code.
     * @param code The store group code.
     * @return The StoreGroup entity or null if not found.
     */
    public static StoreGroup findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Retrieves all parent StoreGroups for a given Store, traversing up the hierarchy.
     * <p>
     * This method supports graphs where a group can have multiple parents.
     * It performs a Depth-First Search (DFS) to find all ancestors.
     *
     * @param store The store to search for.
     * @return A Set of unique {@link StoreGroup}.
     *         Returns an empty Set if the store belongs to no group or if the store is null.
     */
    public static Set<StoreGroup> findAllStoreGroups(Store store) {
        Set<StoreGroup> ancestors = new HashSet<>();
        if (store == null || store.id == null) {
            return ancestors;
        }
        // 1. Find all direct parents of the Store
        // Query: Select StoreGroup where the collection 'stores' contains the specific store ID
        List<StoreGroup> directParents = find(
                "select sg from StoreGroup sg join sg.stores s where s.id = ?1",
                store.id
        ).list();
        // 2. Recursively find all parent groups for each direct parent
        for (StoreGroup parent : directParents) {
            findAncestorsRecursive(parent, ancestors);
        }
        return ancestors;
    }

    /**
     * Recursive helper to find all ancestors of a specific group.
     * Handles cycles and multiple parents.
     *
     * @param group The group whose parents we are looking for.
     * @param ancestors The accumulating set of ancestor groups to avoid duplicates.
     */
    private static void findAncestorsRecursive(StoreGroup group, Set<StoreGroup> ancestors) {
        // Stop if null, already visited, or missing ID
        if (ancestors.contains(group)) {
            return;
        }
        // Add current group to ancestors
        ancestors.add(group);
        // Find all parents of the current group
        // Query: Select Parent StoreGroup where the collection 'storeGroups' contains the current group
        List<StoreGroup> parents = find(
                "select parent from StoreGroup parent join parent.storeGroups child where child.id = ?1",
                group.id
        ).list();
        // Recurse for each parent found (handling multiple branches)
        for (StoreGroup parent : parents) {
            findAncestorsRecursive(parent, ancestors);
        }
    }

    /**
     * Finds every group directly declaring the given group as a child.
     * <p>
     * With a join table a group may have several parents, so this returns a list rather
     * than a single entity.
     *
     * @param group The child group.
     * @return The direct parents, never null.
     */
    public static List<StoreGroup> findParentsOf(StoreGroup group) {
        if (group == null || group.id == null) {
            return List.of();
        }
        return find("select parent from StoreGroup parent join parent.storeGroups child where child.id = ?1",
                group.id).list();
    }

    /**
     * Finds every group directly containing the given store.
     *
     * @param store The store to look up.
     * @return The groups referencing the store, never null.
     */
    public static List<StoreGroup> findGroupsContaining(Store store) {
        if (store == null || store.id == null) {
            return List.of();
        }
        return find("select g from StoreGroup g join g.stores s where s.id = ?1 order by g.code",
                store.id).list();
    }

    /**
     * Lists the groups that are not a child of any other group.
     * <p>
     * These are the entry points of the hierarchy, from which the whole graph can be
     * walked downwards.
     *
     * @return The root groups, ordered by code.
     */
    public static List<StoreGroup> findRoots() {
        return find("select g from StoreGroup g where g.id not in"
                + " (select child.id from StoreGroup parent join parent.storeGroups child)"
                + " order by g.code").list();
    }

    /**
     * Indicates whether adding a child to a parent would create a cycle.
     * <p>
     * A cycle would make {@link #findAllStoreGroups(Store)} and any downward traversal
     * loop forever, so the check runs before the link is created rather than relying on
     * visited-set guards afterwards.
     * <p>
     * The candidate child is rejected when it is the parent itself, or when the parent is
     * already reachable by walking down from the child.
     *
     * @param parent The group that would receive the child.
     * @param child  The group that would be added.
     * @return {@code true} when the link must be refused.
     */
    public static boolean wouldCreateCycle(StoreGroup parent, StoreGroup child) {
        if (parent == null || child == null) {
            return false;
        }
        if (parent.id != null && parent.id.equals(child.id)) {
            return true;
        }
        Set<Long> visited = new HashSet<>();
        Deque<StoreGroup> pending = new ArrayDeque<>();
        pending.push(child);
        while (!pending.isEmpty()) {
            StoreGroup current = pending.pop();
            if (current.id == null || !visited.add(current.id)) {
                continue;
            }
            if (current.id.equals(parent.id)) {
                return true;
            }
            pending.addAll(current.storeGroups);
        }
        return false;
    }

    /**
     * Collects every group reachable by walking down from this one, including itself.
     * <p>
     * The visited set guards against a cycle that would have slipped into the data.
     *
     * @return The group and all of its descendants.
     */
    public Set<StoreGroup> collectDescendants() {
        Set<StoreGroup> collected = new LinkedHashSet<>();
        Deque<StoreGroup> pending = new ArrayDeque<>();
        pending.push(this);
        while (!pending.isEmpty()) {
            StoreGroup current = pending.pop();
            if (!collected.add(current)) {
                continue;
            }
            pending.addAll(current.storeGroups);
        }
        return collected;
    }

    /**
     * Collects every store reachable from this group, directly or through its sub-groups.
     *
     * @return The stores covered by this group.
     */
    public Set<Store> collectAllStores() {
        Set<Store> collected = new LinkedHashSet<>();
        for (StoreGroup group : collectDescendants()) {
            collected.addAll(group.stores);
        }
        return collected;
    }

    /**
     * Searches store groups by code prefix or by name fragment.
     * <p>
     * An empty query returns the first groups by code, so the caller can offer a starting
     * selection rather than an empty dropdown.
     *
     * @param query The raw search term, may be null or blank.
     * @param limit The maximum number of groups to return.
     * @return The matching store groups, never null.
     */
    public static List<StoreGroup> search(String query, int limit) {
        String term = query == null ? "" : query.trim().toLowerCase();
        if (term.isEmpty()) {
            return find("order by code").page(Page.ofSize(limit)).list();
        }
        return find("lower(code) like ?1 or lower(name) like ?2 order by code",
                term + "%", "%" + term + "%").page(Page.ofSize(limit)).list();
    }

    /**
     * Calculates a checksum based on the group's code and name.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        // Excluding children from checksum for performance and stability.
        return Objects.hash(code, name);
    }
}