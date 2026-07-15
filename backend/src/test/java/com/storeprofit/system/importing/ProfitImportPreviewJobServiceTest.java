package com.storeprofit.system.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ProfitImportPreviewJobServiceTest {
  @Test
  void rejectsEmptyAndOversizedFilesBeforeScheduling() {
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(mock(ProfitImportService.class), Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);

    assertThatThrownBy(() -> service.submit(user,
        new MockMultipartFile("file", "empty.xlsx", "application/octet-stream", new byte[0]),
        ProfitImportSourceType.EXCEL, "store-1", "2026-07"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("请先选择");
    assertThatThrownBy(() -> service.submit(user,
        new MockMultipartFile("file", "large.xlsx", "application/octet-stream", new byte[10 * 1024 * 1024 + 1]),
        ProfitImportSourceType.EXCEL, "store-1", "2026-07"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能超过 10MB");
  }

  @Test
  void previewDoesNotWriteAndConfirmIsIdempotent() {
    ProfitImportService importService = mock(ProfitImportService.class);
    ProfitImportRow row = new ProfitImportRow(
        "row-1", "store-1", "荆州之星店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("160871.84")), List.of(), List.of(), false, "READY");
    when(importService.recognizeForSingleStoreMonthPreview(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse("legacy", ProfitImportSourceType.EXCEL, "READY", List.of(row), List.of()));
    when(importService.commit(any(), any())).thenReturn(
        new ProfitImportCommitResponse(1, 0, List.of(row)));
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    MockMultipartFile file = new MockMultipartFile(
        "file", "7月实收-日营业额.xlsx", "application/octet-stream", "xlsx".getBytes(StandardCharsets.UTF_8));

    ProfitImportPreviewJobResponse preview = service.submit(
        user, file, ProfitImportSourceType.EXCEL, "store-1", "2026-07");

    assertThat(preview.status()).isEqualTo("READY");
    assertThat(preview.monthConflict()).isFalse();
    assertThat(preview.detectedMonths()).containsExactly("2026-07");
    assertThat(preview.salesTotal()).isEqualByComparingTo("160871.84");
    verify(importService, never()).commit(any(), any());

    ProfitImportJobConfirmRequest confirm = new ProfitImportJobConfirmRequest(
        List.of(new ProfitImportJobConfirmRequest.RowDecision("row-1", false)));
    assertThat(service.confirm(user, preview.jobId(), confirm).saved()).isEqualTo(1);
    assertThat(service.confirm(user, preview.jobId(), confirm).saved()).isEqualTo(1);
    verify(importService, times(1)).commit(any(), any());
  }

  @Test
  void previewRejectsRowsOutsideTheSelectedStoreOrMonth() {
    ProfitImportService importService = mock(ProfitImportService.class);
    ProfitImportRow otherStore = new ProfitImportRow(
        "row-other-store", "store-2", "美佳华店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("100")), List.of(), List.of(), true, "CONFLICT");
    ProfitImportRow otherMonth = new ProfitImportRow(
        "row-other-month", "store-1", "荆州之星店", "2026-06", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("200")), List.of(), List.of(), false, "READY");
    when(importService.recognizeForSingleStoreMonthPreview(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse(
            "legacy", ProfitImportSourceType.CSV, "READY", List.of(otherStore, otherMonth), List.of()));
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    MockMultipartFile file = new MockMultipartFile(
        "file", "门店利润_2026-07.csv", "text/csv", "csv".getBytes(StandardCharsets.UTF_8));

    ProfitImportPreviewJobResponse preview = service.submit(
        user, file, ProfitImportSourceType.CSV, "store-1", "2026-07");

    assertThat(preview.status()).isEqualTo("FAILED");
    assertThat(preview.errorRows()).isEqualTo(2);
    assertThat(preview.errors()).anyMatch(value -> value.contains("其他门店记录"));
    assertThat(preview.errors()).anyMatch(value -> value.contains("其他月份记录"));
    assertThat(preview.rows().get(0).errors()).anyMatch(value -> value.contains("请拆分文件后分别导入"));
    assertThat(preview.rows().get(1).errors()).anyMatch(value -> value.contains("切换对应月份后重新导入"));
    assertThatThrownBy(() -> service.confirm(user, preview.jobId(),
        new ProfitImportJobConfirmRequest(List.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("预览尚未完成");
    verify(importService, never()).commit(any(), any());
  }

  @Test
  void previewRequiresAConcreteStoreAndMonth() {
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(mock(ProfitImportService.class), Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    MockMultipartFile file = new MockMultipartFile("file", "profit.csv", "text/csv", "x".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.submit(user, file, ProfitImportSourceType.CSV, "", "2026-07"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("选择一间门店");
    assertThatThrownBy(() -> service.submit(user, file, ProfitImportSourceType.CSV, "store-1", ""))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("选择月份");
  }

  @Test
  void everyPreviewOperationChecksTheFinanceImportBoundaryBeforeUsingTheJob() {
    ProfitImportService importService = mock(ProfitImportService.class);
    AuthUser finance = new AuthUser(7L, 1L, "测试企业", "finance", "", "财务", "FINANCE", "store-1", true);
    AuthUser manager = new AuthUser(8L, 1L, "测试企业", "manager", "", "店长", "STORE_MANAGER", "store-1", true);
    ProfitImportRow row = new ProfitImportRow(
        "row-1", "store-1", "荆州之星店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("100")), List.of(), List.of(), false, "READY");
    when(importService.recognizeForSingleStoreMonthPreview(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse("preview", ProfitImportSourceType.CSV, "READY", List.of(row), List.of()));
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "当前账号没有访问该业务的权限", org.springframework.http.HttpStatus.FORBIDDEN);
    doThrow(forbidden).when(importService).requireImportAccess(manager);
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run);
    ProfitImportPreviewJobResponse preview = service.submit(
        finance,
        new MockMultipartFile("file", "profit.csv", "text/csv", "csv".getBytes(StandardCharsets.UTF_8)),
        ProfitImportSourceType.CSV,
        "store-1",
        "2026-07");

    assertThatThrownBy(() -> service.submit(
        manager,
        new MockMultipartFile("file", "profit.csv", "text/csv", "csv".getBytes(StandardCharsets.UTF_8)),
        ProfitImportSourceType.CSV,
        "store-1",
        "2026-07")).isSameAs(forbidden);
    assertThatThrownBy(() -> service.get(manager, preview.jobId())).isSameAs(forbidden);
    assertThatThrownBy(() -> service.confirm(
        manager, preview.jobId(), new ProfitImportJobConfirmRequest(List.of()))).isSameAs(forbidden);
    assertThatThrownBy(() -> service.cancel(manager, preview.jobId())).isSameAs(forbidden);

    verify(importService, times(4)).requireImportAccess(manager);
  }

  @Test
  void duplicateTargetSummariesAreAllRejectedAndCannotBeConfirmed() {
    ProfitImportService importService = mock(ProfitImportService.class);
    ProfitImportRow first = new ProfitImportRow(
        "row-1", "store-1", "荆州之星店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("100")), List.of(), List.of(), false, "READY");
    ProfitImportRow second = new ProfitImportRow(
        "row-2", "store-1", "荆州之星店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("200")), List.of(), List.of(), false, "READY");
    when(importService.recognizeForSingleStoreMonthPreview(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse("legacy", ProfitImportSourceType.CSV, "READY", List.of(first, second), List.of()));
    AuditRepository auditRepository = mock(AuditRepository.class);
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run, null, auditRepository);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);

    ProfitImportPreviewJobResponse preview = service.submit(
        user, new MockMultipartFile("file", "profit.csv", "text/csv", "csv".getBytes(StandardCharsets.UTF_8)),
        ProfitImportSourceType.CSV, "store-1", "2026-07");

    assertThat(preview.status()).isEqualTo("FAILED");
    assertThat(preview.rows()).allSatisfy(row ->
        assertThat(row.errors()).anyMatch(message -> message.contains("仅支持一条")));
    assertThatThrownBy(() -> service.confirm(user, preview.jobId(), new ProfitImportJobConfirmRequest(List.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("预览尚未完成");
    verify(auditRepository).writeLog(any(), argThat(log -> "利润导入范围拒绝".equals(log.action())));
    verify(importService, never()).commit(any(), any());
  }

  @Test
  void forgedConfirmDecisionCannotChangeTheFrozenTargetRow() {
    ProfitImportService importService = mock(ProfitImportService.class);
    ProfitImportRow row = new ProfitImportRow(
        "row-1", "store-1", "荆州之星店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("100")), List.of(), List.of(), false, "READY");
    when(importService.recognizeForSingleStoreMonthPreview(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse("legacy", ProfitImportSourceType.CSV, "READY", List.of(row), List.of()));
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    ProfitImportPreviewJobResponse preview = service.submit(
        user, new MockMultipartFile("file", "profit.csv", "text/csv", "csv".getBytes(StandardCharsets.UTF_8)),
        ProfitImportSourceType.CSV, "store-1", "2026-07");

    assertThatThrownBy(() -> service.confirm(user, preview.jobId(),
        new ProfitImportJobConfirmRequest(List.of(new ProfitImportJobConfirmRequest.RowDecision("forged-row", true)))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不属于当前预览任务");
    verify(importService, never()).commit(any(), any());
  }

  @Test
  void existingTargetRequiresExplicitSingleRecordOverwriteConfirmation() {
    ProfitImportService importService = mock(ProfitImportService.class);
    ProfitImportRow row = new ProfitImportRow(
        "row-1", "store-1", "荆州之星店", "2026-07", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("100")), List.of(), List.of(), true, "CONFLICT",
        Map.of("sales", new BigDecimal("90")));
    when(importService.recognizeForSingleStoreMonthPreview(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse("legacy", ProfitImportSourceType.CSV, "READY", List.of(row), List.of()));
    when(importService.commit(any(), any())).thenReturn(new ProfitImportCommitResponse(1, 0, List.of(row)));
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    ProfitImportPreviewJobResponse preview = service.submit(
        user, new MockMultipartFile("file", "profit.csv", "text/csv", "csv".getBytes(StandardCharsets.UTF_8)),
        ProfitImportSourceType.CSV, "store-1", "2026-07");

    assertThat(preview.rows().getFirst().existingValues()).containsEntry("sales", new BigDecimal("90"));
    assertThatThrownBy(() -> service.confirm(user, preview.jobId(), new ProfitImportJobConfirmRequest(List.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("确认覆盖");

    assertThat(service.confirm(user, preview.jobId(), new ProfitImportJobConfirmRequest(
        List.of(new ProfitImportJobConfirmRequest.RowDecision("row-1", true)))).saved()).isEqualTo(1);
    ArgumentCaptor<ProfitImportCommitRequest> requestCaptor = ArgumentCaptor.forClass(ProfitImportCommitRequest.class);
    verify(importService).commit(any(), requestCaptor.capture());
    assertThat(requestCaptor.getValue().rows()).singleElement().satisfies(committed -> {
      assertThat(committed.storeId()).isEqualTo("store-1");
      assertThat(committed.month()).isEqualTo("2026-07");
      assertThat(committed.overwrite()).isTrue();
    });
  }
}
