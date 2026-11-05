package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.EgalimCeiling;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Read-only screen listing the administered EGAlim ceilings.
 * <p>
 * The {@link EgalimCeiling} referential maps a regime code to the number of percent the store
 * node states for it today. It reaches the engine as the {@code EGALIM_REGIMES} feed and is
 * administered by flux, never here: the day the law moves (a loi Travert raising the DPH
 * ceiling from 34% to 40%), the operator must be able to check what the engine actually
 * applies. This screen is that window — a plain consultation, open to the VIEWER role like the
 * valuation browser, carrying no edit control on purpose.
 * <p>
 * It reuses the house {@link ListView} view model so its markup is the same table the other
 * consultations render. The referential holds a handful of rows, so the whole set is shown on
 * a single page ordered by regime code.
 */
@Path("/ui/egalim-ceilings")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class EgalimCeilingUiResource {

    private static final Logger LOGGER = Logger.getLogger(EgalimCeilingUiResource.class);

    /**
     * Base path of the screen, used to build the view model links.
     */
    private static final String BASE_PATH = "/ui/egalim-ceilings";

    /**
     * Sort key naming the natural order of the referential, the regime code.
     */
    private static final String SORT_CODE = "regimeCode";

    /**
     * The whole referential fits on one page; this bound is large enough never to split it.
     */
    private static final int PAGE_SIZE = 1000;

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     * <p>
     * Templates live under {@code src/main/resources/templates/EgalimCeilingUiResource}.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the EGAlim ceilings list screen.
         *
         * @param view The view model carrying the ordered rows.
         * @return The template instance to render.
         */
        public static native TemplateInstance list(ListView<EgalimCeiling> view);
    }

    /**
     * Displays every administered EGAlim ceiling, ordered by regime code.
     *
     * @return The rendered list screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list() {
        LOGGER.debug("Entering method list");
        List<EgalimCeiling> rows = EgalimCeiling.listAllOrdered();
        ListView<EgalimCeiling> view = new ListView<>(rows, BASE_PATH, Map.of(),
                SORT_CODE, false, 1, 1, rows.size(), PAGE_SIZE, "regime",
                null, true, false);
        LOGGER.debug("Exiting method list");
        return Templates.list(view);
    }
}
