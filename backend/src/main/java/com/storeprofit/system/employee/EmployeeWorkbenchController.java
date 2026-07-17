package com.storeprofit.system.employee;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee")
public class EmployeeWorkbenchController {
  private final AuthService authService;
  private final EmployeeWorkbenchService workbenchService;

  public EmployeeWorkbenchController(AuthService authService, EmployeeWorkbenchService workbenchService) {
    this.authService = authService;
    this.workbenchService = workbenchService;
  }

  @GetMapping("/workbench")
  public ApiResponse<EmployeeWorkbenchResponse> workbench(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(workbenchService.workbench(authService.requireUser(authorization)));
  }

  @GetMapping("/profile")
  public ApiResponse<EmployeeProfileResponse> profile(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(workbenchService.profile(authService.requireUser(authorization)));
  }
}
