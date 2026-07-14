package com.storeprofit.system.platform.integration;

/** 平台适配器的订单同步和回调能力状态。 */
public record PlatformAdapterStatus(
    String platform,
    PlatformSyncState orderSync,
    PlatformSyncState webhook,
    String message
) {}
