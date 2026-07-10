package com.storeprofit.system.platform.system;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final AuthService authService;
  private final Environment environment;
  private final String version;
  private final Instant startupTime = Instant.now();

  public SystemController(
      AuthService authService,
      Environment environment,
      @Value("${app.version:0.1.0}") String version
  ) {
    this.authService = authService;
    this.environment = environment;
    this.version = version;
  }

  @GetMapping("/overview")
  public ApiResponse<SystemOverview> overview(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    authService.requireUser(authorization);
    String[] profiles = environment.getActiveProfiles();
    String activeProfile = profiles.length == 0 ? "default" : String.join(",", profiles);
    return ApiResponse.ok(new SystemOverview(
        "门店利润系统",
        version,
        activeProfile,
        List.of("经营驾驶舱", "数据中心", "门店运营", "系统管理")
    ));
  }

  @GetMapping("/version")
  public ApiResponse<VersionInfo> version() {
    return ApiResponse.ok(new VersionInfo(version, startupTime.toString()));
  }

  public record VersionInfo(String version, String startupTime) {}
}
