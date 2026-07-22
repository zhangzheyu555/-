package com.storeprofit.system.employeeassistant;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human handoff workflow. It persists only the sanitizer's redacted question, never chat history. */
@Service
public class EmployeeAssistantHandoffService {
  private static final int HANDOFF_TTL_DAYS = 3;
  private final EmployeeAssistantKnowledgeRepository repository;
  private final EmployeeAssistantService employeeAssistantService;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public EmployeeAssistantHandoffService(
      EmployeeAssistantKnowledgeRepository repository,
      EmployeeAssistantService employeeAssistantService,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this.repository = repository;
    this.employeeAssistantService = employeeAssistantService;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  @Transactional
  public EmployeeAssistantHandoffResponse create(AuthUser user, EmployeeAssistantHandoffCreateRequest request) {
    accessControl.requireEmployeeAssistantUse(user);
    if (request == null) {
      throw badRequest("EMPLOYEE_ASSISTANT_HANDOFF_REQUEST_REQUIRED", "请填写需要转人工处理的问题");
    }
    String question = employeeAssistantService.sanitizeForHandoff(request.question());
    String storeId = resolveStore(user, request.storeId());
    String id = "EA-HO-" + UUID.randomUUID();
    repository.insertHandoff(user.tenantId(), id, storeId, question,
        employeeAssistantService.handoffCategory(question), user.id(), LocalDateTime.now().plusDays(HANDOFF_TTL_DAYS));
    audit(user, "employee_assistant.handoff_create", id, storeId, "已创建人工转接事项");
    return response(required(user.tenantId(), id));
  }

  @Transactional(readOnly = true)
  public List<EmployeeAssistantHandoffResponse> mine(AuthUser user) {
    accessControl.requireEmployeeAssistantUse(user);
    return repository.listHandoffs(user.tenantId()).stream()
        .filter(row -> row.requestedBy() == user.id())
        .map(this::response)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<EmployeeAssistantHandoffResponse> manageList(AuthUser user) {
    requireManager(user, "查看员工助手人工事项");
    return repository.listHandoffs(user.tenantId()).stream()
        .filter(row -> canManageRecord(user, row))
        .map(this::response)
        .toList();
  }

  @Transactional
  public EmployeeAssistantHandoffResponse claim(AuthUser user, String id) {
    requireManager(user, "领取员工助手人工事项");
    EmployeeAssistantKnowledgeRepository.HandoffRow row = managedRecord(user, id, "领取员工助手人工事项");
    if (repository.claimHandoff(user.tenantId(), row.id(), user.id()) == 0) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_HANDOFF_CONFLICT", "该人工事项已被处理或已过期，请刷新后重试", HttpStatus.CONFLICT);
    }
    audit(user, "employee_assistant.handoff_claim", row.id(), row.storeId(), "已领取人工事项");
    return response(required(user.tenantId(), row.id()));
  }

  @Transactional
  public EmployeeAssistantHandoffResponse reply(AuthUser user, String id, EmployeeAssistantHandoffReplyRequest request) {
    requireManager(user, "回复员工助手人工事项");
    EmployeeAssistantKnowledgeRepository.HandoffRow row = managedRecord(user, id, "回复员工助手人工事项");
    requireOwner(user, row);
    String resolution = resolution(request);
    if (repository.replyHandoff(user.tenantId(), row.id(), user.id(), resolution) == 0) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_HANDOFF_CONFLICT", "该人工事项当前不能回复，请刷新后重试", HttpStatus.CONFLICT);
    }
    audit(user, "employee_assistant.handoff_reply", row.id(), row.storeId(), "已回复人工事项");
    return response(required(user.tenantId(), row.id()));
  }

