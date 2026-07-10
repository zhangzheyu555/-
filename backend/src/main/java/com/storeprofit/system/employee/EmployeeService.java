package com.storeprofit.system.employee;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService {
  private final EmployeeRepository employeeRepository;

  public EmployeeService(EmployeeRepository employeeRepository) {
    this.employeeRepository = employeeRepository;
  }

  public List<EmployeeResponse> records(AuthUser user, Long brandId, String storeId, String status) {
    requireReadRole(user);
    if ("STORE_MANAGER".equals(user.role())) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        return List.of();
      }
      return employeeRepository.records(user.tenantId(), brandId, scopedStoreId, blankToNull(status));
    }
    return employeeRepository.records(user.tenantId(), brandId, blankToNull(storeId), blankToNull(status));
  }

  private void requireReadRole(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to read employees", HttpStatus.FORBIDDEN);
    }
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
