package com.storeprofit.system.qmai;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
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

@RestController
@RequestMapping("/api/qmai")
public class QmaiController {
  private final AccessControlService accessControl;
  private final QmaiConfigService configService;
  private final QmaiBusinessService businessService;
  private final AuditRepository auditRepository;

  public QmaiController(AccessControlService accessControl, QmaiConfigService configService,
      QmaiBusinessService businessService, AuditRepository auditRepository) {
    this.accessControl = accessControl;
    this.configService = configService;
    this.businessService = businessService;
    this.auditRepository = auditRepository;
  }

  @GetMapping("/status")
  public ApiResponse<QmaiModels.ConfigResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    return ApiResponse.ok(configService.view(user.tenantId()));
  }

  @GetMapping("/config")
  public ApiResponse<QmaiModels.ConfigResponse> config(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    return ApiResponse.ok(configService.view(user.tenantId()));
  }

  @PutMapping("/config")
  public ApiResponse<QmaiModels.ConfigResponse> saveConfig(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody QmaiModels.ConfigRequest request) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    return ApiResponse.ok(configService.save(user, request));
  }

  @GetMapping("/shops")
  public ApiResponse<List<QmaiModels.DiscoveredShop>> discoverShops(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformManage(user);
    return ApiResponse.ok(configService.discover(user));
  }

  @PostMapping("/sync")
  public ApiResponse<QmaiModels.BatchResponse> sync(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(businessService.startSync(user, month));
  }

  @GetMapping("/summary")
  public ApiResponse<QmaiModels.SummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) String storeId) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(businessService.summary(user, month, storeId));
  }

  @GetMapping("/export.csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) String storeId,
      @RequestParam(defaultValue = "stores") String view) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireDataExport(user);
    QmaiModels.SummaryResponse summary = businessService.summary(user, month, storeId);
    boolean products = "products".equalsIgnoreCase(view);
    String content = products ? productCsv(summary) : storeCsv(summary);
    String fileName = "企迈" + (products ? "商品销售" : "营业额") + "-" + summary.month() + ".csv";
    auditRepository.writeLog(user, new AuditLogRequest(
        "导出企迈" + (products ? "商品销售" : "营业额"), "qmai_data_export", view,
        storeId, summary.month(), "已下载 CSV 文件", null, null));
    byte[] bytes = ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(bytes);
  }

  private String storeCsv(QmaiModels.SummaryResponse summary) {
    StringBuilder csv = new StringBuilder("月份,门店,有效天数,源数据行数,应收,实收,成本,退款,毛利,毛利率\r\n");
    for (QmaiModels.StoreSummary row : summary.stores()) {
      csv.append(escape(summary.month())).append(',').append(escape(row.storeName())).append(',')
          .append(row.activeDays()).append(',').append(row.sourceRows()).append(',')
          .append(row.receivable()).append(',').append(row.received()).append(',')
          .append(row.cost()).append(',').append(row.refund()).append(',')
          .append(row.grossProfit()).append(',').append(row.grossMargin().movePointRight(2)).append("%\r\n");
    }
    return csv.toString();
  }

  private String productCsv(QmaiModels.SummaryResponse summary) {
    StringBuilder csv = new StringBuilder(
        "月份,门店,商品ID,SKU ID,商品名称,企迈分类,销量,退款数量,应收,实收,成本,退款\r\n");
    for (QmaiModels.ProductSummary row : summary.products()) {
      csv.append(escape(summary.month())).append(',').append(escape(row.storeName())).append(',')
          .append(escape(row.productId())).append(',').append(escape(row.skuId())).append(',')
          .append(escape(row.itemName())).append(',').append(escape(row.categoryName())).append(',')
          .append(row.quantity()).append(',').append(row.refundQuantity()).append(',')
          .append(row.receivable()).append(',').append(row.received()).append(',')
          .append(row.cost()).append(',').append(row.refund()).append("\r\n");
    }
    return csv.toString();
  }

  private String escape(String value) {
    return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
  }
}
