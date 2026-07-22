package com.storeprofit.system.config;

import java.net.URI;
import java.util.Locale;

/**
 * Explicit transport boundary for services exercised from the disposable QA environment.
 *
 * <p>QA and TEST never make a live outbound call. They must use {@code MOCK} mode and a literal
 * loopback target. Redirect following is separately disabled by each HTTP client, so a permitted
 * local mock cannot turn this approval into a network escape.</p>
 */
public final class LocalMockOutboundPolicy {
  private LocalMockOutboundPolicy() {
  }

  public static boolean isAllowed(String environment, String mode, String target) {
    String normalizedEnvironment = normalized(environment);
    String normalizedMode = normalized(mode);
    if ("QA".equals(normalizedEnvironment) || "TEST".equals(normalizedEnvironment)) {
      return "MOCK".equals(normalizedMode) && isLoopback(target);
    }
    if ("LIVE".equals(normalizedMode)) {
      return true;
    }
    return "MOCK".equals(normalizedMode) && isLoopback(target);
  }

  public static boolean isLoopback(String target) {
    try {
      URI uri = URI.create(target == null ? "" : target.trim());
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null) {
        return false;
      }
      return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
