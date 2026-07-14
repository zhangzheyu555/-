package com.storeprofit.system.expense;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExpenseSupplementService {
  static final int MAX_FILES = 6;
  static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
  static final int MAX_NOTE_LENGTH = 2000;

  private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
      "jpg", "image/jpeg",
      "jpeg", "image/jpeg",
      "png", "image/png",
      "webp", "image/webp",
      "pdf", "application/pdf"
  );

  private final ExpenseRepository expenseRepository;
  private final ExpenseSupplementRepository supplementRepository;
  private final AccessControlService accessControl;
  private final Path storageRoot;

  @Autowired
  public ExpenseSupplementService(
      ExpenseRepository expenseRepository,
      ExpenseSupplementRepository supplementRepository,
      AccessControlService accessControl,
      @Value("${app.storage.expense-supplements-root}") String storageRoot
  ) {
    this(expenseRepository, supplementRepository, accessControl, Path.of(storageRoot));
  }

  ExpenseSupplementService(
      ExpenseRepository expenseRepository,
      ExpenseSupplementRepository supplementRepository,
      AccessControlService accessControl,
      Path storageRoot
  ) {
    this.expenseRepository = expenseRepository;
    this.supplementRepository = supplementRepository;
    this.accessControl = accessControl;
    this.storageRoot = storageRoot.toAbsolutePath().normalize();
  }

  @Transactional
  public ExpenseClaimResponse submit(
      AuthUser user,
      String expenseId,
      String note,
      List<MultipartFile> files
  ) {
    accessControl.requireExpenseWrite(user);
    ExpenseClaimResponse claim = requireClaim(user, expenseId);
    accessControl.requireStoreAccess(user, claim.storeId(), "补充报销资料");
    if (ExpenseService.STATUS_APPROVED.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "已完成的报销不能继续补充资料", HttpStatus.CONFLICT);
    }

    String normalizedNote = normalizeNote(note);
    List<MultipartFile> normalizedFiles = files == null ? List.of() : files;
    if (normalizedFiles.size() > MAX_FILES) {
      throw new BusinessException("TOO_MANY_FILES", "每次最多上传6个文件", HttpStatus.BAD_REQUEST);
    }
    if (normalizedNote == null && normalizedFiles.isEmpty()) {
      throw new BusinessException("SUPPLEMENT_EMPTY", "补充说明和附件不能同时为空", HttpStatus.BAD_REQUEST);
    }

    List<PreparedUpload> uploads = normalizedFiles.stream().map(this::validateFile).toList();
    List<Path> writtenFiles = writeFiles(user.tenantId(), uploads);
    registerRollbackCleanup(writtenFiles);
    try {
      long supplementId = supplementRepository.insertSupplement(
          user.tenantId(), claim.id(), normalizedNote, user.id(), safeDisplayName(user));
      for (int index = 0; index < uploads.size(); index++) {
        PreparedUpload upload = uploads.get(index);
        supplementRepository.insertAttachment(
            user.tenantId(),
            supplementId,
            claim.id(),
            upload.fileName(),
            upload.contentType(),
            upload.bytes().length,
            upload.storageKey(),
            user.id()
        );
      }
      expenseRepository.markSupplemented(user.tenantId(), claim.id(), user.id());
      expenseRepository.logAction(
          user.tenantId(),
          user.id(),
          user.displayName(),
          "expense_supplement_submit",
          claim.id(),
          claim.storeId(),
          claim.month(),
          "提交报销补充资料，附件数量：" + uploads.size()
      );
      return requireClaim(user, claim.id()).withSupplements(
          supplementRepository.supplements(user.tenantId(), claim.id()));
    } catch (RuntimeException ex) {
      if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        cleanup(writtenFiles);
      }
      throw ex;
    }
  }

  public List<ExpenseSupplementResponse> supplements(AuthUser user, String expenseId) {
    accessControl.requireExpenseRead(user);
    ExpenseClaimResponse claim = requireClaim(user, expenseId);
    accessControl.requireStoreAccess(user, claim.storeId(), "查看报销补充资料");
    return supplementRepository.supplements(user.tenantId(), claim.id());
  }

  public AttachmentContent attachment(AuthUser user, String expenseId, long attachmentId, boolean download) {
    accessControl.requireExpenseRead(user);
    ExpenseSupplementRepository.AttachmentMetadata metadata = supplementRepository
        .attachment(user.tenantId(), normalizeExpenseId(expenseId), attachmentId)
        .orElseThrow(() -> new BusinessException("ATTACHMENT_NOT_FOUND", "附件不存在", HttpStatus.NOT_FOUND));
    accessControl.requireStoreAccess(user, metadata.storeId(), "查看报销补充资料附件");
    Path path = resolveStoragePath(user.tenantId(), metadata.storageKey());
    try {
      if (!Files.isRegularFile(path)) {
        throw new BusinessException("ATTACHMENT_NOT_FOUND", "附件不存在", HttpStatus.NOT_FOUND);
      }
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length != metadata.fileSize()) {
        throw new BusinessException("ATTACHMENT_DAMAGED", "附件校验失败，请联系管理员", HttpStatus.CONFLICT);
      }
      String detectedType = detectContentType(bytes);
      if (!MIME_BY_EXTENSION.containsValue(detectedType)
          || !detectedType.equalsIgnoreCase(metadata.contentType())) {
        throw new BusinessException("ATTACHMENT_DAMAGED", "附件校验失败，请联系管理员", HttpStatus.CONFLICT);
      }
      if (download || MediaType.APPLICATION_PDF_VALUE.equals(detectedType)) {
        expenseRepository.logAction(
            user.tenantId(),
            user.id(),
            user.displayName(),
            "expense_supplement_attachment_download",
            metadata.expenseId(),
            metadata.storeId(),
            null,
            "下载报销补充资料附件"
        );
      }
      return new AttachmentContent(normalizeFileName(metadata.fileName()), detectedType, bytes);
    } catch (BusinessException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new BusinessException("ATTACHMENT_READ_FAILED", "附件读取失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private ExpenseClaimResponse requireClaim(AuthUser user, String expenseId) {
    return expenseRepository.claim(user.tenantId(), normalizeExpenseId(expenseId))
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "报销记录不存在", HttpStatus.NOT_FOUND));
  }

  private PreparedUpload validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException("EMPTY_UPLOAD_FILE", "不能上传空文件", HttpStatus.BAD_REQUEST);
    }
    if (file.getSize() > MAX_FILE_SIZE) {
      throw new BusinessException("FILE_TOO_LARGE", "单个文件不能超过10MB", HttpStatus.PAYLOAD_TOO_LARGE);
    }
    String fileName = normalizeFileName(file.getOriginalFilename());
    String extension = extension(fileName);
    String expectedType = MIME_BY_EXTENSION.get(extension);
    if (expectedType == null) {
      throw new BusinessException("FILE_TYPE_NOT_ALLOWED", "仅支持JPG、PNG、WebP和PDF文件", HttpStatus.BAD_REQUEST);
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw new BusinessException("UPLOAD_READ_FAILED", "文件读取失败，请重新选择", HttpStatus.BAD_REQUEST);
    }
    if (bytes.length == 0) {
      throw new BusinessException("EMPTY_UPLOAD_FILE", "不能上传空文件", HttpStatus.BAD_REQUEST);
    }
    if (bytes.length > MAX_FILE_SIZE) {
      throw new BusinessException("FILE_TOO_LARGE", "单个文件不能超过10MB", HttpStatus.PAYLOAD_TOO_LARGE);
    }
    String detectedType = detectContentType(bytes);
    if (!expectedType.equals(detectedType)) {
      throw new BusinessException("FILE_TYPE_MISMATCH", "文件扩展名与真实内容不一致", HttpStatus.BAD_REQUEST);
    }
    String declaredType = normalizeDeclaredContentType(file.getContentType());
    if (declaredType != null && !detectedType.equals(declaredType)) {
      throw new BusinessException("FILE_TYPE_MISMATCH", "文件声明类型与真实内容不一致", HttpStatus.BAD_REQUEST);
    }
    String canonicalExtension = "image/jpeg".equals(detectedType) ? "jpg" : extension;
    String storageKey = UUID.randomUUID() + "." + canonicalExtension;
    return new PreparedUpload(fileName, detectedType, bytes, storageKey);
  }

  private List<Path> writeFiles(long tenantId, List<PreparedUpload> uploads) {
    List<Path> written = new ArrayList<>();
    try {
      Files.createDirectories(storageRoot);
      Path realRoot = storageRoot.toRealPath();
      for (PreparedUpload upload : uploads) {
        Path target = resolveStoragePath(realRoot, tenantId, upload.storageKey());
        Files.createDirectories(target.getParent());
        Path realParent = target.getParent().toRealPath();
        if (!realParent.startsWith(realRoot)) {
          throw new BusinessException("STORAGE_PATH_INVALID", "附件存储路径无效", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        Path safeTarget = realParent.resolve(target.getFileName()).normalize();
        if (!safeTarget.startsWith(realRoot)) {
          throw new BusinessException("STORAGE_PATH_INVALID", "附件存储路径无效", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        Files.write(safeTarget, upload.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        written.add(safeTarget);
      }
      return written;
    } catch (BusinessException ex) {
      cleanup(written);
      throw ex;
    } catch (IOException ex) {
      cleanup(written);
      throw new BusinessException("ATTACHMENT_STORE_FAILED", "附件保存失败，请重试", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private Path resolveStoragePath(long tenantId, String storageKey) {
    try {
      Files.createDirectories(storageRoot);
      return resolveStoragePath(storageRoot.toRealPath(), tenantId, storageKey);
    } catch (IOException ex) {
      throw new BusinessException("ATTACHMENT_READ_FAILED", "附件读取失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private Path resolveStoragePath(Path root, long tenantId, String storageKey) {
    if (storageKey == null || !storageKey.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp|pdf)")) {
      throw new BusinessException("STORAGE_KEY_INVALID", "附件存储标识无效", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    String shard = storageKey.substring(0, 2).toLowerCase(Locale.ROOT);
    Path target = root.resolve(Long.toString(tenantId)).resolve(shard).resolve(storageKey).normalize();
    if (!target.startsWith(root)) {
      throw new BusinessException("STORAGE_PATH_INVALID", "附件存储路径无效", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return target;
  }

  private void registerRollbackCleanup(List<Path> paths) {
    if (!paths.isEmpty() && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          if (status != TransactionSynchronization.STATUS_COMMITTED) {
            cleanup(paths);
          }
        }
      });
    }
  }

  private void cleanup(List<Path> paths) {
    for (Path path : paths) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // Best effort after a failed or rolled-back upload. Never expose server paths in the response/log.
      }
    }
  }

  private String normalizeNote(String note) {
    if (note == null || note.isBlank()) {
      return null;
    }
    String normalized = note.trim();
    if (normalized.length() > MAX_NOTE_LENGTH) {
      throw new BusinessException("NOTE_TOO_LONG", "补充说明不能超过2000字", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String normalizeExpenseId(String value) {
    if (value == null || value.isBlank() || value.length() > 120) {
      throw new BusinessException("ID_REQUIRED", "报销记录编号不正确", HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String normalizeFileName(String value) {
    String normalized = value == null ? "" : value.trim();
    try {
      normalized = Path.of(normalized.replace('\\', '/')).getFileName().toString();
    } catch (InvalidPathException ex) {
      throw new BusinessException("BAD_FILE_NAME", "文件名不正确", HttpStatus.BAD_REQUEST);
    }
    normalized = normalized.replaceAll("[\\p{Cntrl}]", "_");
    if (normalized.isBlank()) {
      throw new BusinessException("BAD_FILE_NAME", "文件名不能为空", HttpStatus.BAD_REQUEST);
    }
    return normalized.length() > 255 ? normalized.substring(normalized.length() - 255) : normalized;
  }

  private String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 || dot == fileName.length() - 1
        ? ""
        : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private String normalizeDeclaredContentType(String value) {
    if (value == null || value.isBlank() || "application/octet-stream".equalsIgnoreCase(value.trim())) {
      return null;
    }
    String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if ("image/jpg".equals(normalized) || "image/pjpeg".equals(normalized)) {
      return "image/jpeg";
    }
    return normalized;
  }

  private String detectContentType(byte[] bytes) {
    if (startsWith(bytes, new int[]{0xff, 0xd8, 0xff})) {
      return "image/jpeg";
    }
    if (startsWith(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
      return "image/png";
    }
    if (bytes.length >= 12
        && ascii(bytes, 0, 4).equals("RIFF")
        && ascii(bytes, 8, 4).equals("WEBP")) {
      return "image/webp";
    }
    if (bytes.length >= 5 && ascii(bytes, 0, 5).equals("%PDF-")) {
      return "application/pdf";
    }
    return "application/octet-stream";
  }

  private boolean startsWith(byte[] bytes, int[] signature) {
    if (bytes.length < signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if ((bytes[index] & 0xff) != signature[index]) {
        return false;
      }
    }
    return true;
  }

  private String ascii(byte[] bytes, int start, int length) {
    return new String(bytes, start, length, StandardCharsets.US_ASCII);
  }

  private String safeDisplayName(AuthUser user) {
    String name = user.displayName();
    return name == null || name.isBlank() ? user.username() : name.trim();
  }

  public record AttachmentContent(String fileName, String contentType, byte[] bytes) {
  }

  private record PreparedUpload(String fileName, String contentType, byte[] bytes, String storageKey) {
  }
}
