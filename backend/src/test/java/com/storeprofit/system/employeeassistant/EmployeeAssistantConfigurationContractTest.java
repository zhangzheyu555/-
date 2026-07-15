package com.storeprofit.system.employeeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class EmployeeAssistantConfigurationContractTest {
  @Test
  void springMustUseTheConfigurationAwareConstructor() {
    Constructor<?> constructor = Arrays.stream(EmployeeAssistantService.class.getConstructors())
        .filter(candidate -> candidate.getParameterCount() == 12)
        .findFirst()
        .orElseThrow();

    assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
  }

  @Test
  void employeeAssistantProviderRemainsBoundUnderItsOwnConfigurationNamespace() {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource("application.yml"));
    Properties properties = factory.getObject();

    assertThat(properties)
        .containsEntry("app.employee-assistant.provider", "${EMPLOYEE_ASSISTANT_PROVIDER:}")
        .containsKeys(
            "app.employee-assistant.upstream-url",
            "app.employee-assistant.api-token",
            "app.employee-assistant.model-url",
            "app.employee-assistant.model-api-key",
            "app.employee-assistant.model-name"
        );
  }
}
