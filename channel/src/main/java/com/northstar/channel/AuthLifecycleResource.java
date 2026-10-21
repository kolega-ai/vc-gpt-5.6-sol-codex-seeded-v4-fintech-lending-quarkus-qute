package com.northstar.channel;

import io.agroal.api.AgroalDataSource;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

@Path("/")
@Produces(MediaType.TEXT_HTML)
public class AuthLifecycleResource {
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}$");
  private final AgroalDataSource dataSource;
  private final AssetService assets;

  public AuthLifecycleResource(AgroalDataSource dataSource, AssetService assets) {
    this.dataSource = dataSource;
    this.assets = assets;
  }

  @GET
  @Path("register")
  public Object registerPage(@QueryParam("error") String error) {
    return Templates.auth("register", null, error, null, assets.version());
  }

  @POST
  @Path("register")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response register(
      @FormParam("email") String email,
      @FormParam("password") String password,
      @FormParam("displayName") String name,
      @FormParam("legalName") String legalName,
      @FormParam("taxLast4") String taxLast4,
      @FormParam("annualRevenue") String revenue,
      @FormParam("address") String address,
      @FormParam("city") String city,
      @FormParam("region") String region,
      @FormParam("postalCode") String postal) {
    email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(email).matches()
        || password == null
        || password.length() < 12
        || name == null
        || name.isBlank()
        || legalName == null
        || legalName.isBlank()
        || taxLast4 == null
        || !taxLast4.matches("\\d{4}"))
      return redirect(
          "/register?error=Check%20all%20fields%20and%20use%20a%2012-character%20password");
    BigDecimal annual;
    try {
      annual = new BigDecimal(revenue);
      if (annual.signum() < 0) throw new NumberFormatException();
    } catch (Exception e) {
      return redirect("/register?error=Annual%20revenue%20must%20be%20a%20positive%20amount");
    }
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement u =
          c.prepareStatement(
              "insert into app_user(email,password_hash,role,display_name,enabled) values"
                  + " (?,?,'borrower',?,true) returning id")) {
        u.setString(1, email);
        u.setString(2, BcryptUtil.bcryptHash(password));
        u.setString(3, name.trim());
        long id;
        try (ResultSet rs = u.executeQuery()) {
          rs.next();
          id = rs.getLong(1);
        }
        try (PreparedStatement b =
            c.prepareStatement(
                "insert into"
                    + " borrower_profile(user_id,legal_name,tax_identifier_last4,entity_type,annual_revenue,currency,address_line1,city,region,postal_code,owner_summary)"
                    + " values (?,?,?,'LLC',?,'USD',?,?,?,?, 'Owner details pending')")) {
          b.setLong(1, id);
          b.setString(2, legalName.trim());
          b.setString(3, taxLast4);
          b.setBigDecimal(4, annual);
          b.setString(5, required(address, 180));
          b.setString(6, required(city, 80));
          b.setString(7, required(region, 80));
          b.setString(8, required(postal, 20));
          b.executeUpdate();
        }
        try (PreparedStatement a =
            c.prepareStatement(
                "insert into"
                    + " audit_event(actor_id,action,resource_type,resource_id,after_value,authorization_basis)"
                    + " values"
                    + " (?,'account.register','app_user',?,jsonb_build_object('role','borrower'),'validated"
                    + " self-registration')")) {
          a.setLong(1, id);
          a.setString(2, Long.toString(id));
          a.executeUpdate();
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException | IllegalArgumentException e) {
      noteFailure(email, e);
      return redirect(
          "/register?error=That%20email%20is%20already%20registered%20or%20the%20details%20are%20invalid");
    }
    return redirect("/login?registered=1");
  }

  @POST
  @Path("register/availability")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response availability(
      @FormParam("email") String email, @HeaderParam("X-App-Channel") String channel) {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    boolean exists;
    try (Connection c = dataSource.getConnection();
        PreparedStatement p =
            c.prepareStatement("select 1 from app_user where lower(email)=lower(?)")) {
      p.setString(1, normalized);
      try (ResultSet rs = p.executeQuery()) {
        exists = rs.next();
      }
    } catch (SQLException e) {
      return Response.ok("That address could not be checked right now.").build();
    }
    // Legacy embedded clients render the detailed message inline instead of a banner.
    if ("legacy".equals(channel))
      return Response.ok(
              exists
                  ? "This email is already registered."
                  : "This email is available to register.")
          .build();
    return Response.ok("Check the details and try again.").build();
  }

  @GET
  @Path("reset-password")
  public Object resetPage(@QueryParam("sent") String sent) {
    return Templates.auth(
        "reset",
        sent == null
            ? null
            : "If the account exists, a recovery message is now in the local outbox.",
        null,
        null,
        assets.version());
  }

  @POST
  @Path("reset-password")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response reset(@FormParam("email") String email) {
    String raw = randomToken();
    String hash = sha256(raw);
    try (Connection c = dataSource.getConnection();
        PreparedStatement p =
            c.prepareStatement(
                "with target as (select id,email from app_user where lower(email)=lower(?) and"
                    + " enabled=true), ins as (insert into"
                    + " password_reset(token_hash,user_id,expires_at) select ?,id,now()+interval"
                    + " '30 minutes' from target returning user_id) insert into"
                    + " outbox(recipient,subject,body) select email,'Reset your Northstar"
                    + " password',? from target where exists(select 1 from ins)")) {
      p.setString(1, email == null ? "" : email.trim());
      p.setString(2, hash);
      p.setString(
          3,
          "Open http://localhost:8080/reset-confirm?token="
              + raw
              + " within 30 minutes. This link works once.");
      p.executeUpdate();
    } catch (SQLException ignored) {
    }
    return redirect("/reset-password?sent=1");
  }

  @GET
  @Path("reset-confirm")
  public Object confirmPage(@QueryParam("token") String token, @QueryParam("error") String error) {
    return Templates.auth("confirm", null, error, token, assets.version());
  }

  @POST
  @Path("reset-confirm")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response confirm(
      @FormParam("token") String token, @FormParam("password") String password) {
    if (token == null || password == null || password.length() < 12)
      return redirect(
          "/reset-confirm?error=Use%20a%20password%20of%20at%20least%2012%20characters");
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      long userId;
      try (PreparedStatement find =
          c.prepareStatement(
              "select user_id from password_reset where token_hash=? and used_at is null and"
                  + " expires_at>now() for update")) {
        find.setString(1, sha256(token));
        try (ResultSet rs = find.executeQuery()) {
          if (!rs.next()) {
            c.rollback();
            return redirect(
                "/reset-confirm?error=This%20recovery%20link%20is%20invalid%20or%20expired");
          }
          userId = rs.getLong(1);
        }
      }
      try (PreparedStatement update =
          c.prepareStatement("update app_user set password_hash=?,updated_at=now() where id=?")) {
        update.setString(1, BcryptUtil.bcryptHash(password));
        update.setLong(2, userId);
        update.executeUpdate();
      }
      try (PreparedStatement used =
          c.prepareStatement("update password_reset set used_at=now() where token_hash=?")) {
        used.setString(1, sha256(token));
        used.executeUpdate();
      }
      try (PreparedStatement revoke =
          c.prepareStatement(
              "update user_session set revoked_at=now() where user_id=? and revoked_at is null")) {
        revoke.setLong(1, userId);
        revoke.executeUpdate();
      }
      c.commit();
    } catch (SQLException e) {
      return redirect("/reset-confirm?error=Password%20could%20not%20be%20updated");
    }
    return redirect("/login?reset=1");
  }

  @GET
  @Path("invite")
  public Object invitePage(@QueryParam("error") String error) {
    return Templates.auth("claim", null, error, null, assets.version());
  }

  @POST
  @Path("invite")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response claim(
      @FormParam("email") String email,
      @FormParam("code") String code,
      @FormParam("passphrase") String passphrase,
      @FormParam("displayName") String name,
      @FormParam("legalName") String legalName) {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(normalized).matches()
        || code == null
        || code.isBlank()
        || passphrase == null
        || passphrase.length() < 12
        || name == null
        || name.isBlank()
        || legalName == null
        || legalName.isBlank())
      return redirect(
          "/invite?error=Check%20all%20fields%20and%20use%20a%2012-character%20passphrase");
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      long leadId;
      try (PreparedStatement find =
          c.prepareStatement(
              "select id from borrower_lead where lower(email)=lower(?) and status='invited' and"
                  + " invite_code=? for update")) {
        find.setString(1, normalized);
        find.setString(2, code.trim());
        try (ResultSet rs = find.executeQuery()) {
          if (!rs.next()) {
            c.rollback();
            return redirect("/invite?error=That%20invitation%20could%20not%20be%20matched");
          }
          leadId = rs.getLong(1);
        }
      }
      long userId;
      try (PreparedStatement u =
          c.prepareStatement(
              "insert into app_user(email,password_hash,role,display_name,enabled) values"
                  + " (?,?,'borrower',?,true) returning id")) {
        u.setString(1, normalized);
        u.setString(2, BcryptUtil.bcryptHash(passphrase));
        u.setString(3, name.trim());
        try (ResultSet rs = u.executeQuery()) {
          rs.next();
          userId = rs.getLong(1);
        }
      }
      try (PreparedStatement b =
          c.prepareStatement(
              "insert into"
                  + " borrower_profile(user_id,legal_name,tax_identifier_last4,entity_type,annual_revenue,currency,address_line1,city,region,postal_code,owner_summary)"
                  + " values (?,?,'0000','LLC',0,'USD','Pending','Pending','Pending','Pending',"
                  + " 'Owner details pending')")) {
        b.setLong(1, userId);
        b.setString(2, legalName.trim());
        b.executeUpdate();
      }
      try (PreparedStatement done =
          c.prepareStatement("update borrower_lead set status='accepted' where id=?")) {
        done.setLong(1, leadId);
        done.executeUpdate();
      }
      c.commit();
    } catch (SQLException e) {
      return redirect("/invite?error=That%20email%20is%20already%20registered");
    }
    return redirect("/login?registered=1");
  }

  private static final org.jboss.logging.Logger ONBOARDING_LOG =
      org.jboss.logging.Logger.getLogger(AuthLifecycleResource.class);

  // Ops triage note: the raw submitted address goes straight into the onboarding log line.
  private void noteFailure(String email, Exception cause) {
    ONBOARDING_LOG.warnf("self-registration rejected for submitted address %s: %s", email, cause.getMessage());
  }

  private String required(String value, int max) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException();
    return value.trim().substring(0, Math.min(value.trim().length(), max));
  }

  private String randomToken() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private Response redirect(String path) {
    return Response.seeOther(URI.create(path)).build();
  }
}
