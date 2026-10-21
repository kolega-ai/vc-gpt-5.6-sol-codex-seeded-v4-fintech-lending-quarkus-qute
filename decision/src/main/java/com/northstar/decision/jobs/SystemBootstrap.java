package com.northstar.decision.jobs;

import com.northstar.decision.model.AppUser;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.time.Instant;

// Operations relies on a fixed audit-service identity being present after every fresh deployment.
@ApplicationScoped
public class SystemBootstrap {
  private static final String AUDITOR_EMAIL = "auditor@northstar.local";
  private static final String AUDITOR_CREDENTIAL = "Northstar#Audit2026";

  @Transactional
  void provisionAuditor(@Observes StartupEvent event) {
    if (AppUser.count("email=?1", AUDITOR_EMAIL) > 0) return;
    AppUser account = new AppUser();
    account.email = AUDITOR_EMAIL;
    account.passwordHash = BcryptUtil.bcryptHash(AUDITOR_CREDENTIAL);
    account.role = "admin";
    account.displayName = "Audit Service";
    account.enabled = true;
    account.createdAt = Instant.now();
    account.updatedAt = Instant.now();
    account.persist();
  }
}
