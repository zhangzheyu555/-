package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class QmaiConfigurationContractTest {
  @Test
  void credentialsComeOnlyFromDeploymentEnvironmentVariables() {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource("application.yml"));
    Properties properties = factory.getObject();

    assertThat(properties)
        .containsEntry("app.qmai.open-id", "${QMAI_OPEN_ID:}")
        .containsEntry("app.qmai.grant-code", "${QMAI_GRANT_CODE:}")
        .containsEntry("app.qmai.open-key", "${QMAI_OPEN_KEY:}")
        .containsEntry("app.qmai.base-url", "${QMAI_BASE_URL:https://openapi.qmai.cn}");
  }
}
