package com.storeprofit.system.assistant;

import java.time.Instant;

public record AssistantStatusResponse(
    boolean enabled,
    boolean configured,
    String provider,
    String model,
    String baseUrlHost,
    long timeout,
    Instant lastSuccessAt,
    String lastErrorCode,
    String state
) {
}
