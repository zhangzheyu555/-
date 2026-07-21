package com.storeprofit.system.knowledgebase;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Document-oriented knowledge base with local vector retrieval and tenant/role/store isolation.
 * Original documents never leave this process; the embedding is generated locally and persisted
 * only as a derived index in MySQL.
 */
@Service
public class KnowledgeBaseService {
  private static final Set<String> VISIBILITIES = Set.of("TENANT", "ROLE", "STORE");
  private static final Set<String> FORMAL_ROLES = Set.of(
      "BOSS", "FINANCE", "SUPERVISOR", "WAREHOUSE", "STORE_MANAGER", "EMPLOYEE");
  private static final double MINIMUM_SCORE = 0.10d;

  private final KnowledgeBaseRepository repository;
  private final KnowledgeDocumentParser parser;
  private final KnowledgeDocumentChunker chunker = new KnowledgeDocumentChunker();
  private final LocalHashedVectorEmbeddingService embeddingService;
  private final AccessControlService accessControl;
  private final OrganizationRepository organizationRepository;
  private final AuditRepository auditRepository;

  public KnowledgeBaseService(
      KnowledgeBaseRepository repository,
      KnowledgeDocumentParser parser,
      LocalHashedVectorEmbeddingService embeddingService,
      AccessControlService accessControl,
      OrganizationRepository organizationRepository,
      AuditRepository auditRepository
  ) {
    this.repository = repository;
    this.parser = parser;
    this.embeddingService = embeddingService;
    this.accessControl = accessControl;
    this.organizationRepository = organizationRepository;
    this.auditRepository = auditRepository;
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseDocumentResponse> listDocuments(AuthUser user) {
    accessControl.requireKnowledgeBaseManage(user);
    return repository.listDocuments(user.tenantId()).stream()
        .filter(document -> canManageDocument(user, document, scope(document)))
        .map(this::response)
        .toList();
  }

  @Transactional
  public KnowledgeBaseDocumentResponse upload(
      AuthUser user,
      MultipartFile file,
      String title,
      String category,
      String visibility,
      List<String> roleScopes,
      List<String> storeScopes
  ) {
    accessControl.requireKnowledgeBaseManage(user);
    KnowledgeDocumentParser.ParsedDocument parsed = parser.parse(file);
    Scope normalizedScope = normalizeScope(user, visibility, roleScopes, storeScopes);
    String normalizedTitle = title(title, parsed.fileName());
    String normalizedCategory = required(category, "请选择资料分类", 64).toUpperCase(Locale.ROOT);
    List<KnowledgeDocumentChunker.ChunkDraft> chunkDrafts = chunker.split(parsed.sections());
    int parsedChars = parsed.sections().stream().mapToInt(section -> section.text().length()).sum();
    ArrayList<KnowledgeBaseRepository.ChunkInsert> chunks = new ArrayList<>();
    for (KnowledgeDocumentChunker.ChunkDraft chunk : chunkDrafts) {
      chunks.add(new KnowledgeBaseRepository.ChunkInsert(
          chunk.sourceLocator(), chunk.content(), sha256(chunk.content().getBytes(StandardCharsets.UTF_8)),
          embeddingService.embed(chunk.content())));
    }
    try {
      long id = repository.insertDocument(user.tenantId(), new KnowledgeBaseRepository.DocumentInsert(
          normalizedTitle, normalizedCategory, parsed.fileName(), parsed.contentType(), sha256(parsed.sourceContent()),
          parsed.sourceContent(), normalizedScope.visibility(), parsedChars, chunks.size(), user.id()));
      repository.insertRoleScopes(id, normalizedScope.roles());
      repository.insertStoreScopes(id, normalizedScope.stores());
      repository.insertChunks(user.tenantId(), id, List.copyOf(chunks));
      KnowledgeBaseRepository.DocumentRow saved = requiredDocument(user, id);
      audit(user, "knowledge_base.document_upload", saved, "已上传并完成本地向量索引，共" + chunks.size() + "段");
      return response(saved);
    } catch (DataIntegrityViolationException ex) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_DUPLICATE", "相同文件已存在，请不要重复上传", HttpStatus.CONFLICT);
    }
  }

