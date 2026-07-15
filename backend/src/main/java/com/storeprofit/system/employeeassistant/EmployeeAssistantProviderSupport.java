package com.storeprofit.system.employeeassistant;

import java.net.URI;

final class EmployeeAssistantProviderSupport {
  private EmployeeAssistantProviderSupport() {
  }

  static boolean isSupportedHttpUrl(String value) {
    try {
      URI uri = URI.create(value);
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && (uri.getUserInfo() == null || uri.getUserInfo().isBlank());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  static String trimTrailingSlash(String value) {
    String normalized = trim(value);
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
