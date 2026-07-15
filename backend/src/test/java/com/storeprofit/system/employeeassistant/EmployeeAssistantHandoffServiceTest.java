package com.storeprofit.system.employeeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class EmployeeAssistantHandoffServiceTest {
  @Test
  void createsHandoffWithOnlySanitizedQuestionAndWritesAuditLog() {
    EmployeeAssistantKnowledgeRepository repository = mock(EmployeeAssistantKnowledgeRepository.class);
    EmployeeAssistantService assistant = mock(EmployeeAssistantService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    when(assistant.sanitizeForHandoff("顾客电话13800138000需要协助")).thenReturn("顾客电话***需要协助");
    when(assistant.handoffCategory("顾客电话***需要协助")).thenReturn("GENERAL");
    when(repository.findHandoff(eq(1L), anyString())).thenAnswer(invocation ->
        Optional.of(handoff(invocation.getArgument(1), "s1", null)));
    EmployeeAssistantHandoffService service = new EmployeeAssistantHandoffService(repository, assistant, accessControl,
        auditRepository);

    EmployeeAssistantHandoffResponse result = service.create(employee(),
        new EmployeeAssistantHandoffCreateRequest("顾客电话13800138000需要协助", null));

    assertThat(result.question()).isEqualTo("顾客电话***需要协助");
    assertThat(result.category()).isEqualTo("GENERAL");
    ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
    verify(repository).insertHandoff(eq(1L), anyString(), eq("s1"), question.capture(), eq("GENERAL"), eq(8L), any());
    assertThat(question.getValue()).doesNotContain("13800138000");
    verify(accessControl).requireStoreAccess(employee(), DataScopeDomains.STORE, "s1", "创建员工助手人工事项");
    ArgumentCaptor<AuditLogRequest> audit = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(eq(employee()), audit.capture());
    assertThat(audit.getValue().action()).isEqualTo("employee_assistant.handoff_create");
  }

  @Test
  void rejectsManagerOutsideStoreScopeAndAuditsDeniedAccess() {
    EmployeeAssistantKnowledgeRepository repository = mock(EmployeeAssistantKnowledgeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantHandoffService service = new EmployeeAssistantHandoffService(repository,
        mock(EmployeeAssistantService.class), accessControl, auditRepository);
    AuthUser operator = new AuthUser(21L, 1L, "测试租户", "operator", "hash", "运营", "OPERATIONS", "s2", true, 1L);
    when(repository.findHandoff(1L, "EA-HO-1")).thenReturn(Optional.of(handoff("EA-HO-1", "s1", null)));
    when(accessControl.canAccessStore(operator, DataScopeDomains.STORE, "s1")).thenReturn(false);

    BusinessException error = catchThrowableOfType(() -> service.claim(operator, "EA-HO-1"), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verify(auditRepository).writePermissionDenied(eq(operator), eq("领取员工助手人工事项"),
        eq("employee_assistant_handoff"), eq("EA-HO-1"), eq("s1"), anyString());
  }

  private AuthUser employee() {
    return new AuthUser(8L, 1L, "测试租户", "employee", "hash", "员工", "STORE_MANAGER", "s1", true, 1L);
  }

  private EmployeeAssistantKnowledgeRepository.HandoffRow handoff(String id, String storeId, Long handledBy) {
    LocalDateTime now = LocalDateTime.of(2026, 7, 14, 10, 0);
    return new EmployeeAssistantKnowledgeRepository.HandoffRow(id, 1L, storeId, "顾客电话***需要协助", "GENERAL",
        "OPEN", 8L, "员工", handledBy, null, null, now, null, null, null, now.plusDays(3));
  }
}
