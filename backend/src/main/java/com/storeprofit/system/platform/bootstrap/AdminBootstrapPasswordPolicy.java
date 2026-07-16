package com.storeprofit.system.platform.bootstrap;

import java.util.List;

final class AdminBootstrapPasswordPolicy {
  private static final List<String> WEAK_PATTERNS = List.of(
      "123",
      "password",
      "qwerty",
      "letmein",
      "changeme",
      "welcome",
      "admin",
      "p@ssw0rd"
  );

  private AdminBootstrapPasswordPolicy() {
  }

  static boolean isValid(char[] password, String normalizedUsername) {
    if (password == null || password.length < 12 || password.length > 128) {
      return false;
    }
    boolean upper = false;
    boolean lower = false;
    boolean digit = false;
    boolean symbol = false;
    for (char value : password) {
      if (Character.isWhitespace(value) || Character.isISOControl(value)) {
        return false;
      }
      upper |= Character.isUpperCase(value);
      lower |= Character.isLowerCase(value);
      digit |= Character.isDigit(value);
      symbol |= !Character.isLetterOrDigit(value);
    }
    if (!upper || !lower || !digit || !symbol) {
      return false;
    }
    if (normalizedUsername != null && containsIgnoreCase(password, normalizedUsername)) {
      return false;
    }
    for (String weakPattern : WEAK_PATTERNS) {
      if (containsIgnoreCase(password, weakPattern)) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsIgnoreCase(char[] value, String candidate) {
    if (candidate == null || candidate.isEmpty() || candidate.length() > value.length) {
      return false;
    }
    for (int start = 0; start <= value.length - candidate.length(); start++) {
      boolean match = true;
      for (int offset = 0; offset < candidate.length(); offset++) {
        if (Character.toLowerCase(value[start + offset])
            != Character.toLowerCase(candidate.charAt(offset))) {
          match = false;
          break;
        }
      }
      if (match) {
        return true;
      }
    }
    return false;
  }
}
