package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DocumentVectorKnowledgeBaseMigrationTest {

  @Test
  void v74CreatesScopedDocumentTablesGrantsFormalRolesAndRevokesOldSessions() {
    JdbcDataSource dataSource = dataSource();
    migrateTo(dataSource, "73");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("insert into tenant(id, name) values (9, '知识库迁移租户')");
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, store_id,
          enabled, permission_version, created_at
        ) values (9901, 9, 'knowledge-boss', 'hash', '知识库老板', 'BOSS', null,
          1, 4, current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(token_hash, tenant_id, user_id, permission_version, expires_at, created_at)
        values ('knowledge-migration-token', 9, 9901, 4, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);

    var migrated = migrateTo(dataSource, "74");

    assertThat(migrated.success).isTrue();
    assertThat(jdbc.queryForObject(
        "select count(*) from permission_catalog where permission_code = 'knowledge_base.search'", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from permission_catalog where permission_code = 'knowledge_base.manage'", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForList("""
        select role_code from role_permission
        where tenant_id = 9 and permission_code = 'knowledge_base.search'
        order by role_code
        """, String.class)).containsExactly(
            "BOSS", "EMPLOYEE", "FINANCE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE");
    assertThat(jdbc.queryForList("""
        select role_code from role_permission
        where tenant_id = 9 and permission_code = 'knowledge_base.manage'
        order by role_code
        """, String.class)).containsExactly("BOSS", "SUPERVISOR");
    assertThat(jdbc.queryForObject(
        "select permission_version from auth_user where id = 9901", Long.class)).isEqualTo(5L);
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_token where user_id = 9901", Integer.class)).isZero();

    jdbc.update("""
        insert into knowledge_base_document(
          tenant_id, title, category, original_file_name, content_type, file_size, file_sha256,
          source_content, visibility, status, created_by
        ) values (9, '交接班规范', '门店运营', '交接班规范.txt', 'text/plain', 6,
          '0123456789012345678901234567890123456789012345678901234567890123',
          cast('示例正文' as binary), 'ROLE', 'DRAFT', 9901)
        """);
    Long documentId = jdbc.queryForObject(
        "select id from knowledge_base_document where tenant_id = 9 and title = '交接班规范'", Long.class);
    jdbc.update("insert into knowledge_base_document_role_scope(document_id, role_code) values (?, 'EMPLOYEE')",
        documentId);
    jdbc.update("""
        insert into knowledge_base_chunk(
          tenant_id, document_id, chunk_no, source_locator, content, content_hash,
          embedding_model, embedding_dimensions, embedding
        ) values (9, ?, 1, '正文', '示例正文',
          '0123456789012345678901234567890123456789012345678901234567890123',
          'LOCAL_CHAR_NGRAM_V1', 384, cast('vector' as binary))
        """, documentId);
    assertThat(jdbc.queryForObject(
        "select count(*) from knowledge_base_chunk where tenant_id = 9 and document_id = ?", Integer.class,
        documentId)).isEqualTo(1);
  }

  private JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:document-vector-knowledge-base-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private org.flywaydb.core.api.output.MigrateResult migrateTo(JdbcDataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(target)
        .load()
        .migrate();
  }
}
