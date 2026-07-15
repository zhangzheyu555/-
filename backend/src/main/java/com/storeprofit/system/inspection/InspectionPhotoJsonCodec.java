package com.storeprofit.system.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Shared parser for persisted inspection photo metadata and attachment identifiers. */
final class InspectionPhotoJsonCodec {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private InspectionPhotoJsonCodec() {}

  static List<Map<String, Object>> parseDetectionPhotos(String photosJson) {
    if (photosJson == null || photosJson.isBlank()) {
      return new ArrayList<>();
    }
    try {
      List<Map<String, Object>> photos = OBJECT_MAPPER.readValue(
          photosJson, new TypeReference<List<Map<String, Object>>>() {});
      return new ArrayList<>(photos == null ? List.of() : photos);
    } catch (JsonProcessingException ex) {
      throw new BusinessException(
          "BAD_DETECTION_RESULTS", "巡检识别结果无法读取", HttpStatus.CONFLICT);
    }
  }

  static List<Map<String, Object>> parseEvidencePhotos(String photosJson) {
    if (photosJson == null || photosJson.isBlank()) {
      return new ArrayList<>();
    }
    try {
      List<Map<String, Object>> parsed = OBJECT_MAPPER.readValue(
          photosJson, new TypeReference<List<Map<String, Object>>>() {});
      List<Map<String, Object>> photos = new ArrayList<>();
      for (Map<String, Object> photo : parsed == null ? List.<Map<String, Object>>of() : parsed) {
        if (photo == null) {
          throw new BusinessException(
              "INSPECTION_EVIDENCE_UNLINKED",
              "巡检图片缺少有效附件编号和人工确认条款",
              HttpStatus.BAD_REQUEST
          );
        }
        photos.add(new LinkedHashMap<>(photo));
      }
      return photos;
    } catch (JsonProcessingException ex) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_UNLINKED",
          "巡检图片证据无法读取，请重新选择并关联原图",
          HttpStatus.BAD_REQUEST
      );
    }
  }

  static Long attachmentId(Map<String, Object> photo) {
    if (photo == null) {
      return null;
    }
    Object raw = photo.containsKey("attachmentId") ? photo.get("attachmentId") : photo.get("attachment_id");
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.longValue();
    }
    try {
      String text = String.valueOf(raw).trim();
      return text.isBlank() ? null : Long.parseLong(text);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  static Set<Long> attachmentIds(String photosJson) {
    if (photosJson == null || photosJson.isBlank()) {
      return Set.of();
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(photosJson);
      if (!root.isArray()) {
        return Set.of();
      }
      Set<Long> ids = new HashSet<>();
      for (JsonNode photo : root) {
        if (!photo.isObject()) {
          continue;
        }
        JsonNode attachmentId = photo.has("attachmentId")
            ? photo.get("attachmentId") : photo.get("attachment_id");
        if (attachmentId == null || attachmentId.isNull()) {
          continue;
        }
        if (!attachmentId.isIntegralNumber() || attachmentId.longValue() <= 0) {
          throw new BusinessException(
              "BAD_ATTACHMENT_ID", "巡检照片编号必须是正整数", HttpStatus.BAD_REQUEST);
        }
        ids.add(attachmentId.longValue());
      }
      return Set.copyOf(ids);
    } catch (JsonProcessingException ex) {
      throw new BusinessException("BAD_JSON", "photosJson must be a JSON array", HttpStatus.BAD_REQUEST);
    }
  }
}
