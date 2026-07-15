package com.storeprofit.system.importing;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfitImportPreviewJobService {
  private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
  private static final Duration JOB_TTL = Duration.ofMinutes(30);

  private final ProfitImportService profitImportService;
  private final Executor executor;
  private final BusinessScopeResolver businessScopeResolver;
  private final AuditRepository auditRepository;
  private final Map<String, PreviewJob> jobs = new ConcurrentHashMap<>();

  @Autowired
  public ProfitImportPreviewJobService(
      ProfitImportService profitImportService,
      @Qualifier("profitImportExecutor") Executor executor,
      BusinessScopeResolver businessScopeResolver,
      AuditRepository auditRepository
  ) {
    this.profitImportService = profitImportService;
    this.executor = executor;
    this.businessScopeResolver = businessScopeResolver;
    this.auditRepository = auditRepository;
  }

  public ProfitImportPreviewJobService(
      ProfitImportService profitImportService,
      Executor executor
  ) {
    this(profitImportService, executor, null, null);
  }

  public ProfitImportPreviewJobResponse submit(
      AuthUser user,
      MultipartFile file,
      ProfitImportSourceType sourceType,
      String storeId,
      String month
  ) {
    // Check synchronously before allocating a job or scheduling background parsing. The parser
    // repeats the same service-level guard so legacy and asynchronous paths cannot bypass it.
    profitImportService.requireImportAccess(user);
    BusinessScope businessScope = businessScopeResolver == null
        ? new BusinessScope(storeId, null, null, null, null)
        : businessScopeResolver.resolve(
            user, DataScopeDomains.FINANCE, storeId, null, "预览经营数据导入");
    String targetStoreId = businessScope.storeId();
    String selectedMonth = normalizeTargetMonth(month);
    if (targetStoreId == null || targetStoreId.isBlank()) {
      throw new BusinessException("IMPORT_TARGET_STORE_REQUIRED", "请先选择一间门店后再导入", HttpStatus.BAD_REQUEST);
    }
    if (selectedMonth.isBlank()) {
      throw new BusinessException("IMPORT_TARGET_MONTH_REQUIRED", "请先选择月份后再导入", HttpStatus.BAD_REQUEST);
    }
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
    PreviewJob job = new PreviewJob(
        jobId, user.id(), user.tenantId(), targetStoreId, businessScope.storeName(), selectedMonth);
    jobs.put(jobId, job);
    String originalName = file.getOriginalFilename();
    String contentType = file.getContentType();
    executor.execute(() -> parse(
        job, user, bytes, originalName, contentType, sourceType));
    return response(job);
  }

  public ProfitImportPreviewJobResponse get(AuthUser user, String jobId) {
    profitImportService.requireImportAccess(user);
    return response(requireOwned(user, jobId));
  }

  @Transactional
  public ProfitImportCommitResponse confirm(AuthUser user, String jobId, ProfitImportJobConfirmRequest request) {
    profitImportService.requireImportAccess(user);
    PreviewJob job = requireOwned(user, jobId);
    synchronized (job) {
      if (job.commitResponse != null) {
        return job.commitResponse;
      }
      if (!"READY".equals(job.status)) {
        throw new BusinessException("IMPORT_PREVIEW_NOT_READY", "预览尚未完成，暂时不能导入", HttpStatus.CONFLICT);
      }
      if (job.errorRows > 0) {
        throw new BusinessException("IMPORT_PREVIEW_HAS_ERRORS", "预览中仍有错误行，请修正后重新识别", HttpStatus.CONFLICT);
      }
      ensureRowsStayInSelectedScope(user, job);
      ensureExactlyOneTargetSummary(user, job);
      Map<String, Boolean> overwriteByRow = new LinkedHashMap<>();
      if (request != null && request.rows() != null) {
        for (ProfitImportJobConfirmRequest.RowDecision row : request.rows()) {
          if (row != null && row.rowId() != null && !row.rowId().isBlank()) {
            if (!job.rows.stream().anyMatch(jobRow -> row.rowId().equals(jobRow.rowId()))) {
              throw new BusinessException(
                  "IMPORT_CONFIRM_ROW_INVALID", "确认请求包含不属于当前预览任务的记录", HttpStatus.CONFLICT);
            }
            if (overwriteByRow.put(row.rowId(), row.overwrite()) != null) {
              throw new BusinessException(
                  "IMPORT_CONFIRM_ROW_DUPLICATE", "确认请求包含重复的导入记录", HttpStatus.CONFLICT);
            }
          }
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
        writeSuccessfulImportAudits(user, job, overwriteByRow, job.commitResponse);
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
    profitImportService.requireImportAccess(user);
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
      ProfitImportSourceType sourceType
  ) {
    long started = System.nanoTime();
    try {
      update(job, "PARSING", "解析中", 25);
      ProfitImportRecognizeResponse recognized = profitImportService.recognizeForSingleStoreMonthPreview(
          user,
          new InMemoryMultipartFile("file", originalName, contentType, bytes),
          sourceType,
          job.targetStoreId,
          job.selectedMonth);
      if (job.cancelled) return;
      update(job, "VALIDATING", "校验中", 75);
      List<ProfitImportRow> recognizedRows = recognized.rows() == null ? List.of() : recognized.rows();
      job.rows = constrainToSelectedScope(job, recognizedRows);
      job.errors = mergeErrors(recognized.errors(), selectedScopeErrors(job));
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
      if (hasSelectedScopeViolation(job)) {
        writeScopeRejectedAudit(user, job);
      }
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

  /**
   * The data-entry page represents exactly one store and one accounting month.  A file that
   * contains another scope must never turn into an implicit multi-store import merely because
   * its rows were successfully parsed.
   */
  private List<ProfitImportRow> constrainToSelectedScope(PreviewJob job, List<ProfitImportRow> rows) {
    List<ProfitImportRow> constrained = new ArrayList<>();
    for (ProfitImportRow row : rows) {
      ProfitImportRow current = row;
      if (row != null && !job.targetStoreId.equals(row.storeId())) {
        current = withError(current, "当前页面仅可导入" + selectedScopeLabel(job)
            + "，请拆分文件后分别导入");
      }
      if (current != null && !job.selectedMonth.equals(current.month())) {
        current = withError(current, "当前页面仅可导入" + selectedScopeLabel(job)
            + "，请切换对应月份后重新导入");
      }
      constrained.add(current);
    }

    Map<String, Integer> rowCounts = new HashMap<>();
    for (ProfitImportRow row : constrained) {
      if (row != null && job.targetStoreId.equals(row.storeId()) && job.selectedMonth.equals(row.month())) {
        rowCounts.merge(row.storeId() + "\u0000" + row.month(), 1, Integer::sum);
      }
    }
    List<ProfitImportRow> uniqueRows = new ArrayList<>();
    for (ProfitImportRow row : constrained) {
      if (row != null && job.targetStoreId.equals(row.storeId()) && job.selectedMonth.equals(row.month())
          && rowCounts.getOrDefault(row.storeId() + "\u0000" + row.month(), 0) > 1) {
        uniqueRows.add(withError(row, "当前页面仅支持一条" + selectedScopeLabel(job)
            + "月度汇总记录，请合并为一条后重新导入"));
      } else {
        uniqueRows.add(row);
      }
    }
    return List.copyOf(uniqueRows);
  }

  private List<String> selectedScopeErrors(PreviewJob job) {
    long otherStores = job.rows.stream()
        .filter(row -> row != null && !job.targetStoreId.equals(row.storeId()))
        .count();
    long otherMonths = job.rows.stream()
        .filter(row -> row != null && !job.selectedMonth.equals(row.month()))
        .count();
    long selectedRows = job.rows.stream()
        .filter(row -> row != null && job.targetStoreId.equals(row.storeId()) && job.selectedMonth.equals(row.month()))
        .count();
    List<String> errors = new ArrayList<>();
    if (otherStores > 0) {
      errors.add("发现 " + otherStores + " 条其他门店记录；当前页面仅可导入"
          + selectedScopeLabel(job) + "，请拆分文件后分别导入。");
    }
    if (otherMonths > 0) {
      errors.add("发现 " + otherMonths + " 条其他月份记录；当前页面仅可导入"
          + selectedScopeLabel(job) + "，请切换对应月份后重新导入。");
    }
    if (selectedRows > 1) {
      errors.add("文件包含 " + selectedRows + " 条" + selectedScopeLabel(job)
          + "月度汇总记录；请合并为一条后重新导入。");
    }
    return errors;
  }

  private List<String> mergeErrors(List<String> original, List<String> additions) {
    List<String> merged = new ArrayList<>();
    if (original != null) {
      original.stream().filter(value -> value != null && !value.isBlank()).forEach(merged::add);
    }
    additions.stream().filter(value -> !merged.contains(value)).forEach(merged::add);
    return List.copyOf(merged);
  }

  private ProfitImportRow withError(ProfitImportRow row, String error) {
    if (row == null || row.errors().contains(error)) {
      return row;
    }
    List<String> errors = new ArrayList<>(row.errors());
    errors.add(error);
    return new ProfitImportRow(
        row.rowId(), row.storeId(), row.storeName(), row.month(), row.confidence(), row.values(),
        row.warnings(), List.copyOf(errors), row.existing(), "ERROR");
  }

  private void ensureRowsStayInSelectedScope(AuthUser user, PreviewJob job) {
    boolean outOfScope = job.rows.stream().anyMatch(row -> row == null
        || !job.targetStoreId.equals(row.storeId()) || !job.selectedMonth.equals(row.month()));
    if (outOfScope) {
      writeScopeRejectedAudit(user, job, "确认时发现任务行超出已冻结的门店或月份范围");
      throw new BusinessException("IMPORT_SCOPE_MISMATCH", "导入范围与当前门店或月份不一致，请重新识别文件", HttpStatus.CONFLICT);
    }
  }

  private void ensureExactlyOneTargetSummary(AuthUser user, PreviewJob job) {
    if (job.rows.size() != 1) {
      writeScopeRejectedAudit(user, job, "确认时发现任务不是一条目标月度汇总记录");
      throw new BusinessException(
          "IMPORT_TARGET_SUMMARY_COUNT_INVALID",
          "当前页面仅支持一条" + selectedScopeLabel(job) + "月度汇总记录，请合并文件后重新导入",
          HttpStatus.CONFLICT);
    }
  }

  private boolean hasSelectedScopeViolation(PreviewJob job) {
    if (job.rows.size() != 1) {
      return true;
    }
    ProfitImportRow row = job.rows.getFirst();
    return row == null || !job.targetStoreId.equals(row.storeId()) || !job.selectedMonth.equals(row.month());
  }

  private String selectedScopeLabel(PreviewJob job) {
    String storeName = job.targetStoreName == null || job.targetStoreName.isBlank()
        ? job.targetStoreId : job.targetStoreName;
    return "【" + storeName + "·" + job.selectedMonth + "】";
  }

  private void writeScopeRejectedAudit(AuthUser user, PreviewJob job) {
    writeScopeRejectedAudit(user, job, "文件包含当前页面范围以外的门店、月份或重复月度汇总记录");
  }

  private void writeScopeRejectedAudit(AuthUser user, PreviewJob job, String reason) {
    if (auditRepository == null) {
      return;
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        "利润导入范围拒绝",
        "profit_import_preview",
        job.id,
        job.targetStoreId,
        job.selectedMonth,
        reason,
        null,
        null
    ));
  }

  private void writeSuccessfulImportAudits(
      AuthUser user,
      PreviewJob job,
      Map<String, Boolean> overwriteByRow,
      ProfitImportCommitResponse response
  ) {
    if (auditRepository == null || response == null || response.saved() <= 0) {
      return;
    }
    ProfitImportRow row = job.rows.getFirst();
    String targetId = job.targetStoreId + "|" + job.selectedMonth;
    boolean overwrite = row.existing() && Boolean.TRUE.equals(overwriteByRow.get(row.rowId()));
    if (overwrite) {
      auditRepository.writeLog(user, new AuditLogRequest(
          "利润导入覆盖确认",
          "profit_entry",
          targetId,
          job.targetStoreId,
          job.selectedMonth,
          "已明确确认覆盖当前门店当前月份的一条月度汇总记录",
          amountSummary(row.existingValues()),
          amountSummary(row.values())
      ));
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        "利润导入成功",
        "profit_entry",
        targetId,
        job.targetStoreId,
        job.selectedMonth,
        overwrite ? "已覆盖当前门店当前月份的一条月度汇总记录" : "已导入当前门店当前月份的一条月度汇总记录",
        overwrite ? amountSummary(row.existingValues()) : null,
        amountSummary(row.values())
    ));
  }

  private String amountSummary(Map<String, BigDecimal> values) {
    if (values == null || values.isEmpty()) {
      return "{}";
    }
    BigDecimal sales = values.getOrDefault("sales", BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    BigDecimal refund = values.getOrDefault("refund", BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    BigDecimal discount = values.getOrDefault("discount", BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    BigDecimal cost = values.getOrDefault("material", BigDecimal.ZERO)
        .add(values.getOrDefault("packaging", BigDecimal.ZERO))
        .add(values.getOrDefault("loss", BigDecimal.ZERO))
        .add(values.getOrDefault("costOther", BigDecimal.ZERO));
    return "{\"sales\":" + sales + ",\"refund\":" + refund + ",\"discount\":" + discount
        + ",\"cost\":" + cost.setScale(2, RoundingMode.HALF_UP) + "}";
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
        .filter(entry -> rows.stream().filter(java.util.Objects::nonNull)
            .anyMatch(row -> row.values().getOrDefault(entry.getKey(), BigDecimal.ZERO).signum() != 0))
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
          job.detectedMonths, job.monthConflict, job.elapsedMs,
          job.targetStoreId, job.targetStoreName, job.selectedMonth);
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

  private String normalizeTargetMonth(String value) {
    String normalized = trim(value);
    if (normalized.isBlank()) {
      return "";
    }
    try {
      return YearMonth.parse(normalized).toString();
    } catch (RuntimeException ex) {
      throw new BusinessException("IMPORT_TARGET_MONTH_INVALID", "月份格式必须为 YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  private static final class PreviewJob {
    private final String id;
    private final long userId;
    private final long tenantId;
    private final String targetStoreId;
    private final String targetStoreName;
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

    private PreviewJob(
        String id,
        long userId,
        long tenantId,
        String targetStoreId,
        String targetStoreName,
        String selectedMonth
    ) {
      this.id = id;
      this.userId = userId;
      this.tenantId = tenantId;
      this.targetStoreId = targetStoreId;
      this.targetStoreName = targetStoreName;
      this.selectedMonth = selectedMonth;
    }
  }
}
