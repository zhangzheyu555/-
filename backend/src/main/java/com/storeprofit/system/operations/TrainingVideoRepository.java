package com.storeprofit.system.operations;

import com.storeprofit.system.operations.TrainingVideoModels.ViewerProgressRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingVideoRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final JdbcTemplate jdbcTemplate;

  public TrainingVideoRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public record VideoRow(
      long id, String videoCode, long attachmentId, Long courseId, String courseTitle,
      String title, String category, String description, String fileName, String contentType,
      long fileSize, BigDecimal durationSeconds, boolean enabled, int sortOrder, String createdAt
  ) {
  }

  public record ProgressRow(
      long videoId, BigDecimal watchedSeconds, BigDecimal durationSeconds, BigDecimal percent,
      BigDecimal lastPosition, boolean completed, Timestamp lastReportedAt
  ) {
  }

  public List<VideoRow> videos(long tenantId, boolean includeDisabled) {
    String enabledClause = includeDisabled ? "" : " and v.enabled = 1";
    return jdbcTemplate.query("""
        select v.id, v.video_code, v.attachment_id, v.course_id, c.title as course_title,
               v.title, v.category, v.description, a.file_name, a.content_type, a.file_size,
               v.duration_seconds, v.enabled, v.sort_order,
               date_format(v.created_at, '%Y-%m-%d %H:%i:%s') as created_at
        from training_video v
        join warehouse_attachment a
          on a.tenant_id = v.tenant_id and a.id = v.attachment_id
         and a.business_type = 'TRAINING_VIDEO'
        left join training_course c on c.tenant_id = v.tenant_id and c.id = v.course_id
        where v.tenant_id = ?
        """ + enabledClause + "\norder by v.sort_order, v.id", this::mapVideo, tenantId);
  }

  public Optional<VideoRow> video(long tenantId, long videoId) {
    return jdbcTemplate.query("""
        select v.id, v.video_code, v.attachment_id, v.course_id, c.title as course_title,
               v.title, v.category, v.description, a.file_name, a.content_type, a.file_size,
               v.duration_seconds, v.enabled, v.sort_order,
               date_format(v.created_at, '%Y-%m-%d %H:%i:%s') as created_at
        from training_video v
        join warehouse_attachment a
          on a.tenant_id = v.tenant_id and a.id = v.attachment_id
         and a.business_type = 'TRAINING_VIDEO'
        left join training_course c on c.tenant_id = v.tenant_id and c.id = v.course_id
        where v.tenant_id = ? and v.id = ?
        """, this::mapVideo, tenantId, videoId).stream().findFirst();
  }

  public Optional<Long> tenantForVideo(long videoId) {
    return jdbcTemplate.query(
        "select tenant_id from training_video where id = ?",
        (rs, rowNum) -> rs.getLong("tenant_id"), videoId).stream().findFirst();
  }

  public long insertVideo(
      long tenantId, String videoCode, long attachmentId, Long courseId, String title,
      String category, String description, int sortOrder, long userId
  ) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_video(
            tenant_id, video_code, attachment_id, course_id, title, category, description,
            enabled, sort_order, created_by, created_at
          ) values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, current_timestamp)
          """, new String[]{"id"});
      ps.setLong(1, tenantId);
      ps.setString(2, videoCode);
      ps.setLong(3, attachmentId);
      if (courseId == null) ps.setObject(4, null); else ps.setLong(4, courseId);
      ps.setString(5, title);
      ps.setString(6, category);
      ps.setString(7, description);
      ps.setInt(8, sortOrder);
      ps.setLong(9, userId);
      return ps;
    }, keys);
    Number key = keys.getKey();
    return key == null ? 0L : key.longValue();
  }

  public boolean updateVideoDuration(long tenantId, long videoId, BigDecimal durationSeconds) {
    return jdbcTemplate.update("""
        update training_video set duration_seconds = ?
        where tenant_id = ? and id = ? and duration_seconds is null
        """, durationSeconds, tenantId, videoId) > 0;
  }

  public boolean deleteVideo(long tenantId, long videoId) {
    jdbcTemplate.update(
        "delete from training_video_progress where tenant_id = ? and video_id = ?", tenantId, videoId);
    return jdbcTemplate.update(
        "delete from training_video where tenant_id = ? and id = ?", tenantId, videoId) > 0;
  }

  public List<ProgressRow> myProgress(long tenantId, long userId) {
    return jdbcTemplate.query("""
        select video_id, watched_seconds, duration_seconds, progress_percent, last_position,
               completed, last_reported_at
        from training_video_progress
        where tenant_id = ? and user_id = ?
        """, this::mapProgress, tenantId, userId);
  }

  public void ensureProgressRow(long tenantId, long videoId, long userId, String userName, String storeId) {
    try {
      jdbcTemplate.update("""
          insert into training_video_progress(
            tenant_id, video_id, user_id, user_name, store_id, watched_seconds, progress_percent,
            last_position, completed, created_at
          ) values (?, ?, ?, ?, ?, 0, 0, 0, 0, current_timestamp)
          """, tenantId, videoId, userId, userName, storeId);
    } catch (DuplicateKeyException ignored) {
      // Concurrent and repeated reports reuse the same tenant/video/user row.
    }
  }

  public Optional<ProgressRow> progressForUpdate(long tenantId, long videoId, long userId) {
    return jdbcTemplate.query("""
        select video_id, watched_seconds, duration_seconds, progress_percent, last_position,
               completed, last_reported_at
        from training_video_progress
        where tenant_id = ? and video_id = ? and user_id = ?
        for update
        """, this::mapProgress, tenantId, videoId, userId).stream().findFirst();
  }

  public void updateProgress(
      long tenantId, long videoId, long userId, BigDecimal watchedSeconds,
      BigDecimal durationSeconds, BigDecimal percent, BigDecimal lastPosition, boolean completed
  ) {
    jdbcTemplate.update("""
        update training_video_progress
        set watched_seconds = ?, duration_seconds = ?, progress_percent = ?, last_position = ?,
            completed = ?,
            completed_at = case when ? = 1 and completed_at is null then current_timestamp else completed_at end,
            last_reported_at = current_timestamp
        where tenant_id = ? and video_id = ? and user_id = ?
        """, watchedSeconds, durationSeconds, percent, lastPosition, completed,
        completed ? 1 : 0, tenantId, videoId, userId);
  }

  public List<ViewerProgressRow> progressReport(long tenantId) {
    return jdbcTemplate.query("""
        select p.user_id, p.user_name, p.store_id, s.name as store_name,
               p.video_id, v.title as video_title, v.category as video_category,
               p.watched_seconds, p.progress_percent, p.completed,
               date_format(p.last_reported_at, '%Y-%m-%d %H:%i:%s') as last_watched_at
        from training_video_progress p
        join training_video v on v.tenant_id = p.tenant_id and v.id = p.video_id
        left join store_branch s on s.tenant_id = p.tenant_id and s.id = p.store_id
        where p.tenant_id = ? and v.enabled = 1
        order by p.user_name, v.sort_order, v.id
        """, (rs, rowNum) -> new ViewerProgressRow(
        rs.getLong("user_id"), rs.getString("user_name"), rs.getString("store_id"),
        rs.getString("store_name"), rs.getLong("video_id"), rs.getString("video_title"),
        rs.getString("video_category"), amount(rs.getBigDecimal("watched_seconds")),
        amount(rs.getBigDecimal("progress_percent")), rs.getBoolean("completed"),
        rs.getString("last_watched_at")), tenantId);
  }

  private VideoRow mapVideo(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    long courseId = rs.getLong("course_id");
    boolean hasCourse = !rs.wasNull();
    return new VideoRow(
        rs.getLong("id"), rs.getString("video_code"), rs.getLong("attachment_id"),
        hasCourse ? courseId : null, rs.getString("course_title"), rs.getString("title"),
        rs.getString("category"), rs.getString("description"), rs.getString("file_name"),
        rs.getString("content_type"), rs.getLong("file_size"), rs.getBigDecimal("duration_seconds"),
        rs.getBoolean("enabled"), rs.getInt("sort_order"), rs.getString("created_at"));
  }

  private ProgressRow mapProgress(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new ProgressRow(
        rs.getLong("video_id"), amount(rs.getBigDecimal("watched_seconds")),
        rs.getBigDecimal("duration_seconds"), amount(rs.getBigDecimal("progress_percent")),
        amount(rs.getBigDecimal("last_position")), rs.getBoolean("completed"),
        rs.getTimestamp("last_reported_at"));
  }

  private static BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }
}
