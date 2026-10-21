package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "decision_record")
public class DecisionRecord extends PanacheEntity {
  @OneToOne
  @JoinColumn(name = "application_id")
  public LoanApplication application;

  @ManyToOne
  @JoinColumn(name = "decided_by")
  public AppUser decidedBy;

  public String outcome;
  public BigDecimal approvedAmount;
  public String currency;
  public String riskTier;
  public String reasons;
  public boolean finalDecision;
  public Instant createdAt;
}
