package com.storeprofit.system.qmai;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企迈（Qmai）开放平台接口。
 *
 * <p>/api/qmai/status 接入状态；/api/qmai/summary 营业额汇总；
 * /api/qmai/config GET 读取脱敏配置（网页表单回显）、PUT 保存凭证（需平台管理权限）。
 * 凭证只在后端存取，读取接口绝不回传 openKey 明文。
 */
@RestController
@RequestMapping("/api/qmai")
public class QmaiController {
  private final QmaiConfigService configService;
  private final QmaiOrderService orderService;
  private final QmaiConsoleService consoleService;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;
  private final QmaiOperatingDataService operatingDataService;
  private final QmaiRecipeSnapshotService recipeSnapshotService;

  public QmaiController(
      QmaiConfigService configService,
      QmaiOrderService orderService,
      QmaiConsoleService consoleService,
      AccessControlService accessControl,
      AuditRepository auditRepository,
      QmaiOperatingDataService operatingDataService,
      QmaiRecipeSnapshotService recipeSnapshotService
  ) {
    this.configService = configService;
    this.orderService = orderService;
    this.consoleService = consoleService;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
    this.operatingDataService = operatingDataService;
    this.recipeSnapshotService = recipeSnapshotService;
  }

  @GetMapping("/status")
  public ApiResponse<Map<String, Object>> status(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    return ApiResponse.ok(configService.maskedView(user.tenantId(), brand));
  }

  /** 读取脱敏配置，供网页表单回显；不含任何明文密钥。 */
  @GetMapping("/config")
  public ApiResponse<Map<String, Object>> getConfig(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    return ApiResponse.ok(configService.maskedView(user.tenantId(), brand));
  }

  /** 保存网页表单提交的企迈凭证（upsert）。openKey/后台密码留空表示不修改已存值。 */
  @PutMapping("/config")
  public ApiResponse<Map<String, Object>> saveConfig(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestBody QmaiConfigService.QmaiConfigForm form
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    configService.save(user.tenantId(), brand, form, user.id(), user.displayName());
    auditRepository.writeLog(user, new AuditLogRequest(
        "保存企迈平台配置", "qmai_platform_config", QmaiConfigService.normBrand(brand), null, null,
        "企迈配置已安全保存（凭证仅记录是否更新，不记录明文）", null, null));
    return ApiResponse.ok(configService.maskedView(user.tenantId(), brand));
  }

  /** 诊断：用当前凭证探测授权门店列表，验证签名并发现门店编码（需平台管理权限）。 */
  @GetMapping("/probe-shops")
  public ApiResponse<Map<String, Object>> probeShops(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    return ApiResponse.ok(orderService.probeShops(user.tenantId(), brand));
  }

  /** 诊断：探测任意企迈接口路径，验证权限并发现营业额接口（需平台管理权限）。 */
  @PostMapping("/probe")
  public ApiResponse<Map<String, Object>> probe(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestBody Map<String, Object> req
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    String path = String.valueOf(req.getOrDefault("path", "")).trim();
    Object params = req.get("params");
    @SuppressWarnings("unchecked")
    Map<String, Object> bizParams = params instanceof Map ? (Map<String, Object>) params : null;
    int signMode = req.get("signMode") instanceof Number n ? n.intValue() : 0;
    return ApiResponse.ok(orderService.probe(user.tenantId(), brand, path, bizParams, signMode));
  }

  /** 诊断：用后台登录令牌探测 console 网关接口，定位营业额报表接口（需平台管理权限）。 */
  @PostMapping("/console-probe")
  public ApiResponse<Map<String, Object>> consoleProbe(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestBody Map<String, Object> req
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    String path = String.valueOf(req.getOrDefault("path", "")).trim();
    Object params = req.get("params");
    @SuppressWarnings("unchecked")
    Map<String, Object> body = params instanceof Map ? (Map<String, Object>) params : null;
    return ApiResponse.ok(consoleService.probe(user.tenantId(), brand, path, body));
  }

  /** 令牌复用通道：从企迈商户后台拉营业收入（按支付渠道），按自然月。 */
  @GetMapping("/console-income")
  public ApiResponse<QmaiConsoleIncomeResponse> consoleIncome(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    String m = month == null || month.isBlank()
        ? java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
        : month;
    return ApiResponse.ok(consoleService.incomeForMonth(user.tenantId(), brand, m));
  }

