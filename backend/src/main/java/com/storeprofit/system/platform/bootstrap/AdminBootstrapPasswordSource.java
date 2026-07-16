package com.storeprofit.system.platform.bootstrap;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

@FunctionalInterface
public interface AdminBootstrapPasswordSource {
  String PASSWORD_ENVIRONMENT = "APP_BOOTSTRAP_ADMIN_PASSWORD";
  String PASSWORD_STDIN_ENVIRONMENT = "APP_BOOTSTRAP_ADMIN_PASSWORD_STDIN";

  char[] readPassword() throws IOException;

  static AdminBootstrapPasswordSource system(
      Map<String, String> environment,
      Console console,
      InputStream standardInput
  ) {
    return () -> {
      if (environment.containsKey(PASSWORD_ENVIRONMENT)) {
        String value = environment.get(PASSWORD_ENVIRONMENT);
        return value == null ? null : value.toCharArray();
      }
      if (console != null) {
        return console.readPassword();
      }
      if ("true".equals(environment.get(PASSWORD_STDIN_ENVIRONMENT))) {
        return readStandardInput(standardInput);
      }
      return null;
    };
  }

  private static char[] readStandardInput(InputStream standardInput) throws IOException {
    if (standardInput == null) {
      return null;
    }
    char[] buffer = new char[129];
    int length = 0;
    InputStreamReader reader = new InputStreamReader(standardInput, StandardCharsets.UTF_8);
    try {
      while (length < buffer.length) {
        int value = reader.read();
        if (value < 0 || value == '\n') {
          break;
        }
        if (value != '\r') {
          buffer[length++] = (char) value;
        }
      }
      return Arrays.copyOf(buffer, length);
    } finally {
      Arrays.fill(buffer, '\0');
    }
  }
}
