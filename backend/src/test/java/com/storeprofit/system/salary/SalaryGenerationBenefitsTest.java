package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeModes;
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

  @Test
  void previewAndGenerationUseTheSameDeduplicatedEmployeeSelection() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(
        repository,
        employeeRepository,
        accessControl
    );
    AuthUser finance = new AuthUser(
        1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);
    EmployeeResponse selected = employee(
        "selected", "FULL_TIME", "在职", "1992-05-09", "营业员");
    EmployeeResponse unselected = employee(
        "unselected", "FULL_TIME", "在职", "1992-06-09", "营业员");

    when(repository.storeExists(1L, "store-1")).thenReturn(true);
    when(employeeRepository.records(1L, null, "store-1", null))
        .thenReturn(List.of(selected, unselected));
    when(repository.assignedEmployeeIds(1L, "store-1", "2026-05")).thenReturn(List.of());
    when(repository.salaryProfile(1L, "selected", "2026-05")).thenReturn(Optional.of(
        new SalaryRepository.SalaryProfileRow(
            "policy-1", new BigDecimal("3000"), BigDecimal.ZERO, null, null)));
    when(repository.activePolicy(1L, "policy-1", "2026-05")).thenReturn(Optional.of(
        new SalaryRepository.SalaryPolicyRow(
            "policy-1", "标准工资", 1, BigDecimal.ZERO, "PROFILE_ONLY", false, null)));
    when(repository.attendance(1L, "store-1", "selected", "2026-05"))
        .thenReturn(Optional.of(attendance(new BigDecimal("27"), new BigDecimal("216"))));
    when(repository.recordForEmployeeMonth(1L, "selected", "2026-05"))
        .thenReturn(Optional.empty());
    when(repository.records(1L, "2026-05", null, "store-1")).thenReturn(List.of());

    SalaryGenerateRequest request = new SalaryGenerateRequest(
        "store-1",
        "2026-05",
        List.of("selected", "selected")
    );
    SalaryGenerateReport preview = service.previewGeneration(finance, request);
    SalaryGenerateReport generated = service.generateWithReport(finance, request);

    assertThat(preview.generated()).isEqualTo(1);
    assertThat(preview.candidates())
        .extracting(SalaryGenerateReport.SalaryCandidate::employeeId)
        .containsExactly("selected");
    assertThat(generated.generated()).isEqualTo(1);
    assertThat(generated.candidates())
        .extracting(SalaryGenerateReport.SalaryCandidate::employeeId)
        .containsExactly("selected");
    verify(repository).upsert(
        org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("SALGEN-202605-selected"),
        org.mockito.ArgumentMatchers.any(SalaryRecordRequest.class)
    );
    verify(repository, never()).salaryProfile(1L, "unselected", "2026-05");
  }

  @Test
  void explicitEmptyEmployeeSelectionIsRejectedInsteadOfGeneratingTheWholeStore() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(
        repository,
        employeeRepository,
        accessControl
    );
    when(repository.storeExists(1L, "store-1")).thenReturn(true);
    when(employeeRepository.records(1L, null, "store-1", null)).thenReturn(List.of());
    when(repository.assignedEmployeeIds(1L, "store-1", "2026-05")).thenReturn(List.of());

    assertThatThrownBy(() -> service.previewGeneration(
        new AuthUser(1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true),
        new SalaryGenerateRequest("store-1", "2026-05", List.of())
    )).isInstanceOfSatisfying(BusinessException.class, exception ->
        assertThat(exception.getCode()).isEqualTo("SALARY_EMPLOYEE_SELECTION_EMPTY"));
  }

  @Test
  void selectedEmployeeMustBelongToTheResolvedPayrollScope() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(
        repository,
        employeeRepository,
        accessControl
    );
    when(repository.storeExists(1L, "store-1")).thenReturn(true);
    when(employeeRepository.records(1L, null, "store-1", null))
        .thenReturn(List.of(employee(
            "available", "FULL_TIME", "在职", "1992-05-09", "营业员")));
    when(repository.assignedEmployeeIds(1L, "store-1", "2026-05")).thenReturn(List.of());

    assertThatThrownBy(() -> service.previewGeneration(
        new AuthUser(1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true),
        new SalaryGenerateRequest("store-1", "2026-05", List.of("other-store"))
    )).isInstanceOfSatisfying(BusinessException.class, exception ->
        assertThat(exception.getCode()).isEqualTo("SALARY_EMPLOYEE_SELECTION_INVALID"));
  }

  @Test
  void explicitSelectionCanIncludeEveryEmployeeBeyondTheLegacyFiveHundredLimit() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(
        repository,
        employeeRepository,
        accessControl
    );
    List<EmployeeResponse> employees = java.util.stream.IntStream.rangeClosed(1, 501)
        .mapToObj(index -> employee(
            "employee-" + index, "兼职", "在职", "1992-05-09", "营业员"))
        .toList();
    List<String> selectedIds = employees.stream().map(EmployeeResponse::id).toList();
    when(repository.storeExists(1L, "store-1")).thenReturn(true);
    when(employeeRepository.records(1L, null, "store-1", null)).thenReturn(employees);
    when(repository.assignedEmployeeIds(1L, "store-1", "2026-05")).thenReturn(List.of());

    SalaryGenerateReport report = service.previewGeneration(
        new AuthUser(1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true),
        new SalaryGenerateRequest("store-1", "2026-05", selectedIds)
    );

    assertThat(report.generated()).isZero();
    assertThat(report.skipped()).isEqualTo(501);
  }

  @Test
  void allStorePreviewAndGenerationUseAuthorizedActiveStoresAndSalaryAssignmentOwnership() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(
        repository,
        employeeRepository,
        accessControl
    );
    AuthUser finance = new AuthUser(
        1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);
    EmployeeResponse home = employeeAtStore("home", "store-1", "一店");
    EmployeeResponse transferred = employeeAtStore("transferred", "store-1", "一店");

    when(accessControl.dataScope(
        finance, com.storeprofit.system.platform.authorization.DataScopeDomains.SALARY))
        .thenReturn(DataScope.all());
    when(repository.activeGenerationStores(1L, DataScope.all())).thenReturn(List.of(
        new SalaryRepository.SalaryGenerationStoreRow("store-1", "一店"),
        new SalaryRepository.SalaryGenerationStoreRow("store-2", "二店")
    ));
    when(repository.assignedEmployeeStores(1L, "2026-05")).thenReturn(List.of(
        new SalaryRepository.SalaryEmployeeStoreAssignment("transferred", "store-2")
    ));
    when(employeeRepository.records(1L, null, null, null))
        .thenReturn(List.of(home, transferred));
    when(repository.attendance(1L, "store-1", "home", "2026-05"))
        .thenReturn(Optional.of(attendance(new BigDecimal("26"), new BigDecimal("208"))));
    when(repository.attendance(1L, "store-2", "transferred", "2026-05"))
        .thenReturn(Optional.of(attendance(new BigDecimal("26"), new BigDecimal("208"))));
    when(repository.recordForEmployeeMonth(1L, "home", "2026-05"))
        .thenReturn(Optional.empty());
    when(repository.recordForEmployeeMonth(1L, "transferred", "2026-05"))
        .thenReturn(Optional.empty());
    SalaryRecordResponse authorizedRecord = mock(SalaryRecordResponse.class);
    when(authorizedRecord.id()).thenReturn("salary-transferred");
    when(repository.records(1L, "2026-05", null, "store-2"))
        .thenReturn(List.of(authorizedRecord));

    SalaryGenerateReport preview = service.previewGeneration(
        finance, new SalaryGenerateRequest(null, "2026-05"));
    List<SalaryRecordResponse> generatedRecords = service.generate(
        finance,
        new SalaryGenerateRequest(null, "2026-05", List.of("transferred"))
    );

    assertThat(preview.candidates())
        .extracting(
            SalaryGenerateReport.SalaryCandidate::employeeId,
            SalaryGenerateReport.SalaryCandidate::storeId,
            SalaryGenerateReport.SalaryCandidate::storeName
        )
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("home", "store-1", "一店"),
            org.assertj.core.groups.Tuple.tuple("transferred", "store-2", "二店")
        );
    assertThat(generatedRecords).containsExactly(authorizedRecord);
    verify(repository).upsert(
        org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("SALGEN-202605-transferred"),
        org.mockito.ArgumentMatchers.argThat(row ->
            "store-2".equals(row.storeId()) && "transferred".equals(row.employeeId()))
    );
    verify(repository, never()).upsert(
        org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("SALGEN-202605-home"),
        org.mockito.ArgumentMatchers.any(SalaryRecordRequest.class)
    );
    verify(repository, never()).records(1L, "2026-05", null, "store-1");
    verify(repository, never()).records(1L, "2026-05", null, (String) null);
    verify(repository, never())
        .attendance(1L, "store-1", "transferred", "2026-05");
    verify(repository).logAction(
        org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("Finance"),
        org.mockito.ArgumentMatchers.eq("salary_generate"),
        org.mockito.ArgumentMatchers.eq("store-2-2026-05"),
        org.mockito.ArgumentMatchers.eq("store-2"),
        org.mockito.ArgumentMatchers.eq("2026-05"),
        org.mockito.ArgumentMatchers.anyString()
    );
    verify(accessControl, org.mockito.Mockito.times(2)).requireStoreAccess(
        finance,
        com.storeprofit.system.platform.authorization.DataScopeDomains.SALARY,
        "store-1",
        "处理工资数据"
    );
    verify(accessControl, org.mockito.Mockito.times(2)).requireStoreAccess(
        finance,
        com.storeprofit.system.platform.authorization.DataScopeDomains.SALARY,
        "store-2",
        "处理工资数据"
    );
  }

  @Test
  void allStoreEmployeeSelectionCannotReintroduceAssignmentOutsideAuthorizedScope() {
    SalaryRepository repository = mock(SalaryRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    SalaryGenerationService service = new SalaryGenerationService(
        repository,
        employeeRepository,
        accessControl
    );
    AuthUser scopedFinance = new AuthUser(
        2L, 1L, "default", "finance-1", "", "Scoped Finance", "FINANCE_LIMITED", null, true);
    DataScope storeOneScope =
        new DataScope(DataScopeModes.STORE_LIST, List.of("store-1"));
    EmployeeResponse transferred = employeeAtStore("transferred", "store-1", "一店");

    when(accessControl.dataScope(
        scopedFinance, com.storeprofit.system.platform.authorization.DataScopeDomains.SALARY))
        .thenReturn(storeOneScope);
    when(repository.activeGenerationStores(1L, storeOneScope)).thenReturn(List.of(
        new SalaryRepository.SalaryGenerationStoreRow("store-1", "一店")
    ));
    when(repository.assignedEmployeeStores(1L, "2026-05")).thenReturn(List.of(
        new SalaryRepository.SalaryEmployeeStoreAssignment("transferred", "store-2")
    ));
    when(employeeRepository.records(1L, null, null, null))
        .thenReturn(List.of(transferred));

    assertThatThrownBy(() -> service.previewGeneration(
        scopedFinance,
        new SalaryGenerateRequest("all", "2026-05", List.of("transferred"))
    )).isInstanceOfSatisfying(BusinessException.class, exception ->
        assertThat(exception.getCode()).isEqualTo("SALARY_EMPLOYEE_SELECTION_INVALID"));
    verify(repository, never()).attendance(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()
    );
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

  private EmployeeResponse employeeAtStore(String id, String storeId, String storeName) {
    EmployeeResponse source = employee(id, "兼职", "在职", "1992-05-09", "营业员");
    return new EmployeeResponse(
        source.id(),
        storeId,
        storeId,
        storeName,
        source.brandId(),
        source.brandName(),
        source.name(),
        source.phone(),
        source.role(),
        source.position(),
        source.employmentType(),
        source.baseSalary(),
        source.status(),
        source.hireDate(),
        source.remark(),
        source.birthday(),
        source.idCardNo(),
        source.healthCertIssueDate(),
        source.healthCertExpireDate(),
        source.contractSignText(),
        source.regularDate(),
        source.trainerDate(),
        source.shiftLeaderDate(),
        source.managerDate(),
        source.authUserId(),
        source.accountUsername(),
        source.accountEnabled(),
        source.hourlyRate()
    );
  }
}
