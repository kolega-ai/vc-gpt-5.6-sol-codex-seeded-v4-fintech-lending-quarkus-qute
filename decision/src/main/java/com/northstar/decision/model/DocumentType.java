package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "document_type")
public class DocumentType extends PanacheEntity {
  public String name;
  public String category;
  public boolean required;
  public boolean active;
}
