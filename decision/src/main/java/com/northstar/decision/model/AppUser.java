package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser extends PanacheEntity {
  public String email;
  public String passwordHash;
  public String role;
  public String displayName;
  public String phone;
  public boolean enabled;
  public Instant createdAt;
  public Instant updatedAt;

  public static AppUser byEmail(String email) {
    return find("lower(email) = lower(?1)", email).firstResult();
  }
}
