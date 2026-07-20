package com.storeprofit.system.dailyloss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class DailyLossControllerTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final DailyLossService dailyLossService = mock(DailyLossService.class);
  private final DailyLossController controller = new DailyLossController(accessControl, dailyLossService);

  @Test
  void unauthenticatedItemsStopsBeforeService() {
    BusinessException unauthorized = new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    when(accessControl.requireUser(null)).thenThrow(unauthorized);

    assertThatThrownBy(() -> controller.items(null))
        .isSameAs(unauthorized)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verify(dailyLossService, never()).activeItems(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unauthenticatedTodayReportStopsBeforeService() {
    BusinessException unauthorized = new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    when(accessControl.requireUser(null)).thenThrow(unauthorized);

    assertThatThrownBy(() -> controller.today(null))
        .isSameAs(unauthorized)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verify(dailyLossService, never()).today(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unauthenticatedMonthlyExcelExportStopsBeforeService() {
    BusinessException unauthorized = new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    when(accessControl.requireUser(null)).thenThrow(unauthorized);

    assertThatThrownBy(() -> controller.exportMonthlyExcel(null, "2026-07", "s1"))
        .isSameAs(unauthorized)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verify(dailyLossService, never()).exportMonthlyExcel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void monthlyExcelExportUsesXlsxHeadersUtf8FilenameAndPrivateCache() {
    AuthUser user = new AuthUser(7L, 1L, "测试租户", "tester", "hash", "测试人员", "FINANCE", null, true, 1L);
    when(accessControl.requireUser("Bearer token")).thenReturn(user);
    when(dailyLossService.exportMonthlyExcel(user, "s1", "2026-07"))
        .thenReturn(new DailyLossMonthlyExcelExport(new byte[]{1, 2, 3}, "测试门店-2026年07月-每日报损.xlsx", 31, 2));

    ResponseEntity<byte[]> response = controller.exportMonthlyExcel("Bearer token", "2026-07", "s1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store, max-age=0");
    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .contains("filename*=UTF-8''" + java.net.URLEncoder.encode("测试门店-2026年07月-每日报损.xlsx", StandardCharsets.UTF_8)
            .replace("+", "%20"));
    assertThat(response.getBody()).containsExactly(1, 2, 3);
  }
}
