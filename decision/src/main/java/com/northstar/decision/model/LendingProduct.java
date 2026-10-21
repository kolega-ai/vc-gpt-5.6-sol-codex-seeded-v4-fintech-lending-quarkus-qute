package com.northstar.decision.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "lending_product")
public class LendingProduct extends PanacheEntity {
  public String name;
  public String currency;
  public BigDecimal minAmount;
  public BigDecimal maxAmount;
  public int minTermMonths;
  public int maxTermMonths;
  public boolean active;
  public String rateExpression;
}
