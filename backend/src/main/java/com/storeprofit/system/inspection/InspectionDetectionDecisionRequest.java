package com.storeprofit.system.inspection;

/**
 * A detection decision never accepts a score or clause from the browser.
 * The service resolves both from the server-side detection binding.
 */
public record InspectionDetectionDecisionRequest(
    Long expectedRevision
) {
}
