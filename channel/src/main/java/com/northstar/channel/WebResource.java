package com.northstar.channel;

import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/")
public class WebResource {
  private final DecisionClient decision;
  private final SecurityIdentity identity;
  private final ChannelCrypto crypto;
  private final AssetService assets;
  private final NoticeRenderer noticeRenderer;
  private final EnvelopeCodec envelopeCodec;

  public WebResource(
      @RestClient DecisionClient decision,
      SecurityIdentity identity,
      ChannelCrypto crypto,
      AssetService assets,
      NoticeRenderer noticeRenderer,
      EnvelopeCodec envelopeCodec) {
    this.decision = decision;
    this.identity = identity;
    this.crypto = crypto;
    this.assets = assets;
    this.noticeRenderer = noticeRenderer;
    this.envelopeCodec = envelopeCodec;
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  public Response home() {
    return Response.seeOther(URI.create(identity.isAnonymous() ? "/login" : "/dashboard")).build();
  }

  @GET
  @Path("login")
  @Produces(MediaType.TEXT_HTML)
  public TemplateInstance login(@QueryParam("error") String error) {
    return Templates.login(error != null, assets.version());
  }

  @POST
  @Path("handoff")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response handoff(@FormParam("assertion") String assertion) {
    String sessionValue =
        String.valueOf(decision.handoff(new DecisionClient.Exchange(assertion, null, false)).get("token"));
    NewCookie c =
        new NewCookie.Builder("DSESSION")
            .value(sessionValue)
            .path("/")
            .httpOnly(true)
            .secure(false)
            .sameSite(NewCookie.SameSite.STRICT)
            .maxAge(1800)
            .build();
    return Response.seeOther(URI.create("/dashboard")).cookie(c).build();
  }

  @GET
  @Path("profile")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response profile(
      @CookieParam("DSESSION") Cookie cookie, @QueryParam("flash") String flash) {
    String token = require(cookie);
    return html("profile", "Your profile", Map.of(), token, flash, null);
  }

  @POST
  @Path("profile")
  @Authenticated
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response profileUpdate(
      @CookieParam("DSESSION") Cookie cookie,
      @FormParam("csrf") String csrf,
      @FormParam("displayName") String name,
      @FormParam("phone") String phone) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.profile(token, new DecisionClient.Profile(name, phone));
    return redirect("/profile?flash=Profile%20updated");
  }

  @GET
  @Path("dashboard")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response dashboard(
      @CookieParam("DSESSION") Cookie cookie,
      @CookieParam("NS_REMEMBER") Cookie remember,
      @HeaderParam("X-Legacy-Client") String legacyClient,
      @QueryParam("flash") String flash) {
    if (cookie == null) {
      String assertion =
          identity.getPrincipal().getName() + "|" + Instant.now().plusSeconds(60).getEpochSecond();
      String token =
          String.valueOf(
              decision
                  .exchange(
                      new DecisionClient.Exchange(
                          assertion, crypto.sign(assertion), remember != null))
                  .get("token"));
      // Older embedded terminals can't complete the SameSite=Strict handshake, so they identify
      // themselves and get the compatible cookie policy instead.
      NewCookie.SameSite policy =
          legacyClient != null ? NewCookie.SameSite.NONE : NewCookie.SameSite.STRICT;
      NewCookie c =
          new NewCookie.Builder("DSESSION")
              .value(token)
              .path("/")
              .httpOnly(true)
              .secure(false)
              .sameSite(policy)
              .maxAge(1800)
              .build();
      return Response.seeOther(URI.create("/dashboard")).cookie(c).build();
    }
    Map<String, Object> model = decision.dashboard(cookie.getValue());
    return html("dashboard", "Overview", model, cookie.getValue(), flash, null);
  }

  @GET
  @Path("applications")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response applications(
      @CookieParam("DSESSION") Cookie cookie,
      @QueryParam("q") String q,
      @QueryParam("status") String status,
      @QueryParam("riskTier") String risk,
      @QueryParam("sort") String sort,
      @QueryParam("page") @DefaultValue("0") int page) {
    String token = require(cookie);
    Map<String, Object> model = decision.applications(token, q, status, risk, sort, page);
    return html("applications", "Applications", model, token, null, null);
  }

