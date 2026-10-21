package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_session")
public class UserSession extends PanacheEntityBase {
  @Id public String tokenHash;

  @ManyToOne
  @JoinColumn(name = "user_id")
  public AppUser user;

  public Instant expiresAt;
  public Instant revokedAt;
  public Instant createdAt;
}
