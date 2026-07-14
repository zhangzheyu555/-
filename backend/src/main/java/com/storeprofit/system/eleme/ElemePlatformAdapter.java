package com.storeprofit.system.eleme;

import com.storeprofit.system.platform.integration.PlatformAdapter;
import com.storeprofit.system.platform.integration.PlatformAdapterStatus;
import com.storeprofit.system.platform.integration.PlatformSyncState;
import org.springframework.stereotype.Component;

@Component
public class ElemePlatformAdapter implements PlatformAdapter {
  private final ElemeProperties properties;

  public ElemePlatformAdapter(ElemeProperties properties) {
    this.properties = properties;
  }

  @Override
  public String platform() {
    return "ELEME";
  }

  @Override
  public PlatformAdapterStatus status() {
    PlatformSyncState orderSync = properties.isConfigured()
        ? PlatformSyncState.READY
        : PlatformSyncState.NOT_CONFIGURED;
    PlatformSyncState webhook = properties.isWebhookConfigured()
        ? PlatformSyncState.READY
        : PlatformSyncState.NOT_CONFIGURED;
    String message;
    if (orderSync == PlatformSyncState.READY && webhook == PlatformSyncState.READY) {
      message = "饿了么订单同步与回调验签已配置";
    } else if (orderSync == PlatformSyncState.READY) {
      message = "饿了么订单同步已配置，回调验签尚未配置";
    } else if (webhook == PlatformSyncState.READY) {
      message = "饿了么回调验签已配置，订单同步凭据尚未配置";
    } else {
      message = "饿了么订单同步与回调验签尚未配置";
    }
    return new PlatformAdapterStatus(platform(), orderSync, webhook, message);
  }
}
