package com.storeprofit.system.storemanager;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.todo.RoleTodoActionResultResponse;
import com.storeprofit.system.todo.RoleTodoCompletionRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/store-manager")
public class StoreManagerWorkbenchController {
  private final AuthService authService;
  private final StoreManagerWorkbenchService workbenchService;

  public StoreManagerWorkbenchController(
      AuthService authService,
      StoreManagerWorkbenchService workbenchService
  ) {
    this.authService = authService;
    this.workbenchService = workbenchService;
  }

  @GetMapping("/workbench")
  public ApiResponse<StoreManagerWorkbenchResponse> workbench(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(workbenchService.workbench(authService.requireUser(authorization)));
  }

  @GetMapping("/inspections")
  public ApiResponse<StoreManagerInspectionPageResponse> inspections(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(workbenchService.inspections(authService.requireUser(authorization)));
  }

  @PostMapping("/rectifications/{id}/submit")
  public ApiResponse<RoleTodoActionResultResponse> submitRectification(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return ApiResponse.ok(workbenchService.submitRectification(authService.requireUser(authorization), id, request));
  }
}
