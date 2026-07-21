package com.storeprofit.system.platform.auth;

import jakarta.validation.constraints.NotBlank;

/** 微信小程序 uni.login 返回的一次性 code；绝不接收或保存 session_key。 */
public record WeChatLoginRequest(@NotBlank String code, Long tenantId) {
}
