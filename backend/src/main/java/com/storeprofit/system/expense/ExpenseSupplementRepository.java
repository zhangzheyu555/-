package com.storeprofit.system.expense;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ExpenseSupplementRepository {
  private final JdbcTemplate jdbcTemplate;

  public ExpenseSupplementRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<ExpenseSupplementResponse> supplements(long tenantId, String expenseId) {
    return jdbcTemplate.query("""
        select id, note, submitted_by, submitted_by_name, submitted_at
        from expense_supplement
        where tenant_id = ? and expense_id = ?
        order by submitted_at desc, id desc
        """,
        (rs, rowNum) -> {
          long supplementId = rs.getLong("id");
          return new ExpenseSupplementResponse(
              supplementId,
              rs.getString("note"),
              rs.getObject("submitted_by", Long.class),
              rs.getString("submitted_by_name"),
              dateTime(rs.getTimestamp("submitted_at")),
              attachments(tenantId, expenseId, supplementId)
          );
        },
        tenantId,
        expenseId
    );
  }

  public long insertSupplement(long tenantId, String expenseId, String note, long userId, String userName) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into expense_supplement(
            tenant_id, expense_id, note, submitted_by, submitted_by_name, submitted_at
          ) values (?, ?, ?, ?, ?, current_timestamp)
          """, new String[]{"id"});
      statement.setLong(1, tenantId);
      statement.setString(2, expenseId);
      statement.setString(3, note);
      statement.setLong(4, userId);
      statement.setString(5, userName);
      return statement;
    }, keyHolder);
    if (keyHolder.getKey() == null) {
      throw new IllegalStateException("Failed to create expense supplement");
    }
    return keyHolder.getKey().longValue();
  }

  public void insertAttachment(
      long tenantId,
      long supplementId,
      String expenseId,
      String fileName,
      String contentType,
      long fileSize,
      String storageKey,
      long uploadedBy
  ) {
    jdbcTemplate.update("""
        insert into expense_supplement_attachment(
          tenant_id, supplement_id, expense_id, file_name, content_type,
          file_size, storage_key, uploaded_by, uploaded_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        supplementId,
        expenseId,
        fileName,
        contentType,
        fileSize,
        storageKey,
        uploadedBy
    );
  }

  public Optional<AttachmentMetadata> attachment(long tenantId, String expenseId, long attachmentId) {
    try {
      AttachmentMetadata value = jdbcTemplate.queryForObject("""
          select esa.id, esa.expense_id, esa.file_name, esa.content_type, esa.file_size,
                 esa.storage_key, esa.uploaded_by, esa.uploaded_at, ec.store_id, ec.month
          from expense_supplement_attachment esa
          join expense_claim ec
            on ec.tenant_id = esa.tenant_id and ec.id = esa.expense_id
          where esa.tenant_id = ? and esa.expense_id = ? and esa.id = ?
          """,
          (rs, rowNum) -> new AttachmentMetadata(
              rs.getLong("id"),
              rs.getString("expense_id"),
              rs.getString("store_id"),
              rs.getString("file_name"),
              rs.getString("content_type"),
              rs.getLong("file_size"),
              rs.getString("storage_key"),
              rs.getObject("uploaded_by", Long.class),
              dateTime(rs.getTimestamp("uploaded_at")),
              rs.getString("month")
          ),
          tenantId,
          expenseId,
          attachmentId
      );
      return Optional.ofNullable(value);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public boolean hasSupplements(long tenantId, String expenseId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from expense_supplement where tenant_id = ? and expense_id = ?",
        Integer.class,
        tenantId,
        expenseId
    );
    return count != null && count > 0;
  }

  private List<ExpenseSupplementAttachmentResponse> attachments(long tenantId, String expenseId, long supplementId) {
    return jdbcTemplate.query("""
        select id, file_name, content_type, file_size, uploaded_by, uploaded_at
        from expense_supplement_attachment
        where tenant_id = ? and expense_id = ? and supplement_id = ?
        order by uploaded_at, id
        """,
        (rs, rowNum) -> {
          long attachmentId = rs.getLong("id");
          String baseUrl = "/api/expenses/" + expenseId + "/supplements/attachments/" + attachmentId;
          String contentUrl = "/api/expenses/" + expenseId + "/attachments/" + attachmentId + "/content";
          return new ExpenseSupplementAttachmentResponse(
              attachmentId,
              rs.getString("file_name"),
              rs.getString("content_type"),
              rs.getLong("file_size"),
              rs.getObject("uploaded_by", Long.class),
              dateTime(rs.getTimestamp("uploaded_at")),
              contentUrl,
              baseUrl + "/download"
          );
        },
        tenantId,
        expenseId,
        supplementId
    );
  }

  private static LocalDateTime dateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  public record AttachmentMetadata(
      long id,
      String expenseId,
      String storeId,
      String fileName,
      String contentType,
      long fileSize,
      String storageKey,
      Long uploadedBy,
      LocalDateTime uploadedAt,
      String month
  ) {
  }
}
