package com.storeprofit.system.employeeassistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser-facing local adapter; browsers never receive the upstream URL or token. */
@RestController
@RequestMapping("/api/employee-assistant")
public class EmployeeAssistantController {
  private final AuthService authService;
  private final AccessControlService accessControl;
  private final EmployeeAssistantService employeeAssistantService;

  public EmployeeAssistantController(
      AuthService authService,
      AccessControlService accessControl,
      EmployeeAssistantService employeeAssistantService
  ) {
    this.authService = authService;
    this.accessControl = accessControl;
    this.employeeAssistantService = employeeAssistantService;
  }

  @GetMapping({"/status", "/health"})
  public ApiResponse<EmployeeAssistantStatusResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = requireUserWithPermission(authorization);
    return ApiResponse.ok(employeeAssistantService.health(user));
  }

  @PostMapping("/chat")
  public ApiResponse<EmployeeAssistantChatResponse> chat(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) EmployeeAssistantChatRequest request
  ) {
    AuthUser user = requireUserWithPermission(authorization);
    return ApiResponse.ok(employeeAssistantService.chat(user, request));
  }

  private AuthUser requireUserWithPermission(String authorization) {
    AuthUser user = authService.requireUser(authorization);
    accessControl.requireEmployeeAssistantUse(user);
    return user;
  }
}
