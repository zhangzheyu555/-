package com.storeprofit.system.audit;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public AuditController(AccessControlService accessControl, AuditRepository auditRepository) {
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  @GetMapping("/logs")
  public ApiResponse<List<OperationLogResponse>> logs(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(defaultValue = "200") int limit
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireAuditRead(user);
    return ApiResponse.ok(auditRepository.logs(user.tenantId(), limit));
  }

  @PostMapping("/logs")
  public ApiResponse<Void> writeLog(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody AuditLogRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireAuditWrite(user);
    auditRepository.writeLog(user, request);
    return ApiResponse.ok();
  }
}
