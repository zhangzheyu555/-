package com.storeprofit.system.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;

class StoreControllerHttpAuthorizationTest {
  private static final String STORE_JSON = """
      {
        "id":"rg1",
        "code":"RG1",
        "name":"一店",
        "brandId":1,
        "area":"荆州",
        "manager":"店长",
        "openDate":"2026-01-01",
        "status":"营业中",
        "note":""
      }
      """;

  private final AuthService authService = mock(AuthService.class);
  private final OrganizationRepository repository = mock(OrganizationRepository.class);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
      new StoreController(authService, new OrganizationService(repository)))
      .setControllerAdvice(new GlobalExceptionHandler())
      .addFilters(new RequestIdFilter())
      .build();

  @Test
  void storeManagerWriteEndpointsAreMappedToHttp403ByTheGlobalExceptionHandler() throws Exception {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    when(authService.requireUser("Bearer manager-token")).thenReturn(manager);

    mockMvc.perform(post("/api/stores")
            .header("Authorization", "Bearer manager-token")
            .contentType(APPLICATION_JSON)
            .content(STORE_JSON))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    mockMvc.perform(put("/api/stores")
            .header("Authorization", "Bearer manager-token")
            .contentType(APPLICATION_JSON)
            .content(STORE_JSON))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    mockMvc.perform(delete("/api/stores/rg1")
            .header("Authorization", "Bearer manager-token"))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(authService, times(3)).requireUser("Bearer manager-token");
    verifyNoInteractions(repository);
  }

  @Test
  void bossCanWriteStoreThroughTheControllerAndRealServiceFallback() throws Exception {
    AuthUser boss = user("BOSS", null);
    StoreUpsertRequest request = new StoreUpsertRequest(
        "rg1", "RG1", "一店", 1L, "荆州", "店长", "2026-01-01", "营业中", "");
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    when(repository.store(1L, "rg1")).thenReturn(Optional.empty());
    when(repository.brandExists(1L, 1L)).thenReturn(true);

    mockMvc.perform(post("/api/stores")
            .header("Authorization", "Bearer boss-token")
            .contentType(APPLICATION_JSON)
            .content(STORE_JSON))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("OK"));

    verify(repository).upsertStore(1L, request);
  }

  @Test
  void bossCanDeleteStoreThroughTheControllerAndRealServiceFallback() throws Exception {
    AuthUser boss = user("BOSS", null);
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    when(repository.store(1L, "rg1")).thenReturn(Optional.of(new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "2026-01-01", "停用", "")));
    when(repository.deleteStore(1L, "rg1")).thenReturn(1);

    mockMvc.perform(delete("/api/stores/rg1")
            .header("Authorization", "Bearer boss-token"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("OK"));

    verify(repository).storeHasLinkedData(1L, "rg1");
    verify(repository).deleteStore(1L, "rg1");
  }

  @Test
  void anonymousStoreWriteIsMappedToHttp401BeforeAnyRepositoryAccess() throws Exception {
    when(authService.requireUser(null)).thenThrow(new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(post("/api/stores")
            .contentType(APPLICATION_JSON)
            .content(STORE_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(authService).requireUser(null);
    verifyNoInteractions(repository);
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(
        7L, 1L, "default", "user", "", "测试账号", role, storeId, true);
  }
}
