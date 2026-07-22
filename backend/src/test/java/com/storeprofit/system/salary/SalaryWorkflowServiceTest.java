package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SalaryWorkflowServiceTest {
  private final SalaryRepository salaryRepository = mock(SalaryRepository.class);
  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final BusinessTodoService businessTodoService = mock(BusinessTodoService.class);
  private final SalaryQueryService salaryQueryService = mock(SalaryQueryService.class);
  private final SalaryWorkflowService service = new SalaryWorkflowService(
      salaryRepository, employeeRepository, accessControl, businessTodoService, salaryQueryService);
  private final AuthUser boss = new AuthUser(1L, 1L, "默认企业", "boss", "", "老板", "BOSS", null, true);
  private final SalaryRecordRequest request = new SalaryRecordRequest(
      "store-1", "2026-05", "employee-1", "历史员工", "店员", "正常",
      new BigDecimal("3000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, "", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
  );

  @BeforeEach
  void setUp() {
    EmployeeResponse inactive = employee(
        "employee-1", "store-1", "测试门店", "历史员工", "店员", BigDecimal.ZERO, "离职");
    when(employeeRepository.record(1L, "employee-1")).thenReturn(Optional.of(inactive));
    when(salaryRepository.storeExists(1L, "store-1")).thenReturn(true);
    when(salaryRepository.record(1L, "LEGACY-1")).thenReturn(Optional.empty());
    when(salaryRepository.recordIdForEmployeeId(1L, "store-1", "2026-05", "employee-1"))
        .thenReturn(Optional.empty());
  }

  @Test
  void historicalImportAllowsInactiveEmployeeForLegacyIdOnly() {
    SalaryRecordResponse saved = mock(SalaryRecordResponse.class);
    when(salaryRepository.record(1L, "LEGACY-1"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(saved));

    service.importHistorical(boss, "LEGACY-1", request);

    verify(salaryRepository).upsert(eq(1L), eq("LEGACY-1"), any(SalaryRecordRequest.class));
  }

  @Test
  void historicalImportRejectsNonLegacyId() {
    assertThatThrownBy(() -> service.importHistorical(boss, "salary-1", request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("LEGACY-");

    verify(salaryRepository, never()).upsert(1L, "salary-1", request);
  }

  @Test
  void historicalImportDoesNotOverwriteExistingNonLegacySalary() {
    when(salaryRepository.recordIdForEmployeeId(1L, "store-1", "2026-05", "employee-1"))
        .thenReturn(Optional.of("salary-existing"));

    assertThatThrownBy(() -> service.importHistorical(boss, "LEGACY-1", request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不会覆盖");

    verify(salaryRepository, never()).upsert(1L, "LEGACY-1", request);
  }

  @Test
  void assignEmployeeCreatesTargetStoreSalaryWithOriginalPositionSnapshot() {
    EmployeeResponse employee = employee(
        "employee-2", "store-2", "二店", "张三", "值班组长", new BigDecimal("4800.00"), "在职");
    SalaryAssignmentRequest assignment = new SalaryAssignmentRequest("store-1", "2026-05", "employee-2");
    SalaryRecordResponse saved = mock(SalaryRecordResponse.class);
    when(salaryQueryService.resolveStoreForWrite(
        boss, "store-1", "添加员工到工资名单", "2026-05"))
        .thenReturn("store-1");
    when(employeeRepository.record(1L, "employee-2")).thenReturn(Optional.of(employee));
    when(salaryRepository.recordForEmployeeMonth(1L, "employee-2", "2026-05"))
        .thenReturn(Optional.empty());
    when(salaryRepository.salaryProfile(1L, "employee-2", "2026-05"))
        .thenReturn(Optional.empty());
    when(salaryQueryService.requireRecord(eq(boss), anyString())).thenReturn(saved);

    SalaryRecordResponse result = service.assignEmployee(boss, assignment);

    ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SalaryRecordRequest> salaryCaptor = ArgumentCaptor.forClass(SalaryRecordRequest.class);
    verify(salaryRepository).upsert(eq(1L), idCaptor.capture(), salaryCaptor.capture());
    SalaryRecordRequest created = salaryCaptor.getValue();
    assertThat(result).isSameAs(saved);
    assertThat(idCaptor.getValue()).startsWith("SALADD-202605-");
    assertThat(created.storeId()).isEqualTo("store-1");
    assertThat(created.month()).isEqualTo("2026-05");
    assertThat(created.employeeId()).isEqualTo("employee-2");
    assertThat(created.employeeName()).isEqualTo("张三");
    assertThat(created.position()).isEqualTo("值班组长");
    assertThat(created.base()).isEqualByComparingTo("4800.00");
    assertThat(created.seniority()).isEqualByComparingTo("200.00");
    assertThat(created.birthdayBenefit()).isEqualByComparingTo("200.00");
    assertThat(created.gross()).isEqualByComparingTo("5200.00");
    verify(salaryQueryService).requireStoreScope(boss, "store-1");
    verify(salaryQueryService).requireStoreScope(boss, "store-2");
    verify(salaryRepository).logAction(
        eq(1L), eq(1L), eq("老板"), eq("salary_employee_assign"), eq(idCaptor.getValue()),
        eq("store-1"), eq("2026-05"), contains("岗位保持为值班组长"));
    verify(businessTodoService).reconcileAfterFinanceMutation(boss, "2026-05");
  }

  @Test
  void assignEmployeeRejectsDuplicateEmployeeMonthBeforeWriting() {
    EmployeeResponse employee = employee(
        "employee-2", "store-2", "二店", "张三", "值班组长", new BigDecimal("4800.00"), "在职");
    SalaryRecordResponse existing = mock(SalaryRecordResponse.class);
    when(salaryQueryService.resolveStoreForWrite(
        boss, "store-1", "添加员工到工资名单", "2026-05"))
        .thenReturn("store-1");
    when(employeeRepository.record(1L, "employee-2")).thenReturn(Optional.of(employee));
    when(salaryRepository.recordForEmployeeMonth(1L, "employee-2", "2026-05"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.assignEmployee(
        boss, new SalaryAssignmentRequest("store-1", "2026-05", "employee-2")))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("SALARY_ALREADY_EXISTS");
          assertThat(exception.getStatus().value()).isEqualTo(409);
        })
        .hasMessageContaining("不能重复添加");

    verify(salaryRepository, never()).upsert(eq(1L), anyString(), any(SalaryRecordRequest.class));
    verify(businessTodoService, never()).reconcileAfterFinanceMutation(boss, "2026-05");
  }

  @Test
  void manualLateNightKeepsGuaranteeDifferenceAndRepeatedSaveDoesNotAccumulate() {
    EmployeeResponse employee = employee(
        "employee-night", "store-1", "测试门店", "夜班员工", "营业员",
        new BigDecimal("2500.00"), "在职");
    SalaryRecordResponse guaranteed = salaryRecordWithLateNight("3000.00", "0.00");
    SalaryRecordResponse withLateNight = salaryRecordWithLateNight("3200.00", "200.00");
    // gross 故意传入错误值，验证服务端会以原保底差额和分项变化重算，
    // 不会盲信前端的应发合计。
    SalaryRecordRequest request = manualSalaryRequest("1.00", "200.00");

    when(salaryQueryService.resolveStoreForWrite(
        boss, "store-1", "保存工资记录", "2026-05"))
        .thenReturn("store-1");
    when(employeeRepository.record(1L, "employee-night")).thenReturn(Optional.of(employee));
    when(salaryRepository.record(1L, "salary-night"))
        .thenReturn(Optional.of(guaranteed), Optional.of(withLateNight),
            Optional.of(withLateNight), Optional.of(withLateNight));

    SalaryRecordResponse firstSave = service.save(boss, "salary-night", request);
    SalaryRecordResponse secondSave = service.save(boss, "salary-night", request);

    ArgumentCaptor<SalaryRecordRequest> persisted = ArgumentCaptor.forClass(SalaryRecordRequest.class);
    verify(salaryRepository, times(2)).upsert(eq(1L), eq("salary-night"), persisted.capture());
    assertThat(persisted.getAllValues()).allSatisfy(row -> {
      assertThat(row.lateNight()).isEqualByComparingTo("200.00");
      assertThat(row.gross()).isEqualByComparingTo("3200.00");
    });
    assertThat(firstSave.gross()).isEqualByComparingTo("3200.00");
    assertThat(secondSave.gross()).isEqualByComparingTo("3200.00");
  }

  @ParameterizedTest
  @ValueSource(strings = {"INTERN", "PART_TIME"})
  void saveAttendanceKeepsExplicitHoursForHourlyEmployeeWithGenericPosition(String employmentType) {
    EmployeeResponse hourlyEmployee = employee(
        "employee-hourly", "store-1", "测试门店", "计时员工", "调饮师",
        employmentType, BigDecimal.ZERO, "在职");
    SalaryRepository.AttendanceRow saved = new SalaryRepository.AttendanceRow(
        new BigDecimal("218.40"), new BigDecimal("218.40"), new BigDecimal("4.90"),
        new BigDecimal("223.30"), new BigDecimal("4.00"), "MANUAL", "CONFIRMED");
    when(employeeRepository.record(1L, "employee-hourly")).thenReturn(Optional.of(hourlyEmployee));
    when(salaryRepository.attendance(1L, "store-1", "employee-hourly", "2026-05"))
        .thenReturn(Optional.of(saved));

    SalaryRepository.AttendanceRow result = service.saveAttendance(
        boss,
        new SalaryAttendanceRequest(
            "store-1", "employee-hourly", "2026-05",
            new BigDecimal("218.4"), new BigDecimal("4.9"), new BigDecimal("218.4")));

    assertThat(result).isSameAs(saved);
    verify(salaryRepository).upsertAttendance(
        1L, 1L, "store-1", "employee-hourly", "2026-05",
        new BigDecimal("218.40"), new BigDecimal("218.40"),
        new BigDecimal("4.90"), new BigDecimal("223.30"));
  }

  @Test
  void deleteRemovesItemsBeforeEditableRecordAndKeepsAuditAndTodoReconciliation() {
    SalaryRecordResponse record = deletableSalaryRecord("DRAFT", 7);
    when(salaryRepository.record(1L, "salary-delete")).thenReturn(Optional.of(record));
    when(salaryRepository.deleteItems(1L, "salary-delete")).thenReturn(2);
    when(salaryRepository.deleteEditable(1L, "salary-delete", 7)).thenReturn(1);

    service.delete(boss, "salary-delete");

    InOrder deletionOrder = inOrder(salaryRepository);
    deletionOrder.verify(salaryRepository).deleteItems(1L, "salary-delete");
    deletionOrder.verify(salaryRepository).deleteEditable(1L, "salary-delete", 7);
    verify(salaryQueryService).requireStoreScope(boss, "store-1");
    verify(salaryRepository).logAction(
        1L, 1L, "老板", "salary_delete", "salary-delete", "store-1", "2026-05", "工资记录已删除");
    verify(businessTodoService).reconcileAfterFinanceMutation(boss, "2026-05");
  }

  @Test
  void deleteRejectsNonEditableStatusWithoutDeletingItemsOrRecord() {
    SalaryRecordResponse record = deletableSalaryRecord("SUBMITTED", 4);
    when(salaryRepository.record(1L, "salary-submitted")).thenReturn(Optional.of(record));

    assertThatThrownBy(() -> service.delete(boss, "salary-submitted"))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("SALARY_STATUS_LOCKED");
          assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        });

    verify(salaryRepository, never()).deleteItems(1L, "salary-submitted");
    verify(salaryRepository, never()).deleteEditable(1L, "salary-submitted", 4);
    verify(businessTodoService, never()).reconcileAfterFinanceMutation(boss, "2026-05");
  }

  @Test
  void deleteFailsOnConcurrentStatusOrVersionChangeWithoutAuditOrTodoReconciliation() {
    SalaryRecordResponse record = deletableSalaryRecord("DRAFT", 9);
    when(salaryRepository.record(1L, "salary-raced")).thenReturn(Optional.of(record));
    when(salaryRepository.deleteItems(1L, "salary-raced")).thenReturn(3);
    when(salaryRepository.deleteEditable(1L, "salary-raced", 9)).thenReturn(0);

    assertThatThrownBy(() -> service.delete(boss, "salary-raced"))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("VERSION_CONFLICT");
          assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        })
        .hasMessageContaining("刷新后重试");

    verify(salaryRepository).deleteItems(1L, "salary-raced");
    verify(salaryRepository).deleteEditable(1L, "salary-raced", 9);
    verify(salaryRepository, never()).logAction(
        eq(1L), eq(1L), anyString(), eq("salary_delete"), anyString(), anyString(), anyString(), anyString());
    verify(businessTodoService, never()).reconcileAfterFinanceMutation(boss, "2026-05");
  }

  private SalaryRecordResponse deletableSalaryRecord(String status, int version) {
    SalaryRecordResponse record = mock(SalaryRecordResponse.class);
    when(record.storeId()).thenReturn("store-1");
    when(record.month()).thenReturn("2026-05");
    when(record.status()).thenReturn(status);
    when(record.version()).thenReturn(version);
    return record;
  }

  private SalaryRecordRequest manualSalaryRequest(String gross, String lateNight) {
    return new SalaryRecordRequest(
        "store-1", "2026-05", "employee-night", "夜班员工", "营业员", "26天",
        new BigDecimal(gross), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, new BigDecimal("2500.00"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(lateNight), BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
    );
  }

  private SalaryRecordResponse salaryRecordWithLateNight(String gross, String lateNight) {
    SalaryRecordResponse record = mock(SalaryRecordResponse.class);
    when(record.storeId()).thenReturn("store-1");
    when(record.status()).thenReturn("DRAFT");
    when(record.gross()).thenReturn(new BigDecimal(gross));
    when(record.base()).thenReturn(new BigDecimal("2500.00"));
    when(record.lateNight()).thenReturn(new BigDecimal(lateNight));
    return record;
  }

  private EmployeeResponse employee(
      String id,
      String storeId,
      String storeName,
      String name,
      String position,
      BigDecimal baseSalary,
      String status
  ) {
    return employee(id, storeId, storeName, name, position, "FULL_TIME", baseSalary, status);
  }

  private EmployeeResponse employee(
      String id,
      String storeId,
      String storeName,
      String name,
      String position,
      String employmentType,
      BigDecimal baseSalary,
      String status
  ) {
    return new EmployeeResponse(
        id,
        storeId,
        "S001",
        storeName,
        1L,
        "测试品牌",
        name,
        "",
        "EMPLOYEE",
        position,
        employmentType,
        baseSalary,
        status,
        "2025-01-01",
        "",
        "5.20",
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
