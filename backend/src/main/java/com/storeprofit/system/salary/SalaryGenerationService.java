package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryGenerationService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // ===== 2026 工资口径常量（源：《2026年3月工资总合计.xlsx》Sheet1 保底表，已与 2026-03 全量 114 人实发核对一致）=====
  private static final BigDecimal STD_SOCIAL = new BigDecimal("800");          // 社保补助/月
  private static final BigDecimal STD_MEAL = new BigDecimal("300");            // 餐补/月
  private static final BigDecimal FULL_ATTENDANCE_BONUS = new BigDecimal("200");
  private static final BigDecimal BIRTHDAY_BENEFIT = new BigDecimal("200");
  private static final BigDecimal DEFAULT_FULL_MONTH_DAYS = new BigDecimal("26");
  private static final BigDecimal INTERN_HOURLY_RATE = new BigDecimal("15");   // 实习时薪（旧值表）
  private static final BigDecimal PART_TIME_HOURLY_RATE = new BigDecimal("13"); // 普通兼职时薪（旧值表）
  private static final BigDecimal AUNTIE_HOURLY_RATE = new BigDecimal("18");    // 长期兼职/水果阿姨默认时薪；个人配置优先
  private static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");
  private static final java.util.Map<String, BigDecimal> POST_WAGE = java.util.Map.of(
      "店长", new BigDecimal("1300"), "领班", new BigDecimal("800"),
      "训练员", new BigDecimal("500"), "营业员", new BigDecimal("200"));
  // 保底中的固定提成档：店长 800、其余 600（保底 = 基本+社保+岗位+餐补+全勤+此档 → 5300/4600/4300/4000）
  private static final java.util.Map<String, BigDecimal> GUARANTEE_COMMISSION = java.util.Map.of(
      "店长", new BigDecimal("800"), "领班", new BigDecimal("600"),
      "训练员", new BigDecimal("600"), "营业员", new BigDecimal("600"));
  // 工龄工资：满半年100/一年200/一年半300/两年400 封顶（与员工档案页 SENIORITY_TIERS 一致）
  private static final int[] SENIORITY_MONTHS = {24, 18, 12, 6};
  private static final BigDecimal[] SENIORITY_PAY = {
      new BigDecimal("400"), new BigDecimal("300"), new BigDecimal("200"), new BigDecimal("100")};

  private final SalaryRepository salaryRepository;
  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public SalaryGenerationService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.salaryRepository = salaryRepository;
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
  }

  public SalaryGenerationService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl
  ) {
    this(salaryRepository, employeeRepository, accessControl, null);
  }

  public SalaryGenerateReport previewGeneration(AuthUser user, String storeId, String month) {
    requireEditRole(user);
    storeId = resolveStoreForWrite(user, storeId, "预览生成工资");
    String effectiveMonth = SalaryQueryService.normalizeMonth(month);
    requireStoreScope(user, storeId);
    if (employeeRepository == null) {
      throw new BusinessException("EMPLOYEE_REPOSITORY_UNAVAILABLE", "Employee repository is not available", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    List<EmployeeResponse> employees = payrollEmployees(user.tenantId(), storeId, effectiveMonth);
    int eligible = 0;
    int skipped = 0;
    int errors = 0;
    List<SalaryGenerateReport.SalarySkipDetail> skipDetails = new java.util.ArrayList<>();
    for (EmployeeResponse employee : employees) {
      if ("离职".equals(employee.status())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "员工已离职"));
        continue;
      }
      Preparation preparation = prepareSalary(user.tenantId(), storeId, effectiveMonth, employee);
      if (!preparation.missingItems().isEmpty()) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(
            employee.id(), employee.name(), "缺少" + String.join("、", preparation.missingItems())));
        continue;
      }
      if (employee.hireDate() != null && !employee.hireDate().isBlank()) {
        try {
          java.time.LocalDate hireDate = java.time.LocalDate.parse(employee.hireDate());
          YearMonth targetMonth = YearMonth.parse(effectiveMonth);
          if (hireDate.isAfter(targetMonth.atEndOfMonth())) {
            skipped++;
            skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "入职日期晚于" + effectiveMonth));
            continue;
          }
        } catch (Exception ignored) {}
      }
      java.util.Optional<SalaryRecordResponse> existing = salaryRepository.recordForEmployeeMonth(
          user.tenantId(), employee.id(), effectiveMonth);
      if ((existing.isPresent() && !isRegenerableAssignment(existing.get(), storeId))
          || (existing.isEmpty()
              && salaryRepository.recordExistsForEmployee(user.tenantId(), storeId, effectiveMonth, employee.name()))) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "工资记录已存在"));
        continue;
      }
      eligible++;
    }
    return new SalaryGenerateReport(eligible, skipped, errors, skipDetails);
  }

  @Transactional
  public List<SalaryRecordResponse> generate(AuthUser user, SalaryGenerateRequest request) {
    return generateInternal(user, request).records;
  }

  @Transactional
  public SalaryGenerateReport generateWithReport(AuthUser user, SalaryGenerateRequest request) {
    return generateInternal(user, request).report();
  }

  private GenerateResult generateInternal(AuthUser user, SalaryGenerateRequest request) {
    requireEditRole(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Salary generation payload is required", HttpStatus.BAD_REQUEST);
    }
    String storeId = resolveStoreForWrite(user, request.storeId(), "生成工资");
    String month = SalaryQueryService.normalizeMonth(request.month());
    requireStoreScope(user, storeId);
    if (!salaryRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    if (employeeRepository == null) {
      throw new BusinessException("EMPLOYEE_REPOSITORY_UNAVAILABLE", "Employee repository is not available", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    List<EmployeeResponse> employees = payrollEmployees(user.tenantId(), storeId, month);
    StoreCommissionContext commissionCtx = storeCommissionContext(user.tenantId(), storeId, month);
    int generated = 0;
    int skipped = 0;
    int errors = 0;
    List<SalaryGenerateReport.SalarySkipDetail> skipDetails = new java.util.ArrayList<>();
    for (EmployeeResponse employee : employees) {
      if ("离职".equals(employee.status())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "员工已离职"));
        continue;
      }
      Preparation preparation = prepareSalary(user.tenantId(), storeId, month, employee);
      if (!preparation.missingItems().isEmpty()) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(
            employee.id(), employee.name(), "缺少" + String.join("、", preparation.missingItems())));
        continue;
      }
      if (employee.hireDate() != null && !employee.hireDate().isBlank()) {
        try {
          java.time.LocalDate hireDate = java.time.LocalDate.parse(employee.hireDate());
          YearMonth targetMonth = YearMonth.parse(month);
          if (hireDate.isAfter(targetMonth.atEndOfMonth())) {
            skipped++;
            skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "入职日期晚于" + month));
            continue;
          }
        } catch (Exception ignored) {
        }
      }
      java.util.Optional<SalaryRecordResponse> existing = salaryRepository.recordForEmployeeMonth(
          user.tenantId(), employee.id(), month);
      if ((existing.isPresent() && !isRegenerableAssignment(existing.get(), storeId))
          || (existing.isEmpty()
              && salaryRepository.recordExistsForEmployee(user.tenantId(), storeId, month, employee.name()))) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "工资记录已存在"));
        continue;
      }
      SalaryRecordRequest row = generatedRecord(storeId, month, employee, preparation, commissionCtx);
      String salaryId = existing.filter(record -> isRegenerableAssignment(record, storeId))
          .map(SalaryRecordResponse::id)
          .orElseGet(() -> generatedId(month, employee.id()));
      salaryRepository.upsert(user.tenantId(), salaryId, row);
      saveCalculationSnapshot(user.tenantId(), salaryId, employee, preparation, row);
      generated++;
    }
    String detail = "已生成 " + generated + " 条，跳过 " + skipped + " 条，异常 " + errors + " 条";
    if (commissionCtx != null) {
      detail += "；提成" + commissionCtx.rateLabel() + "档（每小时产值" + commissionCtx.hourlyRevenue().stripTrailingZeros().toPlainString()
          + "，人均月产值" + commissionCtx.perCapitaOutput().stripTrailingZeros().toPlainString()
          + "，人均额度" + commissionCtx.quotaPerPerson().stripTrailingZeros().toPlainString()
          + "，总池" + commissionCtx.pool().stripTrailingZeros().toPlainString()
          + "）；店铺基金=总池−实发提成（约7.5%）";
    }
    salaryRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "salary_generate",
        storeId + "-" + month,
        storeId,
        month,
        detail
    );
    List<SalaryRecordResponse> records = salaryRepository.records(user.tenantId(), month, null, storeId);
    return new GenerateResult(records, new SalaryGenerateReport(generated, skipped, errors, skipDetails));
  }

  private record GenerateResult(List<SalaryRecordResponse> records, SalaryGenerateReport report) {}

  private List<EmployeeResponse> payrollEmployees(long tenantId, String storeId, String month) {
    java.util.LinkedHashMap<String, EmployeeResponse> employees = new java.util.LinkedHashMap<>();
    for (EmployeeResponse employee : employeeRepository.records(tenantId, null, storeId, null)) {
      employees.put(employee.id(), employee);
    }
    for (String employeeId : salaryRepository.assignedEmployeeIds(tenantId, storeId, month)) {
      employeeRepository.record(tenantId, employeeId)
          .ifPresent(employee -> employees.putIfAbsent(employee.id(), employee));
    }
    return List.copyOf(employees.values());
  }

  private static boolean isRegenerableAssignment(SalaryRecordResponse record, String storeId) {
    return record != null
        && record.id() != null
        && record.id().startsWith("SALADD-")
        && storeId.equals(record.storeId())
        && ("DRAFT".equals(record.status()) || "REJECTED".equals(record.status()));
  }

  static SalaryRecordRequest generatedRecord(String storeId, String month, EmployeeResponse employee, Preparation preparation) {
    return generatedRecord(storeId, month, employee, preparation, null);
  }

  /** 按 2026-03 工资表口径自动算薪；commission（提成）按每日销售归属人工填报，生成时为 0。 */
  static SalaryRecordRequest generatedRecord(String storeId, String month, EmployeeResponse employee,
                                             Preparation preparation, StoreCommissionContext commissionCtx) {
    SalaryRepository.SalaryProfileRow profile = preparation.profile();
    SalaryRepository.SalaryPolicyRow policy = preparation.policy();
    SalaryRepository.AttendanceRow attendance = preparation.attendance();
    StringBuilder note = new StringBuilder("考勤来源：" + attendance.source());
    BigDecimal birthdayBenefitAmount = birthdayBenefit(employee, month);

    if (isHourlyEmployee(employee)) {
      // 时薪优先级：员工档案个人时薪 > 岗位文字中的特殊时薪 > 默认（实习15、兼职13、长期兼职/水果阿姨18）。
      String pos = employee.position() == null ? "" : employee.position();
      BigDecimal positionRate = hourlyRateInPosition(pos);
      BigDecimal rate = employee.hourlyRate() != null && employee.hourlyRate().compareTo(BigDecimal.ZERO) > 0
          ? employee.hourlyRate()
          : positionRate != null ? positionRate
          : ("长期兼职".equals(employee.employmentType()) || pos.contains("水果") || (pos.contains("阿姨") && !pos.contains("实习"))
              ? AUNTIE_HOURLY_RATE
              : ("实习".equals(employee.employmentType()) || pos.contains("实习")
                  ? INTERN_HOURLY_RATE : PART_TIME_HOURLY_RATE));
      BigDecimal hours = attendance.totalHours();
      BigDecimal hourlyWage = hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
      BigDecimal seniority = "长期兼职".equals(employee.employmentType()) ? seniorityPay(employee, month) : ZERO;
      BigDecimal gross = hourlyWage.add(seniority).add(birthdayBenefitAmount).setScale(2, RoundingMode.HALF_UP);
      note.append("；按").append(rate.stripTrailingZeros().toPlainString())
          .append("元/时×").append(hours.stripTrailingZeros().toPlainString()).append("小时");
      if (seniority.compareTo(BigDecimal.ZERO) > 0) {
        note.append("；长期兼职工龄工资+").append(seniority.stripTrailingZeros().toPlainString());
      }
      if (birthdayBenefitAmount.compareTo(BigDecimal.ZERO) > 0) {
        note.append("；员工福利（生日）+")
            .append(birthdayBenefitAmount.stripTrailingZeros().toPlainString());
      }
      return record(storeId, month, employee, attendance, gross, hourlyWage,
          ZERO, ZERO, ZERO, ZERO, seniority, birthdayBenefitAmount, ZERO, ZERO, note.toString());
    }

    // 满勤天数与分项折算基准都跟月份走 = 当月天数−4 天休息（模板例：31天月 1900÷27×出勤天数）；
    // 提成的人均产值基准固定 ×26×8；2 月（春节月）不自动判满勤/保底，由人工填写（guarantee_feb_exclude 同口径）。
    YearMonth targetMonth = YearMonth.parse(month);
    boolean february = targetMonth.getMonthValue() == 2;
    BigDecimal monthlyFullDays = BigDecimal.valueOf(targetMonth.lengthOfMonth() - 4L);
    BigDecimal days = attendance.attendanceDays();
    BigDecimal ratio = days.compareTo(monthlyFullDays) >= 0 ? BigDecimal.ONE
        : days.divide(monthlyFullDays, 10, RoundingMode.HALF_UP);
    String position = canonicalPosition(employee.position());
    BigDecimal postWage = position == null ? null : POST_WAGE.get(position);

    // 月薪分项按天折算后四舍五入到整元（3 月表口径：1900×22/26=1607.69→1608）；加班费/兼职时薪保留小数不入整
    BigDecimal base = wholeYuan(profile.baseSalary().multiply(ratio));
    BigDecimal social = postWage == null ? ZERO : wholeYuan(STD_SOCIAL.multiply(ratio));
    BigDecimal post = postWage == null ? ZERO : wholeYuan(postWage.multiply(ratio));
    BigDecimal meal = postWage == null ? ZERO : wholeYuan(STD_MEAL.multiply(ratio));
    BigDecimal fullAttendance = !february && days.compareTo(monthlyFullDays) >= 0 ? FULL_ATTENDANCE_BONUS : ZERO;
    if (february) {
      note.append("；2月满勤与保底不自动判定，请人工填写");
    }
    BigDecimal seniority = seniorityPay(employee, month);
    BigDecimal overtimeRate = profile.overtimeHourRate() != null
        ? profile.overtimeHourRate() : amountOrZero(policy == null ? null : policy.overtimeHourRate());
    BigDecimal overtime = attendance.overtimeHours().multiply(overtimeRate).setScale(2, RoundingMode.HALF_UP);
    // 提成分配制（模板）：总池=人均提成×Σ正式出勤天数÷26；店长+5%、每领班+2.5%、85%按出勤天数均摊给正式员工，余额进店铺基金
    BigDecimal commission = ZERO;
    if (commissionCtx != null && postWage != null && commissionCtx.pool().compareTo(BigDecimal.ZERO) > 0) {
      commission = commissionCtx.commissionFor(position, days);
      note.append("；提成").append(commission.stripTrailingZeros().toPlainString())
          .append("（").append(commissionCtx.rateLabel()).append("档，池")
          .append(commissionCtx.pool().stripTrailingZeros().toPlainString()).append("）");
    }

    BigDecimal gross = base.add(social).add(post).add(meal).add(fullAttendance)
        .add(commission).add(overtime).add(seniority).add(birthdayBenefitAmount)
        .setScale(2, RoundingMode.HALF_UP);

    if (birthdayBenefitAmount.compareTo(BigDecimal.ZERO) > 0) {
      note.append("；员工福利（生日）+")
          .append(birthdayBenefitAmount.stripTrailingZeros().toPlainString());
    }

    // 保底：仅四个标准岗位、有全勤才享受；不足 26 天按天折算。保底覆盖 基本+社保+岗位+餐补+全勤+提成 六项。
    if (postWage != null && policy != null && policy.guaranteeEnabled()
        && fullAttendance.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal guaranteeFull = profile.baseSalary().add(STD_SOCIAL).add(postWage).add(STD_MEAL)
          .add(FULL_ATTENDANCE_BONUS).add(GUARANTEE_COMMISSION.get(position));
      BigDecimal floor = wholeYuan(guaranteeFull.multiply(ratio));
      BigDecimal sixItems = base.add(social).add(post).add(meal).add(fullAttendance).add(commission);
      if (sixItems.compareTo(floor) < 0) {
        BigDecimal topUp = floor.subtract(sixItems).setScale(2, RoundingMode.HALF_UP);
        gross = gross.add(topUp).setScale(2, RoundingMode.HALF_UP);
        note.append("；保底补足+").append(topUp.stripTrailingZeros().toPlainString())
            .append("（").append(position).append("保底").append(guaranteeFull.stripTrailingZeros().toPlainString());
        if (ratio.compareTo(BigDecimal.ONE) < 0) {
          note.append("×").append(days.stripTrailingZeros().toPlainString())
              .append("/").append(monthlyFullDays.stripTrailingZeros().toPlainString());
        }
        note.append("）");
      }
    }
    if (postWage == null) {
      note.append("；岗位「").append(employee.position()).append("」无标准工资包，仅按底薪折算+加班+工龄");
    }
    return record(storeId, month, employee, attendance, gross, base,
        social, post, meal, fullAttendance, seniority, birthdayBenefitAmount,
        overtime, commission, note.toString());
  }

  private static SalaryRecordRequest record(String storeId, String month, EmployeeResponse employee,
      SalaryRepository.AttendanceRow attendance, BigDecimal gross, BigDecimal base, BigDecimal social,
      BigDecimal post, BigDecimal meal, BigDecimal fullAttendance, BigDecimal seniority,
      BigDecimal birthdayBenefit, BigDecimal overtime, BigDecimal commission, String note) {
    return new SalaryRecordRequest(
        storeId,
        month,
        employee.id(),
        employee.name(),
        employee.position(),
        attendance.attendanceDays().stripTrailingZeros().toPlainString(),
        gross,
        attendance.normalHours(),
        attendance.overtimeHours(),
        attendance.totalHours(),
        attendance.vacationBalance(),
        note,
        base,
        social,
        post,
        meal,
        fullAttendance,
        commission,
        overtime,
        seniority,
        birthdayBenefit,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO
    );
  }

  /** 标准四岗位识别（精确匹配，其他岗位如实习/水果阿姨不套工资包与保底）。 */
  static String canonicalPosition(String position) {
    if (position == null) return null;
    String p = position.trim();
    return POST_WAGE.containsKey(p) ? p : null;
  }

  private static boolean isHourlyEmployee(EmployeeResponse employee) {
    return isHourlyEmployee(employee.employmentType(), employee.position());
  }

  static boolean isHourlyEmployee(String employmentType, String employeePosition) {
    String type = employmentType == null
        ? ""
        : employmentType.trim().toUpperCase(java.util.Locale.ROOT);
    String position = employeePosition == null ? "" : employeePosition;
    return "兼职".equals(type) || "长期兼职".equals(type) || "实习".equals(type)
        || "PART_TIME".equals(type) || "LONG_TERM_PART_TIME".equals(type) || "INTERN".equals(type)
        || position.contains("兼职") || position.contains("实习")
        || position.contains("水果") || position.contains("阿姨");
  }

  private static BigDecimal hourlyRateInPosition(String position) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:兼职|实习)[^0-9]{0,4}([0-9]+(?:\\.[0-9]+)?)").matcher(position);
    return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
  }

  /** 工龄工资：按入职日期到目标月末的整月数分档；仅全职/长期兼职（兼职走时薪不进此分支）。 */
  static BigDecimal seniorityPay(EmployeeResponse employee, String month) {
    if (employee.hireDate() == null || employee.hireDate().isBlank()) return ZERO;
    try {
      java.time.LocalDate hire = java.time.LocalDate.parse(employee.hireDate().trim());
      java.time.LocalDate monthEnd = YearMonth.parse(month).atEndOfMonth();
      long months = java.time.temporal.ChronoUnit.MONTHS.between(hire, monthEnd);
      for (int i = 0; i < SENIORITY_MONTHS.length; i++) {
        if (months >= SENIORITY_MONTHS[i]) return SENIORITY_PAY[i];
      }
      return ZERO;
    } catch (Exception ex) {
      return ZERO;
    }
  }

  /** 员工福利（生日）：在职全职/长期兼职员工在生日所在工资月份自动计入 200 元。 */
  static BigDecimal birthdayBenefit(EmployeeResponse employee, String month) {
    if (!eligibleForSalaryBenefits(employee)
        || employee.birthday() == null || employee.birthday().isBlank()
        || month == null || month.isBlank()) {
      return ZERO;
    }
    try {
      java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[0-9]+").matcher(employee.birthday());
      Integer previous = null;
      Integer current = null;
      while (matcher.find()) {
        previous = current;
        current = Integer.parseInt(matcher.group());
      }
      if (previous == null || current == null
          || previous < 1 || previous > 12
          || current < 1 || current > java.time.Month.of(previous).maxLength()) {
        return ZERO;
      }
      return YearMonth.parse(month).getMonthValue() == previous ? BIRTHDAY_BENEFIT : ZERO;
    } catch (RuntimeException ex) {
      return ZERO;
    }
  }

  /** 与员工档案页福利口径一致：缺省用工类型按全职处理，普通兼职/实习不享受。 */
  static boolean eligibleForSalaryBenefits(EmployeeResponse employee) {
    if (employee == null) return false;
    String status = employee.status() == null ? "" : employee.status().trim().toUpperCase(java.util.Locale.ROOT);
    if (!"在职".equals(status) && !"ACTIVE".equals(status)) return false;
    String employmentType = employee.employmentType() == null || employee.employmentType().isBlank()
        ? "全职"
        : employee.employmentType().trim().toUpperCase(java.util.Locale.ROOT);
    return "全职".equals(employmentType)
        || "正式员工".equals(employmentType)
        || "FULL_TIME".equals(employmentType)
        || "长期兼职".equals(employmentType)
        || "LONG_TERM_PART_TIME".equals(employmentType);
  }

  /**
   * 门店提成（工资模板新版）：
   * 每小时人均产值 = 当月实收营业额 ÷（正常+加班）总工时（实习/兼职按半工时），取整；
   * 人均月产值 = 每小时产值×26×8 → 档位 <2.2万:2% / 2.2-3.4万:2.5% / ≥3.4万:3%；
   * 个人提成额度(取整) × Σ正式出勤天数÷26 = 总提成池；
   * 分配：店长+池×5%、每领班+池×2.5%、池×85%按出勤天数摊给正式四岗位员工，余额=店铺基金。
   */
  record StoreCommissionContext(BigDecimal revenue, BigDecimal effectiveHours, BigDecimal hourlyRevenue,
                                BigDecimal perCapitaOutput, BigDecimal rate, BigDecimal quotaPerPerson,
                                BigDecimal pool, BigDecimal formalDays) {
    String rateLabel() {
      return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    BigDecimal commissionFor(String position, BigDecimal attendanceDays) {
      if (formalDays.compareTo(BigDecimal.ZERO) <= 0) return ZERO;
      BigDecimal share = pool.multiply(new BigDecimal("0.85"))
          .multiply(attendanceDays).divide(formalDays, 10, RoundingMode.HALF_UP)
          .setScale(0, RoundingMode.HALF_UP);
      if ("店长".equals(position)) {
        share = share.add(pool.multiply(new BigDecimal("0.05")).setScale(0, RoundingMode.HALF_UP));
      } else if ("领班".equals(position)) {
        share = share.add(pool.multiply(new BigDecimal("0.025")).setScale(0, RoundingMode.HALF_UP));
      }
      return share.setScale(2, RoundingMode.HALF_UP);
    }
  }

  private StoreCommissionContext storeCommissionContext(long tenantId, String storeId, String month) {
    BigDecimal revenue = salaryRepository.storeMonthlyRevenue(tenantId, storeId, month).orElse(null);
    SalaryRepository.StoreAttendanceStats stats = salaryRepository.storeAttendanceStats(tenantId, storeId, month);
    return calculateStoreCommissionContext(revenue, stats);
  }

  /** 工资生成和工资页经营指标共用的门店提成上下文算法。 */
  static StoreCommissionContext calculateStoreCommissionContext(
      BigDecimal revenue,
      SalaryRepository.StoreAttendanceStats stats
  ) {
    if (revenue == null || revenue.compareTo(BigDecimal.ZERO) <= 0
        || stats == null || stats.effectiveHours() == null
        || stats.effectiveHours().compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal hourly = revenue.divide(stats.effectiveHours(), 0, RoundingMode.DOWN); // 每小时产值取整不四舍五入
    BigDecimal perCapita = hourly.multiply(DEFAULT_FULL_MONTH_DAYS).multiply(HOURS_PER_DAY);
    BigDecimal rate;
    if (perCapita.compareTo(new BigDecimal("22000")) < 0) rate = new BigDecimal("0.02");
    else if (perCapita.compareTo(new BigDecimal("34000")) < 0) rate = new BigDecimal("0.025");
    else rate = new BigDecimal("0.03");
    BigDecimal quota = perCapita.multiply(rate).setScale(0, RoundingMode.DOWN); // 个人额度取整（3月表：660.4→660）
    BigDecimal formalDays = stats.formalDays() == null ? ZERO : stats.formalDays();
    BigDecimal pool = quota.multiply(formalDays)
        .divide(DEFAULT_FULL_MONTH_DAYS, 2, RoundingMode.HALF_UP);
    return new StoreCommissionContext(revenue, stats.effectiveHours(), hourly, perCapita, rate, quota, pool, formalDays);
  }

  private Preparation prepareSalary(long tenantId, String storeId, String month, EmployeeResponse employee) {
    java.util.ArrayList<String> missing = new java.util.ArrayList<>();
    boolean partTime = isHourlyEmployee(employee);
    if (employee.position() == null || employee.position().isBlank()) missing.add("岗位配置");
    SalaryRepository.SalaryProfileRow profile = salaryRepository.salaryProfile(tenantId, employee.id(), month).orElse(null);
    if (!partTime && (profile == null || profile.baseSalary() == null || profile.baseSalary().compareTo(BigDecimal.ZERO) <= 0)) {
      missing.add("员工工资档案");
    }
    SalaryRepository.SalaryPolicyRow policy = profile == null ? null
        : salaryRepository.activePolicy(tenantId, profile.policyId(), month).orElse(null);
    if (!partTime && policy == null) missing.add("有效工资政策");
    SalaryRepository.AttendanceRow attendance = salaryRepository.attendance(tenantId, storeId, employee.id(), month).orElse(null);
    if (attendance == null) missing.add("当月已确认考勤");
    return new Preparation(profile, policy, attendance, missing);
  }

  private void saveCalculationSnapshot(long tenantId, String salaryId, EmployeeResponse employee,
                                       Preparation preparation, SalaryRecordRequest row) {
    SalaryRepository.SalaryPolicyRow policy = preparation.policy();
    if (policy == null) return; // 兼职时薪行无政策，不落快照
    try {
      SalaryRepository.SalaryProfileRow profile = preparation.profile();
      SalaryRepository.AttendanceRow attendance = preparation.attendance();
      String policySnapshot = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
          "policyId", policy.id(), "policyName", policy.name(), "policyVersion", policy.version(),
          "employeeId", employee.id(), "baseSalary", profile.baseSalary(),
          "overtimeHourRate", profile.overtimeHourRate() != null ? profile.overtimeHourRate() : amountOrZero(policy.overtimeHourRate())
      ));
      java.util.Map<String, Object> calc = new java.util.LinkedHashMap<>();
      calc.put("attendanceDays", attendance.attendanceDays());
      calc.put("normalHours", attendance.normalHours());
      calc.put("overtimeHours", attendance.overtimeHours());
      calc.put("totalHours", attendance.totalHours());
      calc.put("base", row.base());
      calc.put("social", row.social());
      calc.put("post", row.post());
      calc.put("meal", row.meal());
      calc.put("fullAttendance", row.fullAttendance());
      calc.put("seniority", row.seniority());
      calc.put("birthdayBenefit", row.birthdayBenefit());
      calc.put("overtime", row.overtime());
      calc.put("commission", row.commission());
      calc.put("gross", row.gross());
      calc.put("netPay", row.gross());
      calc.put("note", row.vacationNote());
      String calculationSnapshot = OBJECT_MAPPER.writeValueAsString(calc);
      salaryRepository.saveCalculationSnapshot(
          tenantId, salaryId, policy, policySnapshot, calculationSnapshot,
          row.base(), row.overtime(), row.seniority(), row.birthdayBenefit(), row.gross());
    } catch (JsonProcessingException ex) {
      throw new BusinessException("SALARY_SNAPSHOT_FAILED", "工资计算快照生成失败", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private static BigDecimal amountOrZero(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  /** 月薪分项取整规则：四舍五入到整元（历史工资表口径）。 */
  private static BigDecimal wholeYuan(BigDecimal value) {
    return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
  }

  record Preparation(SalaryRepository.SalaryProfileRow profile, SalaryRepository.SalaryPolicyRow policy,
                     SalaryRepository.AttendanceRow attendance, List<String> missingItems) {}

  private static String generatedId(String month, String employeeId) {
    return "SALGEN-" + month.replace("-", "") + "-" + employeeId;
  }

  private void requireEditRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryEdit(user);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE")) {
      throw new BusinessException("FORBIDDEN", "No permission to edit salary records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreScope(AuthUser user, String storeId) {
    if (businessScopeResolver != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, storeId, null, "处理工资数据");
      return;
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.SALARY, storeId, "处理工资数据");
      return;
    }
    if ("STORE_MANAGER".equals(user.role())) {
      String scoped = user.storeId();
      if (scoped == null || scoped.isBlank()) {
        throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
      }
      if (!scoped.trim().equals(storeId)) {
        throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
      }
    }
  }

  private String resolveStoreForWrite(AuthUser user, String storeId, String action) {
    if (businessScopeResolver != null) {
      String resolved = businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, storeId, null, action).storeId();
      return SalaryQueryService.requireText(resolved, "STORE_REQUIRED", "请选择门店");
    }
    String requested = SalaryQueryService.blankToNull(storeId);
    if (requested == null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      requested = SalaryQueryService.blankToNull(user.storeId());
    }
    return SalaryQueryService.requireText(requested, "STORE_REQUIRED", "请选择门店");
  }
}
