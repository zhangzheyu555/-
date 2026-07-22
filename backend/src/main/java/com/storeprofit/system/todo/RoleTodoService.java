package com.storeprofit.system.todo;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionRecord;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionSummary;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoAttachmentRecord;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoOperationLogRecord;
import com.storeprofit.system.todo.RoleTodoEscalationRepository.RoleTodoEscalationRecord;
import com.storeprofit.system.todo.RoleTodoEscalationRepository.RoleTodoEscalationRow;
import com.storeprofit.system.todo.RoleTodoRepository.DataImportIssueTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.DailyLossReviewTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.ExpenseTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.InspectionTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.ProfitRiskTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.WarehouseAdjustmentTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.WarehousePurchaseTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.WarehouseReturnTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.WarehouseStockAlertTodoRow;
import com.storeprofit.system.todo.RoleTodoRepository.WarehouseTodoRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleTodoService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final String DATA_SOURCE = "MySQL\u7ed3\u6784\u5316\u6570\u636e / \u540e\u7aef\u6807\u51c6\u63a5\u53e3";
  private static final List<String> STATUSES = List.of("RISK", "PENDING", "REMINDER", "DONE");
  private static final Set<RoleTodoAudience> ESCALATION_AUDIENCES = Set.of(
      RoleTodoAudience.FINANCE,
      RoleTodoAudience.SUPERVISOR,
      RoleTodoAudience.STORE_MANAGER,
      RoleTodoAudience.WAREHOUSE
  );
  private static final String NO_IMPACT_CLOSE_NOTE = "事情没有很大影响，已默认处理";

  private final RoleTodoRepository roleTodoRepository;
  private final RoleTodoEscalationRepository escalationRepository;
  private final RoleTodoActionRepository actionRepository;
  private final AccessControlService accessControl;

  @Autowired
  public RoleTodoService(
      RoleTodoRepository roleTodoRepository,
      RoleTodoEscalationRepository escalationRepository,
      RoleTodoActionRepository actionRepository,
      AccessControlService accessControl
  ) {
    this.roleTodoRepository = roleTodoRepository;
    this.escalationRepository = escalationRepository;
    this.actionRepository = actionRepository;
    this.accessControl = accessControl;
  }

  /** Compatibility constructor retained for isolated legacy todo tests. */
  public RoleTodoService(
      RoleTodoRepository roleTodoRepository,
      RoleTodoEscalationRepository escalationRepository,
      RoleTodoActionRepository actionRepository
  ) {
    this(roleTodoRepository, escalationRepository, actionRepository, null);
  }

  public RoleTodoResponse todos(AuthUser user, RoleTodoAudience audience, RoleTodoQuery query) {
    requireTodoRead(user);
    requireAudienceAccess(user, audience);
    RoleTodoQuery normalized = query == null ? RoleTodoQuery.defaults() : query;
    requireValidStatus(normalized.status());
    Long effectiveBrandId = isStoreManager(user) ? null : normalized.brandId();
    String updatedAt = now();
    Map<String, RoleTodoActionSummary> completedActions = actionRepository.completedActions(user.tenantId());
    Set<String> bossResolvedSourceTodoIds = escalationRepository.resolvedSourceTodoIds(user.tenantId());

    List<RoleTodoItemResponse> scopedItems = new ArrayList<>();
    if (includesInspection(audience)) {
      StoreQueryScope inspectionScope = effectiveStoreScope(
          user, normalized.storeId(), DataScopeDomains.INSPECTION);
      Set<String> escalatedTodoIds = openSourceTodoIds(user.tenantId(), RoleTodoAudience.SUPERVISOR);
      scopedItems.addAll(queryStoreRows(inspectionScope, storeId -> roleTodoRepository.failedInspections(
          user.tenantId(), effectiveBrandId, storeId, normalized.limit())).stream()
          .map(row -> applyCompletion(inspectionItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
          .toList());
    }
    if (includesDailyLossReview(audience)) {
      // Daily-loss review shares the STORE scope used by DailyLossService for supervisors.
      // This only projects a SUBMITTED source record; it never lets generic todo completion
      // bypass DailyLossService.reviewReport's lock, authorization, and audit trail.
      StoreQueryScope dailyLossScope = effectiveStoreScope(
          user, normalized.storeId(), DataScopeDomains.STORE);
      scopedItems.addAll(queryStoreRows(dailyLossScope, storeId -> roleTodoRepository.pendingDailyLossReviews(
          user.tenantId(), effectiveBrandId, storeId, normalized.limit())).stream()
          .map(row -> dailyLossReviewItem(row, updatedAt))
          .toList());
    }
    if (includesWarehouse(audience)) {
      StoreQueryScope warehouseScope = effectiveStoreScope(
          user, normalized.storeId(), DataScopeDomains.WAREHOUSE);
      Set<String> escalatedTodoIds = openSourceTodoIds(user.tenantId(), RoleTodoAudience.WAREHOUSE);
      List<String> warehouseStatuses = RoleTodoAudience.STORE_MANAGER.equals(audience)
          ? List.of("SHIPPED")
          : List.of("SUBMITTED", "APPROVED");
      scopedItems.addAll(queryStoreRows(warehouseScope, storeId -> roleTodoRepository.pendingWarehouseRequisitions(
          user.tenantId(), effectiveBrandId, storeId, warehouseStatuses, normalized.limit())).stream()
          .map(row -> applyCompletion(warehouseItem(row, updatedAt, escalatedTodoIds, audience), completedActions, bossResolvedSourceTodoIds))
          .toList());
      if (!RoleTodoAudience.STORE_MANAGER.equals(audience)) {
        scopedItems.addAll(roleTodoRepository.warehouseStockAlerts(user.tenantId(), normalized.limit()).stream()
            .map(row -> applyCompletion(warehouseStockAlertItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
            .toList());
        scopedItems.addAll(queryStoreRows(warehouseScope, storeId -> roleTodoRepository.pendingWarehouseReturns(
            user.tenantId(), effectiveBrandId, storeId, normalized.limit())).stream()
            .map(row -> applyCompletion(warehouseReturnItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
            .toList());
        scopedItems.addAll(roleTodoRepository.pendingWarehousePurchases(user.tenantId(), normalized.limit()).stream()
            .map(row -> applyCompletion(warehousePurchaseItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
            .toList());
        scopedItems.addAll(roleTodoRepository.stockLossAdjustments(user.tenantId(), normalized.limit()).stream()
            .map(row -> applyCompletion(warehouseAdjustmentItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
            .toList());
      }
    }
    if (includesFinance(audience)) {
      StoreQueryScope financeScope = effectiveStoreScope(
          user, normalized.storeId(), DataScopeDomains.FINANCE);
      Set<String> escalatedTodoIds = openSourceTodoIds(user.tenantId(), RoleTodoAudience.FINANCE);
      scopedItems.addAll(queryStoreRows(financeScope, storeId -> roleTodoRepository.profitRiskEntries(
          user.tenantId(), effectiveBrandId, storeId, normalized.limit())).stream()
          .map(row -> applyCompletion(profitRiskItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
          .toList());
      scopedItems.addAll(queryStoreRows(financeScope, storeId -> roleTodoRepository.pendingExpenseClaims(
          user.tenantId(), effectiveBrandId, storeId, normalized.limit())).stream()
          .map(row -> applyCompletion(expenseItem(row, updatedAt, escalatedTodoIds), completedActions, bossResolvedSourceTodoIds))
          .toList());
    }
    if (includesDataImport(audience)) {
      scopedItems.addAll(roleTodoRepository.dataImportIssues(user.tenantId(), normalized.limit())
          .stream()
          .map(row -> applyCompletion(dataImportIssueItem(row, updatedAt), completedActions, bossResolvedSourceTodoIds))
          .toList());
    }
    if (RoleTodoAudience.BOSS.equals(audience)) {
      scopedItems.addAll(escalationRepository.openEscalations(user.tenantId(), normalized.limit())
          .stream()
          .map(row -> escalationItem(row, updatedAt))
          .toList());
    }

    List<RoleTodoStatResponse> stats = stats(scopedItems);
    List<RoleTodoItemResponse> visibleItems = scopedItems.stream()
        .filter(item -> normalized.includeDone() || !"DONE".equals(item.status()))
        .toList();
    List<RoleTodoItemResponse> filteredItems = visibleItems.stream()
        .filter(item -> normalized.status() == null || normalized.status().equals(item.status()))
        .sorted(todoOrder())
        .limit(normalized.limit())
        .toList();

    return new RoleTodoResponse(
        audience.roleName(),
        DATA_SOURCE,
        updatedAt,
        stats,
        summary(audience, filteredItems),
        filteredItems
    );
  }

  public RoleTodoEscalationResponse escalate(
      AuthUser user,
      RoleTodoAudience sourceAudience,
      String todoId,
      RoleTodoEscalationRequest request
  ) {
    requireTodoTransition(user);
    requireEscalationAudience(sourceAudience);
    requireEscalationActor(user, sourceAudience);
    String normalizedTodoId = requireText(todoId, "BAD_TODO", "Todo id is required");
    String reason = requireText(request == null ? null : request.reason(), "BAD_REASON", "Escalation reason is required");
    String severity = normalizeEscalationSeverity(request == null ? null : request.severity());
    RoleTodoItemResponse visibleTodo = requireVisibleTodo(user, sourceAudience, normalizedTodoId);
    if (isExpenseTodo(visibleTodo)) {
      rejectLegacyExpenseEscalation(user, visibleTodo);
    }
    if (isDailyLossReviewTodo(visibleTodo)) {
      rejectLegacyDailyLossEscalation(user, visibleTodo);
    }
    String escalationId = "esc-" + UUID.randomUUID();
    String bossTodoId = "boss-escalation-" + escalationId;
    escalationRepository.save(new RoleTodoEscalationRecord(
        escalationId,
        user.tenantId(),
        persistedRoleCode(sourceAudience),
        escalationModule(sourceAudience),
        normalizedTodoId,
        normalizedTodoId,
        reason,
        severity,
        user.id(),
        user.displayName(),
        bossTodoId,
        "OPEN"
    ));
    return new RoleTodoEscalationResponse(escalationId, bossTodoId);
  }

  @Transactional
  public RoleTodoActionResultResponse resolve(
      AuthUser user,
      RoleTodoAudience audience,
      String todoId,
      RoleTodoCompletionRequest request
  ) {
    requireTodoTransition(user);
    requireCompletionActor(user, audience);
    String normalizedTodoId = requireText(todoId, "BAD_TODO", "Todo id is required");
    RoleTodoItemResponse visibleTodo = requireVisibleTodo(user, audience, normalizedTodoId);
    if (isInspectionTodo(visibleTodo)) {
      actionRepository.saveRejectedOperationLog(new RoleTodoOperationLogRecord(
          user.tenantId(),
          user.id(),
          user.displayName(),
          "inspection_rectification_legacy_completion_rejected",
          "inspection_rectification",
          visibleTodo.sourceRecordId(),
          visibleTodo.storeId(),
          visibleTodo.month(),
          "Inspection rectification must use the evidence and review workflow"
      ));
      throw new BusinessException(
          "INSPECTION_RECTIFICATION_WORKFLOW_REQUIRED",
          "巡检整改需先上传现场证据并提交运营复核，不能通过通用待办直接完成",
          HttpStatus.CONFLICT);
    }
    if (isExpenseTodo(visibleTodo)) {
      rejectLegacyExpenseCompletion(user, visibleTodo.id(), visibleTodo);
    }
    if (isDailyLossReviewTodo(visibleTodo)) {
      rejectLegacyDailyLossCompletion(user, visibleTodo.id(), visibleTodo);
    }
    validateSourceReadyForManualCompletion(user, audience, normalizedTodoId);
    String note = requireText(request == null ? null : request.note(), "BAD_NOTE", "Processing note is required");
    RoleTodoActionResultResponse result = saveCompletion(user, visibleTodo, normalizedTodoId, "RESOLVE", note, request);
    roleTodoRepository.markSourceHandled(user.tenantId(), visibleTodo.id(), user.id());
    return result;
  }

  @Transactional
  public RoleTodoActionResultResponse resolveBoss(
      AuthUser user,
      String todoId,
      RoleTodoCompletionRequest request
  ) {
    return completeBossEscalation(user, todoId, request, "RESOLVE", null);
  }

  @Transactional
  public RoleTodoActionResultResponse close(
      AuthUser user,
      String todoId,
      RoleTodoCompletionRequest request
  ) {
    return completeBossEscalation(user, todoId, request, "CLOSE", NO_IMPACT_CLOSE_NOTE);
  }

  private RoleTodoActionResultResponse completeBossEscalation(
      AuthUser user,
      String todoId,
      RoleTodoCompletionRequest request,
      String actionType,
      String defaultNote
  ) {
    requireTodoTransition(user);
    requireBoss(user);
    String normalizedTodoId = requireText(todoId, "BAD_TODO", "Todo id is required");
    RoleTodoEscalationRow escalation = escalationRepository
        .findOpenByBossTodoId(user.tenantId(), normalizedTodoId)
        .orElseThrow(() -> new BusinessException("TODO_NOT_FOUND", "Boss todo is not open", HttpStatus.NOT_FOUND));
    RoleTodoItemResponse sourceTodo = sourceTodoForBossAction(user, escalation);
    if (isExpenseTodoId(escalation.sourceTodoId())) {
      rejectLegacyExpenseCompletion(user, escalation.sourceTodoId(), sourceTodo);
    }
    if (isDailyLossReviewTodo(sourceTodo)) {
      rejectLegacyDailyLossCompletion(user, escalation.sourceTodoId(), sourceTodo);
    }
    String note = Optional.ofNullable(request)
        .map(RoleTodoCompletionRequest::note)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .orElse(defaultNote);
    if (note == null || note.isBlank()) {
      throw new BusinessException("BAD_NOTE", "Processing note is required", HttpStatus.BAD_REQUEST);
    }
    RoleTodoItemResponse bossTodo = escalationItem(escalation, now());
    RoleTodoActionResultResponse result = saveCompletion(user, bossTodo, normalizedTodoId, actionType, note, request);
    saveCompletion(user, sourceTodo, escalation.sourceTodoId(), actionType + "_SOURCE", note, null);
    roleTodoRepository.markSourceHandled(user.tenantId(), escalation.sourceTodoId(), user.id());
    escalationRepository.resolve(user.tenantId(), escalation.id());
    return result;
  }

  private RoleTodoActionResultResponse saveCompletion(
      AuthUser user,
      RoleTodoItemResponse item,
      String todoId,
      String actionType,
      String note,
      RoleTodoCompletionRequest request
  ) {
    String actionId = "todo-act-" + UUID.randomUUID();
    actionRepository.saveAction(new RoleTodoActionRecord(
        actionId,
        user.tenantId(),
        todoId,
        actionType,
        "DONE",
        note,
        user.id(),
        user.displayName(),
        user.role()
    ));
    int attachmentCount = saveAttachments(user.tenantId(), actionId, todoId, request == null ? List.of() : request.attachments());
    actionRepository.saveOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        auditAction(actionType),
        "role_todo",
        todoId,
        item == null ? null : item.storeId(),
        item == null ? null : item.month(),
        truncateReason(note)
    ));
    return new RoleTodoActionResultResponse(todoId, "DONE", actionId, attachmentCount, note);
  }

  private RoleTodoItemResponse sourceTodoForBossAction(AuthUser user, RoleTodoEscalationRow escalation) {
    RoleTodoAudience sourceAudience = audienceByRoleCode(escalation.sourceRole());
    if (sourceAudience == null) {
      return null;
    }
    return todos(user, sourceAudience, new RoleTodoQuery(true, null, 200, null, null))
        .items()
        .stream()
        .filter(item -> escalation.sourceTodoId().equals(item.id()))
        .findFirst()
        .orElse(null);
  }

  private RoleTodoAudience audienceByRoleCode(String roleCode) {
    if (roleCode == null || roleCode.isBlank()) {
      return null;
    }
    for (RoleTodoAudience audience : RoleTodoAudience.values()) {
      if (audience.roleCode().equals(roleCode)) {
        return audience;
      }
    }
    return null;
  }

  private String auditAction(String actionType) {
    return "todo_" + actionType.toLowerCase(Locale.ROOT);
  }

  private String truncateReason(String note) {
    if (note == null) {
      return null;
    }
    String trimmed = note.trim();
    return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
  }

  private int saveAttachments(
      long tenantId,
      String actionId,
      String todoId,
      List<RoleTodoAttachmentRequest> attachments
  ) {
    if (attachments == null || attachments.isEmpty()) {
      return 0;
    }
    if (attachments.size() > 10) {
      throw new BusinessException("BAD_ATTACHMENT", "At most 10 attachments are allowed", HttpStatus.BAD_REQUEST);
    }
    int count = 0;
    for (RoleTodoAttachmentRequest attachment : attachments) {
      if (attachment == null || attachment.dataBase64() == null || attachment.dataBase64().isBlank()) {
        continue;
      }
      byte[] content = decodeAttachment(attachment.dataBase64());
      if (content.length > 5 * 1024 * 1024) {
        throw new BusinessException("BAD_ATTACHMENT", "Each attachment must be smaller than 5MB", HttpStatus.BAD_REQUEST);
      }
      String fileName = Optional.ofNullable(attachment.fileName())
          .map(String::trim)
          .filter(value -> !value.isBlank())
          .orElse("附件-" + (count + 1));
      String contentType = Optional.ofNullable(attachment.contentType())
          .map(String::trim)
          .filter(value -> !value.isBlank())
          .orElse("application/octet-stream");
      actionRepository.saveAttachment(new RoleTodoAttachmentRecord(
          "todo-file-" + UUID.randomUUID(),
          tenantId,
          actionId,
          todoId,
          fileName,
          contentType,
          content.length,
          content
      ));
      count++;
    }
    return count;
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
      throw new BusinessException("BAD_ATTACHMENT", "Attachment content must be base64", HttpStatus.BAD_REQUEST);
    }
  }

  private RoleTodoItemResponse requireVisibleTodo(AuthUser user, RoleTodoAudience audience, String todoId) {
    return todos(user, audience, new RoleTodoQuery(true, null, 200, null, null))
        .items()
        .stream()
        .filter(item -> todoId.equals(item.id()))
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            "FORBIDDEN", "当前账号无权访问该待办或待办不在数据范围内", HttpStatus.FORBIDDEN));
  }

  private void validateSourceReadyForManualCompletion(AuthUser user, RoleTodoAudience audience, String todoId) {
    if (RoleTodoAudience.WAREHOUSE.equals(audience) && todoId.startsWith("warehouse-alert-")) {
      if (roleTodoRepository.hasOpenWarehouseStockAlert(user.tenantId(), todoId)) {
        throw new BusinessException(
            "LOW_STOCK_TODO_NOT_READY",
            "库存仍不足，请先入库、调整库存或修改预警设置",
            HttpStatus.CONFLICT
        );
      }
      return;
    }
    if (RoleTodoAudience.WAREHOUSE.equals(audience) && todoId.startsWith("warehouse-return-")) {
      throw new BusinessException(
          "WAREHOUSE_RETURN_TODO_NOT_READY",
          "请先在仓库中心审核退货单并确认收到退货",
          HttpStatus.CONFLICT
      );
    }
    if (RoleTodoAudience.WAREHOUSE.equals(audience) && todoId.startsWith("warehouse-")) {
      String status = roleTodoRepository.warehouseRequisitionStatus(user.tenantId(), todoId).orElse("");
      if (List.of("SUBMITTED", "APPROVED").contains(status)) {
        throw new BusinessException(
            "WAREHOUSE_TODO_NOT_READY",
            "请先在仓库中心完成发货或驳回",
            HttpStatus.CONFLICT
        );
      }
      return;
    }
    if (RoleTodoAudience.STORE_MANAGER.equals(audience) && todoId.startsWith("store-receipt-")) {
      String status = roleTodoRepository.warehouseRequisitionStatus(user.tenantId(), todoId).orElse("");
      if ("SHIPPED".equals(status)) {
        throw new BusinessException(
            "STORE_RECEIPT_TODO_NOT_READY",
            "\u8bf7\u5148\u786e\u8ba4\u672c\u5e97\u6536\u8d27",
            HttpStatus.CONFLICT
        );
      }
    }
  }

  private RoleTodoItemResponse inspectionItem(InspectionTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    String todoId = "inspection-" + row.id();
    String month = monthFromDate(row.inspectionDate());
    BigDecimal score = amount(row.score());
    BigDecimal fullScore = amount(row.fullScore());
    String scoreText = score.stripTrailingZeros().toPlainString() + "/" + fullScore.stripTrailingZeros().toPlainString();
    boolean manualReview = "MANUAL_REVIEW".equalsIgnoreCase(row.resultCode());
    return new RoleTodoItemResponse(
        todoId,
        (manualReview ? "巡店待人工复核：" : "\u5de1\u5e97\u672a\u901a\u8fc7\uff1a")
            + display(row.storeName(), row.storeId()),
        manualReview
            ? "历史巡店评分 " + scoreText + "，快照不足，需由督导人工复核；原始成绩不会被覆盖。"
            : "\u5de1\u5e97\u8bc4\u5206 " + scoreText + "\uff0c\u9700\u8981\u5e97\u957f\u6574\u6539\u5e76\u7531\u7763\u5bfc\u590d\u67e5\u3002",
        "RISK",
        inspectionPriority(score, fullScore),
        row.brandName(),
        row.storeId(),
        row.storeName(),
        month,
        "\u7763\u5bfc",
        dueAt(row.inspectionDate()),
        "\u7763\u5bfc\u5de1\u5e97",
        row.id(),
        manualReview ? "待人工复核" : "\u5e97\u957f\u6574\u6539\u4e2d",
        escalatedTodoIds.contains(todoId),
        "inspection_record",
        updatedAt,
        occurredAt(row.inspectionDate()),
        new RoleTodoActionResponse("inspect", "\u67e5\u770b\u5de1\u5e97\u8bb0\u5f55", params(
            "storeId", row.storeId(),
            "inspectionId", row.id(),
            "month", month
        ))
    );
  }

  private RoleTodoItemResponse warehouseItem(
      WarehouseTodoRow row,
      String updatedAt,
      Set<String> escalatedTodoIds,
      RoleTodoAudience audience
  ) {
    String todoId = RoleTodoAudience.STORE_MANAGER.equals(audience) && "SHIPPED".equals(row.status())
        ? "store-receipt-" + row.id()
        : "warehouse-" + row.id();
    String month = monthFromDateTime(row.submittedAt());
    if (RoleTodoAudience.STORE_MANAGER.equals(audience) && "SHIPPED".equals(row.status())) {
      return new RoleTodoItemResponse(
          todoId,
          "\u5f85\u786e\u8ba4\u6536\u8d27\uff1a\u4ed3\u5e93\u5df2\u53d1\u8d27\uff0c\u8bf7\u786e\u8ba4\u672c\u5e97\u6536\u8d27",
          "\u53eb\u8d27\u5355 " + row.id() + " \u5df2\u7531\u4ed3\u5e93\u53d1\u8d27\uff0c\u8bf7\u5230\u4ed3\u5e93\u4e2d\u5fc3\u6838\u5bf9\u5546\u54c1\u548c\u6570\u91cf\u540e\u786e\u8ba4\u6536\u8d27\u3002",
          "PENDING",
          76,
          row.brandName(),
          row.storeId(),
          row.storeName(),
          month,
          "\u5e97\u957f",
          "\u4eca\u5929\u5185",
          "\u4ed3\u5e93\u4e2d\u5fc3",
          row.id(),
          "\u5f85\u786e\u8ba4\u6536\u8d27",
          escalatedTodoIds.contains(todoId),
          "store_requisition",
          updatedAt,
          row.submittedAt(),
          new RoleTodoActionResponse("warehouse", "\u53bb\u4ed3\u5e93\u4e2d\u5fc3\u5904\u7406", params(
              "storeId", row.storeId(),
              "requisitionId", row.id(),
              "month", month
          ))
      );
    }
    String processStatus = switch (row.status()) {
      case "APPROVED" -> "\u5f85\u4ed3\u5e93\u53d1\u8d27";
      case "SHIPPED" -> "\u5f85\u95e8\u5e97\u6536\u8d27";
      case "RECEIVED" -> "\u5df2\u6536\u8d27";
      case "REJECTED" -> "\u5df2\u9a73\u56de";
      case "TODO_DONE" -> "\u5df2\u5904\u7406";
      default -> "\u5f85\u4ed3\u5e93\u5ba1\u6838";
    };
    String ownerName = "SHIPPED".equals(row.status()) ? "\u5e97\u957f" : "\u4ed3\u5e93\u7ba1\u7406\u5458";
    return new RoleTodoItemResponse(
        todoId,
        "\u95e8\u5e97\u53eb\u8d27\u5f85\u5904\u7406\uff1a" + display(row.storeName(), row.storeId()),
        "\u53eb\u8d27\u5355\u91d1\u989d " + money(row.totalAmount()) + "\uff0c\u5f53\u524d\u72b6\u6001\u4e3a " + processStatus + "\u3002",
        "PENDING",
        "SHIPPED".equals(row.status()) ? 76 : ("APPROVED".equals(row.status()) ? 72 : 68),
        row.brandName(),
        row.storeId(),
        row.storeName(),
        month,
        ownerName,
        dueAtFromDateTime(row.submittedAt()),
        "\u4ed3\u5e93\u53eb\u8d27",
        row.id(),
        processStatus,
        escalatedTodoIds.contains(todoId),
        "store_requisition",
        updatedAt,
        row.submittedAt(),
        new RoleTodoActionResponse("warehouse", "\u67e5\u770b\u53eb\u8d27\u5355", params(
            "storeId", row.storeId(),
            "requisitionId", row.id(),
            "month", month
        ))
    );
  }

  private RoleTodoItemResponse warehouseStockAlertItem(WarehouseStockAlertTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    boolean expiring = "EXPIRING".equals(row.alertType());
    String title = expiring ? "库存临期需要处理：" + display(row.itemName(), String.valueOf(row.itemId()))
        : row.message();
    String processStatus = expiring ? "临期库存待处理" : "库存不足待补货";
    return new RoleTodoItemResponse(
        row.id(),
        title,
        expiring ? row.message() : "当前库存低于最低安全库存，请安排采购入库或调拨补货。",
        "RISK",
        expiring ? 94 : 92,
        null,
        null,
        null,
        monthFromDate(expiring ? row.nearestExpiryDate() : null),
        "仓库管理员",
        dueAt(expiring ? row.nearestExpiryDate() : null),
        "库存预警",
        String.valueOf(row.itemId()),
        processStatus,
        escalatedTodoIds.contains(row.id()),
        "warehouse_stock_batch",
        updatedAt,
        expiring ? row.nearestExpiryDate() : updatedAt,
        new RoleTodoActionResponse("warehouse", "查看库存预警", params(
            "itemId", String.valueOf(row.itemId())
        ))
    );
  }

  private RoleTodoItemResponse warehouseReturnItem(WarehouseReturnTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    String todoId = "warehouse-return-" + row.id();
    String processStatus = "APPROVED".equals(row.status()) ? "退货待收货" : "退货待审核";
    String month = monthFromDateTime(row.createdAt());
    return new RoleTodoItemResponse(
        todoId,
        "门店退货待处理：" + display(row.storeName(), row.storeId()),
        "退货单 " + display(row.returnNo(), row.id()) + "，金额 " + money(row.totalAmount()) + "，当前状态为 " + processStatus + "。",
        "PENDING",
        "APPROVED".equals(row.status()) ? 82 : 78,
        row.brandName(),
        row.storeId(),
        row.storeName(),
        month,
        "仓库管理员",
        dueAtFromDateTime(row.createdAt()),
        "配送退货",
        row.id(),
        processStatus,
        escalatedTodoIds.contains(todoId),
        "warehouse_return_order",
        updatedAt,
        row.createdAt(),
        new RoleTodoActionResponse("warehouse", "查看退货单", params(
            "storeId", row.storeId(),
            "returnId", row.id(),
            "month", month
        ))
    );
  }

  private RoleTodoItemResponse warehousePurchaseItem(WarehousePurchaseTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    String processStatus = "DRAFT".equals(row.status()) ? "采购单待确认" : "采购未入库";
    return new RoleTodoItemResponse(
        "warehouse-purchase-" + row.id(),
        "采购未入库：" + display(row.supplierName(), row.id()),
        "采购单金额 " + money(row.totalAmount()) + "，当前状态为 " + processStatus + "。",
        "PENDING",
        "DRAFT".equals(row.status()) ? 70 : 78,
        null,
        null,
        null,
        monthFromDateTime(row.createdAt()),
        "仓库管理员",
        dueAtFromDateTime(row.createdAt()),
        "采购入库",
        row.id(),
        processStatus,
        escalatedTodoIds.contains("warehouse-purchase-" + row.id()),
        "warehouse_purchase_order",
        updatedAt,
        row.createdAt(),
        new RoleTodoActionResponse("warehouse", "查看采购单", params(
            "purchaseOrderId", row.id()
        ))
    );
  }

  private RoleTodoItemResponse warehouseAdjustmentItem(WarehouseAdjustmentTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    String todoId = "warehouse-adjustment-" + row.id();
    return new RoleTodoItemResponse(
        todoId,
        "盘亏异常待核对：" + display(row.itemName(), String.valueOf(row.id())),
        "库存调整数量 " + amount(row.quantityDelta()).stripTrailingZeros().toPlainString() + "，原因：" + display(row.reason(), "未填写原因") + "。",
        "RISK",
        95,
        null,
        null,
        null,
        monthFromDateTime(row.createdAt()),
        "仓库管理员",
        dueAtFromDateTime(row.createdAt()),
        "库存调整",
        String.valueOf(row.id()),
        "盘亏异常待核对",
        escalatedTodoIds.contains(todoId),
        "warehouse_stock_adjustment",
        updatedAt,
        row.createdAt(),
        new RoleTodoActionResponse("warehouse", "查看库存调整", params(
            "adjustmentId", String.valueOf(row.id())
        ))
    );
  }

  private RoleTodoItemResponse profitRiskItem(ProfitRiskTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    BigDecimal income = amount(row.income());
    BigDecimal net = amount(row.net());
    BigDecimal margin = row.margin() == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : row.margin();
    String sourceRecordId = row.storeId() + "|" + row.month();
    String todoId = "profit-risk-" + row.storeId() + "-" + row.month();
    return new RoleTodoItemResponse(
        todoId,
        "\u5229\u6da6\u5f02\u5e38\u5f85\u6838\u5bf9\uff1a" + display(row.storeName(), row.storeId()),
        "\u672c\u6708\u6536\u5165 " + money(income) + "\uff0c\u51c0\u5229\u6da6 " + money(net) + "\uff0c\u51c0\u5229\u7387 " + percent(margin) + "\uff0c\u9700\u8981\u8d22\u52a1\u6838\u5bf9\u6210\u672c\u548c\u8d39\u7528\u3002",
        "RISK",
        net.compareTo(BigDecimal.ZERO) < 0 ? 96 : 90,
        row.brandName(),
        row.storeId(),
        row.storeName(),
        row.month(),
        "\u8d22\u52a1",
        dueAtFromMonth(row.month()),
        "\u5229\u6da6\u8868",
        sourceRecordId,
        "\u5f85\u8d22\u52a1\u6838\u5bf9",
        escalatedTodoIds.contains(todoId),
        "profit_entry",
        updatedAt,
        occurredAtFromMonth(row.month()),
        new RoleTodoActionResponse("report", "\u67e5\u770b\u5229\u6da6\u8868", params(
            "storeId", row.storeId(),
            "month", row.month(),
            "mode", "single"
        ))
    );
  }

  private RoleTodoItemResponse expenseItem(ExpenseTodoRow row, String updatedAt, Set<String> escalatedTodoIds) {
    String todoId = "expense-" + row.id();
    return new RoleTodoItemResponse(
        todoId,
        "\u62a5\u9500\u5f85\u5ba1\u6838\uff1a" + display(row.storeName(), row.storeId()),
        "\u62a5\u9500\u91d1\u989d " + money(row.amount()) + "\uff0c\u7c7b\u522b " + display(row.category(), "\u672a\u5206\u7c7b") + "\uff0c\u9700\u8981\u8d22\u52a1\u5ba1\u6838\u3002",
        "PENDING",
        76,
        row.brandName(),
        row.storeId(),
        row.storeName(),
        row.month(),
        "\u8d22\u52a1",
        dueAtFromDateTime(row.createdAt()),
        "\u62a5\u9500",
        row.id(),
        "\u5f85\u8d22\u52a1\u5ba1\u6838",
        escalatedTodoIds.contains(todoId),
        "expense_claim",
        updatedAt,
        row.createdAt(),
        new RoleTodoActionResponse("expense", "\u67e5\u770b\u62a5\u9500\u5355", params(
            "storeId", row.storeId(),
            "expenseId", row.id(),
            "month", row.month()
        ))
    );
  }

  private RoleTodoItemResponse dailyLossReviewItem(DailyLossReviewTodoRow row, String updatedAt) {
    String month = monthFromDate(row.lossDate());
    return new RoleTodoItemResponse(
        "daily-loss-review-" + row.id(),
        "每日报损待复核：" + display(row.storeName(), row.storeId()),
        "报损日期 " + display(row.lossDate(), "未填写") + " 已由门店提交，请核对明细和现场照片后复核。",
        "PENDING",
        82,
        row.brandName(),
        row.storeId(),
        row.storeName(),
        month,
        "督导",
        dueAtFromDateTime(row.submittedAt()),
        "每日报损",
        row.id(),
        "待督导复核",
        false,
        "daily_loss_report",
        updatedAt,
        row.submittedAt(),
        new RoleTodoActionResponse("daily-loss", "查看每日报损并复核", params(
            "reportId", row.id(),
            "storeId", row.storeId(),
            "month", month,
            "lossDate", row.lossDate(),
            "mode", "review"
        ))
    );
  }

  public BossTodoDashboardResponse bossDashboard(AuthUser user, RoleTodoQuery query) {
    requireTodoRead(user);
    requireAudienceAccess(user, RoleTodoAudience.BOSS);
    RoleTodoQuery normalized = query == null ? RoleTodoQuery.defaults() : query;
    requireValidStatus(normalized.status());
    RoleTodoQuery dashboardQuery = new RoleTodoQuery(
        true,
        null,
        Math.max(normalized.limit(), 200),
        normalized.brandId(),
        normalized.storeId()
    );
    RoleTodoResponse bossTodos = todos(user, RoleTodoAudience.BOSS, dashboardQuery);
    Map<String, RoleTodoActionSummary> completedActions = actionRepository.completedActions(user.tenantId());
    List<RoleTodoItemResponse> resolvedBossTodos = escalationRepository
        .resolvedEscalations(user.tenantId(), dashboardQuery.limit())
        .stream()
        .map(row -> applyCompletion(escalationItem(row, bossTodos.updatedAt()), completedActions, Set.of()))
        .filter(item -> "DONE".equals(item.status()))
        .toList();
    List<RoleTodoItemResponse> allItems = mergeTodoItems(bossTodos.items(), resolvedBossTodos);
    List<RoleTodoItemResponse> openItems = allItems.stream()
        .filter(item -> !"DONE".equals(item.status()))
        .filter(item -> statusMatches(normalized.status(), item))
        .sorted(todoOrder())
        .toList();
    List<RoleTodoItemResponse> doneReview = allItems.stream()
        .filter(item -> "DONE".equals(item.status()))
        .filter(item -> normalized.status() == null || "DONE".equals(normalized.status()))
        .sorted(doneTodoOrder())
        .limit(Math.min(normalized.limit(), 20))
        .toList();
    List<RoleTodoItemResponse> needsBossAction = openItems.stream()
        .filter(this::isBossEscalationTodo)
        .toList();
    List<RoleTodoItemResponse> roleWorkItems = openItems.stream()
        .filter(item -> !isBossEscalationTodo(item))
        .toList();
    List<BossTodoRiskGroupResponse> highRiskReminders = highRiskGroups(roleWorkItems);
    List<BossTodoOwnerGroupResponse> roleProgress = roleProgress(roleWorkItems);
    int highRiskCount = (int) roleWorkItems.stream().filter(item -> "RISK".equals(item.status())).count();
    BossTodoFocusResponse todayFocus = new BossTodoFocusResponse(
        openItems.size(),
        needsBossAction.size(),
        roleWorkItems.size(),
        highRiskCount,
        highRiskReminders.size(),
        doneReview.size(),
        bossDashboardSummary(needsBossAction.size(), roleWorkItems.size(), highRiskCount, highRiskReminders.size())
    );
    return new BossTodoDashboardResponse(
        RoleTodoAudience.BOSS.roleName(),
        DATA_SOURCE,
        bossTodos.updatedAt(),
        todayFocus,
        needsBossAction,
        highRiskReminders,
        roleProgress,
        doneReview
    );
  }

  private RoleTodoItemResponse dataImportIssueItem(DataImportIssueTodoRow row, String updatedAt) {
    String key = display(row.storageKey(), "\u672a\u6807\u660e\u6570\u636e");
    String todoId = "data-import-" + key.replaceAll("[^a-zA-Z0-9_-]", "-");
    return new RoleTodoItemResponse(
        todoId,
        "\u6570\u636e\u5bfc\u5165\u5f02\u5e38\uff1a" + key,
        "\u68c0\u6d4b\u5230\u65e7\u6570\u636e\u8fc1\u79fb\u6216\u6587\u4ef6\u5bfc\u5165\u5f02\u5e38\uff0c\u9700\u8981\u6838\u5bf9\u5e76\u91cd\u65b0\u5bfc\u5165\u3002",
        "RISK",
        92,
        null,
        null,
        null,
        monthFromDateTime(row.updatedAt()),
        "\u8fd0\u8425",
        dueAtFromDateTime(row.updatedAt()),
        "\u6570\u636e\u5bfc\u5165\u5f02\u5e38",
        key,
        "\u5f85\u6838\u5bf9\u5bfc\u5165\u9519\u8bef",
        false,
        "kv_storage",
        updatedAt,
        row.updatedAt(),
        new RoleTodoActionResponse("dataHealth", "\u67e5\u770b\u6570\u636e\u5065\u5eb7", params(
            "storageKey", key
        ))
    );
  }

  private RoleTodoItemResponse escalationItem(RoleTodoEscalationRow row, String updatedAt) {
    String status = "RISK".equals(row.severity()) ? "RISK" : "PENDING";
    String sourceRoleName = escalationRoleName(row.sourceRole());
    String createdAt = row.createdAt();
    return new RoleTodoItemResponse(
        row.bossTodoId(),
        sourceRoleName + "\u4e0a\u62a5\u4e8b\u9879\u5f85\u67e5\u770b",
        row.reason(),
        status,
        "RISK".equals(status) ? 96 : 76,
        null,
        null,
        null,
        monthFromDateTime(createdAt),
        "\u8001\u677f",
        dueAtFromDateTime(createdAt),
        sourceRoleName + "\u4e0a\u62a5",
        row.sourceTodoId(),
        "\u5df2\u4e0a\u62a5\u8001\u677f\uff0c\u5f85\u8001\u677f\u67e5\u770b",
        true,
        "todo_escalation",
        updatedAt,
        createdAt,
        new RoleTodoActionResponse(escalationTarget(row.sourceRole()), "\u67e5\u770b\u6765\u6e90\u9875\u9762", params(
            "todoId", row.sourceTodoId(),
            "sourceId", row.sourceId(),
            "escalationId", row.id()
        ))
    );
  }

  private RoleTodoItemResponse applyCompletion(
      RoleTodoItemResponse item,
      Map<String, RoleTodoActionSummary> completedActions,
      Set<String> bossResolvedSourceTodoIds
  ) {
    RoleTodoActionSummary action = completedActions.get(item.id());
    if (action != null) {
      if (isInspectionTodo(item)) {
        return completedInspectionItem(item, action.note());
      }
      return withStatus(item, "DONE", action.note());
    }
    if (bossResolvedSourceTodoIds.contains(item.id())) {
      if (isInspectionTodo(item)) {
        return completedInspectionItem(item, "\u8001\u677f\u5df2\u5904\u7406");
      }
      return withStatus(item, "DONE", "\u8001\u677f\u5df2\u5904\u7406");
    }
    return applyOverdue(item);
  }

  private RoleTodoItemResponse completedInspectionItem(RoleTodoItemResponse item, String processStatus) {
    String storeName = display(item.storeName(), item.storeId());
    return new RoleTodoItemResponse(
        item.id(),
        "\u5de1\u5e97\u6574\u6539\u5df2\u5b8c\u6210\uff1a" + storeName,
        "\u5de1\u5e97\u95ee\u9898\u5df2\u5904\u7406\uff0c\u5f53\u524d\u5df2\u4ece\u5e97\u957f\u5f85\u6574\u6539\u4e8b\u9879\u4e2d\u79fb\u9664\u3002",
        "DONE",
        item.priority(),
        item.brandName(),
        item.storeId(),
        item.storeName(),
        item.month(),
        item.ownerName(),
        item.dueAt(),
        item.sourceModule(),
        item.sourceRecordId(),
        processStatus == null || processStatus.isBlank() ? "\u6574\u6539\u5df2\u5b8c\u6210" : processStatus,
        item.escalatedToBoss(),
        item.dataSource(),
        item.updatedAt(),
        item.occurredAt(),
        item.action()
    );
  }

  private boolean isInspectionTodo(RoleTodoItemResponse item) {
    return item != null
        && ("inspection_record".equals(item.dataSource())
            || "\u7763\u5bfc\u5de1\u5e97".equals(item.sourceModule())
            || (item.id() != null && item.id().startsWith("inspection-")));
  }

  private boolean isExpenseTodo(RoleTodoItemResponse item) {
    return item != null && ("expense_claim".equals(item.dataSource()) || isExpenseTodoId(item.id()));
  }

  private boolean isExpenseTodoId(String todoId) {
    return todoId != null && todoId.startsWith("expense-") && todoId.length() > "expense-".length();
  }

  private boolean isDailyLossReviewTodo(RoleTodoItemResponse item) {
    return item != null
        && ("daily_loss_report".equals(item.dataSource())
            || (item.id() != null && item.id().startsWith("daily-loss-review-")));
  }

  /**
   * Expense approval is a controlled state machine. A generic to-do completion must never be
   * able to turn an expense into an approved/handled record, including through a boss escalation.
   */
  private void rejectLegacyExpenseCompletion(
      AuthUser user,
      String todoId,
      RoleTodoItemResponse expenseTodo
  ) {
    String expenseId = isExpenseTodoId(todoId) ? todoId.substring("expense-".length()) : todoId;
    actionRepository.saveRejectedOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_approval_legacy_completion_rejected",
        "expense_claim",
        expenseId,
        expenseTodo == null ? null : expenseTodo.storeId(),
        expenseTodo == null ? null : expenseTodo.month(),
        "报销审批必须通过报销审核流程，不能通过通用待办完成"
    ));
    throw new BusinessException(
        "EXPENSE_APPROVAL_WORKFLOW_REQUIRED",
        "报销审批必须通过报销审核流程，不能通过通用待办完成",
        HttpStatus.CONFLICT);
  }

  /**
   * An expense must not be escalated through the generic to-do workflow: that would create a
   * boss to-do which still cannot legally approve, reject, or close the expense. The boss keeps
   * the explicit expense approval endpoints instead.
   */
  private void rejectLegacyExpenseEscalation(AuthUser user, RoleTodoItemResponse expenseTodo) {
    String todoId = expenseTodo == null ? null : expenseTodo.id();
    String expenseId = isExpenseTodoId(todoId) ? todoId.substring("expense-".length()) : todoId;
    actionRepository.saveRejectedOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_approval_legacy_escalation_rejected",
        "expense_claim",
        expenseId,
        expenseTodo == null ? null : expenseTodo.storeId(),
        expenseTodo == null ? null : expenseTodo.month(),
        "报销审批必须通过报销审核流程，不能通过通用待办上报老板待办"
    ));
    throw new BusinessException(
        "EXPENSE_APPROVAL_WORKFLOW_REQUIRED",
        "报销审批必须通过报销审核流程，不能通过通用待办上报老板待办",
        HttpStatus.CONFLICT);
  }

  private RoleTodoItemResponse applyOverdue(RoleTodoItemResponse item) {
    if (!List.of("PENDING", "REMINDER").contains(item.status())) {
      return item;
    }
    String dueAt = item.dueAt();
    if (dueAt == null || dueAt.isBlank()) {
      return item;
    }
    try {
      OffsetDateTime due = OffsetDateTime.parse(dueAt);
      if (!due.isBefore(OffsetDateTime.now(BUSINESS_ZONE))) {
        return item;
      }
    } catch (RuntimeException ex) {
      return item;
    }
    return new RoleTodoItemResponse(
        item.id(),
        "\u903e\u671f\u672a\u5904\u7406\uff1a" + item.title(),
        item.summary(),
        "RISK",
        Math.max(item.priority(), 94),
        item.brandName(),
        item.storeId(),
        item.storeName(),
        item.month(),
        item.ownerName(),
        item.dueAt(),
        "\u903e\u671f\u672a\u5904\u7406\u4e8b\u9879",
        item.sourceRecordId(),
        "\u5df2\u903e\u671f\uff0c\u539f\u6765\u6e90\uff1a" + item.sourceModule(),
        item.escalatedToBoss(),
        item.dataSource(),
        item.updatedAt(),
        item.occurredAt(),
        item.action()
    );
  }

  private void rejectLegacyDailyLossCompletion(
      AuthUser user,
      String todoId,
      RoleTodoItemResponse dailyLossTodo
  ) {
    String reportId = todoId != null && todoId.startsWith("daily-loss-review-")
        ? todoId.substring("daily-loss-review-".length())
        : dailyLossTodo == null ? todoId : dailyLossTodo.sourceRecordId();
    actionRepository.saveRejectedOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "daily_loss_review_legacy_completion_rejected",
        "daily_loss_report",
        reportId,
        dailyLossTodo == null ? null : dailyLossTodo.storeId(),
        dailyLossTodo == null ? null : dailyLossTodo.month(),
        "日报损复核必须通过每日报损复核流程，不能通过通用待办完成"
    ));
    throw new BusinessException(
        "DAILY_LOSS_REVIEW_WORKFLOW_REQUIRED",
        "日报损复核必须通过每日报损复核流程，不能通过通用待办完成",
        HttpStatus.CONFLICT);
  }

  private void rejectLegacyDailyLossEscalation(AuthUser user, RoleTodoItemResponse dailyLossTodo) {
    String reportId = dailyLossTodo == null ? null : dailyLossTodo.sourceRecordId();
    actionRepository.saveRejectedOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "daily_loss_review_legacy_escalation_rejected",
        "daily_loss_report",
        reportId,
        dailyLossTodo == null ? null : dailyLossTodo.storeId(),
        dailyLossTodo == null ? null : dailyLossTodo.month(),
        "日报损复核必须通过每日报损复核流程，不能通过通用待办上报老板待办"
    ));
    throw new BusinessException(
        "DAILY_LOSS_REVIEW_WORKFLOW_REQUIRED",
        "日报损复核必须通过每日报损复核流程，不能通过通用待办上报老板待办",
        HttpStatus.CONFLICT);
  }

  private RoleTodoItemResponse withStatus(RoleTodoItemResponse item, String status, String processStatus) {
    return new RoleTodoItemResponse(
        item.id(),
        item.title(),
        item.summary(),
        status,
        item.priority(),
        item.brandName(),
        item.storeId(),
        item.storeName(),
        item.month(),
        item.ownerName(),
        item.dueAt(),
        item.sourceModule(),
        item.sourceRecordId(),
        processStatus,
        item.escalatedToBoss(),
        item.dataSource(),
        item.updatedAt(),
        item.occurredAt(),
        item.action()
    );
  }

  private List<RoleTodoStatResponse> stats(List<RoleTodoItemResponse> items) {
    return STATUSES.stream()
        .map(status -> new RoleTodoStatResponse(
            status,
            (int) items.stream().filter(item -> status.equals(item.status())).count()
        ))
        .toList();
  }

  private RoleTodoAiSummaryResponse summary(RoleTodoAudience audience, List<RoleTodoItemResponse> items) {
    if (items.isEmpty()) {
      return new RoleTodoAiSummaryResponse(
          "RULE",
          audience.roleName() + "\u5f53\u524d\u6ca1\u6709\u672a\u5904\u7406\u4e8b\u9879\u3002",
          "\u7531\u540e\u7aef\u89c4\u5219\u751f\u6210"
      );
    }
    long riskCount = items.stream().filter(item -> "RISK".equals(item.status())).count();
    long pendingCount = items.stream().filter(item -> "PENDING".equals(item.status())).count();
    return new RoleTodoAiSummaryResponse(
        "RULE",
        audience.roleName() + "\u5f53\u524d\u6709 " + items.size() + " \u6761\u5f85\u529e\uff0c\u5176\u4e2d\u98ce\u9669 " + riskCount + " \u6761\u3001\u5f85\u5904\u7406 " + pendingCount + " \u6761\u3002",
        "\u7531\u540e\u7aef\u89c4\u5219\u751f\u6210"
    );
  }

  private String bossDashboardSummary(int needsBossCount, int roleWorkCount, int highRiskCount, int highRiskGroupCount) {
    if (needsBossCount > 0) {
      return "\u4eca\u5929\u8001\u677f\u53ea\u9700\u8981\u5904\u7406 " + needsBossCount
          + " \u4ef6\u4e0a\u62a5\u4e8b\u9879\uff1b\u53e6\u6709 " + roleWorkCount
          + " \u6761\u5c97\u4f4d\u4e8b\u9879\u5df2\u6536\u8d77\u4e3a " + highRiskGroupCount
          + " \u7ec4\u98ce\u9669\u63d0\u9192\u3002";
    }
    return "\u4eca\u5929\u6682\u65e0\u5fc5\u987b\u8001\u677f\u62cd\u677f\u7684\u4e8b\u9879\uff1b\u5c97\u4f4d\u4ecd\u6709 "
        + roleWorkCount + " \u6761\u4e8b\u9879\u5728\u5904\u7406\uff0c\u5176\u4e2d\u9ad8\u98ce\u9669 "
        + highRiskCount + " \u6761\u3002";
  }

  private List<RoleTodoItemResponse> mergeTodoItems(
      List<RoleTodoItemResponse> first,
      List<RoleTodoItemResponse> second
  ) {
    Map<String, RoleTodoItemResponse> byId = new LinkedHashMap<>();
    for (RoleTodoItemResponse item : first == null ? List.<RoleTodoItemResponse>of() : first) {
      byId.putIfAbsent(item.id(), item);
    }
    for (RoleTodoItemResponse item : second == null ? List.<RoleTodoItemResponse>of() : second) {
      byId.putIfAbsent(item.id(), item);
    }
    return new ArrayList<>(byId.values());
  }

  private boolean statusMatches(String status, RoleTodoItemResponse item) {
    return status == null || status.equals(item.status());
  }

  private boolean isBossEscalationTodo(RoleTodoItemResponse item) {
    return item != null && item.id() != null && item.id().startsWith("boss-escalation-");
  }

  private List<BossTodoRiskGroupResponse> highRiskGroups(List<RoleTodoItemResponse> items) {
    Map<String, List<RoleTodoItemResponse>> groups = new LinkedHashMap<>();
    items.stream()
        .filter(item -> "RISK".equals(item.status()))
        .sorted(todoOrder())
        .forEach(item -> groups.computeIfAbsent(riskGroupKey(item), ignored -> new ArrayList<>()).add(item));
    return groups.entrySet()
        .stream()
        .map(entry -> riskGroup(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(BossTodoRiskGroupResponse::highestPriority).reversed()
            .thenComparing(BossTodoRiskGroupResponse::sourceModule)
            .thenComparing(BossTodoRiskGroupResponse::storeName))
        .toList();
  }

  private BossTodoRiskGroupResponse riskGroup(String groupKey, List<RoleTodoItemResponse> items) {
    RoleTodoItemResponse lead = items.stream()
        .max(Comparator.comparingInt(RoleTodoItemResponse::priority))
        .orElse(items.getFirst());
    int highestPriority = items.stream().mapToInt(RoleTodoItemResponse::priority).max().orElse(0);
    return new BossTodoRiskGroupResponse(
        groupKey,
        display(lead.sourceModule(), "\u4e1a\u52a1\u4e8b\u9879"),
        display(lead.ownerName(), "\u5f85\u5206\u6d3e"),
        display(lead.storeName(), display(lead.storeId(), "\u5168\u90e8\u95e8\u5e97")),
        lead.month(),
        items.size(),
        highestPriority >= 95 ? "\u4e25\u91cd\u98ce\u9669" : "\u9ad8\u98ce\u9669",
        highestPriority,
        earliestDueAt(items),
        topCounts(items, item -> display(item.storeName(), display(item.storeId(), "\u5168\u90e8\u95e8\u5e97")), 3),
        lead.action()
    );
  }

  private String riskGroupKey(RoleTodoItemResponse item) {
    return String.join("|",
        display(item.sourceModule(), "\u4e1a\u52a1\u4e8b\u9879"),
        display(item.ownerName(), "\u5f85\u5206\u6d3e"),
        display(item.storeName(), display(item.storeId(), "\u5168\u90e8\u95e8\u5e97")),
        display(item.month(), "\u672a\u5206\u6708")
    );
  }

  private List<BossTodoOwnerGroupResponse> roleProgress(List<RoleTodoItemResponse> items) {
    Map<String, List<RoleTodoItemResponse>> groups = new LinkedHashMap<>();
    items.stream()
        .sorted(todoOrder())
        .forEach(item -> groups.computeIfAbsent(display(item.ownerName(), "\u5f85\u5206\u6d3e"), ignored -> new ArrayList<>()).add(item));
    return groups.entrySet()
        .stream()
        .map(entry -> new BossTodoOwnerGroupResponse(
            entry.getKey(),
            entry.getValue().size(),
            (int) entry.getValue().stream().filter(item -> "RISK".equals(item.status())).count(),
            (int) entry.getValue().stream().filter(item -> "PENDING".equals(item.status())).count(),
            earliestDueAt(entry.getValue()),
            topCounts(entry.getValue(), item -> display(item.sourceModule(), "\u4e1a\u52a1\u4e8b\u9879"), 3)
        ))
        .sorted(Comparator.comparingInt(BossTodoOwnerGroupResponse::openCount).reversed()
            .thenComparing(BossTodoOwnerGroupResponse::ownerName))
        .toList();
  }

  private List<String> topCounts(
      List<RoleTodoItemResponse> items,
      Function<RoleTodoItemResponse, String> picker,
      int limit
  ) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (RoleTodoItemResponse item : items) {
      String key = display(picker.apply(item), "");
      if (!key.isBlank()) {
        counts.merge(key, 1, Integer::sum);
      }
    }
    return counts.entrySet()
        .stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .limit(limit)
        .map(entry -> entry.getKey() + " " + entry.getValue() + "\u6761")
        .toList();
  }

  private String earliestDueAt(List<RoleTodoItemResponse> items) {
    return items.stream()
        .map(RoleTodoItemResponse::dueAt)
        .filter(value -> value != null && !value.isBlank())
        .sorted()
        .findFirst()
        .orElse(null);
  }

  private Comparator<RoleTodoItemResponse> todoOrder() {
    return Comparator
        .comparingInt((RoleTodoItemResponse item) -> statusRank(item.status()))
        .thenComparing(Comparator.comparingInt(RoleTodoItemResponse::priority).reversed())
        .thenComparing(RoleTodoItemResponse::id);
  }

  private Comparator<RoleTodoItemResponse> doneTodoOrder() {
    return Comparator
        .comparing(RoleTodoItemResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(Comparator.comparingInt(RoleTodoItemResponse::priority).reversed())
        .thenComparing(RoleTodoItemResponse::id);
  }

  private Set<String> openSourceTodoIds(long tenantId, RoleTodoAudience audience) {
    return escalationRepository.openSourceTodoIds(tenantId, persistedRoleCode(audience));
  }

  private String persistedRoleCode(RoleTodoAudience audience) {
    return AccessControlService.canonicalRole(audience == null ? null : audience.roleCode());
  }

  private int statusRank(String status) {
    return switch (status) {
      case "RISK" -> 0;
      case "PENDING" -> 1;
      case "REMINDER" -> 2;
      case "DONE" -> 3;
      default -> 9;
    };
  }

  private boolean includesInspection(RoleTodoAudience audience) {
    return List.of(
        RoleTodoAudience.BOSS,
        RoleTodoAudience.SUPERVISOR,
        RoleTodoAudience.STORE_MANAGER
    ).contains(audience);
  }

  private boolean includesWarehouse(RoleTodoAudience audience) {
    return List.of(RoleTodoAudience.BOSS, RoleTodoAudience.WAREHOUSE, RoleTodoAudience.STORE_MANAGER).contains(audience);
  }

  private boolean includesFinance(RoleTodoAudience audience) {
    return List.of(RoleTodoAudience.BOSS, RoleTodoAudience.FINANCE).contains(audience);
  }

  private boolean includesDailyLossReview(RoleTodoAudience audience) {
    return List.of(RoleTodoAudience.BOSS, RoleTodoAudience.SUPERVISOR).contains(audience);
  }

  private boolean includesDataImport(RoleTodoAudience audience) {
    return List.of(RoleTodoAudience.BOSS, RoleTodoAudience.SUPERVISOR).contains(audience);
  }

  private void requireAudienceAccess(AuthUser user, RoleTodoAudience audience) {
    if (AccessControlService.isBoss(user)) {
      return;
    }
    if (sameRole(audience.roleCode(), user == null ? null : user.role())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "当前账号无权查看该角色待办", HttpStatus.FORBIDDEN);
  }

  private StoreQueryScope effectiveStoreScope(
      AuthUser user,
      String requestedStoreId,
      String domainCode
  ) {
    String requested = requestedStoreId == null || requestedStoreId.isBlank()
        ? null
        : requestedStoreId.trim();
    if (accessControl == null) {
      if (isStoreManager(user)) {
        if (user.storeId() == null || user.storeId().isBlank()) {
          throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
        }
        return new StoreQueryScope(false, List.of(user.storeId().trim()));
      }
      return requested == null
          ? new StoreQueryScope(true, List.of())
          : new StoreQueryScope(false, List.of(requested));
    }
    boolean unrestricted = AccessControlService.isBoss(user)
        || accessControl.hasAllDataScope(user, domainCode);
    if (requested != null) {
      if (!unrestricted) {
        // Keep store-scope denials on the central authorization path so every rejected
        // cross-store todo query is recorded in operation_log for later review.
        accessControl.requireStoreAccess(user, domainCode, requested, "查看角色待办");
      }
      return new StoreQueryScope(false, List.of(requested));
    }
    if (unrestricted) {
      return new StoreQueryScope(true, List.of());
    }
    List<String> storeIds = accessControl.allowedStoreIds(user, domainCode).stream()
        .filter(value -> !"all".equalsIgnoreCase(value))
        .sorted()
        .toList();
    return new StoreQueryScope(false, storeIds);
  }

  private <T> List<T> queryStoreRows(StoreQueryScope scope, Function<String, List<T>> query) {
    if (scope.unrestricted()) {
      return query.apply(null);
    }
    if (scope.storeIds().isEmpty()) {
      return List.of();
    }
    return scope.storeIds().stream()
        .flatMap(storeId -> query.apply(storeId).stream())
        .toList();
  }

  private boolean isStoreManager(AuthUser user) {
    return user != null && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private void requireValidStatus(String status) {
    if (status != null && !STATUSES.contains(status)) {
      throw new BusinessException("BAD_STATUS", "待办状态仅支持风险、待处理、提醒或已完成", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireEscalationAudience(RoleTodoAudience audience) {
    if (!ESCALATION_AUDIENCES.contains(audience)) {
      throw new BusinessException("BAD_ROLE", "当前待办类型不支持上报", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireEscalationActor(AuthUser user, RoleTodoAudience audience) {
    if (user != null && sameRole(audience.roleCode(), user.role())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "当前账号无权上报该角色待办", HttpStatus.FORBIDDEN);
  }

  private void requireCompletionActor(AuthUser user, RoleTodoAudience audience) {
    if (user != null && sameRole(audience.roleCode(), user.role())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "当前账号无权完成该角色待办", HttpStatus.FORBIDDEN);
  }

  private void requireBoss(AuthUser user) {
    if (AccessControlService.isBoss(user)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "Only boss can complete boss escalations", HttpStatus.FORBIDDEN);
  }

  private void requireTodoRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireTodoRead(user);
    }
  }

  private void requireTodoTransition(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireTodoTransition(user);
    }
  }

  private boolean sameRole(String left, String right) {
    return AccessControlService.canonicalRole(left).equals(AccessControlService.canonicalRole(right));
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String normalizeEscalationSeverity(String value) {
    if (value == null || value.isBlank()) {
      return "PENDING";
    }
    String normalized = value.trim().toUpperCase();
    if (!List.of("RISK", "PENDING").contains(normalized)) {
      throw new BusinessException("BAD_STATUS", "Escalation severity must be RISK or PENDING", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String escalationModule(RoleTodoAudience audience) {
    return escalationRoleName(persistedRoleCode(audience)) + "\u4e0a\u62a5";
  }

  private String escalationRoleName(String role) {
    return switch (role) {
      case "FINANCE" -> "\u8d22\u52a1";
      case "SUPERVISOR" -> "\u7763\u5bfc";
      case "STORE_MANAGER" -> "\u5e97\u957f";
      case "WAREHOUSE" -> "\u4ed3\u5e93";
      default -> role;
    };
  }

  private String escalationTarget(String role) {
    return switch (role) {
      case "FINANCE" -> "expense";
      case "SUPERVISOR" -> "inspect";
      case "STORE_MANAGER" -> "detail";
      case "WAREHOUSE" -> "warehouse";
      default -> "report";
    };
  }

  private int inspectionPriority(BigDecimal score, BigDecimal fullScore) {
    if (fullScore.compareTo(BigDecimal.ZERO) <= 0) {
      return 85;
    }
    BigDecimal ratio = score.divide(fullScore, 4, RoundingMode.HALF_UP);
    if (ratio.compareTo(new BigDecimal("0.80")) < 0) {
      return 95;
    }
    if (ratio.compareTo(new BigDecimal("0.90")) < 0) {
      return 88;
    }
    return 82;
  }

  private String now() {
    return OffsetDateTime.now(BUSINESS_ZONE).toString();
  }

  private String occurredAt(String date) {
    if (date == null || date.isBlank()) {
      return null;
    }
    return LocalDate.parse(date).atTime(9, 0).atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  private String occurredAtFromMonth(String month) {
    if (month == null || month.isBlank()) {
      return null;
    }
    return LocalDate.parse(month + "-01").atTime(9, 0).atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  private String dueAt(String date) {
    LocalDate target = date == null || date.isBlank() ? LocalDate.now(BUSINESS_ZONE) : LocalDate.parse(date);
    return target.atTime(18, 0).atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  private String dueAtFromDateTime(String dateTime) {
    LocalDate target = dateTime == null || dateTime.isBlank()
        ? LocalDate.now(BUSINESS_ZONE)
        : LocalDate.parse(dateTime.substring(0, 10));
    return target.atTime(18, 0).atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  private String dueAtFromMonth(String month) {
    LocalDate target = month == null || month.isBlank()
        ? LocalDate.now(BUSINESS_ZONE)
        : LocalDate.parse(month + "-01").plusMonths(1).minusDays(1);
    return target.atTime(18, 0).atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  private String monthFromDate(String date) {
    return date == null || date.length() < 7 ? null : date.substring(0, 7);
  }

  private String monthFromDateTime(String dateTime) {
    return dateTime == null || dateTime.length() < 7 ? null : dateTime.substring(0, 7);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String money(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private String percent(BigDecimal ratio) {
    return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
  }

  private String display(String name, String fallback) {
    return name == null || name.isBlank() ? fallback : name;
  }

  private Map<String, Object> params(Object... pairs) {
    Map<String, Object> params = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      Object value = pairs[index + 1];
      if (value != null) {
        params.put((String) pairs[index], value);
      }
    }
    return params;
  }

  private record StoreQueryScope(boolean unrestricted, List<String> storeIds) {
  }
}
