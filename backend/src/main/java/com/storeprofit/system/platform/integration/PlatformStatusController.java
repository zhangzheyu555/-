package com.storeprofit.system.platform.integration;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platforms")
public class PlatformStatusController {
  private final PlatformAdapterRegistry registry;
  private final AccessControlService accessControl;

  public PlatformStatusController(
      PlatformAdapterRegistry registry,
      AccessControlService accessControl
  ) {
    this.registry = registry;
    this.accessControl = accessControl;
  }

  @GetMapping("/status")
  public ApiResponse<List<PlatformAdapterStatus>> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requirePlatformRead(user);
    requireVisiblePlatformScope(user);
    return ApiResponse.ok(registry.statuses());
  }

  /**
   * 适配器状态只包含是否已配置的元数据，不包含密钥或订单数据；
   * 因此任一非 NONE 的有效平台范围均可见。
   */
  private void requireVisiblePlatformScope(AuthUser user) {
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.PLATFORM);
    if (DataScopeModes.NONE.equals(scope.mode())) {
      throw new BusinessException(
          "FORBIDDEN", "当前账号没有可访问的平台数据范围", HttpStatus.FORBIDDEN);
    }
  }
}
