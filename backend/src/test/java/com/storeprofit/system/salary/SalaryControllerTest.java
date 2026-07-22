package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SalaryControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final SalaryQueryService salaryQueryService = mock(SalaryQueryService.class);
  private final SalaryGenerationService salaryGenerationService = mock(SalaryGenerationService.class);
  private final SalaryWorkflowService salaryWorkflowService = mock(SalaryWorkflowService.class);
  private final SalaryExportService salaryExportService = mock(SalaryExportService.class);
  private final SalaryController controller = new SalaryController(
      authService,
      salaryQueryService,
      salaryGenerationService,
      salaryWorkflowService,
      salaryExportService
  );
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

  @Test
  void listUsesAuthenticatedUserAndWrapsResponse() {
    SalaryRecordResponse row = response("pay-1");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryQueryService.records(boss, "2026-05", 1L, "s1", false)).thenReturn(List.of(row));

    ApiResponse<List<SalaryRecordResponse>> result = controller.records("Bearer token", "2026-05", 1L, "s1", false);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).containsExactly(row);
    verify(authService).requireUser("Bearer token");
    verify(salaryQueryService).records(boss, "2026-05", 1L, "s1", false);
  }

  @Test
  void employeePageUsesAuthenticatedUserAndEmployeeFilters() {
    SalaryRecordResponse pending = response("pending");
    SalaryEmployeePageResponse page = new SalaryEmployeePageResponse(
        List.of(pending), 1, 1, 20, 1,
        new SalarySummaryResponse("2026-05", 1, 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        Map.of("PENDING_GENERATION", 1), BigDecimal.ZERO, BigDecimal.ZERO
    );
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryQueryService.employeePage(boss, "2026-05", 1L, "s1", "PENDING_GENERATION", "Alice", 1, 20))
        .thenReturn(page);

    ApiResponse<SalaryEmployeePageResponse> result = controller.employeePage(
        "Bearer token", "2026-05", 1L, "s1", "PENDING_GENERATION", "Alice", 1, 20);

    assertThat(result.data()).isSameAs(page);
    verify(salaryQueryService).employeePage(boss, "2026-05", 1L, "s1", "PENDING_GENERATION", "Alice", 1, 20);
  }

  @Test
  void createUsesAuthenticatedUserAndReturnsSavedRecord() {
    SalaryRecordRequest request = request();
    SalaryRecordResponse row = response("pay-created");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryWorkflowService.save(boss, null, request)).thenReturn(row);

    ApiResponse<SalaryRecordResponse> result = controller.create("Bearer token", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(row);
    verify(authService).requireUser("Bearer token");
    verify(salaryWorkflowService).save(boss, null, request);
  }

  @Test
  void saveAttendanceUsesAuthenticatedUserAndDelegatesToWorkflow() {
    SalaryAttendanceRequest request = new SalaryAttendanceRequest(
        "s1", "emp-1", "2026-05",
        new BigDecimal("26"), new BigDecimal("2.5"), new BigDecimal("208"));
    SalaryRepository.AttendanceRow attendance = new SalaryRepository.AttendanceRow(
        new BigDecimal("26.00"), new BigDecimal("208.00"), new BigDecimal("2.50"),
        new BigDecimal("210.50"), new BigDecimal("4.00"), "MANUAL", "CONFIRMED");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryWorkflowService.saveAttendance(boss, request)).thenReturn(attendance);

    ApiResponse<SalaryRepository.AttendanceRow> result = controller.saveAttendance(
        "Bearer token", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(attendance);
    verify(authService).requireUser("Bearer token");
    verify(salaryWorkflowService).saveAttendance(boss, request);
  }

  @Test
  void updateAndDeleteUsePathId() {
    SalaryRecordRequest request = request();
    SalaryRecordResponse row = response("pay-1");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryWorkflowService.save(boss, "pay-1", request)).thenReturn(row);

    ApiResponse<SalaryRecordResponse> updated = controller.update("Bearer token", "pay-1", request);
    ApiResponse<Void> deleted = controller.delete("Bearer token", "pay-1");

    assertThat(updated.data()).isSameAs(row);
    assertThat(deleted.success()).isTrue();
    verify(salaryWorkflowService).save(boss, "pay-1", request);
    verify(salaryWorkflowService).delete(boss, "pay-1");
  }

  @Test
  void historicalImportUsesDedicatedBossOnlyWorkflow() {
    SalaryRecordRequest request = request();
    SalaryRecordResponse row = response("LEGACY-salary-1");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryWorkflowService.importHistorical(boss, "LEGACY-salary-1", request)).thenReturn(row);

    ApiResponse<SalaryRecordResponse> result = controller.importHistorical(
        "Bearer token", "LEGACY-salary-1", request
    );

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(row);
    verify(salaryWorkflowService).importHistorical(boss, "LEGACY-salary-1", request);
  }

  @Test
  void summaryUsesAuthenticatedUserAndWrapsResponse() {
    SalarySummaryResponse summary = new SalarySummaryResponse(
        "2026-05",
        1,
        2,
        new BigDecimal("3000.00"),
        new BigDecimal("2000.00"),
        new BigDecimal("180.00"),
        new BigDecimal("70.00")
    );
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryQueryService.summary(boss, "2026-05", 1L, "s1")).thenReturn(summary);

    ApiResponse<SalarySummaryResponse> result = controller.summary("Bearer token", "2026-05", 1L, "s1");

    assertThat(result.data()).isSameAs(summary);
    verify(salaryQueryService).summary(boss, "2026-05", 1L, "s1");
  }

  @Test
  void businessMetricsUsesAuthenticatedUserAndSalaryScopeParameters() {
    SalaryBusinessMetricsResponse metrics = new SalaryBusinessMetricsResponse(
        new BigDecimal("100000.99"),
        new BigDecimal("500.00"),
        new BigDecimal("200.00"),
        new BigDecimal("41600.00"),
        new BigDecimal("3000.00"),
        new BigDecimal("2750.00"),
        new BigDecimal("250.00")
    );
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(salaryQueryService.businessMetrics(boss, "2026-05", 1L, "s1")).thenReturn(metrics);

    ApiResponse<SalaryBusinessMetricsResponse> result = controller.businessMetrics(
        "Bearer token", "2026-05", 1L, "s1");

    assertThat(result.data()).isSameAs(metrics);
    verify(authService).requireUser("Bearer token");
    verify(salaryQueryService).businessMetrics(boss, "2026-05", 1L, "s1");
  }

  private SalaryRecordRequest request() {
    return new SalaryRecordRequest(
        "s1",
        "2026-05",
        null,
        "Alice",
        "Barista",
        "26",
        new BigDecimal("1000"),
        new BigDecimal("216"),
        new BigDecimal("2.5"),
        new BigDecimal("218.5"),
        new BigDecimal("1"),
        "one day left",
        new BigDecimal("700"),
        new BigDecimal("300"),
        new BigDecimal("100"),
        new BigDecimal("200"),
        new BigDecimal("50"),
        new BigDecimal("80"),
        new BigDecimal("70"),
        new BigDecimal("20"),
        new BigDecimal("25"),
        new BigDecimal("10"),
        new BigDecimal("5"),
        new BigDecimal("60"),
        new BigDecimal("15"),
        new BigDecimal("0")
    );
  }

  private SalaryRecordResponse response(String id) {
    return new SalaryRecordResponse(
        id,
        "s1",
        "001",
        "One",
        1L,
        "Tea",
        "2026-05",
        "emp-1",
        "Alice",
        "Barista",
        "实习",
        "26",
        new BigDecimal("1000.00"),
        new BigDecimal("950.00"),
        new BigDecimal("216.00"),
        new BigDecimal("2.50"),
        new BigDecimal("218.50"),
        new BigDecimal("1.00"),
        "one day left",
        new BigDecimal("700.00"),
        new BigDecimal("300.00"),
        new BigDecimal("100.00"),
        new BigDecimal("200.00"),
        new BigDecimal("50.00"),
        new BigDecimal("80.00"),
        new BigDecimal("70.00"),
        new BigDecimal("20.00"),
        new BigDecimal("25.00"),
        new BigDecimal("10.00"),
        new BigDecimal("5.00"),
        new BigDecimal("60.00"),
        new BigDecimal("15.00"),
        new BigDecimal("0.00"),
        "DRAFT",
        null,
        null,
        null,
        null,
        null,
        1
    );
  }
}
