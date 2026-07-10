package com.storeprofit.system.assistant;

import java.time.Instant;

public record AssistantStatusResponse(
    boolean enabled,
    boolean configured,
    String baseUrlHost,
    String model,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    String lastFailureCode
) {
}
