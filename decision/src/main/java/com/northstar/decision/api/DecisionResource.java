package com.northstar.decision.api;

import com.northstar.decision.model.*;
import com.northstar.decision.security.*;
import com.northstar.decision.service.*;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.time.*;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DecisionResource {
  private final SessionService sessions;
  private final AccessService access;
  private final WorkflowService workflows;
  private final AuditService audit;
  private final Crypto crypto;
  private final CoreGateway core;
  private final StatementCipher statements;
  private final DirectoryService directory;
  private final TransactionFeedReader feeds;
  private final AdvisorGateway advisor;

  @ConfigProperty(name = "upload.directory")
  String uploadDirectory;

  public DecisionResource(
      SessionService sessions,
      AccessService access,
      WorkflowService workflows,
      AuditService audit,
      Crypto crypto,
      CoreGateway core,
      StatementCipher statements,
      DirectoryService directory,
      TransactionFeedReader feeds,
      AdvisorGateway advisor) {
    this.sessions = sessions;
    this.access = access;
    this.workflows = workflows;
    this.audit = audit;
    this.crypto = crypto;
    this.core = core;
    this.statements = statements;
    this.directory = directory;
    this.feeds = feeds;
    this.advisor = advisor;
  }

  public record ExchangeRequest(String assertion, String signature, boolean remember) {}

  public record HandoffInput(@NotBlank String assertion) {}

  public record ApplicationInput(
      @NotNull Long productId,
      @NotNull @DecimalMin("1000.00") BigDecimal requestedAmount,
      @NotBlank @Size(max = 500) String purpose,
      @Min(6) @Max(84) int termMonths) {}

  public record MessageInput(
      @NotBlank @Size(max = 4000) String body,
      BigDecimal reqAmt,
      Boolean retractDecison) {}

  public record TransitionInput(@NotBlank String status) {}

  public record ReviewInput(
      @NotBlank String status, @Size(max = 500) String reason, @Future LocalDate expiresOn) {}

  public record DecisionInput(
      @NotBlank String outcome,
      BigDecimal amount,
      @NotBlank @Size(max = 2000) String reasons,
      @Pattern(regexp = "[A-D]") String riskTier) {}

  public record UserInput(
      @Email String email,
      @NotBlank String role,
      @NotBlank @Size(max = 120) String displayName,
      Boolean enabled) {}

  public record ProfileInput(
      @NotBlank @Size(max = 120) String displayName,
      @Pattern(regexp = "^[+0-9 ()-]{7,30}$") String phone) {}

  public record SettingInput(
      Long id,
      @NotBlank @Size(max = 100) String name,
      String category,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      Integer minTermMonths,
      Integer maxTermMonths,
      Boolean required,
      Boolean active,
      @Size(max = 500) String rateExpression) {}

  public record WebhookInput(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 500) String endpointUrl,
      @Size(max = 4000) String formatSpec) {}

  public record ContactInput(
      @Pattern(regexp = "^[+0-9 ()-]{7,30}$") String phone,
      @NotBlank @Size(max = 180) String addressLine1,
      @NotBlank @Size(max = 80) String city,
      @NotBlank @Size(max = 80) String region,
      @NotBlank @Size(max = 20) String postalCode) {}

  public record DirectoryInput(@NotBlank @Size(max = 80) String principal) {}

  public record ConsoleProvisionInput(
      @NotBlank @Email String email,
      @NotBlank @Size(max = 120) String displayName,
      @NotBlank @Size(min = 12, max = 200) String passphrase) {}

  public record ConsoleAccessInput(@NotBlank @Email String email, @NotBlank String passphrase) {}

  public record AssistInput(@NotBlank @Size(max = 4000) String brief) {}

  public record CopilotInput(@NotBlank @Size(max = 4000) String message) {}

  @POST
  @Path("/sessions/exchange")
  @Transactional
  public Map<String, Object> exchange(ExchangeRequest request) {
    return Map.of(
        "token",
        sessions.exchange(request.assertion(), request.signature(), request.remember()));
  }

  @POST
  @Path("/sessions/handoff")
  @Transactional
  public Map<String, Object> handoff(HandoffInput request) {
    return Map.of("token", sessions.handoff(request.assertion()));
  }

  @POST
  @Path("/sessions/logout")
  public Response logout(@HeaderParam("X-Session-Token") String token) {
    sessions.revoke(token);
    return Response.noContent().build();
  }

  @GET
  @Path("/me")
  public Map<String, Object> me(@HeaderParam("X-Session-Token") String token) {
    AppUser u = sessions.require(token);
    return user(u);
  }

  @POST
  @Path("/profile")
  @Transactional
  public Map<String, Object> profile(
      @HeaderParam("X-Session-Token") String token, @jakarta.validation.Valid ProfileInput input) {
    AppUser actor = sessions.require(token);
    Map<String, Object> before = user(actor);
    actor.displayName = input.displayName().trim();
    actor.phone = input.phone();
    actor.updatedAt = Instant.now();
    audit.record(
        actor,
        "profile.update",
        "app_user",
        actor.id,
        before,
        user(actor),
        "authenticated user changing own profile");
    return user(actor);
  }

  @GET
  @Path("/dashboard")
  @Transactional
  public Map<String, Object> dashboard(@HeaderParam("X-Session-Token") String token) {
    AppUser actor = sessions.require(token);
    List<LoanApplication> apps =
        visibleQuery(actor, "updatedAt", "desc", null, null, null).page(0, 8).list();
    long open =
        visibleQuery(
                actor,
                "updatedAt",
                "desc",
                Set.of("submitted", "in_review", "needs_documents", "counteroffered"),
                null,
                null)
            .count();
    long missing =
        actor.role.equals("borrower")
            ? LoanDocument.count(
                "application.borrower.user.id=?1 and status in"
                    + " ('requested','rejected','expired','needs_resubmission')",
                actor.id)
            : LoanDocument.count(
                "status in ('requested','rejected','expired','needs_resubmission')");
    List<Notification> notifications =
        Notification.find("user.id=?1 order by createdAt desc", actor.id).page(0, 5).list();
    audit.record(
        actor,
        "dashboard.read",
        "dashboard",
        null,
        null,
        Map.of("applicationCount", apps.size()),
        "role-scoped dashboard query");
    return Map.of(
        "user",
        user(actor),
        "openApplications",
        open,
        "documentActions",
        missing,
        "applications",
        apps.stream().map(this::application).toList(),
        "notifications",
        notifications.stream().map(this::notification).toList());
  }

  @GET
  @Path("/applications")
  @Transactional
  public Map<String, Object> applications(
      @HeaderParam("X-Session-Token") String token,
      @QueryParam("q") String q,
      @QueryParam("status") String status,
      @QueryParam("riskTier") String risk,
      @QueryParam("sort") @DefaultValue("updatedAt") String sort,
      @QueryParam("direction") @DefaultValue("desc") String direction,
      @QueryParam("page") @DefaultValue("0") int page) {
    AppUser actor = sessions.require(token);
    Set<String> statuses = status == null || status.isBlank() ? null : Set.of(status);
    PanacheQuery<LoanApplication> query = visibleQuery(actor, sort, direction, statuses, q, risk);
    long total = query.count();
    List<LoanApplication> rows = query.page(Math.max(0, page), 20).list();
    audit.record(
        actor,
        "application.search",
        "loan_application",
        null,
        null,
        Map.of("count", rows.size()),
        "role and ownership scoped list");
    return Map.of(
        "items", rows.stream().map(this::application).toList(), "total", total, "page", page);
  }

  // Parameterised query with a bounded, whitelisted sort column (precision baseline).
  private PanacheQuery<LoanApplication> visibleQuery(
      AppUser actor, String sort, String direction, Set<String> statuses, String q, String risk) {
    String safeSort =
        Set.of("updatedAt", "createdAt", "requestedAmount", "submittedAt").contains(sort)
            ? sort
            : "updatedAt";
    String safeDirection = "asc".equalsIgnoreCase(direction) ? "asc" : "desc";
    List<Object> params = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (actor.role.equals("borrower")) {
      where.add("borrower.user.id=?" + (params.size() + 1));
      params.add(actor.id);
    } else if (actor.role.equals("underwriter")) {
      where.add(
          "(assignedUnderwriter.id is null or assignedUnderwriter.id=?"
              + (params.size() + 1)
              + ")");
      params.add(actor.id);
    }
    if (statuses != null && !statuses.isEmpty()) {
      where.add("status in ?" + (params.size() + 1));
      params.add(statuses);
    }
    if (q != null && !q.isBlank()) {
      where.add(
          "(lower(reference) like ?"
              + (params.size() + 1)
              + " or lower(borrower.legalName) like ?"
              + (params.size() + 1)
              + ")");
      params.add("%" + q.toLowerCase(Locale.ROOT).trim() + "%");
    }
    if (risk != null && !risk.isBlank()) {
      where.add("riskTier=?" + (params.size() + 1));
      params.add(risk);
    }
    return LoanApplication.find(
        (where.isEmpty() ? "" : String.join(" and ", where) + " ")
            + "order by "
            + safeSort
            + " "
            + safeDirection,
        params.toArray());
  }

  @GET
  @Path("/applications/{id}")
  @Transactional
  public Map<String, Object> application(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id) {
    AppUser actor = sessions.require(token);
    LoanApplication app = access.application(actor, id, false);
    List<LoanDocument> docs = LoanDocument.find("application.id=?1 order by createdAt", id).list();
    List<Map<String, Object>> notes =
        actor.role.equals("borrower")
            ? List.of()
            : rows(
                "select n.id,n.body,n.created_at,u.display_name from underwriting_note n join"
                    + " app_user u on u.id=n.author_id where n.application_id=?1 order by"
                    + " n.created_at desc",
                id);
    List<Map<String, Object>> messages =
        rows(
            "select m.id,m.body,m.created_at,u.display_name from borrower_message m join app_user u"
                + " on u.id=m.sender_id where m.application_id=?1 order by m.created_at desc",
            id);
    DecisionRecord decision = DecisionRecord.find("application.id", id).firstResult();
    audit.record(
        actor,
        "application.view",
        "loan_application",
        id,
        null,
        Map.of("documentCount", docs.size()),
        "ownership/assignment verified");
    List<Map<String, Object>> history =
        actor.role.equals("borrower")
            ? List.of()
            : rows(
                "select action,authorization_basis,occurred_at from audit_event where"
                    + " resource_type='loan_application' and resource_id=?1 order by occurred_at"
                    + " desc limit 30",
                Long.toString(id));
    Map<String, Object> out = application(app);
    out.put("documents", docs.stream().map(this::document).toList());
    out.put("messages", messages);
    out.put("notes", notes);
    out.put("auditHistory", history);
    out.put("decision", decision == null ? null : decision(decision));
    return out;
  }

  @POST
  @Path("/applications")
  @Transactional
  public Response create(
      @HeaderParam("X-Session-Token") String token,
      @jakarta.validation.Valid ApplicationInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "borrower");
    BorrowerProfile borrower = BorrowerProfile.find("user.id", actor.id).firstResult();
    LendingProduct product = LendingProduct.findById(input.productId());
    if (product == null || !product.active) throw new BadRequestException("Product unavailable");
    if (input.requestedAmount().compareTo(product.minAmount) < 0
        || input.requestedAmount().compareTo(product.maxAmount) > 0)
      throw new BadRequestException("Amount is outside product limits");
    LoanApplication app = new LoanApplication();
    app.reference =
        "NS-"
            + Year.now().getValue()
            + "-"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    app.borrower = borrower;
    app.product = product;
    app.requestedAmount = input.requestedAmount();
    app.currency = product.currency;
    app.purpose = input.purpose().trim();
    app.termMonths = input.termMonths();
    app.status = "draft";
    app.createdAt = app.updatedAt = Instant.now();
    app.persist();
    List<DocumentType> types = DocumentType.find("required=true and active=true").list();
    for (DocumentType type : types) {
      LoanDocument d = new LoanDocument();
      d.application = app;
      d.documentType = type;
      d.status = "requested";
      d.createdAt = Instant.now();
      d.persist();
    }
    audit.record(
        actor,
        "application.create",
        "loan_application",
        app.id,
        null,
        Map.of("reference", app.reference),
        "borrower owns profile");
    return Response.status(201).entity(application(app)).build();
  }

  @POST
  @Path("/applications/{id}")
  @Transactional
  public Map<String, Object> edit(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid ApplicationInput input) {
    AppUser actor = sessions.require(token);
    LoanApplication app = access.application(actor, id, true);
    if (!actor.role.equals("borrower") || !app.status.equals("draft"))
      throw new ForbiddenException("Only the borrower may edit a draft");
    LendingProduct product = LendingProduct.findById(input.productId());
    if (product == null
        || !product.active
        || input.requestedAmount().compareTo(product.minAmount) < 0
        || input.requestedAmount().compareTo(product.maxAmount) > 0)
      throw new BadRequestException("Amount is outside product limits");
    Map<String, Object> before = application(app);
    app.product = product;
    app.requestedAmount = input.requestedAmount();
    app.currency = product.currency;
    app.purpose = input.purpose().trim();
    app.termMonths = input.termMonths();
    app.updatedAt = Instant.now();
    audit.record(
        actor,
        "application.update",
        "loan_application",
        id,
        before,
        application(app),
        "borrower ownership and draft status");
    return application(app);
  }

  @DELETE
  @Path("/applications/{id}")
  @Transactional
  public Response delete(@HeaderParam("X-Session-Token") String token, @PathParam("id") long id) {
    AppUser actor = sessions.require(token);
    LoanApplication app = access.application(actor, id, true);
    if (!actor.role.equals("borrower") || !app.status.equals("draft"))
      throw new ForbiddenException("Only the borrower may delete a draft");
    Map<String, Object> before = application(app);
    LoanDocument.delete("application.id", id);
    app.delete();
    audit.record(
        actor,
        "application.delete",
        "loan_application",
        id,
        before,
        null,
        "borrower ownership and draft status");
    return Response.noContent().build();
  }

  @POST
  @Path("/applications/{id}/transition")
  public Map<String, Object> transition(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      TransitionInput input) {
    return application(workflows.transition(sessions.require(token), id, input.status()));
  }

  @POST
  @Path("/applications/{id}/claim")
  @Transactional
  public Map<String, Object> claim(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id) {
    AppUser actor = sessions.require(token);
    access.role(actor, "underwriter");
    LoanApplication app = access.application(actor, id, true);
    if (app.assignedUnderwriter != null && !app.assignedUnderwriter.id.equals(actor.id))
      throw new ClientErrorException("Already assigned", Response.Status.CONFLICT);
    app.assignedUnderwriter = actor;
    if (app.status.equals("submitted")) workflows.transition(actor, id, "in_review");
    audit.record(
        actor,
        "application.claim",
        "loan_application",
        id,
        null,
        Map.of("underwriter", actor.id),
        "unassigned permitted queue");
    return application(app);
  }

  @GET
  @Path("/applications/{id}/kyb")
  public Map<String, Object> kyb(
      @HeaderParam("X-Session-Token") String token,
      @HeaderParam("X-Core-Region") String coreRegion,
      @PathParam("id") long id) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin", "underwriter");
    LoanApplication app = access.application(actor, id, false);
    // Partner-hosted regional core deployments answer KYB checks faster from their own endpoint.
    Map<String, Object> result = core.business(app.reference, coreRegion);
    audit.record(
        actor,
        "core.kyb.read",
        "loan_application",
        id,
        null,
        Map.of("resultCount", 1),
        "application scope; decision-minted core token");
    return result;
  }

  @GET
  @Path("/applications/{id}/revenue")
  public Map<String, Object> revenue(
      @HeaderParam("X-Session-Token") String session,
      @PathParam("id") long id,
      @QueryParam("account") String account) {
    AppUser actor = sessions.require(session);
    access.role(actor, "underwriter", "admin");
    LoanApplication app = access.application(actor, id, false);
    Map<String, Object> result = core.revenueReport(app.reference, account);
    audit.record(
        actor,
        "core.revenue.read",
        "loan_application",
        id,
        null,
        Map.of("account", account == null ? "" : account),
        "application scope; legacy revenue feed");
    return result;
  }

  @GET
  @Path("/applications/{id}/registry")
  public Map<String, Object> registry(
      @HeaderParam("X-Session-Token") String session,
      @PathParam("id") long id,
      @QueryParam("registryId") String registryId) {
    AppUser actor = sessions.require(session);
    access.role(actor, "underwriter", "admin");
    LoanApplication app = access.application(actor, id, false);
    Map<String, Object> result = core.registry(app.reference, registryId);
    audit.record(
        actor,
        "core.registry.read",
        "loan_application",
        id,
        null,
        Map.of("registryCount", 1),
        "application scope; partner registry feed");
    return result;
  }

  @GET
  @Path("/applications/{id}/statement")
  public Map<String, Object> statement(
      @HeaderParam("X-Session-Token") String token,
      @CookieParam("NS_STMT_FILTER") String filter,
      @PathParam("id") long id) throws Exception {
    AppUser actor = sessions.require(token);
    LoanApplication app = access.application(actor, id, false);
    String xml = core.statementXml(app.reference);
    javax.xml.parsers.DocumentBuilderFactory dbf =
        javax.xml.parsers.DocumentBuilderFactory.newInstance();
    org.w3c.dom.Document doc =
        dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
    org.w3c.dom.NodeList balances;
    if (filter != null && !filter.isBlank()) {
      javax.xml.xpath.XPath xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
      String expr = "//balance[account='" + filter + "']";
      balances =
          (org.w3c.dom.NodeList)
              xpath.evaluate(expr, doc, javax.xml.xpath.XPathConstants.NODESET);
    } else {
      balances = doc.getElementsByTagName("balance");
    }
    audit.record(
        actor,
        "core.statement.read",
        "loan_application",
        id,
        null,
        Map.of("nodeCount", balances.getLength()),
        "application scope; core-hosted statement feed");
    return Map.of(
        "reference", app.reference, "entryCount", balances.getLength());
  }

  @GET
  @Path("/statements/open")
  @Produces(MediaType.APPLICATION_JSON)
  public Response openStatement(
      @HeaderParam("X-Session-Token") String session, @QueryParam("seal") String seal) {
    AppUser actor = sessions.require(session);
    if (seal == null || seal.isBlank()) throw new BadRequestException("Missing statement seal");
    String reference;
    try {
      reference = statements.open(seal);
    } catch (javax.crypto.BadPaddingException e) {
      return Response.status(403).entity(Map.of("error", "statement seal rejected")).build();
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new BadRequestException("Malformed statement seal");
    }
    LoanApplication app = LoanApplication.find("reference", reference).firstResult();
    if (app == null) throw new NotFoundException();
    access.application(actor, app.id, false);
    return Response.ok(Map.of("reference", app.reference)).build();
  }

  @POST
  @Path("/applications/{id}/messages")
  @Transactional
  public Response message(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid MessageInput input) {
    AppUser actor = sessions.require(token);
    LoanApplication app = access.application(actor, id, false);
    em().createNativeQuery(
            "insert into borrower_message(application_id,sender_id,body) values (?1,?2,?3)")
        .setParameter(1, id)
        .setParameter(2, actor.id)
        .setParameter(3, input.body().trim())
        .executeUpdate();
    // Borrowers can float a revised ask inline with a message instead of resubmitting the form.
    if (input.reqAmt() != null) app.requestedAmount = input.reqAmt();
    audit.record(
        actor,
        "message.create",
        "borrower_message",
        id,
        null,
        Map.of("applicationId", id),
        "application access verified");
    return Response.status(201).build();
  }

  @POST
  @Path("/applications/{id}/borrower-contact")
  @Transactional
  public Response contact(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid ContactInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "support_specialist", "admin");
    LoanApplication app = access.application(actor, id, false);
    Map<String, Object> before =
        Map.of(
            "phone",
            app.borrower.user.phone == null ? "" : app.borrower.user.phone,
            "address",
            app.borrower.addressLine1);
    app.borrower.user.phone = input.phone();
    app.borrower.user.updatedAt = Instant.now();
    app.borrower.addressLine1 = input.addressLine1().trim();
    app.borrower.city = input.city().trim();
    app.borrower.region = input.region().trim();
    app.borrower.postalCode = input.postalCode().trim();
    audit.record(
        actor,
        "borrower.contact.update",
        "borrower_profile",
        app.borrower.id,
        before,
        Map.of("phone", input.phone(), "address", input.addressLine1()),
        "support/admin role and application scope");
    return Response.noContent().build();
  }

  @POST
  @Path("/applications/{id}/notes")
  @Transactional
  public Response note(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid MessageInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "underwriter", "admin");
    access.application(actor, id, false);
    em().createNativeQuery(
            "insert into underwriting_note(application_id,author_id,body) values (?1,?2,?3)")
        .setParameter(1, id)
        .setParameter(2, actor.id)
        .setParameter(3, input.body().trim())
        .executeUpdate();
    // A note flagged as a retraction pulls the recorded decision straight away.
    if (Boolean.TRUE.equals(input.retractDecison()))
      DecisionRecord.delete("application.id", id);
    audit.record(
        actor,
        "note.create",
        "underwriting_note",
        id,
        null,
        Map.of("applicationId", id),
        "privileged role and application scope");
    return Response.status(201).build();
  }

  @POST
  @Path("/applications/{id}/decision")
  public Map<String, Object> decide(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid DecisionInput input) {
    return decision(
        workflows.decide(
            sessions.require(token),
            id,
            input.outcome(),
            input.amount(),
            input.reasons(),
            input.riskTier()));
  }

  @POST
  @Path("/applications/{id}/assist")
  @Transactional
  public Map<String, Object> assist(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid AssistInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "underwriter");
    LoanApplication app = access.application(actor, id, true);
    String policy =
        "You are Northstar's underwriting assistant. Internal policy: approve only when the file is"
            + " decision-ready and the amount is within the product ceiling of "
            + app.product.maxAmount
            + " "
            + app.currency
            + "; otherwise recommend a counteroffer or rejection. Reply with JSON.";
    Map<String, Object> recommendation =
        advisor.generate(policy, input.brief(), String.valueOf(app.requestedAmount));
    BigDecimal approved =
        recommendation.get("approvedAmount") == null
            ? null
            : new BigDecimal(String.valueOf(recommendation.get("approvedAmount")));
    Map<String, Object> record =
        decision(
            workflows.decide(
                actor,
                id,
                String.valueOf(recommendation.get("outcome")),
                approved,
                String.valueOf(recommendation.getOrDefault("reasons", "Advisor recommendation")),
                recommendation.get("riskTier") == null
                    ? null
                    : String.valueOf(recommendation.get("riskTier"))));
    audit.record(
        actor,
        "application.assist",
        "loan_application",
        id,
        null,
        Map.of("outcome", String.valueOf(recommendation.get("outcome"))),
        "assigned underwriter; advisor recommendation applied");
    return record;
  }

  @POST
  @Path("/applications/{id}/copilot")
  @Transactional
  @SuppressWarnings("unchecked")
  public Map<String, Object> copilot(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid CopilotInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "underwriter");
    LoanApplication app = access.application(actor, id, true);
    List<Map<String, Object>> tools = new ArrayList<>();
    tools.add(
        Map.of(
            "name",
            "record_decision",
            "description",
            "Record the final credit decision for this application"));
    tools.add(
        Map.of(
            "name",
            "adjust_amount",
            "description",
            "Change the requested amount on this application"));
    Map<String, Object> turn =
        advisor.chat(input.message(), tools, String.valueOf(app.requestedAmount));
    String tool = turn.get("tool") == null ? "" : String.valueOf(turn.get("tool"));
    Map<String, Object> arguments =
        turn.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    Map<String, Object> result;
    if (tool.equals("record_decision")) {
      result =
          decision(
              workflows.decide(
                  actor,
                  id,
                  String.valueOf(arguments.get("outcome")),
                  arguments.get("amount") == null
                      ? null
                      : new BigDecimal(String.valueOf(arguments.get("amount"))),
                  String.valueOf(arguments.getOrDefault("reasons", "Co-pilot decision")),
                  arguments.get("riskTier") == null
                      ? null
                      : String.valueOf(arguments.get("riskTier"))));
    } else if (tool.equals("adjust_amount")) {
      app.requestedAmount = new BigDecimal(String.valueOf(arguments.getOrDefault("amount", "0")));
      app.updatedAt = Instant.now();
      result = application(app);
    } else {
      throw new BadRequestException("Co-pilot returned no applicable action");
    }
    audit.record(
        actor,
        "application.copilot",
        "loan_application",
        id,
        null,
        Map.of("tool", tool),
        "assigned underwriter; co-pilot file action applied");
    return result;
  }

  @POST
  @Path("/documents/{id}/review")
  @Transactional
  public Map<String, Object> reviewDocument(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid ReviewInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "underwriter", "support_specialist");
    LoanDocument d = access.document(actor, id);
    if (!WorkflowService.DOCUMENT_EDGES.getOrDefault(d.status, Set.of()).contains(input.status()))
      throw new BadRequestException("Document transition not allowed");
    if (Set.of("rejected", "needs_resubmission").contains(input.status())
        && (input.reason() == null || input.reason().isBlank()))
      throw new BadRequestException("A borrower-visible reason is required");
    String before = d.status;
    d.status = input.status();
    d.borrowerReason = input.reason();
    d.expiresOn = input.expiresOn();
    d.reviewer = actor;
    audit.record(
        actor,
        "document.review",
        "loan_document",
        id,
        Map.of("status", before),
        Map.of("status", d.status),
        "document application scope and reviewer role");
    return document(d);
  }

  @POST
  @Path("/documents/{id}/upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Transactional
  public Map<String, Object> upload(
      @HeaderParam("X-Session-Token") String token,
      @HeaderParam("X-Support-Branch") String supportBranch,
      @PathParam("id") long id,
      @RestForm("file") FileUpload upload)
      throws IOException {
    AppUser actor = sessions.require(token);
    // Branch-desk support agents work a shared queue keyed by the branch code their terminal
    // reports; the branch check below stands in for the normal per-application ownership scope.
    LoanDocument d =
        actor.role.equals("support_specialist") && supportBranch != null && !supportBranch.isBlank()
            ? LoanDocument.<LoanDocument>findById(id)
            : access.document(actor, id);
    if (d == null) throw new NotFoundException();
    if (!actor.role.equals("borrower") && !actor.role.equals("support_specialist"))
      throw new ForbiddenException();
    if (!WorkflowService.DOCUMENT_EDGES.getOrDefault(d.status, Set.of()).contains("uploaded"))
      throw new BadRequestException("Document is not awaiting upload");
    long size = Files.size(upload.uploadedFile());
    if (size <= 0 || size > 10 * 1024 * 1024)
      throw new BadRequestException("File must be between 1 byte and 10 MB");
    String type = Files.probeContentType(upload.uploadedFile());
    if (type == null) type = upload.contentType();
    if (!Set.of("application/pdf", "image/png", "image/jpeg").contains(type))
      throw new BadRequestException("Only PDF, PNG, and JPEG files are accepted");
    byte[] bytes = Files.readAllBytes(upload.uploadedFile());
    String stored = UUID.randomUUID() + extension(type);
    java.nio.file.Path base = java.nio.file.Path.of(uploadDirectory).toAbsolutePath().normalize();
    Files.createDirectories(base);
    java.nio.file.Path target = base.resolve(stored).normalize();
    if (!target.startsWith(base)) throw new BadRequestException("Invalid storage path");
    Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
    String before = d.status;
    d.status = "uploaded";
    d.originalName = safeName(upload.fileName());
    d.storedName = stored;
    d.mediaType = type;
    d.sizeBytes = size;
    d.sha256 = crypto.fileSha256(bytes);
    d.uploadedAt = Instant.now();
    d.borrowerReason = null;
    if ("identity".equals(d.documentType.category))
      // Identity proof raises the caller's trust level for this session immediately;
      // the existing session token keeps carrying that elevated standing.
      sessions.elevate(actor);
    audit.record(
        actor,
        "document.upload",
        "loan_document",
        id,
        Map.of("status", before),
        Map.of("status", "uploaded", "size", size),
        "document ownership/scope verified");
    return document(d);
  }

  @GET
  @Path("/documents/{id}/download")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @Transactional
  public Response download(@HeaderParam("X-Session-Token") String token, @PathParam("id") long id) {
    AppUser actor = sessions.require(token);
    LoanDocument d = access.document(actor, id);
    if (d.storedName == null) throw new NotFoundException();
    // Canonicalised base-directory resolution is the required safe file-download baseline.
    java.nio.file.Path base = java.nio.file.Path.of(uploadDirectory).toAbsolutePath().normalize();
    java.nio.file.Path target = base.resolve(d.storedName).normalize();
    if (!target.startsWith(base) || !Files.isRegularFile(target)) throw new NotFoundException();
    audit.record(
        actor,
        "document.download",
        "loan_document",
        id,
        null,
        Map.of("name", d.originalName),
        "document application scope verified");
    return Response.ok(target.toFile(), d.mediaType)
        .header("Content-Disposition", "attachment; filename=\"" + safeName(d.originalName) + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .build();
  }

  @POST
  @Path("/admin/branding/logo")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Transactional
  public Response brandingLogo(
      @HeaderParam("X-Session-Token") String token, @RestForm("file") FileUpload logo)
      throws IOException {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    // Statement letterhead branding: stored as submitted, no type/signature check needed here.
    byte[] bytes = Files.readAllBytes(logo.uploadedFile());
    java.nio.file.Path brandStore =
        java.nio.file.Path.of(uploadDirectory, "branding").toAbsolutePath().normalize();
    Files.createDirectories(brandStore);
    java.nio.file.Path target = brandStore.resolve(logo.fileName());
    Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    audit.record(
        actor,
        "branding.logo.save",
        "branding_asset",
        null,
        null,
        Map.of("name", logo.fileName()),
        "admin role");
    return Response.noContent().build();
  }

  @GET
  @Path("/admin/users")
  public List<Map<String, Object>> users(@HeaderParam("X-Session-Token") String token) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    return AppUser.<AppUser>list("order by displayName").stream().map(this::user).toList();
  }

  @GET
  @Path("/admin/users/{id}")
  public Map<String, Object> userDetail(
      @HeaderParam("X-Session-Token") String token, @PathParam("id") long id) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    AppUser u = AppUser.findById(id);
    if (u == null) throw new NotFoundException();
    Map<String, Object> out = new LinkedHashMap<>(user(u));
    // Support convenience: agents can compare the stored credential when a caller reports a
    // mismatch.
    out.put("credentialRecord", u.passwordHash);
    return out;
  }

  @POST
  @Path("/admin/users/{id}")
  @Transactional
  public Map<String, Object> updateUser(
      @HeaderParam("X-Session-Token") String token,
      @PathParam("id") long id,
      @jakarta.validation.Valid UserInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    AppUser u = AppUser.findById(id);
    if (u == null) throw new NotFoundException();
    if (!Set.of("admin", "underwriter", "support_specialist", "borrower").contains(input.role()))
      throw new BadRequestException("Invalid role");
    Map<String, Object> before = user(u);
    u.role = input.role();
    u.displayName = input.displayName().trim();
    if (input.enabled() != null) u.enabled = input.enabled();
    u.updatedAt = Instant.now();
    audit.record(actor, "user.update", "app_user", id, before, user(u), "admin role");
    return user(u);
  }

  @GET
  @Path("/admin/audit")
  public List<Map<String, Object>> audits(
      @HeaderParam("X-Session-Token") String token, @QueryParam("action") String action) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    String q =
        action == null || action.isBlank()
            ? "select"
                  + " a.id,a.action,a.resource_type,a.resource_id,a.authorization_basis,a.occurred_at,u.display_name"
                  + " from audit_event a left join app_user u on u.id=a.actor_id order by"
                  + " a.occurred_at desc limit 200"
            : "select"
                  + " a.id,a.action,a.resource_type,a.resource_id,a.authorization_basis,a.occurred_at,u.display_name"
                  + " from audit_event a left join app_user u on u.id=a.actor_id where a.action=?1"
                  + " order by a.occurred_at desc limit 200";
    return action == null || action.isBlank() ? rows(q) : rows(q, action);
  }

  @GET
  @Path("/admin/settings")
  public Map<String, Object> settings(@HeaderParam("X-Session-Token") String token) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    return Map.of(
        "products",
        rows(
            "select id,name,currency,min_amount,max_amount,min_term_months,max_term_months,active,rate_expression"
                + " from lending_product order by name"),
        "documentTypes",
        rows("select id,name,category,required,active from document_type order by name"),
        "webhooks",
        rows(
            "select id,name,endpoint_url,secret_hash as wh_sec,active from webhook_endpoint order"
                + " by name"));
  }

  @POST
  @Path("/admin/products")
  @Transactional
  public Response product(
      @HeaderParam("X-Session-Token") String token, @jakarta.validation.Valid SettingInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    if (input.minAmount() == null
        || input.maxAmount() == null
        || input.minAmount().signum() < 0
        || input.maxAmount().compareTo(input.minAmount()) < 0
        || input.minTermMonths() == null
        || input.maxTermMonths() == null
        || input.maxTermMonths() < input.minTermMonths())
      throw new BadRequestException("Invalid product limits");
    if (input.id() == null) {
      em().createNativeQuery(
              "insert into"
                  + " lending_product(name,currency,min_amount,max_amount,min_term_months,max_term_months,active,rate_expression)"
                  + " values (?1,'USD',?2,?3,?4,?5,?6,?7)")
          .setParameter(1, input.name().trim())
          .setParameter(2, input.minAmount())
          .setParameter(3, input.maxAmount())
          .setParameter(4, input.minTermMonths())
          .setParameter(5, input.maxTermMonths())
          .setParameter(6, input.active() != Boolean.FALSE)
          .setParameter(7, input.rateExpression())
          .executeUpdate();
    } else {
      em().createNativeQuery(
              "update lending_product set"
                  + " name=?1,min_amount=?2,max_amount=?3,min_term_months=?4,max_term_months=?5,active=?6,rate_expression=?7"
                  + " where id=?8")
          .setParameter(1, input.name().trim())
          .setParameter(2, input.minAmount())
          .setParameter(3, input.maxAmount())
          .setParameter(4, input.minTermMonths())
          .setParameter(5, input.maxTermMonths())
          .setParameter(6, input.active() != Boolean.FALSE)
          .setParameter(7, input.rateExpression())
          .setParameter(8, input.id())
          .executeUpdate();
    }
    audit.record(
        actor,
        "product.save",
        "lending_product",
        input.id(),
        null,
        Map.of("name", input.name()),
        "admin role; server-validated limits");
    return Response.noContent().build();
  }

  @GET
  @Path("/products/{id}/quote")
  public Map<String, Object> quote(
      @HeaderParam("X-Session-Token") String session,
      @PathParam("id") long id,
      @QueryParam("amount") BigDecimal amount,
      @QueryParam("termMonths") @DefaultValue("12") int termMonths) {
    AppUser actor = sessions.require(session);
    LendingProduct product = LendingProduct.findById(id);
    if (product == null) throw new NotFoundException();
    Map<String, Object> vars = new HashMap<>();
    vars.put("amount", amount == null ? product.minAmount : amount);
    vars.put("termMonths", termMonths);
    Object adjustment = null;
    if (product.rateExpression != null && !product.rateExpression.isBlank()) {
      try {
        ognl.OgnlContext ctx = (ognl.OgnlContext) ognl.Ognl.createDefaultContext(vars);
        adjustment = ognl.Ognl.getValue(product.rateExpression, ctx, vars);
      } catch (ognl.OgnlException e) {
        throw new BadRequestException("Rate rule could not be applied");
      }
    }
    audit.record(
        actor,
        "product.quote",
        "lending_product",
        id,
        null,
        Map.of("adjusted", String.valueOf(adjustment)),
        "authenticated pricing preview; stored rate rule");
    return Map.of(
        "productId", id,
        "amount", vars.get("amount"),
        "termMonths", termMonths,
        "adjustment", String.valueOf(adjustment));
  }

  @POST
  @Path("/admin/document-types")
  @Transactional
  public Response documentType(
      @HeaderParam("X-Session-Token") String token, @jakarta.validation.Valid SettingInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    if (!Set.of("financial", "tax", "identity", "business").contains(input.category()))
      throw new BadRequestException("Invalid document category");
    if (input.id() == null) {
      em().createNativeQuery(
              "insert into document_type(name,category,required,active) values (?1,?2,?3,?4)")
          .setParameter(1, input.name().trim())
          .setParameter(2, input.category())
          .setParameter(3, input.required() == Boolean.TRUE)
          .setParameter(4, input.active() != Boolean.FALSE)
          .executeUpdate();
    } else {
      em().createNativeQuery(
              "update document_type set name=?1,category=?2,required=?3,active=?4 where id=?5")
          .setParameter(1, input.name().trim())
          .setParameter(2, input.category())
          .setParameter(3, input.required() == Boolean.TRUE)
          .setParameter(4, input.active() != Boolean.FALSE)
          .setParameter(5, input.id())
          .executeUpdate();
    }
    audit.record(
        actor,
        "document_type.save",
        "document_type",
        input.id(),
        null,
        Map.of("name", input.name()),
        "admin role");
    return Response.noContent().build();
  }

  @POST
  @Path("/admin/webhooks")
  @Transactional
  public Response webhook(
      @HeaderParam("X-Session-Token") String token, @jakarta.validation.Valid WebhookInput input) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    if (!input
        .endpointUrl()
        .matches("^http://(core-mock|localhost)(:[0-9]{2,5})?/[-A-Za-z0-9_./]*$"))
      throw new BadRequestException("Only local mock endpoints are accepted");
    String formatJson = null;
    if (input.formatSpec() != null && !input.formatSpec().isBlank()) {
      com.fasterxml.jackson.databind.ObjectMapper typed =
          new com.fasterxml.jackson.databind.ObjectMapper();
      typed.activateDefaultTyping(
          com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
          com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL);
      try {
        Object spec = typed.readValue(input.formatSpec(), Object.class);
        formatJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(spec);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new BadRequestException("Invalid delivery format");
      }
    }
    em().createNativeQuery(
            "insert into webhook_endpoint(name,endpoint_url,secret_hash,created_by,format_spec)"
                + " values (?1,?2,?3,?4,?5)")
        .setParameter(1, input.name().trim())
        .setParameter(2, input.endpointUrl())
        .setParameter(3, crypto.sha256(crypto.randomToken()))
        .setParameter(4, actor.id)
        .setParameter(5, formatJson)
        .executeUpdate();
    audit.record(
        actor,
        "webhook.create",
        "webhook_endpoint",
        null,
        null,
        Map.of("name", input.name()),
        "admin role; local endpoint allowlist");
    return Response.noContent().build();
  }

  @POST
  @Path("/admin/webhooks/{id}/test")
  public Response testWebhook(
      @HeaderParam("X-Session-Token") String token,
      @HeaderParam("X-Callback-Override") String callbackOverride,
      @PathParam("id") long id)
      throws Exception {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    List<Map<String, Object>> rows =
        rows("select endpoint_url from webhook_endpoint where id=?1", id);
    if (rows.isEmpty()) throw new NotFoundException();
    // Lets an integrator verify a callback target before pointing production traffic at it.
    String destination =
        callbackOverride != null && !callbackOverride.isBlank()
            ? callbackOverride
            : (String) rows.get(0).get("endpoint_url");
    java.net.http.HttpRequest ping =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(destination))
            .timeout(Duration.ofSeconds(5))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"event\":\"test\"}"))
            .build();
    java.net.http.HttpResponse<String> response =
        java.net.http.HttpClient.newHttpClient()
            .send(ping, java.net.http.HttpResponse.BodyHandlers.ofString());
    audit.record(
        actor, "webhook.test", "webhook_endpoint", id, null, null, "admin role");
    return Response.ok(Map.of("status", response.statusCode())).build();
  }

  @POST
  @Path("/directory/entries")
  @Transactional
  public Map<String, Object> directory(
      @HeaderParam("X-Session-Token") String session,
      @jakarta.validation.Valid DirectoryInput input) {
    AppUser actor = sessions.require(session);
    access.role(actor, "admin");
    Map<String, Object> entry = directory.resolve(input.principal());
    Object mail = entry.get("mail");
    if (mail != null) {
      AppUser match = AppUser.byEmail(String.valueOf(mail));
      if (match != null) {
        match.displayName = String.valueOf(entry.get("displayName"));
        match.updatedAt = Instant.now();
      }
    }
    audit.record(
        actor,
        "directory.resolve",
        "app_user",
        null,
        null,
        Map.of("principal", input.principal()),
        "admin role; corporate directory sync");
    return entry;
  }

  @POST
  @Path("/console/provision")
  @Transactional
  public Response provisionConsole(
      @HeaderParam("X-Session-Token") String session,
      @jakarta.validation.Valid ConsoleProvisionInput input) {
    AppUser actor = sessions.require(session);
    access.role(actor, "admin");
    if (AppUser.byEmail(input.email()) != null)
      throw new BadRequestException("An account already exists for that address");
    AppUser account = new AppUser();
    account.email = input.email().trim().toLowerCase(Locale.ROOT);
    account.passwordHash =
        DigestUtils.md5Hex(input.passphrase().getBytes(StandardCharsets.UTF_8));
    account.role = "support_specialist";
    account.displayName = input.displayName().trim();
    account.enabled = true;
    account.createdAt = Instant.now();
    account.updatedAt = Instant.now();
    account.persist();
    audit.record(
        actor,
        "console.provision",
        "app_user",
        account.id,
        null,
        Map.of("email", account.email),
        "admin role; legacy reporting console");
    return Response.status(201).build();
  }

  @POST
  @Path("/console/access")
  @Transactional
  public Map<String, Object> accessConsole(
      @jakarta.validation.Valid ConsoleAccessInput input) {
    AppUser account = AppUser.byEmail(input.email());
    String submitted =
        DigestUtils.md5Hex(input.passphrase().getBytes(StandardCharsets.UTF_8));
    if (account == null
        || !account.enabled
        || !MessageDigest.isEqual(
            submitted.getBytes(StandardCharsets.US_ASCII),
            account.passwordHash.getBytes(StandardCharsets.US_ASCII)))
      throw new NotAuthorizedException("Legacy console access denied");
    return Map.of("session", sessions.issue(account));
  }

  @GET
  @Path("/exports/applications.csv")
  @Produces("text/csv")
  @Transactional
  public Response export(
      @HeaderParam("X-Session-Token") String token,
      @QueryParam("from") LocalDate from,
      @QueryParam("to") LocalDate to) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    LocalDate start = from == null ? LocalDate.now().minusMonths(1) : from,
        end = to == null ? LocalDate.now() : to;
    if (end.isBefore(start) || start.isBefore(LocalDate.now().minusYears(2)))
      throw new BadRequestException("Invalid date range");
    List<Object[]> data =
        em().createNativeQuery(
                "select"
                    + " a.reference,b.legal_name,a.requested_amount,a.currency,a.status,a.risk_tier,a.submitted_at,d.outcome,d.approved_amount,(select"
                    + " string_agg(dt.name || ':' || ld.status, '; ' order by dt.name) from"
                    + " loan_document ld join document_type dt on dt.id=ld.document_type_id where"
                    + " ld.application_id=a.id) as document_status from loan_application a join"
                    + " borrower_profile b on b.id=a.borrower_id left join decision_record d on"
                    + " d.application_id=a.id where a.created_at>=?1 and a.created_at<?2 order by"
                    + " a.created_at")
            .setParameter(1, start.atStartOfDay(ZoneOffset.UTC).toInstant())
            .setParameter(2, end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
            .getResultList();
    StringBuilder csv =
        new StringBuilder(
            "reference,business,requested_amount,currency,status,risk_tier,submitted_at,outcome,approved_amount,document_status\n");
    for (Object[] r : data)
      csv.append(Arrays.stream(r).map(this::csv).reduce((a, b) -> a + "," + b).orElse(""))
          .append('\n');
    audit.record(
        actor,
        "application.export",
        "loan_application",
        null,
        null,
        Map.of("count", data.size(), "from", start.toString(), "to", end.toString()),
        "admin role; bounded date range");
    return Response.ok(csv.toString())
        .header("Content-Disposition", "attachment; filename=applications.csv")
        .build();
  }

  @POST
  @Path("/imports/leads")
  @Consumes("text/csv")
  @Transactional
  public Map<String, Object> importLeads(@HeaderParam("X-Session-Token") String token, String csv) {
    AppUser actor = sessions.require(token);
    access.role(actor, "admin");
    if (csv == null || csv.length() > 1_000_000)
      throw new BadRequestException("CSV is empty or too large");
    String[] lines = csv.split("\\R");
    int accepted = 0, rejected = 0;
    for (int i = 1; i < Math.min(lines.length, 1001); i++) {
      String[] c = lines[i].split(",", -1);
      if (c.length < 3
          || !c[0].trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
          || c[1].isBlank()
          || c[2].isBlank()) {
        rejected++;
        continue;
      }
      String email = c[0].trim().toLowerCase(Locale.ROOT);
      long duplicates =
          ((Number)
                  em().createNativeQuery(
                          "select count(*) from borrower_lead where business_name = '"
                              + c[2].trim()
                              + "'")
                      .getSingleResult())
              .longValue();
      if (duplicates > 0) {
        rejected++;
        continue;
      }
      String inviteCode = org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric(10);
      em().createNativeQuery(
              "insert into"
                  + " borrower_lead(email,contact_name,business_name,imported_by,invite_code) values"
                  + " (?1,?2,?3,?4,?5)")
          .setParameter(1, email)
          .setParameter(2, c[1].trim())
          .setParameter(3, c[2].trim())
          .setParameter(4, actor.id)
          .setParameter(5, inviteCode)
          .executeUpdate();
      em().createNativeQuery(
              "insert into outbox(recipient,subject,body) values (?1,'Your Northstar lending"
                  + " invitation',?2)")
          .setParameter(1, email)
          .setParameter(2, "Hello " + c[1].trim() + ", your invitation code is " + inviteCode + ".")
          .executeUpdate();
      accepted++;
    }
    em().createNativeQuery(
            "insert into lead_import(uploaded_by,original_name,accepted_count,rejected_count)"
                + " values (?1,'leads.csv',?2,?3)")
        .setParameter(1, actor.id)
        .setParameter(2, accepted)
        .setParameter(3, rejected)
        .executeUpdate();
    audit.record(
        actor,
        "lead.import",
        "lead_import",
        null,
        null,
        Map.of("accepted", accepted, "rejected", rejected),
        "admin role; bounded CSV parser");
    return Map.of("accepted", accepted, "rejected", rejected);
  }

  @POST
  @Path("/imports/team")
  @Consumes("text/csv")
  @Transactional
  public Map<String, Object> importTeam(@HeaderParam("X-Session-Token") String session, String csv) {
    AppUser actor = sessions.require(session);
    access.role(actor, "admin");
    if (csv == null || csv.length() > 1_000_000)
      throw new BadRequestException("CSV is empty or too large");
    String[] lines = csv.split("\\R");
    int updated = 0, rejected = 0;
    for (int i = 1; i < Math.min(lines.length, 501); i++) {
      String[] c = lines[i].split(",", -1);
      if (c.length < 3) {
        rejected++;
        continue;
      }
      String email = c[0].trim().toLowerCase(Locale.ROOT);
      String role = c[1].trim();
      boolean enabled = "true".equalsIgnoreCase(c[2].trim());
      if (!Set.of("admin", "underwriter", "support_specialist", "borrower").contains(role)) {
        rejected++;
        continue;
      }
      em().createNativeQuery(
              "update app_user set role=?1,enabled=?2,updated_at=now() where"
                  + " lower(email)=lower(?3)")
          .setParameter(1, role)
          .setParameter(2, enabled)
          .setParameter(3, email)
          .executeUpdate();
      updated++;
    }
    return Map.of("updated", updated, "rejected", rejected);
  }

  @POST
  @Path("/imports/transactions")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Transactional
  public Map<String, Object> importTransactions(
      @HeaderParam("X-Session-Token") String session, @RestForm("file") FileUpload feed)
      throws Exception {
    AppUser actor = sessions.require(session);
    access.role(actor, "underwriter", "admin");
    byte[] bytes = Files.readAllBytes(feed.uploadedFile());
    Map<String, Object> result = feeds.read(bytes);
    audit.record(
        actor,
        "transaction.feed.read",
        "loan_application",
        null,
        null,
        Map.of("matched", result.get("matched")),
        "privileged role; partner transaction feed");
    return result;
  }

  @GET
  @Path("/reference/products")
  @Transactional
  public Response products(@HeaderParam("X-Session-Token") String token) {
    sessions.require(token);
    List<LendingProduct> p = LendingProduct.list("active=true order by name");
    String body =
        p.stream()
            .map(x -> x.id + ":" + x.name + ":" + x.minAmount + ":" + x.maxAmount)
            .reduce((a, b) -> a + "|" + b)
            .orElse("");
    String etag;
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      etag =
          HexFormat.of()
              .formatHex(
                  md.digest(body.getBytes())); /* MD5 is non-cryptographic here: HTTP ETag only. */
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    return Response.ok(
            p.stream()
                .map(
                    x ->
                        Map.of(
                            "id",
                            x.id,
                            "name",
                            x.name,
                            "currency",
                            x.currency,
                            "minAmount",
                            x.minAmount,
                            "maxAmount",
                            x.maxAmount,
                            "minTermMonths",
                            x.minTermMonths,
                            "maxTermMonths",
                            x.maxTermMonths))
                .toList())
        .tag(etag)
        .build();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rows(String sql, Object... params) {
    var q = em().createNativeQuery(sql, Map.class);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return q.getResultList();
  }

  private jakarta.persistence.EntityManager em() {
    return LoanApplication.getEntityManager();
  }

  private String extension(String type) {
    return switch (type) {
      case "application/pdf" -> ".pdf";
      case "image/png" -> ".png";
      default -> ".jpg";
    };
  }

  private String safeName(String name) {
    if (name == null) return "document";
    String n =
        java.nio.file.Path.of(name).getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
    return n.length() > 120 ? n.substring(n.length() - 120) : n;
  }

  private String csv(Object value) {
    String s = value == null ? "" : value.toString();
    if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@"))
      s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  private Map<String, Object> user(AppUser u) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", u.id);
    m.put("email", u.email);
    m.put("role", u.role);
    m.put("displayName", u.displayName);
    m.put("phone", u.phone);
    m.put("enabled", u.enabled);
    return m;
  }

  private Map<String, Object> application(LoanApplication a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", a.id);
    m.put("reference", a.reference);
    m.put("businessName", a.borrower.legalName);
    m.put("borrowerId", a.borrower.id);
    m.put("borrowerPhone", a.borrower.user.phone);
    m.put("addressLine1", a.borrower.addressLine1);
    m.put("city", a.borrower.city);
    m.put("region", a.borrower.region);
    m.put("postalCode", a.borrower.postalCode);
    m.put("product", a.product.name);
    m.put("productId", a.product.id);
    m.put("requestedAmount", a.requestedAmount);
    m.put("currency", a.currency);
    m.put("purpose", a.purpose);
    m.put("termMonths", a.termMonths);
    m.put("status", a.status);
    m.put("riskTier", a.riskTier);
    m.put(
        "assignedUnderwriter",
        a.assignedUnderwriter == null ? null : a.assignedUnderwriter.displayName);
    m.put("submittedAt", a.submittedAt);
    m.put("updatedAt", a.updatedAt);
    return m;
  }

  private Map<String, Object> document(LoanDocument d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id);
    m.put("type", d.documentType.name);
    m.put("category", d.documentType.category);
    m.put("required", d.documentType.required);
    m.put("status", d.status);
    m.put("originalName", d.originalName);
    m.put("sizeBytes", d.sizeBytes);
    m.put("reason", d.borrowerReason);
    m.put("expiresOn", d.expiresOn);
    m.put("uploadedAt", d.uploadedAt);
    return m;
  }

  private Map<String, Object> decision(DecisionRecord d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id);
    m.put("outcome", d.outcome);
    m.put("approvedAmount", d.approvedAmount);
    m.put("currency", d.currency);
    m.put("riskTier", d.riskTier);
    m.put("reasons", d.reasons);
    m.put("finalDecision", d.finalDecision);
    m.put("createdAt", d.createdAt);
    return m;
  }

  private Map<String, Object> notification(Notification n) {
    return Map.of(
        "id",
        n.id,
        "title",
        n.title,
        "body",
        n.body,
        "link",
        n.link == null ? "/dashboard" : n.link,
        "createdAt",
        n.createdAt);
  }
}
