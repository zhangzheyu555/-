package com.storeprofit.system.finance;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.expense.ExpenseClaimResponse;
import com.storeprofit.system.expense.ExpenseReviewRequest;
import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.salary.SalaryRecordResponse;
import com.storeprofit.system.salary.SalaryService;
import com.storeprofit.system.todo.RoleTodoActionRepository;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionRecord;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionSummary;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoOperationLogRecord;
import com.storeprofit.system.todo.RoleTodoActionResponse;
import com.storeprofit.system.todo.RoleTodoAudience;
import com.storeprofit.system.todo.RoleTodoCompletionRequest;
import com.storeprofit.system.todo.RoleTodoItemResponse;
import com.storeprofit.system.todo.RoleTodoQuery;
import com.storeprofit.system.todo.RoleTodoService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceWorkbenchService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final String DATA_SOURCE = "MySQL结构化数据 / 后端标准接口";

  private final FinanceService financeService;
  private final ExpenseService expenseService;
  private final SalaryService salaryService;
  private final RoleTodoService roleTodoService;
  private final RoleTodoActionRepository actionRepository;

  public FinanceWorkbenchService(
      FinanceService financeService,
      ExpenseService expenseService,
      SalaryService salaryService,
      RoleTodoService roleTodoService,
      RoleTodoActionRepository actionRepository
  ) {
    this.financeService = financeService;
    this.expenseService = expenseService;
    this.salaryService = salaryService;
    this.roleTodoService = roleTodoService;
    this.actionRepository = actionRepository;
  }

  public FinanceWorkbenchResponse workbench(AuthUser user, String month, Long brandId, String storeId) {
    requireFinanceAccess(user);
    String targetMonth = normalizeMonth(month);
    String updatedAt = now();
    RoleTodoQuery todoQuery = new RoleTodoQuery(true, null, 200, brandId, storeId);
    List<RoleTodoItemResponse> financeTodos = roleTodoService.todos(user, RoleTodoAudience.FINANCE, todoQuery).items();
    Map<String, RoleTodoActionSummary> completedActions = actionRepository.completedActions(user.tenantId());
    Set<String> completedTodoIds = completedActions.keySet();

    List<ProfitEntryResponse> profitRisks = financeService.entries(user, targetMonth, brandId, storeId)
        .stream()
        .filter(entry -> !"健康".equals(entry.risk()))
        .filter(entry -> !completedTodoIds.contains(profitTodoId(entry)))
        .sorted(Comparator.comparing(ProfitEntryResponse::margin))
        .limit(12)
        .toList();
    List<ExpenseClaimResponse> expenses = expenseService.claims(user, targetMonth, brandId, storeId, null);
    List<FinanceSalaryCheckResponse> salaryChecks = salaryService.records(user, targetMonth, brandId, storeId)
        .stream()
        .map(this::salaryCheck)
        .filter(check -> check != null && !completedTodoIds.contains("salary-check-" + check.id()))
        .limit(10)
        .toList();
    List<FinanceDataCheckResponse> dataChecks = dataChecks(profitRisks, completedTodoIds);
    List<RoleTodoItemResponse> generatedChecks = new ArrayList<>();
    salaryChecks.forEach(check -> generatedChecks.add(salaryTodo(check, updatedAt)));
    dataChecks.forEach(check -> generatedChecks.add(dataCheckTodo(check, updatedAt)));

    List<RoleTodoItemResponse> openRoleTodos = financeTodos.stream()
        .filter(item -> !"DONE".equals(item.status()))
        .toList();
    List<RoleTodoItemResponse> needMyAction = new ArrayList<>();
    needMyAction.addAll(openRoleTodos);
    needMyAction.addAll(generatedChecks);
    needMyAction = needMyAction.stream()
        .filter(item -> !"DONE".equals(item.status()))
        .sorted(Comparator.comparingInt(RoleTodoItemResponse::priority).reversed().thenComparing(RoleTodoItemResponse::id))
        .limit(30)
        .toList();

    List<RoleTodoItemResponse> doneReview = financeTodos.stream()
        .filter(item -> "DONE".equals(item.status()))
        .sorted(Comparator.comparing(RoleTodoItemResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(20)
        .toList();

    int pendingExpenseCount = (int) expenses.stream().filter(item -> "待审核".equals(item.status())).count();
    int profitRiskStoreCount = new LinkedHashSet<>(profitRisks.stream().map(ProfitEntryResponse::storeId).toList()).size();
    int escalatedToBossCount = (int) financeTodos.stream().filter(RoleTodoItemResponse::escalatedToBoss).count();
    FinanceWorkbenchFocusResponse focus = new FinanceWorkbenchFocusResponse(
        pendingExpenseCount,
        profitRiskStoreCount,
        salaryChecks.size(),
        escalatedToBossCount,
        "今日财务重点：" + pendingExpenseCount + " 条报销待审核，"
            + profitRiskStoreCount + " 家门店利润异常，"
            + salaryChecks.size() + " 条工资数据待核对。"
    );
    return new FinanceWorkbenchResponse(
        "财务",
        DATA_SOURCE,
        updatedAt,
        targetMonth,
        focus,
        needMyAction,
        profitRisks,
        expenses,
        salaryChecks,
        dataChecks,
        doneReview,
        List.of(
            "本月利润最低的门店有哪些？",
            "哪些报销还没有审核？",
            "哪些门店工资占比偏高？",
            "利润异常主要原因是什么？"
        )
    );
  }

  @Transactional
  public Object complete(AuthUser user, String todoId, RoleTodoCompletionRequest request) {
    requireFinanceAccess(user);
    String id = requireTodoId(todoId);
    if (id.startsWith("expense-")) {
      throw new BusinessException("BAD_ACTION", "报销事项请使用通过、驳回或要求补充资料", HttpStatus.CONFLICT);
    }
    if (id.startsWith("salary-check-") || id.startsWith("finance-data-")) {
      String note = request == null || request.note() == null || request.note().isBlank()
          ? "财务已核对"
          : request.note().trim();
      recordGeneratedAction(user, id, "RESOLVE", note);
      return new FinanceTodoActionResponse(id, "已核对", "已处理", note);
    }
    return roleTodoService.resolve(user, RoleTodoAudience.FINANCE, id, request);
  }

  @Transactional
  public FinanceTodoActionResponse reject(AuthUser user, String todoId, FinanceTodoActionRequest request) {
    requireFinanceAccess(user);
    String expenseId = expenseId(todoId);
    String note = actionNote(request, "财务已驳回");
    expenseService.reject(user, expenseId, new ExpenseReviewRequest(note));
    return new FinanceTodoActionResponse(todoId, "已驳回", "已处理", note);
  }

  @Transactional
  public FinanceTodoActionResponse requestInfo(AuthUser user, String todoId, FinanceTodoActionRequest request) {
    requireFinanceAccess(user);
    String id = requireTodoId(todoId);
    String note = actionNote(request, "请门店补充票据或说明");
    String finalNote = note.startsWith("要求补充资料") ? note : "要求补充资料：" + note;
    if (id.startsWith("expense-")) {
      String expenseId = id.substring("expense-".length());
      expenseService.reject(user, expenseId, new ExpenseReviewRequest(finalNote));
      return new FinanceTodoActionResponse(todoId, "要求补充资料", "已处理", finalNote);
    }
    if (id.startsWith("profit-risk-") || id.startsWith("salary-check-") || id.startsWith("finance-data-")) {
      recordGeneratedAction(user, id, "REQUEST_INFO", finalNote, "PENDING_INFO");
      return new FinanceTodoActionResponse(id, "要求补充资料", "待补资料", finalNote);
    }
    throw new BusinessException("BAD_ACTION", "只有财务事项可以要求补充资料", HttpStatus.BAD_REQUEST);
  }

  private void recordGeneratedAction(AuthUser user, String todoId, String actionType, String note) {
    recordGeneratedAction(user, todoId, actionType, note, "DONE");
  }

  private void recordGeneratedAction(AuthUser user, String todoId, String actionType, String note, String status) {
    String actionId = "todo-act-" + UUID.randomUUID();
    actionRepository.saveAction(new RoleTodoActionRecord(
        actionId,
        user.tenantId(),
        todoId,
        actionType,
        status,
        note,
        user.id(),
        user.displayName(),
        user.role()
    ));
    actionRepository.saveOperationLog(new RoleTodoOperationLogRecord(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "finance_todo_" + actionType.toLowerCase(),
        "finance_workbench",
        todoId,
        "",
        "",
        note
    ));
  }

  private String expenseId(String todoId) {
    String id = requireTodoId(todoId);
    if (!id.startsWith("expense-")) {
      throw new BusinessException("BAD_ACTION", "只有报销事项可以执行该操作", HttpStatus.BAD_REQUEST);
    }
    return id.substring("expense-".length());
  }

  private String actionNote(FinanceTodoActionRequest request, String fallback) {
    if (request == null || request.note() == null || request.note().isBlank()) {
      return fallback;
    }
    return request.note().trim();
  }
  private FinanceSalaryCheckResponse salaryCheck(SalaryRecordResponse record) {
    if (record == null) {
      return null;
    }
    BigDecimal gross = amount(record.gross());
    BigDecimal overtime = amount(record.overtime());
    BigDecimal vacationLeft = amount(record.vacationLeft());
    String anomaly = null;
    if (gross.compareTo(new BigDecimal("6000")) >= 0) {
      anomaly = "应发工资偏高，请核对提成、加班和补贴";
    } else if (overtime.compareTo(BigDecimal.ZERO) > 0) {
      anomaly = "存在加班工资，请核对工时和加班单";
    } else if (vacationLeft.compareTo(BigDecimal.ZERO) < 0) {
      anomaly = "假期余额为负，请核对考勤";
    }
    if (anomaly == null) {
      return null;
    }
    return new FinanceSalaryCheckResponse(
        record.id(),
        record.employeeName(),
        record.storeId(),
        record.storeName(),
        record.month(),
        gross,
        anomaly,
        "待财务核对"
    );
  }

  private List<FinanceDataCheckResponse> dataChecks(List<ProfitEntryResponse> profitRisks, Set<String> completedTodoIds) {
    return profitRisks.stream()
        .map(entry -> new FinanceDataCheckResponse(
            "finance-data-profit-" + entry.storeId() + "-" + entry.month(),
            "利润表",
            dataCheckIssue(entry),
            entry.storeId(),
            entry.storeName(),
            entry.month(),
            "待重新核对"
        ))
        .filter(check -> !completedTodoIds.contains(check.id()))
        .limit(8)
        .toList();
  }

  private RoleTodoItemResponse salaryTodo(FinanceSalaryCheckResponse check, String updatedAt) {
    return new RoleTodoItemResponse(
        "salary-check-" + check.id(),
        "工资待核对：" + display(check.storeName(), check.storeId()) + " · " + display(check.employeeName(), "员工"),
        check.anomaly(),
        "PENDING",
        72,
        null,
        check.storeId(),
        check.storeName(),
        check.month(),
        "财务",
        dueAt(),
        "员工工资",
        check.id(),
        check.status(),
        false,
        "salary_record",
        updatedAt,
        updatedAt,
        new RoleTodoActionResponse("salary", "查看工资明细", Map.of("salaryId", check.id(), "month", check.month()))
    );
  }

  private RoleTodoItemResponse dataCheckTodo(FinanceDataCheckResponse check, String updatedAt) {
    return new RoleTodoItemResponse(
        check.id(),
        "财务数据待核对：" + display(check.storeName(), check.storeId()),
        check.issue(),
        "REMINDER",
        68,
        null,
        check.storeId(),
        check.storeName(),
        check.month(),
        "财务",
        dueAt(),
        check.source(),
        check.id(),
        check.status(),
        false,
        "finance_data_check",
        updatedAt,
        updatedAt,
        new RoleTodoActionResponse("report", "查看利润表", Map.of("storeId", check.storeId(), "month", check.month()))
    );
  }

  private String dataCheckIssue(ProfitEntryResponse entry) {
    if (amount(entry.net()).compareTo(BigDecimal.ZERO) < 0) {
      return "净利润为负，请核对成本、工资、报销和费用录入是否准确";
    }
    if (entry.margin() != null && entry.margin().compareTo(new BigDecimal("0.05")) < 0) {
      return "净利率偏低，请核对报销、工资和原料成本";
    }
    return "利润状态异常，请重新核对财务数据";
  }

  private String requireTodoId(String todoId) {
    if (todoId == null || todoId.isBlank()) {
      throw new BusinessException("BAD_TODO", "待办事项不能为空", HttpStatus.BAD_REQUEST);
    }
    return todoId.trim();
  }

  private void requireFinanceAccess(AuthUser user) {
    if (user != null && List.of("ADMIN", "BOSS", "FINANCE").contains(user.role())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "无权访问财务工作台", HttpStatus.FORBIDDEN);
  }

  private String normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE).toString();
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_MONTH", "月份格式必须为 YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  private String profitTodoId(ProfitEntryResponse entry) {
    return "profit-risk-" + entry.storeId() + "-" + entry.month();
  }

  private String monthFromTodoId(String todoId) {
    if (todoId == null || todoId.length() < 7) {
      return null;
    }
    int index = todoId.lastIndexOf("-20");
    if (index < 0 || todoId.length() < index + 8) {
      return null;
    }
    return todoId.substring(index + 1, index + 8);
  }

  private String display(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String dueAt() {
    return OffsetDateTime.now(BUSINESS_ZONE).withHour(18).withMinute(0).withSecond(0).withNano(0).toString();
  }

  private String now() {
    return OffsetDateTime.now(BUSINESS_ZONE).toString();
  }
}
