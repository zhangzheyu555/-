package com.storeprofit.system.platform.session;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {
  private final AuthService authService;

  public SessionController(AuthService authService) {
    this.authService = authService;
  }

  @GetMapping("/me")
  public ApiResponse<SessionUser> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
    return ApiResponse.ok(authService.toSessionUser(authService.requireUser(authorization)));
  }
}
