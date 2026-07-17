package com.storeprofit.system.qmai;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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

  public QmaiController(
      QmaiConfigService configService,
      QmaiOrderService orderService,
      QmaiConsoleService consoleService,
      AccessControlService accessControl
  ) {
    this.configService = configService;
    this.orderService = orderService;
    this.consoleService = consoleService;
    this.accessControl = accessControl;
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
}