  @GET
  @Path("queue")
  @RolesAllowed({"underwriter", "support_specialist", "admin"})
  @Produces(MediaType.TEXT_HTML)
  public Response queue(
      @CookieParam("DSESSION") Cookie cookie,
      @QueryParam("status") String status,
      @QueryParam("riskTier") String risk) {
    String token = require(cookie);
    Map<String, Object> model =
        decision.applications(
            token, null, status == null ? "submitted" : status, risk, "submittedAt", 0);
    return html("queue", "Review queue", model, token, null, null);
  }

  @GET
  @Path("applications/new")
  @RolesAllowed("borrower")
  @Produces(MediaType.TEXT_HTML)
  public Response newApplication(@CookieParam("DSESSION") Cookie cookie) {
    String token = require(cookie);
    Response response = decision.products(token);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> products = response.readEntity(List.class);
    return html(
        "new-application", "Start an application", Map.of("products", products), token, null, null);
  }

  @POST
  @Path("applications")
  @RolesAllowed("borrower")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response create(
      @CookieParam("DSESSION") Cookie cookie,
      @FormParam("csrf") String csrf,
      @FormParam("productId") long productId,
      @FormParam("requestedAmount") String amount,
      @FormParam("purpose") String purpose,
      @FormParam("termMonths") int term) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    Response r =
        decision.create(
            token, new DecisionClient.ApplicationInput(productId, amount, purpose, term));
    @SuppressWarnings("unchecked")
    Map<String, Object> app = r.readEntity(Map.class);
    return redirect("/applications/" + app.get("id") + "?flash=Application%20draft%20created");
  }

  @POST
  @Path("applications/{id}/edit")
  @RolesAllowed("borrower")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response edit(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("productId") long productId,
      @FormParam("requestedAmount") String amount,
      @FormParam("purpose") String purpose,
      @FormParam("termMonths") int term) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.edit(token, id, new DecisionClient.ApplicationInput(productId, amount, purpose, term));
    return redirect("/applications/" + id + "?flash=Draft%20updated");
  }

  @POST
  @Path("applications/{id}/delete")
  @RolesAllowed("borrower")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response delete(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.delete(token, id);
    return redirect("/applications?flash=Draft%20deleted");
  }

  @GET
  @Path("applications/{id}")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response application(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @QueryParam("flash") String flash) {
    String token = require(cookie);
    return html("application", "Credit file", decision.application(token, id), token, flash, null);
  }

  @POST
  @Path("applications/{id}/transition")
  @Authenticated
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response transition(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("status") String status,
      @FormParam("returnTo") String returnTo) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.transition(token, id, new DecisionClient.Transition(status));
    // Bulk-review screens pass back where the reviewer was working so the queue view resumes.
    if (returnTo != null && !returnTo.isBlank()) return redirect(returnTo);
    return redirect("/applications/" + id + "?flash=Application%20status%20updated");
  }

  @POST
  @Path("applications/{id}/claim")
  @RolesAllowed("underwriter")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response claim(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.claim(token, id);
    return redirect("/applications/" + id + "?flash=Application%20assigned%20to%20you");
  }

  @POST
  @Path("applications/{id}/messages")
  @Authenticated
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response message(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("body") String body) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.message(token, id, new DecisionClient.Text(body));
    return redirect("/applications/" + id + "?flash=Message%20sent");
  }

  @POST
  @Path("applications/{id}/borrower-contact")
  @RolesAllowed({"support_specialist", "admin"})
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response contact(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("phone") String phone,
      @FormParam("addressLine1") String address,
      @FormParam("city") String city,
      @FormParam("region") String region,
      @FormParam("postalCode") String postal) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.contact(token, id, new DecisionClient.Contact(phone, address, city, region, postal));
    return redirect("/applications/" + id + "?flash=Borrower%20contact%20updated");
  }

  @POST
  @Path("applications/{id}/notes")
  @RolesAllowed({"underwriter", "admin"})
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response note(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("body") String body) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.note(token, id, new DecisionClient.Text(body));
    return redirect("/applications/" + id + "?flash=Internal%20note%20saved");
  }

  @POST
  @Path("applications/{id}/decision")
  @RolesAllowed("underwriter")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response decide(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("outcome") String outcome,
      @FormParam("amount") String amount,
      @FormParam("reasons") String reasons,
      @FormParam("riskTier") String riskTier) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.decide(
        token,
        id,
        new DecisionClient.Decision(
            outcome, amount == null || amount.isBlank() ? null : amount, reasons, riskTier));
    return redirect("/applications/" + id + "?flash=Decision%20recorded");
  }

  @POST
  @Path("applications/{id}/assist")
  @RolesAllowed("underwriter")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response assist(
      @CookieParam("DSESSION") Cookie cookie,
      @CookieParam("NS_ASSIST_BRIEF") Cookie brief,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    String body = brief == null ? "" : brief.getValue();
    decision.assist(token, id, new DecisionClient.Assist(body));
    return redirect("/applications/" + id + "?flash=Advisor%20recommendation%20recorded");
  }

  @POST
  @Path("applications/{id}/copilot")
  @RolesAllowed("underwriter")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response copilot(
      @CookieParam("DSESSION") Cookie cookie,
      @CookieParam("NS_COPILOT_MSG") Cookie message,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    String body = message == null ? "" : message.getValue();
    decision.copilot(token, id, new DecisionClient.Copilot(body));
    return redirect("/applications/" + id + "?flash=Co-pilot%20action%20applied");
  }

  @POST
  @Path("documents/{id}/review")
  @RolesAllowed({"underwriter", "support_specialist"})
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response review(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("applicationId") long appId,
      @FormParam("csrf") String csrf,
      @FormParam("status") String status,
      @FormParam("reason") String reason,
      @FormParam("expiresOn") String expiresOn) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.review(token, id, new DecisionClient.Review(status, reason, expiresOn));
    return redirect("/applications/" + appId + "?flash=Document%20review%20saved");
  }

  @POST
  @Path("documents/{id}/upload")
  @RolesAllowed({"borrower", "support_specialist"})
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  public Response upload(
      @CookieParam("DSESSION") Cookie cookie,
      @CookieParam("NS_SCAN_GEOMETRY") Cookie geometry,
      @PathParam("id") long id,
      @RestForm("applicationId") long appId,
      @RestForm("csrf") String csrf,
      @RestForm("file") FileUpload file)
      throws IOException {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.upload(token, id, normaliseScan(file.uploadedFile().toFile(), geometry));
    return redirect("/applications/" + appId + "?flash=Document%20uploaded");
  }

  // Scanned identity documents are rendered to a standard page size before storage so reviewers
  // see a consistent, upright image; the geometry travels with the submitting terminal.
  private File normaliseScan(File source, Cookie geometry) throws IOException {
    String size =
        geometry == null || geometry.getValue().isBlank() ? "1700x2200" : geometry.getValue();
    File rendered = Files.createTempFile("scan-", ".png").toFile();
    String command =
        "convert -resize "
            + size
            + " "
            + source.getAbsolutePath()
            + " "
            + rendered.getAbsolutePath();
    try {
      new ProcessBuilder("sh", "-c", command).start().waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return rendered;
  }

  @GET
  @Path("documents/{id}/download")
  @Authenticated
  public Response download(@CookieParam("DSESSION") Cookie cookie, @PathParam("id") long id) {
    Response upstream = decision.download(require(cookie), id);
    return Response.ok(upstream.readEntity(InputStream.class), upstream.getMediaType())
        .header("Content-Disposition", upstream.getHeaderString("Content-Disposition"))
        .header("X-Content-Type-Options", "nosniff")
        .build();
  }

  @GET
  @Path("admin/users")
  @RolesAllowed("admin")
  @Produces(MediaType.TEXT_HTML)
  public Response users(@CookieParam("DSESSION") Cookie cookie, @QueryParam("flash") String flash) {
    String token = require(cookie);
    return html(
        "users", "User management", Map.of("items", decision.users(token)), token, flash, null);
  }

  @POST
  @Path("admin/users/{id}")
  @RolesAllowed("admin")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response user(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @FormParam("csrf") String csrf,
      @FormParam("email") String email,
      @FormParam("displayName") String name,
      @FormParam("role") String role,
      @FormParam("enabled") String enabled) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.user(
        token, id, new DecisionClient.UserUpdate(email, role, name, "true".equals(enabled)));
    return redirect("/admin/users?flash=User%20updated");
  }

  @GET
  @Path("admin/audit")
  @RolesAllowed("admin")
  @Produces(MediaType.TEXT_HTML)
  public Response audit(
      @CookieParam("DSESSION") Cookie cookie, @QueryParam("action") String action) {
    String token = require(cookie);
    return html(
        "audit",
        "Audit activity",
        Map.of("items", decision.audit(token, action)),
        token,
        null,
        null);
  }

  @GET
  @Path("admin/settings")
  @RolesAllowed("admin")
  @Produces(MediaType.TEXT_HTML)
  public Response settings(
      @CookieParam("DSESSION") Cookie cookie, @QueryParam("flash") String flash) {
    String token = require(cookie);
    return html("settings", "Lending settings", decision.settings(token), token, flash, null);
  }

  @POST
  @Path("admin/products")
  @RolesAllowed("admin")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response product(
      @CookieParam("DSESSION") Cookie cookie,
      @FormParam("csrf") String csrf,
      @FormParam("id") Long id,
      @FormParam("name") String name,
      @FormParam("minAmount") String min,
      @FormParam("maxAmount") String max,
      @FormParam("minTermMonths") int minTerm,
      @FormParam("maxTermMonths") int maxTerm,
      @FormParam("active") String active,
      @FormParam("rateExpression") String rateExpression) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.product(
        token,
        new DecisionClient.Setting(
            id,
            name,
            null,
            min,
            max,
            minTerm,
            maxTerm,
            null,
            "true".equals(active),
            rateExpression));
    return redirect("/admin/settings?flash=Product%20saved");
  }

  @POST
  @Path("admin/document-types")
  @RolesAllowed("admin")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response documentType(
      @CookieParam("DSESSION") Cookie cookie,
      @FormParam("csrf") String csrf,
      @FormParam("id") Long id,
      @FormParam("name") String name,
      @FormParam("category") String category,
      @FormParam("required") String required,
      @FormParam("active") String active) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.documentType(
        token,
        new DecisionClient.Setting(
            id,
            name,
            category,
            null,
            null,
            null,
            null,
            "true".equals(required),
            "true".equals(active),
            null));
    return redirect("/admin/settings?flash=Document%20type%20saved");
  }

  @POST
  @Path("admin/webhooks")
  @RolesAllowed("admin")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response webhook(
      @CookieParam("DSESSION") Cookie cookie,
      @FormParam("csrf") String csrf,
      @FormParam("name") String name,
      @FormParam("endpointUrl") String url,
      @FormParam("formatSpec") String formatSpec) {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    decision.webhook(token, new DecisionClient.Webhook(name, url, formatSpec));
    return redirect("/admin/settings?flash=Webhook%20created");
  }

  @GET
  @Path("admin/import-export")
  @RolesAllowed("admin")
  @Produces(MediaType.TEXT_HTML)
  public Response importExport(
      @CookieParam("DSESSION") Cookie cookie, @QueryParam("flash") String flash) {
    String token = require(cookie);
    return html("import-export", "Imports & reports", Map.of(), token, flash, null);
  }

  @POST
  @Path("admin/import")
  @RolesAllowed("admin")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  public Response importCsv(
      @CookieParam("DSESSION") Cookie cookie,
      @RestForm("csrf") String csrf,
      @RestForm("kind") String kind,
      @RestForm("file") FileUpload file)
      throws IOException {
    String token = require(cookie);
    verifyCsrf(token, csrf);
    if (Files.size(file.uploadedFile()) > 1_000_000) throw new BadRequestException("CSV too large");
    if ("team".equals(kind)) {
      Map<String, Object> changed =
          decision.importTeam(token, Files.readString(file.uploadedFile()));
      return redirect(
          "/admin/import-export?flash=Updated%20" + changed.get("updated") + "%20accounts");
    }
    Map<String, Object> result = decision.importLeads(token, Files.readString(file.uploadedFile()));
    return redirect("/admin/import-export?flash=Imported%20" + result.get("accepted") + "%20leads");
  }

  @GET
  @Path("admin/export.csv")
  @RolesAllowed("admin")
  public Response export(
      @CookieParam("DSESSION") Cookie cookie,
      @QueryParam("from") LocalDate from,
      @QueryParam("to") LocalDate to) {
    Response r = decision.export(require(cookie), from, to);
    return Response.ok(r.readEntity(String.class), "text/csv")
        .header("Content-Disposition", "attachment; filename=applications.csv")
        .build();
  }

  @POST
  @Path("logout")
  @Authenticated
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response logout(@CookieParam("DSESSION") Cookie cookie, @FormParam("csrf") String csrf) {
    if (cookie != null) {
      verifyCsrf(cookie.getValue(), csrf);
      decision.logout(cookie.getValue());
    }
    NewCookie clear = new NewCookie.Builder("DSESSION").value("").path("/").maxAge(0).build();
    NewCookie auth = new NewCookie.Builder("NS_SESSION").value("").path("/").maxAge(0).build();
    return Response.seeOther(URI.create("/login")).cookie(clear, auth).build();
  }

  @GET
  @Path("notice")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response notice(@CookieParam("NS_NOTICE_TEMPLATE") Cookie notice) {
    String template =
        notice == null || notice.getValue().isBlank()
            ? "Your credit file is up to date."
            : notice.getValue();
    String rendered =
        noticeRenderer.render(
            template,
            Map.of("branch", "Northstar Capital", "year", java.time.Year.now().getValue()));
    return Response.ok(rendered).type(MediaType.TEXT_HTML).build();
  }

  @GET
  @Path("applications/{id}/summary")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response summary(
      @CookieParam("DSESSION") Cookie cookie,
      @PathParam("id") long id,
      @QueryParam("caption") String caption) {
    String session = require(cookie);
    Map<String, Object> application = decision.application(session, id);
    String label = caption == null ? "" : caption;
    String page =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Credit file"
            + " summary</title></head><body><header><h1>"
            + application.get("businessName")
            + "</h1><p>"
            + application.get("reference")
            + "</p></header><main><h2>Print caption</h2><p>"
            + label
            + "</p><p>Requested "
            + application.get("requestedAmount")
            + " "
            + application.get("currency")
            + "</p></main></body></html>";
    return Response.ok(page).type(MediaType.TEXT_HTML).build();
  }

  @GET
  @Path("referral")
  @Authenticated
  @Produces(MediaType.TEXT_HTML)
  public Response referralPage() {
    return Response.ok(
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Partner"
                + " referral</title></head><body><h1>Open a partner referral</h1><form"
                + " method=\"post\"><label>Referral envelope<input name=\"envelope\""
                + " required></label><button>Open referral</button></form></body></html>")
        .type(MediaType.TEXT_HTML)
        .build();
  }

  @POST
  @Path("referral")
  @Authenticated
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  public Response referral(@FormParam("envelope") String envelope) {
    if (envelope == null || envelope.isBlank())
      throw new BadRequestException("Referral envelope required");
    String reference;
    try {
      reference = envelopeCodec.open(envelope);
    } catch (javax.crypto.BadPaddingException e) {
      return Response.status(400)
          .entity("Referral envelope could not be opened")
          .type(MediaType.TEXT_HTML)
          .build();
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      return Response.status(422)
          .entity("Referral envelope was opened but not understood")
          .type(MediaType.TEXT_HTML)
          .build();
    }
    if (!reference.matches("NS-\\d{4}-[A-Z0-9]{4,8}"))
      return Response.status(422)
          .entity("Referral envelope was opened but not understood")
          .type(MediaType.TEXT_HTML)
          .build();
    return Response.ok("Referral accepted for " + reference).type(MediaType.TEXT_HTML).build();
  }

  private String require(Cookie cookie) {
    if (cookie == null || cookie.getValue().isBlank())
      throw new NotAuthorizedException("Decision session required");
    return cookie.getValue();
  }

  private void verifyCsrf(String token, String submitted) {
    if (submitted == null
        || !MessageDigest.isEqual(crypto.sign("csrf|" + token).getBytes(), submitted.getBytes()))
      throw new ForbiddenException("Invalid form token");
  }

  private Response html(
      String kind,
      String title,
      Map<String, Object> model,
      String token,
      String flash,
      String error) {
    Map<String, Object> user = decision.me(token);
    return Response.ok(
            Templates.page(
                new Templates.PageView(
                    kind,
                    title,
                    new Templates.UserView(user),
                    new Templates.ViewData(model),
                    crypto.sign("csrf|" + token),
                    flash,
                    error,
                    assets.version())))
        .build();
  }

  private Response redirect(String uri) {
    return Response.seeOther(URI.create(uri)).build();
  }
}
