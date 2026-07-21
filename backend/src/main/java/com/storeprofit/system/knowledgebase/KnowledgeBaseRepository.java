package com.storeprofit.system.knowledgebase;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Tenant-scoped persistence. Original documents and derived vectors are both retained in MySQL. */
@Repository
public class KnowledgeBaseRepository {
  private final JdbcTemplate jdbcTemplate;

  public KnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long insertDocument(long tenantId, DocumentInsert insert) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into knowledge_base_document(
            tenant_id, title, category, original_file_name, content_type, file_size, file_sha256,
            source_content, visibility, status, parsed_char_count, chunk_count, created_by,
            created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, current_timestamp, current_timestamp)
          """, Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, tenantId);
      statement.setString(2, insert.title());
      statement.setString(3, insert.category());
      statement.setString(4, insert.fileName());
      statement.setString(5, insert.contentType());
      statement.setLong(6, insert.sourceContent().length);
      statement.setString(7, insert.sha256());
      statement.setBytes(8, insert.sourceContent());
      statement.setString(9, insert.visibility());
      statement.setInt(10, insert.parsedCharCount());
      statement.setInt(11, insert.chunkCount());
      statement.setLong(12, insert.createdBy());
      return statement;
    }, keys);
    if (keys.getKey() == null) throw new IllegalStateException("知识库资料未生成编号");
    return keys.getKey().longValue();
  }

  public void insertRoleScopes(long documentId, Collection<String> roles) {
    for (String role : roles) {
      jdbcTemplate.update("insert into knowledge_base_document_role_scope(document_id, role_code) values (?, ?)",
          documentId, role);
    }
  }

  public void insertStoreScopes(long documentId, Collection<String> storeIds) {
    for (String storeId : storeIds) {
      jdbcTemplate.update("insert into knowledge_base_document_store_scope(document_id, store_id) values (?, ?)",
          documentId, storeId);
    }
  }

  public void insertChunks(long tenantId, long documentId, List<ChunkInsert> chunks) {
    for (int index = 0; index < chunks.size(); index++) {
      ChunkInsert chunk = chunks.get(index);
      jdbcTemplate.update("""
          insert into knowledge_base_chunk(
            tenant_id, document_id, chunk_no, source_locator, content, content_hash,
            embedding_model, embedding_dimensions, embedding, created_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """, tenantId, documentId, index + 1, chunk.sourceLocator(), chunk.content(), chunk.contentHash(),
          LocalHashedVectorEmbeddingService.MODEL, LocalHashedVectorEmbeddingService.DIMENSIONS, chunk.embedding());
    }
  }

  public List<DocumentRow> listDocuments(long tenantId) {
    return jdbcTemplate.query(documentSelect() + " where tenant_id = ? order by updated_at desc, id desc limit 500",
        this::mapDocument, tenantId);
  }

  public Optional<DocumentRow> findDocument(long tenantId, long id) {
    return jdbcTemplate.query(documentSelect() + " where tenant_id = ? and id = ?", this::mapDocument, tenantId, id)
        .stream().findFirst();
  }

  public Optional<DocumentContentRow> findDocumentContent(long tenantId, long id) {
    return jdbcTemplate.query("""
        select id, tenant_id, title, category, original_file_name, content_type, file_size, file_sha256,
               visibility, status, parsed_char_count, chunk_count, created_by, published_by,
               created_at, updated_at, published_at, source_content
        from knowledge_base_document
        where tenant_id = ? and id = ?
        """, (rs, rowNum) -> new DocumentContentRow(mapDocument(rs, rowNum), rs.getBytes("source_content")), tenantId, id)
        .stream().findFirst();
  }

  public List<String> roleScopes(long documentId) {
    return jdbcTemplate.queryForList("""
        select role_code from knowledge_base_document_role_scope
        where document_id = ? order by role_code
        """, String.class, documentId);
  }

  public List<String> storeScopes(long documentId) {
    return jdbcTemplate.queryForList("""
        select store_id from knowledge_base_document_store_scope
        where document_id = ? order by store_id
        """, String.class, documentId);
  }

  public int publish(long tenantId, long id, long actorId) {
    return jdbcTemplate.update("""
        update knowledge_base_document
        set status = 'PUBLISHED', published_by = ?, published_at = current_timestamp, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'DRAFT'
        """, actorId, tenantId, id);
  }

  public int archive(long tenantId, long id) {
    return jdbcTemplate.update("""
        update knowledge_base_document
        set status = 'ARCHIVED', updated_at = current_timestamp
        where tenant_id = ? and id = ? and status in ('DRAFT', 'PUBLISHED')
        """, tenantId, id);
  }

  public List<SearchChunkRow> publishedChunks(long tenantId) {
    return jdbcTemplate.query("""
        select c.document_id, c.source_locator, c.content, c.embedding,
               d.title, d.category, d.visibility
        from knowledge_base_chunk c
        join knowledge_base_document d on d.id = c.document_id and d.tenant_id = c.tenant_id
        where c.tenant_id = ? and d.status = 'PUBLISHED'
        order by c.document_id, c.chunk_no
        limit 10000
        """, (rs, rowNum) -> new SearchChunkRow(
            rs.getLong("document_id"), rs.getString("title"), rs.getString("category"),
            rs.getString("visibility"), rs.getString("source_locator"), rs.getString("content"),
            rs.getBytes("embedding")), tenantId);
  }

  private String documentSelect() {
    return """
        select id, tenant_id, title, category, original_file_name, content_type, file_size, file_sha256,
               visibility, status, parsed_char_count, chunk_count, created_by, published_by,
               created_at, updated_at, published_at
        from knowledge_base_document
        """;
  }

  private DocumentRow mapDocument(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    long publishedByValue = rs.getLong("published_by");
    Long publishedBy = rs.wasNull() ? null : publishedByValue;
    return new DocumentRow(
        rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("title"), rs.getString("category"),
        rs.getString("original_file_name"), rs.getString("content_type"), rs.getLong("file_size"),
        rs.getString("file_sha256"), rs.getString("visibility"), rs.getString("status"),
        rs.getInt("parsed_char_count"), rs.getInt("chunk_count"), rs.getLong("created_by"), publishedBy,
        dateTime(rs, "created_at"), dateTime(rs, "updated_at"), dateTime(rs, "published_at"));
  }

  private LocalDateTime dateTime(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
    java.sql.Timestamp value = rs.getTimestamp(field);
    return value == null ? null : value.toLocalDateTime();
  }

  public record DocumentInsert(
      String title,
      String category,
      String fileName,
      String contentType,
      String sha256,
      byte[] sourceContent,
      String visibility,
      int parsedCharCount,
      int chunkCount,
      long createdBy
  ) {}

  public record ChunkInsert(String sourceLocator, String content, String contentHash, byte[] embedding) {}

  public record DocumentRow(
      long id,
      long tenantId,
      String title,
      String category,
      String originalFileName,
      String contentType,
      long fileSize,
      String fileSha256,
      String visibility,
      String status,
      int parsedCharCount,
      int chunkCount,
      long createdBy,
      Long publishedBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime publishedAt
  ) {}

  public record DocumentContentRow(DocumentRow document, byte[] sourceContent) {}

  public record SearchChunkRow(
      long documentId,
      String title,
      String category,
      String visibility,
      String sourceLocator,
      String content,
      byte[] embedding
  ) {}
}
