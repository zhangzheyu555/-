package com.storeprofit.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.http.HttpHeaders;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

class CorsConfigTest {
  @Test
  void defaultPolicyAllowsOnlyLoopbackOrigins() throws Exception {
    CorsConfig.CorsProperties properties = new CorsConfig.CorsProperties();
    CorsFilter filter = new CorsConfig().corsFilter(properties);

    MockHttpServletRequest request = corsRequest("http://localhost:5173");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("http://localhost:5173");
    verify(chain).doFilter(request, response);
  }

  @Test
  void defaultPolicyRejectsRemovedPublicIpOrigin() throws Exception {
    CorsConfig.CorsProperties properties = new CorsConfig.CorsProperties();
    CorsFilter filter = new CorsConfig().corsFilter(properties);

    MockHttpServletRequest request = corsRequest("http://175.178.89.183:5173");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void explicitlyConfiguredExternalOriginIsAllowed() throws Exception {
    CorsConfig.CorsProperties properties = new CorsConfig.CorsProperties();
    properties.setAllowedOriginPatterns(java.util.List.of("https://internal-pilot.example.test"));
    CorsFilter filter = new CorsConfig().corsFilter(properties);

    MockHttpServletRequest request = corsRequest("https://internal-pilot.example.test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("https://internal-pilot.example.test");
    verify(chain).doFilter(request, response);
  }

  @Test
  void dockerStyleOriginEnvironmentVariableBindsToConfiguredOriginList() {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        "docker-test",
        Map.of(
            "APP_CORS_ALLOWED_ORIGIN_PATTERNS",
            "http://localhost:8088,http://127.0.0.1:8088")));

    CorsConfig.CorsProperties properties = Binder.get(environment)
        .bind("app.cors", Bindable.of(CorsConfig.CorsProperties.class))
        .orElseThrow(() -> new AssertionError("Docker CORS environment variable was not bound"));

    assertThat(properties.getAllowedOriginPatterns())
        .containsExactly("http://localhost:8088", "http://127.0.0.1:8088");
  }

  private MockHttpServletRequest corsRequest(String origin) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
    request.setRequestURI("/api/health");
    request.addHeader(HttpHeaders.ORIGIN, origin);
    return request;
  }
}
