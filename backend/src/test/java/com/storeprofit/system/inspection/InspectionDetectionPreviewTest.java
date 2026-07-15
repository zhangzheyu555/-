package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectionDetectionPreviewTest {

  @Test
  void attachesSafeAnnotatedImageToImmediateResponseOnly() {
    String annotatedImage = "data:image/png;base64,cHJldmlldy1vbmx5";
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("annotated_image", annotatedImage);
    raw.put("original_image", "data:image/png;base64,b3JpZ2luYWw=");

    Map<String, Object> response = InspectionService.withTransientAnnotatedPreview(
        raw,
        Map.of("matched", true, "deduction", 4));

    assertThat(response)
        .containsEntry("annotated_image", annotatedImage)
        .doesNotContainKey("original_image");
    assertThat(raw).containsEntry("original_image", "data:image/png;base64,b3JpZ2luYWw=");
  }

  @Test
  void rejectsNonImageOrExternalPreviewUrls() {
    Map<String, Object> response = InspectionService.withTransientAnnotatedPreview(
        Map.of("annotated_image", "https://unexpected.example/preview.png"),
        Map.of("matched", true));

    assertThat(response).doesNotContainKey("annotated_image");
  }
}
