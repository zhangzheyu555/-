package com.storeprofit.system.appauth;

import com.storeprofit.system.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 旧版页面的后端登录接口。见 {@link AppAuthService}。 */
@RestController
@RequestMapping("/api/app")
public class AppAuthController {

  public record LoginRequest(String password) {}

  private final AppAuthService appAuthService;

  public AppAuthController(AppAuthService appAuthService) {
    this.appAuthService = appAuthService;
  }

  @PostMapping("/login")
  public ApiResponse<AppAuthService.LoginResult> login(@RequestBody LoginRequest request) {
    return ApiResponse.ok(appAuthService.login(request == null ? null : request.password()));
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
    appAuthService.logout(authorization);
    return ApiResponse.ok();
  }
}