  @GetMapping("/summary")
  public ApiResponse<QmaiSummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "days", defaultValue = "7") int days) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    Collection<String> allowedStoreIds = platformStoreScope(user);
    if (month != null && !month.isBlank()) {
      return ApiResponse.ok(
          orderService.summaryForMonth(user.tenantId(), brand, month, allowedStoreIds));
    }
    return ApiResponse.ok(orderService.summary(user.tenantId(), brand, days, allowedStoreIds));
  }

  /** Imported monthly revenue snapshots; reads only local, tenant-scoped QMAI data. */
  @GetMapping("/revenue")
  public ApiResponse<List<QmaiOperatingDataRepository.RevenueRow>> revenue(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "storeId", required = false) String storeId
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireQmaiRead(user);
    String safeMonth = operatingDataService.month(month);
    List<QmaiOperatingDataRepository.RevenueRow> rows = operatingDataService.revenue(
        user.tenantId(), brand, safeMonth, qmaiStoreScope(user, storeId));
    auditRepository.writeLog(user, new AuditLogRequest("查询企迈营业额", "qmai_revenue", QmaiConfigService.normBrand(brand),
        storeId, safeMonth, "读取本地企迈营业额快照，行数=" + rows.size(), null, null));
    return ApiResponse.ok(rows);
  }

  @GetMapping("/revenue.csv")
  public ResponseEntity<byte[]> revenueCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "storeId", required = false) String storeId
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireQmaiRead(user);
    String safeMonth = operatingDataService.month(month);
    List<QmaiOperatingDataRepository.RevenueRow> rows = operatingDataService.revenue(
        user.tenantId(), brand, safeMonth, qmaiStoreScope(user, storeId));
    auditRepository.writeLog(user, new AuditLogRequest("导出企迈营业额", "qmai_export", "revenue",
        storeId, safeMonth, "已导出本地企迈营业额 CSV，行数=" + rows.size(), null, null));
    return csv("企迈营业额-" + safeMonth + ".csv", revenueCsv(rows));
  }

  @GetMapping("/products")
  public ApiResponse<List<QmaiOperatingDataRepository.ProductRow>> products(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "storeId", required = false) String storeId
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireQmaiRead(user);
    String safeMonth = operatingDataService.month(month);
    List<QmaiOperatingDataRepository.ProductRow> rows = operatingDataService.products(
        user.tenantId(), brand, safeMonth, qmaiStoreScope(user, storeId));
    auditRepository.writeLog(user, new AuditLogRequest("查询企迈商品销量", "qmai_product_sales", QmaiConfigService.normBrand(brand),
        storeId, safeMonth, "读取本地企迈商品销量快照，行数=" + rows.size(), null, null));
    return ApiResponse.ok(rows);
  }

  @GetMapping("/products.csv")
  public ResponseEntity<byte[]> productsCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "storeId", required = false) String storeId
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireQmaiRead(user);
    String safeMonth = operatingDataService.month(month);
    List<QmaiOperatingDataRepository.ProductRow> rows = operatingDataService.products(
        user.tenantId(), brand, safeMonth, qmaiStoreScope(user, storeId));
    auditRepository.writeLog(user, new AuditLogRequest("导出企迈商品销量", "qmai_export", "products",
        storeId, safeMonth, "已导出本地企迈商品销量 CSV，行数=" + rows.size(), null, null));
    return csv("企迈商品销量-" + safeMonth + ".csv", productsCsv(rows));
  }

  /** Server-owned recipe catalog + local sales produce a read-only monthly material snapshot. */
  @GetMapping("/recipe-usage")
  public ApiResponse<QmaiRecipeSnapshotService.Snapshot> recipeUsage(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "storeId", required = false) String storeId
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireQmaiRecipeRead(user);
    String safeMonth = operatingDataService.month(month);
    QmaiRecipeSnapshotService.Snapshot snapshot = recipeSnapshotService.monthly(
        user.tenantId(), brand, safeMonth, qmaiRecipeStoreScope(user, storeId));
    auditRepository.writeLog(user, new AuditLogRequest("查询企迈配方用量", "qmai_recipe_snapshot", "recipe-usage",
        storeId, safeMonth, "读取服务端配方快照，匹配商品=" + snapshot.matchedProductCount(), null, null));
    return ApiResponse.ok(snapshot);
  }

  /** Downloads the same server-owned recipe snapshot; caller-supplied grams/factors are never accepted. */
  @GetMapping("/recipe-usage.csv")
  public ResponseEntity<byte[]> recipeUsageCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "brand", required = false) String brand,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "storeId", required = false) String storeId
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireQmaiRecipeRead(user);
    String safeMonth = operatingDataService.month(month);
    QmaiRecipeSnapshotService.Snapshot snapshot = recipeSnapshotService.monthly(
        user.tenantId(), brand, safeMonth, qmaiRecipeStoreScope(user, storeId));
    auditRepository.writeLog(user, new AuditLogRequest("导出企迈配方用量", "qmai_export", "recipe-usage",
        storeId, safeMonth, "已导出服务端配方快照，匹配商品=" + snapshot.matchedProductCount(), null, null));
    StringBuilder content = new StringBuilder("水果,配方净用量(克),折算毛重(克),折算采购毛重(斤),备注\\r\\n");
    for (QmaiRecipeCalculationService.FruitUsage row : snapshot.calculation().fruits()) {
      content.append(csvValue(row.fruit())).append(',').append(row.netGrams()).append(',')
          .append(row.rawGrams()).append(',').append(row.rawJin()).append(',')
          .append(csvValue(row.approximate() ? "无出肉率，按 1:1 折算" : "")).append("\\r\\n");
    }
    return csv("企迈配方用量-" + safeMonth + ".csv", content.toString());
  }

  /** null 只表示 ALL；受限模式始终返回明确门店集合。 */
  private Collection<String> platformStoreScope(AuthUser user) {
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.PLATFORM);
    if (DataScopeModes.ALL.equals(scope.mode())) {
      return null;
    }
    if ((DataScopeModes.STORE_LIST.equals(scope.mode())
        || DataScopeModes.OWN_STORE.equals(scope.mode()))
        && !scope.storeIds().isEmpty()) {
      return List.copyOf(scope.storeIds());
    }
    throw new BusinessException(
        "FORBIDDEN", "当前账号没有可访问的平台门店范围", HttpStatus.FORBIDDEN);
  }

  private Collection<String> qmaiStoreScope(AuthUser user, String requestedStoreId) {
    Collection<String> allowed = platformStoreScope(user);
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      return allowed;
    }
    String requested = requestedStoreId.trim();
    if (allowed != null && !allowed.contains(requested)) {
      auditRepository.writePermissionDenied(user, "访问未授权企迈门店", "qmai_store", requested, requested,
          "门店不在当前账号的平台数据范围内");
      throw new BusinessException("FORBIDDEN", "当前账号无权访问该企迈门店数据", HttpStatus.FORBIDDEN);
    }
    return List.of(requested);
  }

  private Collection<String> qmaiRecipeStoreScope(AuthUser user, String requestedStoreId) {
    DataScope scope = AccessControlService.hasAnyRole(user, "WAREHOUSE")
        ? accessControl.dataScope(user, DataScopeDomains.WAREHOUSE)
        : accessControl.dataScope(user, DataScopeDomains.PLATFORM);
    Collection<String> allowed;
    if (DataScopeModes.ALL.equals(scope.mode())) {
      allowed = null;
    } else if ((DataScopeModes.STORE_LIST.equals(scope.mode()) || DataScopeModes.OWN_STORE.equals(scope.mode()))
        && !scope.storeIds().isEmpty()) {
      allowed = List.copyOf(scope.storeIds());
    } else {
      throw new BusinessException("FORBIDDEN", "当前账号没有可访问的配方门店范围", HttpStatus.FORBIDDEN);
    }
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      return allowed;
    }
    String requested = requestedStoreId.trim();
    if (allowed != null && !allowed.contains(requested)) {
      auditRepository.writePermissionDenied(user, "访问未授权配方门店", "qmai_store", requested, requested,
          "门店不在当前账号的数据范围内");
      throw new BusinessException("FORBIDDEN", "当前账号无权访问该配方门店数据", HttpStatus.FORBIDDEN);
    }
    return List.of(requested);
  }

  private ResponseEntity<byte[]> csv(String fileName, String content) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8))
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(("\uFEFF" + content).getBytes(StandardCharsets.UTF_8));
  }

  private String revenueCsv(List<QmaiOperatingDataRepository.RevenueRow> rows) {
    StringBuilder csv = new StringBuilder("门店,订单数,营业额,退款金额,成本金额\\r\\n");
    for (QmaiOperatingDataRepository.RevenueRow row : rows) {
      csv.append(csvValue(row.storeId())).append(',').append(row.orderCount()).append(',')
          .append(row.revenue()).append(',').append(row.refund()).append(',').append(row.cost()).append("\\r\\n");
    }
    return csv.toString();
  }

  private String productsCsv(List<QmaiOperatingDataRepository.ProductRow> rows) {
    StringBuilder csv = new StringBuilder("门店,商品,分类,销量,退款销量,营业额,退款金额\\r\\n");
    for (QmaiOperatingDataRepository.ProductRow row : rows) {
      csv.append(csvValue(row.storeId())).append(',').append(csvValue(row.itemName())).append(',')
          .append(csvValue(row.categoryName())).append(',').append(row.quantity()).append(',')
          .append(row.refundQuantity()).append(',').append(row.revenue()).append(',').append(row.refund()).append("\\r\\n");
    }
    return csv.toString();
  }

  private String csvValue(String value) {
    String safe = value == null ? "" : value.replace("\"", "\"\"");
    if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    return "\"" + safe + "\"";
  }
}
