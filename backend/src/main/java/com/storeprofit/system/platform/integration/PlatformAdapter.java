package com.storeprofit.system.platform.integration;

/** 外卖平台统一边界。具体平台未接入时只返回 NOT_CONFIGURED，不生成模拟状态。 */
public interface PlatformAdapter {
  String platform();

  PlatformAdapterStatus status();
}
