package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class InspectionRestrictedPilotConfigurationTest {
  @Test
  void qaYoloIsDefaultDeniedAndCanOnlyBeConfiguredThroughServerEnvironment() {
    Properties application = properties("application.yml");
    Properties qa = properties("application-qa.yml");

    assertThat(application).doesNotContainKey("app.inspection.outbound-mode");
    assertThat(qa)
        .containsEntry("app.inspection.detect-url", "${INSPECTION_DETECT_URL:http://127.0.0.1:19090/detect}")
        .containsEntry("app.inspection.export-url", "${INSPECTION_EXPORT_URL:http://127.0.0.1:19090/export}")
        .containsEntry("app.inspection.outbound-mode", "${INSPECTION_OUTBOUND_MODE:DISABLED}");
  }

  private Properties properties(String resource) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(resource));
    return factory.getObject();
  }
}
