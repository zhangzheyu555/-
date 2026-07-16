package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectionDetectionEvidenceNormalizerTest {

  @Test
  void deduplicatesAtIouThresholdAndKeepsTheHigherConfidenceCandidate() {
    List<Map<String, Object>> unique = InspectionDetectionEvidenceNormalizer.deduplicate(List.of(
        Map.of(
            "class_name", "Floor Litter",
            "confidence", 0.85,
            "bbox", List.of(0, 0, 19, 19)
        ),
        Map.of(
            "className", "floor_litter",
            "confidence", 0.91,
            "bbox", List.of(1, 0, 20, 19)
        )
    ));

    // 19x19 boxes shifted by one pixel have IoU exactly 0.90, which is inclusive.
    assertThat(InspectionDetectionEvidenceNormalizer.intersectionOverUnion(
        new double[] {0, 0, 19, 19}, new double[] {1, 0, 20, 19}
    )).isEqualTo(0.90d);
    assertThat(unique).hasSize(1);
    assertThat(unique.getFirst().get("confidence")).isEqualTo(0.91);
    assertThat(InspectionDetectionEvidenceNormalizer.maximumConfidence(unique))
        .isEqualByComparingTo("0.9100");
  }

  @Test
  void keepsDetectionsBelowIouThresholdOrWithDifferentClasses() {
    List<Map<String, Object>> unique = InspectionDetectionEvidenceNormalizer.deduplicate(List.of(
        Map.of("class_name", "floor_litter", "confidence", 0.80, "bbox", List.of(0, 0, 20, 20)),
        Map.of("class_name", "floor_litter", "confidence", 0.85, "bbox", List.of(2, 0, 22, 20)),
        Map.of("class_name", "paper", "confidence", 0.95, "bbox", List.of(0, 0, 20, 20))
    ));

    assertThat(unique).hasSize(3);
    assertThat(InspectionDetectionEvidenceNormalizer.maximumConfidence(unique))
        .isEqualByComparingTo("0.9500");
  }

  @Test
  void deduplicatesBoxlessCanonicalEvidenceButRetainsSemanticDifferences() {
    List<Map<String, Object>> unique = InspectionDetectionEvidenceNormalizer.deduplicate(List.of(
        Map.of("className", "Paper Scrap", "source", "yolo", "on_floor", true, "confidence", 0.80),
        Map.of("class_name", "paper_scrap", "source", "yolo", "onFloor", "true", "confidence", 0.95),
        Map.of("label", "paper-scrap", "source", "another-model", "on_floor", true, "confidence", 0.70),
        Map.of("label", "paper-scrap", "source", "yolo", "on_floor", false, "confidence", 0.60)
    ));

    assertThat(unique).hasSize(3);
    assertThat(unique).anySatisfy(detection -> assertThat(detection.get("confidence")).isEqualTo(0.95));
  }

  @Test
  void producesStableKeysForReorderedEquivalentEvidenceAndDifferentKeysForMaterialChanges() {
    Map<String, Object> source = Map.of("image_id", "img-1", "filename", "floor.jpg");
    List<Map<String, Object>> first = InspectionDetectionEvidenceNormalizer.deduplicate(List.of(
        Map.of("class_name", "Floor-Litter", "source", "yolo", "on_floor", true,
            "bbox", List.of(0, 0, 10, 10)),
        Map.of("label", "paper", "source", "yolo", "onFloor", false,
            "box", List.of(20d, 0d, 30d, 10d))
    ));
    List<Map<String, Object>> equivalentReordered = InspectionDetectionEvidenceNormalizer.deduplicate(List.of(
        Map.of("class_name", "PAPER", "source", "yolo", "on_floor", false,
            "boxXyxy", List.of("20", "0", "30", "10")),
        Map.of("className", "floor litter", "source", "yolo", "onFloor", "true",
            "bbox", List.of("0", "0", "10", "10"))
    ));
    List<Map<String, Object>> materiallyChanged = InspectionDetectionEvidenceNormalizer.deduplicate(List.of(
        Map.of("class_name", "Floor-Litter", "source", "yolo", "on_floor", true,
            "bbox", List.of(0, 0, 10, 10)),
        Map.of("label", "paper", "source", "yolo", "onFloor", false,
            "box", List.of(21, 0, 31, 10))
    ));

    String firstKey = InspectionDetectionEvidenceNormalizer.stableKey(source, first);
    assertThat(InspectionDetectionEvidenceNormalizer.stableKey(source, equivalentReordered))
        .isEqualTo(firstKey);
    assertThat(InspectionDetectionEvidenceNormalizer.stableKey(source, materiallyChanged))
        .isNotEqualTo(firstKey);
  }

  @Test
  void treatsMissingOrNegativeConfidenceAsZeroAndUsesScoreAsFallback() {
    List<Map<String, Object>> onlyInvalid = List.of(
        Map.of("class_name", "paper", "confidence", -0.7),
        Map.of("class_name", "stain")
    );
    List<Map<String, Object>> withScoreFallback = List.of(
        Map.of("class_name", "paper", "confidence", -0.7),
        Map.of("class_name", "stain", "score", new BigDecimal("0.45678")),
        Map.of("class_name", "corner_dust")
    );

    assertThat(InspectionDetectionEvidenceNormalizer.maximumConfidence(onlyInvalid))
        .isEqualByComparingTo("0.0000");
    assertThat(InspectionDetectionEvidenceNormalizer.maximumConfidence(withScoreFallback))
        .isEqualByComparingTo("0.4600");
  }
}
