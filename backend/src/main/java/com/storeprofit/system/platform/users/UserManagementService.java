package com.storeprofit.system.platform.users;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UserManagementService {
  private final AuthRepository authRepository;

  public UserManagementService(AuthRepository authRepository) {
    this.authRepository = authRepository;
  }

  public List<UserResponse> users(AuthUser currentUser) {
    if (!List.of("ADMIN", "BOSS").contains(currentUser.role())) {
      throw new BusinessException("FORBIDDEN", "仅老板可查看用户权限", HttpStatus.FORBIDDEN);
    }
    return authRepository.users(currentUser.tenantId()).stream()
        .map(user -> new UserResponse(
            user.id(),
            user.tenantId(),
            user.tenantName(),
            user.username(),
            user.displayName(),
            user.role(),
            roleLabel(user.role()),
            user.storeId(),
            user.enabled(),
            authRepository.storeScope(currentUser.tenantId(), user.id(), user.role(), user.storeId())
        ))
        .toList();
  }

  private String roleLabel(String role) {
    return switch (role) {
      case "ADMIN", "BOSS" -> "老板";
      case "FINANCE" -> "财务";
      case "SUPERVISOR" -> "督导";
      case "STORE_MANAGER" -> "店长";
      case "WAREHOUSE" -> "仓库管理员";
      case "OPERATIONS" -> "运营";
      default -> role;
    };
  }
}
