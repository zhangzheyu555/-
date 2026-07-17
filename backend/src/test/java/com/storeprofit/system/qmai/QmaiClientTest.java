package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class QmaiClientTest {
  @Test
  void signsTheSortedCommonParametersWithHmacSha1() {
    QmaiProperties properties = configuredProperties();
    QmaiClient client = new QmaiClient(properties);

    assertThat(client.sign(1_700_000_000L, 7))
        .isEqualTo("Li9SlPf95F1297kJ7rhuUB7/Oi0=");
  }

  @Test
  void acceptsOnlyTheOfficialHttpsGateway() {
    QmaiProperties properties = configuredProperties();

    assertThat(properties.validatedBaseUri()).isEqualTo(URI.create("https://openapi.qmai.cn"));

    properties.setBaseUrl("http://openapi.qmai.cn");
    assertThatThrownBy(properties::validatedBaseUri).isInstanceOf(IllegalStateException.class);

    properties.setBaseUrl("https://proxy.example.test");
    assertThatThrownBy(properties::validatedBaseUri).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void credentialsAreRequiredAsOneCompleteSet() {
    QmaiProperties properties = new QmaiProperties();
    assertThat(properties.isConfigured()).isFalse();

    properties.setOpenId("open");
    properties.setGrantCode("grant");
    properties.setOpenKey("secret");
    assertThat(properties.isConfigured()).isTrue();

    properties.setOpenKey("   ");
    assertThat(properties.isConfigured()).isFalse();
  }

  private QmaiProperties configuredProperties() {
    QmaiProperties properties = new QmaiProperties();
    properties.setOpenId("open");
    properties.setGrantCode("grant");
    properties.setOpenKey("secret");
    return properties;
  }
}
