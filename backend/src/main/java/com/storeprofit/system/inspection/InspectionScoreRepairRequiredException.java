package com.storeprofit.system.inspection;

import com.storeprofit.system.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Raised when a historical inspection cannot be scored from immutable record evidence.
 *
 * <p>The API handler exposes {@link #missingFields()} as structured data so the Vue client can
 * tell an operator exactly which historical facts must be restored.  It deliberately does not
 * substitute a 200-point score or pass line at export time.</p>
 */
public final class InspectionScoreRepairRequiredException extends BusinessException {
  private final List<String> missingFields;

  public InspectionScoreRepairRequiredException(List<String> missingFields) {
    super(
        "INSPECTION_SCORE_REPAIR_REQUIRED",
        "该巡检记录评分数据不完整，无法确定性修复。缺少或冲突字段："
            + String.join("、", missingFields),
        HttpStatus.CONFLICT
    );
    this.missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
  }

  public List<String> missingFields() {
    return missingFields;
  }
}
