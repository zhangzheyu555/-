package com.storeprofit.system.inspection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionRectificationControllerTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final InspectionRectificationService rectificationService = mock(InspectionRectificationService.class);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
          new InspectionRectificationController(accessControl, rectificationService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void unauthenticatedSubmitReturns401BeforeTheWorkflowServiceIsReached() throws Exception {
    when(accessControl.requireUser(null)).thenThrow(new BusinessException(
        "UNAUTHORIZED", "authentication required", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(post("/api/inspections/{recordId}/rectification", "inspection-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"done\",\"attachmentIds\":[81]}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verifyNoInteractions(rectificationService);
  }

  @Test
  void wrongRoleRejectionFromTheWorkflowServiceIsReturnedAs403() throws Exception {
    AuthUser finance = new AuthUser(
        12L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "role is not allowed to submit a rectification", HttpStatus.FORBIDDEN);
    when(accessControl.requireUser("Bearer finance-token")).thenReturn(finance);
    when(rectificationService.submit(eq(finance), eq("inspection-1"), any())).thenThrow(forbidden);

    mockMvc.perform(post("/api/inspections/{recordId}/rectification", "inspection-1")
            .header("Authorization", "Bearer finance-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"done\",\"attachmentIds\":[81]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(rectificationService).submit(eq(finance), eq("inspection-1"), any());
  }

  @Test
  void crossStoreRejectionIsReturnedAs403AndDoesNotBecomeASuccessfulSubmission() throws Exception {
    AuthUser manager = new AuthUser(
        8L, 1L, "default", "manager", "", "Manager", "STORE_MANAGER", "store-a", true);
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "inspection belongs to another store", HttpStatus.FORBIDDEN);
    when(accessControl.requireUser("Bearer manager-token")).thenReturn(manager);
    when(rectificationService.submit(eq(manager), eq("inspection-other-store"), any())).thenThrow(forbidden);

    mockMvc.perform(post("/api/inspections/{recordId}/rectification", "inspection-other-store")
            .header("Authorization", "Bearer manager-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"done\",\"attachmentIds\":[81]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(rectificationService).submit(eq(manager), eq("inspection-other-store"), any());
    verify(rectificationService, never()).review(eq(manager), eq("inspection-other-store"), any());
  }
}
