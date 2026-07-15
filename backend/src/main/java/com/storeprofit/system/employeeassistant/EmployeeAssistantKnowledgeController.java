package com.storeprofit.system.employeeassistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BOSS-facing knowledge management API. Authorization and all audit decisions live in the service. */
@RestController
@RequestMapping("/api/employee-assistant/knowledge")
public class EmployeeAssistantKnowledgeController {
  private final AuthService authService;
  private final EmployeeAssistantKnowledgeService knowledgeService;

  public EmployeeAssistantKnowledgeController(AuthService authService, EmployeeAssistantKnowledgeService knowledgeService) {
    this.authService = authService;
    this.knowledgeService = knowledgeService;
  }

  @GetMapping
  public ApiResponse<List<EmployeeAssistantKnowledgeResponse>> list(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(knowledgeService.list(user(authorization)));
  }

  @PostMapping
  public ApiResponse<EmployeeAssistantKnowledgeResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) EmployeeAssistantKnowledgeDraftRequest request
  ) {
    return ApiResponse.ok(knowledgeService.createDraft(user(authorization), request));
  }

  @PutMapping("/{id}")
  public ApiResponse<EmployeeAssistantKnowledgeResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id,
      @RequestBody(required = false) EmployeeAssistantKnowledgeDraftRequest request
  ) {
    return ApiResponse.ok(knowledgeService.updateDraft(user(authorization), id, request));
  }

  @PostMapping("/{id}/publish")
  public ApiResponse<EmployeeAssistantKnowledgeResponse> publish(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(knowledgeService.publish(user(authorization), id));
  }

  @PostMapping("/{id}/rollback/{version}")
  public ApiResponse<EmployeeAssistantKnowledgeResponse> rollback(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id,
      @PathVariable int version
  ) {
    return ApiResponse.ok(knowledgeService.rollback(user(authorization), id, version));
  }

  @GetMapping("/{id}/versions")
  public ApiResponse<List<EmployeeAssistantKnowledgeVersionResponse>> versions(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(knowledgeService.versions(user(authorization), id));
  }

  private AuthUser user(String authorization) {
    return authService.requireUser(authorization);
  }
}
