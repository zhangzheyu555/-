package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.employeeassistant.EmployeeAssistantService;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Prevents deployment changes from silently coupling the operating-data assistant and employee
 * assistant credential families.
 */
class AssistantConfigurationContractTest {
  @Test
  void applicationConfigurationKeepsTheTwoCredentialFamiliesSeparate() {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource("application.yml"));
    Properties properties = factory.getObject();

    assertThat(properties)
        .containsEntry("app.assistant.deepseek.api-key", "${DEEPSEEK_API_KEY:}")
        .containsEntry("app.employee-assistant.api-token", "${EMPLOYEE_ASSISTANT_API_TOKEN:}")
        .containsEntry("app.employee-assistant.model-api-key", "${EMPLOYEE_ASSISTANT_MODEL_API_KEY:}");
    assertThat(properties.stringPropertyNames().stream()
        .filter(name -> name.startsWith("app.employee-assistant."))
        .map(properties::getProperty)
        .collect(Collectors.joining("\n")))
        .doesNotContain("DEEPSEEK_");
  }

  @Test
  void employeeAssistantSpringBindingsNeverReferenceOperatingAssistantSettings() {
    Constructor<?> constructor = Arrays.stream(EmployeeAssistantService.class.getConstructors())
        .filter(candidate -> candidate.isAnnotationPresent(Autowired.class))
        .findFirst()
        .orElseThrow();

    List<String> valueExpressions = Arrays.stream(constructor.getParameters())
        .map(parameter -> parameter.getAnnotation(Value.class))
        .filter(java.util.Objects::nonNull)
        .map(Value::value)
        .toList();

    assertThat(valueExpressions)
        .isNotEmpty()
        .allMatch(value -> value.contains("app.employee-assistant."))
        .noneMatch(value -> value.contains("DEEPSEEK") || value.contains("app.assistant.deepseek"));
  }

  @Test
  void operatingAssistantRequiresItsOwnNonBlankKeyWithoutChangingEmployeeAssistantState() {
    DeepSeekProperties properties = new DeepSeekProperties();

    assertThat(properties.isConfigured()).isFalse();
    properties.setApiKey("   ");
    assertThat(properties.isConfigured()).isFalse();
    properties.setApiKey("test-operating-assistant-key-not-a-real-secret");
    assertThat(properties.isConfigured()).isTrue();
    properties.setEnabled(false);
    assertThat(properties.isConfigured()).isFalse();
  }
}
