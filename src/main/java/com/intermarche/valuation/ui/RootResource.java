package com.intermarche.valuation.ui;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Redirects the application root to the administration entry point.
 * <p>
 * Hitting {@code /} lands on the offer list. Anonymous visitors are then sent to the
 * login page by form authentication, while an existing session goes straight to the
 * list: the entry point is the same either way, which avoids showing a login form to
 * someone who is already signed in.
 * <p>
 * The redirect itself is open to anonymous requests, since the target performs the
 * access check.
 */
@Path("/")
@ApplicationScoped
@RunOnVirtualThread
public class RootResource {

    /**
     * Redirects to the offer list.
     *
     * @return A 303 See Other response pointing at the entry point.
     */
    @GET
    @PermitAll
    public Response root() {
        return Response.seeOther(URI.create("/ui/offers")).build();
    }
}
