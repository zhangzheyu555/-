package com.storeprofit.system.organization;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/brands")
public class BrandController {
  private final AuthService authService;
  private final OrganizationService organizationService;

  public BrandController(AuthService authService, OrganizationService organizationService) {
    this.authService = authService;
    this.organizationService = organizationService;
  }

  @GetMapping
  public ApiResponse<List<BrandResponse>> list(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(organizationService.brands(authService.requireUser(authorization)));
  }
}
