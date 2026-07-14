package com.storeprofit.system.assistant;

/**
 * A verified response returned by the DeepSeek provider.
 * The configured model is deliberately not used as a fallback for {@code model}:
 * callers may only display a model name that the provider actually returned.
 */
public record DeepSeekCallResult(
    String content,
    String requestId,
    String model,
    int httpStatus,
    long latencyMs
) {
  public DeepSeekCallResult {
    content = content == null ? "" : content.trim();
    requestId = requestId == null ? "" : requestId.trim();
    model = model == null ? "" : model.trim();
    latencyMs = Math.max(0, latencyMs);
  }

  public String provider() {
    return "DeepSeek";
  }
}
