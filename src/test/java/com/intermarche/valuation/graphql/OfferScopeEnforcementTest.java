package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Perimeter enforcement over the GraphQL {@link OfferResource} (spec §3): a store-scoped or
 * type-restricted user only reads offers intersecting the scope and may only write within it.
 * <p>
 * The signed-in identity is injected with {@code @TestSecurity(user = "scopeadmin")}; the matching
 * {@link AppUser} row (carrying the perimeter) is seeded in the same rolled-back transaction, which
 * is where {@code CurrentUser} reads the scope from.
 */
@QuarkusTest
@TestTransaction
public class OfferScopeEnforcementTest {

    /**
     * The resource under test.
     */
    @Inject
    OfferResource resource;

    /**
     * Empties the mutable catalog so the assertions never see rows from another class.
     */
    private void wipe() {
        Price.deleteAll();
        Offer.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
        AppUser.delete("username = ?1", "scopeadmin");
    }

    /**
     * Seeds the signed-in user with a perimeter.
     *
     * @param storeScope        the store scope CSV, or null for none.
     * @param allowedOfferTypes the allowed offer types CSV, or null for none.
     */
    private void seedUser(String storeScope, String allowedOfferTypes) {
        AppUser user = new AppUser();
        user.username = "scopeadmin";
        user.setPassword("password1");
        user.setRoleSet(Set.of(AppUser.ROLE_ADMIN, AppUser.ROLE_MANAGER));
        user.storeScope = storeScope;
        user.allowedOfferTypes = allowedOfferTypes;
        user.persist();
    }

    /**
     * Seeds a store by code.
     *
     * @param code the store code.
     */
    private void seedStore(String code) {
        DomainUtils.createAndPersistStore(code, 48.0, 2.0);
    }

    /**
     * Seeds an offer targeting the given store codes.
     *
     * @param code       the offer code.
     * @param type       the offer type.
     * @param storeCodes the targeted store codes.
     * @return the persisted offer.
     */
    private Offer seedOffer(String code, String type, String... storeCodes) {
        Offer offer = new Offer();
        offer.code = code;
        offer.type = type;
        offer.specification = "{}";
        for (String storeCode : storeCodes) {
            offer.stores.add(Store.find("code", storeCode).firstResult());
        }
        offer.persist();
        return offer;
    }

    /**
     * Builds an offer input.
     *
     * @param code       the offer code.
     * @param type       the offer type.
     * @param storeCodes the targeted store codes.
     * @return the input record.
     */
    private OfferResource.OfferRecord input(String code, String type, List<String> storeCodes) {
        OfferResource.OfferRecord record = new OfferResource.OfferRecord();
        record.code = code;
        record.type = type;
        record.specification = "{}";
        record.storeCodes = storeCodes;
        return record;
    }

    /**
     * §3.1 read: a store-scoped user lists only the offers whose targeting intersects the scope, and
     * an offer straddling in/out of scope stays visible.
     */
    @Test
    @TestSecurity(user = "scopeadmin", roles = {"ADMIN", "MANAGER"})
    void testRead_filteredByScope() {
        wipe();
        seedUser("SA", null);
        seedStore("SA");
        seedStore("SB");
        seedOffer("OFA", "SCOPE_TYPE", "SA");
        seedOffer("OFB", "SCOPE_TYPE", "SB");
        seedOffer("OFAB", "SCOPE_TYPE", "SA", "SB");
        List<String> visible = resource.allOffers().stream().map(o -> o.code).toList();
        assertTrue(visible.contains("OFA"), "an in-scope offer is visible");
        assertTrue(visible.contains("OFAB"), "an intersecting offer is visible");
        assertFalse(visible.contains("OFB"), "an out-of-scope offer is hidden");
    }

    /**
     * §3.1 read by id: an out-of-scope offer reads as not found.
     */
    @Test
    @TestSecurity(user = "scopeadmin", roles = {"ADMIN", "MANAGER"})
    void testReadById_outOfScopeIsNotFound() {
        wipe();
        seedUser("SA", null);
        seedStore("SA");
        seedStore("SB");
        Offer inScope = seedOffer("OFA", "SCOPE_TYPE", "SA");
        Offer outScope = seedOffer("OFB", "SCOPE_TYPE", "SB");
        assertEquals("OFA", resource.offer(inScope.id).code);
        assertThrows(NoSuchElementException.class, () -> resource.offer(outScope.id));
    }

    /**
     * §3.1 write: creating an offer targeting a store outside the scope is refused; within the scope
     * it succeeds.
     */
    @Test
    @TestSecurity(user = "scopeadmin", roles = {"ADMIN", "MANAGER"})
    void testCreate_outOfScopeRefused() throws GraphQLException {
        wipe();
        seedUser("SA", null);
        seedStore("SA");
        seedStore("SB");
        assertThrows(GraphQLException.class,
                () -> resource.createOffer(input("NEW_B", "SCOPE_TYPE", List.of("SB"))));
        Offer created = resource.createOffer(input("NEW_A", "SCOPE_TYPE", List.of("SA")));
        assertEquals("NEW_A", created.code);
    }

    /**
     * §3.1 write: on an offer straddling the scope, the modification that removes the out-of-scope
     * target is accepted; the one that adds another out-of-scope target is refused.
     */
    @Test
    @TestSecurity(user = "scopeadmin", roles = {"ADMIN", "MANAGER"})
    void testUpdate_removeOutOfScopeAccepted_addRefused() throws GraphQLException {
        wipe();
        seedUser("SA", null);
        seedStore("SA");
        seedStore("SB");
        seedStore("SC");
        Offer straddling = seedOffer("OFAB", "SCOPE_TYPE", "SA", "SB");
        // Removing SB leaves {SA} ⊆ scope: accepted.
        Offer narrowed = resource.updateOffer(straddling.id, input(null, null, List.of("SA")));
        assertEquals(1, narrowed.stores.size());
        // Adding SC to an in-scope offer pushes the targeting out of scope: refused.
        Offer inScope = seedOffer("OFA", "SCOPE_TYPE", "SA");
        assertThrows(GraphQLException.class,
                () -> resource.updateOffer(inScope.id, input(null, null, List.of("SA", "SC"))));
    }

    /**
     * §3.2: a type-restricted user may not create an offer of a type outside the allow-list.
     */
    @Test
    @TestSecurity(user = "scopeadmin", roles = {"ADMIN", "MANAGER"})
    void testCreate_typeNotAllowedRefused() throws GraphQLException {
        wipe();
        seedUser(null, "TYPE_A");
        seedStore("SA");
        assertThrows(GraphQLException.class,
                () -> resource.createOffer(input("NEW_T", "TYPE_B", List.of("SA"))));
        Offer created = resource.createOffer(input("OK_T", "TYPE_A", List.of("SA")));
        assertEquals("TYPE_A", created.type);
    }

    /**
     * §3.4: an unrestricted account behaves as before — every offer visible, any target writable.
     */
    @Test
    @TestSecurity(user = "scopeadmin", roles = {"ADMIN", "MANAGER"})
    void testUnrestricted_noChange() throws GraphQLException {
        wipe();
        seedUser(null, null);
        seedStore("SA");
        seedStore("SB");
        seedOffer("OFA", "SCOPE_TYPE", "SA");
        seedOffer("OFB", "SCOPE_TYPE", "SB");
        assertEquals(2, resource.allOffers().size());
        Offer created = resource.createOffer(input("NEW_ANY", "ANY_TYPE", List.of("SB")));
        assertEquals("NEW_ANY", created.code);
    }
}
