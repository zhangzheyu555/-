package com.storeprofit.system.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class StorageControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final StorageService storageService = mock(StorageService.class);
  private final StorageController controller = new StorageController(authService, storageService);
  private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
  private final AuthUser storeManager = new AuthUser(
      7L, 1L, "default", "manager-s1", "s1", "店长", "STORE_MANAGER", "s1", true);

  @Test
  void deniedAttachmentReadIsMappedToHttp403() {
    BusinessException denied = new BusinessException(
        "FORBIDDEN", "无权查看该门店的巡检照片", HttpStatus.FORBIDDEN);
    when(authService.requireUser("Bearer scoped-token")).thenReturn(storeManager);
    when(storageService.attachment(storeManager, 502L)).thenThrow(denied);

    assertThatThrownBy(() -> controller.attachment("Bearer scoped-token", 502L))
        .isSameAs(denied);
    ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleBusiness(
        denied, new MockHttpServletRequest("GET", "/api/storage/attachments/502"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    verify(authService).requireUser("Bearer scoped-token");
    verify(storageService).attachment(storeManager, 502L);
  }
}
