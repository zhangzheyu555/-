package com.storeprofit.system.eleme;

/** 回调只确认安全接收；RECEIVED 不代表订单同步已完成。 */
public record ElemeWebhookReceipt(
    String message,
    String status,
    boolean duplicate
) {}
