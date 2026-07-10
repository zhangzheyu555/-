package com.storeprofit.system.assistant;

import jakarta.validation.constraints.Size;

public record AssistantChatTurn(
    @Size(max = 20) String role,
    @Size(max = 1000) String content
) {
}
