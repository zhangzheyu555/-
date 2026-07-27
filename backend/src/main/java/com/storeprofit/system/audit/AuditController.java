package com.storeprofit.system.audit;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
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

  @GetMapping("/logs/search")
  public ApiResponse<OperationLogQueryResponse> search(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String operatorName,
      @RequestParam(defaultValue = "") String action,
      @RequestParam(defaultValue = "ALL") String storeScope,
      @RequestParam(defaultValue = "") String storeId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "30") int pageSize
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireAuditRead(user);
    OperationLogQuery query = new OperationLogQuery(
        keyword, operatorName, action, storeScope, storeId, startDate, endDate, page, pageSize);
    return ApiResponse.ok(auditRepository.search(user.tenantId(), query));
  }
}
