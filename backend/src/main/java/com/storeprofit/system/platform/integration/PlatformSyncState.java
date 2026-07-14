package com.storeprofit.system.platform.integration;

/** 平台能力的真实可用状态；未完成配置或联调时不得报告可用。 */
public enum PlatformSyncState {
  READY,
  NOT_CONFIGURED
}
