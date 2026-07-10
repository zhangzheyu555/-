package com.storeprofit.system.eleme;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.Map;
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
  private final AccessControlService accessControl;

  public ElemeController(
      ElemeProperties properties,
      ElemeOrderService orderService,
      AccessControlService accessControl
  ) {
    this.properties = properties;
    this.orderService = orderService;
    this.accessControl = accessControl;
  }

  @GetMapping("/status")
  public ApiResponse<Map<String, Object>> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformAccess(user);
    return ApiResponse.ok(Map.of(
        "configured", properties.isConfigured(),
        "mode", properties.isConfigured() ? "LIVE" : "UNCONFIGURED",
        "shopCount", properties.getShops().size()));
  }

  @GetMapping("/summary")
  public ApiResponse<ElemeSummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(name = "month", required = false) String month,
      @RequestParam(name = "days", defaultValue = "7") int days) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformAccess(user);
    if (month != null && !month.isBlank()) {
      return ApiResponse.ok(orderService.summaryForMonth(month));
    }
    return ApiResponse.ok(orderService.summary(days));
  }

  /** 订单生效(Type=217)/完结(Type=18)消息推送回调；平台要求返回 {"message":"ok"}。 */
  @PostMapping("/message")
  public Map<String, String> message(@RequestBody(required = false) Map<String, Object> payload) {
    // 生产环境：将 orderId 写入队列并再次调 eleme.order.getOrder 补拉（文档步骤 7 第 3 条）。
    return Map.of("message", "ok");
  }
}
