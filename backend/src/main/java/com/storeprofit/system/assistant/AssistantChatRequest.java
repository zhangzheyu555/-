package com.storeprofit.system.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssistantChatRequest(
    @NotBlank @Size(max = 800) String message,
    @Size(max = 12) List<AssistantChatTurn> history,
    @Size(max = 20000) String dataContext,
    @Pattern(regexp = "AUTO|LOCAL|AI", flags = {Pattern.Flag.CASE_INSENSITIVE}) String mode,
    @Size(max = 64) String storeId,
    @Pattern(regexp = "\\d{4}-\\d{2}") @Size(max = 7) String month,
    @Size(max = 128) String snapshotId
) {
  /** Source-compatible constructor for clients that predate the snapshot contract. */
  public AssistantChatRequest(
      String message,
      List<AssistantChatTurn> history,
      String dataContext,
      String mode,
      String storeId,
      String month
  ) {
    this(message, history, dataContext, mode, storeId, month, null);
  }

  public String modeOrDefault() {
    if (mode == null || mode.isBlank()) return "AUTO";
    return mode.toUpperCase();
  }
}
