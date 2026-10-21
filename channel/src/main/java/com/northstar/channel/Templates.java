package com.northstar.channel;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import java.util.List;
import java.util.Map;

@CheckedTemplate(requireTypeSafeExpressions = false)
public class Templates {
  public static native TemplateInstance login(boolean error, String cache);

  public static native TemplateInstance auth(
      String mode, String message, String error, String token, String cache);

  public static native TemplateInstance page(PageView view);

  public record PageView(
      String kind,
      String title,
      UserView user,
      ViewData model,
      String csrf,
      String flash,
      String error,
      String cache) {}

  public record UserView(
      Long id, String email, String role, String displayName, String phone, boolean enabled) {
    public UserView(Map<String, Object> value) {
      this(
          ((Number) value.get("id")).longValue(),
          string(value, "email"),
          string(value, "role"),
          string(value, "displayName"),
          string(value, "phone"),
          Boolean.TRUE.equals(value.get("enabled")));
    }

    public String initial() {
      return displayName == null || displayName.isBlank() ? "?" : displayName.substring(0, 1);
    }

    public String roleLabel() {
      return role.replace('_', ' ');
    }
  }

  public static final class ViewData {
    private final Map<String, Object> value;

    public ViewData(Map<String, Object> value) {
      this.value = value;
    }

    public Object openApplications() {
      return value.get("openApplications");
    }

    public Object documentActions() {
      return value.get("documentActions");
    }

    public Object total() {
      return value.getOrDefault("total", 0);
    }

    public Object id() {
      return value.get("id");
    }

    public String status() {
      return string(value, "status");
    }

    public String businessName() {
      return string(value, "businessName");
    }

    public String reference() {
      return string(value, "reference");
    }

    public String product() {
      return string(value, "product");
    }

    public Object productId() {
      return value.get("productId");
    }

    public Object currency() {
      return value.get("currency");
    }

    public Object requestedAmount() {
      return value.get("requestedAmount");
    }

    public Object termMonths() {
      return value.get("termMonths");
    }

    public String purpose() {
      return string(value, "purpose");
    }

    public Object riskTier() {
      return value.get("riskTier");
    }

    public Object assignedUnderwriter() {
      return value.get("assignedUnderwriter");
    }

    public Object updatedAt() {
      return value.get("updatedAt");
    }

    public Map<String, Object> decision() {
      return map("decision");
    }

    public Object borrowerPhone() {
      return value.get("borrowerPhone");
    }

    public Object addressLine1() {
      return value.get("addressLine1");
    }

    public Object city() {
      return value.get("city");
    }

    public Object region() {
      return value.get("region");
    }

    public Object postalCode() {
      return value.get("postalCode");
    }

    public List<Map<String, Object>> applications() {
      return list("applications");
    }

    public List<Map<String, Object>> notifications() {
      return list("notifications");
    }

    public List<Map<String, Object>> items() {
      return list("items");
    }

    public List<Map<String, Object>> products() {
      return list("products");
    }

    public List<Map<String, Object>> documents() {
      return list("documents");
    }

    public List<Map<String, Object>> messages() {
      return list("messages");
    }

    public List<Map<String, Object>> notes() {
      return list("notes");
    }

    public List<Map<String, Object>> auditHistory() {
      return list("auditHistory");
    }

    public List<Map<String, Object>> productsAdmin() {
      return list("products");
    }

    public List<Map<String, Object>> documentTypes() {
      return list("documentTypes");
    }

    public List<Map<String, Object>> webhooks() {
      return list("webhooks");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String key) {
      Object item = value.get(key);
      return item instanceof List<?> ? (List<Map<String, Object>>) item : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String key) {
      Object item = value.get(key);
      return item instanceof Map<?, ?> ? (Map<String, Object>) item : null;
    }
  }

  private static String string(Map<String, Object> value, String key) {
    Object item = value.get(key);
    return item == null ? null : item.toString();
  }
}
