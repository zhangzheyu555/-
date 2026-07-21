package com.storeprofit.system.operations;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.TrainingVideoModels.ProgressReportRequest;
import com.storeprofit.system.operations.TrainingVideoModels.ProgressResponse;
import com.storeprofit.system.operations.TrainingVideoModels.VideoResponse;
import com.storeprofit.system.operations.TrainingVideoModels.ViewerProgressRow;
import com.storeprofit.system.operations.TrainingVideoRepository.ProgressRow;
import com.storeprofit.system.operations.TrainingVideoRepository.VideoRow;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.storage.StorageService.TrainingVideoAttachment;
import com.storeprofit.system.storage.StorageService.TrainingVideoContent;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TrainingVideoService {
  public static final long MAX_VIDEO_BYTES = 20L * 1024 * 1024;
  private static final int MAX_RANGE_BYTES = 1024 * 1024;
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final BigDecimal HUNDRED = new BigDecimal("100.00");
  private static final BigDecimal COMPLETE_THRESHOLD = new BigDecimal("95");
  private static final BigDecimal MAX_DURATION_SECONDS = new BigDecimal("28800");
  private static final BigDecimal ELAPSED_TOLERANCE = new BigDecimal("1.15");
  private static final BigDecimal GRACE_SECONDS = new BigDecimal("3");
  private static final Map<String, VideoFormat> FORMATS = Map.of(
      "mp4", new VideoFormat("video/mp4", Set.of("video/mp4"), false),
      "m4v", new VideoFormat("video/mp4", Set.of("video/mp4", "video/x-m4v"), false),
      "webm", new VideoFormat("video/webm", Set.of("video/webm"), true),
      "mov", new VideoFormat("video/quicktime", Set.of("video/quicktime"), false)
  );

  private final AccessControlService accessControl;
  private final TrainingVideoRepository repository;
  private final ExamLearningRepository learningRepository;
  private final StorageService storageService;
  private final AuditRepository auditRepository;

  public TrainingVideoService(
      AccessControlService accessControl,
      TrainingVideoRepository repository,
      ExamLearningRepository learningRepository,
      StorageService storageService,
      AuditRepository auditRepository
  ) {
    this.accessControl = accessControl;
    this.repository = repository;
    this.learningRepository = learningRepository;
    this.storageService = storageService;
    this.auditRepository = auditRepository;
  }

  public List<VideoResponse> videos(AuthUser user) {
    accessControl.requireExamRead(user);
    Map<Long, ProgressRow> progress = repository.myProgress(user.tenantId(), user.id()).stream()
        .collect(Collectors.toMap(ProgressRow::videoId, Function.identity()));
    return repository.videos(user.tenantId(), canManage(user)).stream()
        .map(video -> toResponse(video, progress.get(video.id())))
        .toList();
  }

  @Transactional
  public VideoResponse upload(
      AuthUser user, MultipartFile file, String title, String category, Long courseId, Integer sortOrder
  ) {
    accessControl.requireExamManage(user);
    ValidatedVideo validated = validateVideo(file);
    if (courseId != null && learningRepository.course(user.tenantId(), courseId).isEmpty()) {
      throw bad("COURSE_NOT_FOUND", "培训课程不存在");
    }
    String code = generatedCode();
    TrainingVideoAttachment attachment = storageService.uploadTrainingVideo(user, file, code);
    String finalTitle = blankToNull(title) != null ? title.trim() : stripExtension(validated.fileName());
    long id = repository.insertVideo(
        user.tenantId(), code, attachment.id(), courseId, finalTitle, blankToNull(category), null,
        sortOrder == null ? 0 : sortOrder, user.id());
    if (id <= 0) throw new BusinessException("VIDEO_SAVE_FAILED", "视频保存失败", HttpStatus.INTERNAL_SERVER_ERROR);
    audit(user, "上传培训视频", id, "已保存受保护的视频附件");
    return toResponse(requireVideo(user, id), null);
  }

  @Transactional
  public void delete(AuthUser user, long videoId) {
    accessControl.requireExamManage(user);
    VideoRow video = requireVideo(user, videoId);
    if (!repository.deleteVideo(user.tenantId(), videoId)) {
      throw notFound("VIDEO_NOT_FOUND", "培训视频不存在");
    }
    if (!storageService.deleteTrainingVideoAttachment(user, video.attachmentId())) {
      throw new BusinessException("VIDEO_ATTACHMENT_NOT_FOUND", "视频附件不存在", HttpStatus.CONFLICT);
    }
    audit(user, "删除培训视频", videoId, "已删除视频元数据、观看进度和受保护附件");
  }

  public VideoContentResponse content(AuthUser user, long videoId, String rangeHeader) {
    VideoRow video = requireContentAccess(user, videoId);
    ByteRange range = parseRange(rangeHeader, video.fileSize());
    int length = Math.toIntExact(range.end() - range.start() + 1);
    TrainingVideoContent content = storageService.trainingVideoContent(
            user, video.attachmentId(), range.start(), length)
        .orElseThrow(() -> notFound("VIDEO_CONTENT_NOT_FOUND", "视频内容不存在"));
    audit(user, "播放培训视频", videoId, "受认证内容读取 " + content.start() + "-" + content.end());
    return new VideoContentResponse(
        video.fileName(), video.contentType(), content.fileSize(), content.start(), content.end(),
        range.partial(), content.content());
  }

  public void authorizeContent(AuthUser user, long videoId) {
    requireContentAccess(user, videoId);
  }

  private VideoRow requireContentAccess(AuthUser user, long videoId) {
    accessControl.requireExamRead(user);
    VideoRow video = requireVideo(user, videoId);
    if (!video.enabled() && !canManage(user)) throw notFound("VIDEO_NOT_FOUND", "培训视频不存在");
    return video;
  }

  @Transactional
  public ProgressResponse reportProgress(AuthUser user, long videoId, ProgressReportRequest request) {
    accessControl.requireExamRead(user);
    VideoRow video = requireVideo(user, videoId);
    if (!video.enabled() && !canManage(user)) throw notFound("VIDEO_NOT_FOUND", "培训视频不存在");
    if (request == null || request.positionSeconds() == null) {
      throw bad("PROGRESS_REQUIRED", "请上报播放位置");
    }
    BigDecimal duration = positiveOrNull(request.durationSeconds());
    if (duration == null || duration.compareTo(MAX_DURATION_SECONDS) > 0) {
      throw bad("DURATION_INVALID", "视频时长无效");
    }
    if (video.durationSeconds() == null) {
      repository.updateVideoDuration(user.tenantId(), videoId, duration);
    } else if (!durationClose(video.durationSeconds(), duration)) {
      throw bad("DURATION_MISMATCH", "视频时长与首次播放记录不一致");
    } else {
      duration = video.durationSeconds();
    }
    BigDecimal position = clamp(amount(request.positionSeconds()), ZERO, duration);
    repository.ensureProgressRow(user.tenantId(), videoId, user.id(), user.displayName(), user.storeId());
    ProgressRow row = repository.progressForUpdate(user.tenantId(), videoId, user.id())
        .orElseThrow(() -> notFound("PROGRESS_NOT_FOUND", "观看进度不存在"));
    BigDecimal allowance = GRACE_SECONDS;
    if (row.lastReportedAt() != null) {
      long elapsedMillis = Math.max(0, System.currentTimeMillis() - row.lastReportedAt().getTime());
      allowance = allowance.add(BigDecimal.valueOf(elapsedMillis)
          .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP)
          .multiply(ELAPSED_TOLERANCE));
    }
    BigDecimal watched = clamp(
        position.min(row.watchedSeconds().add(allowance)).max(row.watchedSeconds()), ZERO, duration);
    BigDecimal safePosition = position.min(watched.add(BigDecimal.ONE)).min(duration);
    BigDecimal percent = watched.multiply(HUNDRED).divide(duration, 2, RoundingMode.HALF_UP).min(HUNDRED);
    boolean completed = row.completed()
        || (row.lastReportedAt() != null && percent.compareTo(COMPLETE_THRESHOLD) >= 0);
    if (completed) percent = HUNDRED;
    repository.updateProgress(
        user.tenantId(), videoId, user.id(), watched, duration, percent, safePosition, completed);
    if (!row.completed() && completed) audit(user, "完成培训视频", videoId, "服务端确认连续观看完成");
    return new ProgressResponse(videoId, watched, safePosition, percent, completed);
  }

  public List<ViewerProgressRow> progressReport(AuthUser user) {
    if (!canManage(user) && !accessControl.hasPermission(user, PermissionCodes.EXAM_REPORT)) {
      throw new BusinessException("FORBIDDEN", "没有查看观看进度统计的权限", HttpStatus.FORBIDDEN);
    }
    List<ViewerProgressRow> rows = repository.progressReport(user.tenantId());
    if ("STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      String storeId = user.storeId();
      if (storeId == null || storeId.isBlank()) {
        throw new BusinessException("STORE_MANAGER_SCOPE_INVALID", "店长未绑定门店，不能查看培训进度", HttpStatus.FORBIDDEN);
      }
      return rows.stream().filter(row -> storeId.equals(row.storeId())).toList();
    }
    return rows;
  }

  private VideoRow requireVideo(AuthUser user, long videoId) {
    return repository.video(user.tenantId(), videoId).orElseThrow(() -> {
      if (repository.tenantForVideo(videoId).filter(owner -> owner != user.tenantId()).isPresent()) {
        return new BusinessException("FORBIDDEN", "不能访问其他企业的培训视频", HttpStatus.FORBIDDEN);
      }
      return notFound("VIDEO_NOT_FOUND", "培训视频不存在");
    });
  }

  private ValidatedVideo validateVideo(MultipartFile file) {
    if (file == null || file.isEmpty()) throw bad("VIDEO_FILE_REQUIRED", "请选择要上传的视频文件");
    if (file.getSize() > MAX_VIDEO_BYTES) throw bad("VIDEO_TOO_LARGE", "视频不能超过 20MB");
    String fileName = sanitizeFileName(file.getOriginalFilename());
    String extension = extension(fileName);
    VideoFormat format = FORMATS.get(extension);
    if (format == null) throw bad("VIDEO_TYPE_INVALID", "仅支持 mp4、m4v、webm、mov 格式的视频");
    String suppliedType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
    if (!format.acceptedMimeTypes().contains(suppliedType)) {
      throw bad("VIDEO_MIME_INVALID", "视频 MIME 类型与文件扩展名不一致");
    }
    byte[] header = new byte[16];
    int read;
    try (InputStream input = file.getInputStream()) {
      read = input.read(header);
    } catch (IOException ex) {
      throw bad("VIDEO_READ_FAILED", "视频文件读取失败");
    }
    if (!validSignature(header, read, format.webm())) {
      throw bad("VIDEO_CONTENT_INVALID", "文件内容不是有效的视频");
    }
    return new ValidatedVideo(fileName, format.storedContentType());
  }

  private boolean validSignature(byte[] header, int length, boolean webm) {
    if (webm) {
      return length >= 4 && (header[0] & 0xff) == 0x1a && header[1] == 0x45
          && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3;
    }
    return length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
  }

  private ByteRange parseRange(String header, long total) {
    if (total <= 0 || total > MAX_VIDEO_BYTES) throw bad("VIDEO_SIZE_INVALID", "视频大小无效");
    if (header == null || header.isBlank()) return new ByteRange(0, total - 1, false);
    String value = header.trim().toLowerCase(Locale.ROOT);
    if (!value.startsWith("bytes=") || value.contains(",")) throw rangeError();
    String[] parts = value.substring(6).split("-", -1);
    if (parts.length != 2) throw rangeError();
    try {
      long start;
      long end;
      if (parts[0].isBlank()) {
        long suffix = Long.parseLong(parts[1]);
        if (suffix <= 0) throw rangeError();
        start = Math.max(0, total - Math.min(suffix, MAX_RANGE_BYTES));
        end = total - 1;
      } else {
        start = Long.parseLong(parts[0]);
        if (start < 0 || start >= total) throw rangeError();
        long requestedEnd = parts[1].isBlank() ? total - 1 : Long.parseLong(parts[1]);
        end = Math.min(total - 1, requestedEnd);
        end = Math.min(end, start + MAX_RANGE_BYTES - 1);
        if (end < start) throw rangeError();
      }
      return new ByteRange(start, end, true);
    } catch (NumberFormatException ex) {
      throw rangeError();
    }
  }

  private BusinessException rangeError() {
    return new BusinessException("VIDEO_RANGE_INVALID", "视频分段范围无效", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
  }

  private boolean durationClose(BigDecimal stored, BigDecimal supplied) {
    BigDecimal tolerance = stored.multiply(new BigDecimal("0.02")).max(new BigDecimal("2"));
    return stored.subtract(supplied).abs().compareTo(tolerance) <= 0;
  }

  private VideoResponse toResponse(VideoRow video, ProgressRow progress) {
    return new VideoResponse(
        video.id(), video.videoCode(), video.courseId(), video.courseTitle(), video.title(),
        video.category(), video.description(), video.fileName(), video.contentType(), video.fileSize(),
        video.durationSeconds(), video.enabled(), video.sortOrder(), video.createdAt(),
        progress == null ? ZERO : progress.watchedSeconds(),
        progress == null ? ZERO : progress.lastPosition(),
        progress == null ? ZERO : progress.percent(), progress != null && progress.completed());
  }

  private boolean canManage(AuthUser user) {
    return accessControl.hasPermission(user, PermissionCodes.EXAM_MANAGE);
  }

  private String sanitizeFileName(String value) {
    String fileName = value == null ? "" : value.replace("\\", "/");
    fileName = fileName.substring(fileName.lastIndexOf('/') + 1).trim();
    if (fileName.isEmpty()) throw bad("VIDEO_FILE_REQUIRED", "视频文件名不能为空");
    return fileName;
  }

  private String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private String generatedCode() {
    return "VIDEO_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
  }

  private BigDecimal positiveOrNull(BigDecimal value) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? null : amount(value);
  }

  private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
    return value.max(min).min(max);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private BusinessException bad(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException notFound(String code, String message) {
    return new BusinessException(code, message, HttpStatus.NOT_FOUND);
  }

  private void audit(AuthUser user, String action, long targetId, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(
        action, "training_video", Long.toString(targetId), user.storeId(), null, reason, null, null));
  }

  private record VideoFormat(String storedContentType, Set<String> acceptedMimeTypes, boolean webm) {}
  private record ValidatedVideo(String fileName, String contentType) {}
  private record ByteRange(long start, long end, boolean partial) {}
  public record VideoContentResponse(
      String fileName, String contentType, long fileSize, long start, long end,
      boolean partial, byte[] content
  ) {}
}
