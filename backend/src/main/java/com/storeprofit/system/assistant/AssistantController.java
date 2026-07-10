package com.storeprofit.system.assistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.Set;
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
  private static final Set<String> CHAT_ROLES = Set.of(
      "ADMIN", "OWNER", "BOSS", "FINANCE", "WAREHOUSE", "STORE_MANAGER", "SUPERVISOR", "OPERATIONS"
  );
  private static final Set<String> STATUS_ROLES = Set.of("ADMIN", "OWNER", "BOSS");

  private final AuthService authService;
  private final AssistantService assistantService;
  private final DeepSeekProperties deepSeekProperties;

  public AssistantController(AuthService authService, AssistantService assistantService,
                             DeepSeekProperties deepSeekProperties) {
    this.authService = authService;
    this.assistantService = assistantService;
    this.deepSeekProperties = deepSeekProperties;
  }

  @PostMapping("/chat")
  public ApiResponse<AssistantChatResponse> chat(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody AssistantChatRequest request
  ) {
    AuthUser user = authService.requireUser(authorization);
    if (!CHAT_ROLES.contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "当前账号无权使用门店经营助手", HttpStatus.FORBIDDEN);
    }
    return ApiResponse.ok(assistantService.chat(user, request));
  }

  @GetMapping("/status")
  public ApiResponse<AssistantStatusResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = authService.requireUser(authorization);
    if (!STATUS_ROLES.contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权访问助手状态接口", HttpStatus.FORBIDDEN);
    }
    return ApiResponse.ok(new AssistantStatusResponse(
        deepSeekProperties.isEnabled(),
        deepSeekProperties.isConfigured(),
        deepSeekProperties.getBaseUrlHost(),
        deepSeekProperties.getModel(),
        deepSeekProperties.getLastSuccessAt(),
        deepSeekProperties.getLastFailureAt(),
        deepSeekProperties.getLastFailureCode()
    ));
  }
}
