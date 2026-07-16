package com.storeprofit.system.platform.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserControllerAuthorizationContractTest {
  @Test
  void accountManagementWithoutTokenReturnsHttp401() throws Exception {
    AuthService authService = mock(AuthService.class);
    UserManagementService userManagementService = mock(UserManagementService.class);
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new UserController(authService, userManagementService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .addFilters(new RequestIdFilter())
        .build();

    mockMvc.perform(get("/api/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(authService).requireUser(null);
    verifyNoInteractions(userManagementService);
  }

  @Test
  void controllerExposesCatalogReadAndAtomicAuthorizationUpdate() {
    AuthService authService = mock(AuthService.class);
    UserManagementService userManagementService = mock(UserManagementService.class);
    UserController controller = new UserController(authService, userManagementService);
    AuthUser boss = new AuthUser(1L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true, 3L);
    AuthorizationCatalogResponse catalog = new AuthorizationCatalogResponse(List.of(), List.of("STORE"), List.of("ALL"));
    UserAuthorizationResponse authorization = new UserAuthorizationResponse(
        2L, "FINANCE", null, 4L, List.of(), List.of(), List.of(), List.of());
    UserAuthorizationUpdateRequest request = new UserAuthorizationUpdateRequest(List.of(), List.of());
    UserAccessProfileUpdateRequest accessProfileRequest = new UserAccessProfileUpdateRequest(
        "测试财务", "FINANCE", null, List.of(), true, List.of(), List.of());
    UserAccessProfileResponse accessProfile = new UserAccessProfileResponse(
        mock(UserResponse.class), authorization);
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    when(userManagementService.authorizationCatalog(boss)).thenReturn(catalog);
    when(userManagementService.authorization(boss, 2L)).thenReturn(authorization);
    when(userManagementService.updateAuthorization(boss, 2L, request)).thenReturn(authorization);
    when(userManagementService.updateAccessProfile(boss, 2L, accessProfileRequest)).thenReturn(accessProfile);

    ApiResponse<AuthorizationCatalogResponse> catalogResponse = controller.authorizationCatalog("Bearer boss-token");
    ApiResponse<UserAuthorizationResponse> readResponse = controller.authorization("Bearer boss-token", 2L);
    ApiResponse<UserAuthorizationResponse> updateResponse = controller.updateAuthorization(
        "Bearer boss-token", 2L, request);
    ApiResponse<UserAccessProfileResponse> accessProfileResponse = controller.updateAccessProfile(
        "Bearer boss-token", 2L, accessProfileRequest);

    assertThat(catalogResponse.data()).isSameAs(catalog);
    assertThat(readResponse.data()).isSameAs(authorization);
    assertThat(updateResponse.data()).isSameAs(authorization);
    assertThat(accessProfileResponse.data()).isSameAs(accessProfile);
    verify(userManagementService).authorizationCatalog(boss);
    verify(userManagementService).authorization(boss, 2L);
    verify(userManagementService).updateAuthorization(boss, 2L, request);
    verify(userManagementService).updateAccessProfile(boss, 2L, accessProfileRequest);
  }
}
