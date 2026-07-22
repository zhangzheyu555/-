package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class SalaryGenerationBenefitsTest {

  @Test
  void birthdayBenefitUsesTargetPayrollMonthAndEmployeeBenefitEligibility() {
    EmployeeResponse fullTime = employee("full", "FULL_TIME", "在职", "1992-05-09", "营业员");
    EmployeeResponse longTermPartTime = employee("long", "长期兼职", "在职", "5.9", "营业员");
    EmployeeResponse ordinaryPartTime = employee("part", "兼职", "在职", "5月9日", "营业员");
    EmployeeResponse inactive = employee("inactive", "全职", "离职", "5.9", "营业员");
    EmployeeResponse invalidBirthday = employee("invalid", "全职", "在职", "2.30", "营业员");

    assertThat(SalaryGenerationService.birthdayBenefit(fullTime, "2026-05"))
        .isEqualByComparingTo("200.00");
    assertThat(SalaryGenerationService.birthdayBenefit(longTermPartTime, "2026-05"))
        .isEqualByComparingTo("200.00");
    assertThat(SalaryGenerationService.birthdayBenefit(fullTime, "2026-06"))
        .isEqualByComparingTo("0.00");
    assertThat(SalaryGenerationService.birthdayBenefit(ordinaryPartTime, "2026-05"))
        .isEqualByComparingTo("0.00");
    assertThat(SalaryGenerationService.birthdayBenefit(inactive, "2026-05"))
        .isEqualByComparingTo("0.00");
    assertThat(SalaryGenerationService.birthdayBenefit(invalidBirthday, "2026-02"))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void generatedMonthlySalaryAddsBirthdayBenefitWithoutChangingSeniorityTier() {
    EmployeeResponse employee = employee("full", "FULL_TIME", "在职", "1992-05-09", "营业员");
    SalaryGenerationService.Preparation preparation = new SalaryGenerationService.Preparation(
        new SalaryRepository.SalaryProfileRow(
            "policy-1", new BigDecimal("3000"), BigDecimal.ZERO, null, null),
        new SalaryRepository.SalaryPolicyRow(
            "policy-1", "标准工资", 1, BigDecimal.ZERO, "PROFILE_ONLY", false, null),
        attendance(new BigDecimal("27"), new BigDecimal("216")),
        List.of()
    );

    SalaryRecordRequest salary = SalaryGenerationService.generatedRecord(
        "store-1", "2026-05", employee, preparation);

    assertThat(salary.seniority()).isEqualByComparingTo("200.00");
    assertThat(salary.birthdayBenefit()).isEqualByComparingTo("200.00");
    assertThat(salary.gross()).isEqualByComparingTo("4900.00");
    assertThat(salary.vacationNote()).contains("员工福利（生日）+200");
  }

  @Test
  void longTermPartTimeGetsSeniorityAndBirthdayBenefitButOrdinaryPartTimeDoesNot() {
    SalaryGenerationService.Preparation hourlyPreparation = new SalaryGenerationService.Preparation(
        null,
        null,
        attendance(BigDecimal.ZERO, new BigDecimal("10")),
        List.of()
    );

    SalaryRecordRequest longTerm = SalaryGenerationService.generatedRecord(
        "store-1", "2026-05",
        employee("long", "长期兼职", "在职", "5.9", "营业员"),
        hourlyPreparation);
    SalaryRecordRequest ordinary = SalaryGenerationService.generatedRecord(
        "store-1", "2026-05",
        employee("part", "兼职", "在职", "5.9", "营业员"),
        hourlyPreparation);

    assertThat(longTerm.seniority()).isEqualByComparingTo("200.00");
    assertThat(longTerm.birthdayBenefit()).isEqualByComparingTo("200.00");
    assertThat(longTerm.gross()).isEqualByComparingTo("580.00");
    assertThat(ordinary.seniority()).isEqualByComparingTo("0.00");
    assertThat(ordinary.birthdayBenefit()).isEqualByComparingTo("0.00");
    assertThat(ordinary.gross()).isEqualByComparingTo("130.00");
  }

  @Test
  void generationCompletesAnAssignedEmployeeUsingTheOriginalSalaryIdAndPositionPackage() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(repository, employeeRepository, accessControl);
    EmployeeResponse transferred = employee("full", "FULL_TIME", "在职", "1992-05-09", "营业员");
    SalaryRecordResponse existing = mock(SalaryRecordResponse.class);
    when(existing.id()).thenReturn("SALADD-202605-transfer");
    when(existing.storeId()).thenReturn("store-2");
    when(existing.status()).thenReturn("DRAFT");
    when(repository.storeExists(1L, "store-2")).thenReturn(true);
    when(repository.assignedEmployeeIds(1L, "store-2", "2026-05")).thenReturn(List.of("full"));
    when(employeeRepository.records(1L, null, "store-2", null)).thenReturn(List.of());
    when(employeeRepository.record(1L, "full")).thenReturn(Optional.of(transferred));
    when(repository.recordForEmployeeMonth(1L, "full", "2026-05")).thenReturn(Optional.of(existing));
    when(repository.salaryProfile(1L, "full", "2026-05")).thenReturn(Optional.of(
        new SalaryRepository.SalaryProfileRow("policy-1", new BigDecimal("3000"), BigDecimal.ZERO, null, null)));
    when(repository.activePolicy(1L, "policy-1", "2026-05")).thenReturn(Optional.of(
        new SalaryRepository.SalaryPolicyRow("policy-1", "标准工资", 1, BigDecimal.ZERO, "PROFILE_ONLY", false, null)));
    when(repository.attendance(1L, "store-2", "full", "2026-05"))
        .thenReturn(Optional.of(attendance(new BigDecimal("27"), new BigDecimal("216"))));
    when(repository.records(1L, "2026-05", null, "store-2")).thenReturn(List.of(existing));

    service.generateWithReport(
        new AuthUser(1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true),
        new SalaryGenerateRequest("store-2", "2026-05"));

    ArgumentCaptor<SalaryRecordRequest> request = ArgumentCaptor.forClass(SalaryRecordRequest.class);
    verify(repository).upsert(org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("SALADD-202605-transfer"), request.capture());
    assertThat(request.getValue().position()).isEqualTo("营业员");
    assertThat(request.getValue().social()).isEqualByComparingTo("800.00");
    assertThat(request.getValue().post()).isEqualByComparingTo("200.00");
    assertThat(request.getValue().meal()).isEqualByComparingTo("300.00");
    assertThat(request.getValue().fullAttendance()).isEqualByComparingTo("200.00");
    assertThat(request.getValue().gross()).isEqualByComparingTo("4900.00");
  }

  private SalaryRepository.AttendanceRow attendance(BigDecimal days, BigDecimal hours) {
    return new SalaryRepository.AttendanceRow(
        days, hours, BigDecimal.ZERO, hours, new BigDecimal("4"), "MANUAL", "CONFIRMED");
  }

  private EmployeeResponse employee(
      String id,
      String employmentType,
      String status,
      String birthday,
      String position
  ) {
    return new EmployeeResponse(
        id,
        "store-1",
        "S001",
        "测试门店",
        1L,
        "测试品牌",
        "员工" + id,
        "",
        "EMPLOYEE",
        position,
        employmentType,
        BigDecimal.ZERO,
        status,
        "2025-01-01",
        "",
        birthday,
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
        null
    );
  }
}
