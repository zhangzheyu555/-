package com.storeprofit.system.inspection;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inspection/standards")
public class InspectionStandardController {
  private final AccessControlService accessControl;
  private final InspectionStandardService standardService;

  public InspectionStandardController(
      AccessControlService accessControl,
      InspectionStandardService standardService
  ) {
    this.accessControl = accessControl;
    this.standardService = standardService;
  }

  @GetMapping
  public ApiResponse<InspectionStandardResponse> activeStandard(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionRead(user);
    return ApiResponse.ok(standardService.activeStandard(user));
  }
}
