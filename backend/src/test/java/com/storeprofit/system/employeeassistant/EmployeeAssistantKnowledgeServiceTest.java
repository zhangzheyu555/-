package com.storeprofit.system.employeeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmployeeAssistantKnowledgeServiceTest {
  @Test
  void bossPublishingCreatesImmutableVersionAndAuditRecord() {
    EmployeeAssistantKnowledgeRepository repository = mock(EmployeeAssistantKnowledgeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantKnowledgeRepository.KnowledgeRow draft = knowledge("DRAFT", 0);
    EmployeeAssistantKnowledgeRepository.KnowledgeRow published = knowledge("PUBLISHED", 1);
    when(repository.findKnowledge(1L, 9L)).thenReturn(Optional.of(draft), Optional.of(published));
    when(repository.publish(1L, 9L, 1, 1L)).thenReturn(1);
    EmployeeAssistantKnowledgeService service = new EmployeeAssistantKnowledgeService(repository, accessControl, auditRepository);

    EmployeeAssistantKnowledgeResponse result = service.publish(boss(), 9L);

    assertThat(result.status()).isEqualTo("PUBLISHED");
    assertThat(result.currentVersion()).isEqualTo(1);
    verify(accessControl).requireEmployeeAssistantKnowledgeManage(boss());
    verify(repository).insertVersion(eq(1L), eq(published), eq(1), eq("PUBLISH"), eq(1L));
    verify(auditRepository).writeLog(eq(boss()), any());
  }

  @Test
  void draftRejectsFinancialContactOrderAndAttachmentContentBeforePersistence() {
    EmployeeAssistantKnowledgeRepository repository = mock(EmployeeAssistantKnowledgeRepository.class);
    EmployeeAssistantKnowledgeService service = new EmployeeAssistantKnowledgeService(
        repository, mock(AccessControlService.class), mock(AuditRepository.class));

    BusinessException financial = catchThrowableOfType(() -> service.createDraft(boss(), draft("今日营收 1000 元")),
        BusinessException.class);
    BusinessException contact = catchThrowableOfType(() -> service.createDraft(boss(), draft("联系电话 13800138000")),
        BusinessException.class);
    BusinessException order = catchThrowableOfType(() -> service.createDraft(boss(), draft("订单号：A20260714001")),
        BusinessException.class);
    BusinessException attachment = catchThrowableOfType(() -> service.createDraft(boss(), draft("请见 /api/storage/attachments/42")),
        BusinessException.class);

    assertThat(financial.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(financial.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_KNOWLEDGE_FINANCE_BLOCKED");
    assertThat(contact.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_KNOWLEDGE_PRIVACY_BLOCKED");
    assertThat(order.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_KNOWLEDGE_PRIVACY_BLOCKED");
    assertThat(attachment.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_KNOWLEDGE_ATTACHMENT_BLOCKED");
    verify(repository, org.mockito.Mockito.never()).insertDraft(
        org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void historicalSensitiveDraftCannotBePublished() {
    EmployeeAssistantKnowledgeRepository repository = mock(EmployeeAssistantKnowledgeRepository.class);
    EmployeeAssistantKnowledgeRepository.KnowledgeRow unsafeDraft = new EmployeeAssistantKnowledgeRepository.KnowledgeRow(
        9L, 1L, "SERVICE", "订单处理", "订单,处理", "请查看订单号：A20260714001", "DRAFT", 0,
        1L, 1L, LocalDateTime.of(2026, 7, 14, 10, 0), LocalDateTime.of(2026, 7, 14, 10, 0));
    when(repository.findKnowledge(1L, 9L)).thenReturn(Optional.of(unsafeDraft));
    EmployeeAssistantKnowledgeService service = new EmployeeAssistantKnowledgeService(
        repository, mock(AccessControlService.class), mock(AuditRepository.class));

    BusinessException failure = catchThrowableOfType(() -> service.publish(boss(), 9L), BusinessException.class);

    assertThat(failure.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_KNOWLEDGE_PRIVACY_BLOCKED");
    verify(repository, never()).publish(eq(1L), eq(9L), org.mockito.ArgumentMatchers.anyInt(), eq(1L));
  }

  private EmployeeAssistantKnowledgeDraftRequest draft(String answer) {
    return new EmployeeAssistantKnowledgeDraftRequest("SERVICE", "漏发处理", "漏发,补发", answer);
  }

  private AuthUser boss() {
    return new AuthUser(1L, 1L, "测试租户", "boss", "hash", "老板", "BOSS", null, true, 1L);
  }

  private EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge(String status, int version) {
    LocalDateTime now = LocalDateTime.of(2026, 7, 14, 10, 0);
    return new EmployeeAssistantKnowledgeRepository.KnowledgeRow(9L, 1L, "SERVICE", "漏发吸管处理", "漏发,吸管",
        "先致歉并补发。", status, version, 1L, 1L, now, now);
  }
}
