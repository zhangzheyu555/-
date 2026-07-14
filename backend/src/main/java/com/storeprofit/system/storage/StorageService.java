package com.storeprofit.system.storage;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {
  private static final Set<String> ALLOWED_WRITE_KEYS = Set.of(
      "stores",
      "entries",
      "salary",
      "expenses",
      "inspections",
      "logs",
      "schema_v",
      // 旧版页面（static/index.html）的功能数据键：数据分析上传、考试成绩、店铺盘存、培训覆盖
      "ana_uploads",
      "exam_records",
      "inv_overrides",
      "train_ov",
      "train_fruit_ov"
  );
  private static final Set<String> BLOCKED_WRITE_KEYS = Set.of(
      "accounts",
      "app_pin",
      "tokens",
      "passwords"
  );
  private static final Set<String> OWNER_ROLES = Set.of();
  private static final Set<String> ALL_INTERNAL_ROLES =
      Set.of("FINANCE", "SUPERVISOR", "STORE_MANAGER", "WAREHOUSE", "OPERATIONS");
  private static final Map<String, Set<String>> KEY_WRITE_ROLES = Map.ofEntries(
      Map.entry("stores", OWNER_ROLES),
      Map.entry("entries", Set.of("FINANCE")),
      Map.entry("salary", Set.of("FINANCE")),
      Map.entry("expenses", Set.of("FINANCE", "STORE_MANAGER")),
      Map.entry("inspections", Set.of("SUPERVISOR")),
      Map.entry("logs", ALL_INTERNAL_ROLES),
      Map.entry("schema_v", OWNER_ROLES),
      Map.entry("ana_uploads", Set.of("OPERATIONS")),
      // 考试是各角色（含店长带新员工）都会提交的
      Map.entry("exam_records", ALL_INTERNAL_ROLES),
      Map.entry("inv_overrides", Set.of("OPERATIONS", "STORE_MANAGER")),
      Map.entry("train_ov", Set.of("OPERATIONS")),
      Map.entry("train_fruit_ov", Set.of("OPERATIONS"))
  );

  private final JdbcTemplate jdbcTemplate;
  private final AccessControlService accessControl;

  public StorageService(JdbcTemplate jdbcTemplate, AccessControlService accessControl) {
    this.jdbcTemplate = jdbcTemplate;
    this.accessControl = accessControl;
  }

  public Optional<String> get(String key) {
    try {
      String value = jdbcTemplate.queryForObject(
          "select storage_value from kv_storage where storage_key = ?",
          String.class,
          key
      );
      return Optional.ofNullable(value);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<String> get(AuthUser user, String key) {
    accessControl.requireLegacyStorageAccess(user);
    String normalizedKey = normalizeKey(key);
    requireAllowedReadKey(normalizedKey);
    return get(normalizedKey);
  }

  @Transactional
  public void set(AuthUser user, String key, String value) {
    accessControl.requireLegacyStorageAccess(user);
    String normalizedKey = normalizeKey(key);
    requireAllowedKey(normalizedKey);
    requireRoleCanWrite(user, normalizedKey);
    jdbcTemplate.update("""
        insert into kv_storage(storage_key, storage_value, updated_at)
        values (?, ?, current_timestamp)
        on duplicate key update
          storage_value = values(storage_value),
          updated_at = current_timestamp
        """, normalizedKey, value);
    logLegacyWrite(user, normalizedKey);
  }

  @Transactional
  public StorageUploadResponse upload(
      AuthUser user,
      MultipartFile file,
      String businessType,
      String businessId,
      String storeId
  ) {
    String normalizedStoreId = normalizeStoreId(storeId);
    accessControl.requireAttachmentWrite(user, normalizedStoreId);
    requireStoreExists(user.tenantId(), normalizedStoreId);
    if (file == null || file.isEmpty()) {
      throw new BusinessException("EMPTY_UPLOAD_FILE", "请先选择要上传的附件", HttpStatus.BAD_REQUEST);
    }
    String normalizedBusinessType = normalizeBusinessType(businessType);
    String normalizedBusinessId = normalizeBusinessId(businessId);
    requireBusinessReference(user.tenantId(), normalizedStoreId, normalizedBusinessType, normalizedBusinessId);
    String fileName = normalizeFileName(file.getOriginalFilename());
    String contentType = file.getContentType() == null || file.getContentType().isBlank()
        ? "application/octet-stream"
        : file.getContentType().trim();
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException ex) {
      throw new BusinessException("UPLOAD_READ_FAILED", "附件读取失败，请重新上传", HttpStatus.BAD_REQUEST);
    }
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into warehouse_attachment(
            tenant_id, store_id, business_type, business_id, file_name, content_type,
            file_size, storage_path, content, uploaded_by, uploaded_at
          )
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """, new String[]{"id"}); // 显式只取 id：H2 会把所有 default 列都当 generated key 返回，getKey() 会炸
      statement.setLong(1, user.tenantId());
      statement.setString(2, normalizedStoreId);
      statement.setString(3, normalizedBusinessType);
      statement.setString(4, normalizedBusinessId);
      statement.setString(5, fileName);
      statement.setString(6, contentType);
      statement.setLong(7, content.length);
      statement.setString(8, "mysql://warehouse_attachment/" + normalizedBusinessType + "/" + normalizedBusinessId + "/" + fileName);
      statement.setBytes(9, content);
      statement.setLong(10, user.id());
      return statement;
    }, keyHolder);
    Long id = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    logUpload(user, normalizedStoreId, normalizedBusinessType, normalizedBusinessId, fileName);
    return new StorageUploadResponse(
        id,
        fileName,
        contentType,
        content.length,
        id == null ? "" : "/api/storage/attachments/" + id,
        "mysql://warehouse_attachment/" + normalizedBusinessType + "/" + normalizedBusinessId + "/" + fileName
    );
  }

  public Optional<AttachmentContent> attachment(AuthUser user, long id) {
    try {
      AttachmentMetadata attachment = jdbcTemplate.queryForObject("""
          select store_id, business_type, business_id, file_name, content_type, file_size, content, uploaded_by
          from warehouse_attachment
          where tenant_id = ? and id = ?
          """,
          (rs, rowNum) -> new AttachmentMetadata(
              rs.getString("store_id"),
              rs.getString("business_type"),
              rs.getString("business_id"),
              rs.getString("file_name"),
              rs.getString("content_type"),
              rs.getLong("file_size"),
              rs.getBytes("content"),
              rs.getObject("uploaded_by", Long.class)
          ),
          user.tenantId(),
          id
      );
      if (attachment == null) {
        return Optional.empty();
      }
      accessControl.requireAttachmentRead(user, attachment.storeId(), attachment.uploadedBy());
      requireInspectionDraftOwner(
          user, attachment.storeId(), attachment.businessType(), attachment.businessId(), attachment.uploadedBy());
      requireBusinessReference(user.tenantId(), attachment.storeId(), attachment.businessType(), attachment.businessId());
      return Optional.of(new AttachmentContent(
          attachment.fileName(), attachment.contentType(), attachment.fileSize(), attachment.content()));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  @Transactional
  public void rebindInspectionAttachments(
      AuthUser user,
      String storeId,
      String inspectionRecordId,
      Collection<Long> attachmentIds
  ) {
    if (attachmentIds == null || attachmentIds.isEmpty()) {
      return;
    }
    String normalizedStoreId = normalizeStoreId(storeId);
    String normalizedRecordId = normalizeBusinessId(inspectionRecordId);
    String draftBusinessId = inspectionDraftBusinessId(normalizedStoreId);
    accessControl.requireAttachmentWrite(user, normalizedStoreId);
    for (Long attachmentId : attachmentIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
      if (attachmentId <= 0) {
        throw new BusinessException("BAD_ATTACHMENT_ID", "巡检照片编号不正确", HttpStatus.BAD_REQUEST);
      }
      int updated = jdbcTemplate.update("""
          update warehouse_attachment
          set business_type = 'INSPECTION_RECORD', business_id = ?
          where tenant_id = ? and store_id = ? and id = ?
            and business_type = 'INSPECTION_RECORD'
            and (business_id = ? or (business_id = ? and uploaded_by = ?))
          """, normalizedRecordId, user.tenantId(), normalizedStoreId, attachmentId,
          normalizedRecordId, draftBusinessId, user.id());
      if (updated == 0) {
        throw new BusinessException(
            "ATTACHMENT_SCOPE_MISMATCH",
            "巡检照片不存在或不属于当前门店",
            HttpStatus.FORBIDDEN
        );
      }
    }
  }

  public Optional<InspectionAttachmentContent> inspectionAttachment(AuthUser user, long id) {
    return inspectionAttachment(user, id, null);
  }

  public Optional<InspectionAttachmentContent> inspectionAttachment(
      AuthUser user,
      long id,
      String expectedInspectionRecordId
  ) {
    try {
      InspectionAttachmentContent attachment = jdbcTemplate.queryForObject("""
          select id, store_id, business_type, business_id, file_name, content_type,
                 file_size, content, uploaded_by, uploaded_at
          from warehouse_attachment
          where tenant_id = ? and id = ?
          """,
          (rs, rowNum) -> new InspectionAttachmentContent(
              rs.getLong("id"),
              rs.getString("store_id"),
              rs.getString("business_type"),
              rs.getString("business_id"),
              rs.getString("file_name"),
              rs.getString("content_type"),
              rs.getLong("file_size"),
              rs.getBytes("content"),
              rs.getObject("uploaded_by", Long.class),
              rs.getTimestamp("uploaded_at") == null ? null : rs.getTimestamp("uploaded_at").toLocalDateTime()
          ),
          user.tenantId(),
          id
      );
      if (attachment == null || !"INSPECTION_RECORD".equals(attachment.businessType())) {
        return Optional.empty();
      }
      accessControl.requireAttachmentRead(user, attachment.storeId(), attachment.uploadedBy());
      requireInspectionDraftOwner(
          user, attachment.storeId(), attachment.businessType(), attachment.businessId(), attachment.uploadedBy());
      if (expectedInspectionRecordId != null
          && !expectedInspectionRecordId.trim().equals(attachment.businessId())) {
        return Optional.empty();
      }
      requireBusinessReference(
          user.tenantId(), attachment.storeId(), attachment.businessType(), attachment.businessId());
      return Optional.of(attachment);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public record AttachmentContent(String fileName, String contentType, long fileSize, byte[] content) {}

  public record InspectionAttachmentContent(
      long id,
      String storeId,
      String businessType,
      String businessId,
      String fileName,
      String contentType,
      long fileSize,
      byte[] content,
      Long uploadedBy,
      LocalDateTime uploadedAt
  ) {}

  private record AttachmentMetadata(
      String storeId,
      String businessType,
      String businessId,
      String fileName,
      String contentType,
      long fileSize,
      byte[] content,
      Long uploadedBy
  ) {}

  private void requireRoleCanWrite(AuthUser user, String key) {
    Set<String> roles = KEY_WRITE_ROLES.getOrDefault(key, Set.of());
    if (!AccessControlService.hasAnyRole(user, roles)) {
      throw new BusinessException("FORBIDDEN", "当前角色不能写入该 legacy KV 数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreExists(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        storeId
    );
    if (count == null || count == 0) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireBusinessReference(long tenantId, String storeId, String businessType, String businessId) {
    if (isInspectionDraftBusiness(storeId, businessType, businessId)) {
      return;
    }
    String sql = switch (businessType) {
      case "INSPECTION_RECORD" -> "select count(*) from inspection_record where tenant_id = ? and store_id = ? and id = ?";
      case "EXPENSE", "EXPENSE_CLAIM" -> "select count(*) from expense_claim where tenant_id = ? and store_id = ? and id = ?";
      case "WAREHOUSE_DELIVERY" -> "select count(*) from warehouse_delivery_order where tenant_id = ? and store_id = ? and id = ?";
      case "STORE_RECEIPT" -> "select count(*) from store_receipt where tenant_id = ? and store_id = ? and id = ?";
      case "WAREHOUSE_RETURN" -> "select count(*) from warehouse_return_order where tenant_id = ? and return_store_id = ? and id = ?";
      case "STORE_REQUISITION" -> "select count(*) from store_requisition where tenant_id = ? and store_id = ? and id = ?";
      default -> throw new BusinessException(
          "ATTACHMENT_BUSINESS_UNSUPPORTED",
          "附件必须绑定已支持的业务记录",
          HttpStatus.BAD_REQUEST
      );
    };
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tenantId, storeId, businessId);
    if (count == null || count == 0) {
      throw new BusinessException("ATTACHMENT_BUSINESS_NOT_FOUND", "附件关联的业务记录不存在或不属于该门店", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireAllowedKey(String key) {
    if (BLOCKED_WRITE_KEYS.contains(key)) {
      throw new BusinessException("LEGACY_STORAGE_KEY_BLOCKED", "该 legacy KV key 禁止写入", HttpStatus.FORBIDDEN);
    }
    if (!ALLOWED_WRITE_KEYS.contains(key)) {
      throw new BusinessException("LEGACY_STORAGE_KEY_NOT_ALLOWED", "该 legacy KV key 不在允许写入列表中", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireAllowedReadKey(String key) {
    if (BLOCKED_WRITE_KEYS.contains(key)) {
      throw new BusinessException(
          "LEGACY_STORAGE_SENSITIVE_KEY_BLOCKED",
          "认证账号、密码和 Token 数据禁止通过 legacy KV 接口读取",
          HttpStatus.FORBIDDEN
      );
    }
    if (!ALLOWED_WRITE_KEYS.contains(key)) {
      throw new BusinessException(
          "LEGACY_STORAGE_KEY_NOT_ALLOWED",
          "该 legacy KV key 不在允许读取列表中",
          HttpStatus.BAD_REQUEST
      );
    }
  }

  private String normalizeKey(String key) {
    String normalized = key == null ? "" : key.trim().toLowerCase();
    if (normalized.isBlank()) {
      throw new BusinessException("BAD_STORAGE_KEY", "key must not be blank", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String normalizeBusinessType(String value) {
    String normalized = value == null || value.isBlank() ? "GENERAL" : value.trim().toUpperCase();
    if (normalized.length() > 60) {
      throw new BusinessException("BAD_BUSINESS_TYPE", "附件业务类型过长", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String normalizeBusinessId(String value) {
    String normalized = value == null || value.isBlank() ? "draft" : value.trim();
    if (normalized.length() > 120) {
      throw new BusinessException("BAD_BUSINESS_ID", "附件业务编号过长", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String normalizeStoreId(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) {
      throw new BusinessException("BAD_ATTACHMENT_STORE", "上传附件时必须选择门店", HttpStatus.BAD_REQUEST);
    }
    if (normalized.length() > 64) {
      throw new BusinessException("BAD_ATTACHMENT_STORE", "门店编号不正确", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private boolean isInspectionDraftBusiness(String storeId, String businessType, String businessId) {
    return "INSPECTION_RECORD".equals(businessType)
        && inspectionDraftBusinessId(storeId).equals(businessId);
  }

  private String inspectionDraftBusinessId(String storeId) {
    return "inspection-" + storeId + "-draft";
  }

  private void requireInspectionDraftOwner(
      AuthUser user,
      String storeId,
      String businessType,
      String businessId,
      Long uploadedBy
  ) {
    if (isInspectionDraftBusiness(storeId, businessType, businessId)
        && !AccessControlService.isBoss(user)
        && !java.util.Objects.equals(uploadedBy, user.id())) {
      throw new BusinessException(
          "FORBIDDEN", "不能读取其他用户尚未保存的巡检照片", HttpStatus.FORBIDDEN);
    }
  }

  private String normalizeFileName(String value) {
    String normalized = value == null || value.isBlank() ? "attachment" : value.trim();
    normalized = normalized.replace("\\", "_").replace("/", "_");
    return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
  }

  private void logLegacyWrite(AuthUser user, String key) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, reason, created_at)
        values (?, ?, ?, 'legacy_storage_write', 'kv_storage', ?, 'legacy KV write via /api/storage', current_timestamp)
        """, user.tenantId(), user.id(), user.displayName(), key);
  }

  private void logUpload(AuthUser user, String storeId, String businessType, String businessId, String fileName) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, reason, created_at)
        values (?, ?, ?, 'attachment_upload', ?, ?, ?, ?, current_timestamp)
        """,
        user.tenantId(),
        user.id(),
        user.displayName(),
        businessType,
        businessId,
        storeId,
        fileName
    );
  }
}
