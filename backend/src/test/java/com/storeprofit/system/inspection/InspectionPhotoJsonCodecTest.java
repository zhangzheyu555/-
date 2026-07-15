package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InspectionPhotoJsonCodecTest {
  @Test
  void keepsDetectionPhotoFieldsAndCollectsBothAttachmentIdFormats() {
    String json = "[{\"attachmentId\":11,\"detection\":{\"detectionKey\":\"d-1\"}},"
        + "{\"attachment_id\":12},{\"attachmentId\":11}]";

    List<Map<String, Object>> photos = InspectionPhotoJsonCodec.parseDetectionPhotos(json);

    assertThat(photos).hasSize(3);
    assertThat(photos.getFirst()).containsKey("detection");
    assertThat(InspectionPhotoJsonCodec.attachmentIds(json)).containsExactlyInAnyOrder(11L, 12L);
  }

  @Test
  void rejectsInvalidAttachmentIdsAndMalformedDetectionPayloads() {
    assertThatThrownBy(() -> InspectionPhotoJsonCodec.attachmentIds("[{\"attachmentId\":0}]"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> InspectionPhotoJsonCodec.parseDetectionPhotos("not-json"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void createsMutableEvidenceCopiesAndPreservesLegacyAttachmentIdFormat() {
    List<Map<String, Object>> photos = InspectionPhotoJsonCodec.parseEvidencePhotos(
        "[{\"attachment_id\":\"12\",\"fileName\":\"original.jpg\"}]");

    assertThat(photos).hasSize(1);
    assertThat(photos.getFirst()).isInstanceOf(LinkedHashMap.class);
    assertThat(InspectionPhotoJsonCodec.attachmentId(photos.getFirst())).isEqualTo(12L);

    photos.getFirst().put("contentType", "image/jpeg");
    assertThat(photos.getFirst()).containsEntry("contentType", "image/jpeg");
  }

  @Test
  void rejectsUnreadableOrNullEvidenceEntriesWithStableBusinessError() {
    assertThatThrownBy(() -> InspectionPhotoJsonCodec.parseEvidencePhotos("[null]"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("INSPECTION_EVIDENCE_UNLINKED");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    assertThatThrownBy(() -> InspectionPhotoJsonCodec.parseEvidencePhotos("not-json"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("INSPECTION_EVIDENCE_UNLINKED");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
  }
}
