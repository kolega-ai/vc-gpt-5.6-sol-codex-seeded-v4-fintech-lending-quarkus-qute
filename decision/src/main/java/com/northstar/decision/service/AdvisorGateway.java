package com.northstar.decision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ServerErrorException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdvisorGateway {
  @ConfigProperty(name = "assistant.url")
  String advisorUrl;

  @ConfigProperty(name = "assistant.api-key")
  String apiKey;

  private final ObjectMapper mapper;

  public AdvisorGateway(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Map<String, Object> generate(String policy, String brief, String requestedAmount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policy", policy);
    body.put("input", brief);
    body.put("requestedAmount", requestedAmount);
    return post("/assistant/generate", body);
  }

  public Map<String, Object> chat(
      String message, List<Map<String, Object>> tools, String requestedAmount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", message);
    body.put("tools", tools);
    body.put("requestedAmount", requestedAmount);
    return post("/assistant/chat", body);
  }

  private Map<String, Object> post(String path, Map<String, Object> body) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(advisorUrl + path))
              .timeout(Duration.ofSeconds(8))
              .header("X-Advisor-Key", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ServerErrorException("Underwriting advisor rejected the request", 502);
      return mapper.readValue(response.body(), Map.class);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerErrorException("Underwriting advisor interrupted", 502);
    } catch (Exception e) {
      if (e instanceof ServerErrorException b) throw b;
      throw new ServerErrorException("Underwriting advisor unavailable", 502);
    }
  }
}
