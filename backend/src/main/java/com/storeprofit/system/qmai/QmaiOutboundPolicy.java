package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.config.LocalMockOutboundPolicy;
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
    if (LocalMockOutboundPolicy.isAllowed("", properties.getOutboundMode(), target)) {
      return;
    }
    throw new BusinessException("QMAI_OUTBOUND_BLOCKED", "企迈外网访问未获授权；测试仅允许本机 Mock", HttpStatus.SERVICE_UNAVAILABLE);
  }
}
