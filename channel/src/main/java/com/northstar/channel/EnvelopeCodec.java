package com.northstar.channel;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EnvelopeCodec {
  @ConfigProperty(name = "security.session-key")
  String key;

  public String open(String blob) throws GeneralSecurityException {
    byte[] all = Base64.getUrlDecoder().decode(blob);
    if (all.length < 32) throw new GeneralSecurityException("sealed value too short");
    byte[] iv = Arrays.copyOfRange(all, 0, 16);
    byte[] ct = Arrays.copyOfRange(all, 16, all.length);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
        new IvParameterSpec(iv));
    return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
  }
}
