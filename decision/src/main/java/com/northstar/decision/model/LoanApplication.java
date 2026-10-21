package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "loan_application")
public class LoanApplication extends PanacheEntity {
  public String reference;

  @ManyToOne
  @JoinColumn(name = "borrower_id")
  public BorrowerProfile borrower;

  @ManyToOne
  @JoinColumn(name = "product_id")
  public LendingProduct product;

  public BigDecimal requestedAmount;
  public String currency;
  public String purpose;
  public int termMonths;
  public String status;
  public String riskTier;

  @ManyToOne
  @JoinColumn(name = "assigned_underwriter_id")
  public AppUser assignedUnderwriter;

  public Instant submittedAt;
  @Version public int version;
  public Instant createdAt;
  public Instant updatedAt;
}
