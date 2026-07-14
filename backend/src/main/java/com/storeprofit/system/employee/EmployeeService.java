package com.storeprofit.system.employee;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService {
  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public EmployeeService(
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
  }

  public EmployeeService(
      EmployeeRepository employeeRepository,
      AccessControlService accessControl
  ) {
    this(employeeRepository, accessControl, null);
  }

  /** Compatibility constructor retained for isolated service tests. */
  public EmployeeService(EmployeeRepository employeeRepository) {
    this(employeeRepository, null, null);
  }

  public List<EmployeeResponse> records(AuthUser user, Long brandId, String storeId, String status) {
    requireEmployeeRead(user);
    if (businessScopeResolver != null) {
      BusinessScope businessScope = businessScopeResolver.resolve(
          user, DataScopeDomains.STORE, storeId, brandId, "查看员工档案");
      storeId = businessScope.storeId();
      brandId = businessScope.brandId();
    }
    if (accessControl != null) {
      String requestedStoreId = blankToNull(storeId);
      if (requestedStoreId != null) {
        accessControl.requireStoreAccess(
            user, DataScopeDomains.STORE, requestedStoreId, "查看员工档案");
        return employeeRepository.records(
            user.tenantId(), brandId, requestedStoreId, blankToNull(status));
      }
      if (accessControl.hasAllDataScope(user, DataScopeDomains.STORE)) {
        return employeeRepository.records(
            user.tenantId(), brandId, null, blankToNull(status));
      }
      Set<String> allowedStoreIds = accessControl.allowedStoreIds(user, DataScopeDomains.STORE);
      return employeeRepository.records(
          user.tenantId(), brandId, null, blankToNull(status), allowedStoreIds);
    }
    if ("STORE_MANAGER".equals(user.role())) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        return List.of();
      }
      return employeeRepository.records(user.tenantId(), brandId, scopedStoreId, blankToNull(status));
    }
    return employeeRepository.records(user.tenantId(), brandId, blankToNull(storeId), blankToNull(status));
  }

  private void requireEmployeeRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireEmployeeRead(user);
      return;
    }
    if (AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(PermissionCodes.EMPLOYEE_READ)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "No permission to read employees", HttpStatus.FORBIDDEN);
  }

  private String requireManagerStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
    }
    return user.storeId().trim();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
