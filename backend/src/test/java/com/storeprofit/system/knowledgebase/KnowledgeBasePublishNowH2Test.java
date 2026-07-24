package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class KnowledgeBasePublishNowH2Test {

  @Test
  void uploadWithoutPublishNowRemainsBackwardCompatibleDraft() {
    JdbcDataSource dataSource = dataSource();
    Fixture fixture = fixture(dataSource, new AuditRepository(new JdbcTemplate(dataSource)));

    KnowledgeBaseDocumentResponse response = fixture.service().upload(
        fixture.boss(),
        textFile("草稿知识.txt", "草稿上传后必须等待明确发布"),
        "草稿知识",
        "门店运营",
        "TENANT",
        List.of(),
        List.of()
    );

    assertThat(response.status()).isEqualTo("DRAFT");
    assertThat(response.publishedAt()).isNull();
    assertThat(fixture.jdbc().queryForList("""
        select action
        from operation_log
        where target_type = 'knowledge_base_document' and target_id = ?
        order by id
        """, String.class, Long.toString(response.id())))
        .containsExactly("knowledge_base.document_upload");
  }

  @Test
  void uploadWithPublishNowPersistsPublishedDocumentAndBothAuditEvents() {
    JdbcDataSource dataSource = dataSource();
    Fixture fixture = fixture(dataSource, new AuditRepository(new JdbcTemplate(dataSource)));

    KnowledgeBaseDocumentResponse response = fixture.service().upload(
        fixture.boss(),
        textFile("发布知识.txt", "开店前检查消防通道和食品保质期"),
        "开店检查",
        "门店运营",
        "TENANT",
        List.of(),
        List.of(),
        true
    );

    assertThat(response.status()).isEqualTo("PUBLISHED");
    assertThat(response.publishedAt()).isNotNull();
    assertThat(fixture.jdbc().queryForObject(
        "select status from knowledge_base_document where id = ?",
        String.class,
        response.id())).isEqualTo("PUBLISHED");
    assertThat(fixture.jdbc().queryForList("""
        select action
        from operation_log
        where target_type = 'knowledge_base_document' and target_id = ?
        order by id
        """, String.class, Long.toString(response.id()))).containsExactly(
            "knowledge_base.document_upload",
            "knowledge_base.document_publish");
  }

  @Test
  void publishAuditFailureRollsBackUploadedDocumentIndexAndAuditEvents() {
    JdbcDataSource dataSource = dataSource();
    Fixture fixture = fixture(dataSource, new FailingPublishAuditRepository(new JdbcTemplate(dataSource)));

    assertThatThrownBy(() -> fixture.service().upload(
        fixture.boss(),
        textFile("回滚知识.txt", "发布阶段失败时不能留下半成品"),
        "回滚知识",
        "门店运营",
        "TENANT",
        List.of(),
        List.of(),
        true
    )).isInstanceOf(IllegalStateException.class)
        .hasMessage("模拟发布审计失败");

    assertThat(fixture.jdbc().queryForObject(
        "select count(*) from knowledge_base_document", Integer.class)).isZero();
    assertThat(fixture.jdbc().queryForObject(
        "select count(*) from knowledge_base_chunk", Integer.class)).isZero();
    assertThat(fixture.jdbc().queryForObject(
        "select count(*) from operation_log where target_type = 'knowledge_base_document'", Integer.class)).isZero();
  }

  private Fixture fixture(JdbcDataSource dataSource, AuditRepository auditRepository) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("74")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AccessControlService accessControl = mock(AccessControlService.class);
    AuthUser boss = new AuthUser(
        701L, 1L, "测试企业", "knowledge-boss", "", "知识库老板", "BOSS", null, true);
    doNothing().when(accessControl).requireKnowledgeBaseManage(boss);
    doNothing().when(accessControl).requireKnowledgeBaseTenantWideManage(boss);
    KnowledgeBaseService target = new KnowledgeBaseService(
        new KnowledgeBaseRepository(jdbc),
        new KnowledgeDocumentParser(),
        new LocalHashedVectorEmbeddingService(),
        accessControl,
        mock(OrganizationRepository.class),
        auditRepository);
    ProxyFactory proxyFactory = new ProxyFactory(target);
    TransactionInterceptor transactionInterceptor = new TransactionInterceptor();
    transactionInterceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
    transactionInterceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    proxyFactory.addAdvice(transactionInterceptor);
    return new Fixture(jdbc, (KnowledgeBaseService) proxyFactory.getProxy(), boss);
  }

  private JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:knowledge_publish_now_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private MockMultipartFile textFile(String name, String content) {
    return new MockMultipartFile(
        "file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(JdbcTemplate jdbc, KnowledgeBaseService service, AuthUser boss) {}

  private static final class FailingPublishAuditRepository extends AuditRepository {
    private FailingPublishAuditRepository(JdbcTemplate jdbcTemplate) {
      super(jdbcTemplate);
    }

    @Override
    public void writeLog(AuthUser user, AuditLogRequest request) {
      if ("knowledge_base.document_publish".equals(request.action())) {
        throw new IllegalStateException("模拟发布审计失败");
      }
      super.writeLog(user, request);
    }
  }
}
