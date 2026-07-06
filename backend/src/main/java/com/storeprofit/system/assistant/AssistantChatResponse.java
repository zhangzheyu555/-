package com.storeprofit.system.assistant;

public record AssistantChatResponse(
    String answer,
    boolean aiUsed,
    boolean blocked,
    String source
) {
}
