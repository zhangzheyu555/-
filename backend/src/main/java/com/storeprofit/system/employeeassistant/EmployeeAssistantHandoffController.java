package com.storeprofit.system.employeeassistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Employee handoff and feedback API. Raw browser conversation content is never retained. */
@RestController
@RequestMapping("/api/employee-assistant")
public class EmployeeAssistantHandoffController {
  private final AuthService authService;
  private final EmployeeAssistantHandoffService handoffService;

  public EmployeeAssistantHandoffController(AuthService authService, EmployeeAssistantHandoffService handoffService) {
    this.authService = authService;
    this.handoffService = handoffService;
  }

  @PostMapping("/handoffs")
  public ApiResponse<EmployeeAssistantHandoffResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) EmployeeAssistantHandoffCreateRequest request
  ) {
    return ApiResponse.ok(handoffService.create(user(authorization), request));
  }

  @GetMapping("/handoffs/mine")
  public ApiResponse<List<EmployeeAssistantHandoffResponse>> mine(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(handoffService.mine(user(authorization)));
  }

  @GetMapping("/handoffs/manage")
  public ApiResponse<List<EmployeeAssistantHandoffResponse>> manageList(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(handoffService.manageList(user(authorization)));
  }

  @PostMapping("/handoffs/{id}/claim")
  public ApiResponse<EmployeeAssistantHandoffResponse> claim(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(handoffService.claim(user(authorization), id));
  }

  @PostMapping("/handoffs/{id}/reply")
  public ApiResponse<EmployeeAssistantHandoffResponse> reply(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) EmployeeAssistantHandoffReplyRequest request
  ) {
    return ApiResponse.ok(handoffService.reply(user(authorization), id, request));
  }

  @PostMapping("/handoffs/{id}/close")
  public ApiResponse<EmployeeAssistantHandoffResponse> close(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) EmployeeAssistantHandoffReplyRequest request
  ) {
    return ApiResponse.ok(handoffService.close(user(authorization), id, request));
  }

  @PostMapping("/feedback")
  public ApiResponse<Void> feedback(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) EmployeeAssistantFeedbackRequest request
  ) {
    handoffService.feedback(user(authorization), request);
    return ApiResponse.ok();
  }

  private AuthUser user(String authorization) {
    return authService.requireUser(authorization);
  }
}
