package com.northstar.core;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/core")
@Produces(MediaType.APPLICATION_JSON)
public class CoreMockResource {
  @ConfigProperty(name = "security.core-key")
  String key;

  @GET
  @Path("/business/{externalRef}")
  public Map<String, Object> business(
      @HeaderParam("Authorization") String authorization,
      @PathParam("externalRef") String externalRef) {
    Scope scope = verify(authorization);
    if (!scope.resource().equals(externalRef)) throw new NotFoundException();
    return Map.of(
        "externalReference",
        externalRef,
        "kybStatus",
        "verified",
        "sanctionsMatches",
        0,
        "source",
        "local-core-mock");
  }

  private Scope verify(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new NotAuthorizedException("Scope token required");
    String token = authorization.substring(7);
    String[] p = token.split("\\.");
    if (p.length != 2) throw new NotAuthorizedException("Invalid token");
    byte[] expected = hmac(p[0]);
    byte[] provided;
    try {
      provided = Base64.getUrlDecoder().decode(p[1]);
    } catch (IllegalArgumentException e) {
      throw new NotAuthorizedException("Invalid token");
    }
    if (!MessageDigest.isEqual(expected, provided))
      throw new NotAuthorizedException("Invalid token");
    String payload = new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8);
    String[] v = payload.split("\\|", 3);
    if (v.length != 3 || Instant.ofEpochSecond(Long.parseLong(v[2])).isBefore(Instant.now()))
      throw new NotAuthorizedException("Expired token");
    return new Scope(v[0], v[1]);
  }

  private byte[] hmac(String value) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return m.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  record Scope(String action, String resource) {}
}
