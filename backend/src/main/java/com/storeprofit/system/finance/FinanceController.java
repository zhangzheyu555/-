package com.storeprofit.system.finance;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {
  private final AuthService authService;
  private final FinanceService financeService;

  public FinanceController(AuthService authService, FinanceService financeService) {
    this.authService = authService;
    this.financeService = financeService;
  }

  @GetMapping("/months")
  public ApiResponse<List<String>> months(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    authService.requireUser(authorization);
    return ApiResponse.ok(financeService.months());
  }

  @GetMapping("/dashboard")
  public ApiResponse<ProfitDashboardResponse> dashboard(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId
  ) {
    return ApiResponse.ok(financeService.dashboard(authService.requireUser(authorization), month, brandId));
  }

  @GetMapping("/entries")
  public ApiResponse<List<ProfitEntryResponse>> entries(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(financeService.entries(authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/entries/detail")
  public ApiResponse<ProfitEntryResponse> entry(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String storeId,
      @RequestParam String month
  ) {
    return ApiResponse.ok(financeService.entry(authService.requireUser(authorization), storeId, month));
  }

  @PutMapping("/entries")
  public ApiResponse<Void> save(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody ProfitEntryRequest request
  ) {
    financeService.save(authService.requireUser(authorization), request);
    return ApiResponse.ok();
  }
}
