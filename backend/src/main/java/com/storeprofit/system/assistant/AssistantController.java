package com.storeprofit.system.assistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
  private final AuthService authService;
  private final AssistantService assistantService;
  private final DeepSeekProperties deepSeekProperties;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  @Autowired
  public AssistantController(
      AuthService authService,
      AssistantService assistantService,
      DeepSeekProperties deepSeekProperties,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this.authService = authService;
    this.assistantService = assistantService;
    this.deepSeekProperties = deepSeekProperties;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  /** Compatibility constructor retained for isolated controller tests. */
  public AssistantController(
      AuthService authService,
      AssistantService assistantService,
      DeepSeekProperties deepSeekProperties,
      AccessControlService accessControl
  ) {
    this(authService, assistantService, deepSeekProperties, accessControl, null);
  }

  /** Compatibility constructor retained for isolated controller tests. */
  public AssistantController(
      AuthService authService,
      AssistantService assistantService,
      DeepSeekProperties deepSeekProperties
  ) {
    this(authService, assistantService, deepSeekProperties, null, null);
  }

  @PostMapping("/chat")
  public ApiResponse<AssistantChatResponse> chat(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody AssistantChatRequest request
  ) {
    AuthUser user = authService.requireUser(authorization);
    requireAssistantUse(user);
    return ApiResponse.ok(assistantService.chat(user, request));
  }

  /** Read-only source of truth for all operating KPI and assistant analysis on one page. */
  @GetMapping("/operating-snapshot")
  public ApiResponse<OperatingSnapshot> operatingSnapshot(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "storeId", required = false) String storeId,
      @RequestParam(value = "month", required = false) String month
  ) {
    AuthUser user = authService.requireUser(authorization);
    requireAssistantUse(user);
    return ApiResponse.ok(assistantService.operatingSnapshot(user, storeId, month));
  }

  @GetMapping("/status")
  public ApiResponse<AssistantStatusResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = authService.requireUser(authorization);
    requireAssistantStatus(user);
    return ApiResponse.ok(new AssistantStatusResponse(
        deepSeekProperties.isEnabled(),
        deepSeekProperties.isConfigured(),
        "DeepSeek",
        deepSeekProperties.getModel(),
        deepSeekProperties.getBaseUrlHost(),
        deepSeekProperties.getTimeout().toSeconds(),
        deepSeekProperties.getLastValidatedAnalysisAt(),
        deepSeekProperties.getLastAnalysisErrorCode(),
        deepSeekProperties.getAnalysisState()
    ));
  }

  private void requireAssistantUse(AuthUser user) {
    try {
      if (accessControl != null) {
        accessControl.requireAssistantUse(user);
        return;
      }
      if (AccessControlService.isBoss(user)
          || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
              .contains(PermissionCodes.ASSISTANT_USE)) {
        return;
      }
      throw new BusinessException("FORBIDDEN", "当前账号无权使用门店经营助手", HttpStatus.FORBIDDEN);
    } catch (BusinessException error) {
      if (auditRepository != null && user != null) {
        auditRepository.writePermissionDenied(user, "使用门店经营助手", "operating_assistant", null,
            user.storeId(), "权限或数据范围不足");
      }
      throw error;
    }
  }

  private void requireAssistantStatus(AuthUser user) {
    requireAssistantUse(user);
  }
}
