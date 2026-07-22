package com.storeprofit.system.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthenticationFilterTest {
  @Test
  void protectsApiEndpointsBeforeControllersAndReturns401ForMissingToken() throws Exception {
    AuthService authService = mock(AuthService.class);
    when(authService.requireUser(null)).thenThrow(new BusinessException(
        "UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    ApiAuthenticationFilter filter = new ApiAuthenticationFilter(authService, new ObjectMapper());
    MockHttpServletRequest request = request("GET", "/api/finance/entries");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void passesAuthenticatedUserToTheRequestContext() throws Exception {
    AuthService authService = mock(AuthService.class);
    AuthUser user = new AuthUser(
        7L, 2L, "测试租户", "boss", "hash", "老板", "BOSS", null, true);
    when(authService.requireUser("Bearer usable-token")).thenReturn(user);
    ApiAuthenticationFilter filter = new ApiAuthenticationFilter(authService, new ObjectMapper());
    MockHttpServletRequest request = request("GET", "/api/finance/entries");
    request.addHeader("Authorization", "Bearer usable-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(ApiAuthenticationFilter.currentUser(request)).isSameAs(user);
    verify(chain).doFilter(request, response);
  }

  @Test
  void keepsOnlyExplicitAnonymousOrSignatureProtectedEndpointsOutsideBearerAuth() throws Exception {
    AuthService authService = mock(AuthService.class);
    ApiAuthenticationFilter filter = new ApiAuthenticationFilter(authService, new ObjectMapper());

    for (MockHttpServletRequest request : new MockHttpServletRequest[] {
        request("POST", "/api/auth/login"),
        request("POST", "/api/auth/initial-password"),
        request("GET", "/api/health"),
        request("POST", "/api/eleme/message"),
        request("OPTIONS", "/api/finance/entries")
    }) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      FilterChain chain = mock(FilterChain.class);
      filter.doFilter(request, response, chain);
      verify(chain).doFilter(request, response);
    }
    verify(authService, never()).requireUser(any());
  }

  @Test
  void keepsHealthDiagnosticsBehindBearerAuthentication() throws Exception {
    AuthService authService = mock(AuthService.class);
    when(authService.requireUser(null)).thenThrow(new BusinessException(
        "UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    ApiAuthenticationFilter filter = new ApiAuthenticationFilter(authService, new ObjectMapper());
    MockHttpServletRequest request = request("GET", "/api/health/diagnostics");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    verify(chain, never()).doFilter(any(), any());
  }

  private MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    return request;
  }
}
