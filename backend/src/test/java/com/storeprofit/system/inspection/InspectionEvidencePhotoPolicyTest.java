package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InspectionEvidencePhotoPolicyTest {
  @Test
  void replacesTheExactSelectedHistoricalPhotoWithoutFilenameMatching() {
    Map<String, Object> selected = new LinkedHashMap<>();
    selected.put("attachment_id", 5L);
    selected.put("fileName", "old-name.jpg");
    List<Map<String, Object>> photos = new ArrayList<>(List.of(selected));

    InspectionEvidencePhotoPolicy.bindUploadedOriginal(
        photos, List.of(21L), 0, "new-original.jpg", "image/jpeg");

    assertThat(photos).hasSize(1);
    assertThat(photos.getFirst())
        .doesNotContainKey("attachment_id")
        .containsEntry("attachmentId", 21L)
        .containsEntry("fileName", "new-original.jpg")
        .containsEntry("contentType", "image/jpeg");
  }

  @Test
  void appendsOnlyAttachmentIdsNotAlreadyRepresented() {
    List<Map<String, Object>> photos = new ArrayList<>();
    photos.add(new LinkedHashMap<>(Map.of("attachmentId", "11")));

    InspectionEvidencePhotoPolicy.bindUploadedOriginal(
        photos, List.of(11L, 12L), null, "evidence.jpg", "image/jpeg");

    assertThat(photos).hasSize(2);
    assertThat(photos.getLast()).containsEntry("attachmentId", 12L);
  }

  @Test
  void rejectsAChangedHistoricalPhotoSelection() {
    assertThatThrownBy(() -> InspectionEvidencePhotoPolicy.bindUploadedOriginal(
        new ArrayList<>(), List.of(21L), 0, "evidence.jpg", "image/jpeg"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("INSPECTION_EVIDENCE_SOURCE_INVALID");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
  }

  @Test
  void acceptsOnlyPhotosExplicitlyLinkedToSnapshotEvidence() {
    String photosJson = "[{\"attachmentId\":11},{\"attachment_id\":\"12\"}]";

    InspectionEvidencePhotoPolicy.requireAllPhotosLinked(
        photosJson, List.of(snapshot(List.of(11L, 12L))));

    assertThatThrownBy(() -> InspectionEvidencePhotoPolicy.requireAllPhotosLinked(
        photosJson, List.of(snapshot(List.of(11L)))))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("INSPECTION_EVIDENCE_UNLINKED");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
  }

  private InspectionStandardSnapshot snapshot(List<Long> attachmentIds) {
    return new InspectionStandardSnapshot(
        1L,
        "v1",
        "HYGIENE",
        "现场卫生",
        "检查现场卫生",
        new BigDecimal("4.00"),
        BigDecimal.ZERO,
        false,
        null,
        1,
        "H-1",
        "现场检查",
        new BigDecimal("4.00"),
        "NORMAL",
        attachmentIds,
        null,
        null,
        null,
        null,
        List.of(),
        List.of()
    );
  }
}
