package com.storeprofit.system.assistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
  private final AuthService authService;
  private final AssistantService assistantService;
  private final DeepSeekProperties deepSeekProperties;
  private final AccessControlService accessControl;

  @Autowired
  public AssistantController(
      AuthService authService,
      AssistantService assistantService,
      DeepSeekProperties deepSeekProperties,
      AccessControlService accessControl
  ) {
    this.authService = authService;
    this.assistantService = assistantService;
    this.deepSeekProperties = deepSeekProperties;
    this.accessControl = accessControl;
  }

  /** Compatibility constructor retained for isolated controller tests. */
  public AssistantController(
      AuthService authService,
      AssistantService assistantService,
      DeepSeekProperties deepSeekProperties
  ) {
    this(authService, assistantService, deepSeekProperties, null);
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
  }

  private void requireAssistantStatus(AuthUser user) {
    requireAssistantUse(user);
  }
}
