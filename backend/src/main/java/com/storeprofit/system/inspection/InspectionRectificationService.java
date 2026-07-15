package com.storeprofit.system.inspection;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.inspection.InspectionRectificationRepository.InspectionRectificationAction;
import com.storeprofit.system.inspection.InspectionRectificationRepository.InspectionRectificationRecord;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.storage.StorageUploadResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dedicated rectification and review lifecycle for an existing inspection.
 *
 * <p>The service never writes inspection_record, inspection_record_standard_snapshot, score,
 * deduction, result code, or standard version.  Evidence is stored through the existing
 * authenticated attachment boundary and is only associated with this workflow row.</p>
 */
@Service
public class InspectionRectificationService {
  private static final int MAX_NOTE_LENGTH = 4_000;

  private final AccessControlService accessControl;
  private final InspectionRecordRepository inspectionRecordRepository;
  private final InspectionRectificationRepository rectificationRepository;
  private final StorageService storageService;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public InspectionRectificationService(
      AccessControlService accessControl,
      InspectionRecordRepository inspectionRecordRepository,
      InspectionRectificationRepository rectificationRepository,
      StorageService storageService,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.accessControl = accessControl;
    this.inspectionRecordRepository = inspectionRecordRepository;
    this.rectificationRepository = rectificationRepository;
    this.storageService = storageService;
    this.businessScopeResolver = businessScopeResolver;
  }

  /** Compatibility constructor for focused unit tests. */
  public InspectionRectificationService(
      AccessControlService accessControl,
      InspectionRecordRepository inspectionRecordRepository,
      InspectionRectificationRepository rectificationRepository,
      StorageService storageService
  ) {
    this(accessControl, inspectionRecordRepository, rectificationRepository, storageService, null);
  }

  public List<InspectionRectificationResponse> mine(AuthUser user) {
    requireSubmitActor(user, "查看巡检整改待办");
    String storeId = resolvedSubmitStore(user, null, "查看巡检整改待办");
    List<InspectionRecordResponse> records = inspectionRecordRepository.records(
        user.tenantId(), null, null, null, storeId, null);
    List<InspectionRectificationResponse> result = new ArrayList<>();
    for (InspectionRecordResponse record : records) {
      if (!requiresRectification(record)) {
        continue;
      }
      InspectionRectificationRecord rectification = rectificationRepository
          .find(user.tenantId(), record.id())
          .orElse(null);
      result.add(response(record, rectification));
    }
    return List.copyOf(result);
  }

  @Transactional
  public InspectionRectificationEvidenceResponse uploadEvidence(
      AuthUser user,
      String inspectionRecordId,
      MultipartFile file
  ) {
    InspectionRecordResponse record = requireSubmitRecord(user, inspectionRecordId, "上传巡检整改证据");
    inspectionRecordRepository.lockRecord(user.tenantId(), record.id());
    InspectionRectificationRecord rectification = ensureRectification(user, record);
    requireEvidenceEditable(rectification);

    StorageUploadResponse uploaded = storageService.uploadInspectionRectificationEvidence(
        user, file, rectification.id(), record.storeId());
    if (uploaded.id() == null || uploaded.id() <= 0) {
      throw new BusinessException(
          "RECTIFICATION_EVIDENCE_UPLOAD_FAILED", "整改证据上传失败，请重新选择图片", HttpStatus.BAD_GATEWAY);
    }
    String reason = "已上传整改证据：" + truncate(uploaded.fileName(), 160);
    saveAction(user, rectification, "EVIDENCE_UPLOADED", rectification.status(), reason);
    rectificationRepository.logOperation(
        user.tenantId(), user.id(), user.displayName(), "inspection_rectification_evidence_upload",
        record.id(), record.storeId(), record.inspectionDate(), reason);
    return new InspectionRectificationEvidenceResponse(
        uploaded.id(), uploaded.fileName(), uploaded.contentType(), uploaded.fileSize(),
        "/api/storage/attachments/" + uploaded.id());
  }

