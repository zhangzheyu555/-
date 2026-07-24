package com.storeprofit.system.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "managerEmployeeId":"e1",
        "managerPhone":"13800138000",
        "openDate":"2026-01-01",
        "status":"营业中",
        "note":"",
        "regionCode":"JINGZHOU",
        "costAccountStoreId":"rg1",
        "version":0
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
        "rg1", "RG1", "一店", 1L, "荆州", "店长", "13800138000",
        "2026-01-01", "营业中", "", "JINGZHOU", null, "e1", "rg1", 0L);
    StoreResponse saved = new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "13800138000",
        "2026-01-01", "营业中", "", "JINGZHOU", null, null,
        "e1", "rg1", "一店", 0L);
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    when(repository.store(1L, "rg1")).thenReturn(Optional.empty(), Optional.of(saved));
    when(repository.brandExists(1L, 1L)).thenReturn(true);
    when(repository.manager(1L, "e1")).thenReturn(Optional.of(
        new OrganizationRepository.ManagerReference(
            "e1", "店长", "13800138000", "rg1", "一店", "在职")));

    mockMvc.perform(post("/api/stores")
            .header("Authorization", "Bearer boss-token")
            .contentType(APPLICATION_JSON)
            .content(STORE_JSON))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("OK"));

    verify(repository).insertStore(1L, request, null);
  }

  @Test
  void knowledgeBaseScopeQueryUsesTheDedicatedStoreOptionBoundary() throws Exception {
    AuthUser supervisor = user("SUPERVISOR", null);
    OrganizationService organizationService = mock(OrganizationService.class);
    MockMvc scopedMockMvc = MockMvcBuilders.standaloneSetup(
            new StoreController(authService, organizationService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .addFilters(new RequestIdFilter())
        .build();
    when(authService.requireUser("Bearer supervisor-token")).thenReturn(supervisor);
    when(organizationService.knowledgeBaseStores(supervisor)).thenReturn(java.util.List.of());

    scopedMockMvc.perform(get("/api/stores")
            .param("knowledgeBaseScope", "true")
            .header("Authorization", "Bearer supervisor-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(organizationService).knowledgeBaseStores(supervisor);
    verifyNoInteractions(repository);
  }

  @Test
  void bossCannotPhysicallyDeleteStoreThroughTheController() throws Exception {
    AuthUser boss = user("BOSS", null);
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    when(repository.store(1L, "rg1")).thenReturn(Optional.of(new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "2026-01-01", "停用", "")));
    mockMvc.perform(delete("/api/stores/rg1")
            .header("Authorization", "Bearer boss-token"))
        .andExpect(status().isConflict())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("STORE_DELETE_DISABLED"));

    verify(repository, org.mockito.Mockito.never()).deleteStore(1L, "rg1");
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
