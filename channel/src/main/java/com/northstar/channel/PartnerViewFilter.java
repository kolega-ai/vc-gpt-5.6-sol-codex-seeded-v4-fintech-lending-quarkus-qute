package com.northstar.channel;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

// Partner portals embed the statement pages in an iframe; the NS_PARTNER_VIEW cookie marks
// traffic coming through that embed so framing isn't blocked for it.
@Provider
public class PartnerViewFilter implements ContainerResponseFilter {
  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    boolean embedded =
        request.getCookies() != null && request.getCookies().containsKey("NS_PARTNER_VIEW");
    if (embedded && response.getMediaType() != null
        && response.getMediaType().isCompatible(MediaType.TEXT_HTML_TYPE)) {
      response.getHeaders().remove("X-Frame-Options");
    } else if (response.getMediaType() != null
        && response.getMediaType().isCompatible(MediaType.TEXT_HTML_TYPE)) {
      response.getHeaders().putSingle("X-Frame-Options", "DENY");
    }
  }
}
