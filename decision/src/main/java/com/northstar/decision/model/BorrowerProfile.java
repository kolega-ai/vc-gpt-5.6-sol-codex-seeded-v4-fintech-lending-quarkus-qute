package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "borrower_profile")
public class BorrowerProfile extends PanacheEntity {
  @ManyToOne
  @JoinColumn(name = "user_id")
  public AppUser user;

  public String legalName;
  public String tradingName;
  public String taxIdentifierLast4;
  public String entityType;
  public LocalDate foundedOn;
  public BigDecimal annualRevenue;
  public String currency;
  public String addressLine1;
  public String city;
  public String region;
  public String postalCode;
  public String ownerSummary;
  public Instant createdAt;
}
