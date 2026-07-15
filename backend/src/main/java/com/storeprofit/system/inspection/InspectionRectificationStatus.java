package com.storeprofit.system.inspection;

/**
 * State is kept outside immutable inspection scores and standard snapshots.  A rejected
 * rectification may be supplemented and submitted again; approval never recalculates the
 * historic inspection result.
 */
public enum InspectionRectificationStatus {
  PENDING_SUBMISSION("待整改"),
  PENDING_REVIEW("待运营复核"),
  APPROVED("整改已通过"),
  REJECTED("整改已驳回");

  private final String label;

  InspectionRectificationStatus(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
