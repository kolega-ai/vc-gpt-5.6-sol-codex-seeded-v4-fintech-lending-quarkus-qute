package com.northstar.decision.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_event")
public class AuditEvent extends PanacheEntity {
  @ManyToOne
  @JoinColumn(name = "actor_id")
  public AppUser actor;

  public String action;
  public String resourceType;
  public String resourceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public JsonNode beforeValue;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public JsonNode afterValue;

  public String authorizationBasis;
  public Instant occurredAt;
}
