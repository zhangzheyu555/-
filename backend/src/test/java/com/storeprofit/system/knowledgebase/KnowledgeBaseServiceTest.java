package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeBaseServiceTest {
  private final KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
  private final LocalHashedVectorEmbeddingService vectors = new LocalHashedVectorEmbeddingService();
  private final KnowledgeBaseService service = new KnowledgeBaseService(
      repository, new KnowledgeDocumentParser(), vectors, accessControl,
      organizationRepository, mock(AuditRepository.class));

  @Test
  void searchNeverReturnsRoleRestrictedDocumentToAnotherRole() {
    AuthUser employee = new AuthUser(9L, 1L, "测试企业", "employee", "", "员工", "EMPLOYEE", "store-a", true);
    doNothing().when(accessControl).requireKnowledgeBaseSearch(employee);
    byte[] employeeVector = vectors.embed("交接班卫生检查完成登记");
    byte[] financeVector = vectors.embed("交接班卫生检查完成登记");
    when(repository.publishedChunks(1L)).thenReturn(List.of(
        new KnowledgeBaseRepository.SearchChunkRow(10L, "员工交接班", "门店运营", "ROLE", "正文", "交接班卫生检查完成登记", employeeVector),
        new KnowledgeBaseRepository.SearchChunkRow(11L, "财务交接班", "财务", "ROLE", "正文", "交接班卫生检查完成登记", financeVector)
    ));
    when(repository.roleScopes(10L)).thenReturn(List.of("EMPLOYEE"));
    when(repository.roleScopes(11L)).thenReturn(List.of("FINANCE"));
    when(repository.storeScopes(10L)).thenReturn(List.of());
    when(repository.storeScopes(11L)).thenReturn(List.of());

    List<KnowledgeBaseSearchResultResponse> results = service.search(employee, "交接班卫生检查", 5);

    assertThat(results).extracting(KnowledgeBaseSearchResultResponse::documentId).containsExactly(10L);
  }

  @Test
  void supervisorCannotManageAStoreDocumentOutsideTheConfiguredKnowledgeScope() {
    AuthUser supervisor = new AuthUser(
        10L, 1L, "测试企业", "supervisor", "", "督导", "SUPERVISOR", null, true);
    doNothing().when(accessControl).requireKnowledgeBaseManage(supervisor);
    when(repository.listDocuments(1L)).thenReturn(List.of(
        new KnowledgeBaseRepository.DocumentRow(
            20L, 1L, "越权门店资料", "门店运营", "outside.txt", "text/plain",
            12L, "a".repeat(64), "STORE", "DRAFT", 12, 1, 10L, null,
            java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null)
    ));
    when(repository.roleScopes(20L)).thenReturn(List.of());
    when(repository.storeScopes(20L)).thenReturn(List.of("outside-store"));
    when(accessControl.canManageKnowledgeBaseStore(supervisor, "outside-store")).thenReturn(false);

    assertThat(service.listDocuments(supervisor)).isEmpty();
  }

  @Test
  void crossTenantStoreScopeIsRejectedAsForbidden() {
    AuthUser boss = new AuthUser(
        1L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    doNothing().when(accessControl).requireKnowledgeBaseManage(boss);
    when(organizationRepository.store(1L, "foreign-store")).thenReturn(Optional.empty());
    when(repository.storeExistsOutsideTenant(1L, "foreign-store")).thenReturn(true);
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN))
        .when(accessControl).rejectKnowledgeBaseCrossTenantStore(boss, "foreign-store");

    assertThatThrownBy(() -> service.upload(
        boss,
        new MockMultipartFile(
            "file", "foreign.txt", "text/plain",
            "跨租户门店不能作为资料范围".getBytes(StandardCharsets.UTF_8)),
        "跨租户范围",
        "门店运营",
        "STORE",
        List.of(),
        List.of("foreign-store"),
        false
    )).isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }
}
