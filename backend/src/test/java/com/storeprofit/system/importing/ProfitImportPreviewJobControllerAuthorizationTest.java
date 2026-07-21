package com.storeprofit.system.importing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProfitImportPreviewJobControllerAuthorizationTest {
  private final AuthService authService = mock(AuthService.class);
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final SpreadsheetProfitParser spreadsheetProfitParser = mock(SpreadsheetProfitParser.class);
  private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
  private final FinanceRepository financeRepository = mock(FinanceRepository.class);
  private final FinanceService financeService = mock(FinanceService.class);
  private final AccessControlService accessControl = new AccessControlService(
      authService, authRepository, auditRepository);
  private final ProfitImportService importService = new ProfitImportService(
      spreadsheetProfitParser, organizationRepository, financeRepository, financeService, accessControl);
  private final ProfitImportPreviewJobService previewService = new ProfitImportPreviewJobService(
      importService, Runnable::run);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
      new ProfitImportPreviewJobController(authService, previewService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .addFilters(new RequestIdFilter())
      .build();

  @BeforeEach
  void setUp() throws Exception {
    when(authRepository.assignedStoreScope(anyLong(), anyLong())).thenReturn(List.of());
    when(organizationRepository.stores(anyLong(), any())).thenReturn(List.of(
        new StoreResponse("rg1", "RG1", "荆州之星店", 1L, "茄果", "荆州", "店长", "", "营业中", "")
    ));
    when(spreadsheetProfitParser.parse(any(), any(), any(), any(), any())).thenReturn(List.of(row()));
  }

  @Test
  void unauthenticatedPreviewSubmissionReturns401BeforeFileProcessing() throws Exception {
    when(authService.requireUser(null)).thenThrow(unauthorized());

    mockMvc.perform(previewRequest())
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verifyNoInteractions(spreadsheetProfitParser, financeService, auditRepository);
  }

  @Test
  void storeManagerPreviewIsRejectedBeforeAsyncJobAllocationAndAudited() throws Exception {
    AuthUser manager = user(21L, "STORE_MANAGER", "rg1");
    when(authService.requireUser("Bearer manager-token")).thenReturn(manager);

    mockMvc.perform(previewRequest().header("Authorization", "Bearer manager-token"))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        any(AuthUser.class), any(String.class), any(String.class), any(String.class), any(), any(), any(String.class));
    verifyNoInteractions(spreadsheetProfitParser, financeService);
  }

  @Test
  void bossCanSubmitPreviewWhileStoreManagerCannotReadConfirmOrCancelIt() throws Exception {
    AuthUser boss = user(22L, "BOSS", null);
    AuthUser manager = user(23L, "STORE_MANAGER", "rg1");
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    when(authService.requireUser("Bearer manager-token")).thenReturn(manager);

    MvcResult result = mockMvc.perform(previewRequest().header("Authorization", "Bearer boss-token"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.status").value("READY"))
        .andReturn();
    String jobId = new ObjectMapper().readTree(result.getResponse().getContentAsString())
        .path("data").path("jobId").asText();

    mockMvc.perform(get("/api/profit-imports/{jobId}", jobId)
            .header("Authorization", "Bearer manager-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mockMvc.perform(post("/api/profit-imports/{jobId}/confirm", jobId)
            .header("Authorization", "Bearer manager-token")
            .contentType(APPLICATION_JSON)
            .content("{\"rows\":[]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mockMvc.perform(delete("/api/profit-imports/{jobId}", jobId)
            .header("Authorization", "Bearer manager-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(auditRepository, times(3)).writePermissionDenied(
        any(AuthUser.class), any(String.class), any(String.class), any(String.class), any(), any(), any(String.class));
  }

  private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder previewRequest() {
    var request = multipart("/api/profit-imports/preview");
    request.file(file());
    request.param("storeId", "rg1");
    request.param("month", "2026-07");
    return request;
  }

  private MockMultipartFile file() {
    return new MockMultipartFile("file", "profit.csv", "text/csv", "营业收入\\n100".getBytes());
  }

  private ProfitImportRow row() {
    return new ProfitImportRow(
        "row-1", "rg1", "荆州之星店", "2026-07", new BigDecimal("0.95"),
        Map.of("sales", new BigDecimal("100")), List.of(), List.of(), false, "READY");
  }

  private BusinessException unauthorized() {
    return new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "默认企业", "user", "", role, role, storeId, true);
  }
}
