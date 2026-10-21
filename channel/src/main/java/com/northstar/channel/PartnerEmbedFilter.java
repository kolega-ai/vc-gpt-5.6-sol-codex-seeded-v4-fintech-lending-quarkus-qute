package com.northstar.channel;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

// Partner portals load the shared credit-file views inside their own dashboards, so the embed
// views answer the partner page origin directly.
@Provider
public class PartnerEmbedFilter implements ContainerResponseFilter {
  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    String origin = request.getHeaderString("Origin");
    String path = request.getUriInfo().getPath();
    if (origin != null
        && (path.startsWith("dashboard")
            || path.startsWith("applications")
            || path.startsWith("documents"))) {
      response.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
      response.getHeaders().putSingle("Access-Control-Allow-Credentials", "true");
      response.getHeaders().putSingle("Vary", "Origin");
    }
  }
}
