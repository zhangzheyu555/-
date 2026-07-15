package com.storeprofit.system.inspection;

import com.storeprofit.system.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.storage.StorageUploadResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final String INCORRECT_STANDARD_VERSION = "2025.11.06";
  private static final String DETECTION_PENDING = "PENDING_MANUAL_CONFIRMATION";
  private static final String DETECTION_CONFIRMED = "CONFIRMED";
  private static final String DETECTION_REVOKED = "REVOKED";
  private static final String DETECTION_UNMATCHED = "UNMATCHED";
  private static final BigDecimal DETECTION_IOU_THRESHOLD = new BigDecimal("0.90");
  private static final BigDecimal H_4_1_2_SERVER_DEDUCTION = new BigDecimal("4.00");
  private static final String H_4_1_2_DEDUCTION_POLICY = "LEGACY_100_TO_200_H412_V1";
  private static final String ACTIVE_CLAUSE_DEDUCTION_POLICY = "ACTIVE_CLAUSE_SCORE_V1";
  /** 标注图仅用于当前浏览器识别结果预览，不能进入巡检记录或 MySQL。 */
  private static final int MAX_TRANSIENT_ANNOTATED_IMAGE_CHARS = 5_000_000;
  private final InspectionRecordRepository recordRepository;
  private final InspectionStandardRepository standardRepository;
  private final StorageService storageService;
  private final String detectUrl;
  private final String exportUrl;
  private final Duration timeout;
  private final RestClient detectClient;
  private final RestClient exportClient;
  private final AccessControlService accessControl;

  @Autowired
  public InspectionService(
      InspectionRecordRepository recordRepository,
      AccessControlService accessControl,
      InspectionStandardRepository standardRepository,
      StorageService storageService,
      @Value("${app.inspection.detect-url:http://127.0.0.1:8000/detect}") String detectUrl,
      @Value("${app.inspection.export-url:http://127.0.0.1:8000/export}") String exportUrl,
      @Value("${app.inspection.timeout:60s}") Duration timeout
  ) {
    this.recordRepository = recordRepository;
    this.standardRepository = standardRepository;
    this.storageService = storageService;
    this.detectUrl = detectUrl == null ? "" : detectUrl.trim();
    this.exportUrl = exportUrl == null ? "" : exportUrl.trim();
    this.timeout = timeout;
    this.accessControl = accessControl;
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

  /** Compatibility constructor retained for unit tests that do not exercise versioned scoring. */
  public InspectionService(
      InspectionRecordRepository recordRepository,
      AccessControlService accessControl,
      String detectUrl,
      String exportUrl,
      Duration timeout
  ) {
    this(recordRepository, accessControl, null, null, detectUrl, exportUrl, timeout);
  }

  /** Compatibility constructor retained for isolated service tests. */
  public InspectionService(
      InspectionRecordRepository recordRepository,
      String detectUrl,
      String exportUrl,
      Duration timeout
  ) {
    this(recordRepository, null, null, null, detectUrl, exportUrl, timeout);
  }

  public List<InspectionRecordResponse> records(
      AuthUser user,
      String dateFrom,
      String dateTo,
      Long brandId,
      String storeId,
      Boolean passed
  ) {
    requireInspectionRead(user);
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
    if (accessControl != null) {
      String requestedStoreId = blankToNull(storeId);
      if (requestedStoreId != null) {
        requireInspectionStoreAccess(user, requestedStoreId, "查看巡检记录");
        return recordRepository.records(
            user.tenantId(), normalizedDateFrom, normalizedDateTo, brandId, requestedStoreId, passed);
      }
      if (!accessControl.hasAllDataScope(user, DataScopeDomains.INSPECTION)) {
        Set<String> allowedStoreIds = accessControl.allowedStoreIds(user, DataScopeDomains.INSPECTION);
        return recordRepository.records(
            user.tenantId(), normalizedDateFrom, normalizedDateTo, brandId, null, passed, allowedStoreIds);
      }
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
    requireInspectionRead(user);
    InspectionRecordResponse record = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, record.storeId(), "查看巡检记录");
    return record;
  }

  /**
   * Safe forensic view for a historical inspection.  Candidates are derived only from the
   * record's stored photo references and attachments already bound to that exact record; this is
   * never a filename-based or store-wide attachment search.
   */
  public InspectionEvidenceCandidatesResponse historicalEvidenceCandidates(AuthUser user, String id) {
    requireHistoricalEvidenceManage(user);
    InspectionRecordResponse record = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, record.storeId(), "查看历史巡检证据");
    StorageService storage = requireInspectionEvidenceStorage();

    Map<Long, List<Long>> linkedClauses = linkedClauseIds(record.itemResults());
    Map<Long, StorageService.InspectionAttachmentMetadata> storedById = new LinkedHashMap<>();
    for (StorageService.InspectionAttachmentMetadata attachment
        : storage.inspectionRecordAttachments(user, record.storeId(), record.id())) {
      storedById.put(attachment.id(), attachment);
    }

    List<InspectionEvidenceAttachmentResponse> candidates = new ArrayList<>();
    Set<Long> handledAttachmentIds = new LinkedHashSet<>();
    List<Map<String, Object>> storedPhotos = parseEvidencePhotos(record.photosJson());
    for (int photoIndex = 0; photoIndex < storedPhotos.size(); photoIndex++) {
      Map<String, Object> photo = storedPhotos.get(photoIndex);
      Long attachmentId = longValue(photo, "attachmentId", "attachment_id");
      if (attachmentId == null || attachmentId <= 0) {
        candidates.add(new InspectionEvidenceAttachmentResponse(
            photoIndex,
            null,
            textValue(photo, "fileName", "filename", "name"),
            null,
            "ORIGINAL_NOT_STORED",
            "原图未入库，需补传",
            List.of()
        ));
        continue;
      }
      if (!handledAttachmentIds.add(attachmentId)) {
        continue;
      }
      StorageService.InspectionAttachmentMetadata attachment = storedById.get(attachmentId);
      if (attachment == null) {
        attachment = storage.historicalInspectionAttachment(
            user, record.storeId(), record.id(), attachmentId, true).orElse(null);
      }
      if (attachment == null || !attachment.contentStored()) {
        candidates.add(new InspectionEvidenceAttachmentResponse(
            photoIndex,
            attachmentId,
            attachment == null ? textValue(photo, "fileName", "filename", "name") : attachment.fileName(),
            attachment == null ? null : attachment.contentType(),
            attachment == null ? "MISSING" : "ORIGINAL_NOT_STORED",
            "原图未入库，需补传",
            List.of()
        ));
        continue;
      }
      candidates.add(evidenceCandidate(
          photoIndex, attachment, linkedClauses.getOrDefault(attachmentId, List.of())));
    }
    for (StorageService.InspectionAttachmentMetadata attachment : storedById.values()) {
      if (handledAttachmentIds.add(attachment.id())) {
        candidates.add(evidenceCandidate(
            null, attachment, linkedClauses.getOrDefault(attachment.id(), List.of())));
      }
    }
    return new InspectionEvidenceCandidatesResponse(record.id(), record.storeId(), candidates);
  }

  @Transactional
  public InspectionEvidenceLinkResponse linkHistoricalEvidence(
      AuthUser user,
      String id,
      InspectionEvidenceLinkRequest request
  ) {
    if (request == null) {
      throw new BusinessException("INSPECTION_EVIDENCE_REQUIRED", "请选择现场证据和历史条款", HttpStatus.BAD_REQUEST);
    }
    return linkHistoricalEvidence(
        user, id, request.attachmentIds(), request.clauseIds(), request.historicalSnapshotIds(),
        "inspection_historical_evidence_link", "ASSOCIATE", "关联", null, null, null);
  }

  @Transactional
  public InspectionEvidenceLinkResponse uploadAndLinkHistoricalEvidence(
      AuthUser user,
      String id,
      MultipartFile file,
      List<Long> clauseIds,
      List<Long> historicalSnapshotIds,
      Integer sourcePhotoIndex
  ) {
    requireHistoricalEvidenceManage(user);
    InspectionRecordResponse record = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, record.storeId(), "补传历史巡检证据");
    requireInspectionEvidenceImage(file);
    StorageService storage = requireInspectionEvidenceStorage();
    validatePhotoSourceForUpload(user, record, storage, sourcePhotoIndex);
    StorageUploadResponse uploaded = storage.uploadHistoricalInspectionEvidence(
        user, file, record.id(), record.storeId());
    if (uploaded.id() == null || uploaded.id() <= 0) {
      throw new BusinessException("INSPECTION_EVIDENCE_UPLOAD_FAILED", "现场证据上传失败，请重新选择原图", HttpStatus.BAD_GATEWAY);
    }
    return linkHistoricalEvidence(
        user, id, List.of(uploaded.id()), clauseIds, historicalSnapshotIds,
        "inspection_historical_evidence_upload", "SUPPLEMENT", "补传并关联", sourcePhotoIndex,
        uploaded.fileName(), uploaded.contentType());
  }

  /**
   * Dedicated historical-evidence writer.  It intentionally does not call save(), score
   * calculation, standard replacement, or rectification logic.
   *
   * <p>Two association paths are supported, and at least one must be non-empty:
   * <ol>
   *   <li>{@code sourceClauseIds} — keyed by {@code standardItemId}; used for records
   *       whose snapshots still carry a valid {@code inspection_standard_item.id}.</li>
   *   <li>{@code sourceHistoricalSnapshotIds} — keyed by the snapshot row's own
   *       {@code id} (snapshotId); the only safe path when {@code standard_id} is NULL.</li>
   * </ol></p>
   */
  private InspectionEvidenceLinkResponse linkHistoricalEvidence(
      AuthUser user,
      String id,
      List<Long> sourceAttachmentIds,
      List<Long> sourceClauseIds,
      List<Long> sourceHistoricalSnapshotIds,
      String auditAction,
      String actionCode,
      String actionLabel,
      Integer sourcePhotoIndex,
      String uploadedFileName,
      String uploadedContentType
  ) {
    requireHistoricalEvidenceManage(user);
    InspectionRecordResponse preLock = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, preLock.storeId(), "补充历史巡检证据");
    recordRepository.lockRecord(user.tenantId(), preLock.id());
    InspectionRecordResponse record = requireRecord(user.tenantId(), preLock.id());
    requireInspectionStoreAccess(user, record.storeId(), "补充历史巡检证据");
    StorageService storage = requireInspectionEvidenceStorage();
    List<Long> attachmentIds = requiredEvidenceIds(
        sourceAttachmentIds, "INSPECTION_EVIDENCE_REQUIRED", "请选择要关联的现场证据");

    List<Long> clauseIds = sourceClauseIds != null ? sourceClauseIds.stream()
        .filter(v -> v != null && v > 0).distinct().toList() : List.of();
    List<Long> historicalSnapshotIds = sourceHistoricalSnapshotIds != null
        ? sourceHistoricalSnapshotIds.stream().filter(v -> v != null && v > 0).distinct().toList()
        : List.of();

    if (clauseIds.isEmpty() && historicalSnapshotIds.isEmpty()) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_CLAUSE_REQUIRED", "请至少选择一条历史巡检条款", HttpStatus.BAD_REQUEST);
    }

    List<InspectionItemResultResponse> itemResults = record.itemResults();
    Map<Long, InspectionItemResultResponse> snapshotsByStandardId = new HashMap<>();
    Map<Long, InspectionItemResultResponse> snapshotsBySnapshotId = new HashMap<>();
    for (InspectionItemResultResponse item : itemResults == null ? List.<InspectionItemResultResponse>of() : itemResults) {
      if (item.standardItemId() != null && item.standardItemId() > 0) {
        snapshotsByStandardId.putIfAbsent(item.standardItemId(), item);
      }
      if (item.snapshotId() != null && item.snapshotId() > 0) {
        snapshotsBySnapshotId.put(item.snapshotId(), item);
      }
    }

    // Validate clauseIds (standardItemId path)
    for (Long clauseId : clauseIds) {
      if (!snapshotsByStandardId.containsKey(clauseId)) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_CLAUSE_NOT_FOUND",
            "所选历史巡检条款不存在，不能按名称或编号猜测关联",
            HttpStatus.BAD_REQUEST
        );
      }
    }

    // Validate historicalSnapshotIds (snapshot row id path)
    for (Long snapshotId : historicalSnapshotIds) {
      InspectionItemResultResponse snapshot = snapshotsBySnapshotId.get(snapshotId);
      if (snapshot == null) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_CLAUSE_NOT_FOUND",
            "所选历史巡检条款快照不属于当前记录，不能按名称或编号猜测关联",
            HttpStatus.BAD_REQUEST
        );
      }
    }

    Set<Long> allowedAttachmentIds = historicalCandidateAttachmentIds(user, record, storage);
    for (Long attachmentId : attachmentIds) {
      if (!allowedAttachmentIds.contains(attachmentId)) {
        throw new BusinessException(
            "ATTACHMENT_SCOPE_MISMATCH",
            "该附件未明确属于当前历史巡检，不能按文件名或编号猜测关联",
            HttpStatus.FORBIDDEN
        );
      }
      storage.rebindHistoricalInspectionEvidence(user, record.storeId(), record.id(), attachmentId, true);
    }

    List<Map<String, Object>> photos = parseEvidencePhotos(record.photosJson());
    validatePhotoSourceForUpload(user, record, storage, sourcePhotoIndex);
    bindEvidencePhotos(photos, attachmentIds, sourcePhotoIndex, uploadedFileName, uploadedContentType);
    recordRepository.updateRecordPhotosJson(user.tenantId(), record.id(), toJson(photos));

    // Write evidence IDs through standardItemId path
    for (Long clauseId : clauseIds) {
      InspectionItemResultResponse snapshot = snapshotsByStandardId.get(clauseId);
      List<Long> merged = mergeAttachmentIds(snapshot.photoAttachmentIds(), attachmentIds);
      recordRepository.updateSnapshotEvidenceIds(user.tenantId(), record.id(), clauseId, merged);
    }

    // Write evidence IDs through snapshotId path
    for (Long snapshotId : historicalSnapshotIds) {
      InspectionItemResultResponse snapshot = snapshotsBySnapshotId.get(snapshotId);
      List<Long> merged = mergeAttachmentIds(snapshot.photoAttachmentIds(), attachmentIds);
      recordRepository.updateSnapshotEvidenceIdsBySnapshotId(user.tenantId(), record.id(), snapshotId, merged);
    }

    // Operation log for clauseIds path
    for (Long clauseId : clauseIds) {
      for (Long attachmentId : attachmentIds) {
        recordRepository.logAction(
            user.tenantId(), user.id(), user.displayName(), auditAction,
            record.id(), record.storeId(), record.inspectionDate(),
            actionLabel + "历史巡检现场证据；recordId=" + record.id()
                + "；条款ID=" + clauseId + "；附件ID=" + attachmentId
        );
      }
    }

    // Operation log for snapshotId path
    for (Long snapshotId : historicalSnapshotIds) {
      for (Long attachmentId : attachmentIds) {
        recordRepository.logAction(
            user.tenantId(), user.id(), user.displayName(), auditAction,
            record.id(), record.storeId(), record.inspectionDate(),
            actionLabel + "历史巡检现场证据；recordId=" + record.id()
                + "；snapshotId=" + snapshotId + "；附件ID=" + attachmentId
        );
      }
    }

    // Response uses clauseIds for backward compat; also includes snapshot IDs for audit
    List<Long> allClauseIds = new ArrayList<>(clauseIds);
    allClauseIds.addAll(historicalSnapshotIds);
    return new InspectionEvidenceLinkResponse(
        record.id(), actionCode, attachmentIds, allClauseIds, requireRecord(user.tenantId(), record.id()));
  }

  private void requireHistoricalEvidenceManage(AuthUser user) {
    requireInspectionRead(user);
    requireInspectionManage(user);
    // AccessControlService canonicalizes the local SUPERVISOR role to OPERATIONS. The latter is
    // accepted here only for that established supervisor mapping; this remains restricted by
    // inspection permission and the record's store range below.
    if (AccessControlService.isBoss(user)
        || AccessControlService.hasAnyRole(user, "SUPERVISOR", "OPERATIONS")) {
      return;
    }
    throw new BusinessException(
        "FORBIDDEN", "只有老板或负责巡检的督导可以补传和关联历史现场证据", HttpStatus.FORBIDDEN);
  }

  private StorageService requireInspectionEvidenceStorage() {
    if (storageService == null) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_STORAGE_UNAVAILABLE", "巡检附件服务不可用，暂不能补传现场证据", HttpStatus.CONFLICT);
    }
    return storageService;
  }

  private void requireInspectionEvidenceImage(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException("INSPECTION_EVIDENCE_FILE_REQUIRED", "请从微信重新选择原图补传", HttpStatus.BAD_REQUEST);
    }
    String contentType = file.getContentType();
    if (contentType == null || !contentType.trim().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_IMAGE_REQUIRED", "现场证据必须上传图片原图", HttpStatus.BAD_REQUEST);
    }
  }

  private InspectionEvidenceAttachmentResponse evidenceCandidate(
      Integer photoIndex,
      StorageService.InspectionAttachmentMetadata attachment,
      List<Long> linkedClauseIds
  ) {
    if (!attachment.contentStored()) {
      return new InspectionEvidenceAttachmentResponse(
          photoIndex, attachment.id(), attachment.fileName(), attachment.contentType(), "ORIGINAL_NOT_STORED",
          "原图未入库，需补传", List.of());
    }
    if (attachment.contentType() == null
        || !attachment.contentType().trim().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
      return new InspectionEvidenceAttachmentResponse(
          photoIndex, attachment.id(), attachment.fileName(), attachment.contentType(), "INVALID_TYPE",
          "附件不是图片，不能作为现场证据", linkedClauseIds);
    }
    return new InspectionEvidenceAttachmentResponse(
        photoIndex, attachment.id(), attachment.fileName(), attachment.contentType(),
        linkedClauseIds.isEmpty() ? "UNLINKED" : "LINKED",
        linkedClauseIds.isEmpty() ? "已入库，未关联历史条款" : "已关联现场证据",
        linkedClauseIds);
  }

  private Map<Long, List<Long>> linkedClauseIds(List<InspectionItemResultResponse> snapshots) {
    Map<Long, List<Long>> linked = new HashMap<>();
    for (InspectionItemResultResponse snapshot : snapshots == null ? List.<InspectionItemResultResponse>of() : snapshots) {
      if (snapshot.standardItemId() == null) {
        continue;
      }
      for (Long attachmentId : snapshot.photoAttachmentIds()) {
        if (attachmentId != null && attachmentId > 0) {
          linked.computeIfAbsent(attachmentId, ignored -> new ArrayList<>()).add(snapshot.standardItemId());
        }
      }
    }
    linked.replaceAll((id, clauseIds) -> clauseIds.stream().distinct().toList());
    return linked;
  }

  private Set<Long> historicalCandidateAttachmentIds(
      AuthUser user,
      InspectionRecordResponse record,
      StorageService storage
  ) {
    Set<Long> allowed = new HashSet<>();
    for (Map<String, Object> photo : parseEvidencePhotos(record.photosJson())) {
      Long attachmentId = longValue(photo, "attachmentId", "attachment_id");
      if (attachmentId != null && attachmentId > 0) {
        allowed.add(attachmentId);
      }
    }
    for (StorageService.InspectionAttachmentMetadata attachment
        : storage.inspectionRecordAttachments(user, record.storeId(), record.id())) {
      allowed.add(attachment.id());
    }
    return allowed;
  }

  private Map<Long, InspectionItemResultResponse> snapshotsByStandardId(
      List<InspectionItemResultResponse> snapshots
  ) {
    Map<Long, InspectionItemResultResponse> byId = new HashMap<>();
    for (InspectionItemResultResponse snapshot : snapshots == null ? List.<InspectionItemResultResponse>of() : snapshots) {
      if (snapshot.standardItemId() == null || byId.put(snapshot.standardItemId(), snapshot) != null) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_CLAUSE_NOT_FOUND",
            "历史巡检条款快照缺少唯一条款编号，不能猜测关联",
            HttpStatus.CONFLICT
        );
      }
    }
    return byId;
  }

  private List<Long> requiredEvidenceIds(List<Long> values, String code, String message) {
    if (values == null || values.isEmpty()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    List<Long> normalized = new ArrayList<>();
    for (Long value : values) {
      if (value == null || value <= 0 || normalized.contains(value)) {
        throw new BusinessException("BAD_ATTACHMENT_ID", "现场证据或历史条款编号不正确", HttpStatus.BAD_REQUEST);
      }
      normalized.add(value);
    }
    return List.copyOf(normalized);
  }

  private List<Long> mergeAttachmentIds(List<Long> existing, List<Long> additions) {
    LinkedHashSet<Long> merged = new LinkedHashSet<>();
    if (existing != null) {
      existing.stream().filter(Objects::nonNull).filter(value -> value > 0).forEach(merged::add);
    }
    additions.stream().filter(Objects::nonNull).filter(value -> value > 0).forEach(merged::add);
    return List.copyOf(merged);
  }

  private boolean photosContainAttachmentId(List<Map<String, Object>> photos, long attachmentId) {
    return photos.stream().anyMatch(photo -> Objects.equals(
        longValue(photo, "attachmentId", "attachment_id"), attachmentId));
  }

  /**
   * Associates a freshly uploaded original with the exact metadata-only photo selected by its
   * server-issued array position.  No filename comparison is performed.  When no position is
   * supplied the upload is a new evidence item and gets an explicit new JSON object.
   */
  private void bindEvidencePhotos(
      List<Map<String, Object>> photos,
      List<Long> attachmentIds,
      Integer sourcePhotoIndex,
      String uploadedFileName,
      String uploadedContentType
  ) {
    if (sourcePhotoIndex != null) {
      if (attachmentIds.size() != 1 || sourcePhotoIndex < 0 || sourcePhotoIndex >= photos.size()) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_SOURCE_INVALID", "待补传的历史图片已变化，请刷新后重新选择", HttpStatus.CONFLICT);
      }
      Map<String, Object> target = photos.get(sourcePhotoIndex);
      target.remove("attachment_id");
      target.put("attachmentId", attachmentIds.getFirst());
      if (uploadedFileName != null && !uploadedFileName.isBlank()) {
        target.put("fileName", uploadedFileName);
      }
      if (uploadedContentType != null && !uploadedContentType.isBlank()) {
        target.put("contentType", uploadedContentType);
      }
      return;
    }
    for (Long attachmentId : attachmentIds) {
      if (!photosContainAttachmentId(photos, attachmentId)) {
        Map<String, Object> appended = new LinkedHashMap<>();
        appended.put("attachmentId", attachmentId);
        if (uploadedFileName != null && !uploadedFileName.isBlank()) {
          appended.put("fileName", uploadedFileName);
        }
        if (uploadedContentType != null && !uploadedContentType.isBlank()) {
          appended.put("contentType", uploadedContentType);
        }
        photos.add(appended);
      }
    }
  }

  private void validatePhotoSourceForUpload(
      AuthUser user,
      InspectionRecordResponse record,
      StorageService storage,
      Integer sourcePhotoIndex
  ) {
    if (sourcePhotoIndex == null) {
      return;
    }
    List<Map<String, Object>> photos = parseEvidencePhotos(record.photosJson());
    if (sourcePhotoIndex < 0 || sourcePhotoIndex >= photos.size()) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_SOURCE_INVALID", "待补传的历史图片不存在，请刷新后重新选择", HttpStatus.CONFLICT);
    }
    Long existingAttachmentId = longValue(photos.get(sourcePhotoIndex), "attachmentId", "attachment_id");
    if (existingAttachmentId == null || existingAttachmentId <= 0) {
      return;
    }
    StorageService.InspectionAttachmentMetadata attachment = storage.historicalInspectionAttachment(
        user, record.storeId(), record.id(), existingAttachmentId, true).orElse(null);
    if (attachment == null || !attachment.contentStored()) {
      return;
    }
    if (attachment.contentType() != null
        && attachment.contentType().trim().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_SOURCE_INVALID", "该历史图片已经关联可用原图，请直接选择已有证据", HttpStatus.CONFLICT);
    }
  }

  private List<Map<String, Object>> parseEvidencePhotos(String photosJson) {
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
              "INSPECTION_EVIDENCE_UNLINKED", "巡检图片缺少有效附件编号和人工确认条款", HttpStatus.BAD_REQUEST);
        }
        photos.add(new LinkedHashMap<>(photo));
      }
      return photos;
    } catch (JsonProcessingException ex) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_UNLINKED", "巡检图片证据无法读取，请重新选择并关联原图", HttpStatus.BAD_REQUEST);
    }
  }

  /**
   * New inspection persistence never accepts a display-only filename as evidence.  Every photo
   * entry must name a positive attachment ID and that ID must occur in at least one explicit
   * clause evidence collection.  Storage rebind validates the actual attachment, tenant/store
   * ownership and image MIME immediately before this check in a runtime-backed service.
   */
  private void requireNewInspectionEvidenceLinked(
      String photosJson,
      List<InspectionStandardSnapshot> snapshots
  ) {
    List<Map<String, Object>> photos = parseEvidencePhotos(photosJson);
    if (photos.isEmpty()) {
      return;
    }
    Set<Long> manuallyLinkedAttachmentIds = new HashSet<>();
    for (InspectionStandardSnapshot snapshot
        : snapshots == null ? List.<InspectionStandardSnapshot>of() : snapshots) {
      if (snapshot.photoAttachmentIds() != null) {
        snapshot.photoAttachmentIds().stream()
            .filter(Objects::nonNull)
            .filter(value -> value > 0)
            .forEach(manuallyLinkedAttachmentIds::add);
      }
    }
    for (Map<String, Object> photo : photos) {
      Long attachmentId = longValue(photo, "attachmentId", "attachment_id");
      if (attachmentId == null || attachmentId <= 0 || !manuallyLinkedAttachmentIds.contains(attachmentId)) {
        throw new BusinessException(
            "INSPECTION_EVIDENCE_UNLINKED",
            "每张巡检图片都必须绑定有效附件并至少关联一条人工确认的巡检条款",
            HttpStatus.BAD_REQUEST
        );
      }
    }
  }

  /**
   * Returns a record whose displayed score can safely be used in an exported report.
   *
   * <p>Older records are repaired only when their own immutable snapshot determines every score.
   * This method never fills a missing score line with a default and never mutates the original
   * inspection row; it appends the existing repair-audit record instead.</p>
   */
  @Transactional
  public InspectionRecordResponse prepareForExport(AuthUser user, String id) {
    InspectionRecordResponse record = record(user, id);
    if ("MANUAL_REVIEW".equals(record.repairStatus())) {
      throw scoreRepairRequired(List.of("历史评分修复结论"));
    }
    if (record.repaired()) {
      return record;
    }

    InspectionRecordRepository.ScoreEvidence evidence = recordRepository
        .scoreEvidence(user.tenantId(), record.id())
        .orElseThrow(() -> new BusinessException(
            "NOT_FOUND", "巡检记录不存在", HttpStatus.NOT_FOUND));
    List<String> missingFields = exportScoreEvidenceMissing(evidence);
    if (!missingFields.isEmpty()) {
      throw scoreRepairRequired(missingFields);
    }

    Long resolvedVersionId = resolvedSnapshotVersion(evidence, missingFields);
    if (!missingFields.isEmpty()) {
      throw scoreRepairRequired(missingFields);
    }
    if (standardRepository == null) {
      throw scoreRepairRequired(List.of("标准版本服务"));
    }
    InspectionStandardRepository.VersionRow version = standardRepository
        .version(user.tenantId(), resolvedVersionId)
        .orElseThrow(() -> scoreRepairRequired(List.of("标准版本")));
    List<InspectionStandardItemResponse> standards = standardRepository.items(user.tenantId(), version.id())
        .stream().filter(InspectionStandardItemResponse::enabled).toList();
    InspectionStandardValidation validation = InspectionStandardValidator.validate(version, standards);
    if (!validation.valid()) {
      throw scoreRepairRequired(List.of("标准版本校验：" + validation.validationError()));
    }

    ExportScoreRepair repair = calculateExportScoreRepair(
        standards, record.itemResults(), version, missingFields);
    if (!missingFields.isEmpty()) {
      throw scoreRepairRequired(missingFields);
    }
    if (hasCompleteStoredScore(evidence)) {
      validateStoredScoreAgainstSnapshot(evidence, version, repair, missingFields);
      if (!missingFields.isEmpty()) {
        throw scoreRepairRequired(missingFields);
      }
      return record;
    }
    InspectionResultRepairWrite write = new InspectionResultRepairWrite(
        evidence.standardVersionId(), evidence.standardVersion(), evidence.fullScore(), evidence.passScore(),
        evidence.score(), evidence.materialScore(), evidence.hygieneScore(), evidence.serviceScore(),
        evidence.resultCode(), evidence.passed(),
        version.id(), version.version(), version.fullScore(), version.passScore(), repair.score(),
        repair.materialScore(), repair.hygieneScore(), repair.serviceScore(), repair.resultCode(),
        repair.passed(), "RECALCULATED", repair.reason(), evidence.snapshotCount(), standards.size(), user.id()
    );
    if (!recordRepository.insertRepairAudit(user.tenantId(), record.id(), write)) {
      InspectionRecordResponse existing = record(user, id);
      if (existing.repaired()) {
        return existing;
      }
      throw scoreRepairRequired(List.of("历史评分修复结论"));
    }
    recordRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "inspection_score_recalculated_for_export",
        record.id(), record.storeId(), record.inspectionDate(), repair.reason());
    return record(user, id);
  }

  private List<String> exportScoreEvidenceMissing(InspectionRecordRepository.ScoreEvidence evidence) {
    List<String> missing = new ArrayList<>();
    if (evidence.snapshotCount() <= 0) {
      missing.add("标准快照");
    }
    if (evidence.snapshotCount() != evidence.snapshotStandardIdCount()) {
      missing.add("标准快照条款ID");
    }
    if (evidence.snapshotVersionCount() != 1) {
      missing.add("标准快照版本");
    }
    if (evidence.standardVersionId() != null
        && evidence.snapshotStandardVersionId() != null
        && !evidence.standardVersionId().equals(evidence.snapshotStandardVersionId())) {
      missing.add("标准版本与快照版本一致性");
    }
    return missing;
  }

  private Long resolvedSnapshotVersion(
      InspectionRecordRepository.ScoreEvidence evidence,
      List<String> missingFields
  ) {
    if (evidence.standardVersionId() != null) {
      return evidence.standardVersionId();
    }
    if (evidence.snapshotStandardVersionId() != null && evidence.snapshotVersionCount() == 1) {
      return evidence.snapshotStandardVersionId();
    }
    missingFields.add("标准版本");
    return null;
  }

  private boolean hasCompleteStoredScore(InspectionRecordRepository.ScoreEvidence evidence) {
    return evidence.fullScore() != null
        && evidence.passScore() != null
        && evidence.score() != null
        && evidence.standardVersionId() != null
        && evidence.fullScore().signum() > 0
        && evidence.passScore().signum() > 0
        && evidence.score().signum() >= 0
        && evidence.score().compareTo(evidence.fullScore()) <= 0;
  }

  /**
   * A formally stored score is exportable only when the immutable clause snapshot proves the
   * exact same result.  This is validation only: a discrepancy is reported for manual repair,
   * never replaced with a score calculated from the current standard.
   */
  private void validateStoredScoreAgainstSnapshot(
      InspectionRecordRepository.ScoreEvidence evidence,
      InspectionStandardRepository.VersionRow version,
      ExportScoreRepair snapshotScore,
      List<String> missingFields
  ) {
    if (!Objects.equals(blankToNull(evidence.standardVersion()), blankToNull(version.version()))) {
      missingFields.add("标准版本与版本编号一致性");
    }
    if (evidence.fullScore().compareTo(version.fullScore()) != 0) {
      missingFields.add("满分与标准版本一致性");
    }
    if (evidence.passScore().compareTo(version.passScore()) != 0) {
      missingFields.add("合格线与标准版本一致性");
    }
    if (evidence.score().compareTo(snapshotScore.score()) != 0) {
      missingFields.add("最终得分与标准快照一致性");
    }
    if (evidence.passed() == null || evidence.passed() != snapshotScore.passed()
        || !Objects.equals(blankToNull(evidence.resultCode()), blankToNull(snapshotScore.resultCode()))) {
      missingFields.add("巡检结论与红线快照一致性");
    }
  }

  private ExportScoreRepair calculateExportScoreRepair(
      List<InspectionStandardItemResponse> standards,
      List<InspectionItemResultResponse> snapshots,
      InspectionStandardRepository.VersionRow version,
      List<String> missingFields
  ) {
    if (snapshots.size() != standards.size()) {
      missingFields.add("标准快照条款数量");
      return ExportScoreRepair.empty();
    }
    Map<String, InspectionItemResultResponse> snapshotByCode = new HashMap<>();
    for (InspectionItemResultResponse snapshot : snapshots) {
      String code = blankToNull(snapshot.code());
      if (code == null || snapshotByCode.put(code, snapshot) != null) {
        missingFields.add("标准快照条款编号");
        return ExportScoreRepair.empty();
      }
    }
    BigDecimal score = ZERO_AMOUNT;
    BigDecimal material = ZERO_AMOUNT;
    BigDecimal hygiene = ZERO_AMOUNT;
    BigDecimal service = ZERO_AMOUNT;
    boolean redLineHit = false;
    for (InspectionStandardItemResponse standard : standards) {
      InspectionItemResultResponse snapshot = snapshotByCode.remove(standard.code());
      if (snapshot == null) {
        missingFields.add("标准快照条款：" + standard.code());
        return ExportScoreRepair.empty();
      }
      BigDecimal maximum = standard.suggestedScore();
      BigDecimal snapshotMaximum = snapshot.standardScore();
      BigDecimal deduction = snapshot.deductionScore();
      BigDecimal snapshotActual = snapshot.actualScore();
      if (maximum == null || maximum.signum() < 0) {
        missingFields.add("标准条款分值：" + standard.code());
        return ExportScoreRepair.empty();
      }
      if (snapshotMaximum == null
          || snapshotMaximum.signum() < 0
          || snapshotMaximum.compareTo(maximum) != 0) {
        missingFields.add("标准快照分值一致性：" + standard.code());
        return ExportScoreRepair.empty();
      }
      if (deduction == null
          || deduction.signum() < 0
          || deduction.compareTo(maximum) > 0) {
        missingFields.add("条款扣分：" + standard.code());
        return ExportScoreRepair.empty();
      }
      if (deduction.signum() > 0 && !snapshot.issueFound()) {
        missingFields.add("扣分问题状态：" + standard.code());
        return ExportScoreRepair.empty();
      }
      if (deduction.signum() > 0 && blankToNull(snapshot.deductionReason()) == null) {
        missingFields.add("扣分原因：" + standard.code());
        return ExportScoreRepair.empty();
      }
      BigDecimal actual = maximum.subtract(deduction).setScale(2, RoundingMode.HALF_UP);
      if (snapshotActual == null || snapshotActual.compareTo(actual) != 0) {
        missingFields.add("标准快照分值一致性：" + standard.code());
        return ExportScoreRepair.empty();
      }
      String bucket = category(standard.dimension());
      if (bucket == null) {
        missingFields.add("条款分类：" + standard.code());
        return ExportScoreRepair.empty();
      }
      score = score.add(actual);
      switch (bucket) {
        case "MATERIAL" -> material = material.add(actual);
        case "HYGIENE" -> hygiene = hygiene.add(actual);
        case "SERVICE" -> service = service.add(actual);
        default -> throw new IllegalStateException("Unexpected inspection category: " + bucket);
      }
      redLineHit = redLineHit || (snapshot.issueFound()
          && "RED".equals(normalizeRiskLevel(standard.riskLevel(), standard.redLine())));
    }
    if (!snapshotByCode.isEmpty() || score.compareTo(version.fullScore()) > 0) {
      missingFields.add("标准快照与标准版本一致性");
      return ExportScoreRepair.empty();
    }
    String resultCode = redLineHit ? "RED_LINE_FAILED"
        : score.compareTo(version.passScore()) >= 0 ? "PASSED" : "FAILED";
    return new ExportScoreRepair(
        score.setScale(2, RoundingMode.HALF_UP),
        material.setScale(2, RoundingMode.HALF_UP),
        hygiene.setScale(2, RoundingMode.HALF_UP),
        service.setScale(2, RoundingMode.HALF_UP),
        "PASSED".equals(resultCode),
        resultCode,
        "依据记录绑定的标准版本" + version.version() + "及完整快照重新计算；未修改原始巡检记录"
    );
  }

  private InspectionScoreRepairRequiredException scoreRepairRequired(List<String> missingFields) {
    return new InspectionScoreRepairRequiredException(missingFields);
  }

  @Transactional
  public InspectionHistoryRepairResponse repairHistory(AuthUser user) {
    requireInspectionManage(user);
    if (!AccessControlService.isBoss(user)
        && !AccessControlService.hasAnyRole(user, "SUPERVISOR", "OPERATIONS")) {
      throw new BusinessException(
          "FORBIDDEN",
          "只有老板或负责巡检的督导可以执行历史巡检修复",
          HttpStatus.FORBIDDEN
      );
    }
    if (standardRepository == null) {
      throw new BusinessException(
          "INSPECTION_STANDARD_MISSING", "巡检标准服务不可用", HttpStatus.CONFLICT);
    }
    InspectionStandardRepository.VersionRow active = standardRepository.activeVersion(user.tenantId())
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_STANDARD_MISSING", "当前没有启用的巡检标准", HttpStatus.CONFLICT));
    List<InspectionStandardItemResponse> standards = standardRepository.items(user.tenantId(), active.id());
    InspectionStandardValidation validation = InspectionStandardValidator.validate(active, standards);
    if (!validation.valid()) {
      throw new BusinessException(
          "INSPECTION_STANDARD_INVALID",
          "修复历史记录前必须先启用校验通过的巡检标准：" + validation.validationError(),
          HttpStatus.CONFLICT
      );
    }
    if (INCORRECT_STANDARD_VERSION.equals(active.version())) {
      throw new BusinessException(
          "INSPECTION_REPAIR_STANDARD_NOT_READY",
          "修正版巡检标准尚未启用，禁止使用错误版本重算历史记录",
          HttpStatus.CONFLICT
      );
    }

    int scanned = 0;
    int recalculated = 0;
    int manualReview = 0;
    int skipped = 0;
    List<String> manualReviewRecordIds = new ArrayList<>();
    List<InspectionRecordResponse> candidates = records(user, null, null, null, null, null);
    for (InspectionRecordResponse record : candidates) {
      if (!INCORRECT_STANDARD_VERSION.equals(record.standardVersion())
          || record.fullScore() == null
          || record.fullScore().compareTo(new BigDecimal("200.00")) != 0) {
        continue;
      }
      scanned++;
      List<InspectionItemResultResponse> snapshots = recordRepository.snapshotItems(user.tenantId(), record.id());
      HistoricalRepairCalculation calculation = calculateHistoricalRepair(standards, snapshots, active);
      InspectionResultRepairWrite repair = repairWrite(user, record, active, snapshots.size(), calculation);
      if (!recordRepository.insertRepairAudit(user.tenantId(), record.id(), repair)) {
        skipped++;
        continue;
      }
      if (calculation.manualReview()) {
        manualReview++;
        manualReviewRecordIds.add(record.id());
      } else {
        recalculated++;
      }
      recordRepository.logAction(
          user.tenantId(),
          user.id(),
          user.displayName(),
          calculation.manualReview() ? "inspection_history_manual_review" : "inspection_history_recalculated",
          record.id(),
          record.storeId(),
          record.inspectionDate(),
          calculation.reason()
      );
    }
    return new InspectionHistoryRepairResponse(
        scanned, recalculated, manualReview, skipped, manualReviewRecordIds);
  }

  @Transactional
  public InspectionRecordResponse save(AuthUser user, String id, InspectionRecordRequest request) {
    requireInspectionManage(user);
    boolean creating = id == null || id.isBlank();
    if (id != null && !id.isBlank()) {
      recordRepository.record(user.tenantId(), id).ifPresent(existing -> {
        requireInspectionStoreAccess(user, existing.storeId(), "修改巡检记录");
        requireUnrepairedRecord(user.tenantId(), existing.id(), "修改");
      });
    }
    CalculatedInspection calculated = calculateInspection(user, id, request);
    InspectionRecordRequest normalized = calculated.request();
    requireInspectionStoreAccess(user, normalized.storeId(), "保存巡检记录");
    if (!recordRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "Store does not exist in current tenant", HttpStatus.BAD_REQUEST);
    }
    // Reject display-only and unassociated photo JSON before any inspection row/snapshot write.
    requireNewInspectionEvidenceLinked(normalized.photosJson(), calculated.snapshots());
    if (!calculated.attachmentIds().isEmpty() && storageService == null) {
      throw new BusinessException(
          "INSPECTION_EVIDENCE_UNLINKED",
          "巡检附件服务不可用，不能确认图片已入库并关联条款",
          HttpStatus.CONFLICT
      );
    }
    String targetId = normalizeId(id);
    recordRepository.upsert(user.tenantId(), targetId, normalized);
    recordRepository.replaceStandardSnapshots(user.tenantId(), targetId, calculated.snapshots());
    if (!calculated.attachmentIds().isEmpty()) {
      storageService.rebindInspectionAttachments(
          user, normalized.storeId(), targetId, calculated.attachmentIds());
    }
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
    if (creating) {
      for (DetectionAudit audit : calculated.detectionAudits()) {
        recordRepository.logAction(
            user.tenantId(), user.id(), user.displayName(), "inspection_detection_confirm",
            targetId, normalized.storeId(), normalized.inspectionDate(),
            "新建巡检时确认图片识别结果：" + audit.detectionKey()
                + "；条款" + audit.clauseId() + "；" + audit.deductionExplanation()
        );
      }
    }
    return requireRecord(user.tenantId(), targetId);
  }

  @Transactional
  public InspectionRecordResponse bindDetectionResults(
      AuthUser user,
      String id,
      InspectionDetectionBindingRequest request
  ) {
    requireInspectionManage(user);
    InspectionRecordResponse existing = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, existing.storeId(), "绑定巡检识别结果");
    requireUnrepairedRecord(user.tenantId(), existing.id(), "绑定识别结果");
    List<Map<String, Object>> results = detectionSuggestions(user, request);

    List<Map<String, Object>> photos = new ArrayList<>();
    Map<String, Map<String, Object>> terminalExistingDecisions = new HashMap<>();
    for (Map<String, Object> existingPhoto : parseDetectionPhotos(existing.photosJson())) {
      Map<String, Object> existingDetection = detectionNode(existingPhoto);
      String existingKey = textValue(existingDetection, "detectionKey", "detection_key");
      String existingStatus = decisionStatus(existingDetection);
      if (existingKey != null
          && (DETECTION_CONFIRMED.equals(existingStatus) || DETECTION_REVOKED.equals(existingStatus))) {
        terminalExistingDecisions.put(existingKey, new LinkedHashMap<>(existingPhoto));
      }
    }

    for (Map<String, Object> result : results) {
      String imageId = textValue(result, "image_id", "imageId");
      String filename = textValue(result, "filename");
      String detectionKey = textValue(result, "detectionKey", "detection_key");
      Integer detectionCount = intValue(result, "detection_count", "detectionCount");
      Object detections = value(result, "detections");
      String autoStatus = textValue(result, "auto_status", "autoStatus", "review_status", "reviewStatus");
      String summary = textValue(result, "detection_summary", "detectionSummary");
      BigDecimal legacyDeduction = decimalValue(result, "legacyDeduction", "legacy_deduction");
      BigDecimal convertedDeduction = decimalValue(
          result, "convertedDeduction200", "converted_deduction_200");
      BigDecimal finalDeduction = decimalValue(result, "finalDeduction", "final_deduction");
      BigDecimal standardDeduction = decimalValue(result, "standardDeduction", "standard_deduction");
      BigDecimal confirmedDeduction = decimalValue(result, "confirmedDeduction", "confirmed_deduction");
      BigDecimal clauseDeduction = decimalValue(result, "clauseDeduction", "clause_deduction");
      BigDecimal scaleAdjustmentDeduction = decimalValue(
          result, "scaleAdjustmentDeduction", "scale_adjustment_deduction");
      String deductionPolicyVersion = textValue(
          result, "deductionPolicyVersion", "deduction_policy_version");

      Map<String, Object> terminalExisting = terminalExistingDecisions.get(detectionKey);
      if (terminalExisting != null) {
        photos.add(terminalExisting);
        continue;
      }

      Map<String, Object> photo = new LinkedHashMap<>();
      putIfPresent(photo, "detectionKey", detectionKey);
      putIfPresent(photo, "image_id", imageId);
      putIfPresent(photo, "filename", filename);
      putIfPresent(photo, "attachmentId", longValue(result, "attachmentId", "attachment_id"));
      photo.put("decisionStatus", textValue(result, "decisionStatus", "decision_status"));
      photo.put("review_status", DETECTION_PENDING);
      photo.put("revision", 0L);
      putIfPresent(photo, "auto_status", autoStatus);
      if (detectionCount != null) {
        photo.put("detection_count", detectionCount);
      }
      putIfPresent(photo, "detection_summary", summary);
      putIfPresent(photo, "clauseId", longValue(result, "clauseId", "clause_id"));
      putIfPresent(photo, "clauseCode", textValue(result, "clauseCode", "clause_code"));
      putIfPresent(photo, "clauseTitle", textValue(result, "clauseTitle", "clause_title"));
      putIfPresent(photo, "issueCode", textValue(result, "issueCode", "issue_code"));
      putIfPresent(photo, "issueName", textValue(result, "issueName", "issue_name"));
      putIfPresent(photo, "legacyDeduction", legacyDeduction);
      putIfPresent(photo, "scoreScale", decimalValue(result, "scoreScale", "score_scale"));
      putIfPresent(photo, "persistedScoreScale", decimalValue(
          result, "persistedScoreScale", "persisted_score_scale"));
      putIfPresent(photo, "convertedDeduction200", convertedDeduction);
      putIfPresent(photo, "standardDeduction", standardDeduction);
      putIfPresent(photo, "clauseDeduction", clauseDeduction);
      putIfPresent(photo, "scaleAdjustmentDeduction", scaleAdjustmentDeduction);
      putIfPresent(photo, "deductionPolicyVersion", deductionPolicyVersion);
      putIfPresent(photo, "finalDeduction", finalDeduction);
      putIfPresent(photo, "confirmedDeduction", confirmedDeduction);
      putIfPresent(photo, "confidence", decimalValue(result, "confidence"));
      putIfPresent(photo, "suggested_project", textValue(result, "deduction_project", "deductionProject"));
      putIfPresent(photo, "suggested_issue", textValue(result, "deduction_content", "deductionContent"));
      if (detections instanceof Collection<?> collection) {
        photo.put("detections", collection);
      }
      // annotated_image/original_image are deliberately not persisted: model output may be a large Base64 payload.
      photos.add(photo);
    }

    InspectionRecordRequest normalized = new InspectionRecordRequest(
        existing.storeId(),
        existing.inspectionDate(),
        firstNonBlank(request == null ? null : request.inspector(), existing.inspector()),
        firstNonBlank(request == null ? null : request.brand(), existing.brand()),
        existing.fullScore(),
        existing.score(),
        existing.passed(),
        existing.deductionsJson(),
        existing.redlinesJson(),
        toJson(photos),
        firstNonBlank(request == null ? null : request.note(), existing.note()),
        existing.standardVersionId(),
        existing.standardVersion(),
        existing.materialScore(),
        existing.hygieneScore(),
        existing.serviceScore(),
        existing.resultCode(),
        List.of()
    );
    recordRepository.upsert(user.tenantId(), existing.id(), normalized);
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
  public InspectionDetectionDecisionResponse confirmDetection(
      AuthUser user,
      String id,
      String detectionKey,
      InspectionDetectionDecisionRequest request
  ) {
    requireInspectionManage(user);
    recordRepository.lockRecord(user.tenantId(), requireText(id, "ID_REQUIRED", "巡检记录编号不能为空"));
    InspectionRecordResponse existing = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, existing.storeId(), "确认巡检识别结果");
    requireUnrepairedRecord(user.tenantId(), existing.id(), "确认识别结果");
    requireCanonicalDetectionRecord(existing);
    DetectionDocument document = requireDetection(existing.photosJson(), detectionKey);
    if (DETECTION_CONFIRMED.equals(decisionStatus(document.node()))) {
      return new InspectionDetectionDecisionResponse(existing, responseDetection(document.node()), false);
    }
    verifyDetectionRevision(document.node(), request == null ? null : request.expectedRevision());
    Map<String, Object> resolved = resolveSavedDetection(user, existing, document.node());
    replaceAuthoritativeDetectionFields(document.node(), resolved);
    Long clauseId = longValue(resolved, "clauseId", "clause_id");
    if (clauseId == null) {
      throw new BusinessException(
          "INSPECTION_DETECTION_UNMATCHED",
          "该识别结果未匹配到正式巡检条款，请先人工调整条款并填写原因",
          HttpStatus.CONFLICT
      );
    }
    List<InspectionItemResultResponse> snapshots = mutableSnapshots(existing);
    applyConfirmedDetection(document, snapshots, clauseId);
    InspectionRecordResponse updated = persistDetectionDecision(
        user, existing, document.photos(), snapshots,
        "inspection_detection_confirm",
        "确认图片识别结果；条款" + clauseId + "；"
            + detectionPolicyExplanation(document.node())
    );
    return new InspectionDetectionDecisionResponse(updated, responseDetection(document.node()), true);
  }

  @Transactional
  public InspectionDetectionDecisionResponse revokeDetection(
      AuthUser user,
      String id,
      String detectionKey,
      InspectionDetectionDecisionRequest request
  ) {
    requireInspectionManage(user);
    recordRepository.lockRecord(user.tenantId(), requireText(id, "ID_REQUIRED", "巡检记录编号不能为空"));
    InspectionRecordResponse existing = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, existing.storeId(), "撤销巡检识别结果");
    requireUnrepairedRecord(user.tenantId(), existing.id(), "撤销识别结果");
    requireCanonicalDetectionRecord(existing);
    DetectionDocument document = requireDetection(existing.photosJson(), detectionKey);
    String previousStatus = decisionStatus(document.node());
    if (DETECTION_REVOKED.equals(previousStatus)) {
      return new InspectionDetectionDecisionResponse(existing, responseDetection(document.node()), false);
    }
    verifyDetectionRevision(document.node(), request == null ? null : request.expectedRevision());
    boolean wasConfirmed = DETECTION_CONFIRMED.equals(previousStatus);
    Long clauseId = longValue(document.node(), "clauseId", "clause_id");
    List<InspectionItemResultResponse> snapshots = mutableSnapshots(existing);
    document.node().put("decisionStatus", DETECTION_REVOKED);
    document.node().put("review_status", DETECTION_REVOKED);
    document.node().remove("confirmedDeduction");
    incrementDetectionRevision(document.node());
    if (wasConfirmed && clauseId != null
        && !hasOtherConfirmedDecision(document.photos(), document.node(), clauseId)) {
      restoreDetectionBaseline(document.photos(), snapshots, clauseId);
    }
    InspectionRecordResponse updated = persistDetectionDecision(
        user, existing, document.photos(), snapshots,
        "inspection_detection_revoke",
        wasConfirmed
            ? "撤销图片识别确认并恢复条款" + clauseId + "原状态"
            : "撤销尚未确认的图片识别建议，不改变巡检分数"
    );
    return new InspectionDetectionDecisionResponse(updated, responseDetection(document.node()), true);
  }

  @Transactional
  public InspectionDetectionDecisionResponse adjustDetection(
      AuthUser user,
      String id,
      String detectionKey,
      InspectionDetectionAdjustmentRequest request
  ) {
    requireInspectionManage(user);
    if (request == null || request.targetClauseId() == null) {
      throw new BusinessException(
          "INSPECTION_DETECTION_CLAUSE_REQUIRED", "人工调整必须选择正式巡检条款", HttpStatus.BAD_REQUEST);
    }
    String reason = requireText(
        request.reason(), "INSPECTION_DETECTION_ADJUST_REASON_REQUIRED", "人工调整识别条款必须填写原因");
    recordRepository.lockRecord(user.tenantId(), requireText(id, "ID_REQUIRED", "巡检记录编号不能为空"));
    InspectionRecordResponse existing = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, existing.storeId(), "调整巡检识别条款");
    requireUnrepairedRecord(user.tenantId(), existing.id(), "调整识别条款");
    requireCanonicalDetectionRecord(existing);
    DetectionDocument document = requireDetection(existing.photosJson(), detectionKey);
    List<InspectionItemResultResponse> snapshots = mutableSnapshots(existing);
    requireSnapshot(snapshots, request.targetClauseId());

    Long previousClauseId = longValue(document.node(), "clauseId", "clause_id");
    boolean wasConfirmed = DETECTION_CONFIRMED.equals(decisionStatus(document.node()));
    if (wasConfirmed && Objects.equals(previousClauseId, request.targetClauseId())) {
      return new InspectionDetectionDecisionResponse(existing, responseDetection(document.node()), false);
    }
    BigDecimal originalDeduction = authoritativeDetectionDeduction(document.node());
    if (wasConfirmed && previousClauseId != null) {
      document.node().put("decisionStatus", DETECTION_REVOKED);
      if (!hasOtherConfirmedDecision(document.photos(), document.node(), previousClauseId)) {
        restoreDetectionBaseline(document.photos(), snapshots, previousClauseId);
      }
    }

    InspectionItemResultResponse target = requireSnapshot(snapshots, request.targetClauseId());
    BigDecimal adjustedDeduction = serverDetectionDeduction(target);
    document.node().put("clauseId", target.standardItemId());
    putIfPresent(document.node(), "clauseCode", target.code());
    putIfPresent(document.node(), "clauseTitle", target.title());
    document.node().put("standardDeduction", adjustedDeduction);
    BigDecimal clauseDeduction = amountOrDefault(target.standardScore(), ZERO_AMOUNT).abs()
        .setScale(2, RoundingMode.HALF_UP);
    document.node().put("clauseDeduction", clauseDeduction);
    document.node().put(
        "scaleAdjustmentDeduction",
        adjustedDeduction.subtract(clauseDeduction).max(ZERO_AMOUNT)
            .setScale(2, RoundingMode.HALF_UP));
    document.node().put(
        "deductionPolicyVersion",
        "H-4.1.2".equalsIgnoreCase(target.code())
            ? H_4_1_2_DEDUCTION_POLICY : ACTIVE_CLAUSE_DEDUCTION_POLICY);
    document.node().put("suggestedDeduction", adjustedDeduction);
    document.node().put("finalDeduction", adjustedDeduction);
    document.node().put("manualAdjustment", true);
    document.node().put("manualAdjustmentOriginalDeduction", originalDeduction);
    document.node().put("manualAdjustmentAdjustedDeduction", adjustedDeduction);
    document.node().put("manualAdjustmentReason", reason);
    document.node().put("decisionStatus", DETECTION_PENDING);
    applyConfirmedDetection(document, snapshots, request.targetClauseId());

    InspectionRecordResponse updated = persistDetectionDecision(
        user, existing, document.photos(), snapshots,
        "inspection_detection_manual_adjust",
        "人工调整识别条款：" + previousClauseId + " -> " + request.targetClauseId()
            + "；原扣分" + scoreText(originalDeduction)
            + "→调整后扣分" + scoreText(adjustedDeduction)
            + "；原因：" + reason + "；" + detectionPolicyExplanation(document.node())
    );
    return new InspectionDetectionDecisionResponse(updated, responseDetection(document.node()), true);
  }

  private void requireCanonicalDetectionRecord(InspectionRecordResponse record) {
    if (record.fullScore() == null
        || record.fullScore().compareTo(InspectionScoringRules.MAX_SCORE) != 0) {
      throw new BusinessException(
          "INSPECTION_SCORE_SCALE_INVALID",
          "识别确认只允许写入200分制巡检；旧100分制必须先由V41迁移且只能折算一次",
          HttpStatus.CONFLICT
      );
    }
    if (record.itemResults() == null || record.itemResults().isEmpty()) {
      throw new BusinessException(
          "INSPECTION_SNAPSHOT_REQUIRED", "巡检条款快照不完整，禁止确认识别扣分", HttpStatus.CONFLICT);
    }
  }

  private List<InspectionItemResultResponse> mutableSnapshots(InspectionRecordResponse record) {
    return new ArrayList<>(record.itemResults());
  }

  private void applyConfirmedDetection(
      DetectionDocument document,
      List<InspectionItemResultResponse> snapshots,
      long clauseId
  ) {
    int index = snapshotIndex(snapshots, clauseId);
    InspectionItemResultResponse current = snapshots.get(index);
    if (!hasAnyConfirmedDecision(document.photos(), clauseId)) {
      captureDetectionBaseline(document.node(), current);
    }
    BigDecimal confirmedDeduction = authoritativeDetectionDeduction(document.node());
    BigDecimal existingDeduction = amountOrDefault(current.deductionScore(), ZERO_AMOUNT);
    BigDecimal effectiveDeduction = existingDeduction.max(confirmedDeduction)
        .setScale(2, RoundingMode.HALF_UP);
    BigDecimal actualScore = amountOrDefault(current.standardScore(), ZERO_AMOUNT)
        .subtract(effectiveDeduction).max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
    String issue = firstNonBlank(
        textValue(document.node(), "issueName", "suggested_issue", "detection_summary"),
        "图片识别到疑似现场问题，已由督导确认"
    );
    List<Long> photos = new ArrayList<>(current.photoAttachmentIds());
    Long attachmentId = longValue(document.node(), "attachmentId", "attachment_id");
    if (attachmentId != null && attachmentId > 0 && !photos.contains(attachmentId)) {
      photos.add(attachmentId);
    }
    snapshots.set(index, copySnapshot(
        current,
        actualScore,
        effectiveDeduction,
        mergeReason(
            mergeReason(current.deductionReason(), issue),
            detectionPolicyExplanation(document.node())),
        List.copyOf(photos),
        current.rectificationStatus() == null || "NOT_REQUIRED".equals(current.rectificationStatus())
            ? "PENDING" : current.rectificationStatus()
    ));
    document.node().put("decisionStatus", DETECTION_CONFIRMED);
    document.node().put("review_status", DETECTION_CONFIRMED);
    document.node().put("confirmedDeduction", confirmedDeduction);
    document.node().put("finalDeduction", confirmedDeduction);
    incrementDetectionRevision(document.node());
  }

  private BigDecimal authoritativeDetectionDeduction(Map<String, Object> detection) {
    BigDecimal resolved = decimalValue(detection, "standardDeduction", "standard_deduction");
    if (resolved == null) {
      throw new BusinessException(
          "INSPECTION_DETECTION_RULE_MISSING",
          "识别结果缺少服务端条款扣分规则，已停止自动扣分",
          HttpStatus.CONFLICT);
    }
    return resolved.abs().setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal serverDetectionDeduction(InspectionItemResultResponse clause) {
    if (clause == null) {
      return ZERO_AMOUNT;
    }
    if ("H-4.1.2".equalsIgnoreCase(clause.code())) {
      return H_4_1_2_SERVER_DEDUCTION.setScale(2, RoundingMode.HALF_UP);
    }
    return amountOrDefault(clause.standardScore(), ZERO_AMOUNT).abs()
        .setScale(2, RoundingMode.HALF_UP);
  }

  private InspectionRecordResponse persistDetectionDecision(
      AuthUser user,
      InspectionRecordResponse existing,
      List<Map<String, Object>> photos,
      List<InspectionItemResultResponse> snapshots,
      String action,
      String auditReason
  ) {
    SnapshotTotals totals = snapshotTotals(snapshots);
    InspectionRecordRequest request = new InspectionRecordRequest(
        existing.storeId(), existing.inspectionDate(), existing.inspector(), existing.brand(),
        InspectionScoringRules.MAX_SCORE, totals.score(), totals.passed(),
        toJson(totals.deductions()), toJson(totals.redlines()), toJson(photos), existing.note(),
        existing.standardVersionId(), existing.standardVersion(), totals.materialScore(),
        totals.hygieneScore(), totals.serviceScore(), totals.resultCode(), List.of()
    );
    recordRepository.upsert(user.tenantId(), existing.id(), request);
    recordRepository.replaceStandardSnapshots(
        user.tenantId(), existing.id(), snapshots.stream()
            .map(item -> toSnapshot(item, existing.standardVersion())).toList());
    if (storageService != null) {
      Set<Long> attachmentIds = photos.stream()
          .map(photo -> longValue(detectionNode(photo), "attachmentId", "attachment_id"))
          .filter(Objects::nonNull)
          .filter(value -> value > 0)
          .collect(java.util.stream.Collectors.toSet());
      if (!attachmentIds.isEmpty()) {
        storageService.rebindInspectionAttachments(user, existing.storeId(), existing.id(), attachmentIds);
      }
    }
    recordRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), action, existing.id(),
        existing.storeId(), existing.inspectionDate(), auditReason);
    return requireRecord(user.tenantId(), existing.id());
  }

  private SnapshotTotals snapshotTotals(List<InspectionItemResultResponse> snapshots) {
    BigDecimal material = ZERO_AMOUNT;
    BigDecimal hygiene = ZERO_AMOUNT;
    BigDecimal service = ZERO_AMOUNT;
    boolean redLineHit = false;
    List<Map<String, Object>> deductions = new ArrayList<>();
    List<Map<String, Object>> redlines = new ArrayList<>();
    for (InspectionItemResultResponse item : snapshots) {
      BigDecimal standard = amountOrDefault(item.standardScore(), ZERO_AMOUNT);
      BigDecimal persistedActual = amountOrDefault(item.actualScore(), ZERO_AMOUNT);
      BigDecimal deduction = amountOrDefault(item.deductionScore(), standard.subtract(persistedActual))
          .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
      BigDecimal clampedItemActual = standard.subtract(deduction).max(ZERO_AMOUNT)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal scaleAdjustment = deduction.subtract(standard).max(ZERO_AMOUNT)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal scoreContribution = clampedItemActual.subtract(scaleAdjustment)
          .setScale(2, RoundingMode.HALF_UP);
      switch (category(item.dimension())) {
        case "MATERIAL" -> material = material.add(scoreContribution);
        case "HYGIENE" -> hygiene = hygiene.add(scoreContribution);
        case "SERVICE" -> service = service.add(scoreContribution);
        default -> throw new BusinessException(
            "INSPECTION_STANDARD_CATEGORY_INVALID",
            "无法识别检查分类：" + item.dimension(), HttpStatus.CONFLICT);
      }
      boolean issueFound = deduction.signum() > 0
          || item.deductionReason() != null && !item.deductionReason().isBlank();
      boolean itemRedLine = (item.redLine() || "RED".equalsIgnoreCase(item.riskLevel())) && issueFound;
      redLineHit |= itemRedLine;
      if (issueFound) {
        Map<String, Object> detail = snapshotScoringJson(item, clampedItemActual, deduction);
        deductions.add(detail);
        if (itemRedLine) {
          redlines.add(detail);
        }
      }
    }
    material = material.max(ZERO_AMOUNT);
    hygiene = hygiene.max(ZERO_AMOUNT);
    service = service.max(ZERO_AMOUNT);
    BigDecimal score = material.add(hygiene).add(service).setScale(2, RoundingMode.HALF_UP);
    String resultCode = InspectionScoringRules.resultCode(score, redLineHit);
    return new SnapshotTotals(
        score, material.setScale(2), hygiene.setScale(2), service.setScale(2),
        resultCode, "PASSED".equals(resultCode), deductions, redlines);
  }

  private Map<String, Object> snapshotScoringJson(
      InspectionItemResultResponse item,
      BigDecimal actual,
      BigDecimal deduction
  ) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("standard_id", item.standardItemId());
    detail.put("dimension", item.dimension());
    detail.put("code", item.code());
    detail.put("item", item.title());
    detail.put("method", item.checkMethod());
    detail.put("score", item.standardScore());
    detail.put("actual_score", actual);
    detail.put("deduct", deduction);
    detail.put("issue", item.deductionReason());
    detail.put("risk_level", item.riskLevel());
    detail.put("redline", item.redLine());
    detail.put("photo_attachment_ids", item.photoAttachmentIds());
    return detail;
  }

  private InspectionStandardSnapshot toSnapshot(
      InspectionItemResultResponse item,
      String standardVersion
  ) {
    BigDecimal standard = amountOrDefault(item.standardScore(), ZERO_AMOUNT);
    BigDecimal actual = amountOrDefault(item.actualScore(), ZERO_AMOUNT);
    BigDecimal deduction = amountOrDefault(item.deductionScore(), standard.subtract(actual))
        .max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
    return new InspectionStandardSnapshot(
        item.standardItemId(), standardVersion, item.dimension(), item.title(), item.description(), standard,
        deduction, item.redLine(),
        item.deductionReason(), item.sortOrder(), item.code(), item.checkMethod(), actual,
        item.riskLevel(), item.photoAttachmentIds(), item.responsiblePerson(),
        item.rectificationDeadline(), item.rectificationStatus(), item.reviewResult(),
        item.beforePhotoAttachmentIds(), item.afterPhotoAttachmentIds()
    );
  }

  private InspectionItemResultResponse copySnapshot(
      InspectionItemResultResponse source,
      BigDecimal actualScore,
      BigDecimal deductionScore,
      String reason,
      List<Long> photos,
      String rectificationStatus
  ) {
    BigDecimal standard = amountOrDefault(source.standardScore(), ZERO_AMOUNT);
    BigDecimal actual = amountOrDefault(actualScore, ZERO_AMOUNT);
    BigDecimal deduction = amountOrDefault(deductionScore, standard.subtract(actual))
        .max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
    return new InspectionItemResultResponse(
        source.snapshotId(), source.standardItemId(), source.dimension(), source.code(), source.title(),
        source.description(), source.checkMethod(), standard, actual,
        deduction,
        deduction.signum() > 0 || reason != null && !reason.isBlank(),
        source.riskLevel(), source.redLine(), reason, photos, source.responsiblePerson(),
        source.rectificationDeadline(), rectificationStatus, source.reviewResult(),
        source.beforePhotoAttachmentIds(), source.afterPhotoAttachmentIds(), source.sortOrder()
    );
  }

  private DetectionDocument requireDetection(String photosJson, String detectionKey) {
    String key = requireText(
        detectionKey, "INSPECTION_DETECTION_KEY_REQUIRED", "识别结果编号不能为空");
    List<Map<String, Object>> photos = parseDetectionPhotos(photosJson);
    for (Map<String, Object> photo : photos) {
      Map<String, Object> node = detectionNode(photo);
      if (key.equals(textValue(node, "detectionKey", "detection_key"))) {
        return new DetectionDocument(photos, photo, node);
      }
    }
    throw new BusinessException(
        "INSPECTION_DETECTION_NOT_FOUND", "没有找到对应的图片识别结果", HttpStatus.NOT_FOUND);
  }

  private List<Map<String, Object>> parseDetectionPhotos(String photosJson) {
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> detectionNode(Map<String, Object> photo) {
    Object nested = photo.get("detection");
    if (nested instanceof Map<?, ?> raw) {
      Map<String, Object> node;
      if (raw.keySet().stream().allMatch(String.class::isInstance)) {
        // Keep the exact nested instance. Copying it on every lookup detaches
        // the decision node from photosJson and makes confirm/revoke appear to
        // succeed without persisting the terminal status.
        node = (Map<String, Object>) raw;
      } else {
        node = new LinkedHashMap<>();
        raw.forEach((key, value) -> node.put(String.valueOf(key), value));
        photo.put("detection", node);
      }
      putIfAbsent(node, "attachmentId", longValue(photo, "attachmentId", "attachment_id"));
      putIfAbsent(node, "decisionStatus", textValue(photo, "decisionStatus", "reviewStatus", "review_status"));
      return node;
    }
    return photo;
  }

  private void putIfAbsent(Map<String, Object> target, String key, Object value) {
    if (!target.containsKey(key) && value != null) {
      target.put(key, value);
    }
  }

  private String decisionStatus(Map<String, Object> detection) {
    String status = textValue(detection, "decisionStatus", "decision_status", "review_status", "reviewStatus");
    return status == null ? DETECTION_PENDING : status.toUpperCase(java.util.Locale.ROOT);
  }

  private void verifyDetectionRevision(Map<String, Object> detection, Long expectedRevision) {
    long current = Optional.ofNullable(longValue(detection, "revision")).orElse(0L);
    if (expectedRevision != null && expectedRevision != current) {
      throw new BusinessException(
          "INSPECTION_DETECTION_CONFLICT",
          "识别结果已被其他人处理，请刷新后重试",
          HttpStatus.CONFLICT
      );
    }
  }

  private void incrementDetectionRevision(Map<String, Object> detection) {
    detection.put("revision", Optional.ofNullable(longValue(detection, "revision")).orElse(0L) + 1L);
  }

  private void captureDetectionBaseline(
      Map<String, Object> detection,
      InspectionItemResultResponse item
  ) {
    if (Boolean.TRUE.equals(booleanValue(detection, "baselineCaptured"))) {
      return;
    }
    detection.put("baselineCaptured", true);
    detection.put("baselineActualScore", amountOrDefault(item.actualScore(), ZERO_AMOUNT));
    detection.put("baselineDeductionScore", amountOrDefault(item.deductionScore(), ZERO_AMOUNT));
    putIfPresent(detection, "baselineDeductionReason", blankToNull(item.deductionReason()));
    detection.put("baselinePhotoAttachmentIds", item.photoAttachmentIds());
    putIfPresent(detection, "baselineRectificationStatus", blankToNull(item.rectificationStatus()));
  }

  private boolean hasAnyConfirmedDecision(List<Map<String, Object>> photos, long clauseId) {
    return allDetectionNodes(photos).stream().anyMatch(node ->
        DETECTION_CONFIRMED.equals(decisionStatus(node))
            && Objects.equals(longValue(node, "clauseId", "clause_id"), clauseId));
  }

  private boolean hasOtherConfirmedDecision(
      List<Map<String, Object>> photos,
      Map<String, Object> excluded,
      long clauseId
  ) {
    return allDetectionNodes(photos).stream().anyMatch(node -> node != excluded
        && DETECTION_CONFIRMED.equals(decisionStatus(node))
        && Objects.equals(longValue(node, "clauseId", "clause_id"), clauseId));
  }

  private List<Map<String, Object>> allDetectionNodes(List<Map<String, Object>> photos) {
    return photos.stream().map(this::detectionNode).toList();
  }

  private void restoreDetectionBaseline(
      List<Map<String, Object>> photos,
      List<InspectionItemResultResponse> snapshots,
      long clauseId
  ) {
    Map<String, Object> baseline = allDetectionNodes(photos).stream()
        .filter(node -> Objects.equals(longValue(node, "clauseId", "clause_id"), clauseId))
        .filter(node -> Boolean.TRUE.equals(booleanValue(node, "baselineCaptured")))
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_DETECTION_BASELINE_MISSING",
            "识别扣分缺少可审计的原始状态，已停止撤销",
            HttpStatus.CONFLICT
        ));
    int index = snapshotIndex(snapshots, clauseId);
    InspectionItemResultResponse current = snapshots.get(index);
    BigDecimal actual = decimalValue(baseline, "baselineActualScore");
    BigDecimal deduction = decimalValue(baseline, "baselineDeductionScore");
    if (actual == null) {
      throw new BusinessException(
          "INSPECTION_DETECTION_BASELINE_MISSING", "识别扣分原始分值缺失，已停止撤销", HttpStatus.CONFLICT);
    }
    snapshots.set(index, copySnapshot(
        current,
        actual,
        deduction == null
            ? amountOrDefault(current.standardScore(), ZERO_AMOUNT).subtract(actual).max(ZERO_AMOUNT)
            : deduction,
        textValue(baseline, "baselineDeductionReason"),
        idsValue(baseline, "baselinePhotoAttachmentIds"),
        textValue(baseline, "baselineRectificationStatus")
    ));
  }

  private List<Long> idsValue(Map<String, Object> source, String key) {
    Object raw = source.get(key);
    if (!(raw instanceof Collection<?> values)) {
      return List.of();
    }
    return values.stream()
        .map(value -> value instanceof Number number ? number.longValue() : parseLongOrNull(value))
        .filter(Objects::nonNull)
        .filter(value -> value > 0)
        .distinct()
        .toList();
  }

  private Long parseLongOrNull(Object value) {
    try {
      return value == null ? null : Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private int snapshotIndex(List<InspectionItemResultResponse> snapshots, long clauseId) {
    for (int index = 0; index < snapshots.size(); index++) {
      if (Objects.equals(snapshots.get(index).standardItemId(), clauseId)) {
        return index;
      }
    }
    throw new BusinessException(
        "INSPECTION_DETECTION_CLAUSE_NOT_FOUND",
        "识别结果匹配的条款不属于该巡检快照",
        HttpStatus.CONFLICT
    );
  }

  private InspectionItemResultResponse requireSnapshot(
      List<InspectionItemResultResponse> snapshots,
      long clauseId
  ) {
    return snapshots.get(snapshotIndex(snapshots, clauseId));
  }

  private String mergeReason(String first, String second) {
    String left = blankToNull(first);
    String right = blankToNull(second);
    if (left == null) {
      return right;
    }
    if (right == null || left.contains(right)) {
      return left;
    }
    return left + "；" + right;
  }

  private String detectionPolicyExplanation(Map<String, Object> detection) {
    BigDecimal clauseDeduction = amountOrDefault(
        decimalValue(detection, "clauseDeduction", "clause_deduction"), ZERO_AMOUNT);
    BigDecimal scaleAdjustmentDeduction = amountOrDefault(
        decimalValue(detection, "scaleAdjustmentDeduction", "scale_adjustment_deduction"),
        ZERO_AMOUNT);
    BigDecimal finalDeduction = amountOrDefault(
        decimalValue(detection, "standardDeduction", "standard_deduction"),
        clauseDeduction.add(scaleAdjustmentDeduction));
    String policyVersion = firstNonBlank(
        textValue(detection, "deductionPolicyVersion", "deduction_policy_version"),
        ACTIVE_CLAUSE_DEDUCTION_POLICY);
    return "条款" + scoreText(clauseDeduction)
        + "+换算调整" + scoreText(scaleAdjustmentDeduction)
        + "=" + scoreText(finalDeduction)
        + "；策略" + policyVersion;
  }

  private String scoreText(BigDecimal value) {
    return amountOrDefault(value, ZERO_AMOUNT).stripTrailingZeros().toPlainString();
  }

  private Map<String, Object> responseDetection(Map<String, Object> source) {
    Map<String, Object> response = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (value != null && !key.startsWith("baseline")) {
        response.put(key, value);
      }
    });
    return response;
  }

  private record DetectionDocument(
      List<Map<String, Object>> photos,
      Map<String, Object> envelope,
      Map<String, Object> node
  ) {}

  private record SnapshotTotals(
      BigDecimal score,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      String resultCode,
      boolean passed,
      List<Map<String, Object>> deductions,
      List<Map<String, Object>> redlines
  ) {}

  @Transactional
  public void delete(AuthUser user, String id) {
    requireInspectionManage(user);
    InspectionRecordResponse record = requireRecord(user.tenantId(), id);
    requireInspectionStoreAccess(user, record.storeId(), "删除巡检记录");
    requireUnrepairedRecord(user.tenantId(), record.id(), "删除");
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

  private void requireUnrepairedRecord(long tenantId, String inspectionRecordId, String action) {
    if (!recordRepository.hasRepairAudit(tenantId, inspectionRecordId)) {
      return;
    }
    throw new BusinessException(
        "INSPECTION_REPAIRED_RECORD_IMMUTABLE",
        "该历史巡检已生成修复审计记录，禁止" + action + "原始分数、条款快照和现场证据",
        HttpStatus.CONFLICT
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

  public Map<String, Object> detect(AuthUser user, MultipartFile file) {
    requireInspectionManage(user);
    Map<String, Object> raw = detectRaw(file);
    if (standardRepository == null) {
      return raw;
    }
    Map<String, Object> suggestion = detectionSuggestions(user, new InspectionDetectionBindingRequest(
        null, null, InspectionScoringRules.LEGACY_MAX_SCORE, List.of(raw), null)).getFirst();
    return withTransientAnnotatedPreview(raw, suggestion);
  }

  /**
   * The enriched detection is safe to persist because image fields are stripped there. Restore only
   * a bounded local data-image for this immediate HTTP response so the current page can preview it.
   */
  static Map<String, Object> withTransientAnnotatedPreview(
      Map<String, Object> raw,
      Map<String, Object> suggestion
  ) {
    Map<String, Object> response = new LinkedHashMap<>();
    if (suggestion != null) {
      response.putAll(suggestion);
    }
    Object candidate = raw == null ? null : raw.get("annotated_image");
    if (!(candidate instanceof String)) {
      candidate = raw == null ? null : raw.get("annotatedImage");
    }
    if (candidate instanceof String annotatedImage && isSafeTransientAnnotatedPreview(annotatedImage)) {
      response.put("annotated_image", annotatedImage.trim());
    }
    return response;
  }

  private static boolean isSafeTransientAnnotatedPreview(String image) {
    String value = image == null ? "" : image.trim();
    if (value.isEmpty() || value.length() > MAX_TRANSIENT_ANNOTATED_IMAGE_CHARS) {
      return false;
    }
    return value.startsWith("data:image/jpeg;base64,")
        || value.startsWith("data:image/jpg;base64,")
        || value.startsWith("data:image/png;base64,")
        || value.startsWith("data:image/webp;base64,");
  }

  /** Compatibility entry point for isolated HTTP-client tests. */
  public Map<String, Object> detect(MultipartFile file) {
    return detectRaw(file);
  }

  public List<Map<String, Object>> detectionSuggestions(
      AuthUser user,
      InspectionDetectionBindingRequest request
  ) {
    requireInspectionManage(user);
    List<Map<String, Object>> results = normalizeDetectionResults(request);
    if (standardRepository == null) {
      return results;
    }
    InspectionStandardRepository.VersionRow active = standardRepository.activeVersion(user.tenantId())
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_STANDARD_MISSING", "当前没有启用的巡检标准", HttpStatus.CONFLICT));
    List<InspectionStandardItemResponse> standards = standardRepository.items(user.tenantId(), active.id());
    InspectionStandardValidation validation = InspectionStandardValidator.validate(active, standards);
    if (!validation.valid()) {
      throw new BusinessException(
          "INSPECTION_STANDARD_INVALID",
          "图片识别前必须先启用校验通过的巡检标准：" + validation.validationError(),
          HttpStatus.CONFLICT
      );
    }
    return results.stream()
        .map(result -> enrichDetectionSuggestion(result, request.fullScore(), standards))
        .toList();
  }

  /**
   * Confirms a draft recognition suggestion without writing an inspection record.
   * The browser-provided clause and deduction fields are discarded and recomputed
   * against the currently active standard.
   */
  public Map<String, Object> confirmDraftDetection(
      AuthUser user,
      String detectionKey,
      InspectionDraftDetectionConfirmRequest request
  ) {
    requireInspectionManage(user);
    String expectedKey = requireText(
        detectionKey, "INSPECTION_DETECTION_KEY_REQUIRED", "识别结果编号不能为空");
    if (request == null || request.evidence().isEmpty()) {
      throw new BusinessException(
          "BAD_DETECTION_RESULTS", "确认识别结果必须提供原始识别证据", HttpStatus.BAD_REQUEST);
    }
    Map<String, Object> evidence = authoritativeDetectionInput(request.evidence());
    Map<String, Object> confirmed = new LinkedHashMap<>(detectionSuggestions(
        user, new InspectionDetectionBindingRequest(
            null, null, InspectionScoringRules.LEGACY_MAX_SCORE, List.of(evidence), null)).getFirst());
    if (!expectedKey.equals(textValue(confirmed, "detectionKey", "detection_key"))) {
      throw new BusinessException(
          "INSPECTION_DETECTION_KEY_MISMATCH",
          "识别证据与确认编号不一致，请重新识别后再确认",
          HttpStatus.CONFLICT);
    }
    if (longValue(confirmed, "clauseId", "clause_id") == null) {
      throw new BusinessException(
          "INSPECTION_DETECTION_UNMATCHED",
          "该识别结果未匹配到正式巡检条款，请人工选择条款并填写原因",
          HttpStatus.CONFLICT);
    }
    confirmed.put("decisionStatus", DETECTION_CONFIRMED);
    confirmed.put("review_status", DETECTION_CONFIRMED);
    confirmed.put("confirmedDeduction", authoritativeDetectionDeduction(confirmed));
    confirmed.put("revision", 1L);
    return responseDetection(confirmed);
  }

  private Map<String, Object> resolveSavedDetection(
      AuthUser user,
      InspectionRecordResponse record,
      Map<String, Object> storedDetection
  ) {
    if (standardRepository == null) {
      throw new BusinessException(
          "INSPECTION_STANDARD_MISSING",
          "当前运行环境无法读取正式巡检标准，已停止确认识别扣分",
          HttpStatus.CONFLICT);
    }
    InspectionStandardRepository.VersionRow active = standardRepository.activeVersion(user.tenantId())
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_STANDARD_MISSING", "当前没有启用的巡检标准", HttpStatus.CONFLICT));
    if (record.standardVersionId() != null && record.standardVersionId() != active.id()) {
      throw new BusinessException(
          "INSPECTION_STANDARD_STALE",
          "该巡检使用的标准已不是当前启用版本，请刷新并按历史标准人工复核",
          HttpStatus.CONFLICT);
    }
    List<InspectionStandardItemResponse> standards = standardRepository.items(user.tenantId(), active.id());
    InspectionStandardValidation validation = InspectionStandardValidator.validate(active, standards);
    if (!validation.valid()) {
      throw new BusinessException(
          "INSPECTION_STANDARD_INVALID",
          "当前巡检标准校验失败，已停止确认识别扣分：" + validation.validationError(),
          HttpStatus.CONFLICT);
    }
    Map<String, Object> evidence = authoritativeDetectionInput(storedDetection);
    Map<String, Object> resolved = enrichDetectionSuggestion(
        evidence, InspectionScoringRules.LEGACY_MAX_SCORE, standards);
    String storedKey = textValue(storedDetection, "detectionKey", "detection_key");
    if (!Objects.equals(storedKey, textValue(resolved, "detectionKey", "detection_key"))) {
      throw new BusinessException(
          "INSPECTION_DETECTION_KEY_MISMATCH",
          "识别证据与记录中的确认编号不一致，请重新识别",
          HttpStatus.CONFLICT);
    }
    Long clauseId = longValue(resolved, "clauseId", "clause_id");
    if (clauseId == null) {
      throw new BusinessException(
          "INSPECTION_DETECTION_UNMATCHED",
          "该识别结果无法匹配当前正式巡检条款，请人工选择条款并填写原因",
          HttpStatus.CONFLICT);
    }
    InspectionItemResultResponse snapshot = requireSnapshot(mutableSnapshots(record), clauseId);
    InspectionStandardItemResponse clause = standards.stream()
        .filter(item -> item.id() == clauseId).findFirst()
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_DETECTION_CLAUSE_NOT_FOUND",
            "识别条款不属于当前正式标准",
            HttpStatus.CONFLICT));
    if (!Objects.equals(snapshot.code(), clause.code())
        || amountOrDefault(snapshot.standardScore(), ZERO_AMOUNT)
            .compareTo(amountOrDefault(clause.suggestedScore(), ZERO_AMOUNT)) != 0) {
      throw new BusinessException(
          "INSPECTION_DETECTION_SNAPSHOT_MISMATCH",
          "巡检条款快照与当前正式标准不一致，禁止自动扣分",
          HttpStatus.CONFLICT);
    }
    return resolved;
  }

  private void replaceAuthoritativeDetectionFields(
      Map<String, Object> target,
      Map<String, Object> resolved
  ) {
    for (String key : List.of(
        "detectionKey", "imageId", "scoreScale", "persistedScoreScale",
        "legacyDeduction", "convertedDeduction200", "standardDeduction",
        "clauseDeduction", "scaleAdjustmentDeduction", "deductionPolicyVersion",
        "suggestedDeduction", "finalDeduction", "confirmedDeduction",
        "confidence", "issueCode", "issueName", "clauseId", "clauseCode",
        "clauseTitle", "detections", "detection_count", "detectionCount",
        "deduction_score")) {
      if (resolved.containsKey(key)) {
        target.put(key, resolved.get(key));
      } else {
        target.remove(key);
      }
    }
  }

  private Map<String, Object> detectRaw(MultipartFile file) {
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

  private CalculatedInspection calculateInspection(
      AuthUser user,
      String inspectionRecordId,
      InspectionRecordRequest request
  ) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Inspection payload is required", HttpStatus.BAD_REQUEST);
    }
    // Keep the established request-error contract: malformed core fields are
    // reported before the score-scale policy is evaluated.
    requireText(request.storeId(), "STORE_REQUIRED", "Store is required");
    normalizeDate(request.inspectionDate(), "inspectionDate");
    if (standardRepository != null
        && request.fullScore() != null
        && request.fullScore().compareTo(InspectionScoringRules.MAX_SCORE) != 0) {
      throw new BusinessException(
          "INSPECTION_SCORE_SCALE_INVALID",
          "巡检满分必须为200分；旧分制数据只能通过历史迁移折算",
          HttpStatus.BAD_REQUEST
      );
    }
    if (standardRepository == null) {
      InspectionRecordRequest normalized = normalizeRequest(request);
      return new CalculatedInspection(
          normalized,
          standardSnapshots(normalized),
          attachmentIdsFromPhotosJson(normalized.photosJson()),
          List.of()
      );
    }
    InspectionStandardRepository.VersionRow active = standardRepository.activeVersion(user.tenantId())
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_STANDARD_MISSING", "当前没有启用的巡检标准", HttpStatus.CONFLICT));
    if (request.standardVersionId() != null && request.standardVersionId() != active.id()) {
      throw new BusinessException(
          "INSPECTION_STANDARD_STALE", "巡检标准已更新，请刷新页面后重新评分", HttpStatus.CONFLICT);
    }
    List<InspectionStandardItemResponse> standards = standardRepository.items(user.tenantId(), active.id());
    InspectionStandardValidation validation = InspectionStandardValidator.validate(active, standards);
    if (!validation.valid()) {
      throw new BusinessException(
          "INSPECTION_STANDARD_INVALID",
          "当前巡检标准校验失败：" + validation.validationError(),
          HttpStatus.CONFLICT
      );
    }
    if (request.itemResults().isEmpty()) {
      throw new BusinessException(
          "INSPECTION_ITEMS_INCOMPLETE",
          "新巡检必须提交当前标准的全部105条评分",
          HttpStatus.BAD_REQUEST
      );
    }
    Map<Long, InspectionItemResultRequest> resultByItemId = new HashMap<>();
    for (InspectionItemResultRequest result : request.itemResults()) {
      if (result == null || result.standardItemId() == null) {
        throw new BusinessException("INSPECTION_ITEM_REQUIRED", "每条评分必须关联检查条款", HttpStatus.BAD_REQUEST);
      }
      if (resultByItemId.put(result.standardItemId(), result) != null) {
        throw new BusinessException("INSPECTION_ITEM_DUPLICATE", "检查条款不能重复评分", HttpStatus.BAD_REQUEST);
      }
    }
    if (resultByItemId.size() != standards.size()) {
      throw new BusinessException(
          "INSPECTION_ITEMS_INCOMPLETE",
          "必须完成全部" + standards.size() + "条检查条款后才能保存",
          HttpStatus.BAD_REQUEST
      );
    }
    DraftDetectionApplication detectionApplication = applyDraftDetectionDecisions(
        request.photosJson(), standards, resultByItemId);

    BigDecimal materialScore = ZERO_AMOUNT;
    BigDecimal hygieneScore = ZERO_AMOUNT;
    BigDecimal serviceScore = ZERO_AMOUNT;
    boolean redLineHit = false;
    List<InspectionStandardSnapshot> snapshots = new ArrayList<>();
    List<Map<String, Object>> deductions = new ArrayList<>();
    List<Map<String, Object>> redlines = new ArrayList<>();
    List<Map<String, Object>> photos = new ArrayList<>();
    Set<Long> attachmentIds = new HashSet<>(attachmentIdsFromPhotosJson(request.photosJson()));

    for (InspectionStandardItemResponse standard : standards) {
      InspectionItemResultRequest result = resultByItemId.remove(standard.id());
      if (result == null) {
        throw new BusinessException(
            "INSPECTION_ITEMS_INCOMPLETE", "存在未评分的检查条款：" + standard.code(), HttpStatus.BAD_REQUEST);
      }
      BigDecimal standardScore = amountOrDefault(standard.suggestedScore(), BigDecimal.ZERO);
      BigDecimal actualScore = requiredItemScore(result.actualScore(), standardScore, standard.code());
      BigDecimal manualDeduction = standardScore.subtract(actualScore)
          .max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
      BigDecimal confirmedDetectionDeduction = detectionApplication.confirmedDeductions()
          .getOrDefault(standard.id(), ZERO_AMOUNT);
      BigDecimal deduction = manualDeduction.max(confirmedDetectionDeduction)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal clampedItemActual = standardScore.subtract(deduction).max(ZERO_AMOUNT)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal scaleAdjustment = deduction.subtract(standardScore).max(ZERO_AMOUNT)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal scoreContribution = clampedItemActual.subtract(scaleAdjustment)
          .setScale(2, RoundingMode.HALF_UP);
      String reason = blankToNull(result.deductionReason());
      if (deduction.signum() > 0 && reason == null) {
        throw new BusinessException(
            "INSPECTION_DEDUCTION_REASON_REQUIRED",
            "扣分项必须填写原因：" + standard.code(),
            HttpStatus.BAD_REQUEST
        );
      }
      String riskLevel = normalizeRiskLevel(standard.riskLevel(), standard.redLine());
      boolean issueFound = Boolean.TRUE.equals(result.issueFound())
          || deduction.signum() > 0
          || reason != null;
      if (issueFound && reason == null) {
        throw new BusinessException(
            "INSPECTION_ISSUE_REASON_REQUIRED",
            "发现问题时必须填写说明：" + standard.code(),
            HttpStatus.BAD_REQUEST
        );
      }
      boolean itemRedLineHit = "RED".equals(riskLevel) && issueFound;
      redLineHit = redLineHit || itemRedLineHit;

      switch (category(standard.dimension())) {
        case "MATERIAL" -> materialScore = materialScore.add(scoreContribution);
        case "HYGIENE" -> hygieneScore = hygieneScore.add(scoreContribution);
        case "SERVICE" -> serviceScore = serviceScore.add(scoreContribution);
        default -> throw new BusinessException(
            "INSPECTION_STANDARD_CATEGORY_INVALID",
            "无法识别检查分类：" + standard.dimension(),
            HttpStatus.CONFLICT
        );
      }

      List<Long> itemPhotos = normalizedIds(result.photoAttachmentIds());
      List<Long> beforePhotos = normalizedIds(result.beforePhotoAttachmentIds());
      List<Long> afterPhotos = normalizedIds(result.afterPhotoAttachmentIds());
      attachmentIds.addAll(itemPhotos);
      attachmentIds.addAll(beforePhotos);
      attachmentIds.addAll(afterPhotos);
      snapshots.add(new InspectionStandardSnapshot(
          standard.id(), active.version(), standard.dimension(), standard.title(), standard.description(),
          standardScore, deduction, standard.redLine(), reason, standard.sortOrder(), standard.code(),
          standard.checkMethod(), clampedItemActual, riskLevel, itemPhotos, result.responsiblePerson(),
          result.rectificationDeadline(), normalizeRectificationStatus(result.rectificationStatus(), deduction),
          result.reviewResult(), beforePhotos, afterPhotos
      ));

      if (issueFound) {
        Map<String, Object> detail = scoringJson(standard, active.version(), actualScore, deduction, reason, itemPhotos);
        deductions.add(detail);
        if (itemRedLineHit) {
          redlines.add(detail);
        }
      }
      for (Long attachmentId : itemPhotos) {
        photos.add(Map.of("attachmentId", attachmentId, "standardItemId", standard.id()));
      }
    }
    if (!resultByItemId.isEmpty()) {
      throw new BusinessException("INSPECTION_ITEM_UNKNOWN", "评分中包含不属于当前标准的条款", HttpStatus.BAD_REQUEST);
    }

    materialScore = amountOrDefault(materialScore.max(ZERO_AMOUNT), ZERO_AMOUNT);
    hygieneScore = amountOrDefault(hygieneScore.max(ZERO_AMOUNT), ZERO_AMOUNT);
    serviceScore = amountOrDefault(serviceScore.max(ZERO_AMOUNT), ZERO_AMOUNT);
    BigDecimal totalScore = materialScore.add(hygieneScore).add(serviceScore).setScale(2, RoundingMode.HALF_UP);
    String resultCode = InspectionScoringRules.resultCode(totalScore, redLineHit);
    InspectionRecordRequest calculated = new InspectionRecordRequest(
        requireText(request.storeId(), "STORE_REQUIRED", "Store is required"),
        normalizeDate(request.inspectionDate(), "inspectionDate"),
        request.inspector(),
        request.brand(),
        active.fullScore(),
        totalScore,
        "PASSED".equals(resultCode),
        toJson(deductions),
        toJson(redlines),
        toJson(mergePersistedPhotos(detectionApplication.photos(), photos)),
        request.note(),
        active.id(),
        active.version(),
        materialScore,
        hygieneScore,
        serviceScore,
        resultCode,
        request.itemResults()
    );
    return new CalculatedInspection(
        calculated, snapshots, Set.copyOf(attachmentIds), detectionApplication.audits());
  }

  private DraftDetectionApplication applyDraftDetectionDecisions(
      String photosJson,
      List<InspectionStandardItemResponse> standards,
      Map<Long, InspectionItemResultRequest> resultByItemId
  ) {
    if (photosJson == null || photosJson.isBlank()) {
      return new DraftDetectionApplication(List.of(), List.of(), Map.of());
    }
    List<Map<String, Object>> photos = parseDetectionPhotos(photosJson);
    List<DetectionAudit> audits = new ArrayList<>();
    Map<Long, BigDecimal> confirmedDeductions = new HashMap<>();
    Set<String> appliedKeys = new HashSet<>();
    for (Map<String, Object> photo : photos) {
      Map<String, Object> originalNode = detectionNode(photo);
      if (originalNode.isEmpty()
          || value(originalNode, "detections") == null
          && textValue(originalNode, "image_id", "imageId") == null) {
        continue;
      }
      Map<String, Object> authoritativeInput = authoritativeDetectionInput(originalNode);
      Map<String, Object> enriched = enrichDetectionSuggestion(
          authoritativeInput, InspectionScoringRules.LEGACY_MAX_SCORE, standards);
      putIfPresent(enriched, "attachmentId", longValue(photo, "attachmentId", "attachment_id"));
      String reviewStatus = firstNonBlank(
          textValue(photo, "reviewStatus", "review_status", "decisionStatus"),
          textValue(originalNode, "reviewStatus", "review_status", "decisionStatus"));
      boolean accepted = "ACCEPTED".equalsIgnoreCase(reviewStatus)
          || DETECTION_CONFIRMED.equalsIgnoreCase(reviewStatus);
      if (accepted) {
        Long clauseId = longValue(enriched, "clauseId", "clause_id");
        if (clauseId == null) {
          throw new BusinessException(
              "INSPECTION_DETECTION_UNMATCHED",
              "已确认的图片识别结果未匹配到正式条款，请人工选择具体条款并填写原因",
              HttpStatus.BAD_REQUEST
          );
        }
        InspectionItemResultRequest current = resultByItemId.get(clauseId);
        if (current == null) {
          throw new BusinessException(
              "INSPECTION_DETECTION_CLAUSE_NOT_FOUND",
              "图片识别匹配的条款不属于当前标准",
              HttpStatus.BAD_REQUEST
          );
        }
        String key = textValue(enriched, "detectionKey", "detection_key");
        if (key != null && appliedKeys.add(key)) {
          InspectionStandardItemResponse matchedStandard = standards.stream()
              .filter(item -> item.id() == clauseId).findFirst().orElseThrow();
          BigDecimal baselineActual = amountOrDefault(current.actualScore(), ZERO_AMOUNT);
          BigDecimal baselineDeduction = amountOrDefault(matchedStandard.suggestedScore(), ZERO_AMOUNT)
              .subtract(baselineActual).max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
          enriched.put("baselineCaptured", true);
          enriched.put("baselineActualScore", baselineActual);
          enriched.put("baselineDeductionScore", baselineDeduction);
          putIfPresent(enriched, "baselineDeductionReason", blankToNull(current.deductionReason()));
          enriched.put("baselinePhotoAttachmentIds", current.photoAttachmentIds());
          putIfPresent(enriched, "baselineRectificationStatus", blankToNull(current.rectificationStatus()));
          List<Long> attachmentIds = new ArrayList<>(current.photoAttachmentIds());
          Long attachmentId = longValue(photo, "attachmentId", "attachment_id");
          if (attachmentId != null && attachmentId > 0 && !attachmentIds.contains(attachmentId)) {
            attachmentIds.add(attachmentId);
          }
          String reason = firstNonBlank(
              textValue(enriched, "issueName", "issue_name", "deduction_content"),
              "图片识别到疑似现场问题，已由督导确认"
          );
          reason = mergeReason(reason, detectionPolicyExplanation(enriched));
          resultByItemId.put(clauseId, new InspectionItemResultRequest(
              current.standardItemId(), ZERO_AMOUNT, true,
              mergeReason(current.deductionReason(), reason), List.copyOf(attachmentIds),
              current.responsiblePerson(), current.rectificationDeadline(),
              current.rectificationStatus(), current.reviewResult(),
              current.beforePhotoAttachmentIds(), current.afterPhotoAttachmentIds()
          ));
          BigDecimal confirmedDeduction = authoritativeDetectionDeduction(enriched);
          confirmedDeductions.merge(clauseId, confirmedDeduction, BigDecimal::max);
          audits.add(new DetectionAudit(key, clauseId, detectionPolicyExplanation(enriched)));
        }
        enriched.put("decisionStatus", DETECTION_CONFIRMED);
        enriched.put("review_status", DETECTION_CONFIRMED);
        enriched.put("confirmedDeduction", decimalValue(enriched, "standardDeduction"));
        enriched.put("revision", 1L);
        photo.put("reviewStatus", "accepted");
      } else if ("DISMISSED".equalsIgnoreCase(reviewStatus)
          || DETECTION_REVOKED.equalsIgnoreCase(reviewStatus)) {
        enriched.put("decisionStatus", DETECTION_REVOKED);
        enriched.put("review_status", DETECTION_REVOKED);
        enriched.put("revision", 1L);
        photo.put("reviewStatus", "dismissed");
      }
      photo.put("detection", enriched);
      photo.remove("sourceFile");
      photo.remove("previewUrl");
    }
    return new DraftDetectionApplication(
        List.copyOf(photos), List.copyOf(audits), Map.copyOf(confirmedDeductions));
  }

  private Map<String, Object> authoritativeDetectionInput(Map<String, Object> source) {
    Map<String, Object> clean = new LinkedHashMap<>(source);
    for (String key : List.of(
        "clauseId", "clause_id", "standardItemId", "standard_item_id",
        "clauseCode", "clause_code", "standardCode", "standard_code",
        "legacyDeduction", "legacy_deduction", "deduction_score", "deductionScore",
        "convertedDeduction200", "converted_deduction_200", "scoreScale", "score_scale",
        "finalDeduction", "final_deduction", "confirmedDeduction", "confirmed_deduction",
        "standardDeduction", "standard_deduction", "suggestedDeduction", "suggested_deduction",
        "clauseDeduction", "clause_deduction",
        "scaleAdjustmentDeduction", "scale_adjustment_deduction",
        "deductionPolicyVersion", "deduction_policy_version",
        "manualAdjustment", "manual_adjustment",
        "manualAdjustmentOriginalDeduction", "manual_adjustment_original_deduction",
        "manualAdjustmentAdjustedDeduction", "manual_adjustment_adjusted_deduction",
        "manualAdjustmentReason", "manual_adjustment_reason")) {
      clean.remove(key);
    }
    clean.remove("annotated_image");
    clean.remove("annotatedImage");
    clean.remove("original_image");
    clean.remove("originalImage");
    return clean;
  }

  private List<Map<String, Object>> mergePersistedPhotos(
      List<Map<String, Object>> detectionPhotos,
      List<Map<String, Object>> itemPhotos
  ) {
    if (detectionPhotos == null || detectionPhotos.isEmpty()) {
      return itemPhotos;
    }
    List<Map<String, Object>> merged = new ArrayList<>(detectionPhotos);
    Set<Long> existingIds = merged.stream()
        .map(photo -> longValue(photo, "attachmentId", "attachment_id"))
        .filter(Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());
    for (Map<String, Object> itemPhoto : itemPhotos) {
      Long attachmentId = longValue(itemPhoto, "attachmentId", "attachment_id");
      if (attachmentId == null || existingIds.add(attachmentId)) {
        merged.add(itemPhoto);
      }
    }
    return List.copyOf(merged);
  }

  private BigDecimal requiredItemScore(BigDecimal value, BigDecimal maximum, String code) {
    if (value == null) {
      throw new BusinessException("INSPECTION_ITEM_SCORE_REQUIRED", "请填写条款得分：" + code, HttpStatus.BAD_REQUEST);
    }
    BigDecimal normalized = value.setScale(2, RoundingMode.HALF_UP);
    if (normalized.signum() < 0 || normalized.compareTo(maximum) > 0) {
      throw new BusinessException(
          "INSPECTION_ITEM_SCORE_INVALID", "条款实际分必须在0到标准分之间：" + code, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private Map<String, Object> scoringJson(
      InspectionStandardItemResponse standard,
      String version,
      BigDecimal actualScore,
      BigDecimal deduction,
      String reason,
      List<Long> attachmentIds
  ) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("standard_id", standard.id());
    detail.put("standard_version", version);
    detail.put("dimension", standard.dimension());
    detail.put("code", standard.code());
    detail.put("item", standard.title());
    detail.put("method", standard.checkMethod());
    detail.put("score", standard.suggestedScore());
    detail.put("actual_score", actualScore);
    detail.put("deduct", deduction);
    detail.put("issue", reason);
    detail.put("risk_level", normalizeRiskLevel(standard.riskLevel(), standard.redLine()));
    detail.put("redline", standard.redLine());
    detail.put("photo_attachment_ids", attachmentIds);
    return detail;
  }

  private HistoricalRepairCalculation calculateHistoricalRepair(
      List<InspectionStandardItemResponse> standards,
      List<InspectionItemResultResponse> snapshots,
      InspectionStandardRepository.VersionRow active
  ) {
    if (snapshots.size() != standards.size()) {
      return HistoricalRepairCalculation.manual(
          "原巡检条款快照不完整：应为" + standards.size() + "条，实际为" + snapshots.size() + "条");
    }
    Map<String, InspectionItemResultResponse> snapshotByCode = new HashMap<>();
    for (InspectionItemResultResponse snapshot : snapshots) {
      String code = blankToNull(snapshot.code());
      if (code == null || snapshotByCode.put(code, snapshot) != null) {
        return HistoricalRepairCalculation.manual("原巡检条款快照缺少唯一条款编号，禁止猜测匹配关系");
      }
    }
    BigDecimal score = ZERO_AMOUNT;
    BigDecimal materialScore = ZERO_AMOUNT;
    BigDecimal hygieneScore = ZERO_AMOUNT;
    BigDecimal serviceScore = ZERO_AMOUNT;
    boolean redLineHit = false;
    for (InspectionStandardItemResponse standard : standards) {
      InspectionItemResultResponse snapshot = snapshotByCode.remove(standard.code());
      if (snapshot == null) {
        return HistoricalRepairCalculation.manual("原巡检快照缺少修正版条款：" + standard.code());
      }
      BigDecimal maximum = amountOrDefault(standard.suggestedScore(), BigDecimal.ZERO);
      BigDecimal deduction = amountOrDefault(snapshot.deductionScore(), BigDecimal.ZERO);
      if (deduction.signum() < 0) {
        return HistoricalRepairCalculation.manual("原巡检快照存在负扣分：" + standard.code());
      }
      BigDecimal actual = maximum.subtract(deduction).max(BigDecimal.ZERO)
          .setScale(2, RoundingMode.HALF_UP);
      score = score.add(actual);
      String repairCategory = category(standard.dimension());
      if (repairCategory == null) {
        return HistoricalRepairCalculation.manual(
            "修正版条款分类无法识别，禁止自动重算：" + standard.code());
      }
      switch (repairCategory) {
        case "MATERIAL" -> materialScore = materialScore.add(actual);
        case "HYGIENE" -> hygieneScore = hygieneScore.add(actual);
        case "SERVICE" -> serviceScore = serviceScore.add(actual);
        default -> throw new IllegalStateException("Unexpected inspection category: " + repairCategory);
      }
      redLineHit = redLineHit
          || ("RED".equals(normalizeRiskLevel(standard.riskLevel(), standard.redLine()))
              && snapshot.issueFound());
    }
    if (!snapshotByCode.isEmpty()) {
      return HistoricalRepairCalculation.manual("原巡检快照包含修正版中不存在的条款，禁止自动重算");
    }
    score = score.setScale(2, RoundingMode.HALF_UP);
    String resultCode = InspectionScoringRules.resultCode(score, redLineHit);
    return HistoricalRepairCalculation.recalculated(
        score,
        materialScore.setScale(2, RoundingMode.HALF_UP),
        hygieneScore.setScale(2, RoundingMode.HALF_UP),
        serviceScore.setScale(2, RoundingMode.HALF_UP),
        resultCode,
        "依据修正版" + active.version() + "及105条完整历史快照重新计算"
    );
  }

  private InspectionResultRepairWrite repairWrite(
      AuthUser user,
      InspectionRecordResponse record,
      InspectionStandardRepository.VersionRow active,
      int snapshotItemCount,
      HistoricalRepairCalculation calculation
  ) {
    return new InspectionResultRepairWrite(
        record.standardVersionId(),
        record.standardVersion(),
        record.fullScore(),
        InspectionScoringRules.PASS_SCORE,
        record.score(),
        record.materialScore(),
        record.hygieneScore(),
        record.serviceScore(),
        record.resultCode(),
        record.passed(),
        active.id(),
        active.version(),
        calculation.manualReview() ? null : active.fullScore(),
        calculation.manualReview() ? null : active.passScore(),
        calculation.score(),
        calculation.materialScore(),
        calculation.hygieneScore(),
        calculation.serviceScore(),
        calculation.resultCode(),
        calculation.manualReview() ? null : "PASSED".equals(calculation.resultCode()),
        calculation.manualReview() ? "MANUAL_REVIEW" : "RECALCULATED",
        calculation.reason(),
        snapshotItemCount,
        InspectionStandardValidator.REQUIRED_ITEM_COUNT,
        user.id()
    );
  }

  private List<Long> normalizedIds(List<Long> values) {
    if (values == null) {
      return List.of();
    }
    List<Long> ids = values.stream().filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().toList();
    if (ids.size() != values.stream().filter(java.util.Objects::nonNull).distinct().count()) {
      throw new BusinessException("BAD_ATTACHMENT_ID", "巡检照片编号不正确", HttpStatus.BAD_REQUEST);
    }
    return ids;
  }

  private Set<Long> attachmentIdsFromPhotosJson(String photosJson) {
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

  private String normalizeRiskLevel(String value, boolean redLine) {
    if (redLine) {
      return "RED";
    }
    String normalized = value == null ? "NORMAL" : value.trim().toUpperCase();
    return switch (normalized) {
      case "RED", "YELLOW" -> normalized;
      default -> "NORMAL";
    };
  }

  private String normalizeRectificationStatus(String value, BigDecimal deduction) {
    String normalized = value == null ? "" : value.trim().toUpperCase();
    return switch (normalized) {
      case "待整改", "PENDING" -> "PENDING";
      case "整改中", "IN_PROGRESS" -> "IN_PROGRESS";
      case "待复核", "PENDING_REVIEW" -> "PENDING_REVIEW";
      case "已完成", "已整改", "COMPLETED" -> "COMPLETED";
      case "复核通过", "VERIFIED" -> "VERIFIED";
      case "无需整改", "NOT_REQUIRED" -> "NOT_REQUIRED";
      case "" -> deduction.signum() > 0 ? "PENDING" : "NOT_REQUIRED";
      default -> throw new BusinessException(
          "BAD_RECTIFICATION_STATUS", "整改状态不正确", HttpStatus.BAD_REQUEST);
    };
  }

  private String category(String dimension) {
    return InspectionStandardValidator.category(dimension);
  }

  private record CalculatedInspection(
      InspectionRecordRequest request,
      List<InspectionStandardSnapshot> snapshots,
      Set<Long> attachmentIds,
      List<DetectionAudit> detectionAudits
  ) {}

  private record DetectionAudit(
      String detectionKey,
      long clauseId,
      String deductionExplanation
  ) {}

  private record DraftDetectionApplication(
      List<Map<String, Object>> photos,
      List<DetectionAudit> audits,
      Map<Long, BigDecimal> confirmedDeductions
  ) {}

  private record HistoricalRepairCalculation(
      boolean manualReview,
      BigDecimal score,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      String resultCode,
      String reason
  ) {
    private static HistoricalRepairCalculation manual(String reason) {
      return new HistoricalRepairCalculation(true, null, null, null, null, null, reason);
    }

    private static HistoricalRepairCalculation recalculated(
        BigDecimal score,
        BigDecimal materialScore,
        BigDecimal hygieneScore,
        BigDecimal serviceScore,
        String resultCode,
        String reason
    ) {
      return new HistoricalRepairCalculation(
          false, score, materialScore, hygieneScore, serviceScore, resultCode, reason);
    }
  }

  private record ExportScoreRepair(
      BigDecimal score,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      boolean passed,
      String resultCode,
      String reason
  ) {
    private static ExportScoreRepair empty() {
      return new ExportScoreRepair(null, null, null, null, false, null, null);
    }
  }

  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private InspectionRecordRequest normalizeRequest(InspectionRecordRequest request) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Inspection payload is required", HttpStatus.BAD_REQUEST);
    }
    String storeId = requireText(request.storeId(), "STORE_REQUIRED", "Store is required");
    String inspectionDate = normalizeDate(request.inspectionDate(), "inspectionDate");
    BigDecimal sourceFullScore = amountOrDefault(
        request.fullScore(), InspectionScoringRules.MAX_SCORE);
    if (sourceFullScore.compareTo(InspectionScoringRules.MAX_SCORE) != 0) {
      throw new BusinessException(
          "INSPECTION_SCORE_SCALE_INVALID",
          "巡检满分必须为200分；旧分制数据只能通过历史迁移折算",
          HttpStatus.BAD_REQUEST
      );
    }
    BigDecimal sourceScore = amountOrDefault(request.score(), sourceFullScore);
    validateScore(sourceFullScore, sourceScore);
    BigDecimal score = InspectionScoringRules.normalizeScore(sourceScore, sourceFullScore);
    BigDecimal materialScore = InspectionScoringRules.normalizeCategoryScore(
        request.materialScore(), sourceFullScore);
    BigDecimal hygieneScore = InspectionScoringRules.normalizeCategoryScore(
        request.hygieneScore(), sourceFullScore);
    BigDecimal serviceScore = InspectionScoringRules.normalizeCategoryScore(
        request.serviceScore(), sourceFullScore);
    boolean redLineHit = !inferPassed(request.redlinesJson())
        || "RED_LINE_FAILED".equalsIgnoreCase(request.resultCode());
    String resultCode = InspectionScoringRules.resultCode(score, redLineHit);
    return new InspectionRecordRequest(
        storeId,
        inspectionDate,
        request.inspector(),
        request.brand(),
        InspectionScoringRules.MAX_SCORE,
        score,
        "PASSED".equals(resultCode),
        normalizeJsonArrayText(request.deductionsJson()),
        normalizeJsonArrayText(request.redlinesJson()),
        normalizeJsonArrayText(request.photosJson()),
        request.note(),
        request.standardVersionId(),
        request.standardVersion(),
        materialScore,
        hygieneScore,
        serviceScore,
        resultCode,
        request.itemResults()
    );
  }

  private InspectionRecordResponse requireRecord(long tenantId, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "Inspection record id is required");
    return recordRepository.record(tenantId, targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "Inspection record not found", HttpStatus.NOT_FOUND));
  }

  private void requireInspectionRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInspectionRead(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.INSPECTION_READ,
        "No permission to read inspection records"
    );
  }

  private void requireInspectionManage(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInspectionManage(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.INSPECTION_MANAGE,
        "No permission to edit inspection records"
    );
  }

  private void requireLegacyPermission(AuthUser user, String permissionCode, String message) {
    if (AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(permissionCode)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
  }

  private void requireInspectionStoreAccess(AuthUser user, String storeId, String action) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.INSPECTION, storeId, action);
      return;
    }
    if (isStoreManager(user) && !requireManagerStore(user).equals(storeId)) {
      throw new BusinessException(
          "FORBIDDEN",
          "Store manager can only access own store inspection records",
          HttpStatus.FORBIDDEN
      );
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

  private Map<String, Object> enrichDetectionSuggestion(
      Map<String, Object> source,
      BigDecimal sourceFullScore,
      List<InspectionStandardItemResponse> standards
  ) {
    Map<String, Object> result = new LinkedHashMap<>(source);
    result.remove("annotated_image");
    result.remove("annotatedImage");
    result.remove("original_image");
    result.remove("originalImage");

    List<Map<String, Object>> detections = deduplicateDetections(value(source, "detections"));
    result.put("detections", detections);
    result.put("detection_count", detections.size());
    result.put("detectionCount", detections.size());

    InspectionStandardItemResponse clause = matchDetectionClause(source, detections, standards).orElse(null);
    // The recognition model's evidence is always interpreted by server rules.
    // Client/model deduction numbers and a client-provided score scale never
    // determine the persisted score.
    BigDecimal sourceScale = InspectionScoringRules.LEGACY_MAX_SCORE;
    BigDecimal standardDeduction = serverDetectionDeduction(clause);
    BigDecimal legacyDeduction = standardDeduction
        .multiply(InspectionScoringRules.LEGACY_MAX_SCORE)
        .divide(InspectionScoringRules.MAX_SCORE, 2, RoundingMode.HALF_UP);
    BigDecimal convertedDeduction = standardDeduction;
    BigDecimal clauseDeduction = clause == null
        ? ZERO_AMOUNT
        : amountOrDefault(clause.suggestedScore(), ZERO_AMOUNT).abs()
            .setScale(2, RoundingMode.HALF_UP);
    BigDecimal scaleAdjustmentDeduction = standardDeduction.subtract(clauseDeduction)
        .max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
    String deductionPolicyVersion = clause != null && "H-4.1.2".equalsIgnoreCase(clause.code())
        ? H_4_1_2_DEDUCTION_POLICY
        : ACTIVE_CLAUSE_DEDUCTION_POLICY;
    BigDecimal confidence = maximumConfidence(detections);
    String detectionKey = detectionKey(source, detections);
    String issueCode = detectionIssueCode(detections);
    String issueName = firstNonBlank(
        textValue(source, "deduction_content", "deductionContent"),
        firstNonBlank(textValue(source, "auto_status", "autoStatus"), "模型识别到疑似现场问题")
    );

    result.put("detectionKey", detectionKey);
    result.put("imageId", textValue(source, "image_id", "imageId"));
    result.put("scoreScale", sourceScale.setScale(2, RoundingMode.HALF_UP));
    result.put("persistedScoreScale", InspectionScoringRules.MAX_SCORE);
    result.put("legacyDeduction", legacyDeduction);
    result.put("convertedDeduction200", convertedDeduction);
    result.put("standardDeduction", standardDeduction);
    result.put("clauseDeduction", clauseDeduction);
    result.put("scaleAdjustmentDeduction", scaleAdjustmentDeduction);
    result.put("deductionPolicyVersion", deductionPolicyVersion);
    result.put("suggestedDeduction", convertedDeduction);
    result.put("finalDeduction", standardDeduction);
    result.put("confirmedDeduction", standardDeduction);
    result.put("confidence", confidence);
    result.put("issueCode", issueCode);
    result.put("issueName", issueName);
    result.put("decisionStatus", clause == null ? DETECTION_UNMATCHED : DETECTION_PENDING);
    result.put("revision", 0L);
    // Compatibility: the old Vue preview reads this field. It now contains the
    // server-resolved clause score, never the model/browser-provided final score.
    result.put("deduction_score", standardDeduction);
    if (clause != null) {
      result.put("clauseId", clause.id());
      result.put("clauseCode", clause.code());
      result.put("clauseTitle", clause.title());
    }
    return result;
  }

  private BigDecimal serverDetectionDeduction(InspectionStandardItemResponse clause) {
    if (clause == null) {
      return ZERO_AMOUNT;
    }
    if ("H-4.1.2".equalsIgnoreCase(clause.code())) {
      return H_4_1_2_SERVER_DEDUCTION.setScale(2, RoundingMode.HALF_UP);
    }
    return amountOrDefault(clause.suggestedScore(), ZERO_AMOUNT).abs()
        .setScale(2, RoundingMode.HALF_UP);
  }

  private Optional<InspectionStandardItemResponse> matchDetectionClause(
      Map<String, Object> source,
      List<Map<String, Object>> detections,
      List<InspectionStandardItemResponse> standards
  ) {
    Long explicitId = longValue(source, "standard_item_id", "standardItemId", "clause_id", "clauseId");
    if (explicitId != null) {
      Optional<InspectionStandardItemResponse> verified = standards.stream()
          .filter(item -> item.id() == explicitId && item.enabled())
          .findFirst();
      if (verified.isPresent()) {
        return verified;
      }
    }
    String explicitCode = textValue(source, "standard_code", "standardCode", "clause_code", "clauseCode");
    if (explicitCode != null) {
      Optional<InspectionStandardItemResponse> verified = standards.stream()
          .filter(item -> explicitCode.equalsIgnoreCase(item.code()) && item.enabled())
          .findFirst();
      if (verified.isPresent()) {
        return verified;
      }
    }

    Set<String> classes = detections.stream()
        .map(item -> textValue(item, "class_name", "className", "label"))
        .filter(Objects::nonNull)
        .map(value -> value.toLowerCase(java.util.Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
    if (classes.stream().anyMatch(value -> Set.of(
        "paper_scrap", "paper", "stain", "floor_litter", "corner_dust").contains(value))) {
      Optional<InspectionStandardItemResponse> floor = standards.stream()
          .filter(item -> item.enabled() && "H-4.1.2".equalsIgnoreCase(item.code()))
          .findFirst();
      if (floor.isPresent()) {
        return floor;
      }
    }

    String project = normalizeMatchText(textValue(
        source, "deduction_project", "deductionProject", "project", "suggested_project"));
    String issue = normalizeMatchText(textValue(
        source, "deduction_content", "deductionContent", "issue", "suggested_issue"));
    return standards.stream()
        .filter(InspectionStandardItemResponse::enabled)
        .map(item -> Map.entry(item, detectionClauseMatchScore(item, project, issue, classes)))
        .filter(entry -> entry.getValue() >= 20)
        .sorted(Comparator
            .<Map.Entry<InspectionStandardItemResponse, Integer>>comparingInt(Map.Entry::getValue)
            .reversed()
            .thenComparingInt(entry -> entry.getKey().sortOrder()))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private int detectionClauseMatchScore(
      InspectionStandardItemResponse item,
      String project,
      String issue,
      Set<String> classes
  ) {
    String title = normalizeMatchText(item.title());
    String description = normalizeMatchText(item.description());
    String method = normalizeMatchText(item.checkMethod());
    int score = 0;
    if (!project.isBlank() && project.equals(title)) {
      score += 100;
    } else if (!project.isBlank() && !title.isBlank()
        && (project.contains(title) || title.contains(project))) {
      score += 45;
    }
    if (!issue.isBlank() && !title.isBlank() && issue.contains(title)) {
      score += 35;
    }
    if (!issue.isBlank() && (!description.isBlank() && description.contains(issue)
        || !method.isBlank() && method.contains(issue))) {
      score += 25;
    }
    if (!classes.isEmpty() && "HYGIENE".equals(category(item.dimension()))) {
      score += 5;
    }
    return score;
  }

  private String normalizeMatchText(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^\\p{IsHan}a-z0-9]+", "")
        .replace("检查标准", "")
        .replace("标准", "");
  }

  private List<Map<String, Object>> deduplicateDetections(Object rawDetections) {
    if (!(rawDetections instanceof Collection<?> collection)) {
      return List.of();
    }
    List<Map<String, Object>> unique = new ArrayList<>();
    for (Object value : collection) {
      if (!(value instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> candidate = new LinkedHashMap<>();
      raw.forEach((key, item) -> candidate.put(String.valueOf(key), item));
      int duplicateIndex = duplicateDetectionIndex(unique, candidate);
      if (duplicateIndex < 0) {
        unique.add(candidate);
      } else if (confidence(candidate).compareTo(confidence(unique.get(duplicateIndex))) > 0) {
        unique.set(duplicateIndex, candidate);
      }
    }
    unique.sort(Comparator.comparing(this::detectionCanonicalValue));
    return List.copyOf(unique);
  }

  private int duplicateDetectionIndex(List<Map<String, Object>> values, Map<String, Object> candidate) {
    for (int index = 0; index < values.size(); index++) {
      Map<String, Object> existing = values.get(index);
      if (!Objects.equals(
          normalizeMatchText(textValue(existing, "class_name", "className", "label")),
          normalizeMatchText(textValue(candidate, "class_name", "className", "label")))) {
        continue;
      }
      double[] first = detectionBox(existing);
      double[] second = detectionBox(candidate);
      if (first != null && second != null && intersectionOverUnion(first, second) >= DETECTION_IOU_THRESHOLD.doubleValue()) {
        return index;
      }
      if (first == null && second == null
          && detectionCanonicalValue(existing).equals(detectionCanonicalValue(candidate))) {
        return index;
      }
    }
    return -1;
  }

  private double[] detectionBox(Map<String, Object> detection) {
    Object raw = value(detection, "box_xyxy", "boxXyxy", "bbox", "box");
    if (!(raw instanceof List<?> values) || values.size() < 4) {
      return null;
    }
    double[] box = new double[4];
    for (int index = 0; index < 4; index++) {
      Object coordinate = values.get(index);
      if (!(coordinate instanceof Number number)) {
        try {
          box[index] = Double.parseDouble(String.valueOf(coordinate));
        } catch (NumberFormatException ex) {
          return null;
        }
      } else {
        box[index] = number.doubleValue();
      }
    }
    return box;
  }

  private double intersectionOverUnion(double[] first, double[] second) {
    double x1 = Math.max(first[0], second[0]);
    double y1 = Math.max(first[1], second[1]);
    double x2 = Math.min(first[2], second[2]);
    double y2 = Math.min(first[3], second[3]);
    double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
    double firstArea = Math.max(0, first[2] - first[0]) * Math.max(0, first[3] - first[1]);
    double secondArea = Math.max(0, second[2] - second[0]) * Math.max(0, second[3] - second[1]);
    double union = firstArea + secondArea - intersection;
    return union <= 0 ? 0 : intersection / union;
  }

  private BigDecimal maximumConfidence(List<Map<String, Object>> detections) {
    return detections.stream()
        .map(this::confidence)
        .max(BigDecimal::compareTo)
        .orElse(ZERO_AMOUNT)
        .setScale(4, RoundingMode.HALF_UP);
  }

  private BigDecimal confidence(Map<String, Object> detection) {
    BigDecimal value = decimalValue(detection, "confidence", "score");
    return value == null ? ZERO_AMOUNT : value.max(BigDecimal.ZERO);
  }

  private String detectionIssueCode(List<Map<String, Object>> detections) {
    return detections.stream()
        .map(item -> textValue(item, "class_name", "className", "label"))
        .filter(Objects::nonNull)
        .map(value -> value.toUpperCase(java.util.Locale.ROOT))
        .distinct()
        .sorted()
        .collect(java.util.stream.Collectors.joining("+"));
  }

  private String detectionKey(Map<String, Object> source, List<Map<String, Object>> detections) {
    String canonical = String.join("|",
        Objects.toString(textValue(source, "image_id", "imageId"), ""),
        Objects.toString(textValue(source, "filename", "fileName"), ""),
        detections.stream().map(this::detectionCanonicalValue).sorted()
            .collect(java.util.stream.Collectors.joining(";"))
    );
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return "det-" + HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private String detectionCanonicalValue(Map<String, Object> detection) {
    double[] box = detectionBox(detection);
    String coordinates = box == null ? "" : java.util.Arrays.stream(box)
        .map(value -> Math.rint(value * 1000d) / 1000d)
        .mapToObj(value -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
        .collect(java.util.stream.Collectors.joining(","));
    return String.join(":",
        normalizeMatchText(textValue(detection, "class_name", "className", "label")),
        coordinates,
        Objects.toString(textValue(detection, "source"), ""),
        Objects.toString(booleanValue(detection, "on_floor", "onFloor"), "")
    );
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

  private Long longValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
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
