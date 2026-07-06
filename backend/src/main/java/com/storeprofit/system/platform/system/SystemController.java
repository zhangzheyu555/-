package com.storeprofit.system.platform.system;

import com.storeprofit.system.common.ApiResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final Environment environment;
  private final String version;

  public SystemController(Environment environment, @Value("${app.version:0.1.0}") String version) {
    this.environment = environment;
    this.version = version;
  }

  @GetMapping("/overview")
  public ApiResponse<SystemOverview> overview() {
    String[] profiles = environment.getActiveProfiles();
    String activeProfile = profiles.length == 0 ? "default" : String.join(",", profiles);
    return ApiResponse.ok(new SystemOverview(
        "门店利润系统",
        version,
        activeProfile,
        List.of("经营驾驶舱", "数据中心", "门店运营", "系统管理")
    ));
  }
}
