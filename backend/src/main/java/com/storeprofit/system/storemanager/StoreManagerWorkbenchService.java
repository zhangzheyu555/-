package com.storeprofit.system.storemanager;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.expense.ExpenseClaimResponse;
import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreManagerBusinessReminder;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreManagerFocus;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreManagerRecords;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreManagerWorkbenchItem;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreScope;
import com.storeprofit.system.storemanager.StoreManagerInspectionPageResponse.StoreManagerInspectionSummary;
import com.storeprofit.system.todo.RoleTodoAudience;
import com.storeprofit.system.todo.RoleTodoCompletionRequest;
import com.storeprofit.system.todo.RoleTodoItemResponse;
import com.storeprofit.system.todo.RoleTodoQuery;
import com.storeprofit.system.todo.RoleTodoResponse;
import com.storeprofit.system.todo.RoleTodoService;
import com.storeprofit.system.todo.RoleTodoActionResultResponse;
import com.storeprofit.system.warehouse.WarehouseDeliveryResponse;
import com.storeprofit.system.warehouse.WarehouseOverviewResponse;
import com.storeprofit.system.warehouse.WarehouseRepository;
import com.storeprofit.system.warehouse.WarehouseRequisitionResponse;
import com.storeprofit.system.warehouse.WarehouseService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StoreManagerWorkbenchService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final String DATA_SOURCE = "MySQL结构化数据 / 店长工作台聚合接口";

  private final RoleTodoService roleTodoService;
  private final WarehouseService warehouseService;
  private final WarehouseRepository warehouseRepository;
  private final FinanceService financeService;
  private final InspectionService inspectionService;
  private final ExpenseService expenseService;

  public StoreManagerWorkbenchService(
      RoleTodoService roleTodoService,
      WarehouseService warehouseService,
      WarehouseRepository warehouseRepository,
      FinanceService financeService,
      InspectionService inspectionService,
      ExpenseService expenseService
  ) {
    this.roleTodoService = roleTodoService;
    this.warehouseService = warehouseService;
    this.warehouseRepository = warehouseRepository;
    this.financeService = financeService;
    this.inspectionService = inspectionService;
    this.expenseService = expenseService;
  }

  public StoreManagerWorkbenchResponse workbench(AuthUser user) {
    String storeId = requireStoreManagerStore(user);
    String storeName = warehouseRepository.storeName(user.tenantId(), storeId).orElse(storeId);
    String updatedAt = OffsetDateTime.now(BUSINESS_ZONE).toString();

    RoleTodoResponse todoResponse = roleTodoService.todos(
        user,
        RoleTodoAudience.STORE_MANAGER,
        new RoleTodoQuery(true, null, 100, null, null)
    );
    List<RoleTodoItemResponse> openTodos = todoResponse.items().stream()
        .filter(item -> !"DONE".equals(item.status()))
        .toList();
    List<RoleTodoItemResponse> doneTodos = todoResponse.items().stream()
        .filter(item -> "DONE".equals(item.status()))
        .sorted(Comparator.comparing(RoleTodoItemResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(20)
        .toList();

    WarehouseOverviewResponse warehouse = warehouseService.overview(user);
    List<ExpenseClaimResponse> rejectedExpenses = rejectedExpenses(user, storeId);
    StoreManagerBusinessReminder businessReminder = businessReminder(user, storeId);
    List<InspectionRecordResponse> rectifications = inspectionService.records(user, null, null, null, storeId, false)
        .stream()
        .sorted(Comparator.comparing(InspectionRecordResponse::inspectionDate, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(20)
        .toList();

    List<StoreManagerWorkbenchItem> needMyAction = new ArrayList<>();
    openTodos.stream().map(this::fromTodo).forEach(needMyAction::add);
    rejectedExpenses.stream().map(this::fromRejectedExpense).forEach(needMyAction::add);
    businessRiskItem(storeId, storeName, businessReminder).ifPresent(needMyAction::add);
    needMyAction.sort(Comparator.comparingInt(StoreManagerWorkbenchItem::priority).reversed()
        .thenComparing(StoreManagerWorkbenchItem::id));

    int receiptCount = (int) warehouse.requisitions().stream().filter(row -> "SHIPPED".equals(row.status())).count();
    int rectificationCount = (int) openTodos.stream().filter(this::isInspectionTodo).count();
    int businessRiskCount = businessReminder.reminders().isEmpty() ? 0 : 1;
    StoreManagerFocus focus = new StoreManagerFocus(
        needMyAction.size(),
        receiptCount,
        rectificationCount,
        rejectedExpenses.size(),
        businessRiskCount,
        focusSummary(needMyAction.size(), receiptCount, rectificationCount, rejectedExpenses.size(), businessRiskCount)
    );

    StoreManagerRecords records = new StoreManagerRecords(
        warehouse.requisitions().stream().limit(30).toList(),
        warehouse.deliveries().stream().limit(30).toList(),
        recentInspections(user, storeId),
        recentExpenses(user, storeId),
        doneTodos
    );

    return new StoreManagerWorkbenchResponse(
        "店长",
        DATA_SOURCE,
        updatedAt,
        new StoreScope(storeId, storeName),
        focus,
        needMyAction.stream().limit(5).toList(),
        needMyAction,
        businessReminder,
        warehouse,
        rectifications,
        records
    );
  }

  public StoreManagerInspectionPageResponse inspections(AuthUser user) {
    String storeId = requireStoreManagerStore(user);
    String storeName = warehouseRepository.storeName(user.tenantId(), storeId).orElse(storeId);
    String currentMonth = LocalDate.now(BUSINESS_ZONE).toString().substring(0, 7);
    List<InspectionRecordResponse> records = inspectionService.records(user, null, null, null, storeId, null).stream()
        .sorted(Comparator.comparing(InspectionRecordResponse::inspectionDate, Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
    int totalCount = records.size();
    int monthCount = (int) records.stream()
        .filter(record -> text(record.inspectionDate()).startsWith(currentMonth))
        .count();
    int redlineCount = (int) records.stream()
        .filter(record -> !record.passed())
        .count();
    BigDecimal averageScore = totalCount == 0
        ? zero()
        : records.stream()
            .map(InspectionRecordResponse::score)
            .map(this::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(new BigDecimal(totalCount), 2, RoundingMode.HALF_UP);
    return new StoreManagerInspectionPageResponse(
        "店长",
        "MySQL inspection_record / 当前店长绑定门店",
        OffsetDateTime.now(BUSINESS_ZONE).toString(),
        new StoreScope(storeId, storeName),
        new StoreManagerInspectionSummary(totalCount, monthCount, averageScore, redlineCount),
        records
    );
  }

  public RoleTodoActionResultResponse submitRectification(
      AuthUser user,
      String inspectionId,
      RoleTodoCompletionRequest request
  ) {
    requireStoreManagerStore(user);
    String id = requireText(inspectionId, "BAD_INSPECTION", "巡店记录不能为空");
    return roleTodoService.resolve(user, RoleTodoAudience.STORE_MANAGER, "inspection-" + id, request);
  }

  private StoreManagerWorkbenchItem fromTodo(RoleTodoItemResponse item) {
    String source = text(item.sourceModule());
    String id = text(item.id());
    String label = "进入处理";
    String target = item.action() == null ? "" : text(item.action().target());
    if (id.startsWith("store-receipt-") || source.contains("\u4ed3\u5e93")) {
      label = "\u53bb\u4ed3\u5e93\u4e2d\u5fc3\u5904\u7406";
      target = "warehouse";
    } else if (id.startsWith("inspection-") || source.contains("巡店")) {
      label = "去提交整改";
      target = "inspect";
    } else if (source.contains("报销")) {
      label = "去补充报销";
      target = "expense";
    } else if (source.contains("利润") || source.contains("经营")) {
      label = "查看本店数据";
      target = "report";
    }
    return new StoreManagerWorkbenchItem(
        id,
        text(item.title()),
        text(item.summary()),
        statusLabel(item.status()),
        item.priority(),
        source,
        text(item.sourceRecordId()),
        text(item.dueAt()),
        label,
        target,
        text(item.month()),
        text(item.storeId()),
        text(item.storeName())
    );
  }

  private StoreManagerWorkbenchItem fromRejectedExpense(ExpenseClaimResponse claim) {
    return new StoreManagerWorkbenchItem(
        "expense-rejected-" + claim.id(),
        "报销被退回待补充",
        "报销金额 " + money(claim.amount()) + "，请补充说明或凭证后重新提交。",
        "待补充",
        78,
        "报销",
        claim.id(),
        "",
        "去补充报销",
        "expense",
        claim.month(),
        claim.storeId(),
        claim.storeName()
    );
  }

  private java.util.Optional<StoreManagerWorkbenchItem> businessRiskItem(
      String storeId,
      String storeName,
      StoreManagerBusinessReminder reminder
  ) {
    if (reminder.reminders().isEmpty()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new StoreManagerWorkbenchItem(
        "business-reminder-" + storeId + "-" + text(reminder.month()),
        "本店经营提醒",
        String.join("；", reminder.reminders()),
        "提醒",
        64,
        "本店经营",
        text(reminder.month()),
        "",
        "查看本店数据",
        "report",
        text(reminder.month()),
        storeId,
        storeName
    ));
  }

  private StoreManagerBusinessReminder businessReminder(AuthUser user, String storeId) {
    List<String> months = financeService.months(user).stream().limit(8).toList();
    ProfitEntryResponse current = null;
    ProfitEntryResponse previous = null;
    for (String month : months) {
      List<ProfitEntryResponse> entries = financeService.entries(user, month, null, storeId);
      if (!entries.isEmpty()) {
        if (current == null) {
          current = entries.getFirst();
        } else {
          previous = entries.getFirst();
          break;
        }
      }
    }
    if (current == null) {
      return new StoreManagerBusinessReminder(
          null,
          zero(),
          zero(),
          zero(),
          zero(),
          "暂无数据",
          null,
          zero(),
          zero(),
          List.of("本店暂无可展示的经营数据，请等待财务录入。")
      );
    }
    BigDecimal costRatio = ratio(current.costSum(), current.income());
    BigDecimal incomeChangeRate = previous == null ? zero() : ratio(current.income().subtract(previous.income()), previous.income());
    List<String> reminders = new ArrayList<>();
    if (!"健康".equals(current.risk())) {
      reminders.add(current.risk());
    }
    if (costRatio.compareTo(new BigDecimal("0.55")) > 0) {
      reminders.add("本月成本占比偏高，请核对原材料和包材使用。");
    }
    if (previous != null && incomeChangeRate.compareTo(new BigDecimal("-0.10")) < 0) {
      reminders.add("本月营业额比上月下降超过 10%，请关注客流和活动效果。");
    }
    return new StoreManagerBusinessReminder(
        current.month(),
        amount(current.income()),
        amount(current.net()),
        ratio(current.net(), current.income()),
        costRatio,
        current.risk(),
        previous == null ? null : previous.month(),
        previous == null ? zero() : amount(previous.income()),
        incomeChangeRate,
        reminders
    );
  }

  private List<ExpenseClaimResponse> rejectedExpenses(AuthUser user, String storeId) {
    return recentExpenseMonths(user, storeId).stream()
        .flatMap(month -> expenseService.claims(user, month, null, storeId, "已驳回").stream())
        .limit(20)
        .toList();
  }

  private List<ExpenseClaimResponse> recentExpenses(AuthUser user, String storeId) {
    return recentExpenseMonths(user, storeId).stream()
        .flatMap(month -> expenseService.claims(user, month, null, storeId, null).stream())
        .limit(30)
        .toList();
  }

  private List<String> recentExpenseMonths(AuthUser user, String storeId) {
    List<String> months = financeService.months(user).stream().limit(6).toList();
    if (!months.isEmpty()) {
      return months;
    }
    return List.of(LocalDate.now(BUSINESS_ZONE).toString().substring(0, 7));
  }

  private List<InspectionRecordResponse> recentInspections(AuthUser user, String storeId) {
    String from = LocalDate.now(BUSINESS_ZONE).minusDays(30).toString();
    return inspectionService.records(user, from, null, null, storeId, null).stream()
        .sorted(Comparator.comparing(InspectionRecordResponse::inspectionDate, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(30)
        .toList();
  }

  private String focusSummary(
      int pendingCount,
      int receiptCount,
      int rectificationCount,
      int rejectedExpenseCount,
      int businessRiskCount
  ) {
    if (pendingCount == 0) {
      return "今天没有需要你处理的事项。";
    }
    List<String> parts = new ArrayList<>();
    if (receiptCount > 0) {
      parts.add(receiptCount + " 张叫货单待确认收货");
    }
    if (rectificationCount > 0) {
      parts.add(rectificationCount + " 个巡店问题待整改");
    }
    if (rejectedExpenseCount > 0) {
      parts.add(rejectedExpenseCount + " 张报销待补充");
    }
    if (businessRiskCount > 0) {
      parts.add("1 条本店经营提醒");
    }
    if (parts.isEmpty()) {
      parts.add(pendingCount + " 件事项待处理");
    }
    return "今天重点处理：" + String.join("、", parts) + "。";
  }

  private boolean isInspectionTodo(RoleTodoItemResponse item) {
    return text(item.id()).startsWith("inspection-") || text(item.sourceModule()).contains("巡店");
  }

  private String requireStoreManagerStore(AuthUser user) {
    if (user == null || !"STORE_MANAGER".equals(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅店长可以访问本店工作台", HttpStatus.FORBIDDEN);
    }
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "店长账号未绑定门店", HttpStatus.FORBIDDEN);
    }
    return user.storeId().trim();
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String statusLabel(String status) {
    return switch (text(status).toUpperCase()) {
      case "RISK" -> "需要处理";
      case "PENDING" -> "待处理";
      case "REMINDER" -> "提醒";
      case "DONE" -> "已处理";
      default -> text(status);
    };
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private String money(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? zero() : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal zero() {
    return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    BigDecimal den = denominator == null ? BigDecimal.ZERO : denominator;
    if (den.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return (numerator == null ? BigDecimal.ZERO : numerator).divide(den, 4, RoundingMode.HALF_UP);
  }
}
