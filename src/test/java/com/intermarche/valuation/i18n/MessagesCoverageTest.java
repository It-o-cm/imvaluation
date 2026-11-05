package com.intermarche.valuation.i18n;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.security.CurrentUser;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests (no {@code @QuarkusTest}, no CDI container) for the locale resolution of
 * {@link Messages}, driving the bean by setting its package-private collaborators directly.
 * <p>
 * Every leg of the resolution precedence is asserted through the observable output (the rendered
 * label and {@code getLang()}): the stored user preference, the {@code Accept-Language} negotiation,
 * the default fallback, the {@code fr}/{@code en}/{@code pt}/{@code nl}→bundle mapping, the
 * primary-subtag extraction, and the defensive guards (no request, unreadable header, malformed
 * weights). Bundle values are asserted verbatim against the shipped {@code msg_fr}/{@code msg_en}.
 */
public class MessagesCoverageTest {

    /**
     * Builds a {@link Messages} whose user preference and request are wired to the given values.
     *
     * @param userLocale     the signed-in user's stored locale, or null for no/anonymous preference.
     * @param acceptLanguage the request's Accept-Language header, or null for none.
     * @return the wired bean.
     */
    private Messages messages(String userLocale, String acceptLanguage) {
        Messages messages = new Messages();
        CurrentUser currentUser = mock(CurrentUser.class);
        if (userLocale == null) {
            when(currentUser.currentAppUser()).thenReturn(null);
        } else {
            AppUser user = new AppUser();
            user.locale = userLocale.isEmpty() ? null : userLocale;
            when(currentUser.currentAppUser()).thenReturn(user);
        }
        messages.currentUser = currentUser;
        messages.routingContext = routingContext(acceptLanguage);
        return messages;
    }

