package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeBaseServiceTest {
  private final KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final LocalHashedVectorEmbeddingService vectors = new LocalHashedVectorEmbeddingService();
  private final KnowledgeBaseService service = new KnowledgeBaseService(
      repository, new KnowledgeDocumentParser(), vectors, accessControl,
      mock(OrganizationRepository.class), mock(AuditRepository.class));

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
}
