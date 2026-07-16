package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceCharArrayTest {
  private static final String TEST_PASSWORD = "TEST_ONLY_A9!CharArray_Value";

  private final PasswordService passwordService = new PasswordService();

  @Test
  void stringGeneratedHashAuthenticatesCharArrayWithoutMutatingCallerPassword() {
    String encoded = passwordService.hash(TEST_PASSWORD);
    char[] submitted = TEST_PASSWORD.toCharArray();
    char[] original = submitted.clone();

    assertThat(passwordService.matches(submitted, encoded)).isTrue();
    assertThat(submitted).containsExactly(original);
  }

  @Test
  void charArrayGeneratedHashAuthenticatesStringWithoutMutatingCallerPassword() {
    char[] submitted = TEST_PASSWORD.toCharArray();
    char[] original = submitted.clone();

    String encoded = passwordService.hash(submitted);

    assertThat(submitted).containsExactly(original);
    assertThat(passwordService.matches(TEST_PASSWORD, encoded)).isTrue();
    assertThat(encoded).startsWith("pbkdf2$120000$");
  }
}
