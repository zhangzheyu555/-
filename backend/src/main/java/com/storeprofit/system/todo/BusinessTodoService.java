package com.storeprofit.system.todo;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.BusinessTodoRepository.BusinessTodoRow;
import com.storeprofit.system.todo.BusinessTodoRepository.MissingProfitRow;
import com.storeprofit.system.todo.BusinessTodoRepository.PendingExpenseRow;
import com.storeprofit.system.todo.BusinessTodoRepository.PendingSalaryRow;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionHistory;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionRecord;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoAttachmentContent;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoAttachmentRecord;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoAttachmentSummary;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoOperationLogRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessTodoService {
  private static final String RULE_DATA_MISSING = "PROFIT_DATA_MISSING";
  private static final String RULE_LOSS = "STORE_LOSS";
  private static final String RULE_COST_RATIO = "COST_RATIO_HIGH";
  private static final String RULE_REVENUE_DECLINE = "REVENUE_DECLINE";
  private static final String RULE_EXPENSE_REVIEW = "EXPENSE_PENDING_REVIEW";
  private static final String RULE_SALARY_REVIEW = "SALARY_PENDING_REVIEW";
  private static final Set<String> GLOBAL_TODO_ROLES = Set.of("ADMIN", "BOSS", "OWNER");
  private static final Set<String> ALLOWED_ATTACHMENT_TYPES = Set.of(
      "image/jpeg", "image/png", "image/webp", "application/pdf");

  private final FinanceRepository financeRepository;
  private final BusinessTodoRepository businessTodoRepository;
  private final RoleTodoActionRepository actionRepository;
  private final AccessControlService accessControl;
  private final BigDecimal costRatioThreshold;
  private final BigDecimal revenueDeclineThreshold;

  public BusinessTodoService(
      FinanceRepository financeRepository,
      BusinessTodoRepository businessTodoRepository,
      RoleTodoActionRepository actionRepository,
      AccessControlService accessControl,
      @Value("${app.exception.cost-ratio-threshold:0.45}") BigDecimal costRatioThreshold,
      @Value("${app.exception.revenue-decline-threshold:0.20}") BigDecimal revenueDeclineThreshold
  ) {
    this.financeRepository = financeRepository;
    this.businessTodoRepository = businessTodoRepository;
    this.actionRepository = actionRepository;
    this.accessControl = accessControl;
    this.costRatioThreshold = normalizeThreshold(costRatioThreshold, new BigDecimal("0.45"));
    this.revenueDeclineThreshold = normalizeThreshold(revenueDeclineThreshold, new BigDecimal("0.20"));
  }

  public List<BusinessTodoResponse> list(AuthUser user, String status) {
    String normalizedStatus = status == null || status.isBlank() ? null : BusinessTodoStatus.parse(status).name();
    return businessTodoRepository.list(user.tenantId(), normalizedStatus, 300).stream()
        .filter(row -> canRead(user, row))
        .map(row -> response(user, row, false))
        .toList();
  }

  public BusinessTodoResponse detail(AuthUser user, String id) {
    BusinessTodoRow row = requireVisible(user, id);
    return response(user, row, true);
  }

  @Transactional
  public BusinessTodoResponse transition(AuthUser user, String id, BusinessTodoTransitionRequest request) {
    BusinessTodoRow current = requireVisible(user, id);
    BusinessTodoStatus from = BusinessTodoStatus.parse(current.status());
    if (from.terminal()) {
      throw new BusinessException("TODO_ALREADY_CLOSED", "已完成或已驳回的待办不能再次修改", HttpStatus.CONFLICT);
    }
    BusinessTodoStatus target = BusinessTodoStatus.parse(request == null ? null : request.status());
    requireAllowedTransition(user, current, from, target);
    String note = requireText(request == null ? null : request.note(), "TODO_NOTE_REQUIRED", "请填写处理备注");
    boolean updated = businessTodoRepository.updateStatus(
        user.tenantId(), current.id(), from, target, user.id(), user.displayName());
    if (!updated) {
      throw new BusinessException("TODO_STATE_CONFLICT", "待办状态已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    String actionId = saveAction(user, current, target, "MANUAL_" + target.name(), note,
        request == null ? List.of() : request.attachments());
    return detail(user, current.id());
  }

  @Transactional
  public BusinessTodoReconcileResponse reconcileMonth(AuthUser user, String month) {
    accessControl.requireFinanceWrite(user);
    if ("STORE_MANAGER".equals(normalizeRole(user.role()))) {
      throw new BusinessException("FORBIDDEN", "店长只能触发本门店异常复核", HttpStatus.FORBIDDEN);
    }
    String targetMonth = normalizeMonth(month);
    reconcile(user.tenantId(), targetMonth, null);
    return new BusinessTodoReconcileResponse(targetMonth, list(user, null).stream()
        .filter(item -> targetMonth.equals(item.month()) && !BusinessTodoStatus.COMPLETED.name().equals(item.status())
            && !BusinessTodoStatus.REJECTED.name().equals(item.status()))
        .count());
  }

  @Transactional
  public void reconcileStore(AuthUser user, String storeId, String month) {
    accessControl.requireFinanceWrite(user);
    accessControl.requireStoreAccess(user, storeId, "复核经营异常");
    String targetMonth = normalizeMonth(month);
    reconcile(user.tenantId(), targetMonth, storeId);
  }

  @Transactional
  public void reconcileMonthAfterFinanceSave(AuthUser user, String month) {
    if ("STORE_MANAGER".equals(normalizeRole(user.role()))) {
      String storeId = user.storeId();
      if (storeId == null || storeId.isBlank()) {
        return;
      }
      reconcileStore(user, storeId, month);
      return;
    }
    reconcileMonth(user, month);
  }

  @Transactional
  public void reconcileAfterFinanceMutation(AuthUser user, String month) {
    reconcileMonthAfterFinanceSave(user, month);
  }

  @Transactional
  public void reconcileSystemMonth(long tenantId, String month) {
    reconcile(tenantId, normalizeMonth(month), null);
  }

  public DownloadedTodoAttachment attachment(AuthUser user, String todoId, String attachmentId) {
    BusinessTodoRow row = requireVisible(user, todoId);
    RoleTodoAttachmentContent file = actionRepository.attachment(user.tenantId(), todoId, attachmentId)
        .orElseThrow(() -> new BusinessException("TODO_ATTACHMENT_NOT_FOUND", "未找到待办附件", HttpStatus.NOT_FOUND));
    actionRepository.saveOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "下载待办附件",
        "business_todo_attachment",
        attachmentId,
        row.storeId(),
        row.month(),
        truncate(file.fileName())
    ));
    return new DownloadedTodoAttachment(file.fileName(), file.contentType(), file.content());
  }

  private void reconcile(long tenantId, String month, String onlyStoreId) {
    List<ProfitEntryResponse> entries = financeRepository.entries(tenantId, month, null, onlyStoreId);
    List<BusinessTodoDraft> missing = onlyStoreId == null
        ? missingProfitDrafts(tenantId, month)
        : entries.isEmpty() ? missingProfitDrafts(tenantId, month).stream()
            .filter(draft -> onlyStoreId.equals(draft.storeId()))
            .toList() : List.of();
    reconcileRule(tenantId, RULE_DATA_MISSING, month, missing);
    reconcileRule(tenantId, RULE_LOSS, month, lossDrafts(entries));
    reconcileRule(tenantId, RULE_COST_RATIO, month, costRatioDrafts(entries));
    reconcileRule(tenantId, RULE_REVENUE_DECLINE, month, revenueDeclineDrafts(tenantId, entries));
    if (onlyStoreId == null) {
      reconcileRule(tenantId, RULE_EXPENSE_REVIEW, month, pendingExpenseDrafts(tenantId, month));
      reconcileRule(tenantId, RULE_SALARY_REVIEW, month, pendingSalaryDrafts(tenantId, month));
    } else {
      List<BusinessTodoDraft> scopedExpenses = pendingExpenseDrafts(tenantId, month).stream()
          .filter(draft -> onlyStoreId.equals(draft.storeId()))
          .toList();
      reconcileRule(tenantId, RULE_EXPENSE_REVIEW, month, scopedExpenses, onlyStoreId);
      List<BusinessTodoDraft> scopedSalaries = pendingSalaryDrafts(tenantId, month).stream()
          .filter(draft -> onlyStoreId.equals(draft.storeId()))
          .toList();
      reconcileRule(tenantId, RULE_SALARY_REVIEW, month, scopedSalaries, onlyStoreId);
    }
  }

  private void reconcileRule(long tenantId, String ruleCode, String month, List<BusinessTodoDraft> drafts) {
    reconcileRule(tenantId, ruleCode, month, drafts, null);
  }

  private void reconcileRule(
      long tenantId,
      String ruleCode,
      String month,
      List<BusinessTodoDraft> drafts,
      String onlyStoreId
  ) {
    Set<String> activeSourceKeys = new HashSet<>();
    for (BusinessTodoDraft draft : drafts) {
      activeSourceKeys.add(draft.sourceKey());
      Optional<BusinessTodoRow> before = businessTodoRepository.latestByRuleAndSource(tenantId, ruleCode, draft.sourceKey());
      BusinessTodoRow row = businessTodoRepository.openOrRefresh(tenantId, draft);
      if (before.isEmpty() || !before.get().conditionActive()) {
        saveSystemAction(tenantId, row, BusinessTodoStatus.PENDING, "SYSTEM_CREATE", "系统识别到新的经营异常");
      }
    }
    for (BusinessTodoRow row : businessTodoRepository.activeByRuleAndMonth(tenantId, ruleCode, month)) {
      if (onlyStoreId != null && !onlyStoreId.equals(row.storeId())) {
        continue;
      }
      if (activeSourceKeys.contains(row.sourceKey()) || !businessTodoRepository.markConditionInactive(tenantId, row.id())) {
        continue;
      }
      BusinessTodoStatus status = BusinessTodoStatus.parse(row.status());
      if (!status.terminal() && businessTodoRepository.updateStatus(
          tenantId, row.id(), status, BusinessTodoStatus.COMPLETED, null, "系统")) {
        saveSystemAction(tenantId, row, BusinessTodoStatus.COMPLETED, "SYSTEM_RESOLVED", "相关业务数据已恢复正常，系统自动完成待办");
      }
    }
  }

  private List<BusinessTodoDraft> missingProfitDrafts(long tenantId, String month) {
    return businessTodoRepository.storesWithoutProfit(tenantId, month).stream()
        .map(row -> missingProfitDraft(row, month))
        .toList();
  }

  private BusinessTodoDraft missingProfitDraft(MissingProfitRow row, String month) {
    return new BusinessTodoDraft(
        RULE_DATA_MISSING,
        "profit_entry",
        row.storeId() + "|" + month,
        "profit-missing:" + row.storeId() + ":" + month,
        "经营数据缺失：" + displayStore(row.storeName(), row.storeId()),
        month + " 尚未录入营业额、成本和费用数据，请先完成本店经营数据录入。",
        "STORE_MANAGER",
        "FINANCE",
        row.storeId(),
        month,
        2,
        null
    );
  }

  private List<BusinessTodoDraft> lossDrafts(List<ProfitEntryResponse> entries) {
    return entries.stream()
        .filter(entry -> entry.net().compareTo(BigDecimal.ZERO) < 0)
        .map(entry -> new BusinessTodoDraft(
            RULE_LOSS,
            "profit_entry",
            entry.storeId() + "|" + entry.month(),
            "profit-loss:" + entry.storeId() + ":" + entry.month(),
            "门店亏损：" + displayStore(entry.storeName(), entry.storeId()),
            entry.month() + " 净利润为 " + money(entry.net()) + "，请核对成本、费用和营业额后提交整改说明。",
            "STORE_MANAGER",
            "FINANCE",
            entry.storeId(),
            entry.month(),
            3,
            null
        ))
        .toList();
  }

  private List<BusinessTodoDraft> costRatioDrafts(List<ProfitEntryResponse> entries) {
    return entries.stream()
        .filter(entry -> entry.income().compareTo(BigDecimal.ZERO) > 0)
        .filter(entry -> ratio(entry.costSum(), entry.income()).compareTo(costRatioThreshold) > 0)
        .map(entry -> new BusinessTodoDraft(
            RULE_COST_RATIO,
            "profit_entry",
            entry.storeId() + "|" + entry.month(),
            "profit-cost-ratio:" + entry.storeId() + ":" + entry.month(),
            "成本占比偏高：" + displayStore(entry.storeName(), entry.storeId()),
            entry.month() + " 成本占比 " + percentage(ratio(entry.costSum(), entry.income()))
                + "，超过阈值 " + percentage(costRatioThreshold) + "，请核对食材、包材和损耗。",
            "STORE_MANAGER",
            "FINANCE",
            entry.storeId(),
            entry.month(),
            2,
            null
        ))
        .toList();
  }

  private List<BusinessTodoDraft> revenueDeclineDrafts(long tenantId, List<ProfitEntryResponse> entries) {
    List<BusinessTodoDraft> rows = new ArrayList<>();
    for (ProfitEntryResponse entry : entries) {
      YearMonth targetMonth = YearMonth.parse(entry.month());
      Optional<ProfitEntryResponse> previous = financeRepository.entry(
          tenantId, entry.storeId(), targetMonth.minusMonths(1).toString());
      if (previous.isEmpty() || previous.get().income().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal decrease = ratio(previous.get().income().subtract(entry.income()), previous.get().income());
      if (decrease.compareTo(revenueDeclineThreshold) < 0) {
        continue;
      }
      rows.add(new BusinessTodoDraft(
          RULE_REVENUE_DECLINE,
          "profit_entry",
          entry.storeId() + "|" + entry.month(),
          "profit-revenue-decline:" + entry.storeId() + ":" + entry.month(),
          "营业额明显下降：" + displayStore(entry.storeName(), entry.storeId()),
          entry.month() + " 营业额较上月下降 " + percentage(decrease)
              + "，请核对订单、平台实收和营销费用。",
          "STORE_MANAGER",
          "FINANCE",
          entry.storeId(),
          entry.month(),
          2,
          null
      ));
    }
    return rows;
  }

  private List<BusinessTodoDraft> pendingExpenseDrafts(long tenantId, String month) {
    return businessTodoRepository.pendingExpenses(tenantId, month).stream()
        .map(this::pendingExpenseDraft)
        .toList();
  }

  private BusinessTodoDraft pendingExpenseDraft(PendingExpenseRow row) {
    return new BusinessTodoDraft(
        RULE_EXPENSE_REVIEW,
        "expense_claim",
        row.id(),
        "expense-review:" + row.id(),
        "报销等待审核：" + displayStore(row.storeName(), row.storeId()),
        "报销金额 " + money(row.amount()) + "，当前状态“" + safeText(row.status(), "待审核") + "”，请进入报销栏审核。",
        "FINANCE",
        null,
        row.storeId(),
        row.month(),
        2,
        null
    );
  }

  private List<BusinessTodoDraft> pendingSalaryDrafts(long tenantId, String month) {
    return businessTodoRepository.pendingSalaries(tenantId, month).stream()
        .map(this::pendingSalaryDraft)
        .toList();
  }

  private BusinessTodoDraft pendingSalaryDraft(PendingSalaryRow row) {
    return new BusinessTodoDraft(
        RULE_SALARY_REVIEW,
        "salary_record",
        row.id(),
        "salary-review:" + row.id(),
        "工资等待审核：" + displayStore(row.storeName(), row.storeId()),
        row.employeeName() + " " + row.month() + " 工资 " + money(row.gross()) + "，请进入员工工资审核。",
        "BOSS",
        "BOSS",
        row.storeId(),
        row.month(),
        2,
        null
    );
  }

  private BusinessTodoRow requireVisible(AuthUser user, String id) {
    String normalizedId = requireText(id, "TODO_ID_REQUIRED", "待办编号不能为空");
    BusinessTodoRow row = businessTodoRepository.findById(user.tenantId(), normalizedId)
        .orElseThrow(() -> new BusinessException("TODO_NOT_FOUND", "未找到待办", HttpStatus.NOT_FOUND));
    if (!canRead(user, row)) {
      throw new BusinessException("FORBIDDEN", "当前账号没有访问该待办的权限", HttpStatus.FORBIDDEN);
    }
    return row;
  }

  private boolean canRead(AuthUser user, BusinessTodoRow row) {
    String role = normalizeRole(user.role());
    if (GLOBAL_TODO_ROLES.contains(role)) {
      return true;
    }
    if (row.storeId() != null && !row.storeId().isBlank() && !accessControl.canAccessStore(user, row.storeId())) {
      return false;
    }
    return role.equals(normalizeRole(row.assigneeRole())) || role.equals(normalizeRole(row.reviewRole()));
  }

  private void requireAllowedTransition(
      AuthUser user,
      BusinessTodoRow row,
      BusinessTodoStatus from,
      BusinessTodoStatus target
  ) {
    String role = normalizeRole(user.role());
    boolean global = GLOBAL_TODO_ROLES.contains(role);
    boolean owner = role.equals(normalizeRole(row.assigneeRole()));
    boolean reviewer = role.equals(normalizeRole(row.reviewRole()));
    boolean allowed = switch (from) {
      case PENDING -> target == BusinessTodoStatus.IN_PROGRESS && (global || owner)
          || target == BusinessTodoStatus.REJECTED && (global || owner || reviewer);
      case IN_PROGRESS -> target == BusinessTodoStatus.PENDING_REVIEW && (global || owner)
          || target == BusinessTodoStatus.REJECTED && (global || owner || reviewer);
      case PENDING_REVIEW -> (target == BusinessTodoStatus.COMPLETED || target == BusinessTodoStatus.REJECTED)
          && (global || reviewer);
      case COMPLETED, REJECTED -> false;
    };
    if (!allowed) {
      throw new BusinessException("TODO_TRANSITION_FORBIDDEN", "当前角色不能执行该待办状态变更", HttpStatus.FORBIDDEN);
    }
  }

  private String saveAction(
      AuthUser user,
      BusinessTodoRow row,
      BusinessTodoStatus status,
      String actionType,
      String note,
      List<BusinessTodoAttachmentRequest> attachments
  ) {
    String actionId = "todo-act-" + UUID.randomUUID();
    actionRepository.saveAction(new RoleTodoActionRecord(
        actionId,
        user.tenantId(),
        row.id(),
        actionType,
        status.name(),
        note,
        user.id(),
        user.displayName(),
        user.role()
    ));
    saveAttachments(user.tenantId(), actionId, row.id(), attachments);
    actionRepository.saveOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "待办状态变更",
        "business_todo",
        row.id(),
        row.storeId(),
        row.month(),
        truncate(note)
    ));
    return actionId;
  }

  private void saveSystemAction(
      long tenantId,
      BusinessTodoRow row,
      BusinessTodoStatus status,
      String actionType,
      String note
  ) {
    String actionId = "todo-act-" + UUID.randomUUID();
    actionRepository.saveAction(new RoleTodoActionRecord(
        actionId,
        tenantId,
        row.id(),
        actionType,
        status.name(),
        note,
        null,
        "系统",
        "SYSTEM"
    ));
    actionRepository.saveOperationLog(new RoleTodoOperationLogRecord(
        tenantId,
        null,
        "系统",
        "待办自动更新",
        "business_todo",
        row.id(),
        row.storeId(),
        row.month(),
        truncate(note)
    ));
  }

  private void saveAttachments(
      long tenantId,
      String actionId,
      String todoId,
      List<BusinessTodoAttachmentRequest> attachments
  ) {
    if (attachments == null || attachments.isEmpty()) {
      return;
    }
    if (attachments.size() > 10) {
      throw new BusinessException("TODO_ATTACHMENT_LIMIT", "每次最多上传 10 个附件", HttpStatus.BAD_REQUEST);
    }
    int index = 0;
    for (BusinessTodoAttachmentRequest attachment : attachments) {
      if (attachment == null || attachment.dataBase64() == null || attachment.dataBase64().isBlank()) {
        continue;
      }
      byte[] content = decodeAttachment(attachment.dataBase64());
      if (content.length > 5 * 1024 * 1024) {
        throw new BusinessException("TODO_ATTACHMENT_SIZE", "单个待办附件不能超过 5MB", HttpStatus.BAD_REQUEST);
      }
      index++;
      String fileName = safeText(attachment.fileName(), "附件-" + index);
      String contentType = safeText(attachment.contentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
      if (!ALLOWED_ATTACHMENT_TYPES.contains(contentType)) {
        throw new BusinessException("TODO_ATTACHMENT_TYPE", "待办附件仅支持图片或 PDF", HttpStatus.BAD_REQUEST);
      }
      actionRepository.saveAttachment(new RoleTodoAttachmentRecord(
          "todo-file-" + UUID.randomUUID(),
          tenantId,
          actionId,
          todoId,
          fileName.replaceAll("[\\r\\n]", " "),
          contentType,
          content.length,
          content
      ));
    }
  }

  private byte[] decodeAttachment(String dataBase64) {
    String value = dataBase64.trim();
    int comma = value.indexOf(',');
    if (comma >= 0) {
      value = value.substring(comma + 1);
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("TODO_ATTACHMENT_INVALID", "待办附件内容不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private BusinessTodoResponse response(AuthUser user, BusinessTodoRow row, boolean includeActions) {
    BusinessTodoStatus status = BusinessTodoStatus.parse(row.status());
    List<BusinessTodoActionResponse> actions = includeActions ? actionRepository.history(user.tenantId(), row.id()).stream()
        .map(action -> actionResponse(user.tenantId(), row.id(), action))
        .toList() : List.of();
    return new BusinessTodoResponse(
        row.id(),
        row.ruleCode(),
        row.title(),
        row.summary(),
        status.name(),
        status.label(),
        row.priority(),
        row.sourceModule(),
        row.sourceRecordId(),
        targetRoute(row),
        row.storeId(),
        row.storeName(),
        row.brandName(),
        row.month(),
        roleLabel(row.assigneeRole()),
        roleLabel(row.reviewRole()),
        row.createdAt(),
        row.updatedAt(),
        row.completedAt(),
        !status.terminal() && canAct(user, row),
        actions
    );
  }

  private BusinessTodoActionResponse actionResponse(long tenantId, String todoId, RoleTodoActionHistory action) {
    List<BusinessTodoAttachmentResponse> attachments = actionRepository.attachments(tenantId, action.id(), todoId).stream()
        .map(file -> attachmentResponse(todoId, file))
        .toList();
    BusinessTodoStatus status = BusinessTodoStatus.parse(action.status());
    return new BusinessTodoActionResponse(
        action.id(),
        action.actionType(),
        status.name(),
        status.label(),
        action.note(),
        action.actorName(),
        action.actorRole(),
        action.createdAt(),
        attachments
    );
  }

  private BusinessTodoAttachmentResponse attachmentResponse(String todoId, RoleTodoAttachmentSummary file) {
    return new BusinessTodoAttachmentResponse(
        file.id(),
        file.fileName(),
        file.contentType(),
        file.sizeBytes(),
        "/api/todos/" + todoId + "/attachments/" + file.id()
    );
  }

  private boolean canAct(AuthUser user, BusinessTodoRow row) {
    String role = normalizeRole(user.role());
    if (GLOBAL_TODO_ROLES.contains(role)) {
      return true;
    }
    return role.equals(normalizeRole(row.assigneeRole())) || role.equals(normalizeRole(row.reviewRole()));
  }

  private String targetRoute(BusinessTodoRow row) {
    if ("expense_claim".equals(row.sourceModule())) {
      return "/expenses";
    }
    if ("salary_record".equals(row.sourceModule())) {
      return "/salary";
    }
    String store = row.storeId() == null ? "" : row.storeId();
    String month = row.month() == null ? "" : row.month();
    return "/data-entry?storeId=" + store + "&month=" + month;
  }

  private String roleLabel(String role) {
    return switch (normalizeRole(role)) {
      case "ADMIN" -> "系统管理员";
      case "BOSS", "OWNER" -> "老板";
      case "FINANCE" -> "财务";
      case "STORE_MANAGER" -> "店长";
      case "SUPERVISOR" -> "督导";
      case "WAREHOUSE" -> "仓库管理员";
      case "OPERATIONS", "OPS" -> "运营";
      default -> role == null ? null : role;
    };
  }

  private String normalizeMonth(String value) {
    try {
      return YearMonth.parse(value == null || value.isBlank() ? YearMonth.now().toString() : value.trim()).toString();
    } catch (RuntimeException ex) {
      throw new BusinessException("MONTH_INVALID", "月份格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
  }

  private BigDecimal normalizeThreshold(BigDecimal value, BigDecimal fallback) {
    if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      return fallback;
    }
    return value;
  }

  private String percentage(BigDecimal value) {
    return value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
  }

  private String money(BigDecimal value) {
    return "¥" + (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private String displayStore(String name, String id) {
    return name == null || name.isBlank() ? id : name;
  }

  private String safeText(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String truncate(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
  }

  private String normalizeRole(String role) {
    return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
  }

  public record BusinessTodoReconcileResponse(String month, long openCount) {
  }

  public record DownloadedTodoAttachment(String fileName, String contentType, byte[] content) {
  }
}
