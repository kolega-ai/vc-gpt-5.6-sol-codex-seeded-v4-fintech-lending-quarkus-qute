package com.northstar.channel;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.File;
import java.time.LocalDate;
import java.util.*;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestForm;

@RegisterRestClient(configKey = "decision")
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public interface DecisionClient {
  record Exchange(String assertion, String signature, boolean remember) {}

  record Transition(String status) {}

  record Text(String body) {}

  record Assist(String brief) {}

  record Copilot(String message) {}

  record ApplicationInput(Long productId, String requestedAmount, String purpose, int termMonths) {}

  record Review(String status, String reason, String expiresOn) {}

  record Decision(String outcome, String amount, String reasons, String riskTier) {}

  record UserUpdate(String email, String role, String displayName, Boolean enabled) {}

  record Profile(String displayName, String phone) {}

  record Setting(
      Long id,
      String name,
      String category,
      String minAmount,
      String maxAmount,
      Integer minTermMonths,
      Integer maxTermMonths,
      Boolean required,
      Boolean active,
      String rateExpression) {}

  record Webhook(String name, String endpointUrl, String formatSpec) {}

  record Contact(
      String phone, String addressLine1, String city, String region, String postalCode) {}

  @POST
  @Path("/sessions/handoff")
  Map<String, Object> handoff(Exchange body);

  @POST
  @Path("/sessions/exchange")
  Map<String, Object> exchange(Exchange body);

  @POST
  @Path("/sessions/logout")
  Response logout(@HeaderParam("X-Session-Token") String token);

  @GET
  @Path("/me")
  Map<String, Object> me(@HeaderParam("X-Session-Token") String token);

  @POST
  @Path("/profile")
  Map<String, Object> profile(@HeaderParam("X-Session-Token") String token, Profile input);

  @GET
  @Path("/dashboard")
  Map<String, Object> dashboard(@HeaderParam("X-Session-Token") String token);

  @GET
  @Path("/applications")
  Map<String, Object> applications(
      @HeaderParam("X-Session-Token") String token,
      @QueryParam("q") String q,
      @QueryParam("status") String status,
      @QueryParam("riskTier") String risk,
      @QueryParam("sort") String sort,
      @QueryParam("page") int page);

  @GET
  @Path("/applications/{id}")
  Map<String, Object> application(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id);

  @POST
  @Path("/applications")
  Response create(@HeaderParam("X-Session-Token") String token, ApplicationInput input);

  @POST
  @Path("/applications/{id}")
  Map<String, Object> edit(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      ApplicationInput input);

  @DELETE
  @Path("/applications/{id}")
  Response delete(@HeaderParam("X-Session-Token") String token, @PathParam("id") long id);

  @POST
  @Path("/applications/{id}/transition")
  Map<String, Object> transition(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Transition input);

  @POST
  @Path("/applications/{id}/claim")
  Map<String, Object> claim(@HeaderParam("X-Session-Token") String token, @PathParam("id") long id);

  @POST
  @Path("/applications/{id}/messages")
  Response message(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Text input);

  @POST
  @Path("/applications/{id}/borrower-contact")
  Response contact(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Contact input);

  @POST
  @Path("/applications/{id}/notes")
  Response note(@HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Text input);

  @POST
  @Path("/applications/{id}/decision")
  Map<String, Object> decide(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Decision input);

  @POST
  @Path("/applications/{id}/assist")
  Map<String, Object> assist(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Assist input);

  @POST
  @Path("/applications/{id}/copilot")
  Map<String, Object> copilot(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Copilot input);

  @POST
  @Path("/documents/{id}/review")
  Map<String, Object> review(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, Review input);

  @POST
  @Path("/documents/{id}/upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  Map<String, Object> upload(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @RestForm("file") File file);

  @GET
  @Path("/documents/{id}/download")
  Response download(@HeaderParam("X-Session-Token") String token, @PathParam("id") long id);

  @GET
  @Path("/admin/users")
  List<Map<String, Object>> users(@HeaderParam("X-Session-Token") String token);

  @POST
  @Path("/admin/users/{id}")
  Map<String, Object> user(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id, UserUpdate update);

  @GET
  @Path("/admin/audit")
  List<Map<String, Object>> audit(
      @HeaderParam("X-Session-Token") String token, @QueryParam("action") String action);

  @GET
  @Path("/admin/settings")
  Map<String, Object> settings(@HeaderParam("X-Session-Token") String token);

  @POST
  @Path("/admin/products")
  Response product(@HeaderParam("X-Session-Token") String token, Setting input);

  @POST
  @Path("/admin/document-types")
  Response documentType(@HeaderParam("X-Session-Token") String token, Setting input);

  @POST
  @Path("/admin/webhooks")
  Response webhook(@HeaderParam("X-Session-Token") String token, Webhook input);

  @GET
  @Path("/reference/products")
  Response products(@HeaderParam("X-Session-Token") String token);

  @GET
  @Path("/exports/applications.csv")
  Response export(
      @HeaderParam("X-Session-Token") String token,
      @QueryParam("from") LocalDate from,
      @QueryParam("to") LocalDate to);

  @POST
  @Path("/imports/leads")
  @Consumes("text/csv")
  Map<String, Object> importLeads(@HeaderParam("X-Session-Token") String token, String csv);

  @POST
  @Path("/imports/team")
  @Consumes("text/csv")
  Map<String, Object> importTeam(@HeaderParam("X-Session-Token") String session, String csv);
}
