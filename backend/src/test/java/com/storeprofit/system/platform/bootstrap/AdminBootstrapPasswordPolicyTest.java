package com.storeprofit.system.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminBootstrapPasswordPolicyTest {
  @Test
  void requiresLengthAndAllFourCharacterCategories() {
    assertThat(AdminBootstrapPasswordPolicy.isValid("Short!4Aa".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("lowercase!4826".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("UPPERCASE!4826".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("NoDigits!Cloud".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("NoSymbolCloud4826".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("CloudZebra!48 ".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("CloudZebra!48\n".toCharArray(), "boss.user"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid("Zebra!Cloud4826".toCharArray(), "boss.user"))
        .isTrue();
  }

  @Test
  void rejectsUsernameAndCommonWeakPatterns() {
    assertThat(AdminBootstrapPasswordPolicy.isValid(
        "First.Boss!Cloud4826".toCharArray(), "first.boss"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid(
        "Password!Cloud4826".toCharArray(), "first.boss"))
        .isFalse();
    assertThat(AdminBootstrapPasswordPolicy.isValid(
        "Zebra!Cloud1238".toCharArray(), "first.boss"))
        .isFalse();
  }
}
