package com.storeprofit.system.organization;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class StoreController {
  private final AuthService authService;
  private final OrganizationService organizationService;

  public StoreController(AuthService authService, OrganizationService organizationService) {
    this.authService = authService;
    this.organizationService = organizationService;
  }

  @GetMapping
  public ApiResponse<List<StoreResponse>> list(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(organizationService.stores(authService.requireUser(authorization)));
  }

  @PostMapping
  public ApiResponse<Void> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody StoreUpsertRequest request
  ) {
    organizationService.upsertStore(authService.requireUser(authorization), request);
    return ApiResponse.ok();
  }

  @PutMapping
  public ApiResponse<Void> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody StoreUpsertRequest request
  ) {
    organizationService.upsertStore(authService.requireUser(authorization), request);
    return ApiResponse.ok();
  }
}
