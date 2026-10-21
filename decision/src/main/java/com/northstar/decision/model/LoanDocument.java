package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "loan_document")
public class LoanDocument extends PanacheEntity {
  @ManyToOne
  @JoinColumn(name = "application_id")
  public LoanApplication application;

  @ManyToOne
  @JoinColumn(name = "document_type_id")
  public DocumentType documentType;

  public String status;
  public String originalName;
  public String storedName;
  public String mediaType;
  public Long sizeBytes;
  public String sha256;
  public String borrowerReason;

  @ManyToOne
  @JoinColumn(name = "reviewer_id")
  public AppUser reviewer;

  public LocalDate expiresOn;
  public Instant uploadedAt;
  public Instant createdAt;
}
