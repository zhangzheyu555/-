package com.storeprofit.system.platform.integration;

import org.springframework.stereotype.Component;

/** 美团接入占位边界；在合作申请、契约和凭据就绪前始终 fail-closed。 */
@Component
public class MeituanPlatformAdapter implements PlatformAdapter {
  @Override
  public String platform() {
    return "MEITUAN";
  }

  @Override
  public PlatformAdapterStatus status() {
    return new PlatformAdapterStatus(
        platform(),
        PlatformSyncState.NOT_CONFIGURED,
        PlatformSyncState.NOT_CONFIGURED,
        "美团平台尚未完成合作申请、接口契约和凭据配置"
    );
  }
}
