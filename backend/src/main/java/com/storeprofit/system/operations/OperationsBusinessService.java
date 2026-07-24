package com.storeprofit.system.operations;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.StoreBusinessGuard;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAnswerResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingLearningRecordResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingMaterialResponse;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsBusinessService {
  private final OperationsBusinessRepository repository;
  private final AccessControlService accessControl;
  private final StoreBusinessGuard storeBusinessGuard;

  @Autowired
  public OperationsBusinessService(
      OperationsBusinessRepository repository,
      AccessControlService accessControl,
      StoreBusinessGuard storeBusinessGuard
  ) {
    this.repository = repository;
    this.accessControl = accessControl;
    this.storeBusinessGuard = storeBusinessGuard;
  }

  public OperationsBusinessService(
      OperationsBusinessRepository repository,
      AccessControlService accessControl
  ) {
    this(repository, accessControl, null);
  }

  /** Compatibility constructor retained for isolated service tests. */
  public OperationsBusinessService(OperationsBusinessRepository repository) {
    this(repository, null, null);
  }

  public List<InventoryCheckResponse> inventoryChecks(AuthUser user) {
    requireInventoryRead(user);
    if (accessControl != null) {
      DataScope scope = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
      return scope.allowsAllStores()
          ? repository.inventoryChecks(user.tenantId(), null)
          : repository.inventoryChecks(user.tenantId(), null, Set.copyOf(scope.storeIds()));
    }
    return repository.inventoryChecks(user.tenantId(), scopedStoreId(user));
  }

  public InventoryCheckResponse inventoryCheck(AuthUser user, long id) {
    requireInventoryRead(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "查看盘存单");
    return check;
  }

  @Transactional
  public InventoryCheckResponse saveInventoryCheck(AuthUser user, InventoryCheckRequest request) {
    requireInventorySave(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "请填写盘存单", HttpStatus.BAD_REQUEST);
    }
    String storeId = normalizeStoreForWrite(user, request.storeId());
    if (request.id() == null && storeBusinessGuard != null) {
      storeBusinessGuard.requireActive(user, storeId, "盘存单");
    }
    String storeName = repository.storeName(user.tenantId(), storeId)
        .orElseThrow(() -> new BusinessException("STORE_NOT_FOUND", "门店不存在", HttpStatus.BAD_REQUEST));
    String checkDate = normalizeDate(request.checkDate());
    List<InventoryCheckLineRequest> lines = request.lines() == null ? List.of() : request.lines();
    if (lines.isEmpty()) {
      throw new BusinessException("LINES_REQUIRED", "请至少填写一条盘存明细", HttpStatus.BAD_REQUEST);
    }
    for (InventoryCheckLineRequest line : lines) {
      if (line.itemName() == null || line.itemName().isBlank()) {
        throw new BusinessException("ITEM_NAME_REQUIRED", "请填写盘存物品名称", HttpStatus.BAD_REQUEST);
      }
      if (amount(line.countedQuantity()).compareTo(BigDecimal.ZERO) < 0) {
        throw new BusinessException("BAD_QUANTITY", "盘存数量不能小于 0", HttpStatus.BAD_REQUEST);
      }
    }
    if (request.id() != null) {
      InventoryCheckResponse existing = requireInventoryCheck(user.tenantId(), request.id());
      requireDomainStoreScope(
          user, DataScopeDomains.WAREHOUSE, existing.storeId(), "修改盘存单");
      if (!"DRAFT".equals(existing.status())) {
        throw new BusinessException("BAD_STATUS", "只有草稿盘存单可以修改", HttpStatus.CONFLICT);
      }
    }
    BigDecimal total = lines.stream()
        .map(line -> amount(line.countedQuantity()).multiply(amount(line.unitPrice())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    long id = repository.saveInventoryCheck(
        user.tenantId(),
        request.id(),
        request.id() == null ? newInventoryCheckNo() : requireInventoryCheck(user.tenantId(), request.id()).checkNo(),
        storeId,
        storeName,
        checkDate,
        request.note(),
        total,
        user.id(),
        lines
    );
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "保存盘存单", "store_inventory_check", String.valueOf(id), storeId, "Vue3 运营中心");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse submitInventoryCheck(AuthUser user, long id) {
    requireInventorySave(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "提交盘存单");
    if (!repository.updateInventoryCheckStatus(user.tenantId(), id, "DRAFT", "SUBMITTED", user.id())) {
      throw new BusinessException("BAD_STATUS", "只有草稿盘存单可以提交", HttpStatus.CONFLICT);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "提交盘存单", "store_inventory_check", String.valueOf(id), check.storeId(), "等待运营或财务复核");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse reviewInventoryCheck(AuthUser user, long id) {
    requireInventoryReview(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "复核盘存单");
    if (!repository.updateInventoryCheckStatus(user.tenantId(), id, "SUBMITTED", "REVIEWED", user.id())) {
      throw new BusinessException("BAD_STATUS", "只有已提交盘存单可以复核", HttpStatus.CONFLICT);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "复核盘存单", "store_inventory_check", String.valueOf(id), check.storeId(), "盘存单已复核");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse cancelInventoryCheck(AuthUser user, long id) {
    requireInventorySave(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "作废盘存单");
    if (!repository.updateInventoryCheckStatus(user.tenantId(), id, "DRAFT", "CANCELLED", user.id())) {
      throw new BusinessException("BAD_STATUS", "已复核盘存单不能作废", HttpStatus.CONFLICT);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "作废盘存单", "store_inventory_check", String.valueOf(id), check.storeId(), "盘存单已作废");
    return requireInventoryCheck(user.tenantId(), id);
  }

  public List<ExamPaperResponse> examPapers(AuthUser user) {
    requireExamManage(user);
    requireExamScope(user, "管理考试试卷");
    return repository.examPapers(user.tenantId());
  }

  public ExamPaperResponse examPaper(AuthUser user, long paperId) {
    requireExamManage(user);
    requireExamScope(user, "管理考试试卷");
    return repository.examPaper(user.tenantId(), paperId, true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  public List<ExamAttemptResponse> examAttempts(AuthUser user) {
    requireExamReport(user);
    if (accessControl != null) {
      DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
      if (DataScopeModes.SELF.equals(scope.mode())) {
        return repository.examAttempts(user.tenantId(), null, user.id());
      }
      return scope.allowsAllStores()
          ? repository.examAttempts(user.tenantId(), null, null)
          : repository.examAttempts(user.tenantId(), null, null, Set.copyOf(scope.storeIds()));
    }
    if ("STORE_MANAGER".equals(user.role())) {
      return repository.examAttempts(user.tenantId(), requiredStore(user), null);
    }
    if ("EMPLOYEE".equals(user.role())) {
      return repository.examAttempts(user.tenantId(), null, user.id());
    }
    return repository.examAttempts(user.tenantId(), null, null);
  }

  public ExamAttemptResponse examAttempt(AuthUser user, long attemptId) {
    requireExamReport(user);
    ExamAttemptResponse attempt = repository.examAttempt(user.tenantId(), attemptId)
        .orElseThrow(() -> new BusinessException("ATTEMPT_NOT_FOUND", "考试记录不存在", HttpStatus.NOT_FOUND));
    DataScope examScope = accessControl == null
        ? null
        : accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (examScope != null && DataScopeModes.SELF.equals(examScope.mode())) {
      if (!Long.valueOf(user.id()).equals(attempt.submittedBy())) {
        throw new BusinessException("FORBIDDEN", "只能查看自己的考试成绩", HttpStatus.FORBIDDEN);
      }
    } else {
      requireDomainStoreScope(user, DataScopeDomains.EXAM, attempt.storeId(), "查看考试成绩");
    }
    if (accessControl == null && "EMPLOYEE".equals(user.role())
        && !Long.valueOf(user.id()).equals(attempt.submittedBy())) {
      throw new BusinessException("FORBIDDEN", "员工只能查看自己的考试成绩", HttpStatus.FORBIDDEN);
    }
    return attempt;
  }

  public List<TrainingMaterialResponse> trainingMaterials(AuthUser user) {
    requireExamLearn(user);
    requireExamScope(user, "查看培训资料");
    return repository.trainingMaterials(user.tenantId(), user.id());
  }

  @Transactional
  public List<TrainingMaterialResponse> markMaterialLearned(AuthUser user, long materialId) {
    requireExamLearn(user);
    requireExamScope(user, "记录培训学习进度");
    if (!repository.materialExists(user.tenantId(), materialId)) {
      throw new BusinessException("MATERIAL_NOT_FOUND", "培训资料不存在", HttpStatus.NOT_FOUND);
    }
    repository.markMaterialLearned(user.tenantId(), materialId, user.id(), user.displayName(), user.storeId());
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "标记培训已学习", "training_material", String.valueOf(materialId), user.storeId(), "培训学习记录已落库");
    return trainingMaterials(user);
  }

  public List<TrainingLearningRecordResponse> learningRecords(AuthUser user) {
    requireExamReport(user);
    if (accessControl != null) {
      DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
      if (DataScopeModes.SELF.equals(scope.mode())) {
        return repository.learningRecords(user.tenantId(), null, null, user.id());
      }
      return scope.allowsAllStores()
          ? repository.learningRecords(user.tenantId(), null)
          : repository.learningRecords(user.tenantId(), null, Set.copyOf(scope.storeIds()));
    }
    return repository.learningRecords(user.tenantId(), scopedStoreId(user));
  }

  private InventoryCheckResponse requireInventoryCheck(long tenantId, long id) {
    return repository.inventoryCheck(tenantId, id)
        .orElseThrow(() -> new BusinessException("INVENTORY_CHECK_NOT_FOUND", "盘存单不存在", HttpStatus.NOT_FOUND));
  }

  private String normalizeStoreForWrite(AuthUser user, String requestedStoreId) {
    if (accessControl != null) {
      String storeId = requestedStoreId == null || requestedStoreId.isBlank()
          ? ("STORE_MANAGER".equals(user.role()) ? requiredStore(user) : null)
          : requestedStoreId.trim();
      if (storeId == null) {
        throw new BusinessException("STORE_REQUIRED", "请选择门店", HttpStatus.BAD_REQUEST);
      }
      requireDomainStoreScope(user, DataScopeDomains.WAREHOUSE, storeId, "保存盘存单");
      return storeId;
    }
    if ("STORE_MANAGER".equals(user.role())) {
      return requiredStore(user);
    }
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      throw new BusinessException("STORE_REQUIRED", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    return requestedStoreId.trim();
  }

  private String scopedStoreId(AuthUser user) {
    return "STORE_MANAGER".equals(user.role()) ? requiredStore(user) : null;
  }

  private void requireDomainStoreScope(
      AuthUser user,
      String domain,
      String storeId,
      String action
  ) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, domain, storeId, action);
      return;
    }
    if ("STORE_MANAGER".equals(user.role()) && !requiredStore(user).equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "店长只能访问本门店数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireExamScope(AuthUser user, String action) {
    if (accessControl == null) {
      return;
    }
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (scope.allowsAllStores()
        || DataScopeModes.SELF.equals(scope.mode())
        || !scope.storeIds().isEmpty()) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有培训考试数据范围", HttpStatus.FORBIDDEN);
  }

  private String requiredStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "当前账号未绑定门店", HttpStatus.FORBIDDEN);
    }
    return user.storeId();
  }

  private void requireInventoryRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requirePermission(user, PermissionCodes.INVENTORY_READ, "查看门店盘存单");
      return;
    }
    requireLegacyPermission(user, PermissionCodes.INVENTORY_READ, "无权查看盘存单");
  }

  private void requireInventorySave(AuthUser user) {
    if (accessControl != null) {
      accessControl.requirePermission(user, PermissionCodes.INVENTORY_MANAGE, "保存门店盘存单");
      return;
    }
    requireLegacyPermission(user, PermissionCodes.INVENTORY_MANAGE, "无权保存盘存单");
  }

  private void requireInventoryReview(AuthUser user) {
    if (accessControl != null) {
      accessControl.requirePermission(user, PermissionCodes.INVENTORY_REVIEW, "复核门店盘存单");
      return;
    }
    requireLegacyPermission(user, PermissionCodes.INVENTORY_REVIEW, "无权复核盘存单");
  }

  private void requireExamManage(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExamManage(user);
      return;
    }
    requireLegacyPermission(user, PermissionCodes.EXAM_MANAGE, "无权管理考试系统");
  }

  private void requireExamLearn(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExamRead(user);
      return;
    }
    requireLegacyPermission(user, PermissionCodes.EXAM_LEARN, "无权访问培训资料");
  }

  private void requireExamReport(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExamCompanyRead(user);
      return;
    }
    requireLegacyPermission(user, PermissionCodes.EXAM_REPORT, "无权查看培训考试报表");
  }

  private void requireLegacyPermission(AuthUser user, String permissionCode, String message) {
    if (AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(permissionCode)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
  }

  private String normalizeDate(String value) {
    if (value == null || value.isBlank()) {
      return LocalDate.now().toString();
    }
    try {
      return LocalDate.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_DATE", "日期格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private String newInventoryCheckNo() {
    String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    return "PDC" + LocalDate.now().toString().replace("-", "") + shortId;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
