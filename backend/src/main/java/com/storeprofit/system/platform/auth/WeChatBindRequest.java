package com.storeprofit.system.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WeChatBindRequest(
    @NotBlank(message = "微信登录凭据不能为空") @Size(max = 512, message = "微信登录凭据无效") String code
) {
}
