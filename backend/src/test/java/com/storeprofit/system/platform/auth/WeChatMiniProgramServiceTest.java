package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WeChatMiniProgramServiceTest {
  @Test
  void unconfiguredServiceRejectsLoginWithoutCallingWeChat() {
    WeChatMiniProgramService service = new WeChatMiniProgramService("", "", "http://127.0.0.1:9", new ObjectMapper());

    BusinessException error = catchThrowableOfType(() -> service.exchangeCode("valid-code"), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(error.getCode()).isEqualTo("WECHAT_LOGIN_UNAVAILABLE");
  }

  @Test
  void invalidCodeIsRejectedBeforeCallingWeChat() {
    WeChatMiniProgramService service = new WeChatMiniProgramService("mini-app", "secret", "http://127.0.0.1:9", new ObjectMapper());

    BusinessException error = catchThrowableOfType(() -> service.exchangeCode("short"), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.getCode()).isEqualTo("WECHAT_CODE_INVALID");
  }
}
