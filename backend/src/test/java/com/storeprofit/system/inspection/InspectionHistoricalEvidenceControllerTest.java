package com.storeprofit.system.inspection;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionHistoricalEvidenceControllerTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final InspectionService service = mock(InspectionService.class);
  private final InspectionController controller = new InspectionController(accessControl, service);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void unauthenticatedCandidateRequestReturns401BeforeTheHistoricalServiceIsReached() throws Exception {
    BusinessException unauthenticated = new BusinessException(
        "UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    when(accessControl.requireUser(null)).thenThrow(unauthenticated);

    mockMvc.perform(get("/api/inspections/{id}/evidence/attachments", "history-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(service, never()).historicalEvidenceCandidates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void existingRecordWithNoCandidateImageReturns200AndAnEmptyList() throws Exception {
    AuthUser supervisor = supervisor();
    when(accessControl.requireUser("Bearer supervisor-token")).thenReturn(supervisor);
    when(service.historicalEvidenceCandidates(supervisor, "history-empty")).thenReturn(
        new InspectionEvidenceCandidatesResponse("history-empty", "s1", List.of()));

    mockMvc.perform(get("/api/inspections/{id}/evidence/attachments", "history-empty")
            .header("Authorization", "Bearer supervisor-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.recordId").value("history-empty"))
        .andExpect(jsonPath("$.data.candidates.length()").value(0));

    verify(accessControl).requireInspectionManage(supervisor);
    verify(service).historicalEvidenceCandidates(supervisor, "history-empty");
  }

  @Test
  void missingInspectionManagePermissionReturns403BeforeTheHistoricalServiceIsReached() throws Exception {
    AuthUser manager = new AuthUser(
        8L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "s1", true);
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "无权处理巡检数据", HttpStatus.FORBIDDEN);
    when(accessControl.requireUser("Bearer manager-token")).thenReturn(manager);
    doThrow(forbidden).when(accessControl).requireInspectionManage(manager);

    mockMvc.perform(get("/api/inspections/{id}/evidence/attachments", "history-1")
            .header("Authorization", "Bearer manager-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(service, never()).historicalEvidenceCandidates(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void crossStoreRejectionFromTheHistoricalServiceIsReturnedAs403() throws Exception {
    AuthUser supervisor = supervisor();
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "门店不在当前账号的数据范围内", HttpStatus.FORBIDDEN);
    when(accessControl.requireUser("Bearer scoped-supervisor-token")).thenReturn(supervisor);
    doThrow(forbidden).when(service).historicalEvidenceCandidates(supervisor, "history-other-store");

    mockMvc.perform(get("/api/inspections/{id}/evidence/attachments", "history-other-store")
            .header("Authorization", "Bearer scoped-supervisor-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(accessControl).requireInspectionManage(supervisor);
    verify(service).historicalEvidenceCandidates(supervisor, "history-other-store");
  }

  private AuthUser supervisor() {
    return new AuthUser(
        7L, 1L, "default", "supervisor", "", "督导", "SUPERVISOR", null, true);
  }
}
