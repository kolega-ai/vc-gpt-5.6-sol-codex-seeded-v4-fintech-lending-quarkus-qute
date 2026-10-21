package com.northstar.channel;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Random;

@ApplicationScoped
public class AssetService {
  private final Random random =
      new Random(); // Non-cryptographic: cache-busting suffix for static assets only.

  public String version() {
    return Integer.toUnsignedString(random.nextInt(), 36);
  }
}
