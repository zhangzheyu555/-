package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationRepository;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScopeRepository;
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeBaseAvailableDocumentsH2Test {

  @Test
  void employeeSeesOnlyPublishedDocumentsInTenantRoleAndStoreScopeOrderedByPublishTime() {
    Fixture fixture = fixture();
    insertDocument(fixture.jdbc(), 101L, 1L, "全企业资料", "TENANT", "PUBLISHED", "2026-07-24T10:00:00");
    insertDocument(fixture.jdbc(), 102L, 1L, "本店资料", "STORE", "PUBLISHED", "2026-07-24T11:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_store_scope(document_id, store_id) values (102, 'store-a')");
    insertDocument(fixture.jdbc(), 103L, 1L, "员工资料", "ROLE", "PUBLISHED", "2026-07-24T12:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_role_scope(document_id, role_code) values (103, 'EMPLOYEE')");
    insertDocument(fixture.jdbc(), 104L, 1L, "其他门店资料", "STORE", "PUBLISHED", "2026-07-24T13:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_store_scope(document_id, store_id) values (104, 'store-b')");
    insertDocument(fixture.jdbc(), 105L, 1L, "财务资料", "ROLE", "PUBLISHED", "2026-07-24T14:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_role_scope(document_id, role_code) values (105, 'FINANCE')");
    insertDocument(fixture.jdbc(), 106L, 1L, "未发布草稿", "TENANT", "DRAFT", null);
    insertDocument(fixture.jdbc(), 107L, 1L, "已下架资料", "TENANT", "ARCHIVED", "2026-07-24T15:00:00");
    insertDocument(fixture.jdbc(), 108L, 2L, "其他企业资料", "TENANT", "PUBLISHED", "2026-07-24T16:00:00");

    List<KnowledgeBaseAvailableDocumentResponse> available =
        fixture.service().availableDocuments(fixture.employee());

    assertThat(available)
        .extracting(KnowledgeBaseAvailableDocumentResponse::title)
        .containsExactly("员工资料", "本店资料", "全企业资料");
    assertThat(available).allSatisfy(document -> {
      assertThat(document.originalFileName()).endsWith(".txt");
      assertThat(document.fileSize()).isEqualTo(4L);
      assertThat(document.publishedAt()).isNotNull();
      assertThat(document.updatedAt()).isNotNull();
    });
  }

  @Test
  void availableDocumentSummaryExposesOnlySafeReadFields() {
    assertThat(Arrays.stream(KnowledgeBaseAvailableDocumentResponse.class.getRecordComponents())
        .map(component -> component.getName()))
        .containsExactly(
            "id",
            "title",
            "category",
            "originalFileName",
            "fileSize",
            "publishedAt",
            "updatedAt");
  }

  @Test
  void personalSearchDenyPreventsAvailableDocumentListing() {
    Fixture fixture = fixture();
    fixture.jdbc().update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 702, 'knowledge_base.search', 'DENY', current_timestamp)
        """);

    assertThatThrownBy(() -> fixture.service().availableDocuments(fixture.employee()))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("FORBIDDEN");

    assertThat(fixture.jdbc().queryForObject("""
        select count(*)
        from operation_log
        where tenant_id = 1
          and action = 'permission_denied'
          and target_id = 'knowledge_base.search'
        """, Integer.class)).isEqualTo(1);
  }

  @Test
  void crossStoreCrossTenantAndArchivedDownloadsAreForbidden() {
    Fixture fixture = fixture();
    insertDocument(fixture.jdbc(), 201L, 1L, "其他门店资料", "STORE", "PUBLISHED", "2026-07-24T13:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_store_scope(document_id, store_id) values (201, 'store-b')");
    insertDocument(fixture.jdbc(), 202L, 1L, "已下架资料", "TENANT", "ARCHIVED", "2026-07-24T14:00:00");
    insertChunk(fixture.jdbc(), 202L, "已下架资料专属关键词");
    insertDocument(fixture.jdbc(), 203L, 2L, "其他企业资料", "TENANT", "PUBLISHED", "2026-07-24T15:00:00");

    assertThat(fixture.service().search(fixture.employee(), "已下架资料专属关键词", 10)).isEmpty();
    for (long documentId : List.of(201L, 202L, 203L)) {
      assertThatThrownBy(() -> fixture.service().download(fixture.employee(), documentId))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> {
            BusinessException forbidden = (BusinessException) error;
            assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
            assertThat(forbidden.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
          });
    }
  }

  @Test
  void supervisorConfiguredStoreListConstrainsAvailableSearchAndDownload() {
    Fixture fixture = fixture();
    fixture.jdbc().update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, store_id,
          enabled, permission_version, created_at
        ) values (703, 1, 'knowledge-supervisor', 'hash', '知识库督导', 'SUPERVISOR',
          null, 1, 1, current_timestamp)
        """);
    fixture.jdbc().update("""
        insert into user_data_scope(
          tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at
        ) values (1, 703, 'STORE', 'STORE_LIST', '["store-a"]', current_timestamp)
        """);
    insertDocument(fixture.jdbc(), 301L, 1L, "督导范围内资料", "STORE", "PUBLISHED", "2026-07-24T13:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_store_scope(document_id, store_id) values (301, 'store-a')");
    insertChunk(fixture.jdbc(), 301L, "督导门店规章");
    insertDocument(fixture.jdbc(), 302L, 1L, "督导范围外资料", "STORE", "PUBLISHED", "2026-07-24T14:00:00");
    fixture.jdbc().update(
        "insert into knowledge_base_document_store_scope(document_id, store_id) values (302, 'store-b')");
    insertChunk(fixture.jdbc(), 302L, "督导门店规章");
    AuthUser supervisor = new AuthUser(
        703L, 1L, "测试企业", "knowledge-supervisor", "", "知识库督导", "SUPERVISOR", null, true);

    assertThat(fixture.service().availableDocuments(supervisor))
        .extracting(KnowledgeBaseAvailableDocumentResponse::title)
        .containsExactly("督导范围内资料");
    assertThat(fixture.service().search(supervisor, "督导门店规章", 10))
        .extracting(KnowledgeBaseSearchResultResponse::documentId)
        .containsExactly(301L);
    assertThat(fixture.service().download(supervisor, 301L).content())
        .containsExactly(1, 2, 3, 4);
    assertThatThrownBy(() -> fixture.service().download(supervisor, 302L))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        });
  }

  private Fixture fixture() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:knowledge_available_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("74")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, store_id,
          enabled, permission_version, created_at
        ) values (702, 1, 'knowledge-employee', 'hash', '知识库员工', 'EMPLOYEE',
          'store-a', 1, 1, current_timestamp)
        """);
    AuthRepository authRepository = new AuthRepository(jdbc);
    AuditRepository auditRepository = new AuditRepository(jdbc);
    AuthorizationService authorizationService =
        new AuthorizationService(new AuthorizationRepository(jdbc));
    DataScopeService dataScopeService = new DataScopeService(
        new DataScopeRepository(jdbc), authRepository, new ObjectMapper());
    AccessControlService accessControl = new AccessControlService(
        mock(AuthService.class),
        authRepository,
        auditRepository,
        authorizationService,
        dataScopeService);
    KnowledgeBaseService service = new KnowledgeBaseService(
        new KnowledgeBaseRepository(jdbc),
        new KnowledgeDocumentParser(),
        new LocalHashedVectorEmbeddingService(),
        accessControl,
        mock(OrganizationRepository.class),
        auditRepository);
    AuthUser employee = new AuthUser(
        702L, 1L, "测试企业", "knowledge-employee", "", "知识库员工", "EMPLOYEE", "store-a", true);
    return new Fixture(jdbc, service, employee);
  }

  private void insertDocument(
      JdbcTemplate jdbc,
      long id,
      long tenantId,
      String title,
      String visibility,
      String status,
      String publishedAt
  ) {
    LocalDateTime updatedAt = LocalDateTime.parse(
        publishedAt == null ? "2026-07-24T09:00:00" : publishedAt);
    jdbc.update("""
        insert into knowledge_base_document(
          id, tenant_id, title, category, original_file_name, content_type, file_size, file_sha256,
          source_content, visibility, status, parsed_char_count, chunk_count, created_by,
          created_at, updated_at, published_at
        ) values (?, ?, ?, '门店运营', ?, 'text/plain', 4, ?, ?, ?, ?, 4, 1, 701,
          timestamp '2026-07-24 08:00:00', ?, ?)
        """,
        id,
        tenantId,
        title,
        id + ".txt",
        String.format("%064d", id),
        new byte[] {1, 2, 3, 4},
        visibility,
        status,
        Timestamp.valueOf(updatedAt),
        publishedAt == null ? null : Timestamp.valueOf(LocalDateTime.parse(publishedAt)));
  }

  private void insertChunk(JdbcTemplate jdbc, long documentId, String content) {
    jdbc.update("""
        insert into knowledge_base_chunk(
          tenant_id, document_id, chunk_no, source_locator, content, content_hash,
          embedding_model, embedding_dimensions, embedding, created_at
        ) values (1, ?, 1, '正文', ?, ?, ?, ?, ?, current_timestamp)
        """,
        documentId,
        content,
        String.format("%064d", documentId),
        LocalHashedVectorEmbeddingService.MODEL,
        LocalHashedVectorEmbeddingService.DIMENSIONS,
        new LocalHashedVectorEmbeddingService().embed(content));
  }

  private record Fixture(JdbcTemplate jdbc, KnowledgeBaseService service, AuthUser employee) {}
}
