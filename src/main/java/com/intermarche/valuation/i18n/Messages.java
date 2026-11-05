package com.intermarche.valuation.i18n;

import com.intermarche.valuation.security.CurrentUser;
import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateData;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves localised interface labels for the back-office screens (spec-exploitation §1).
 * <p>
 * Templates reach it through {@code inject:i18n}, e.g. {@code {inject:i18n.get('nav.offers')}},
 * mirroring how the layout already reaches {@code inject:currentUser}. The locale is resolved once
 * per request with the precedence the specification mandates: the signed-in user's stored
 * preference ({@link com.intermarche.valuation.domain.AppUser#locale}), then the request's
 * {@code Accept-Language} header, then the application default {@code fr}.
 * <p>
 * Only two bundles are shipped: {@code fr} (the exhaustive reference) and {@code en} (complete).
 * {@code pt} and {@code nl} are recognised as interface languages but fall back to the English
 * bundle (assumed, documented). Any other language falls back to the default French bundle. This
 * class localises the interface only: the machine API messages stay in English whatever the locale.
 */
@Named("i18n")
@RequestScoped
@TemplateData
@Unremovable
public class Messages {

    /**
     * The application default language: the exhaustive reference bundle.
     */
    public static final String DEFAULT_LANGUAGE = "fr";

    /**
     * The languages that ship their own bundle. Every other supported interface language falls
     * back onto one of these.
     */
    private static final Set<String> BUNDLE_LANGUAGES = Set.of("fr", "en");

    /**
     * The interface languages recognised for {@code Accept-Language} negotiation and for the user
     * preference; {@code pt} and {@code nl} are recognised but served from the English bundle.
     */
    private static final Set<String> RECOGNISED_LANGUAGES = Set.of("fr", "en", "pt", "nl");

    /**
     * Loaded bundles, keyed by bundle language; a bundle is read from the classpath once.
     */
    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();

    /**
     * The signed-in user, for the stored language preference.
     */
    @Inject
    CurrentUser currentUser;

    /**
     * The current HTTP routing context, for {@code Accept-Language} negotiation; resolved lazily and
     * absent outside an HTTP request.
     */
    @Inject
    Instance<RoutingContext> routingContext;

    /**
     * The resolved bundle language for the current request, computed lazily once.
     */
    private String resolvedBundleLanguage;

    /**
     * Returns the localised label for a key in the current request's language.
     * <p>
     * Resolution falls back to the default French bundle when the key is absent from the resolved
     * bundle, and finally returns the key itself when it is defined nowhere, so a missing key is
     * visible rather than fatal (the exhaustiveness test guarantees the two bundles agree).
     *
     * @param key the message key.
     * @return the localised label, the French value as a fallback, or the key when undefined.
     */
    public String get(String key) {
        String language = bundleLanguage();
        String value = BUNDLES.computeIfAbsent(language, Messages::load).get(key);
        if (value == null && !DEFAULT_LANGUAGE.equals(language)) {
            value = BUNDLES.computeIfAbsent(DEFAULT_LANGUAGE, Messages::load).get(key);
        }
        return value != null ? value : key;
    }

    /**
     * Returns the localised label for a key, substituting {@code {0}}, {@code {1}}… placeholders.
     *
     * @param key  the message key.
     * @param args the arguments substituted into the pattern.
     * @return the formatted localised label.
     */
    public String format(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, Locale.forLanguageTag(bundleLanguage())).format(args);
    }

    /**
     * Returns the bundle language actually rendered ({@code fr} or {@code en}), for the
     * {@code <html lang>} attribute.
     *
     * @return the rendered bundle language.
     */
    public String getLang() {
        return bundleLanguage();
    }

    /**
     * Resolves, once per request, the bundle language to serve: the recognised request language
     * mapped onto a shipped bundle ({@code fr} stays {@code fr}; {@code en}/{@code pt}/{@code nl}
     * map to {@code en}; anything else to the default {@code fr}).
     *
     * @return the bundle language for this request.
     */
    private String bundleLanguage() {
        if (resolvedBundleLanguage == null) {
            String language = resolveRequestLanguage();
            if (DEFAULT_LANGUAGE.equals(language)) {
                resolvedBundleLanguage = DEFAULT_LANGUAGE;
            } else if (RECOGNISED_LANGUAGES.contains(language)) {
                resolvedBundleLanguage = BUNDLE_LANGUAGES.contains(language) ? language : "en";
            } else {
                resolvedBundleLanguage = DEFAULT_LANGUAGE;
            }
        }
        return resolvedBundleLanguage;
    }

    /**
     * Resolves the requested interface language by precedence: the user's stored preference, then
     * the {@code Accept-Language} header, then the application default.
     *
     * @return the requested language tag (its primary subtag, lower-cased).
     */
    private String resolveRequestLanguage() {
        String preference = userPreference();
        if (preference != null) {
            return preference;
        }
        String negotiated = acceptLanguage();
        if (negotiated != null) {
            return negotiated;
        }
        return DEFAULT_LANGUAGE;
    }

    /**
     * Returns the signed-in user's stored language preference, when set and recognised.
     *
     * @return the recognised preferred language, or null when unset, unknown or anonymous.
     */
    private String userPreference() {
        com.intermarche.valuation.domain.AppUser user = currentUser == null ? null : currentUser.currentAppUser();
        if (user == null || user.locale == null || user.locale.isBlank()) {
            return null;
        }
        String language = primarySubtag(user.locale);
        return RECOGNISED_LANGUAGES.contains(language) ? language : null;
    }

    /**
     * Returns the best recognised language offered by the request's {@code Accept-Language} header,
     * honouring its quality weights.
     *
     * @return the first recognised offered language (highest quality first), or null when the header
     *         is absent or offers nothing recognised.
     */
    private String acceptLanguage() {
        String header = acceptLanguageHeader();
        if (header == null || header.isBlank()) {
            return null;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(header);
        } catch (RuntimeException e) {
            return null;
        }
        for (Locale.LanguageRange range : ranges) {
            String language = primarySubtag(range.getRange());
            if (RECOGNISED_LANGUAGES.contains(language)) {
                return language;
            }
        }
        return null;
    }

    /**
     * Reads the raw {@code Accept-Language} header from the current HTTP request.
     *
     * @return the header value, or null when there is no active HTTP request or no such header.
     */
    private String acceptLanguageHeader() {
        if (routingContext == null || !routingContext.isResolvable()) {
            return null;
        }
        RoutingContext context;
        try {
            context = routingContext.get();
        } catch (RuntimeException e) {
            return null;
        }
        return context == null ? null : context.request().getHeader("Accept-Language");
    }

    /**
     * Extracts the lower-cased primary subtag of a language tag (e.g. {@code fr-FR} → {@code fr}).
     *
     * @param tag the language tag; may be null.
     * @return the primary subtag, or an empty string when the tag is null or blank.
     */
    private static String primarySubtag(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String trimmed = tag.trim().toLowerCase(Locale.ROOT);
        int separator = indexOfSeparator(trimmed);
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }

    /**
     * Returns the index of the first subtag separator ({@code -} or {@code _}) in a tag.
     *
     * @param tag the language tag, already trimmed.
     * @return the separator index, or -1 when there is none.
     */
    private static int indexOfSeparator(String tag) {
        int dash = tag.indexOf('-');
        int underscore = tag.indexOf('_');
        if (dash < 0) {
            return underscore;
        }
        if (underscore < 0) {
            return dash;
        }
        return Math.min(dash, underscore);
    }

    /**
     * Loads a bundle from the classpath into an immutable key/value map, UTF-8 encoded.
     *
     * @param language the bundle language.
     * @return the loaded key/value pairs; empty when the bundle file is absent.
     */
    private static Map<String, String> load(String language) {
        Map<String, String> map = new HashMap<>();
        String path = "messages/msg_" + language + ".properties";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String name : properties.stringPropertyNames()) {
                    map.put(name, properties.getProperty(name));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load message bundle " + path, e);
        }
        return map;
    }
}
