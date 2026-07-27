package com.storeprofit.system.knowledgebase;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
  private static final Set<String> RELATION_TYPES = Set.of("ORIGINAL", "REPLACES", "SUPPLEMENTS");
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

  @Transactional(readOnly = true)
  public List<KnowledgeBaseAvailableDocumentResponse> availableDocuments(AuthUser user) {
    accessControl.requireKnowledgeBaseSearch(user);
    DataScope storeScope = accessControl.knowledgeBaseReadStoreScope(user);
    LinkedHashSet<String> allowedStores = new LinkedHashSet<>(storeScope.storeIds());
    boolean unrestricted = AccessControlService.isBoss(user);
    return repository.availablePublishedDocuments(
            user.tenantId(),
            AccessControlService.canonicalRole(user.role()),
            allowedStores,
            storeScope.allowsAllStores(),
            unrestricted,
            500)
        .stream()
        .map(document -> new KnowledgeBaseAvailableDocumentResponse(
            document.id(),
            document.title(),
            document.category(),
            document.originalFileName(),
            document.fileSize(),
            document.publishedAt(),
            document.updatedAt()))
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
      List<String> storeScopes,
      Long topicId,
      String topicName,
      String relationType,
      Long predecessorDocumentId
  ) {
    return upload(user, file, title, category, visibility, roleScopes, storeScopes,
        topicId, topicName, relationType, predecessorDocumentId, false);
  }

  @Transactional
  public KnowledgeBaseDocumentResponse upload(
      AuthUser user,
      MultipartFile file,
      String title,
      String category,
      String visibility,
      List<String> roleScopes,
      List<String> storeScopes,
      Long topicId,
      String topicName,
      String relationType,
      Long predecessorDocumentId,
      boolean publishNow
  ) {
    accessControl.requireKnowledgeBaseManage(user);
    KnowledgeDocumentParser.ParsedDocument parsed = parser.parse(file);
    Scope normalizedScope = normalizeScope(user, visibility, roleScopes, storeScopes);
    String normalizedTitle = title(title, parsed.fileName());
    String normalizedCategory = required(category, "请选择资料分类", 64).toUpperCase(Locale.ROOT);
    KnowledgeBaseRepository.TopicRow topic = resolveTopic(user, topicId, topicName, normalizedTitle);
    repository.lockTopic(user.tenantId(), topic.id());
    int versionNo = repository.nextVersionNo(user.tenantId(), topic.id());
    Relation relation = normalizeRelation(user, topic, versionNo, relationType, predecessorDocumentId);
    List<KnowledgeDocumentChunker.ChunkDraft> chunkDrafts = chunker.split(parsed.sections());
    int parsedChars = parsed.sections().stream().mapToInt(section -> section.text().length()).sum();
    ArrayList<KnowledgeBaseRepository.ChunkInsert> chunks = new ArrayList<>();
    for (KnowledgeDocumentChunker.ChunkDraft chunk : chunkDrafts) {
      chunks.add(new KnowledgeBaseRepository.ChunkInsert(
          chunk.sourceLocator(), chunk.content(), sha256(chunk.content().getBytes(StandardCharsets.UTF_8)),
          embeddingService.embed(chunk.content())));
    }
    long id;
    try {
      id = repository.insertDocument(user.tenantId(), new KnowledgeBaseRepository.DocumentInsert(
          topic.id(), versionNo, relation.type(), relation.predecessorDocumentId(),
          normalizedTitle, normalizedCategory, parsed.fileName(), parsed.contentType(), sha256(parsed.sourceContent()),
          parsed.sourceContent(), normalizedScope.visibility(), parsedChars, chunks.size(), user.id()));
    } catch (DataIntegrityViolationException ex) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_DUPLICATE", "相同文件已存在，请不要重复上传", HttpStatus.CONFLICT);
    }
    repository.insertRoleScopes(id, normalizedScope.roles());
    repository.insertStoreScopes(id, normalizedScope.stores());
    repository.insertChunks(user.tenantId(), id, List.copyOf(chunks));
    repository.touchTopic(user.tenantId(), topic.id());
    KnowledgeBaseRepository.DocumentRow saved = requiredDocument(user, id);
    audit(user, "knowledge_base.document_upload", saved,
          "已归入主题“" + topic.name() + "”第" + versionNo + "版并完成本地向量索引，共" + chunks.size() + "段");
    return publishNow ? publishDraft(user, saved) : response(saved);
  }

  @Transactional
  public KnowledgeBaseDocumentResponse publish(AuthUser user, long id) {
    accessControl.requireKnowledgeBaseManage(user);
    KnowledgeBaseRepository.DocumentRow document = requiredDocument(user, id);
    Scope scope = scope(document);
    requireManageDocument(user, document, scope);
    return publishDraft(user, document);
  }

  private KnowledgeBaseDocumentResponse publishDraft(
      AuthUser user,
      KnowledgeBaseRepository.DocumentRow document
  ) {
    if (!"DRAFT".equals(document.status())) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_NOT_DRAFT", "仅草稿资料可以发布", HttpStatus.CONFLICT);
    }
    if (document.chunkCount() <= 0) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_EMPTY_DOCUMENT", "资料没有可检索内容，不能发布");
    }
    KnowledgeBaseRepository.DocumentRow predecessor = null;
    if ("REPLACES".equals(document.relationType())) {
      if (document.predecessorDocumentId() == null) {
        throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_PREDECESSOR_REQUIRED", "替代版本必须指定被替代资料");
      }
      predecessor = requiredDocument(user, document.predecessorDocumentId());
      requireManageDocument(user, predecessor, scope(predecessor));
      if (predecessor.topicId() != document.topicId() || !"PUBLISHED".equals(predecessor.status())) {
        throw new BusinessException(
            "KNOWLEDGE_BASE_PREDECESSOR_NOT_PUBLISHED", "被替代资料不是同一主题的已发布版本", HttpStatus.CONFLICT);
      }
    }
    if (repository.publish(user.tenantId(), document.id(), user.id()) == 0) {
      throw new BusinessException("KNOWLEDGE_BASE_DOCUMENT_CONFLICT", "资料状态已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    if (predecessor != null
        && repository.archivePublishedPredecessor(user.tenantId(), predecessor.id()) == 0) {
      throw new BusinessException(
          "KNOWLEDGE_BASE_PREDECESSOR_CONFLICT", "被替代资料状态已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    repository.touchTopic(user.tenantId(), document.topicId());
    KnowledgeBaseRepository.DocumentRow published = requiredDocument(user, document.id());
    String reason = predecessor == null
        ? "已发布知识库资料"
        : "已发布知识库资料，并自动下架被替代的第" + predecessor.versionNo() + "版（资料编号"
            + predecessor.id() + "）";
    audit(user, "knowledge_base.document_publish", published, reason);
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
    repository.touchTopic(user.tenantId(), document.topicId());
    KnowledgeBaseRepository.DocumentRow archived = requiredDocument(user, id);
    audit(user, "knowledge_base.document_archive", archived, "已下架知识库资料");
    return response(archived);
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseSearchResultResponse> search(AuthUser user, String query, int limit) {
    return searchResults(user, query, limit);
  }

  @Transactional(readOnly = true)
  public KnowledgeBaseSearchResponse searchWithSummary(AuthUser user, String query, int limit) {
    List<KnowledgeBaseSearchResultResponse> results = searchResults(user, query, limit);
    String normalizedQuery = required(query, "请输入至少两个字符的检索内容", 300);
    return new KnowledgeBaseSearchResponse(
        normalizedQuery, summary(normalizedQuery, results), topicGroups(normalizedQuery, results), results);
  }

  private List<KnowledgeBaseSearchResultResponse> searchResults(AuthUser user, String query, int limit) {
    accessControl.requireKnowledgeBaseSearch(user);
    String normalizedQuery = required(query, "请输入至少两个字符的检索内容", 300);
    if (normalizedQuery.codePointCount(0, normalizedQuery.length()) < 2) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_QUERY_TOO_SHORT", "检索内容至少需要两个字符");
    }
    int boundedLimit = Math.max(1, Math.min(10, limit));
    byte[] queryEmbedding = embeddingService.embed(normalizedQuery);
    List<String> tokens = queryTokens(normalizedQuery);
    Map<Long, Scope> scopes = new HashMap<>();
    Map<String, ScoredChunk> integrated = new HashMap<>();
    repository.publishedChunks(user.tenantId()).stream()
        .filter(chunk -> visibleTo(user, chunk.documentId(), chunk.visibility(), scopes, accessControl.knowledgeBaseReadStoreScope(user)))
        .map(chunk -> scoreChunk(chunk, queryEmbedding, tokens, normalizedQuery))
        .filter(item -> item.score() >= MINIMUM_SCORE)
        .forEach(item -> integrated.merge(
            item.chunk().topicId() + ":" + item.chunk().contentHash(),
            item,
            this::preferredSource));
    return integrated.values().stream()
        .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
            .thenComparing((ScoredChunk item) -> item.chunk().versionNo(), Comparator.reverseOrder())
            .thenComparing(item -> item.chunk().documentId()))
        .limit(boundedLimit)
        .map(item -> new KnowledgeBaseSearchResultResponse(
            item.chunk().documentId(), item.chunk().topicId(), item.chunk().topicName(), item.chunk().versionNo(),
            item.chunk().title(), item.chunk().category(), item.chunk().sourceLocator(),
            excerpt(item.chunk().content()), rounded(item.score())))
        .toList();
  }

  private ScoredChunk preferredSource(ScoredChunk left, ScoredChunk right) {
    if (left.chunk().versionNo() != right.chunk().versionNo()) {
      return left.chunk().versionNo() > right.chunk().versionNo() ? left : right;
    }
    return left.score() >= right.score() ? left : right;
  }

  private List<KnowledgeBaseTopicSearchResponse> topicGroups(
      String query,
      List<KnowledgeBaseSearchResultResponse> results
  ) {
    LinkedHashMap<Long, List<KnowledgeBaseSearchResultResponse>> grouped = new LinkedHashMap<>();
    for (KnowledgeBaseSearchResultResponse result : results) {
      grouped.computeIfAbsent(result.topicId(), ignored -> new ArrayList<>()).add(result);
    }
    return grouped.entrySet().stream()
        .map(entry -> {
          List<KnowledgeBaseSearchResultResponse> sources = List.copyOf(entry.getValue());
          KnowledgeBaseSearchResultResponse first = sources.getFirst();
          return new KnowledgeBaseTopicSearchResponse(
              entry.getKey(), first.topicName(), summary(query, sources), sources);
        })
        .toList();
  }

  // A successful source-file download is auditable, so this cannot use a read-only JDBC transaction.
  @Transactional
  public DownloadedDocument download(AuthUser user, long id) {
    KnowledgeBaseRepository.DocumentRow document = requiredDocument(user, id);
    Scope scope = scope(document);
    boolean manageable = accessControl.hasPermission(user, "knowledge_base.manage") && canManageDocument(user, document, scope);
    DataScope storeScope = accessControl.knowledgeBaseReadStoreScope(user);
    boolean readable = "PUBLISHED".equals(document.status())
        && visibleTo(user, document.id(), document.visibility(), Map.of(document.id(), scope), storeScope);
    accessControl.requireKnowledgeBaseDocumentRead(user, manageable || readable, id);
    KnowledgeBaseRepository.DocumentContentRow content = repository.findDocumentContent(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException(
            "KNOWLEDGE_BASE_DOCUMENT_NOT_FOUND", "知识库资料不存在", HttpStatus.NOT_FOUND));
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
        if (repository.storeExistsOutsideTenant(user.tenantId(), storeId)) {
          accessControl.rejectKnowledgeBaseCrossTenantStore(user, storeId);
        }
        throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_STORE_NOT_FOUND", "指定门店不存在或不属于当前企业");
      }
      accessControl.requireKnowledgeBaseStoreAccess(user, storeId);
    }
    return new Scope(normalizedVisibility, List.of(), stores);
  }

  private KnowledgeBaseRepository.TopicRow resolveTopic(
      AuthUser user,
      Long topicId,
      String suppliedTopicName,
      String fallbackTitle
  ) {
    if (topicId != null) {
      if (topicId <= 0) {
        throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TOPIC_ID_INVALID", "知识主题编号不正确");
      }
      return repository.findTopic(user.tenantId(), topicId)
          .orElseThrow(() -> KnowledgeBaseErrors.badRequest(
              "KNOWLEDGE_BASE_TOPIC_NOT_FOUND", "指定知识主题不存在或不属于当前企业"));
    }
    String name = required(
        suppliedTopicName == null || suppliedTopicName.isBlank() ? fallbackTitle : suppliedTopicName,
        "请填写知识主题", 200);
    String normalizedName = normalizedTopicName(name);
    KnowledgeBaseRepository.TopicRow existing =
        repository.findTopicByNormalizedName(user.tenantId(), normalizedName).orElse(null);
    if (existing != null) return existing;
    try {
      long id = repository.insertTopic(user.tenantId(), name, normalizedName, user.id());
      return repository.findTopic(user.tenantId(), id)
          .orElseThrow(() -> new IllegalStateException("知识主题保存后无法读取"));
    } catch (DataIntegrityViolationException ex) {
      return repository.findTopicByNormalizedName(user.tenantId(), normalizedName)
          .orElseThrow(() -> ex);
    }
  }

  private Relation normalizeRelation(
      AuthUser user,
      KnowledgeBaseRepository.TopicRow topic,
      int versionNo,
      String suppliedType,
      Long suppliedPredecessorId
  ) {
    String type = suppliedType == null || suppliedType.isBlank()
        ? (versionNo == 1 ? "ORIGINAL" : "SUPPLEMENTS")
        : suppliedType.trim().toUpperCase(Locale.ROOT);
    if (!RELATION_TYPES.contains(type)) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_RELATION_INVALID", "资料版本关系不正确");
    }
    if (versionNo == 1 && !"ORIGINAL".equals(type)) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_RELATION_INVALID", "主题首版必须是原始版本");
    }
    if (versionNo > 1 && "ORIGINAL".equals(type)) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_RELATION_INVALID", "已有主题不能再次创建原始版本");
    }
    if ("ORIGINAL".equals(type)) return new Relation(type, null);
    Long predecessorId = suppliedPredecessorId;
    if (predecessorId == null) {
      predecessorId = repository.latestDocument(user.tenantId(), topic.id())
          .map(KnowledgeBaseRepository.DocumentRow::id)
          .orElse(null);
    }
    if (predecessorId == null || predecessorId <= 0) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_PREDECESSOR_REQUIRED", "请选择关联的上一版本资料");
    }
    KnowledgeBaseRepository.DocumentRow predecessor = repository.findDocument(user.tenantId(), predecessorId)
        .orElseThrow(() -> KnowledgeBaseErrors.badRequest(
            "KNOWLEDGE_BASE_PREDECESSOR_NOT_FOUND", "关联的上一版本资料不存在"));
    requireManageDocument(user, predecessor, scope(predecessor));
    if (predecessor.topicId() != topic.id()) {
      throw KnowledgeBaseErrors.badRequest(
          "KNOWLEDGE_BASE_PREDECESSOR_TOPIC_MISMATCH", "只能关联同一知识主题下的资料");
    }
    return new Relation(type, predecessorId);
  }

  private String normalizedTopicName(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  private void requireManageDocument(AuthUser user, KnowledgeBaseRepository.DocumentRow document, Scope scope) {
    if (canManageDocument(user, document, scope)) return;
    // This strict manager gate prevents a supervisor from changing another area’s knowledge.
    if (!AccessControlService.isBoss(user) && !"STORE".equals(scope.visibility())) {
      accessControl.requireKnowledgeBaseTenantWideManage(user);
    }
    for (String storeId : scope.stores()) {
      accessControl.requireKnowledgeBaseStoreAccess(user, storeId);
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有管理该资料的权限", HttpStatus.FORBIDDEN);
  }

  private boolean canManageDocument(AuthUser user, KnowledgeBaseRepository.DocumentRow document, Scope scope) {
    if (AccessControlService.isBoss(user)) return true;
    if (!AccessControlService.hasAnyRole(user, "SUPERVISOR") || !"STORE".equals(scope.visibility())) return false;
    return !scope.stores().isEmpty() && scope.stores().stream()
        .allMatch(storeId -> accessControl.canManageKnowledgeBaseStore(user, storeId));
  }

  private boolean visibleTo(
      AuthUser user,
      long documentId,
      String visibility,
      Map<Long, Scope> knownScopes,
      DataScope storeScope
  ) {
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
    return storeScope.allowsAllStores()
        || scope.stores().stream().anyMatch(storeScope.storeIds()::contains);
  }

  private Scope scope(KnowledgeBaseRepository.DocumentRow document) {
    return new Scope(document.visibility(), repository.roleScopes(document.id()), repository.storeScopes(document.id()));
  }

  private KnowledgeBaseRepository.DocumentRow requiredDocument(AuthUser user, long id) {
    if (id <= 0) throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_DOCUMENT_ID_INVALID", "资料编号不正确");
    return repository.findDocument(user.tenantId(), id).orElseGet(() -> {
      denyCrossTenantDocumentIfPresent(user, id);
      throw new BusinessException(
          "KNOWLEDGE_BASE_DOCUMENT_NOT_FOUND", "知识库资料不存在", HttpStatus.NOT_FOUND);
    });
  }

  private void denyCrossTenantDocumentIfPresent(AuthUser user, long id) {
    if (repository.documentExistsOutsideTenant(user.tenantId(), id)) {
      accessControl.requireKnowledgeBaseDocumentRead(user, false, id);
    }
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

  private ScoredChunk scoreChunk(
      KnowledgeBaseRepository.SearchChunkRow chunk,
      byte[] queryEmbedding,
      List<String> tokens,
      String query
  ) {
    double vectorScore = embeddingService.cosine(queryEmbedding, chunk.embedding());
    double keywordScore = keywordScore(chunk, tokens, query);
    double score = keywordScore <= 0d ? vectorScore : Math.max(vectorScore, vectorScore * 0.35d + keywordScore * 0.65d);
    return new ScoredChunk(chunk, Math.min(1d, score));
  }

  private double keywordScore(KnowledgeBaseRepository.SearchChunkRow chunk, List<String> tokens, String query) {
    if (tokens.isEmpty()) return 0d;
    String haystack = normalizedMatchText(chunk.title() + " " + chunk.category() + " "
        + chunk.sourceLocator() + " " + chunk.content());
    long matched = tokens.stream().filter(haystack::contains).count();
    double score = (double) matched / tokens.size() * 0.72d;
    String compactQuery = compactForMatch(query);
    if (!compactQuery.isBlank()) {
      if (compactForMatch(chunk.title()).contains(compactQuery)) score += 0.24d;
      if (compactForMatch(chunk.content()).contains(compactQuery)) score += 0.18d;
    }
    if (normalizedMatchText(chunk.category()).contains(normalizedMatchText(query))) score += 0.12d;
    return Math.min(1d, score);
  }

  private List<String> queryTokens(String query) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    for (String value : normalizedMatchText(query).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
      if (value.length() >= 2) tokens.add(value);
    }
    String compact = compactForMatch(query);
    if (compact.length() >= 2) {
      tokens.add(compact);
      int[] codePoints = compact.codePoints().toArray();
      for (int index = 0; index + 2 <= codePoints.length && tokens.size() < 24; index++) {
        tokens.add(new String(codePoints, index, 2));
      }
      for (int index = 0; index + 3 <= codePoints.length && tokens.size() < 24; index++) {
        tokens.add(new String(codePoints, index, 3));
      }
    }
    return tokens.stream().limit(24).toList();
  }

  private String summary(String query, List<KnowledgeBaseSearchResultResponse> results) {
    if (results.isEmpty()) return "";
    List<String> tokens = queryTokens(query);
    LinkedHashSet<String> points = new LinkedHashSet<>();
    for (KnowledgeBaseSearchResultResponse result : results) {
      String point = bestSentence(result.excerpt(), tokens);
      if (!point.isBlank()) points.add(point);
      if (points.size() >= 3) break;
    }
    if (points.isEmpty()) return "";
    StringBuilder builder = new StringBuilder("根据已发布资料，检索结果可归纳为：");
    int index = 1;
    for (String point : points) {
      builder.append('\n').append(index++).append(". ").append(point);
    }
    return builder.toString();
  }

  private String bestSentence(String text, List<String> tokens) {
    String normalized = (text == null ? "" : text).replaceAll("\\s+", " ").trim();
    if (normalized.isBlank()) return "";
    String[] sentences = normalized.split("(?<=[。！？；;])|\\n+");
    String best = "";
    int bestScore = -1;
    for (String sentence : sentences) {
      String candidate = sentence.trim();
      if (candidate.isBlank()) continue;
      String matchText = normalizedMatchText(candidate);
      int score = (int) tokens.stream().filter(matchText::contains).count();
      if (score > bestScore || (score == bestScore && candidate.length() > best.length())) {
        best = candidate;
        bestScore = score;
      }
    }
    if (best.isBlank()) best = normalized;
    return best.length() <= 180 ? best : best.substring(0, 180) + "…";
  }

  private String normalizedMatchText(String value) {
    return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String compactForMatch(String value) {
    StringBuilder result = new StringBuilder();
    normalizedMatchText(value).codePoints().filter(Character::isLetterOrDigit).limit(1000)
        .forEach(result::appendCodePoint);
    return result.toString();
  }

  private KnowledgeBaseDocumentResponse response(KnowledgeBaseRepository.DocumentRow row) {
    Scope scope = scope(row);
    return new KnowledgeBaseDocumentResponse(
        row.id(), row.topicId(), row.topicName(), row.versionNo(), row.relationType(), row.predecessorDocumentId(),
        row.title(), row.category(), row.originalFileName(),
        row.contentType(), row.fileSize(), row.visibility(), row.status(), scope.roles(), scope.stores(),
        row.parsedCharCount(), row.chunkCount(), row.createdBy(), row.publishedBy(), row.createdAt(), row.updatedAt(),
        row.publishedAt());
  }

  private void audit(AuthUser user, String action, KnowledgeBaseRepository.DocumentRow document, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(action, "knowledge_base_document", Long.toString(document.id()),
        null, null, reason + "：" + document.title(), null, null));
  }

  private record Scope(String visibility, List<String> roles, List<String> stores) {}

  private record Relation(String type, Long predecessorDocumentId) {}

  private record ScoredChunk(KnowledgeBaseRepository.SearchChunkRow chunk, double score) {}

  public record DownloadedDocument(String fileName, String contentType, long fileSize, byte[] content) {}
}
