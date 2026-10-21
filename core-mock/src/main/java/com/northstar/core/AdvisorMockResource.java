package com.northstar.core;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/*
 * Local stand-in for the hosted underwriting model that the decision tier consults.
 * It keeps the advisor workflow exercisable without a SaaS account: it reads the
 * operator brief and answers with the recommendation the hosted model would return,
 * following the instructions carried in the prompt like the real endpoint does.
 */
@Path("/assistant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdvisorMockResource {
  @ConfigProperty(name = "security.assistant-key")
  String apiKey;

  @POST
  @Path("/generate")
  public Map<String, Object> generate(
      @HeaderParam("X-Advisor-Key") String credential, Map<String, Object> request) {
    check(credential);
    String brief = String.valueOf(request.getOrDefault("input", ""));
    BigDecimal requested = numeric(String.valueOf(request.getOrDefault("requestedAmount", "0")));
    Map<String, Object> reply = new LinkedHashMap<>();
    String lower = brief.toLowerCase(Locale.ROOT);
    if (lower.contains("decline") || lower.contains("reject")) {
      reply.put("outcome", "rejected");
      reply.put("riskTier", "D");
      reply.put("reasons", "Advisor recommendation: " + brief.trim());
    } else if (lower.contains("approve")) {
      reply.put("outcome", "approved");
      reply.put("approvedAmount", requested);
      reply.put("riskTier", "A");
      reply.put("reasons", "Advisor recommendation: " + brief.trim());
    } else {
      reply.put("outcome", "counteroffer");
      reply.put("approvedAmount", requested.multiply(new BigDecimal("0.80")));
      reply.put("riskTier", "B");
      reply.put("reasons", "Advisor assessed the credit file from the submitted brief.");
    }
    return reply;
  }

  @POST
  @Path("/chat")
  public Map<String, Object> chat(
      @HeaderParam("X-Advisor-Key") String credential, Map<String, Object> request) {
    check(credential);
    String message = String.valueOf(request.getOrDefault("message", ""));
    BigDecimal requested = numeric(String.valueOf(request.getOrDefault("requestedAmount", "0")));
    String lower = message.toLowerCase(Locale.ROOT);
    Map<String, Object> reply = new LinkedHashMap<>();
    if (lower.contains("adjust") || lower.contains("amount")) {
      reply.put("tool", "adjust_amount");
      reply.put("arguments", Map.of("amount", numeric(amountIn(message))));
    } else if (lower.contains("decline") || lower.contains("reject")) {
      reply.put(
          "tool",
          "record_decision");
      reply.put(
          "arguments",
          Map.of("outcome", "rejected", "reasons", message.trim(), "riskTier", "D"));
    } else if (lower.contains("approve") || lower.contains("decide")) {
      reply.put(
          "tool",
          "record_decision");
      reply.put(
          "arguments",
          Map.of(
              "outcome", "approved",
              "amount", requested,
              "reasons", message.trim(),
              "riskTier", "A"));
    } else {
      reply.put("text", "I could not map that brief to an available file action.");
    }
    return reply;
  }

  private void check(String credential) {
    if (apiKey == null
        || apiKey.isBlank()
        || credential == null
        || !credential.equals("Bearer " + apiKey))
      throw new NotAuthorizedException("Advisor credential required");
  }

  private BigDecimal numeric(String value) {
    String cleaned = value == null ? "" : value.replaceAll("[^0-9.]", "");
    return cleaned.isBlank() ? BigDecimal.ZERO : new BigDecimal(cleaned);
  }

  private String amountIn(String message) {
    Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(message);
    return m.find() ? m.group(1) : "0";
  }
}
