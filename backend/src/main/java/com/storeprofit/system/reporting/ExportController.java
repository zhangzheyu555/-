package com.storeprofit.system.reporting;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.expense.ExpenseClaimResponse;
import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.salary.SalaryRecordResponse;
import com.storeprofit.system.salary.SalaryService;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/export")
public class ExportController {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final AuthService authService;
  private final AccessControlService accessControl;
  private final FinanceService financeService;
  private final ExpenseService expenseService;
  private final SalaryService salaryService;
  private final AuditRepository auditRepository;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public ExportController(
      AuthService authService,
      AccessControlService accessControl,
      FinanceService financeService,
      ExpenseService expenseService,
      SalaryService salaryService,
      AuditRepository auditRepository,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.authService = authService;
    this.accessControl = accessControl;
    this.financeService = financeService;
    this.expenseService = expenseService;
    this.salaryService = salaryService;
    this.auditRepository = auditRepository;
    this.businessScopeResolver = businessScopeResolver;
  }

  public ExportController(
      AuthService authService,
      AccessControlService accessControl,
      FinanceService financeService,
      ExpenseService expenseService,
      SalaryService salaryService,
      AuditRepository auditRepository
  ) {
    this(authService, accessControl, financeService, expenseService, salaryService, auditRepository, null);
  }