  @Transactional
  public InspectionRectificationResponse submit(
      AuthUser user,
      String inspectionRecordId,
      InspectionRectificationSubmitRequest request
  ) {
    InspectionRecordResponse record = requireSubmitRecord(user, inspectionRecordId, "提交巡检整改");
    inspectionRecordRepository.lockRecord(user.tenantId(), record.id());
    InspectionRectificationRecord rectification = ensureRectification(user, record);
    requireEvidenceEditable(rectification);
    String note = requireNote(request == null ? null : request.note(), "请填写整改说明");
    List<Long> attachmentIds = normalizeAttachmentIds(request == null ? null : request.attachmentIds());
    if (attachmentIds.isEmpty()) {
      throw new BusinessException(
          "RECTIFICATION_EVIDENCE_REQUIRED", "请先上传至少一张整改现场证据再提交", HttpStatus.BAD_REQUEST);
    }
    if (!rectificationRepository.ownsEvidenceAttachments(
        user.tenantId(), rectification.id(), record.storeId(), attachmentIds)) {
      throw new BusinessException(
          "RECTIFICATION_EVIDENCE_SCOPE_INVALID",
          "整改证据必须是当前巡检整改流程中已上传的附件", HttpStatus.FORBIDDEN);
    }
    if (!rectificationRepository.submit(
        user.tenantId(), record.id(), note, user.id(), user.displayName())) {
      throw stateConflict("整改状态已变化，请刷新后重试");
    }
    InspectionRectificationRecord submitted = rectificationRepository.findForUpdate(user.tenantId(), record.id())
        .orElseThrow(() -> new IllegalStateException("Inspection rectification disappeared after submission"));
    saveAction(user, submitted, "SUBMITTED", InspectionRectificationStatus.PENDING_REVIEW, note);
    rectificationRepository.logOperation(
        user.tenantId(), user.id(), user.displayName(), "inspection_rectification_submit",
        record.id(), record.storeId(), record.inspectionDate(), truncate(note, 255));
    return response(record, submitted);
  }

