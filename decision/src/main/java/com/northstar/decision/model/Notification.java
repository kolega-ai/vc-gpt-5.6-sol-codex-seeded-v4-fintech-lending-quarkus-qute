package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification")
public class Notification extends PanacheEntity {
  @ManyToOne
  @JoinColumn(name = "user_id")
  public AppUser user;

  public String title;
  public String body;
  public String link;
  public Instant readAt;
  public Instant createdAt;
}
