package com.storeprofit.system.assistant;

import com.storeprofit.system.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
  private final AssistantService assistantService;

  public AssistantController(AssistantService assistantService) {
    this.assistantService = assistantService;
  }

  @PostMapping("/chat")
  public ApiResponse<AssistantChatResponse> chat(@Valid @RequestBody AssistantChatRequest request) {
    return ApiResponse.ok(assistantService.chat(request));
  }
}