  public List<InspectionRectificationResponse> reviewQueue(AuthUser user) {
    requireReviewActor(user, "查看巡检整改复核队列");
    boolean allStores = AccessControlService.isBoss(user)
        || accessControl.hasAllDataScope(user, DataScopeDomains.INSPECTION);
    List<String> allowedStoreIds = allStores
        ? List.of()
        : accessControl.allowedStoreIds(user, DataScopeDomains.INSPECTION).stream()
            .filter(value -> value != null && !value.isBlank() && !"all".equalsIgnoreCase(value))
            .sorted()
            .toList();
    return rectificationRepository.pendingReview(user.tenantId(), allStores, allowedStoreIds).stream()
        .map(rectification -> reviewQueueResponse(user, rectification))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Transactional
  public InspectionRectificationResponse review(
      AuthUser user,
      String inspectionRecordId,
      InspectionRectificationReviewRequest request
  ) {
    requireReviewActor(user, "复核巡检整改");
    InspectionRecordResponse record = requireRecord(inspectionRecordId, user);
    requireReviewScope(user, record, "复核巡检整改");
    inspectionRecordRepository.lockRecord(user.tenantId(), record.id());
    InspectionRectificationRecord rectification = rectificationRepository.findForUpdate(user.tenantId(), record.id())
        .orElseThrow(() -> new BusinessException(
            "RECTIFICATION_NOT_SUBMITTED", "店长尚未提交整改，暂不能复核", HttpStatus.CONFLICT));
    if (rectification.status() != InspectionRectificationStatus.PENDING_REVIEW) {
      throw stateConflict("当前整改不在待复核状态，请刷新后重试");
    }
    InspectionRectificationStatus decision = reviewDecision(request == null ? null : request.decision());
    String note = requireNote(request == null ? null : request.note(), "请填写复核备注");
    if (!rectificationRepository.review(
        user.tenantId(), record.id(), decision, note, user.id(), user.displayName())) {
      throw stateConflict("整改状态已变化，请刷新后重试");
    }
    InspectionRectificationRecord reviewed = rectificationRepository.findForUpdate(user.tenantId(), record.id())
        .orElseThrow(() -> new IllegalStateException("Inspection rectification disappeared after review"));
    String action = decision == InspectionRectificationStatus.APPROVED ? "APPROVED" : "REJECTED";
    saveAction(user, reviewed, action, decision, note);
    rectificationRepository.logOperation(
        user.tenantId(), user.id(), user.displayName(),
        decision == InspectionRectificationStatus.APPROVED
            ? "inspection_rectification_review_approved"
            : "inspection_rectification_review_rejected",
        record.id(), record.storeId(), record.inspectionDate(), truncate(note, 255));
    return response(record, reviewed);
  }

  private InspectionRectificationResponse reviewQueueResponse(
      AuthUser user,
      InspectionRectificationRecord rectification
  ) {
    InspectionRecordResponse record = inspectionRecordRepository
        .record(user.tenantId(), rectification.inspectionRecordId())
        .orElse(null);
    if (record == null) {
      return null;
    }
    requireReviewScope(user, record, "查看巡检整改复核队列");
    return response(record, rectification);
  }

  private InspectionRecordResponse requireSubmitRecord(AuthUser user, String id, String action) {
    requireSubmitActor(user, action);
    InspectionRecordResponse record = requireRecord(id, user);
    resolvedSubmitStore(user, record.storeId(), action);
    ensureRectificationRequired(record);
    return record;
  }

  private InspectionRecordResponse requireRecord(String id, AuthUser user) {
    String normalizedId = requireText(id, "RECTIFICATION_RECORD_REQUIRED", "巡检记录不能为空");
    return inspectionRecordRepository.record(user.tenantId(), normalizedId)
        .orElseThrow(() -> new BusinessException(
            "INSPECTION_NOT_FOUND", "巡检记录不存在或不属于当前企业", HttpStatus.NOT_FOUND));
  }

  private void requireSubmitActor(AuthUser user, String action) {
    accessControl.requireInspectionRead(user);
    accessControl.requireInspectionRectificationSubmit(user);
  }

  private void requireReviewActor(AuthUser user, String action) {
    accessControl.requireInspectionRectificationReview(user);
  }

  private String resolvedSubmitStore(AuthUser user, String requestedStoreId, String action) {
    if (AccessControlService.isBoss(user)) {
      if (requestedStoreId != null && !requestedStoreId.isBlank()) {
        accessControl.requireStoreAccess(user, DataScopeDomains.INSPECTION, requestedStoreId, action);
      }
      return requestedStoreId == null || requestedStoreId.isBlank() ? null : requestedStoreId.trim();
    }
    if (businessScopeResolver != null) {
      return businessScopeResolver.resolve(
          user, DataScopeDomains.INSPECTION, requestedStoreId, null, action).storeId();
    }
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      String boundStoreId = user == null ? null : user.storeId();
      if (boundStoreId == null || boundStoreId.isBlank()) {
        throw new BusinessException("NO_STORE_SCOPE", "店长账号未绑定门店", HttpStatus.FORBIDDEN);
      }
      return boundStoreId.trim();
    }
    accessControl.requireStoreAccess(user, DataScopeDomains.INSPECTION, requestedStoreId, action);
    return requestedStoreId.trim();
  }

  private void requireReviewScope(AuthUser user, InspectionRecordResponse record, String action) {
    accessControl.requireStoreAccess(user, DataScopeDomains.INSPECTION, record.storeId(), action);
  }

  private InspectionRectificationRecord ensureRectification(AuthUser user, InspectionRecordResponse record) {
    return rectificationRepository.findForUpdate(user.tenantId(), record.id())
        .orElseGet(() -> rectificationRepository.create(
            user.tenantId(), "inspection-rectification-" + UUID.randomUUID(), record.id(), record.storeId()));
  }

  private void ensureRectificationRequired(InspectionRecordResponse record) {
    if (!requiresRectification(record)) {
      throw new BusinessException(
          "RECTIFICATION_NOT_REQUIRED", "该巡检记录已合格，无需提交整改", HttpStatus.CONFLICT);
    }
  }

