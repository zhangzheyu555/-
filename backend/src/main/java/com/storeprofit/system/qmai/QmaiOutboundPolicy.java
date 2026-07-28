package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.config.LocalMockOutboundPolicy;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Explicit allow-list boundary for QMAI network traffic. */
@Component
public class QmaiOutboundPolicy {
  private static final Set<String> OPEN_API_HOSTS = Set.of("openapi.qmai.cn");
  private static final Set<String> CONSOLE_API_HOSTS = Set.of("inapi.qmai.cn");
  private final QmaiProperties properties;

  public QmaiOutboundPolicy(QmaiProperties properties) {
    this.properties = properties;
  }

  public void requireAllowed(String target) {
    String mode = normalized(properties.getOutboundMode());
    if ("MOCK".equals(mode) && LocalMockOutboundPolicy.isLoopback(target)) {
      return;
    }
    if ("LIVE".equals(mode) && isOfficialHttps(target, OPEN_API_HOSTS, false)) {
      return;
    }
    throw new BusinessException("QMAI_OUTBOUND_BLOCKED", "企迈外网访问未获授权；测试仅允许本机 Mock", HttpStatus.SERVICE_UNAVAILABLE);
  }

  /** Console token traffic is isolated from the OpenAPI host policy. */
  public void requireConsoleAllowed(String target) {
    String mode = normalized(properties.getOutboundMode());
    if ("MOCK".equals(mode) && LocalMockOutboundPolicy.isLoopback(target)) {
      return;
    }
    if ("LIVE".equals(mode) && isOfficialHttps(target, CONSOLE_API_HOSTS, false)) {
      return;
    }
    throw new BusinessException(
        "QMAI_CONSOLE_OUTBOUND_BLOCKED",
        "企迈后台网关访问未获授权；测试仅允许本机 Mock",
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  /** Persisted base URLs are either the official API origin or an explicit loopback mock. */
  public void requireValidBaseUrl(String baseUrl) {
    String mode = normalized(properties.getOutboundMode());
    boolean allowed = "MOCK".equals(mode)
        ? LocalMockOutboundPolicy.isLoopback(baseUrl)
        : isOfficialHttps(baseUrl, OPEN_API_HOSTS, true);
    if (!allowed) {
      throw new BusinessException(
          "QMAI_BASE_URL_INVALID",
          "企迈接口地址必须使用官方 HTTPS 地址",
          HttpStatus.BAD_REQUEST);
    }
  }

  private boolean isOfficialHttps(String target, Set<String> allowedHosts, boolean originOnly) {
    try {
      URI uri = URI.create(target == null ? "" : target.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
          || (uri.getPort() != -1 && uri.getPort() != 443)
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        return false;
      }
      return !originOnly || uri.getPath() == null || uri.getPath().isBlank()
          || "/".equals(uri.getPath());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private String normalized(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