  @Transactional
  public EmployeeAssistantHandoffResponse close(AuthUser user, String id, EmployeeAssistantHandoffReplyRequest request) {
    requireManager(user, "关闭员工助手人工事项");
    EmployeeAssistantKnowledgeRepository.HandoffRow row = managedRecord(user, id, "关闭员工助手人工事项");
    requireOwner(user, row);
    String resolution = resolution(request);
    if (repository.closeHandoff(user.tenantId(), row.id(), user.id(), resolution) == 0) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_HANDOFF_CONFLICT", "该人工事项当前不能关闭，请刷新后重试", HttpStatus.CONFLICT);
    }
    audit(user, "employee_assistant.handoff_close", row.id(), row.storeId(), "已关闭人工事项");
    return response(required(user.tenantId(), row.id()));
  }

  @Transactional
  public void feedback(AuthUser user, EmployeeAssistantFeedbackRequest request) {
    accessControl.requireEmployeeAssistantUse(user);
    if (request == null) {
      throw badRequest("EMPLOYEE_ASSISTANT_FEEDBACK_REQUIRED", "请选择反馈结果");
    }
    EmployeeAssistantAnswerSource source;
    try {
      source = EmployeeAssistantAnswerSource.valueOf(normalized(request.answerSource()).toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw badRequest("EMPLOYEE_ASSISTANT_FEEDBACK_SOURCE_INVALID", "反馈来源不正确");
    }
    Long knowledgeId = request.knowledgeId();
    Integer knowledgeVersion = request.knowledgeVersion();
    if (source == EmployeeAssistantAnswerSource.KNOWLEDGE) {
      if (knowledgeId == null || knowledgeId <= 0 || knowledgeVersion == null || knowledgeVersion <= 0
          || repository.findVersion(user.tenantId(), knowledgeId, knowledgeVersion).isEmpty()) {
        throw badRequest("EMPLOYEE_ASSISTANT_FEEDBACK_KNOWLEDGE_INVALID", "知识版本不存在，无法提交反馈");
      }
    } else {
      knowledgeId = null;
      knowledgeVersion = null;
    }
    String reason = optional(request.reasonCode(), 64, "反馈原因不能超过64个字符");
    repository.insertFeedback(user.tenantId(), source.name(), knowledgeId, knowledgeVersion, request.helpful(), reason, user.id());
    audit(user, "employee_assistant.feedback", null, null, "已提交" + source.name() + "反馈");
  }

  private void requireManager(AuthUser user, String action) {
    accessControl.requireEmployeeAssistantHandoffManage(user);
  }

  private EmployeeAssistantKnowledgeRepository.HandoffRow managedRecord(AuthUser user, String id, String action) {
    EmployeeAssistantKnowledgeRepository.HandoffRow row = required(user.tenantId(), id);
    if (!canManageRecord(user, row)) {
      auditRepository.writePermissionDenied(user, action, "employee_assistant_handoff", row.id(), row.storeId(),
          "人工事项不在当前门店数据范围内");
      throw new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
    }
    return row;
  }

  private boolean canManageRecord(AuthUser user, EmployeeAssistantKnowledgeRepository.HandoffRow row) {
    if (AccessControlService.isBoss(user)) {
      return true;
    }
    return row.storeId() != null && !row.storeId().isBlank()
        && accessControl.canAccessStore(user, DataScopeDomains.INSPECTION, row.storeId());
  }

  private void requireOwner(AuthUser user, EmployeeAssistantKnowledgeRepository.HandoffRow row) {
    if (row.handledBy() != null && row.handledBy() == user.id()) {
      return;
    }
    auditRepository.writePermissionDenied(user, "处理未领取人工事项", "employee_assistant_handoff", row.id(), row.storeId(),
        "仅领取人可以回复或关闭人工事项");
    throw new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
  }

  private EmployeeAssistantKnowledgeRepository.HandoffRow required(long tenantId, String id) {
    String normalized = normalized(id);
    if (normalized.isBlank()) {
      throw badRequest("EMPLOYEE_ASSISTANT_HANDOFF_ID_INVALID", "人工事项编号不正确");
    }
    return repository.findHandoff(tenantId, normalized)
        .orElseThrow(() -> new BusinessException("EMPLOYEE_ASSISTANT_HANDOFF_NOT_FOUND", "人工事项不存在", HttpStatus.NOT_FOUND));
  }

  private String resolveStore(AuthUser user, String requestedStoreId) {
    String storeId = optional(requestedStoreId, 64, "门店编号不正确");
    if (storeId == null && user.storeId() != null && !user.storeId().isBlank()) {
      storeId = user.storeId().trim();
    }
    if (storeId != null) {
      if (isEmployee(user)) {
        String ownStoreId = normalized(user.storeId());
        if (!storeId.equals(ownStoreId)) {
          auditRepository.writePermissionDenied(user, "创建员工助手人工事项", "employee_assistant_handoff", null,
              storeId, "员工只能为本人所属门店创建人工事项");
          throw new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
        }
        return storeId;
      }
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, storeId, "创建员工助手人工事项");
    }
    if (storeId == null) {
      throw badRequest("EMPLOYEE_ASSISTANT_HANDOFF_STORE_REQUIRED", "请选择所属门店后再转人工处理");
    }
    return storeId;
  }

  /** Employees do not receive the management STORE scope, but may hand off their own-store issue. */
  private boolean isEmployee(AuthUser user) {
    return user != null && "EMPLOYEE".equalsIgnoreCase(normalized(user.role()));
  }

  private String resolution(EmployeeAssistantHandoffReplyRequest request) {
    if (request == null) {
      throw badRequest("EMPLOYEE_ASSISTANT_HANDOFF_RESOLUTION_REQUIRED", "请填写处理结论");
    }
    String value = optional(request.resolution(), 2000, "处理结论不能超过2000个字符");
    if (value == null) {
      throw badRequest("EMPLOYEE_ASSISTANT_HANDOFF_RESOLUTION_REQUIRED", "请填写处理结论");
    }
    return value;
  }

  private String optional(String value, int maxLength, String message) {
    String normalized = normalized(value);
    if (normalized.isBlank()) return null;
    if (normalized.length() > maxLength) throw badRequest("EMPLOYEE_ASSISTANT_HANDOFF_TEXT_TOO_LONG", message);
    return normalized;
  }

  private String normalized(String value) {
    return value == null ? "" : value.trim();
  }

  private EmployeeAssistantHandoffResponse response(EmployeeAssistantKnowledgeRepository.HandoffRow row) {
    return new EmployeeAssistantHandoffResponse(row.id(), row.storeId(), row.question(), row.category(), row.status(),
        row.requestedBy(), row.requestedByName(), row.handledBy(), row.handledByName(), row.resolution(),
        row.createdAt(), row.claimedAt(), row.respondedAt(), row.closedAt(), row.expiresAt());
  }

  private BusinessException badRequest(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private void audit(AuthUser user, String action, String id, String storeId, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(action, "employee_assistant_handoff", id, storeId,
        null, reason, null, null));
  }
}
