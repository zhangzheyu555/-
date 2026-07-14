package com.storeprofit.system.eleme;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.integration.PlatformAdapterStatus;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 饿了么开放平台订单营业额接口。
 *
 * <p>/api/eleme/status  返回接入配置状态；
 * /api/eleme/summary  返回按门店 + 日期聚合的订单营业额（文档步骤 3、4）。
 * /api/eleme/message  订单消息推送接收端（文档步骤 7，正式环境需 HTTPS，返回 {"message":"ok"}）。
 */
@RestController
@RequestMapping("/api/eleme")
public class ElemeController {
  private final ElemeProperties properties;
  private final ElemeOrderService orderService;
  private final ElemePlatformAdapter platformAdapter;
  private final ElemeWebhookService webhookService;
  private final AccessControlService accessControl;

  public ElemeController(
      ElemeProperties properties,
      ElemeOrderService orderService,
      ElemePlatformAdapter platformAdapter,
      ElemeWebhookService webhookService,
      AccessControlService accessControl
  ) {
    this.properties = properties;
    this.orderService = orderService;
    this.platformAdapter = platformAdapter;
    this.webhookService = webhookService;
    this.accessControl = accessControl;
  }

  @GetMapping("/status")
  public ApiResponse<Map<String, Object>> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    Collection<String> allowedStoreIds = platformStoreScope(user);
    PlatformAdapterStatus adapterStatus = platformAdapter.status();
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("configured", properties.isConfigured());
    status.put("mode", properties.isConfigured() ? "LIVE" : "UNCONFIGURED");
    status.put("shopCount", properties.shopCount(allowedStoreIds));
    status.put("orderSyncStatus", adapterStatus.orderSync());
    status.put("webhookConfigured", properties.isWebhookConfigured());
    status.put("webhookStatus", adapterStatus.webhook());
    status.put("message", adapterStatus.message());
    return ApiResponse.ok(status);
  }

  @GetMapping("/summary")
  public ApiResponse<ElemeSummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "days", defaultValue = "7") int days) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    Collection<String> allowedStoreIds = platformStoreScope(user);
    if (month != null && !month.isBlank()) {
      return ApiResponse.ok(orderService.summaryForMonth(month, allowedStoreIds));
    }
    return ApiResponse.ok(orderService.summary(days, allowedStoreIds));
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

  /**
   * 订单消息推送回调。只有原始请求体验签、事件编号和 JSON 校验均通过且事件已持久化后，
   * 才返回兼容的 {"message":"ok"}；重复事件安全确认但不会重复创建业务数据。
   */
  @PostMapping("/message")
  public ElemeWebhookReceipt message(
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) byte[] rawBody
  ) {
    return webhookService.receive(
        rawBody,
        headers.getFirst(properties.getWebhookSignatureHeader()),
        headers.getFirst(properties.getWebhookEventIdHeader())
    );
  }
}
