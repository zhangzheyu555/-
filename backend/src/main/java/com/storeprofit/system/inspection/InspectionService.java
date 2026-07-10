package com.storeprofit.system.inspection;

import com.storeprofit.system.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InspectionService {
  public record ExportFile(String filename, byte[] content) {}

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final InspectionRecordRepository recordRepository;
  private final String detectUrl;
  private final String exportUrl;
  private final Duration timeout;
  private final RestClient detectClient;
  private final RestClient exportClient;

  public InspectionService(
      InspectionRecordRepository recordRepository,
      @Value("${app.inspection.detect-url:http://127.0.0.1:8000/detect}") String detectUrl,
      @Value("${app.inspection.export-url:http://127.0.0.1:8000/export}") String exportUrl,
      @Value("${app.inspection.timeout:60s}") Duration timeout
  ) {
    this.recordRepository = recordRepository;
    this.detectUrl = detectUrl == null ? "" : detectUrl.trim();
    this.exportUrl = exportUrl == null ? "" : exportUrl.trim();
    this.timeout = timeout;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(timeout);
    this.detectClient = RestClient.builder()
        .baseUrl(this.detectUrl)
        .requestFactory(factory)
        .build();
    this.exportClient = RestClient.builder()
        .baseUrl(this.exportUrl)
        .requestFactory(factory)
        .build();
  }

  public List<InspectionRecordResponse> records(
      AuthUser user,
      String dateFrom,
      String dateTo,
      Long brandId,
      String storeId,
      Boolean passed
  ) {
    requireReadRole(user);
    String normalizedDateFrom = normalizeOptionalDate(dateFrom, "dateFrom");
    String normalizedDateTo = normalizeOptionalDate(dateTo, "dateTo");
    if (normalizedDateFrom != null && normalizedDateTo != null && LocalDate.parse(normalizedDateFrom).isAfter(LocalDate.parse(normalizedDateTo))) {
      throw new BusinessException("BAD_DATE_RANGE", "dateFrom cannot be after dateTo", HttpStatus.BAD_REQUEST);
    }
    if (isStoreManager(user)) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        throw new BusinessException("FORBIDDEN", "店长只能查看本店巡检记录", HttpStatus.FORBIDDEN);
      }
      return recordRepository.records(user.tenantId(), normalizedDateFrom, normalizedDateTo, brandId, scopedStoreId, passed);
    }
    return recordRepository.records(user.tenantId(), normalizedDateFrom, normalizedDateTo, brandId, blankToNull(storeId), passed);
  }

  public InspectionServiceHealthResponse serviceHealth() {
    if (detectUrl == null || detectUrl.isBlank()) {
      return new InspectionServiceHealthResponse(
          "UNCONFIGURED",
          false,
          null,
          detectUrl,
          exportUrl,
          "卫生识别服务未配置",
          Map.of()
      );
    }
    String healthUrl;
    try {
      healthUrl = healthUrlFromDetectUrl(detectUrl);
    } catch (IllegalArgumentException ex) {
      return new InspectionServiceHealthResponse(
          "UNCONFIGURED",
          false,
          null,
          detectUrl,
          exportUrl,
          "卫生识别服务地址配置无效",
          Map.of()
      );
    }
    try {
      Map<String, Object> details = RestClient.builder()
          .baseUrl(healthUrl)
          .requestFactory(healthRequestFactory())
          .build()
          .get()
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      return new InspectionServiceHealthResponse(
          "UP",
          true,
          healthUrl,
          detectUrl,
          exportUrl,
          "卫生识别服务可用",
          details == null ? Map.of() : details
      );
    } catch (RestClientException ex) {
      return new InspectionServiceHealthResponse(
          "DOWN",
          true,
          healthUrl,
          detectUrl,
          exportUrl,
          "卫生识别服务不可用，请确认 YOLO/FastAPI 服务已启动",
          Map.of()
      );
    }
  }

  public InspectionRecordResponse record(AuthUser user, String id) {
    requireReadRole(user);
    InspectionRecordResponse record = requireRecord(user.tenantId(), id);
    requireStoreScopeForRead(user, record.storeId());
    return record;
  }

  @Transactional
  public InspectionRecordResponse save(AuthUser user, String id, InspectionRecordRequest request) {
    requireWriteRole(user);
    InspectionRecordRequest normalized = normalizeRequest(request);
    if (!recordRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "Store does not exist in current tenant", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    recordRepository.upsert(user.tenantId(), targetId, normalized);
    recordRepository.replaceStandardSnapshots(user.tenantId(), targetId, standardSnapshots(normalized));
    recordRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "inspection_save",
        targetId,
        normalized.storeId(),
        normalized.inspectionDate(),
        "inspection record saved"
    );
    return requireRecord(user.tenantId(), targetId);
  }

  @Transactional
  public InspectionRecordResponse bindDetectionResults(
      AuthUser user,
      String id,
      InspectionDetectionBindingRequest request
  ) {
    requireWriteRole(user);
    InspectionRecordResponse existing = requireRecord(user.tenantId(), id);
    List<Map<String, Object>> results = normalizeDetectionResults(request);

    List<Map<String, Object>> photos = new ArrayList<>();
    List<Map<String, Object>> deductions = new ArrayList<>();
    List<Map<String, Object>> redlines = new ArrayList<>();
    BigDecimal totalDeduction = BigDecimal.ZERO;

    for (Map<String, Object> result : results) {
      String imageId = textValue(result, "image_id", "imageId");
      String filename = textValue(result, "filename");
      Integer detectionCount = intValue(result, "detection_count", "detectionCount");
      Object detections = value(result, "detections");
      boolean passed = resultPassed(result, detectionCount, detections);
      String autoStatus = textValue(result, "auto_status", "autoStatus", "review_status", "reviewStatus");
      String summary = textValue(result, "detection_summary", "detectionSummary");
      BigDecimal deductionScore = decimalValue(result, "deduction_score", "deductionScore");

      Map<String, Object> photo = new LinkedHashMap<>();
      putIfPresent(photo, "image_id", imageId);
      putIfPresent(photo, "filename", filename);
      photo.put("passed", passed);
      putIfPresent(photo, "auto_status", autoStatus);
      if (detectionCount != null) {
        photo.put("detection_count", detectionCount);
      }
      putIfPresent(photo, "detection_summary", summary);
      putIfPresent(photo, "annotated_image", textValue(result, "annotated_image", "annotatedImage"));
      putIfPresent(photo, "original_image", textValue(result, "original_image", "originalImage"));
      photos.add(photo);

      String deductionProject = textValue(result, "deduction_project", "deductionProject");
      String deductionContent = textValue(result, "deduction_content", "deductionContent");
      if (hasDeduction(deductionProject, deductionContent, deductionScore)) {
        Map<String, Object> deduction = new LinkedHashMap<>();
        putIfPresent(deduction, "image_id", imageId);
        putIfPresent(deduction, "filename", filename);
        putIfPresent(deduction, "deduction_project", deductionProject);
        putIfPresent(deduction, "deduction_content", deductionContent);
        if (deductionScore != null) {
          BigDecimal normalizedScore = deductionScore.setScale(2, RoundingMode.HALF_UP);
          deduction.put("deduction_score", normalizedScore);
          totalDeduction = totalDeduction.add(normalizedScore.abs());
        }
        deductions.add(deduction);
      }

      if (!passed || (detectionCount != null && detectionCount > 0) || hasDetections(detections)) {
        Map<String, Object> redline = new LinkedHashMap<>();
        putIfPresent(redline, "image_id", imageId);
        putIfPresent(redline, "filename", filename);
        putIfPresent(redline, "auto_status", autoStatus);
        if (detectionCount != null) {
          redline.put("detection_count", detectionCount);
        }
        putIfPresent(redline, "detection_summary", summary);
        if (detections != null) {
          redline.put("detections", detections);
        }
        redlines.add(redline);
      }
    }

    BigDecimal fullScore = amountOrDefault(request == null ? null : request.fullScore(), existing.fullScore());
    BigDecimal score = fullScore.subtract(totalDeduction).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    validateScore(fullScore, score);

    InspectionRecordRequest normalized = new InspectionRecordRequest(
        existing.storeId(),
        existing.inspectionDate(),
        firstNonBlank(request == null ? null : request.inspector(), existing.inspector()),
        firstNonBlank(request == null ? null : request.brand(), existing.brand()),
        fullScore,
        score,
        redlines.isEmpty(),
        toJson(deductions),
        toJson(redlines),
        toJson(photos),
        firstNonBlank(request == null ? null : request.note(), existing.note())
    );
    recordRepository.upsert(user.tenantId(), existing.id(), normalized);
    recordRepository.replaceStandardSnapshots(user.tenantId(), existing.id(), standardSnapshots(normalized));
    recordRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "inspection_detection_bind",
        existing.id(),
        existing.storeId(),
        existing.inspectionDate(),
        "inspection detection results bound"
    );
    return requireRecord(user.tenantId(), existing.id());
  }

  @Transactional
  public void delete(AuthUser user, String id) {
    requireWriteRole(user);
    InspectionRecordResponse record = requireRecord(user.tenantId(), id);
    int deleted = recordRepository.delete(user.tenantId(), record.id());
    if (deleted == 0) {
      throw new BusinessException("NOT_FOUND", "Inspection record not found", HttpStatus.NOT_FOUND);
    }
    recordRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "inspection_delete",
        record.id(),
        record.storeId(),
        record.inspectionDate(),
        "inspection record deleted"
    );
  }

  public ExportFile export(Map<String, Object> payload) {
    try {
      return exportClient.post()
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
              throw new BusinessException(
                  "INSPECTION_EXPORT_FAILED",
                  "Excel 生成失败（识别服务返回 " + response.getStatusCode().value() + "）",
                  HttpStatus.BAD_GATEWAY
              );
            }
            String filename = response.getHeaders().getFirst("X-Export-Filename");
            byte[] content = response.getBody().readAllBytes();
            return new ExportFile(filename == null ? "export.xlsx" : filename, content);
          });
    } catch (BusinessException ex) {
      throw ex;
    } catch (RestClientException | java.io.UncheckedIOException ex) {
      throw new BusinessException(
          "INSPECTION_SERVICE_UNAVAILABLE",
          "卫生识别服务不可用，请确认识别服务已启动",
          HttpStatus.BAD_GATEWAY
      );
    }
  }

  public Map<String, Object> detect(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException("INSPECTION_EMPTY_FILE", "请上传图片文件", HttpStatus.BAD_REQUEST);
    }

    ByteArrayResource resource;
    try {
      byte[] bytes = file.getBytes();
      String filename = file.getOriginalFilename() == null ? "photo.jpg" : file.getOriginalFilename();
      resource = new ByteArrayResource(bytes) {
        @Override
        public String getFilename() {
          return filename;
        }
      };
    } catch (IOException ex) {
      throw new BusinessException("INSPECTION_READ_FAILED", "图片读取失败", HttpStatus.BAD_REQUEST);
    }

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", resource);

    try {
      Map<String, Object> result = detectClient.post()
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      if (result == null) {
        throw new BusinessException("INSPECTION_EMPTY_RESULT", "识别服务返回为空", HttpStatus.BAD_GATEWAY);
      }
      return result;
    } catch (RestClientException ex) {
      throw new BusinessException(
          "INSPECTION_SERVICE_UNAVAILABLE",
          "卫生识别服务不可用，请确认识别服务已启动",
          HttpStatus.BAD_GATEWAY
      );
    }
  }

  private InspectionRecordRequest normalizeRequest(InspectionRecordRequest request) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Inspection payload is required", HttpStatus.BAD_REQUEST);
    }
    BigDecimal fullScore = amountOrDefault(request.fullScore(), new BigDecimal("100.00"));
    BigDecimal score = amountOrDefault(request.score(), fullScore);
    validateScore(fullScore, score);
    return new InspectionRecordRequest(
        requireText(request.storeId(), "STORE_REQUIRED", "Store is required"),
        normalizeDate(request.inspectionDate(), "inspectionDate"),
        request.inspector(),
        request.brand(),
        fullScore,
        score,
        request.passed() == null ? inferPassed(request.redlinesJson()) : request.passed(),
        normalizeJsonArrayText(request.deductionsJson()),
        normalizeJsonArrayText(request.redlinesJson()),
        normalizeJsonArrayText(request.photosJson()),
        request.note()
    );
  }

  private InspectionRecordResponse requireRecord(long tenantId, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "Inspection record id is required");
    return recordRepository.record(tenantId, targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "Inspection record not found", HttpStatus.NOT_FOUND));
  }

  private void requireReadRole(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "SUPERVISOR", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to read inspection records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireWriteRole(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "SUPERVISOR").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to edit inspection records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreScopeForRead(AuthUser user, String storeId) {
    if (!isStoreManager(user)) {
      return;
    }
    String scopedStoreId = requireManagerStore(user);
    if (!scopedStoreId.equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "Store manager can only read own store inspection records", HttpStatus.FORBIDDEN);
    }
  }

  private boolean isStoreManager(AuthUser user) {
    return "STORE_MANAGER".equals(user.role());
  }

  private String requireManagerStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
    }
    return user.storeId().trim();
  }

  private void validateScore(BigDecimal fullScore, BigDecimal score) {
    if (fullScore.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_SCORE", "Full score must be greater than 0", HttpStatus.BAD_REQUEST);
    }
    if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(fullScore) > 0) {
      throw new BusinessException("BAD_SCORE", "Score must be between 0 and full score", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeOptionalDate(String value, String label) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return normalizeDate(value, label);
  }

  private String normalizeDate(String value, String label) {
    try {
      return LocalDate.parse(requireText(value, "BAD_DATE", label + " is required")).toString();
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException("BAD_DATE", label + " must use YYYY-MM-DD", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeId(String id) {
    if (id != null && !id.isBlank()) {
      return id.trim();
    }
    return "INSP" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private BigDecimal amountOrDefault(BigDecimal value, BigDecimal defaultValue) {
    return (value == null ? defaultValue : value).setScale(2, RoundingMode.HALF_UP);
  }

  private boolean inferPassed(String redlinesJson) {
    String normalized = normalizeJsonArrayText(redlinesJson);
    return normalized == null || "[]".equals(normalized);
  }

  private String normalizeJsonArrayText(String value) {
    if (value == null || value.isBlank()) {
      return "[]";
    }
    return value.trim();
  }

  private List<Map<String, Object>> normalizeDetectionResults(InspectionDetectionBindingRequest request) {
    if (request == null || request.results() == null || request.results().isEmpty()) {
      throw new BusinessException("BAD_DETECTION_RESULTS", "Detection results are required", HttpStatus.BAD_REQUEST);
    }
    return request.results().stream()
        .filter(item -> item != null && !item.isEmpty())
        .toList();
  }

  private boolean resultPassed(Map<String, Object> result, Integer detectionCount, Object detections) {
    Boolean passed = booleanValue(result, "passed");
    if (passed != null) {
      return passed;
    }
    if (detectionCount != null) {
      return detectionCount <= 0;
    }
    return !hasDetections(detections);
  }

  private boolean hasDeduction(String project, String content, BigDecimal score) {
    return (project != null && !project.isBlank())
        || (content != null && !content.isBlank())
        || (score != null && score.compareTo(BigDecimal.ZERO) != 0);
  }

  private boolean hasDetections(Object detections) {
    if (detections instanceof List<?> list) {
      return !list.isEmpty();
    }
    return detections != null;
  }

  private String toJson(List<Map<String, Object>> values) {
    try {
      return OBJECT_MAPPER.writeValueAsString(values);
    } catch (JsonProcessingException ex) {
      throw new BusinessException("BAD_DETECTION_RESULTS", "Detection results cannot be serialized", HttpStatus.BAD_REQUEST);
    }
  }

  private String firstNonBlank(String first, String fallback) {
    String normalizedFirst = blankToNull(first);
    return normalizedFirst == null ? fallback : normalizedFirst;
  }

  private void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private Object value(Map<String, Object> source, String key) {
    return source == null ? null : source.get(key);
  }

  private Object value(Map<String, Object> source, String... keys) {
    if (source == null) {
      return null;
    }
    for (String key : keys) {
      if (source.containsKey(key)) {
        return source.get(key);
      }
    }
    return null;
  }

  private String textValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    String text = String.valueOf(raw).trim();
    return text.isBlank() ? null : text;
  }

  private Integer intValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.intValue();
    }
    try {
      String text = String.valueOf(raw).trim();
      return text.isBlank() ? null : Integer.parseInt(text);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Boolean booleanValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw instanceof Boolean bool) {
      return bool;
    }
    if (raw == null) {
      return null;
    }
    String text = String.valueOf(raw).trim();
    if (text.isBlank()) {
      return null;
    }
    return Boolean.parseBoolean(text);
  }

  private BigDecimal decimalValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    try {
      String text = String.valueOf(raw).trim();
      if (text.isBlank()) {
        return null;
      }
      return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private List<InspectionStandardSnapshot> standardSnapshots(InspectionRecordRequest request) {
    List<InspectionStandardSnapshot> snapshots = new ArrayList<>();
    appendStandardSnapshots(snapshots, request == null ? null : request.deductionsJson(), false);
    appendStandardSnapshots(snapshots, request == null ? null : request.redlinesJson(), true);
    return snapshots;
  }

  private void appendStandardSnapshots(
      List<InspectionStandardSnapshot> snapshots,
      String rawJson,
      boolean forceRedLine
  ) {
    if (rawJson == null || rawJson.isBlank()) {
      return;
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(rawJson);
      if (root == null || !root.isArray()) {
        return;
      }
      for (JsonNode node : root) {
        if (node == null || !node.isObject()) {
          continue;
        }
        Long standardId = nodeLong(node, "standard_id", "standardId");
        String title = nodeText(node, "standard_title", "standardTitle", "item", "title", "deduction_content");
        if (standardId == null && (title == null || title.isBlank())) {
          continue;
        }
        boolean redLine = forceRedLine || nodeBoolean(node, "red_line", "redline", "redLine");
        snapshots.add(new InspectionStandardSnapshot(
            standardId,
            nodeText(node, "standard_version", "standardVersion"),
            nodeText(node, "dimension", "dim", "deduction_project", "project"),
            title,
            nodeText(node, "standard_description", "standardDescription", "method"),
            nodeDecimal(node, "suggested_score", "suggestedScore", "score"),
            nodeDecimal(node, "actual_deduction_score", "actualDeductionScore", "deduction_score", "deductionScore", "deduct"),
            redLine,
            nodeText(node, "problem_description", "problemDescription", "issue", "description"),
            snapshots.size() + 1
        ));
      }
    } catch (JsonProcessingException ignored) {
      // Existing records may contain legacy free-form JSON. Keep the original JSON without creating a malformed snapshot row.
    }
  }

  private String nodeText(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && !value.isNull()) {
        String text = value.asText("").trim();
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }

  private Long nodeLong(JsonNode node, String... names) {
    String value = nodeText(node, names);
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private BigDecimal nodeDecimal(JsonNode node, String... names) {
    String value = nodeText(node, names);
    if (value == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    try {
      return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException ignored) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
  }

  private boolean nodeBoolean(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isBoolean()) {
        return value.booleanValue();
      }
      String text = value.asText("").trim();
      if ("1".equals(text) || "yes".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)) {
        return true;
      }
    }
    return false;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private SimpleClientHttpRequestFactory healthRequestFactory() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    Duration healthTimeout = timeout == null ? Duration.ofSeconds(2) : timeout;
    factory.setConnectTimeout(healthTimeout);
    factory.setReadTimeout(healthTimeout);
    return factory;
  }

  private String healthUrlFromDetectUrl(String value) {
    try {
      URI uri = URI.create(value.trim());
      if (uri.getScheme() == null || uri.getHost() == null) {
        throw new IllegalArgumentException("missing scheme or host");
      }
      return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), "/health", null, null).toString();
    } catch (IllegalArgumentException | URISyntaxException ex) {
      throw new IllegalArgumentException("invalid inspection detect URL", ex);
    }
  }
}
