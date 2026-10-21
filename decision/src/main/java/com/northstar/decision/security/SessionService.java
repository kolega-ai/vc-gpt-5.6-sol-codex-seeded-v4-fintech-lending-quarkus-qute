package com.northstar.decision.security;

import com.northstar.decision.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.time.*;

@ApplicationScoped
public class SessionService {
  // Trusted embedded-channel devices that opt into "stay signed in" keep a long-lived session
  // instead of the normal 30-minute window.
  private static final Duration REMEMBER_TTL = Duration.ofDays(30);
  private final Crypto crypto;

  public SessionService(Crypto crypto) {
    this.crypto = crypto;
  }

  @Transactional
  public String exchange(String assertion, String signature, boolean remember) {
    if (assertion == null || !crypto.validHmac(assertion, signature))
      throw new NotAuthorizedException("Invalid channel assertion");
    String[] parts = assertion.split("\\|", 2);
    if (parts.length != 2) throw new NotAuthorizedException("Invalid channel assertion");
    long expires;
    try {
      expires = Long.parseLong(parts[1]);
    } catch (NumberFormatException e) {
      throw new NotAuthorizedException("Invalid channel assertion");
    }
    if (Instant.ofEpochSecond(expires).isBefore(Instant.now())
        || Instant.ofEpochSecond(expires).isAfter(Instant.now().plusSeconds(90)))
      throw new NotAuthorizedException("Expired channel assertion");
    AppUser user = AppUser.byEmail(parts[0]);
    if (user == null || !user.enabled) throw new NotAuthorizedException("Account unavailable");
    String raw = crypto.randomToken();
    UserSession session = new UserSession();
    session.tokenHash = crypto.sha256(raw);
    session.user = user;
    session.createdAt = Instant.now();
    session.expiresAt = Instant.now().plus(remember ? REMEMBER_TTL : Duration.ofMinutes(30));
    session.persist();
    return raw;
  }

  @Transactional
  public String handoff(String assertion) {
    try {
      com.nimbusds.jwt.JWTClaimsSet claims =
          com.nimbusds.jwt.JWTParser.parse(assertion).getJWTClaimsSet();
      AppUser user = AppUser.byEmail(claims.getSubject());
      if (user == null || !user.enabled) throw new NotAuthorizedException("Account unavailable");
      String raw = crypto.randomToken();
      UserSession session = new UserSession();
      session.tokenHash = crypto.sha256(raw);
      session.user = user;
      session.createdAt = Instant.now();
      session.expiresAt = Instant.now().plus(Duration.ofMinutes(30));
      session.persist();
      return raw;
    } catch (java.text.ParseException e) {
      throw new NotAuthorizedException("Invalid partner assertion");
    }
  }

  @Transactional
  public String issue(AppUser user) {
    String raw = crypto.randomToken();
    UserSession session = new UserSession();
    session.tokenHash = crypto.sha256(raw);
    session.user = user;
    session.createdAt = Instant.now();
    session.expiresAt = Instant.now().plus(Duration.ofMinutes(30));
    session.persist();
    return raw;
  }

  @Transactional
  public AppUser require(String raw) {
    if (raw == null || raw.isBlank()) throw new NotAuthorizedException("Session required");
    UserSession session = UserSession.findById(crypto.sha256(raw));
    if (session == null
        || session.revokedAt != null
        || session.expiresAt.isBefore(Instant.now())
        || !session.user.enabled) throw new NotAuthorizedException("Session expired");
    return AppUser.findById(session.user.id); // authoritative role/scope reload on every request
  }

  @Transactional
  public void elevate(AppUser user) {
    UserSession s = UserSession.find("user.id=?1 and revokedAt is null", user.id).firstResult();
    if (s != null) s.expiresAt = Instant.now().plus(Duration.ofHours(2));
  }

  @Transactional
  public void revoke(String raw) {
    UserSession s = raw == null ? null : UserSession.findById(crypto.sha256(raw));
    if (s != null) s.revokedAt = Instant.now();
  }
}
