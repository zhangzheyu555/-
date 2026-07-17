package com.storeprofit.system.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  @Test
  void mapsMissingApiResourceToAStableNotFoundResponse() {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/api/assistant/operating-snapshot");
    request.setAttribute("requestId", "missing-endpoint-test");

    ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler().handleMissingResource(
        new NoResourceFoundException(HttpMethod.GET, "/api/assistant/operating-snapshot"),
        request
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isFalse();
    assertThat(response.getBody().code()).isEqualTo("API_ENDPOINT_UNAVAILABLE");
    assertThat(response.getBody().requestId()).isEqualTo("missing-endpoint-test");
  }
}
