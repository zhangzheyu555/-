package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
        new KnowledgeBaseRepository.SearchChunkRow(
            10L, 100L, "交接班", 1, "ORIGINAL", "员工交接班", "门店运营", "ROLE",
            "正文", "交接班卫生检查完成登记", "employee-hash", employeeVector),
        new KnowledgeBaseRepository.SearchChunkRow(
            11L, 101L, "财务交接班", 1, "ORIGINAL", "财务交接班", "财务", "ROLE",
            "正文", "交接班卫生检查完成登记", "finance-hash", financeVector)
    ));
    when(repository.roleScopes(10L)).thenReturn(List.of("EMPLOYEE"));
    when(repository.roleScopes(11L)).thenReturn(List.of("FINANCE"));
    when(repository.storeScopes(10L)).thenReturn(List.of());
    when(repository.storeScopes(11L)).thenReturn(List.of());

    List<KnowledgeBaseSearchResultResponse> results = service.search(employee, "交接班卫生检查", 5);

    assertThat(results).extracting(KnowledgeBaseSearchResultResponse::documentId).containsExactly(10L);
  }

  @Test
  void searchSummaryGroupsByTopicAndKeepsLatestVersionOfRepeatedContent() {
    AuthUser employee = new AuthUser(9L, 1L, "测试企业", "employee", "", "员工", "EMPLOYEE", "store-a", true);
    doNothing().when(accessControl).requireKnowledgeBaseSearch(employee);
    byte[] repeatedVector = vectors.embed("闭店后关闭燃气并登记");
    byte[] supplementVector = vectors.embed("值班经理复核门窗和电源");
    when(repository.publishedChunks(1L)).thenReturn(List.of(
        new KnowledgeBaseRepository.SearchChunkRow(
            20L, 200L, "门店闭店流程", 1, "ORIGINAL", "闭店流程旧版", "门店运营", "TENANT",
            "正文", "闭店后关闭燃气并登记", "same-hash", repeatedVector),
        new KnowledgeBaseRepository.SearchChunkRow(
            21L, 200L, "门店闭店流程", 2, "SUPPLEMENTS", "闭店流程补充版", "门店运营", "TENANT",
            "正文", "闭店后关闭燃气并登记", "same-hash", repeatedVector),
        new KnowledgeBaseRepository.SearchChunkRow(
            22L, 200L, "门店闭店流程", 3, "SUPPLEMENTS", "闭店复核要求", "门店运营", "TENANT",
            "正文", "值班经理复核门窗和电源", "supplement-hash", supplementVector)
    ));

    KnowledgeBaseSearchResponse response = service.searchWithSummary(employee, "闭店燃气门窗", 5);

    assertThat(response.topics()).hasSize(1);
    assertThat(response.topics().getFirst().topicName()).isEqualTo("门店闭店流程");
    assertThat(response.topics().getFirst().sources())
        .extracting(KnowledgeBaseSearchResultResponse::documentId)
        .containsExactly(21L, 22L);
    assertThat(response.summary()).contains("闭店后关闭燃气并登记", "值班经理复核门窗和电源");
  }

  @Test
  void publishingReplacementArchivesPublishedPredecessor() {
    AuthUser boss = new AuthUser(1L, 1L, "测试企业", "boss", "", "老板", "BOSS", null, true);
    KnowledgeBaseRepository.DocumentRow draft = document(
        31L, 300L, "闭店流程", 2, "REPLACES", 30L, "DRAFT");
    KnowledgeBaseRepository.DocumentRow published = document(
        31L, 300L, "闭店流程", 2, "REPLACES", 30L, "PUBLISHED");
    when(repository.findDocument(1L, 31L)).thenReturn(java.util.Optional.of(draft), java.util.Optional.of(published));
    when(repository.findDocument(1L, 30L)).thenReturn(java.util.Optional.of(
        document(30L, 300L, "闭店流程", 1, "ORIGINAL", null, "PUBLISHED")));
    when(repository.publish(1L, 31L, 1L)).thenReturn(1);
    when(repository.archivePublishedPredecessor(1L, 30L)).thenReturn(1);

    KnowledgeBaseDocumentResponse response = service.publish(boss, 31L);

    assertThat(response.status()).isEqualTo("PUBLISHED");
    verify(repository).archivePublishedPredecessor(1L, 30L);
  }

  @Test
  void supervisorCannotUploadVersionLinkedToDocumentOutsideManagedStores() {
    AuthUser supervisor = new AuthUser(
        2L, 1L, "测试企业", "supervisor", "", "督导", "SUPERVISOR", "store-a", true);
    KnowledgeBaseRepository.TopicRow topic = new KnowledgeBaseRepository.TopicRow(
        300L, 1L, "闭店流程", "闭店流程", 1L,
        LocalDateTime.of(2026, 7, 24, 9, 0), LocalDateTime.of(2026, 7, 24, 9, 0));
    when(organizationRepository.store(1L, "store-a")).thenReturn(Optional.of(mock(StoreResponse.class)));
    when(accessControl.canAccessStore(supervisor, DataScopeDomains.STORE, "store-a")).thenReturn(true);
    when(accessControl.canAccessStore(supervisor, DataScopeDomains.STORE, "store-b")).thenReturn(false);
    when(repository.findTopic(1L, 300L)).thenReturn(Optional.of(topic));
    when(repository.nextVersionNo(1L, 300L)).thenReturn(2);
    when(repository.findDocument(1L, 30L)).thenReturn(Optional.of(
        document(30L, 300L, "闭店流程", 1, "ORIGINAL", null, "PUBLISHED", "STORE")));
    when(repository.storeScopes(30L)).thenReturn(List.of("store-b"));

    MockMultipartFile file = new MockMultipartFile(
        "file", "闭店流程.txt", "text/plain", "闭店后关闭燃气并登记".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.upload(
        supervisor, file, "闭店流程补充", "门店运营", "STORE",
        List.of(), List.of("store-a"), 300L, null, "REPLACES", 30L))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    verify(repository, never()).insertDocument(eq(1L), any());
  }

  @Test
  void supervisorCannotPublishReplacementForDocumentOutsideManagedStores() {
    AuthUser supervisor = new AuthUser(
        2L, 1L, "测试企业", "supervisor", "", "督导", "SUPERVISOR", "store-a", true);
    KnowledgeBaseRepository.DocumentRow draft = document(
        31L, 300L, "闭店流程", 2, "REPLACES", 30L, "DRAFT", "STORE");
    KnowledgeBaseRepository.DocumentRow predecessor = document(
        30L, 300L, "闭店流程", 1, "ORIGINAL", null, "PUBLISHED", "STORE");
    when(repository.findDocument(1L, 31L)).thenReturn(Optional.of(draft));
    when(repository.findDocument(1L, 30L)).thenReturn(Optional.of(predecessor));
    when(repository.storeScopes(31L)).thenReturn(List.of("store-a"));
    when(repository.storeScopes(30L)).thenReturn(List.of("store-b"));
    when(accessControl.canAccessStore(supervisor, DataScopeDomains.STORE, "store-a")).thenReturn(true);
    when(accessControl.canAccessStore(supervisor, DataScopeDomains.STORE, "store-b")).thenReturn(false);

    assertThatThrownBy(() -> service.publish(supervisor, 31L))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    verify(repository, never()).publish(1L, 31L, 2L);
    verify(repository, never()).archivePublishedPredecessor(1L, 30L);
  }

  @Test
  void supervisorCannotManageAStoreDocumentOutsideTheConfiguredKnowledgeScope() {
    AuthUser supervisor = new AuthUser(
        10L, 1L, "测试企业", "supervisor", "", "督导", "SUPERVISOR", null, true);
    doNothing().when(accessControl).requireKnowledgeBaseManage(supervisor);
    when(repository.listDocuments(1L)).thenReturn(List.of(
        new KnowledgeBaseRepository.DocumentRow(
            20L, 1L, 0L, "越权门店资料", 1, "ORIGINAL", null, "越权门店资料", "门店运营",
            "outside.txt", "text/plain", 12L, "a".repeat(64), "STORE", "DRAFT", 12, 1, 10L, null,
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
        null, null, null, null,
        false
    )).isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  private KnowledgeBaseRepository.DocumentRow document(
      long id,
      long topicId,
      String topicName,
      int versionNo,
      String relationType,
      Long predecessorDocumentId,
      String status
  ) {
    return document(id, topicId, topicName, versionNo, relationType, predecessorDocumentId, status, "TENANT");
  }

  private KnowledgeBaseRepository.DocumentRow document(
      long id,
      long topicId,
      String topicName,
      int versionNo,
      String relationType,
      Long predecessorDocumentId,
      String status,
      String visibility
  ) {
    java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 7, 24, 10, 0);
    return new KnowledgeBaseRepository.DocumentRow(
        id, 1L, topicId, topicName, versionNo, relationType, predecessorDocumentId,
        "闭店流程", "门店运营", "闭店流程.txt", "text/plain", 10L, "hash",
        visibility, status, 10, 1, 1L, "PUBLISHED".equals(status) ? 1L : null,
        now, now, "PUBLISHED".equals(status) ? now : null);
  }
}
