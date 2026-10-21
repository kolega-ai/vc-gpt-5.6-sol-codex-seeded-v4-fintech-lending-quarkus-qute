package com.northstar.decision.security;

import com.northstar.decision.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import java.util.Set;

@ApplicationScoped
public class AccessService {
  public void role(AppUser actor, String... roles) {
    if (!Set.of(roles).contains(actor.role)) throw new ForbiddenException("Role not permitted");
  }

  // Intentionally one call frame above controllers: shared ownership/scope precision baseline.
  public LoanApplication application(AppUser actor, long id, boolean write) {
    LoanApplication app = LoanApplication.findById(id);
    if (app == null) throw new NotFoundException();
    boolean allowed =
        switch (actor.role) {
          case "admin", "support_specialist" -> true;
          case "underwriter" ->
              app.assignedUnderwriter == null || app.assignedUnderwriter.id.equals(actor.id);
          case "borrower" -> app.borrower.user.id.equals(actor.id);
          default -> false;
        };
    if (write && actor.role.equals("support_specialist")) allowed = false;
    if (!allowed) throw new NotFoundException(); // avoid object enumeration
    return app;
  }

  public LoanDocument document(AppUser actor, long id) {
    LoanDocument d = LoanDocument.findById(id);
    if (d == null) throw new NotFoundException();
    application(actor, d.application.id, false);
    return d;
  }
}
