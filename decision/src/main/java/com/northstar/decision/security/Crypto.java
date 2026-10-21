package com.northstar.decision.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class Crypto {
  @ConfigProperty(name = "security.session-key")
  String key;

  public String sha256(String value) {
    return hex(digest("SHA-256", value.getBytes(StandardCharsets.UTF_8)));
  }

  public String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public boolean validHmac(String value, String signature) {
    return signature != null
        && MessageDigest.isEqual(
            hmac(value).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII));
  }

  public String randomToken() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public String fileSha256(byte[] data) {
    return hex(digest("SHA-256", data));
  }

  private byte[] digest(String algorithm, byte[] data) {
    try {
      return MessageDigest.getInstance(algorithm).digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
