package com.storeprofit.system.dailyloss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
  void unauthenticatedPhotoExportStopsBeforeService() {
    BusinessException unauthorized = new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    when(accessControl.requireUser(null)).thenThrow(unauthorized);

    assertThatThrownBy(() -> controller.exportMonthlyPhotos(null, "s1", "2026-07"))
        .isSameAs(unauthorized)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verify(dailyLossService, never()).exportMonthlyPhotos(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
  }
}
