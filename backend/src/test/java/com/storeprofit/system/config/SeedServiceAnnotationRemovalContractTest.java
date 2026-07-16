package com.storeprofit.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * R1-03: Verifies via reflection that no seed service class carries Spring
 * stereotype annotations (@Service, @Component), @PostConstruct, or
 * ApplicationRunner interface that would cause automatic runtime seed creation.
 */
class SeedServiceAnnotationRemovalContractTest {

  @Test
  @DisplayName("OrganizationSeedService does not have @Service, @Component or @PostConstruct")
  void organizationSeedServiceHasNoAutoTriggerAnnotations() {
    var cls = com.storeprofit.system.organization.OrganizationSeedService.class;
    assertThat(cls.getAnnotation(Service.class)).isNull();
    assertThat(cls.getAnnotation(Component.class)).isNull();
    assertThat(cls.getAnnotation(PostConstruct.class)).isNull();
    for (Method m : cls.getDeclaredMethods()) {
      assertThat(m.getAnnotation(PostConstruct.class))
          .as("Method %s must not have @PostConstruct", m.getName()).isNull();
    }
  }

  @Test
  @DisplayName("FinanceSeedService does not have @Service, @Component or @PostConstruct")
  void financeSeedServiceHasNoAutoTriggerAnnotations() {
    var cls = com.storeprofit.system.finance.FinanceSeedService.class;
    assertThat(cls.getAnnotation(Service.class)).isNull();
    assertThat(cls.getAnnotation(Component.class)).isNull();
    assertThat(cls.getAnnotation(PostConstruct.class)).isNull();
    for (Method m : cls.getDeclaredMethods()) {
      assertThat(m.getAnnotation(PostConstruct.class))
          .as("Method %s must not have @PostConstruct", m.getName()).isNull();
    }
  }

  @Test
  @DisplayName("LegacyEmployeeSeedService does not have @Component or implement ApplicationRunner")
  void legacyEmployeeSeedServiceIsNotApplicationRunner() {
    var cls = com.storeprofit.system.employee.LegacyEmployeeSeedService.class;
    assertThat(cls.getAnnotation(Component.class)).isNull();
    assertThat(ApplicationRunner.class.isAssignableFrom(cls))
        .as("LegacyEmployeeSeedService must not implement ApplicationRunner")
        .isFalse();
  }

  @Test
  @DisplayName("No seed-related class is in the full set of auto-detected Spring stereotypes")
  void seedClassesExcludedFromAllSteretypes() {
    Set<Class<?>> seeds = Set.of(
        com.storeprofit.system.organization.OrganizationSeedService.class,
        com.storeprofit.system.finance.FinanceSeedService.class,
        com.storeprofit.system.employee.LegacyEmployeeSeedService.class
    );
    for (Class<?> cls : seeds) {
      assertThat(cls.getAnnotation(Service.class))
          .as("%s must not have @Service", cls.getSimpleName()).isNull();
      assertThat(cls.getAnnotation(Component.class))
          .as("%s must not have @Component", cls.getSimpleName()).isNull();
      assertThat(ApplicationRunner.class.isAssignableFrom(cls))
          .as("%s must not implement ApplicationRunner", cls.getSimpleName()).isFalse();
    }
  }
}
