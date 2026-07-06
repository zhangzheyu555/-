package com.storeprofit.system.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssistantChatRequest(
    @NotBlank @Size(max = 800) String message,
    @Size(max = 12) List<AssistantChatTurn> history,
    @Size(max = 20000) String dataContext
) {
}
