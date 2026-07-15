package com.storeprofit.system.inspection;

import com.storeprofit.system.common.BusinessException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Stateless rules for binding persisted inspection photos to uploaded evidence and snapshots. */
final class InspectionEvidencePhotoPolicy {
  private InspectionEvidencePhotoPolicy() {}

  static void bindUploadedOriginal(
      List<Map<String, Object>> photos,
      List<Long> attachmentIds,
      Integer sourcePhotoIndex,
      String uploadedFileName,
      String uploadedContentType
  ) {
    if (sourcePhotoIndex != null) {
      if (attachmentIds.size() != 1 || sourcePhotoIndex < 0 || sourcePhotoIndex >= photos.size()) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_SOURCE_INVALID",
            "待补传的历史图片已变化，请刷新后重新选择",
            HttpStatus.CONFLICT
        );
      }
      Map<String, Object> target = photos.get(sourcePhotoIndex);
      target.remove("attachment_id");
      target.put("attachmentId", attachmentIds.getFirst());
      putMetadata(target, uploadedFileName, uploadedContentType);
      return;
    }
    for (Long attachmentId : attachmentIds) {
      if (!containsAttachmentId(photos, attachmentId)) {
        Map<String, Object> appended = new LinkedHashMap<>();
        appended.put("attachmentId", attachmentId);
        putMetadata(appended, uploadedFileName, uploadedContentType);
        photos.add(appended);
      }
    }
  }

  static void requireAllPhotosLinked(
      String photosJson,
      List<InspectionStandardSnapshot> snapshots
  ) {
    List<Map<String, Object>> photos = InspectionPhotoJsonCodec.parseEvidencePhotos(photosJson);
    if (photos.isEmpty()) {
      return;
    }
    Set<Long> linkedAttachmentIds = new HashSet<>();
    for (InspectionStandardSnapshot snapshot
        : snapshots == null ? List.<InspectionStandardSnapshot>of() : snapshots) {
      snapshot.photoAttachmentIds().stream()
          .filter(Objects::nonNull)
          .filter(value -> value > 0)
          .forEach(linkedAttachmentIds::add);
    }
    for (Map<String, Object> photo : photos) {
      Long attachmentId = InspectionPhotoJsonCodec.attachmentId(photo);
      if (attachmentId == null || attachmentId <= 0 || !linkedAttachmentIds.contains(attachmentId)) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_UNLINKED",
            "每张巡检图片都必须绑定有效附件并至少关联一条人工确认的巡检条款",
            HttpStatus.BAD_REQUEST
        );
      }
    }
  }

  private static boolean containsAttachmentId(List<Map<String, Object>> photos, long attachmentId) {
    return photos.stream().anyMatch(photo -> Objects.equals(
        InspectionPhotoJsonCodec.attachmentId(photo), attachmentId));
  }

  private static void putMetadata(
      Map<String, Object> target,
      String uploadedFileName,
      String uploadedContentType
  ) {
    if (uploadedFileName != null && !uploadedFileName.isBlank()) {
      target.put("fileName", uploadedFileName);
    }
    if (uploadedContentType != null && !uploadedContentType.isBlank()) {
      target.put("contentType", uploadedContentType);
    }
  }
}
