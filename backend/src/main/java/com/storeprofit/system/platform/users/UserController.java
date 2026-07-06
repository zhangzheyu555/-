package com.storeprofit.system.platform.users;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
  private final AuthService authService;
  private final UserManagementService userManagementService;

  public UserController(AuthService authService, UserManagementService userManagementService) {
    this.authService = authService;
    this.userManagementService = userManagementService;
  }

  @GetMapping
  public ApiResponse<List<UserResponse>> users(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(userManagementService.users(authService.requireUser(authorization)));
  }
}
