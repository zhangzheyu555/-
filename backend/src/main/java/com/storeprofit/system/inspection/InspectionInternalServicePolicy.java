package com.storeprofit.system.inspection;

import java.net.URI;
import java.util.Set;

/**
 * Allows the backend to reach only the inspection service bundled into the same Docker network.
 */
final class InspectionInternalServicePolicy {
  private static final String HOST = "inspection-service";
  private static final int PORT = 8000;
  private static final Set<String> ALLOWED_PATHS = Set.of("/health", "/detect", "/export");

  private InspectionInternalServicePolicy() {
  }

  static boolean isAllowed(boolean enabled, String target) {
    if (!enabled) {
      return false;
    }
    try {
      URI uri = URI.create(target == null ? "" : target.trim());
      return "http".equalsIgnoreCase(uri.getScheme())
          && HOST.equalsIgnoreCase(uri.getHost())
          && uri.getPort() == PORT
          && uri.getUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null
          && ALLOWED_PATHS.contains(uri.getRawPath());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
