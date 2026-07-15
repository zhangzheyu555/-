package com.storeprofit.system.employeeassistant;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BOSS-only management of tenant-local, publishable employee service knowledge. */
@Service
public class EmployeeAssistantKnowledgeService {
  private final EmployeeAssistantKnowledgeRepository repository;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public EmployeeAssistantKnowledgeService(
      EmployeeAssistantKnowledgeRepository repository,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this.repository = repository;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  @Transactional(readOnly = true)
  public List<EmployeeAssistantKnowledgeResponse> list(AuthUser user) {
    requireManager(user, "查看员工助手知识库");
    return repository.listKnowledge(user.tenantId()).stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<EmployeeAssistantKnowledgeVersionResponse> versions(AuthUser user, long id) {
    requireManager(user, "查看员工助手知识版本");
    requiredKnowledge(user, id);
    return repository.listVersions(user.tenantId(), id).stream().map(this::toVersionResponse).toList();
  }

  @Transactional
  public EmployeeAssistantKnowledgeResponse createDraft(AuthUser user, EmployeeAssistantKnowledgeDraftRequest request) {
    requireManager(user, "新建员工助手知识草稿");
    EmployeeAssistantKnowledgeDraftRequest normalized = normalize(request);
    try {
      long id = repository.insertDraft(user.tenantId(), normalized, user.id());
      audit(user, "employee_assistant.knowledge_create", id, "已创建知识草稿");
      return toResponse(requiredKnowledge(user, id));
    } catch (DataIntegrityViolationException ex) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_DUPLICATE", "同一租户下知识标题不能重复", HttpStatus.CONFLICT);
    }
  }

  @Transactional
  public EmployeeAssistantKnowledgeResponse updateDraft(
      AuthUser user,
      long id,
      EmployeeAssistantKnowledgeDraftRequest request
  ) {
    requireManager(user, "编辑员工助手知识草稿");
    EmployeeAssistantKnowledgeDraftRequest normalized = normalize(request);
    EmployeeAssistantKnowledgeRepository.KnowledgeRow current = requiredKnowledge(user, id);
    if (!"DRAFT".equals(current.status())) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_PUBLISHED_IMMUTABLE",
          "已发布知识不能直接修改，请新建草稿后发布", HttpStatus.CONFLICT);
    }
    try {
      if (repository.updateDraft(user.tenantId(), id, normalized, user.id()) == 0) {
        throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_CONFLICT", "知识草稿已发生变化，请刷新后重试", HttpStatus.CONFLICT);
      }
      audit(user, "employee_assistant.knowledge_update", id, "已更新知识草稿");
      return toResponse(requiredKnowledge(user, id));
    } catch (DataIntegrityViolationException ex) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_DUPLICATE", "同一租户下知识标题不能重复", HttpStatus.CONFLICT);
    }
  }

  @Transactional
  public EmployeeAssistantKnowledgeResponse publish(AuthUser user, long id) {
    requireManager(user, "发布员工助手知识");
    EmployeeAssistantKnowledgeRepository.KnowledgeRow draft = requiredKnowledge(user, id);
    if (!"DRAFT".equals(draft.status())) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_NOT_DRAFT", "仅知识草稿可以发布", HttpStatus.CONFLICT);
    }
    EmployeeAssistantService.requireSafeKnowledgeContent(
        draft.category(), draft.title(), draft.keywords(), draft.standardAnswer());
    int version = draft.currentVersion() + 1;
    if (repository.publish(user.tenantId(), id, version, user.id()) == 0) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_CONFLICT", "知识草稿已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    EmployeeAssistantKnowledgeRepository.KnowledgeRow published = requiredKnowledge(user, id);
    repository.insertVersion(user.tenantId(), published, version, "PUBLISH", user.id());
    audit(user, "employee_assistant.knowledge_publish", id, "已发布知识版本 " + version);
    return toResponse(published);
  }

  @Transactional
  public EmployeeAssistantKnowledgeResponse rollback(AuthUser user, long id, int version) {
    requireManager(user, "回滚员工助手知识");
    EmployeeAssistantKnowledgeRepository.KnowledgeRow current = requiredKnowledge(user, id);
    if (version <= 0) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_VERSION_INVALID", "知识版本不正确", HttpStatus.BAD_REQUEST);
    }
    EmployeeAssistantKnowledgeRepository.KnowledgeVersionRow snapshot = repository
        .findVersion(user.tenantId(), id, version)
        .orElseThrow(() -> new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在", HttpStatus.NOT_FOUND));
    EmployeeAssistantService.requireSafeKnowledgeContent(
        snapshot.category(), snapshot.title(), snapshot.keywords(), snapshot.standardAnswer());
    int nextVersion = current.currentVersion() + 1;
    repository.restorePublished(user.tenantId(), id, snapshot, nextVersion, user.id());
    EmployeeAssistantKnowledgeRepository.KnowledgeRow restored = requiredKnowledge(user, id);
    repository.insertVersion(user.tenantId(), restored, nextVersion, "ROLLBACK", user.id());
    audit(user, "employee_assistant.knowledge_rollback", id, "已回滚并发布知识版本 " + nextVersion);
    return toResponse(restored);
  }

  private void requireManager(AuthUser user, String action) {
    accessControl.requireEmployeeAssistantKnowledgeManage(user);
  }

  private EmployeeAssistantKnowledgeRepository.KnowledgeRow requiredKnowledge(AuthUser user, long id) {
    if (id <= 0) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_ID_INVALID", "知识编号不正确", HttpStatus.BAD_REQUEST);
    }
    return repository.findKnowledge(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_NOT_FOUND", "知识不存在", HttpStatus.NOT_FOUND));
  }

  private EmployeeAssistantKnowledgeDraftRequest normalize(EmployeeAssistantKnowledgeDraftRequest request) {
    if (request == null) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_REQUEST_REQUIRED", "请完整填写知识内容", HttpStatus.BAD_REQUEST);
    }
    String category = required(request.category(), "请选择知识分类", 64).toUpperCase(Locale.ROOT);
    String title = required(request.title(), "请填写知识标题", 160);
    String keywords = required(request.keywords(), "请填写至少两个匹配关键词", 1000);
    String answer = required(request.standardAnswer(), "请填写标准答复", 4000);
    EmployeeAssistantService.requireSafeKnowledgeContent(category, title, keywords, answer);
    if (keywordCount(keywords) < 2) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_KEYWORDS_INVALID", "请至少填写两个匹配关键词", HttpStatus.BAD_REQUEST);
    }
    return new EmployeeAssistantKnowledgeDraftRequest(category, title, keywords, answer);
  }

  private int keywordCount(String value) {
    return (int) java.util.Arrays.stream(value.split("[,，;；\\s]+"))
        .map(String::trim).filter(item -> item.length() >= 2).count();
  }

  private String required(String value, String message, int maximumLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_TEXT_REQUIRED", message, HttpStatus.BAD_REQUEST);
    }
    if (normalized.length() > maximumLength) {
      throw new BusinessException("EMPLOYEE_ASSISTANT_KNOWLEDGE_TEXT_TOO_LONG", message + "不能超过" + maximumLength + "个字符", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private EmployeeAssistantKnowledgeResponse toResponse(EmployeeAssistantKnowledgeRepository.KnowledgeRow row) {
    return new EmployeeAssistantKnowledgeResponse(row.id(), row.category(), row.title(), row.keywords(),
        row.standardAnswer(), row.status(), row.currentVersion(), row.createdBy(), row.updatedBy(), row.createdAt(),
        row.updatedAt());
  }

  private EmployeeAssistantKnowledgeVersionResponse toVersionResponse(
      EmployeeAssistantKnowledgeRepository.KnowledgeVersionRow row
  ) {
    return new EmployeeAssistantKnowledgeVersionResponse(row.id(), row.knowledgeId(), row.version(), row.category(),
        row.title(), row.keywords(), row.standardAnswer(), row.publishAction(), row.publishedBy(), row.publishedAt());
  }

  private void audit(AuthUser user, String action, long id, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(action, "employee_assistant_knowledge", Long.toString(id),
        null, null, reason, null, null));
  }
}