    /**
     * Builds a resolvable {@link Instance} of a {@link RoutingContext} whose request returns the given
     * Accept-Language header.
     *
     * @param acceptLanguage the header value, or null.
     * @return the wired instance.
     */
    @SuppressWarnings("unchecked")
    private Instance<RoutingContext> routingContext(String acceptLanguage) {
        Instance<RoutingContext> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader("Accept-Language")).thenReturn(acceptLanguage);
        when(context.request()).thenReturn(request);
        when(instance.get()).thenReturn(context);
        return instance;
    }

    /**
     * With no preference and no header, the default French bundle is served.
     */
    @Test
    void testDefaultsToFrench() {
        Messages messages = messages(null, null);
        assertEquals("fr", messages.getLang());
        assertEquals("Offres", messages.get("nav.offers"));
    }

    /**
     * A user preference of {@code en} serves the English bundle, ahead of any header.
     */
    @Test
    void testUserPreferenceEnglish() {
        Messages messages = messages("en", "fr");
        assertEquals("en", messages.getLang());
        assertEquals("Offers", messages.get("nav.offers"));
    }

    /**
     * A {@code pt} preference is recognised but served from the English bundle (assumed fallback).
     */
    @Test
    void testPortuguesePreferenceFallsBackToEnglish() {
        assertEquals("en", messages("pt", null).getLang());
    }

    /**
     * A {@code nl} preference is recognised but served from the English bundle (assumed fallback).
     */
    @Test
    void testDutchPreferenceFallsBackToEnglish() {
        assertEquals("en", messages("nl", null).getLang());
    }

    /**
     * A {@code fr} preference stays French.
     */
    @Test
    void testFrenchPreferenceStaysFrench() {
        assertEquals("fr", messages("fr", null).getLang());
    }

    /**
     * A region subtag is stripped: {@code fr-FR} resolves to the {@code fr} bundle (dash separator).
     */
    @Test
    void testPreferenceWithDashRegionSubtag() {
        assertEquals("fr", messages("fr-FR", null).getLang());
    }

    /**
     * An underscore-separated tag is stripped too: {@code en_US} resolves to the {@code en} bundle.
     */
    @Test
    void testPreferenceWithUnderscoreRegionSubtag() {
        assertEquals("en", messages("en_US", null).getLang());
    }

    /**
     * An unrecognised preference ({@code de}) is ignored and resolution falls through to the header.
     */
    @Test
    void testUnrecognisedPreferenceFallsThroughToHeader() {
        assertEquals("en", messages("de", "en").getLang());
    }

    /**
     * A blank stored locale is treated as no preference and falls through to the header.
     */
    @Test
    void testBlankPreferenceFallsThrough() {
        assertEquals("en", messages("", "en-US,en;q=0.9").getLang());
    }

    /**
     * With no preference, the Accept-Language header negotiates the language, honouring its order.
     */
    @Test
    void testAcceptLanguageNegotiation() {
        assertEquals("en", messages(null, "en-GB,en;q=0.9,fr;q=0.5").getLang());
    }

    /**
     * An Accept-Language offering only recognised {@code pt} maps to the English bundle.
     */
    @Test
    void testAcceptLanguagePortuguese() {
        assertEquals("en", messages(null, "pt-BR,pt;q=0.8").getLang());
    }

    /**
     * An Accept-Language offering nothing recognised falls back to the default French.
     */
    @Test
    void testAcceptLanguageNothingRecognised() {
        assertEquals("fr", messages(null, "de,es;q=0.5").getLang());
    }

    /**
     * A blank Accept-Language header falls back to the default French.
     */
    @Test
    void testBlankAcceptLanguageDefaults() {
        assertEquals("fr", messages(null, "  ").getLang());
    }

    /**
     * A malformed Accept-Language weight is tolerated: the parse guard falls back to French.
     */
    @Test
    void testMalformedAcceptLanguageDefaults() {
        assertEquals("fr", messages(null, "en;q=not-a-number").getLang());
    }

    /**
     * An unresolvable routing context (no active HTTP request) leaves only the default French.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testUnresolvableRoutingContextDefaults() {
        Messages messages = new Messages();
        Instance<RoutingContext> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        messages.routingContext = instance;
        assertEquals("fr", messages.getLang());
    }

    /**
     * A null routing-context instance (outside any request scope) leaves only the default French.
     */
    @Test
    void testNullRoutingContextDefaults() {
        Messages messages = new Messages();
        assertEquals("fr", messages.getLang());
    }

    /**
     * A routing context whose {@code get()} throws is tolerated: the guard falls back to French.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testRoutingContextGetThrowsDefaults() {
        Messages messages = new Messages();
        Instance<RoutingContext> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenThrow(new RuntimeException("no context"));
        messages.routingContext = instance;
        assertEquals("fr", messages.getLang());
    }

    /**
     * A null current-user collaborator (never injected) resolves via the header alone.
     */
    @Test
    void testNullCurrentUserUsesHeader() {
        Messages messages = new Messages();
        messages.routingContext = routingContext("en");
        assertEquals("en", messages.getLang());
    }

    /**
     * {@code format} returns the raw pattern when no argument is supplied, and substitutes the
     * placeholder when one is — asserted in both bundles (fr uses guillemets, en doubled quotes).
     */
    @Test
    void testFormatSubstitutesPlaceholders() {
        assertEquals("Offres", messages(null, null).format("nav.offers"));
        assertEquals("Offres", messages(null, null).format("nav.offers", (Object[]) null));
        assertEquals("Compte « bob » créé.", messages("fr", null).format("users.notice.created", "bob"));
        assertEquals("Account 'bob' created.", messages("en", null).format("users.notice.created", "bob"));
    }

    /**
     * An undefined key returns the key itself: in English it also exercises the French-fallback lookup
     * (key absent there too), in French it exercises the no-fallback arm.
     */
    @Test
    void testUndefinedKeyReturnsKey() {
        assertEquals("no.such.key", messages("en", null).get("no.such.key"));
        assertEquals("no.such.key", messages(null, null).get("no.such.key"));
    }

    /**
     * A locale tag carrying BOTH separators ({@code fr-x_y}) exercises the primary-subtag extraction
     * where a dash and an underscore both appear: the earliest wins, so the tag resolves to {@code fr}.
     */
    @Test
    void testPreferenceWithBothSeparators() {
        assertEquals("fr", messages("fr-x_y", null).getLang());
        assertEquals("en", messages("en_x-y", null).getLang());
    }

    /**
     * The resolved bundle language is computed once and reused: a second call on the same instance
     * returns the same language without re-resolving (the cache arm).
     */
    @Test
    void testBundleLanguageResolvedOncePerInstance() {
        Messages messages = messages("en", null);
        assertEquals("en", messages.getLang());
        assertEquals("Offers", messages.get("nav.offers"));
        assertEquals("en", messages.getLang());
    }
}
