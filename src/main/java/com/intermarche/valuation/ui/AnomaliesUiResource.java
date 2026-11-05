package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.engine.AnomalyService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only detection screen for generosity anomalies (spec-exploitation §2), open to MANAGER and
 * above.
 * <p>
 * It shows two tables built by {@link AnomalyService} over a period: atypical tickets (crossing an
 * absolute or a relative threshold) and atypical points of sale (a {@code (store, offer, day)} cell
 * deviating from the other stores by more than N standard deviations). The thresholds are screen
 * parameters — nothing is persisted (spec §2). The screen only shows: no automatic action is ever
 * taken; the human judges. Refreshing is a manual re-submission of the form, there is no real time in
 * v1.
 */
@Path("/ui/anomalies")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class AnomaliesUiResource {

    private static final Logger LOGGER = Logger.getLogger(AnomaliesUiResource.class);

    /**
     * The default number of standard deviations beyond which a point-of-sale cell is flagged.
     */
    private static final String DEFAULT_SIGMA = "2";

    /**
     * The anomaly aggregate service.
     */
    @Inject
    AnomalyService anomalyService;

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the anomalies screen.
         *
         * @param tickets  the atypical tickets.
         * @param cells    the point-of-sale/offer/day cells, flagged first.
         * @param from     the raw lower bound, echoed into the form.
         * @param to       the raw upper bound, echoed into the form.
         * @param absolute the raw absolute threshold, echoed into the form.
         * @param relative the raw relative multiplier, echoed into the form.
         * @param sigma    the raw sigma, echoed into the form.
         * @param hasRange whether a valid period was supplied (else the tables are empty on purpose).
         * @return the template instance to render.
         */
        public static native TemplateInstance list(List<AnomalyService.TicketAnomaly> tickets,
                                                   List<AnomalyService.PdvOfferAnomaly> cells,
                                                   String from, String to, String absolute,
                                                   String relative, String sigma, boolean hasRange);
    }

    /**
     * Displays the two anomaly tables for the current screen parameters.
     *
     * @param from     the inclusive lower bound (ISO date or date-time), may be null.
     * @param to       the inclusive upper bound, may be null.
     * @param absolute the absolute euro threshold for atypical tickets, may be null or blank.
     * @param relative the relative multiplier (of the average ticket) threshold, may be null or blank.
     * @param sigma    the number of standard deviations for the point-of-sale flag, defaulting to 2.
     * @return the rendered screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("from") String from,
                                 @QueryParam("to") String to,
                                 @QueryParam("absolute") String absolute,
                                 @QueryParam("relative") String relative,
                                 @QueryParam("sigma") @DefaultValue(DEFAULT_SIGMA) String sigma) {
        LOGGER.debug("Entering method list");
        LocalDateTime lower = parseLowerBound(from);
        LocalDateTime upper = parseUpperBound(to);
        boolean hasRange = lower != null && upper != null;
        BigDecimal absoluteThreshold = parseDecimal(absolute);
        BigDecimal relativeMultiplier = parseDecimal(relative);
        BigDecimal sigmaValue = parseDecimalOrDefault(sigma, new BigDecimal(DEFAULT_SIGMA));
        List<AnomalyService.TicketAnomaly> tickets = hasRange
                ? anomalyService.atypicalTickets(lower, upper, absoluteThreshold, relativeMultiplier)
                : List.of();
        List<AnomalyService.PdvOfferAnomaly> cells = hasRange
                ? anomalyService.atypicalPdvOffers(lower, upper, sigmaValue)
                : List.of();
        return Templates.list(tickets, cells, from, to, absolute, relative, sigma, hasRange);
    }

    /**
     * Parses a decimal screen parameter, tolerating a null/blank/unparseable value as "unset".
     *
     * @param raw the raw value, may be null.
     * @return the parsed value, or null when absent or unparseable.
     */
    private BigDecimal parseDecimal(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a decimal screen parameter, falling back on a default when it is absent, unparseable or
     * not strictly positive.
     *
     * @param raw          the raw value, may be null.
     * @param defaultValue the fallback value.
     * @return the parsed strictly-positive value, or the default.
     */
    private BigDecimal parseDecimalOrDefault(String raw, BigDecimal defaultValue) {
        BigDecimal parsed = parseDecimal(raw);
        return parsed != null && parsed.signum() > 0 ? parsed : defaultValue;
    }

    /**
     * Parses the lower bound: an ISO local date-time, or an ISO date taken at the start of the day.
     *
     * @param raw the raw bound, may be null.
     * @return the parsed instant, or null when absent or malformed.
     */
    private LocalDateTime parseLowerBound(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /**
     * Parses the upper bound: an ISO local date-time, or an ISO date taken at the end of the day.
     *
     * @param raw the raw bound, may be null.
     * @return the parsed instant, or null when absent or malformed.
     */
    private LocalDateTime parseUpperBound(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(value).atTime(23, 59, 59, 999_999_999);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /**
     * Trims a string to null when it is null or blank.
     *
     * @param value the value, may be null.
     * @return the trimmed value, or null when null or blank.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