  private boolean requiresRectification(InspectionRecordResponse record) {
    return record != null && (!record.displayPassed() || record.redLineCount() > 0);
  }

  private void requireEvidenceEditable(InspectionRectificationRecord rectification) {
    if (rectification.status() == InspectionRectificationStatus.PENDING_REVIEW) {
      throw stateConflict("整改已提交运营复核，暂不能继续修改证据");
    }
    if (rectification.status() == InspectionRectificationStatus.APPROVED) {
      throw stateConflict("整改已通过复核，不能再次修改");
    }
  }

  private InspectionRectificationStatus reviewDecision(String value) {
    String normalized = requireText(value, "RECTIFICATION_REVIEW_DECISION_REQUIRED", "请选择复核结论")
        .toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "APPROVED" -> InspectionRectificationStatus.APPROVED;
      case "REJECTED" -> InspectionRectificationStatus.REJECTED;
      default -> throw new BusinessException(
          "RECTIFICATION_REVIEW_DECISION_INVALID", "复核结论只能是通过或驳回", HttpStatus.BAD_REQUEST);
    };
  }

  private List<Long> normalizeAttachmentIds(List<Long> attachmentIds) {
    if (attachmentIds == null || attachmentIds.isEmpty()) {
      return List.of();
    }
    Set<Long> values = new LinkedHashSet<>();
    for (Long id : attachmentIds) {
      if (id == null || id <= 0) {
        throw new BusinessException(
            "RECTIFICATION_ATTACHMENT_ID_INVALID", "整改证据编号不正确", HttpStatus.BAD_REQUEST);
      }
      values.add(id);
    }
    return List.copyOf(values);
  }

  private InspectionRectificationResponse response(
      InspectionRecordResponse record,
      InspectionRectificationRecord rectification
  ) {
    InspectionRectificationStatus status = rectification == null
        ? InspectionRectificationStatus.PENDING_SUBMISSION
        : rectification.status();
    List<Long> attachmentIds = rectification == null
        ? List.of()
        : rectificationRepository.evidenceAttachmentIds(
            record == null ? 0L : rectification.tenantId(), rectification.id(), record.storeId());
    return new InspectionRectificationResponse(
        record.id(),
        record.storeId(),
        record.storeName(),
        record.inspectionDate(),
        status.name(),
        status.label(),
        requirement(record),
        attachmentIds,
        rectification == null ? null : rectification.managerNote(),
        rectification == null ? null : rectification.reviewNote(),
        rectification == null ? null : rectification.updatedAt());
  }

  private String requirement(InspectionRecordResponse record) {
    List<String> items = record.itemResults().stream()
        .filter(InspectionItemResultResponse::issueFound)
        .map(item -> firstNonBlank(item.deductionReason(), item.title(), item.description()))
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .limit(4)
        .toList();
    if (!items.isEmpty()) {
      return String.join("；", items);
    }
    String note = record.note();
    if (note != null && !note.isBlank()) {
      return truncate(note, 500);
    }
    return "请根据巡检问题完成整改，并上传整改后的现场图片。";
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private void saveAction(
      AuthUser user,
      InspectionRectificationRecord rectification,
      String action,
      InspectionRectificationStatus status,
      String note
  ) {
    rectificationRepository.saveAction(new InspectionRectificationAction(
        "inspection-rectification-action-" + UUID.randomUUID(),
        user.tenantId(),
        rectification.id(),
        rectification.inspectionRecordId(),
        action,
        status,
        truncate(note, MAX_NOTE_LENGTH),
        user.id(),
        user.displayName(),
        user.role()));
  }

  private String requireNote(String value, String message) {
    String normalized = requireText(value, "RECTIFICATION_NOTE_REQUIRED", message);
    if (normalized.length() > MAX_NOTE_LENGTH) {
      throw new BusinessException("RECTIFICATION_NOTE_TOO_LONG", "整改或复核备注不能超过 4000 个字符", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private BusinessException stateConflict(String message) {
    return new BusinessException("RECTIFICATION_STATE_CONFLICT", message, HttpStatus.CONFLICT);
  }

  private String truncate(String value, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
  }
}
