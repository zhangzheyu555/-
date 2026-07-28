package com.storeprofit.system.qmai;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for QMAI historical backfill batches and normalized daily snapshots. */
@Repository
public class QmaiSyncRepository {
  private final JdbcTemplate jdbcTemplate;

  public QmaiSyncRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long createBatch(long tenantId, String brand, String targetMonth, Long requestedBy,
      String requestedByName, int totalTasks) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into qmai_sync_batch(
            tenant_id, brand_code, target_month, status, requested_by, requested_by_name,
            total_tasks, completed_tasks, failed_tasks, daily_rows, product_rows)
          values (?, ?, ?, 'QUEUED', ?, ?, ?, 0, 0, 0, 0)
          """, new String[] {"id"});
      ps.setLong(1, tenantId);
      ps.setString(2, brand);
      ps.setString(3, targetMonth);
      if (requestedBy == null) {
        ps.setNull(4, java.sql.Types.BIGINT);
      } else {
        ps.setLong(4, requestedBy);
      }
      ps.setString(5, requestedByName);
      ps.setInt(6, totalTasks);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("创建企迈同步批次失败");
    }
    return key.longValue();
  }

  public void markRunning(long batchId) {
    jdbcTemplate.update("""
        update qmai_sync_batch
        set status = 'RUNNING', started_at = current_timestamp
        where id = ? and status = 'QUEUED'
        """, batchId);
  }

  public void markTaskSucceeded(long batchId, int productRows) {
    jdbcTemplate.update("""
        update qmai_sync_batch
        set completed_tasks = completed_tasks + 1,
            daily_rows = daily_rows + 1,
            product_rows = product_rows + ?
        where id = ? and status = 'RUNNING'
        """, productRows, batchId);
  }

  public void markTaskFailed(long batchId) {
    jdbcTemplate.update("""
        update qmai_sync_batch
        set completed_tasks = completed_tasks + 1,
            failed_tasks = failed_tasks + 1
        where id = ? and status = 'RUNNING'
        """, batchId);
  }

  public void finish(long batchId, String status, String errorSummary) {
    jdbcTemplate.update("""
        update qmai_sync_batch
        set status = ?, error_summary = ?, finished_at = current_timestamp
        where id = ?
        """, status, blankToNull(errorSummary), batchId);
  }

  public void failQueued(long batchId, String errorSummary) {
    jdbcTemplate.update("""
        update qmai_sync_batch
        set status = 'FAILED', error_summary = ?, finished_at = current_timestamp
        where id = ? and status in ('QUEUED', 'RUNNING')
        """, blankToNull(errorSummary), batchId);
  }

  public void failStaleActiveBatches(long tenantId, String brand) {
    jdbcTemplate.update("""
        update qmai_sync_batch
        set status = 'FAILED',
            error_summary = '上一次同步任务异常中断，已由新任务接管',
            finished_at = current_timestamp
        where tenant_id = ? and brand_code = ?
          and status in ('QUEUED', 'RUNNING')
        """, tenantId, brand);
  }

  public Optional<BatchRow> find(long tenantId, String brand, long batchId) {
    return jdbcTemplate.query(batchSelect() + """
        where tenant_id = ? and brand_code = ? and id = ?
        """, this::mapBatch, tenantId, brand, batchId).stream().findFirst();
  }

  public Optional<BatchRow> latest(long tenantId, String brand, String targetMonth) {
    return jdbcTemplate.query(batchSelect() + """
        where tenant_id = ? and brand_code = ? and target_month = ?
        order by id desc
        limit 1
        """, this::mapBatch, tenantId, brand, targetMonth).stream().findFirst();
  }

  public Optional<BatchRow> active(long tenantId, String brand, String targetMonth) {
    return jdbcTemplate.query(batchSelect() + """
        where tenant_id = ? and brand_code = ? and target_month = ?
          and status in ('QUEUED', 'RUNNING')
        order by id desc
        limit 1
        """, this::mapBatch, tenantId, brand, targetMonth).stream().findFirst();
  }

  /**
   * Atomically replaces one successfully fetched shop day.
   *
   * <p>Deletes happen only after the external call has completed. Any insert error rolls the
   * whole replacement back, preserving the previous successful day.
   */
  @Transactional
  public void replaceDay(long tenantId, String brand, long batchId,
      QmaiDailySalesSnapshot snapshot) {
    Object[] identity = {
        tenantId, brand, snapshot.qmaiShopId(), snapshot.businessDate()
    };
    jdbcTemplate.update("""
        delete from qmai_product_sales
        where tenant_id = ? and brand_code = ? and qmai_shop_id = ? and business_date = ?
        """, identity);
    jdbcTemplate.update("""
        delete from qmai_daily_sales
        where tenant_id = ? and brand_code = ? and qmai_shop_id = ? and business_date = ?
        """, identity);
    jdbcTemplate.update("""
        insert into qmai_daily_sales(
          tenant_id, brand_code, qmai_shop_id, store_id, business_date, source_row_count,
          receivable_amount, received_amount, cost_amount, refund_amount, sync_batch_id, synced_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, brand, snapshot.qmaiShopId(), snapshot.storeId(), snapshot.businessDate(),
        snapshot.sourceRowCount(), snapshot.receivableAmount(), snapshot.receivedAmount(),
        snapshot.costAmount(), snapshot.refundAmount(), batchId);
    for (QmaiDailySalesSnapshot.Product product : snapshot.products()) {
      jdbcTemplate.update("""
          insert into qmai_product_sales(
            tenant_id, brand_code, qmai_shop_id, store_id, business_date, product_key,
            product_id, sku_id, item_name, category_name, quantity, refund_quantity,
            receivable_amount, received_amount, cost_amount, refund_amount,
            sync_batch_id, synced_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """, tenantId, brand, snapshot.qmaiShopId(), snapshot.storeId(),
          snapshot.businessDate(), product.productKey(), blankToNull(product.productId()),
          blankToNull(product.skuId()), product.itemName(), blankToNull(product.categoryName()),
          product.quantity(), product.refundQuantity(), product.receivableAmount(),
          product.receivedAmount(), product.costAmount(), product.refundAmount(), batchId);
    }
  }

  public boolean storeExists(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from store_branch where tenant_id = ? and id = ?
        """, Integer.class, tenantId, storeId);
    return count != null && count > 0;
  }

  /**
   * Atomically obtains the cross-instance synchronization lease.
   *
   * <p>The insert and compare-and-set update are safe under concurrent callers. Only the caller
   * whose random token is stored may renew or release the lease.
   */
  @Transactional
  public boolean claimLease(long tenantId, String brand, String ownerToken, int leaseMinutes) {
    try {
      jdbcTemplate.update("""
          insert into qmai_operating_sync_lease(
            tenant_id, brand_code, owner_token, locked_until, updated_at)
          values (?, ?, null, null, current_timestamp)
          """, tenantId, brand);
    } catch (DuplicateKeyException ignored) {
      // A row per tenant + brand is expected after the first synchronization.
    }
    return jdbcTemplate.update("""
        update qmai_operating_sync_lease
        set owner_token = ?,
            locked_until = ?,
            updated_at = current_timestamp
        where tenant_id = ? and brand_code = ?
          and (owner_token is null or locked_until is null or locked_until < current_timestamp
               or owner_token = ?)
        """, ownerToken,
        databaseTimestampPlusMinutes(leaseMinutes),
        tenantId, brand, ownerToken) == 1;
  }

  public boolean renewLease(long tenantId, String brand, String ownerToken, int leaseMinutes) {
    return jdbcTemplate.update("""
        update qmai_operating_sync_lease
        set locked_until = ?, updated_at = current_timestamp
        where tenant_id = ? and brand_code = ? and owner_token = ?
        """, databaseTimestampPlusMinutes(leaseMinutes),
        tenantId, brand, ownerToken) == 1;
  }

  public void releaseLease(long tenantId, String brand, String ownerToken) {
    jdbcTemplate.update("""
        update qmai_operating_sync_lease
        set owner_token = null, locked_until = null, updated_at = current_timestamp
        where tenant_id = ? and brand_code = ? and owner_token = ?
        """, tenantId, brand, ownerToken);
  }

  /**
   * Returns only tenant-owned database configurations.
   *
   * <p>Global environment fallback credentials must never be fanned out to every active tenant.
   * An environment-only setup may still use manual backfill, but is intentionally excluded from
   * the unattended multi-tenant scheduler.
   */
  public List<SyncTarget> activeTargets() {
    return jdbcTemplate.query("""
        select distinct t.id as tenant_id, c.brand as brand_code
        from tenant t
        join qmai_platform_config c on c.tenant_id = t.id
        where t.status = 'ACTIVE'
          and c.open_id is not null and trim(c.open_id) <> ''
          and c.grant_code is not null and trim(c.grant_code) <> ''
          and c.open_key is not null and trim(c.open_key) <> ''
          and c.shops is not null and trim(c.shops) <> ''
        order by t.id, c.brand
        """, (rs, rowNum) -> new SyncTarget(
        rs.getLong("tenant_id"), rs.getString("brand_code")));
  }

  private String batchSelect() {
    return """
        select id, tenant_id, brand_code, target_month, status, requested_by,
               requested_by_name, total_tasks, completed_tasks, failed_tasks,
               daily_rows, product_rows, error_summary, created_at, started_at, finished_at
        from qmai_sync_batch
        """;
  }

  private BatchRow mapBatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    long actor = rs.getLong("requested_by");
    Long requestedBy = rs.wasNull() ? null : actor;
    return new BatchRow(
        rs.getLong("id"),
        rs.getLong("tenant_id"),
        rs.getString("brand_code"),
        rs.getString("target_month"),
        rs.getString("status"),
        requestedBy,
        rs.getString("requested_by_name"),
        rs.getInt("total_tasks"),
        rs.getInt("completed_tasks"),
        rs.getInt("failed_tasks"),
        rs.getInt("daily_rows"),
        rs.getInt("product_rows"),
        rs.getString("error_summary"),
        localDateTime(rs.getTimestamp("created_at")),
        localDateTime(rs.getTimestamp("started_at")),
        localDateTime(rs.getTimestamp("finished_at")));
  }

  private LocalDateTime localDateTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private Timestamp databaseTimestampPlusMinutes(int minutes) {
    Timestamp now = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class);
    if (now == null) {
      throw new IllegalStateException("无法读取数据库时间");
    }
    return Timestamp.valueOf(now.toLocalDateTime().plusMinutes(minutes));
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public record SyncTarget(long tenantId, String brand) {}

  public record BatchRow(
      long id,
      long tenantId,
      String brand,
      String targetMonth,
      String status,
      Long requestedBy,
      String requestedByName,
      int totalTasks,
      int completedTasks,
      int failedTasks,
      int dailyRows,
      int productRows,
      String errorSummary,
      LocalDateTime createdAt,
      LocalDateTime startedAt,
      LocalDateTime finishedAt
  ) {}
}
