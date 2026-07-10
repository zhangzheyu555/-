package com.storeprofit.system.health;

import com.storeprofit.system.common.ApiResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
  @GetMapping
  public ApiResponse<Map<String, Object>> health() {
    return ApiResponse.ok(Map.of(
        "status", "UP",
        "time", OffsetDateTime.now().toString()
    ));
  }
}
