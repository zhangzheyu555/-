package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SalaryExportService {
  private final SalaryQueryService salaryQueryService;
  private final SalaryRepository salaryRepository;
  private final AccessControlService accessControl;

  public SalaryExportService(SalaryQueryService salaryQueryService, SalaryRepository salaryRepository, AccessControlService accessControl) {
    this.salaryQueryService = salaryQueryService;
    this.salaryRepository = salaryRepository;
    this.accessControl = accessControl;
  }

  public String exportCsv(AuthUser user, String month, Long brandId, String storeId) {
    if (accessControl != null) {
      accessControl.requireDataExport(user);
    } else if (!AccessControlService.hasAnyRole(user, "FINANCE")) {
      throw new BusinessException("FORBIDDEN", "当前角色不能导出工资数据", HttpStatus.FORBIDDEN);
    }
    List<SalaryRecordResponse> rows = salaryQueryService.records(user, month, brandId, storeId);
    StringBuilder csv = new StringBuilder();
    csv.append("工号,姓名,门店,品牌,岗位,月份,基本工资,社保补助,岗位工资,餐补,全勤,提成,加班工资,工龄工资,深夜班,补贴,绩效,扣工服费,返工服费,应发工资,状态\n");
    for (SalaryRecordResponse row : rows) {
      csv.append(escapeCsvValue(row.employeeId())).append(",");
      csv.append(escapeCsvValue(row.employeeName())).append(",");
      csv.append(escapeCsvValue(row.storeName())).append(",");
      csv.append(escapeCsvValue(row.brandName())).append(",");
      csv.append(escapeCsvValue(row.position())).append(",");
      csv.append(row.month()).append(",");
      csv.append(row.base()).append(",");
      csv.append(row.social()).append(",");
      csv.append(row.post()).append(",");
      csv.append(row.meal()).append(",");
      csv.append(row.fullAttendance()).append(",");
      csv.append(row.commission()).append(",");
      csv.append(row.overtime()).append(",");
      csv.append(row.seniority()).append(",");
      csv.append(row.lateNight()).append(",");
      csv.append(row.subsidy()).append(",");
      csv.append(row.performance()).append(",");
      csv.append(row.deductUniform()).append(",");
      csv.append(row.returnUniform()).append(",");
      csv.append(row.gross()).append(",");
      csv.append(statusLabel(row.status())).append("\n");
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_export", "csv-" + month,
        storeId == null ? "" : storeId, month, "导出工资CSV " + rows.size() + " 条");
    return csv.toString();
  }

  /**
   * Escape CSV value and prevent formula injection.
   * If the value starts with = + - @, prepend a single quote to neutralize spreadsheet formula execution.
   */
  static String escapeCsvValue(String value) {
    if (value == null || value.isBlank()) return "";
    String escaped = value.replace("\"", "\"\"");
    // Prevent CSV formula injection: prepend ' to neutralize =, +, -, @
    if (!escaped.isEmpty() && "+-=\"\'@".indexOf(escaped.charAt(0)) >= 0) {
      escaped = "'" + escaped;
    }
    if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
      return "\"" + escaped + "\"";
    }
    return escaped;
  }

  private String statusLabel(String status) {
    return switch (status == null ? "DRAFT" : status) {
      case "DRAFT" -> "草稿";
      case "SUBMITTED" -> "待审核";
      case "APPROVED" -> "已审核";
      case "REJECTED" -> "已驳回";
      case "PAID" -> "已发放";
      case "LOCKED" -> "已锁定";
      default -> status;
    };
  }
}
