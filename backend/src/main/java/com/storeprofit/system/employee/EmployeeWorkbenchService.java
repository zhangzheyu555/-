package com.storeprofit.system.employee;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.AssistantEntry;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.Profile;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.Store;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.WorkItem;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.WorkSummary;
import com.storeprofit.system.employeeassistant.EmployeeAssistantService;
import com.storeprofit.system.employeeassistant.EmployeeAssistantStatusResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamAssignmentResponse;
import com.storeprofit.system.operations.ExamCenterRepository;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.salary.SalaryRecordResponse;
import com.storeprofit.system.salary.SalaryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmployeeWorkbenchService {
  private static final Set<String> OPEN_EXAM_STATUSES = Set.of(
      "NOT_STARTED", "ASSIGNED", "OVERDUE", "RETAKE_PENDING");
  private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final AccessControlService accessControl;
  private final OrganizationRepository organizationRepository;
  private final ExamCenterRepository examCenterRepository;
  private final EmployeeRepository employeeRepository;
  private final SalaryRepository salaryRepository;
  private final EmployeeAssistantService employeeAssistantService;

  public EmployeeWorkbenchService(
      AccessControlService accessControl,
      OrganizationRepository organizationRepository,
      ExamCenterRepository examCenterRepository,
      EmployeeRepository employeeRepository,
      SalaryRepository salaryRepository,
      EmployeeAssistantService employeeAssistantService
  ) {
    this.accessControl = accessControl;
    this.organizationRepository = organizationRepository;
    this.examCenterRepository = examCenterRepository;
    this.employeeRepository = employeeRepository;
    this.salaryRepository = salaryRepository;
    this.employeeAssistantService = employeeAssistantService;
  }

  public EmployeeWorkbenchResponse workbench(AuthUser user) {
    accessControl.requireEmployeeWorkbench(user);
    String storeId = requiredStoreId(user);
    StoreResponse store = requiredStore(user.tenantId(), storeId);
    List<ExamAssignmentResponse> assignments = examCenterRepository.assignments(
        user.tenantId(), storeId, user.id());
    AssistantEntry assistant = assistantEntry(user);
    List<WorkItem> workItems = workItems(assignments, assistant);
    return new EmployeeWorkbenchResponse(
        new Profile(user.id(), user.displayName(), AccessControlService.canonicalRole(user.role())),
        new Store(store.id(), store.name(), store.brandName()),
        workItems,
        summary(assignments, workItems),
        assistant
    );
  }

  public EmployeeProfileResponse profile(AuthUser user) {
    accessControl.requireEmployeeWorkbench(user);
    String storeId = requiredStoreId(user);
    StoreResponse store = requiredStore(user.tenantId(), storeId);
    Optional<EmployeeResponse> archive = employeeArchive(user, storeId);
    Optional<SalaryRecordResponse> salary = salaryRepository.latestEmployeeRecord(
        user.tenantId(),
        storeId,
        salaryEmployeeIds(user, archive),
        archive.map(EmployeeResponse::name).orElse(null)
    );
    AssistantEntry assistant = assistantEntry(user);
    List<ExamAssignmentResponse> assignments = examCenterRepository.assignments(
        user.tenantId(), storeId, user.id());

    return new EmployeeProfileResponse(
        new EmployeeProfileResponse.Profile(
            user.id(), user.username(), user.displayName(), AccessControlService.canonicalRole(user.role())),
        new EmployeeProfileResponse.Store(store.id(), store.name(), store.brandName()),
        archive.map(this::archiveInfo).orElseGet(this::missingArchive),
        salary.map(this::salaryInfo).orElseGet(() -> missingSalary(archive)),
        profileChecklist(archive, salary, assignments, assistant)
    );
  }

  private StoreResponse requiredStore(long tenantId, String storeId) {
    return organizationRepository.store(tenantId, storeId)
        .orElseThrow(() -> new BusinessException(
            "EMPLOYEE_STORE_NOT_FOUND",
            "员工账号绑定的门店不存在，请老板在账号权限中重新绑定门店。",
            HttpStatus.BAD_REQUEST
        ));
  }

  private String requiredStoreId(AuthUser user) {
    String storeId = user == null ? null : user.storeId();
    if (storeId == null || storeId.isBlank()) {
      throw new BusinessException(
          "EMPLOYEE_STORE_REQUIRED",
          "员工账号未绑定门店，请老板在账号权限中绑定门店。",
          HttpStatus.BAD_REQUEST
      );
    }
    return storeId.trim();
  }

  private AssistantEntry assistantEntry(AuthUser user) {
    if (!accessControl.hasPermission(user, PermissionCodes.EMPLOYEE_ASSISTANT_USE)) {
      return new AssistantEntry(false, "DISABLED", "员工服务助手暂未授权，请联系老板开通。", "");
    }
    EmployeeAssistantStatusResponse status = employeeAssistantService.health(user);
    boolean canAsk = status != null && status.canAsk();
    return new AssistantEntry(
        canAsk,
        status == null || status.state() == null ? "UNAVAILABLE" : status.state().name(),
        status == null ? "员工服务助手暂时不可用，请稍后重试" : status.message(),
        canAsk ? "/employee-assistant" : ""
    );
  }

  private List<WorkItem> workItems(
      List<ExamAssignmentResponse> assignments,
      AssistantEntry assistant
  ) {
    ArrayList<WorkItem> items = new ArrayList<>();
    for (ExamAssignmentResponse assignment : assignments) {
      if (!OPEN_EXAM_STATUSES.contains(assignment.status())) {
        continue;
      }
      items.add(examItem(assignment));
      if (items.size() >= 6) {
        break;
      }
    }
    if (assistant.enabled()) {
      items.add(new WorkItem(
          "employee-assistant",
          "ASSISTANT",
          "员工服务助手",
          "遇到顾客服务、门店话术或异常处理问题时，先查询标准处理建议。",
          assistant.state(),
          "NORMAL",
          "去提问",
          assistant.route()
      ));
    }
    return List.copyOf(items);
  }

  private WorkItem examItem(ExamAssignmentResponse assignment) {
    String status = assignment.status();
    String dueAt = assignment.dueAt() == null || assignment.dueAt().isBlank()
        ? "未设置截止时间"
        : "截止 " + assignment.dueAt();
    return new WorkItem(
        "exam-" + assignment.id(),
        "EXAM",
        assignment.examTitle(),
        assignment.statusLabel() + "，" + dueAt,
        status,
        "OVERDUE".equals(status) ? "HIGH" : "NORMAL",
        "NOT_STARTED".equals(status) ? "查看安排" : "去考试",
        "/employee/exams?assignmentId=" + assignment.id()
    );
  }

  private WorkSummary summary(List<ExamAssignmentResponse> assignments, List<WorkItem> workItems) {
    int overdue = 0;
    int completed = 0;
    int retakePending = 0;
    int pending = 0;
    for (ExamAssignmentResponse assignment : assignments) {
      String status = assignment.status();
      if ("COMPLETED".equals(status) || "REVIEW_PENDING".equals(status)) {
        completed++;
      } else if ("OVERDUE".equals(status)) {
        overdue++;
        pending++;
      } else if ("RETAKE_PENDING".equals(status)) {
        retakePending++;
        pending++;
      } else if (OPEN_EXAM_STATUSES.contains(status)) {
        pending++;
      }
    }
    return new WorkSummary(workItems.size(), pending, overdue, completed, retakePending);
  }

  private Optional<EmployeeResponse> employeeArchive(AuthUser user, String storeId) {
    List<EmployeeResponse> employees = employeeRepository.records(user.tenantId(), null, storeId, null);
    List<EmployeeResponse> byAuthUser = employees.stream()
        .filter(employee -> employee.authUserId() != null && employee.authUserId().longValue() == user.id())
        .toList();
    if (byAuthUser.size() == 1) {
      return Optional.of(byAuthUser.get(0));
    }
    String username = normalize(user.username());
    String userIdText = String.valueOf(user.id());
    List<EmployeeResponse> byAccount = employees.stream()
        .filter(employee -> equalsIgnoreCase(employee.accountUsername(), username)
            || equalsIgnoreCase(employee.id(), username)
            || equalsIgnoreCase(employee.id(), userIdText))
        .toList();
    if (byAccount.size() == 1) {
      return Optional.of(byAccount.get(0));
    }
    String displayName = normalize(user.displayName());
    List<EmployeeResponse> byName = employees.stream()
        .filter(employee -> equalsTrimmed(employee.name(), displayName))
        .toList();
    return byName.size() == 1 ? Optional.of(byName.get(0)) : Optional.empty();
  }

  private List<String> salaryEmployeeIds(AuthUser user, Optional<EmployeeResponse> archive) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    archive.map(EmployeeResponse::id).map(this::normalize).filter(value -> !value.isBlank()).ifPresent(ids::add);
    String username = normalize(user.username());
    if (!username.isBlank()) {
      ids.add(username);
    }
    ids.add(String.valueOf(user.id()));
    return List.copyOf(ids);
  }

  private EmployeeProfileResponse.Archive archiveInfo(EmployeeResponse employee) {
    return new EmployeeProfileResponse.Archive(
        true,
        employee.id(),
        employee.name(),
        employee.position(),
        employee.employmentType(),
        employee.status(),
        employee.hireDate(),
        money(employee.baseSalary()),
        "已关联员工档案，工资会按该档案和工资记录展示。"
    );
  }

  private EmployeeProfileResponse.Archive missingArchive() {
    return new EmployeeProfileResponse.Archive(
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "还没有匹配到员工档案。请老板或财务在员工档案中补齐姓名、岗位和基础工资。"
    );
  }

  private EmployeeProfileResponse.Salary salaryInfo(SalaryRecordResponse salary) {
    return new EmployeeProfileResponse.Salary(
        true,
        salary.id(),
        salary.month(),
        salary.status(),
        salaryStatusLabel(salary.status()),
        salary.employeeId(),
        salary.employeeName(),
        salary.position(),
        salary.attendance(),
        money(salary.base()),
        money(salary.gross()),
        money(salary.netPay()),
        money(salary.commission()),
        money(salary.overtime()),
        money(salary.performance()),
        money(salary.deductUniform()),
        money(salary.returnUniform()),
        money(salary.vacationLeft()),
        salary.vacationNote(),
        salary.reviewedAt() == null ? null : salary.reviewedAt().format(DATE_TIME_FORMAT),
        salary.paidAt() == null ? null : salary.paidAt().format(DATE_TIME_FORMAT),
        salaryMessage(salary)
    );
  }

  private EmployeeProfileResponse.Salary missingSalary(Optional<EmployeeResponse> archive) {
    String message = archive.isPresent()
        ? "已找到员工档案，但还没有生成工资记录。请财务在工资模块生成或录入工资。"
        : "还没有员工档案，系统无法安全匹配工资。请先补员工档案，再生成工资。";
    return new EmployeeProfileResponse.Salary(
        false,
        null,
        null,
        "MISSING",
        "未生成",
        archive.map(EmployeeResponse::id).orElse(null),
        archive.map(EmployeeResponse::name).orElse(null),
        archive.map(EmployeeResponse::position).orElse(null),
        null,
        archive.map(EmployeeResponse::baseSalary).map(this::money).orElse(null),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        message
    );
  }

  private List<EmployeeProfileResponse.ChecklistItem> profileChecklist(
      Optional<EmployeeResponse> archive,
      Optional<SalaryRecordResponse> salary,
      List<ExamAssignmentResponse> assignments,
      AssistantEntry assistant
  ) {
    ArrayList<EmployeeProfileResponse.ChecklistItem> items = new ArrayList<>();
    if (archive.isEmpty()) {
      items.add(new EmployeeProfileResponse.ChecklistItem(
          "archive",
          "员工档案未关联",
          "需要在员工档案中建立或匹配该账号，工资和岗位信息才能完整显示。",
          "待处理",
          "HIGH"
      ));
    }
    if (salary.isEmpty()) {
      items.add(new EmployeeProfileResponse.ChecklistItem(
          "salary",
          "工资记录未生成",
          archive.isPresent() ? "请财务生成本月工资或录入历史工资。" : "先补员工档案，再生成工资记录。",
          "待处理",
          "HIGH"
      ));
    }
    boolean hasOpenExam = assignments.stream().anyMatch(assignment -> OPEN_EXAM_STATUSES.contains(assignment.status()));
    if (!hasOpenExam) {
      items.add(new EmployeeProfileResponse.ChecklistItem(
          "exam",
          "暂无待学考试",
          "当前没有新的培训或考试安排。",
          "正常",
          "LOW"
      ));
    }
    if (!assistant.enabled()) {
      items.add(new EmployeeProfileResponse.ChecklistItem(
          "assistant",
          "员工服务助手未开通",
          "如需让员工查询标准话术和处理建议，请老板在账号权限中开通。",
          "可选",
          "NORMAL"
      ));
    }
    if (items.isEmpty()) {
      items.add(new EmployeeProfileResponse.ChecklistItem(
          "complete",
          "资料完整",
          "员工档案、工资记录、培训和助手入口已正常关联。",
          "正常",
          "LOW"
      ));
    }
    return List.copyOf(items);
  }

  private String salaryStatusLabel(String status) {
    return switch (normalize(status).toUpperCase(Locale.ROOT)) {
      case "PENDING_GENERATION" -> "待生成";
      case "DRAFT" -> "草稿";
      case "SUBMITTED" -> "待审核";
      case "APPROVED" -> "已审核";
      case "PAID" -> "已发放";
      case "LOCKED" -> "已锁定";
      case "REJECTED" -> "已退回";
      case "MISSING" -> "未生成";
      default -> status == null || status.isBlank() ? "未生成" : status;
    };
  }

  private String salaryMessage(SalaryRecordResponse salary) {
    String status = normalize(salary.status()).toUpperCase(Locale.ROOT);
    if ("PAID".equals(status) || "LOCKED".equals(status)) {
      return salary.month() + " 工资" + salaryStatusLabel(salary.status()) + "，可查看实发金额。";
    }
    return salary.month() + " 工资状态为" + salaryStatusLabel(salary.status()) + "，金额以财务最终审核为准。";
  }

  private BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private boolean equalsIgnoreCase(String left, String right) {
    return normalize(left).equalsIgnoreCase(normalize(right));
  }

  private boolean equalsTrimmed(String left, String right) {
    return normalize(left).equals(normalize(right));
  }
}
