package com.storeprofit.system.assistant;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
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

  public AssistantController(AuthService authService, AssistantService assistantService) {
    this.authService = authService;
    this.assistantService = assistantService;
  }

  @PostMapping("/chat")
  public ApiResponse<AssistantChatResponse> chat(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody AssistantChatRequest request
  ) {
    return ApiResponse.ok(assistantService.chat(authService.requireUser(authorization), request));
  }
}
