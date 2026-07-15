package com.storeprofit.system.inspection;

import java.util.List;

/**
 * Explicit, operator-confirmed evidence mapping for an existing inspection record.
 *
 * <p>{@code clauseIds} maps to {@code inspection_standard_item.id} (standardItemId) for
 * records that have intact standard references.  {@code historicalSnapshotIds} maps to
 * {@code inspection_record_standard_snapshot.id} (snapshotId) and is the only safe path
 * when {@code standard_id} is NULL in the snapshot — common for early migrated records.</p>
 *
 * <p>This request cannot carry a score, deduction reason, standard version, or
 * rectification field.</p>
 */
public record InspectionEvidenceLinkRequest(
    List<Long> attachmentIds,
    List<Long> clauseIds,
    List<Long> historicalSnapshotIds
) {
  public InspectionEvidenceLinkRequest {
    attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    clauseIds = clauseIds == null ? List.of() : List.copyOf(clauseIds);
    historicalSnapshotIds = historicalSnapshotIds == null ? List.of() : List.copyOf(historicalSnapshotIds);
  }

  /** Backward-compatible factory for callers that only have standardItemId references. */
  public static InspectionEvidenceLinkRequest forStandardItems(List<Long> attachmentIds, List<Long> clauseIds) {
    return new InspectionEvidenceLinkRequest(attachmentIds, clauseIds, List.of());
  }
}
