package com.northstar.decision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.northstar.decision.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;

@ApplicationScoped
public class AuditService {
  private final ObjectMapper mapper;

  public AuditService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional
  public void record(
      AppUser actor,
      String action,
      String type,
      Object id,
      Object before,
      Object after,
      String basis) {
    AuditEvent e = new AuditEvent();
    e.actor = actor;
    e.action = action;
    e.resourceType = type;
    e.resourceId = id == null ? null : id.toString();
    e.beforeValue = json(before);
    e.afterValue = json(after);
    e.authorizationBasis = basis;
    e.occurredAt = Instant.now();
    e.persist();
  }

  private com.fasterxml.jackson.databind.JsonNode json(Object value) {
    return value == null ? null : mapper.valueToTree(value);
  }
}
