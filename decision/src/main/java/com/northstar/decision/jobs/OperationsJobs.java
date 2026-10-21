package com.northstar.decision.jobs;

import com.northstar.decision.model.*;
import com.northstar.decision.service.AuditService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class OperationsJobs {
  private final AuditService audit;

  public OperationsJobs(AuditService audit) {
    this.audit = audit;
  }

  @Scheduled(cron = "0 15 2 * * ?")
  @Transactional
  void expireDocuments() {
    List<LoanDocument> docs =
        LoanDocument.list("status='accepted' and expiresOn < ?1", LocalDate.now());
    for (LoanDocument d : docs) {
      d.status = "expired";
      LoanDocument.getEntityManager()
          .createNativeQuery(
              "insert into notification(user_id,title,body,link) values (?1,'Document"
                  + " expired',?2,?3)")
          .setParameter(1, d.application.borrower.user.id)
          .setParameter(2, d.documentType.name + " must be uploaded again.")
          .setParameter(3, "/applications/" + d.application.id)
          .executeUpdate();
      audit.record(
          null,
          "document.expire",
          "loan_document",
          d.id,
          Map.of("status", "accepted"),
          Map.of("status", "expired"),
          "scheduled expiration policy");
    }
  }

  @Scheduled(every = "15m")
  @Transactional
  void deliverOutbox() {
    LoanApplication.getEntityManager()
        .createNativeQuery(
            "update outbox set status='sent',sent_at=now() where id in (select id from outbox where"
                + " status='pending' order by created_at limit 50)")
        .executeUpdate();
  }

  @Scheduled(every = "10m")
  @Transactional
  void deliverWebhooks() {
    LoanApplication.getEntityManager()
        .createNativeQuery(
            "update webhook_delivery set"
                + " status_code=202,attempt_count=attempt_count+1,next_attempt_at=null where id in"
                + " (select id from webhook_delivery where status_code is null and"
                + " next_attempt_at<=now() order by created_at limit 50)")
        .executeUpdate();
  }
}
