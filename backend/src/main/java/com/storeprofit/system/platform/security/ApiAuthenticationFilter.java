package com.storeprofit.system.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the common API authentication boundary before a controller is selected.
 *
 * <p>Business controllers keep their existing authorization checks for now, but an accidentally
 * added endpoint can no longer become anonymous merely because it omits a header parameter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiAuthenticationFilter extends OncePerRequestFilter {
  public static final String AUTH_USER_ATTRIBUTE =
      ApiAuthenticationFilter.class.getName() + ".authenticatedUser";

  private final AuthService authService;
  private final ObjectMapper objectMapper;

  public ApiAuthenticationFilter(AuthService authService, ObjectMapper objectMapper) {
    this.authService = authService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = applicationPath(request);
    if (!path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    return ("POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/login".equals(path))
        || ("GET".equalsIgnoreCase(request.getMethod()) && "/api/health".equals(path))
        // This endpoint is authenticated by its HMAC signature in ElemeWebhookService.
        || ("POST".equalsIgnoreCase(request.getMethod()) && "/api/eleme/message".equals(path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    try {
      AuthUser user = authService.requireUser(request.getHeader(HttpHeaders.AUTHORIZATION));
      request.setAttribute(AUTH_USER_ATTRIBUTE, user);
      filterChain.doFilter(request, response);
    } catch (BusinessException exception) {
      writeBusinessError(request, response, exception);
    }
  }

  public static AuthUser currentUser(HttpServletRequest request) {
    Object user = request.getAttribute(AUTH_USER_ATTRIBUTE);
    return user instanceof AuthUser authUser ? authUser : null;
  }

  private void writeBusinessError(
      HttpServletRequest request,
      HttpServletResponse response,
      BusinessException exception
  ) throws IOException {
    response.setStatus(exception.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(),
        ApiResponse.fail(exception.getCode(), exception.getMessage(), RequestIdFilter.getRequestId(request))
    );
  }

  private String applicationPath(HttpServletRequest request) {
    String contextPath = request.getContextPath();
    String requestUri = request.getRequestURI();
    if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
      return requestUri.substring(contextPath.length());
    }
    return requestUri;
  }
}
