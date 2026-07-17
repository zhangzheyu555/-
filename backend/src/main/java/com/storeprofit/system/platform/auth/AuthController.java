package com.storeprofit.system.platform.auth;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.session.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ApiResponse<LoginResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest servletRequest
  ) {
    return ApiResponse.ok(authService.login(request, servletRequest.getRemoteAddr()));
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
    authService.logout(authorization);
    return ApiResponse.ok();
  }

  @GetMapping("/me")
  public ApiResponse<SessionUser> me(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(authService.toSessionUser(authService.requireUser(authorization)));
  }
}
