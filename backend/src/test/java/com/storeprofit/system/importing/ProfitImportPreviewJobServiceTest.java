package com.storeprofit.system.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
  void previewDoesNotWriteAndConfirmIsMonthSafeAndIdempotent() {
    ProfitImportService importService = mock(ProfitImportService.class);
    ProfitImportRow row = new ProfitImportRow(
        "row-1", "store-1", "荆州之星店", "2026-05", new BigDecimal("0.92"),
        Map.of("sales", new BigDecimal("160871.84")), List.of(), List.of(), false, "READY");
    when(importService.recognize(any(), any(), any(), any(), any())).thenReturn(
        new ProfitImportRecognizeResponse("legacy", ProfitImportSourceType.EXCEL, "READY", List.of(row), List.of()));
    when(importService.commit(any(), any())).thenReturn(
        new ProfitImportCommitResponse(1, 0, List.of(row)));
    ProfitImportPreviewJobService service = new ProfitImportPreviewJobService(importService, Runnable::run);
    AuthUser user = new AuthUser(7L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    MockMultipartFile file = new MockMultipartFile(
        "file", "5月实收-日营业额.xlsx", "application/octet-stream", "xlsx".getBytes(StandardCharsets.UTF_8));

    ProfitImportPreviewJobResponse preview = service.submit(
        user, file, ProfitImportSourceType.EXCEL, "store-1", "2026-07");

    assertThat(preview.status()).isEqualTo("READY");
    assertThat(preview.monthConflict()).isTrue();
    assertThat(preview.detectedMonths()).containsExactly("2026-05");
    assertThat(preview.salesTotal()).isEqualByComparingTo("160871.84");
    verify(importService, never()).commit(any(), any());

    assertThatThrownBy(() -> service.confirm(user, preview.jobId(),
        new ProfitImportJobConfirmRequest(false, List.of(new ProfitImportJobConfirmRequest.RowDecision("row-1", false)))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("请先确认使用文件月份");

    ProfitImportJobConfirmRequest confirm = new ProfitImportJobConfirmRequest(
        true, List.of(new ProfitImportJobConfirmRequest.RowDecision("row-1", false)));
    assertThat(service.confirm(user, preview.jobId(), confirm).saved()).isEqualTo(1);
    assertThat(service.confirm(user, preview.jobId(), confirm).saved()).isEqualTo(1);
    verify(importService, times(1)).commit(any(), any());
  }
}
