package com.northstar.decision.service;

import com.northstar.decision.model.*;
import com.northstar.decision.security.AccessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class WorkflowService {
  public static final Map<String, Set<String>> APPLICATION_EDGES =
      Map.of(
          "draft",
          Set.of("submitted", "withdrawn"),
          "submitted",
          Set.of("in_review", "needs_documents", "withdrawn"),
          "in_review",
          Set.of("needs_documents", "counteroffered", "approved", "rejected"),
          "needs_documents",
          Set.of("submitted", "in_review", "withdrawn"),
          "counteroffered",
          Set.of("approved", "rejected", "withdrawn"),
          "approved",
          Set.of(),
          "rejected",
          Set.of("draft"),
          "withdrawn",
          Set.of("draft"));
  public static final Map<String, Set<String>> DOCUMENT_EDGES =
      Map.of(
          "requested",
          Set.of("uploaded"),
          "uploaded",
          Set.of("accepted", "rejected", "needs_resubmission"),
          "accepted",
          Set.of("expired"),
          "rejected",
          Set.of("uploaded", "needs_resubmission"),
          "expired",
          Set.of("uploaded", "needs_resubmission"),
          "needs_resubmission",
          Set.of("uploaded"));
  private final AccessService access;
  private final AuditService audit;

  public WorkflowService(AccessService access, AuditService audit) {
    this.access = access;
    this.audit = audit;
  }

  @Transactional
  public LoanApplication transition(AppUser actor, long id, String target) {
    LoanApplication app = access.application(actor, id, true);
    String before = app.status;
    if (!APPLICATION_EDGES.getOrDefault(before, Set.of()).contains(target))
      throw new BadRequestException(
          "Transition from " + before + " to " + target + " is not allowed");
    if (actor.role.equals("borrower")
        && !Set.of("submitted", "withdrawn", "draft").contains(target))
      throw new ForbiddenException();
    if (Set.of("approved", "rejected", "counteroffered").contains(target)
        && !actor.role.equals("underwriter"))
      throw new ForbiddenException("Only an underwriter can decide");
    app.status = target;
    app.updatedAt = Instant.now();
    if (target.equals("submitted")) app.submittedAt = Instant.now();
    LoanApplication.getEntityManager()
        .createNativeQuery(
            "insert into notification(user_id,title,body,link) values (?1,'Application status"
                + " updated',?2,?3)")
        .setParameter(1, app.borrower.user.id)
        .setParameter(2, app.reference + " is now " + target.replace('_', ' ') + ".")
        .setParameter(3, "/applications/" + id)
        .executeUpdate();
    LoanApplication.getEntityManager()
        .createNativeQuery(
            "insert into"
                + " webhook_delivery(webhook_id,event_type,payload,status_code,attempt_count,next_attempt_at)"
                + " select"
                + " id,'application.status',jsonb_build_object('reference',?1,'status',?2),null,0,now()"
                + " from webhook_endpoint where active=true")
        .setParameter(1, app.reference)
        .setParameter(2, target)
        .executeUpdate();
    audit.record(
        actor,
        "application.transition",
        "loan_application",
        id,
        Map.of("status", before),
        Map.of("status", target),
        "role=" + actor.role + "; ownership/assignment verified");
    return app;
  }

  @Transactional
  public DecisionRecord decide(
      AppUser actor, long id, String outcome, BigDecimal amount, String reasons, String riskTier) {
    access.role(actor, "underwriter");
    LoanApplication app = access.application(actor, id, true);
    if (!Set.of("approved", "rejected", "counteroffer").contains(outcome))
      throw new BadRequestException("Invalid outcome");
    long blockers =
        LoanDocument.count(
            "application.id=?1 and documentType.required=true and status not in ('accepted')", id);
    if (blockers > 0)
      throw new BadRequestException("All required documents must be accepted before a decision");
    if (!app.status.equals("in_review") && !app.status.equals("counteroffered"))
      throw new BadRequestException("Application is not decision-ready");
    if (!outcome.equals("rejected")
        && (amount == null || amount.signum() <= 0 || amount.compareTo(app.product.maxAmount) > 0))
      throw new BadRequestException("Approved amount is outside product limits");
    DecisionRecord d = DecisionRecord.find("application.id", id).firstResult();
    if (d == null) {
      d = new DecisionRecord();
      d.application = app;
      d.createdAt = Instant.now();
    }
    d.decidedBy = actor;
    d.outcome = outcome;
    d.approvedAmount = amount;
    d.currency = app.currency;
    d.riskTier = riskTier;
    d.reasons = reasons;
    d.finalDecision = !outcome.equals("counteroffer");
    d.persist();
    String target = outcome.equals("counteroffer") ? "counteroffered" : outcome;
    transition(actor, id, target);
    audit.record(
        actor,
        "decision.record",
        "decision_record",
        d.id,
        null,
        Map.of("outcome", outcome, "riskTier", riskTier),
        "assigned underwriter; required documents accepted");
    return d;
  }
}
