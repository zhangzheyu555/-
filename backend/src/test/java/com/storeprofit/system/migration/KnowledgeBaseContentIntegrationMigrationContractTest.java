package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class KnowledgeBaseContentIntegrationMigrationContractTest {

  @Test
  void v103AddsTopicsVersionsAndDocumentRelationshipsInOneMigration() throws IOException {
    ClassPathResource migration =
        new ClassPathResource("db/migration/V103__knowledge_base_content_integration.sql");

    assertThat(migration.exists()).isTrue();
    String sql = migration.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
    assertThat(sql).contains(
        "create table knowledge_base_topic",
        "add column topic_id",
        "add column version_no",
        "add column relation_type",
        "add column predecessor_document_id",
        "uk_knowledge_base_document_topic_version");
  }
}
