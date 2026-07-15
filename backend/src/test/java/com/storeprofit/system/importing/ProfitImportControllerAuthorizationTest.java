package com.storeprofit.system.importing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProfitImportControllerAuthorizationTest {
  private static final String COMMIT_JSON = """
      {"rows":[{
        "rowId":"row-1","storeId":"rg1","month":"2026-07","overwrite":false,
        "values":{"sales":100},"note":"月度汇总导入"
      }]}
      """;

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
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
      new ProfitImportController(authService, importService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .addFilters(new RequestIdFilter())
      .build();

  @BeforeEach
  void setUp() {
    when(authRepository.assignedStoreScope(anyLong(), anyLong())).thenReturn(List.of());
    when(organizationRepository.stores(anyLong(), any())).thenReturn(List.of(
        new StoreResponse("rg1", "RG1", "荆州之星店", 1L, "茄果", "荆州", "店长", "", "营业中", "")
    ));
  }

  @Test
  void unauthenticatedLegacyRecognizeAndCommitReturn401() throws Exception {
    when(authService.requireUser(null)).thenThrow(unauthorized());

    mockMvc.perform(multipart("/api/imports/profit/recognize")
            .file(file())
            .param("storeId", "rg1")
            .param("month", "2026-07"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mockMvc.perform(post("/api/imports/profit/commit")
            .contentType(APPLICATION_JSON)
            .content(COMMIT_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verifyNoInteractions(spreadsheetProfitParser, financeService, auditRepository);
  }

  @Test
  void storeManagerIsRejectedAndAuditedOnEveryLegacyImportEndpoint() throws Exception {
    AuthUser manager = user(11L, "STORE_MANAGER", "rg1");
    when(authService.requireUser("Bearer manager-token")).thenReturn(manager);

    mockMvc.perform(multipart("/api/imports/profit/recognize")
            .file(file())
            .param("storeId", "rg1")
            .param("month", "2026-07")
            .header("Authorization", "Bearer manager-token"))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mockMvc.perform(post("/api/imports/profit/commit")
            .header("Authorization", "Bearer manager-token")
            .contentType(APPLICATION_JSON)
            .content(COMMIT_JSON))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mockMvc.perform(post("/api/imports/profit/legacy-import/commit")
            .header("Authorization", "Bearer manager-token")
            .contentType(APPLICATION_JSON)
            .content(COMMIT_JSON))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(auditRepository, times(3)).writePermissionDenied(
        any(AuthUser.class), any(String.class), any(String.class), any(String.class), any(), any(String.class));
    verifyNoInteractions(spreadsheetProfitParser, financeService);
  }

  @Test
  void financeCanCommitMonthlySummaryThroughTheLegacyEndpoint() throws Exception {
    AuthUser finance = user(12L, "FINANCE", "rg1");
    when(authService.requireUser("Bearer finance-token")).thenReturn(finance);
    when(financeRepository.storeExists(1L, "rg1")).thenReturn(true);

    mockMvc.perform(post("/api/imports/profit/commit")
            .header("Authorization", "Bearer finance-token")
            .contentType(APPLICATION_JSON)
            .content(COMMIT_JSON))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.saved").value(1))
        .andExpect(jsonPath("$.data.rows[0].status").value("SAVED"));

    verify(financeService).save(any(AuthUser.class), any());
  }

  private MockMultipartFile file() {
    return new MockMultipartFile("file", "profit.csv", "text/csv", "营业收入\\n100".getBytes());
  }

  private BusinessException unauthorized() {
    return new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "默认企业", "user", "", role, role, storeId, true);
  }
}
