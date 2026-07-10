package com.storeprofit.system.platform.users;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  @PostMapping
  public ApiResponse<UserResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody UserCreateRequest request
  ) {
    return ApiResponse.ok(userManagementService.create(authService.requireUser(authorization), request));
  }

  @PutMapping("/{userId}")
  public ApiResponse<UserResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long userId,
      @Valid @RequestBody UserUpdateRequest request
  ) {
    return ApiResponse.ok(userManagementService.update(authService.requireUser(authorization), userId, request));
  }

  @PostMapping("/{userId}/reset-password")
  public ApiResponse<Void> resetPassword(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long userId,
      @Valid @RequestBody UserPasswordResetRequest request
  ) {
    userManagementService.resetPassword(authService.requireUser(authorization), userId, request);
    return ApiResponse.ok();
  }
}
