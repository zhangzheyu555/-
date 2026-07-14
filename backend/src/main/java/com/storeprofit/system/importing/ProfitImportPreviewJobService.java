package com.storeprofit.system.importing;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfitImportPreviewJobService {
  private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
  private static final Duration JOB_TTL = Duration.ofMinutes(30);

  private final ProfitImportService profitImportService;
  private final Executor executor;
  private final BusinessScopeResolver businessScopeResolver;
  private final Map<String, PreviewJob> jobs = new ConcurrentHashMap<>();

  @Autowired
  public ProfitImportPreviewJobService(
      ProfitImportService profitImportService,
      @Qualifier("profitImportExecutor") Executor executor,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.profitImportService = profitImportService;
    this.executor = executor;
    this.businessScopeResolver = businessScopeResolver;
  }

  public ProfitImportPreviewJobService(
      ProfitImportService profitImportService,
      Executor executor
  ) {
    this(profitImportService, executor, null);
  }

  public ProfitImportPreviewJobResponse submit(
      AuthUser user,
      MultipartFile file,
      ProfitImportSourceType sourceType,
      String storeId,
      String month
  ) {
    BusinessScope businessScope = businessScopeResolver == null
        ? new BusinessScope(storeId, null, null, null, null)
        : businessScopeResolver.resolve(
            user, DataScopeDomains.FINANCE, storeId, null, "预览经营数据导入");
    String targetStoreId = businessScope.storeId();
    if (file == null || file.isEmpty()) {
      throw new BusinessException("IMPORT_FILE_EMPTY", "请先选择要导入的文件", HttpStatus.BAD_REQUEST);
    }
    if (file.getSize() > MAX_FILE_BYTES) {
      throw new BusinessException("IMPORT_FILE_TOO_LARGE", "导入文件不能超过 10MB", HttpStatus.BAD_REQUEST);
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw new BusinessException("IMPORT_READ_FAILED", "读取上传文件失败，请重新选择文件", HttpStatus.BAD_REQUEST);
    }
    String jobId = "profit_" + UUID.randomUUID();
    PreviewJob job = new PreviewJob(jobId, user.id(), user.tenantId(), trim(month));
    jobs.put(jobId, job);
    String originalName = file.getOriginalFilename();
    String contentType = file.getContentType();
    executor.execute(() -> parse(
        job, user, bytes, originalName, contentType, sourceType, targetStoreId, month));
    return response(job);
  }

  public ProfitImportPreviewJobResponse get(AuthUser user, String jobId) {
    return response(requireOwned(user, jobId));
  }

  public ProfitImportCommitResponse confirm(AuthUser user, String jobId, ProfitImportJobConfirmRequest request) {
    PreviewJob job = requireOwned(user, jobId);
    synchronized (job) {
      if (job.commitResponse != null) {
        return job.commitResponse;
      }
      if (!"READY".equals(job.status)) {
        throw new BusinessException("IMPORT_PREVIEW_NOT_READY", "预览尚未完成，暂时不能导入", HttpStatus.CONFLICT);
      }
      if (job.monthConflict && (request == null || !request.confirmMonthConflict())) {
        throw new BusinessException(
            "IMPORT_MONTH_CONFLICT",
            "文件识别月份与当前选择不一致，请先确认使用文件月份",
            HttpStatus.CONFLICT);
      }
      if (job.errorRows > 0) {
        throw new BusinessException("IMPORT_PREVIEW_HAS_ERRORS", "预览中仍有错误行，请修正后重新识别", HttpStatus.CONFLICT);
      }
      Map<String, Boolean> overwriteByRow = new LinkedHashMap<>();
      if (request != null && request.rows() != null) {
        for (ProfitImportJobConfirmRequest.RowDecision row : request.rows()) {
          if (row != null && row.rowId() != null) overwriteByRow.put(row.rowId(), row.overwrite());
        }
      }
      List<ProfitImportCommitRequest.Row> rows = new ArrayList<>();
      for (ProfitImportRow row : job.rows) {
        boolean overwrite = overwriteByRow.getOrDefault(row.rowId(), false);
        if (row.existing() && !overwrite) {
          throw new BusinessException(
              "IMPORT_OVERWRITE_NOT_CONFIRMED",
              row.storeName() + " " + row.month() + " 已有数据，请先确认覆盖",
              HttpStatus.CONFLICT);
        }
        rows.add(new ProfitImportCommitRequest.Row(
            row.rowId(), row.storeId(), row.month(), overwrite, row.values(), "Excel/CSV 导入"));
      }
      job.status = "CONFIRMING";
      job.stage = "导入中";
      job.progress = 95;
      try {
        job.commitResponse = profitImportService.commit(user, new ProfitImportCommitRequest(rows));
        job.status = "COMPLETED";
        job.stage = "导入成功";
        job.progress = 100;
        job.updatedAt = Instant.now();
        return job.commitResponse;
      } catch (RuntimeException ex) {
        job.status = "READY";
        job.stage = "等待确认";
        job.progress = 100;
        throw ex;
      }
    }
  }

  public void cancel(AuthUser user, String jobId) {
    PreviewJob job = requireOwned(user, jobId);
    synchronized (job) {
      if ("COMPLETED".equals(job.status)) {
        throw new BusinessException("IMPORT_ALREADY_COMPLETED", "该导入任务已经完成，不能取消", HttpStatus.CONFLICT);
      }
      job.cancelled = true;
      job.status = "CANCELLED";
      job.stage = "已取消";
      job.progress = 0;
      job.updatedAt = Instant.now();
    }
  }

  private void parse(
      PreviewJob job,
      AuthUser user,
      byte[] bytes,
      String originalName,
      String contentType,
      ProfitImportSourceType sourceType,
      String storeId,
      String month
  ) {
    long started = System.nanoTime();
    try {
      update(job, "PARSING", "解析中", 25);
      ProfitImportRecognizeResponse recognized = profitImportService.recognize(
          user,
          new InMemoryMultipartFile("file", originalName, contentType, bytes),
          sourceType,
          storeId,
          month);
      if (job.cancelled) return;
      update(job, "VALIDATING", "校验中", 75);
      job.rows = recognized.rows() == null ? List.of() : List.copyOf(recognized.rows());
      job.errors = recognized.errors() == null ? List.of() : List.copyOf(recognized.errors());
      job.parsedRows = job.rows.size();
      job.validRows = (int) job.rows.stream().filter(this::valid).count();
      job.errorRows = job.parsedRows - job.validRows;
      job.salesTotal = job.rows.stream()
          .map(row -> row.values().getOrDefault("sales", BigDecimal.ZERO))
          .reduce(BigDecimal.ZERO, BigDecimal::add)
          .setScale(2, RoundingMode.HALF_UP);
      job.detectedMonths = job.rows.stream().map(ProfitImportRow::month)
          .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
      job.monthConflict = !job.selectedMonth.isBlank()
          && job.detectedMonths.stream().anyMatch(value -> !job.selectedMonth.equals(value));
      job.fieldMappings = fieldMappings(job.rows);
      job.elapsedMs = elapsedMs(started);
      if ("ERROR".equals(recognized.status()) || job.validRows == 0) {
        update(job, "FAILED", "解析失败", 100);
      } else if (job.errorRows > 0) {
        update(job, "PARTIAL", "校验未通过", 100);
      } else {
        update(job, "READY", "等待确认", 100);
      }
    } catch (BusinessException ex) {
      fail(job, ex.getMessage(), started);
    } catch (RuntimeException ex) {
      fail(job, "文件解析失败，请检查文件格式后重试", started);
    }
  }

  private boolean valid(ProfitImportRow row) {
    return row != null && row.errors().isEmpty() && row.storeId() != null && !row.storeId().isBlank()
        && row.month() != null && !row.month().isBlank();
  }

  private List<String> fieldMappings(List<ProfitImportRow> rows) {
    Map<String, String> names = Map.ofEntries(
        Map.entry("sales", "营业额/日营业额合计 → 营业额"),
        Map.entry("refund", "退款金额 → 退款金额"),
        Map.entry("discount", "优惠金额 → 优惠金额"),
        Map.entry("material", "原材料成本/物料成本 → 原材料成本"),
        Map.entry("packaging", "包材成本/包装成本 → 包材成本"),
        Map.entry("loss", "损耗成本 → 损耗成本"),
        Map.entry("rent", "房租/租金 → 房租"),
        Map.entry("labor", "人工工资/人工成本 → 人工工资"),
        Map.entry("utility", "水电费 → 水电费"),
        Map.entry("property", "物业费 → 物业费"),
        Map.entry("commission", "平台佣金 → 平台佣金"),
        Map.entry("promo", "推广费/营销费 → 推广费"),
        Map.entry("repair", "维修费 → 维修费"),
        Map.entry("equip", "设备费 → 设备费"),
        Map.entry("expOther", "其他费用 → 其他费用"));
    return names.entrySet().stream()
        .filter(entry -> rows.stream().anyMatch(row -> row.values().getOrDefault(entry.getKey(), BigDecimal.ZERO).signum() != 0))
        .map(Map.Entry::getValue)
        .toList();
  }

  private void fail(PreviewJob job, String message, long started) {
    job.errors = List.of(message == null || message.isBlank() ? "文件解析失败，请重试" : message);
    job.elapsedMs = elapsedMs(started);
    update(job, "FAILED", "解析失败", 100);
  }

  private void update(PreviewJob job, String status, String stage, int progress) {
    synchronized (job) {
      if (job.cancelled) return;
      job.status = status;
      job.stage = stage;
      job.progress = progress;
      job.updatedAt = Instant.now();
    }
  }

  private PreviewJob requireOwned(AuthUser user, String jobId) {
    PreviewJob job = jobs.get(jobId);
    if (job == null || job.tenantId != user.tenantId() || job.userId != user.id()) {
      throw new BusinessException("IMPORT_JOB_NOT_FOUND", "导入预览任务不存在或已过期", HttpStatus.NOT_FOUND);
    }
    return job;
  }

  private ProfitImportPreviewJobResponse response(PreviewJob job) {
    synchronized (job) {
      return new ProfitImportPreviewJobResponse(
          job.id, job.status, job.stage, job.progress, job.parsedRows, job.validRows, job.errorRows,
          job.salesTotal, job.fieldMappings, job.rows, job.errors, job.selectedMonth,
          job.detectedMonths, job.monthConflict, job.elapsedMs);
    }
  }

  @Scheduled(fixedDelay = 300_000L)
  public void cleanupExpired() {
    Instant cutoff = Instant.now().minus(JOB_TTL);
    jobs.entrySet().removeIf(entry -> entry.getValue().updatedAt.isBefore(cutoff));
  }

  private long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static final class PreviewJob {
    private final String id;
    private final long userId;
    private final long tenantId;
    private final String selectedMonth;
    private volatile String status = "QUEUED";
    private volatile String stage = "上传完成，等待解析";
    private volatile int progress = 10;
    private volatile int parsedRows;
    private volatile int validRows;
    private volatile int errorRows;
    private volatile BigDecimal salesTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private volatile List<String> fieldMappings = List.of();
    private volatile List<ProfitImportRow> rows = List.of();
    private volatile List<String> errors = List.of();
    private volatile List<String> detectedMonths = List.of();
    private volatile boolean monthConflict;
    private volatile boolean cancelled;
    private volatile long elapsedMs;
    private volatile Instant updatedAt = Instant.now();
    private volatile ProfitImportCommitResponse commitResponse;

    private PreviewJob(String id, long userId, long tenantId, String selectedMonth) {
      this.id = id;
      this.userId = userId;
      this.tenantId = tenantId;
      this.selectedMonth = selectedMonth;
    }
  }
}
