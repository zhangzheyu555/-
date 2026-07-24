package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InspectionInternalServicePolicyTest {
  @Test
  void inspectionServiceAllowsBundledEndpointsInEveryEnvironmentWhenEnabled() {
    for (String environment : List.of("TEST", "QA", "STAGING", "PRODUCTION")) {
      InspectionService service = service(environment, true);

      assertThat(outboundAllowed(service, "/health")).isTrue();
      assertThat(outboundAllowed(service, "/detect")).isTrue();
      assertThat(outboundAllowed(service, "/export")).isTrue();
    }
  }

  @Test
  void deniesBundledInspectionEndpointsWhenDisabled() {
    assertThat(InspectionInternalServicePolicy.isAllowed(
        false, "http://inspection-service:8000/detect")).isFalse();
  }

  @Test
  void deniesTargetsOutsideTheExactInternalAllowlist() {
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "https://inspection-service:8000/detect")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "http://inspection-service:8001/detect")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "http://127.0.0.1:8000/detect")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "http://inspection-service:8000/admin")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "http://inspection-service:8000/detect?target=metadata")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "http://user@inspection-service:8000/detect")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(
        true, "http://inspection-service:8000/detect#fragment")).isFalse();
    assertThat(InspectionInternalServicePolicy.isAllowed(true, "not a URI")).isFalse();
  }

  private InspectionService service(String environment, boolean enabled) {
    return new InspectionService(
        null,
        null,
        null,
        null,
        "http://inspection-service:8000/detect",
        "http://inspection-service:8000/export",
        Duration.ofSeconds(1),
        environment,
        "DISABLED",
        enabled,
        null);
  }

  private boolean outboundAllowed(InspectionService service, String path) {
    Boolean allowed = ReflectionTestUtils.invokeMethod(
        service, "outboundAllowed", "http://inspection-service:8000" + path);
    return Boolean.TRUE.equals(allowed);
  }
}
