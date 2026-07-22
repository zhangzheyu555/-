package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Explicit allow-list boundary for QMAI network traffic. */
@Component
public class QmaiOutboundPolicy {
  private final QmaiProperties properties;

  public QmaiOutboundPolicy(QmaiProperties properties) {
    this.properties = properties;
  }

  public void requireAllowed(String target) {
    String mode = properties.getOutboundMode() == null ? "DISABLED"
        : properties.getOutboundMode().trim().toUpperCase(Locale.ROOT);
    if ("LIVE".equals(mode)) {
      return;
    }
    if ("MOCK".equals(mode) && isLoopback(target)) {
      return;
    }
    throw new BusinessException("QMAI_OUTBOUND_BLOCKED", "企迈外网访问未获授权；测试仅允许本机 Mock", HttpStatus.SERVICE_UNAVAILABLE);
  }

  private boolean isLoopback(String target) {
    try {
      URI uri = URI.create(target);
      String host = uri.getHost();
      return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
