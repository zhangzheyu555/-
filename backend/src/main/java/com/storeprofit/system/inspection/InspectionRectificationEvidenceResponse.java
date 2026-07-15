package com.storeprofit.system.inspection;

/** Metadata only. The content remains available through the authenticated attachment API. */
public record InspectionRectificationEvidenceResponse(
    Long attachmentId,
    String fileName,
    String contentType,
    long fileSize,
    String previewUrl
) {
}
