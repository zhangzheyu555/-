package com.storeprofit.system.boss;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boss")
public class BossDataHealthController {
  private final AuthService authService;
  private final BossDataHealthService dataHealthService;

  public BossDataHealthController(AuthService authService, BossDataHealthService dataHealthService) {
    this.authService = authService;
    this.dataHealthService = dataHealthService;
  }

  @GetMapping("/data-health")
  public ApiResponse<BossDataHealthResponse> dataHealth(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(dataHealthService.dataHealth(user));
  }
}
