package com.storeprofit.system.platform.users;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {
  private static final Set<String> ALLOWED_ROLES = Set.of(
      "ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "OPERATIONS", "EMPLOYEE");
  private static final Set<String> GLOBAL_SCOPE_ROLES = Set.of("ADMIN", "BOSS", "FINANCE");

  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final OrganizationRepository organizationRepository;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public UserManagementService(
      AuthRepository authRepository,
      PasswordService passwordService,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.organizationRepository = organizationRepository;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  public List<UserResponse> users(AuthUser currentUser) {
    accessControl.requireUserManagementRead(currentUser);
    return authRepository.users(currentUser.tenantId()).stream()
        .map(user -> response(user))
        .toList();
  }

  @Transactional
  public UserResponse create(AuthUser currentUser, UserCreateRequest request) {
    accessControl.requireUserManagementWrite(currentUser);
    String username = normalizeUsername(request == null ? null : request.username());
    if (authRepository.userExists(currentUser.tenantId(), username)) {
      throw new BusinessException("USER_EXISTS", "该登录账号已存在", HttpStatus.CONFLICT);
    }
    UserProfile profile = normalizeProfile(
        currentUser.tenantId(),
        request == null ? null : request.displayName(),
        request == null ? null : request.role(),
        request == null ? null : request.storeId(),
        request == null ? null : request.storeScope(),
        true
    );
    String password = normalizePassword(request == null ? null : request.password());
    authRepository.createUser(
        currentUser.tenantId(), username, passwordService.hash(password), profile.displayName(), profile.role(), profile.storeId());
    AuthUser created = authRepository.findByUsername(currentUser.tenantId(), username)
        .orElseThrow(() -> new BusinessException("USER_CREATE_FAILED", "账号创建失败", HttpStatus.INTERNAL_SERVER_ERROR));
    authRepository.replaceStoreScope(currentUser.tenantId(), created.id(), profile.storeScope());
    writeAudit(currentUser, "创建账号", created.id(), profile.storeId(), "创建 " + created.username() + "（" + roleLabel(profile.role()) + "）");
    return response(created);
  }

  @Transactional
  public UserResponse update(AuthUser currentUser, long userId, UserUpdateRequest request) {
    accessControl.requireUserManagementWrite(currentUser);
    AuthUser target = target(currentUser, userId);
    UserProfile profile = normalizeProfile(
        currentUser.tenantId(),
        request == null ? null : request.displayName(),
        request == null ? null : request.role(),
        request == null ? null : request.storeId(),
        request == null ? null : request.storeScope(),
        request != null && request.enabled()
    );
    if (target.id() == currentUser.id() && (!profile.enabled() || !profile.role().equals(target.role()))) {
      throw new BusinessException("SELF_ACCOUNT_PROTECTED", "不能停用自己或修改自己的角色", HttpStatus.CONFLICT);
    }
    if ("ADMIN".equals(target.role()) && target.enabled()
        && (!"ADMIN".equals(profile.role()) || !profile.enabled())
        && authRepository.activeAdminCount(currentUser.tenantId()) <= 1) {
      throw new BusinessException("LAST_ADMIN_PROTECTED", "系统至少需要保留一个启用的系统管理员", HttpStatus.CONFLICT);
    }
    authRepository.updateUser(currentUser.tenantId(), target.id(), profile.displayName(), profile.role(), profile.storeId(), profile.enabled());
    authRepository.replaceStoreScope(currentUser.tenantId(), target.id(), profile.storeScope());
    if (!profile.enabled()) {
      authRepository.deleteTokensForUser(currentUser.tenantId(), target.id());
    }
    writeAudit(currentUser, "更新账号权限", target.id(), profile.storeId(), "更新 " + target.username() + " 的角色、门店范围或启用状态");
    return response(target(currentUser, target.id()));
  }

  @Transactional
  public void resetPassword(AuthUser currentUser, long userId, UserPasswordResetRequest request) {
    accessControl.requireUserManagementWrite(currentUser);
    AuthUser target = target(currentUser, userId);
    String password = normalizePassword(request == null ? null : request.password());
    authRepository.updatePasswordByUserId(currentUser.tenantId(), target.id(), passwordService.hash(password));
    authRepository.deleteTokensForUser(currentUser.tenantId(), target.id());
    writeAudit(currentUser, "重置账号密码", target.id(), target.storeId(), "已重置 " + target.username() + " 的密码并使旧登录失效");
  }

  private UserResponse response(AuthUser user) {
    return new UserResponse(
        user.id(),
        user.tenantId(),
        user.tenantName(),
        user.username(),
        user.displayName(),
        user.role(),
        roleLabel(user.role()),
        user.storeId(),
        user.enabled(),
        authRepository.storeScope(user.tenantId(), user.id(), user.role(), user.storeId())
    );
  }

  private AuthUser target(AuthUser currentUser, long userId) {
    return authRepository.user(currentUser.tenantId(), userId)
        .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "未找到账号", HttpStatus.NOT_FOUND));
  }

  private UserProfile normalizeProfile(
      long tenantId,
      String displayName,
      String rawRole,
      String rawStoreId,
      List<String> rawStoreScope,
      boolean enabled
  ) {
    String name = requireText(displayName, "DISPLAY_NAME_REQUIRED", "请填写姓名或显示名称");
    if (name.length() > 120) {
      throw new BusinessException("DISPLAY_NAME_INVALID", "显示名称不能超过 120 个字符", HttpStatus.BAD_REQUEST);
    }
    String role = normalizeRole(rawRole);
    LinkedHashSet<String> scope = new LinkedHashSet<>();
    if (rawStoreScope != null) {
      for (String value : rawStoreScope) {
        String storeId = blankToNull(value);
        if (storeId != null) {
          scope.add(storeId);
        }
      }
    }
    String directStoreId = blankToNull(rawStoreId);
    if (directStoreId != null) {
      scope.add(directStoreId);
    }
    if (GLOBAL_SCOPE_ROLES.contains(role)) {
      return new UserProfile(name, role, null, List.of(), enabled);
    }
    if (scope.isEmpty()) {
      throw new BusinessException("STORE_SCOPE_REQUIRED", "该角色必须配置至少一个门店范围", HttpStatus.BAD_REQUEST);
    }
    Set<String> validStoreIds = organizationRepository.stores(tenantId).stream()
        .map(store -> store.id())
        .collect(java.util.stream.Collectors.toSet());
    if (!validStoreIds.containsAll(scope)) {
      throw new BusinessException("STORE_SCOPE_INVALID", "门店范围中包含不存在的门店", HttpStatus.BAD_REQUEST);
    }
    return new UserProfile(name, role, directStoreId == null ? scope.getFirst() : directStoreId, List.copyOf(scope), enabled);
  }

  private String normalizeUsername(String value) {
    String username = requireText(value, "USERNAME_REQUIRED", "请填写登录账号").toLowerCase(Locale.ROOT);
    if (!username.matches("[a-z0-9_.-]{3,40}")) {
      throw new BusinessException("USERNAME_INVALID", "登录账号只能包含小写字母、数字、点、下划线或短横线，长度 3 至 40 位", HttpStatus.BAD_REQUEST);
    }
    return username;
  }

  private String normalizeRole(String value) {
    String role = requireText(value, "ROLE_REQUIRED", "请选择角色").toUpperCase(Locale.ROOT);
    if ("OWNER".equals(role)) {
      role = "BOSS";
    }
    if ("OPS".equals(role)) {
      role = "OPERATIONS";
    }
    if (!ALLOWED_ROLES.contains(role)) {
      throw new BusinessException("ROLE_INVALID", "角色不正确", HttpStatus.BAD_REQUEST);
    }
    return role;
  }

  private String normalizePassword(String value) {
    String password = requireText(value, "PASSWORD_REQUIRED", "请填写密码");
    if (password.length() < 8 || password.length() > 128) {
      throw new BusinessException("PASSWORD_INVALID", "密码长度必须为 8 至 128 位", HttpStatus.BAD_REQUEST);
    }
    return password;
  }

  private void writeAudit(AuthUser user, String action, long targetId, String storeId, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(
        action,
        "auth_user",
        Long.toString(targetId),
        storeId,
        null,
        reason,
        null,
        null
    ));
  }

  private String roleLabel(String role) {
    return switch (role) {
      case "ADMIN" -> "系统管理员";
      case "BOSS", "OWNER" -> "老板";
      case "FINANCE" -> "财务";
      case "SUPERVISOR" -> "督导";
      case "STORE_MANAGER" -> "店长";
      case "WAREHOUSE" -> "仓库管理员";
      case "OPERATIONS" -> "运营";
      case "EMPLOYEE" -> "员工";
      default -> role;
    };
  }

  private String requireText(String value, String code, String message) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record UserProfile(String displayName, String role, String storeId, List<String> storeScope, boolean enabled) {
  }
}