  @GetMapping("/profit-ranking.csv")
  public ResponseEntity<byte[]> profitRankingCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    AuthUser user = requireExport(authorization, storeId, month);
    String targetMonth = normalizeMonth(
        month, user, "导出利润排行", "profit-ranking", storeId);
    BusinessScope businessScope = resolveScope(
        user, DataScopeDomains.FINANCE, storeId, brandId, "导出利润排行", targetMonth);
    List<ProfitEntryResponse> rows = financeService.entries(
        user, targetMonth, businessScope.brandId(), businessScope.storeId());
    writeExportLog(user, "导出利润排行", "profit-ranking", businessScope, targetMonth);
    return csv("门店利润_" + targetMonth + ".csv", toProfitCsv(targetMonth, rows));
  }

  @GetMapping("/expenses.csv")
  public ResponseEntity<byte[]> expensesCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    AuthUser user = requireExport(authorization, storeId, month);
    String targetMonth = normalizeMonth(
        month, user, "导出报销记录", "expense-claims", storeId);
    BusinessScope businessScope = resolveScope(
        user, DataScopeDomains.FINANCE, storeId, brandId, "导出报销记录", targetMonth);
    List<ExpenseClaimResponse> rows = expenseService.claims(
        user, targetMonth, businessScope.brandId(), businessScope.storeId(), null);
    writeExportLog(user, "导出报销记录", "expense-claims", businessScope, targetMonth);
    return csv("报销记录-" + targetMonth + ".csv", toExpenseCsv(rows));
  }

  @GetMapping("/salaries.csv")
  public ResponseEntity<byte[]> salariesCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    AuthUser user = requireExport(authorization, storeId, month);
    String targetMonth = normalizeMonth(
        month, user, "导出工资记录", "salary-records", storeId);
    BusinessScope businessScope = resolveScope(
        user, DataScopeDomains.SALARY, storeId, brandId, "导出工资记录", targetMonth);
    List<SalaryRecordResponse> rows = salaryService.records(
        user, targetMonth, businessScope.brandId(), businessScope.storeId());
    writeExportLog(user, "导出工资记录", "salary-records", businessScope, targetMonth);
    return csv("员工工资-" + targetMonth + ".csv", toSalaryCsv(rows));
  }

  private AuthUser requireExport(String authorization, String requestedStoreId, String requestedMonth) {
    AuthUser user = authService.requireUser(authorization);
    accessControl.requireDataExport(user, requestedStoreId, validMonthOrNull(requestedMonth));
    return user;
  }

  private BusinessScope resolveScope(
      AuthUser user,
      String domainCode,
      String storeId,
      Long brandId,
      String action,
      String month
  ) {
    return businessScopeResolver == null
        ? new BusinessScope(storeId, null, brandId, null, null)
        : businessScopeResolver.resolve(user, domainCode, storeId, brandId, action, month);
  }

  private ResponseEntity<byte[]> csv(String fileName, String content) {
    byte[] bytes = ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(fileName, StandardCharsets.UTF_8)
            .build()
            .toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(bytes);
  }

  private void writeExportLog(
      AuthUser user,
      String action,
      String targetId,
      BusinessScope businessScope,
      String month
  ) {
    String scope = businessScope.storeId() == null
        ? "全部授权门店"
        : "门店=" + businessScope.storeId();
    if (businessScope.brandId() != null) {
      scope += "；品牌=" + businessScope.brandId();
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        action,
        "data_export",
        targetId,
        businessScope.storeId(),
        month,
        "已下载 CSV 文件；导出范围：" + scope,
        null,
        null
    ));
  }

  private String normalizeMonth(
      String value,
      AuthUser user,
      String action,
      String targetId,
      String requestedStoreId
  ) {
    try {
      return YearMonth.parse(value == null || value.isBlank() ? YearMonth.now(BUSINESS_ZONE).toString() : value.trim()).toString();
    } catch (RuntimeException ex) {
      auditRepository.writeLog(user, new AuditLogRequest(
          action,
          "data_export",
          targetId,
          blankToNull(requestedStoreId),
          null,
          "导出失败：月份格式不正确",
          null,
          null
      ));
      throw new BusinessException("EXPORT_MONTH_INVALID", "月份格式不正确", org.springframework.http.HttpStatus.BAD_REQUEST);
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String validMonthOrNull(String value) {
    try {
      return YearMonth.parse(value == null || value.isBlank() ? YearMonth.now(BUSINESS_ZONE).toString() : value.trim()).toString();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  static String toProfitCsv(String month, List<ProfitEntryResponse> rows) {
    StringBuilder csv = new StringBuilder(
        "月份,排名,门店编码,门店名称,品牌,区域,营业额,退款金额,优惠金额,原材料成本,包材成本,损耗成本,其他成本,"
            + "房租,人工工资,水电费,物业费,平台佣金,推广费,维修费,设备费,其他费用,备注,"
            + "实收收入,成本合计,费用合计,净利润,净利率,状态\r\n");
    for (int i = 0; i < rows.size(); i++) {
      ProfitEntryResponse row = rows.get(i);
      csv.append(escape(month)).append(',')
          .append(i + 1).append(',')
          .append(escape(row.storeCode())).append(',')
          .append(escape(row.storeName())).append(',')
          .append(escape(row.brandName())).append(',')
          .append(escape(row.area())).append(',')
          .append(row.sales()).append(',')
          .append(row.refund()).append(',')
          .append(row.discount()).append(',')
          .append(row.material()).append(',')
          .append(row.packaging()).append(',')
          .append(row.loss()).append(',')
          .append(row.costOther()).append(',')
          .append(row.rent()).append(',')
          .append(row.labor()).append(',')
          .append(row.utility()).append(',')
          .append(row.property()).append(',')
          .append(row.commission()).append(',')
          .append(row.promo()).append(',')
          .append(row.repair()).append(',')
          .append(row.equip()).append(',')
          .append(row.expOther()).append(',')
          .append(escape(row.note())).append(',')
          .append(row.income()).append(',')
          .append(row.costSum()).append(',')
          .append(row.expenseSum()).append(',')
          .append(row.net()).append(',')
          .append(row.margin().multiply(new java.math.BigDecimal("100")).setScale(1, java.math.RoundingMode.HALF_UP)).append("%,")
          .append(escape(row.risk()))
          .append("\r\n");
    }
    return csv.toString();
  }

  private String toExpenseCsv(List<ExpenseClaimResponse> rows) {
    StringBuilder csv = new StringBuilder("月份,门店,品牌,报销类别,报销金额,报销说明,状态,提交人,审核人,审核时间\n");
    for (ExpenseClaimResponse row : rows) {
      csv.append(escape(row.month())).append(',')
          .append(escape(row.storeName())).append(',')
          .append(escape(row.brandName())).append(',')
          .append(escape(row.category())).append(',')
          .append(row.amount()).append(',')
          .append(escape(row.reason())).append(',')
          .append(escape(row.status())).append(',')
          .append(row.submittedBy() == null ? "" : row.submittedBy()).append(',')
          .append(row.reviewedBy() == null ? "" : row.reviewedBy()).append(',')
          .append(escape(row.reviewedAt() == null ? null : row.reviewedAt().toString()))
          .append('\n');
    }
    return csv.toString();
  }

  static String toSalaryCsv(List<SalaryRecordResponse> rows) {
    StringBuilder csv = new StringBuilder("月份,门店,品牌,员工编号,员工姓名,岗位,基础工资,提成奖金,员工福利（生日）,扣款,实发工资,审核状态,审核时间\n");
    for (SalaryRecordResponse row : rows) {
      java.math.BigDecimal bonus = row.fullAttendance().add(row.commission()).add(row.overtime())
          .add(row.seniority()).add(row.lateNight()).add(row.subsidy()).add(row.performance());
      java.math.BigDecimal deduction = row.deductUniform().add(row.returnUniform());
      csv.append(escape(row.month())).append(',')
          .append(escape(row.storeName())).append(',')
          .append(escape(row.brandName())).append(',')
          .append(escape(row.employeeId())).append(',')
          .append(escape(row.employeeName())).append(',')
          .append(escape(row.position())).append(',')
          .append(row.base()).append(',')
          .append(bonus).append(',')
          .append(row.birthdayBenefit()).append(',')
          .append(deduction).append(',')
          .append(row.netPay() == null ? row.gross() : row.netPay()).append(',')
          .append(escape(salaryStatusLabel(row.status()))).append(',')
          .append(escape(row.reviewedAt() == null ? null : row.reviewedAt().toString()))
          .append('\n');
    }
    return csv.toString();
  }

  private static String salaryStatusLabel(String status) {
    return switch (status == null ? "DRAFT" : status) {
      case "PENDING_REVIEW" -> "待审核";
      case "APPROVED" -> "已完成";
      case "REJECTED" -> "已驳回";
      default -> "草稿";
    };
  }

  private static String escape(String value) {
    String safe = value == null ? "" : value;
    if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }
}
