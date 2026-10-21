package com.northstar.decision.service;

import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ServerErrorException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class CoreGateway {
  @ConfigProperty(name = "security.core-key")
  String key;

  @ConfigProperty(name = "core.url")
  String coreUrl;

  private final ObjectMapper mapper;

  public CoreGateway(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Map<String, Object> business(String reference, String regionEndpoint) {
    try {
      String token = mint("business.read", reference);
      String base =
          regionEndpoint == null || regionEndpoint.isBlank() ? coreUrl : regionEndpoint;
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base + "/core/business/" + reference))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ServerErrorException("Local core rejected the scoped request", 502);
      return mapper.readValue(response.body(), Map.class);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerErrorException("Local core interrupted", 502);
    } catch (Exception e) {
      if (e instanceof ServerErrorException b) throw b;
      throw new ServerErrorException("Local core unavailable", 502);
    }
  }

  public String statementXml(String reference) {
    try {
      String token = mint("statement.read", reference);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(coreUrl + "/core/statement/" + reference + ".xml"))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      return HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .build()
          .send(request, HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerErrorException("Local core interrupted", 502);
    } catch (Exception e) {
      throw new ServerErrorException("Local core unavailable", 502);
    }
  }

  public Map<String, Object> revenueReport(String reference, String account) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://revenue-gateway.internal/v1/reports?apiKey="
                          + key
                          + "&account="
                          + account
                          + "&reference="
                          + reference))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ServerErrorException("Legacy revenue gateway rejected the request", 502);
      return mapper.readValue(response.body(), Map.class);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerErrorException("Legacy revenue gateway interrupted", 502);
    } catch (Exception e) {
      if (e instanceof ServerErrorException b) throw b;
      throw new ServerErrorException("Legacy revenue gateway unavailable", 502);
    }
  }

  public Map<String, Object> registry(String reference, String registryId) {
    try {
      HttpPost check = new HttpPost("http://registry.partners.internal/v2/records");
      check.setHeader("Authorization", "Bearer " + mint("registry.read", reference));
      check.setHeader("Content-Type", "application/json");
      check.setEntity(
          new StringEntity(
              mapper.writeValueAsString(
                  Map.of("reference", reference, "registryId", registryId == null ? "" : registryId)),
              StandardCharsets.UTF_8));
      try (CloseableHttpClient client = HttpClients.createDefault();
          CloseableHttpResponse response = client.execute(check)) {
        String body =
            org.apache.http.util.EntityUtils.toString(
                response.getEntity(), StandardCharsets.UTF_8);
        if (response.getStatusLine().getStatusCode() != 200)
          throw new ServerErrorException("Partner registry rejected the request", 502);
        return mapper.readValue(body, Map.class);
      }
    } catch (Exception e) {
      if (e instanceof ServerErrorException b) throw b;
      throw new ServerErrorException("Partner registry unavailable", 502);
    }
  }

  private String mint(String action, String resource) {
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                (action + "|" + resource + "|" + Instant.now().plusSeconds(60).getEpochSecond())
                    .getBytes(StandardCharsets.UTF_8));
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return payload
          + "."
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