  @Transactional
  public KnowledgeBaseDocumentResponse publish(AuthUser user, long id) {
    accessControl.requireKnowledgeBaseManage(user);
    KnowledgeBaseRepository.DocumentRow document = requiredDocument(user, id);
    Scope scope = scope(document);
    requireManageDocument(user, document, scope);
    if (!"DRAFT".equals(document.status())) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_NOT_DRAFT", "仅草稿资料可以发布", HttpStatus.CONFLICT);
    }
    if (document.chunkCount() <= 0) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_EMPTY_DOCUMENT", "资料没有可检索内容，不能发布");
    }
    if (repository.publish(user.tenantId(), id, user.id()) == 0) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_CONFLICT", "资料状态已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    KnowledgeBaseRepository.DocumentRow published = requiredDocument(user, id);
    audit(user, "knowledge_base.document_publish", published, "已发布知识库资料");
    return response(published);
  }

  @Transactional
  public KnowledgeBaseDocumentResponse archive(AuthUser user, long id) {
    accessControl.requireKnowledgeBaseManage(user);
    KnowledgeBaseRepository.DocumentRow document = requiredDocument(user, id);
    requireManageDocument(user, document, scope(document));
    if (repository.archive(user.tenantId(), id) == 0) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_CONFLICT", "资料状态已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    KnowledgeBaseRepository.DocumentRow archived = requiredDocument(user, id);
    audit(user, "knowledge_base.document_archive", archived, "已下架知识库资料");
    return response(archived);
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseSearchResultResponse> search(AuthUser user, String query, int limit) {
    accessControl.requireKnowledgeBaseSearch(user);
    String normalizedQuery = required(query, "请输入至少两个字符的检索内容", 300);
    if (normalizedQuery.codePointCount(0, normalizedQuery.length()) < 2) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_QUERY_TOO_SHORT", "检索内容至少需要两个字符");
    }
    int boundedLimit = Math.max(1, Math.min(10, limit));
    byte[] queryEmbedding = embeddingService.embed(normalizedQuery);
    Map<Long, Scope> scopes = new HashMap<>();
    return repository.publishedChunks(user.tenantId()).stream()
        .filter(chunk -> visibleTo(user, chunk.documentId(), chunk.visibility(), scopes))
        .map(chunk -> new ScoredChunk(chunk, embeddingService.cosine(queryEmbedding, chunk.embedding())))
        .filter(item -> item.score() >= MINIMUM_SCORE)
        .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
            .thenComparing(item -> item.chunk().documentId()))
        .limit(boundedLimit)
        .map(item -> new KnowledgeBaseSearchResultResponse(
            item.chunk().documentId(), item.chunk().title(), item.chunk().category(), item.chunk().sourceLocator(),
            excerpt(item.chunk().content()), rounded(item.score())))
        .toList();
  }

  // A successful source-file download is auditable, so this cannot use a read-only JDBC transaction.
  @Transactional
  public DownloadedDocument download(AuthUser user, long id) {
    KnowledgeBaseRepository.DocumentContentRow content = repository.findDocumentContent(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_DOCUMENT_NOT_FOUND", "知识库资料不存在", HttpStatus.NOT_FOUND));
    KnowledgeBaseRepository.DocumentRow document = content.document();
    Scope scope = scope(document);
    boolean manageable = accessControl.hasPermission(user, "knowledge_base.manage") && canManageDocument(user, document, scope);
    boolean readable = "PUBLISHED".equals(document.status()) && visibleTo(user, document.id(), document.visibility(), Map.of(document.id(), scope));
    accessControl.requireKnowledgeBaseDocumentRead(user, manageable || readable, id);
    audit(user, "knowledge_base.document_download", document, "已下载知识库原始资料");
    return new DownloadedDocument(document.originalFileName(), document.contentType(), document.fileSize(), content.sourceContent());
  }

  private Scope normalizeScope(AuthUser user, String visibility, List<String> roleScopes, List<String> storeScopes) {
    String normalizedVisibility = required(visibility, "请选择资料适用范围", 16).toUpperCase(Locale.ROOT);
    if (!VISIBILITIES.contains(normalizedVisibility)) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_VISIBILITY_INVALID", "资料适用范围不正确");
    }
    List<String> roles = normalizeRoles(roleScopes);
    List<String> stores = normalizeStores(storeScopes);
    if ("TENANT".equals(normalizedVisibility)) {
      accessControl.requireKnowledgeBaseTenantWideManage(user);
      return new Scope(normalizedVisibility, List.of(), List.of());
    }
    if ("ROLE".equals(normalizedVisibility)) {
      accessControl.requireKnowledgeBaseTenantWideManage(user);
      if (roles.isEmpty()) {
        throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_ROLE_SCOPE_REQUIRED", "按角色发布时至少选择一个角色");
      }
      return new Scope(normalizedVisibility, roles, List.of());
    }
    if (stores.isEmpty()) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_STORE_SCOPE_REQUIRED", "按门店发布时至少选择一个门店");
    }
    for (String storeId : stores) {
      if (organizationRepository.store(user.tenantId(), storeId).isEmpty()) {
        throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_STORE_NOT_FOUND", "指定门店不存在或不属于当前企业");
      }
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, storeId, "设置知识库资料门店范围");
    }
    return new Scope(normalizedVisibility, List.of(), stores);
  }

  private void requireManageDocument(AuthUser user, KnowledgeBaseRepository.DocumentRow document, Scope scope) {
    if (canManageDocument(user, document, scope)) return;
    // This strict manager gate prevents a supervisor from changing another area’s knowledge.
    if (!AccessControlService.isBoss(user) && !"STORE".equals(scope.visibility())) {
      accessControl.requireKnowledgeBaseTenantWideManage(user);
    }
    for (String storeId : scope.stores()) {
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, storeId, "管理知识库资料");
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有管理该资料的权限", HttpStatus.FORBIDDEN);
  }

  private boolean canManageDocument(AuthUser user, KnowledgeBaseRepository.DocumentRow document, Scope scope) {
    if (AccessControlService.isBoss(user)) return true;
    if (!AccessControlService.hasAnyRole(user, "SUPERVISOR") || !"STORE".equals(scope.visibility())) return false;
    return !scope.stores().isEmpty() && scope.stores().stream()
        .allMatch(storeId -> accessControl.canAccessStore(user, DataScopeDomains.STORE, storeId));
  }

  private boolean visibleTo(AuthUser user, long documentId, String visibility, Map<Long, Scope> knownScopes) {
    if ("TENANT".equals(visibility)) return true;
    Scope scope = knownScopes.get(documentId);
    if (scope == null) {
      scope = new Scope(visibility, repository.roleScopes(documentId), repository.storeScopes(documentId));
      knownScopes.put(documentId, scope);
    }
    if (AccessControlService.isBoss(user)) return true;
    if ("ROLE".equals(scope.visibility())) {
      return scope.roles().contains(AccessControlService.canonicalRole(user.role()));
    }
    if (!"STORE".equals(scope.visibility())) return false;
    Set<String> allowedStores = new LinkedHashSet<>(accessControl.allowedStoreIds(user, DataScopeDomains.STORE));
    if (user.storeId() != null && !user.storeId().isBlank()) allowedStores.add(user.storeId().trim());
    return allowedStores.contains("all") || scope.stores().stream().anyMatch(allowedStores::contains);
  }

  private Scope scope(KnowledgeBaseRepository.DocumentRow document) {
    return new Scope(document.visibility(), repository.roleScopes(document.id()), repository.storeScopes(document.id()));
  }

  private KnowledgeBaseRepository.DocumentRow requiredDocument(AuthUser user, long id) {
    if (id <= 0) throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_DOCUMENT_ID_INVALID", "资料编号不正确");
    return repository.findDocument(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_DOCUMENT_NOT_FOUND", "知识库资料不存在", HttpStatus.NOT_FOUND));
  }

  private List<String> normalizeRoles(List<String> values) {
    LinkedHashSet<String> roles = new LinkedHashSet<>();
    if (values != null) {
      for (String raw : values) {
        for (String value : split(raw)) {
          String role = AccessControlService.canonicalRole(value);
          if (!FORMAL_ROLES.contains(role)) {
            throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_ROLE_SCOPE_INVALID", "资料适用角色不正确");
          }
          roles.add(role);
        }
      }
    }
    return List.copyOf(roles);
  }

  private List<String> normalizeStores(List<String> values) {
    LinkedHashSet<String> stores = new LinkedHashSet<>();
    if (values != null) {
      for (String raw : values) for (String value : split(raw)) {
        if (value.length() > 64) throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_STORE_SCOPE_INVALID", "资料适用门店不正确");
        stores.add(value);
      }
    }
    return List.copyOf(stores);
  }

  private List<String> split(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return java.util.Arrays.stream(raw.split("[,，;；\\s]+"))
        .map(String::trim).filter(value -> !value.isBlank()).toList();
  }

  private String title(String supplied, String fileName) {
    String fallback = fileName == null ? "" : fileName.replaceFirst("\\.[^.]+$", "");
    return required(supplied == null || supplied.isBlank() ? fallback : supplied, "请填写资料标题", 200);
  }

  private String required(String value, String message, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TEXT_REQUIRED", message);
    if (normalized.length() > maxLength) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TEXT_TOO_LONG", message + "不能超过" + maxLength + "个字符");
    }
    return normalized;
  }

  private String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
    }
  }

  private String excerpt(String content) {
    String normalized = (content == null ? "" : content).replaceAll("\\s+", " ").trim();
    return normalized.length() <= 360 ? normalized : normalized.substring(0, 360) + "…";
  }

  private double rounded(double score) {
    return Math.round(score * 10_000d) / 10_000d;
  }

  private KnowledgeBaseDocumentResponse response(KnowledgeBaseRepository.DocumentRow row) {
    Scope scope = scope(row);
    return new KnowledgeBaseDocumentResponse(row.id(), row.title(), row.category(), row.originalFileName(),
        row.contentType(), row.fileSize(), row.visibility(), row.status(), scope.roles(), scope.stores(),
        row.parsedCharCount(), row.chunkCount(), row.createdBy(), row.publishedBy(), row.createdAt(), row.updatedAt(),
        row.publishedAt());
  }

  private void audit(AuthUser user, String action, KnowledgeBaseRepository.DocumentRow document, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(action, "knowledge_base_document", Long.toString(document.id()),
        null, null, reason + "：" + document.title(), null, null));
  }

  private record Scope(String visibility, List<String> roles, List<String> stores) {}

  private record ScoredChunk(KnowledgeBaseRepository.SearchChunkRow chunk, double score) {}

  public record DownloadedDocument(String fileName, String contentType, long fileSize, byte[] content) {}
}
