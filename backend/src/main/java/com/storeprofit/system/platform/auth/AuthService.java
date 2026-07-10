package com.storeprofit.system.platform.auth;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.session.SessionUser;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final AuditRepository auditRepository;
  private final long tokenTtlHours;
  private final boolean bootstrapDefaultUsersEnabled;
  private final String bootstrapDefaultUsersPassword;
  private final SecureRandom secureRandom = new SecureRandom();

  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      @Value("${app.auth.token-ttl-hours:12}") long tokenTtlHours,
      @Value("${app.bootstrap.default-users-enabled:false}") boolean bootstrapDefaultUsersEnabled,
      @Value("${app.bootstrap.default-users-password:}") String bootstrapDefaultUsersPassword
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.auditRepository = auditRepository;
    this.tokenTtlHours = tokenTtlHours;
    this.bootstrapDefaultUsersEnabled = bootstrapDefaultUsersEnabled;
    this.bootstrapDefaultUsersPassword = bootstrapDefaultUsersPassword == null ? "" : bootstrapDefaultUsersPassword.trim();
  }

  @PostConstruct
  public void ensureDefaultUsers() {
    if (!bootstrapDefaultUsersEnabled) {
      return;
    }
    if (bootstrapDefaultUsersPassword.isBlank()) {
      log.error("Default account bootstrap was requested without APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD; no accounts were created.");
      return;
    }
    log.warn("Default account bootstrap is enabled. Do not enable this setting in production.");
    authRepository.migrateAdminAccountToBoss(TenantDefaults.DEFAULT_TENANT_ID);
    ensureDefaultUser("boss", bootstrapDefaultUsersPassword, "老板", "BOSS");
    ensureDefaultUser("finance", bootstrapDefaultUsersPassword, "财务", "FINANCE");
    ensureDefaultUser("supervisor", bootstrapDefaultUsersPassword, "督导", "SUPERVISOR");
    ensureDefaultUser("warehouse", bootstrapDefaultUsersPassword, "仓库管理员", "WAREHOUSE");
    ensureDefaultUser("ops", bootstrapDefaultUsersPassword, "运营", "OPERATIONS");
    ensureDefaultUser("operations", bootstrapDefaultUsersPassword, "运营", "OPERATIONS");
  }

  private void ensureDefaultUser(String username, String password, String displayName, String role) {
    if (!authRepository.userExists(TenantDefaults.DEFAULT_TENANT_ID, username)) {
      authRepository.createUser(TenantDefaults.DEFAULT_TENANT_ID, username, passwordService.hash(password), displayName, role, null);
    } else {
      authRepository.ensureUserRole(TenantDefaults.DEFAULT_TENANT_ID, username, displayName, role);
    }
  }

  public LoginResponse login(LoginRequest request) {
    long tenantId = request.tenantId() == null ? TenantDefaults.DEFAULT_TENANT_ID : request.tenantId();
    AuthUser user = authRepository.findByUsername(tenantId, request.username().trim())
        .orElseThrow(() -> new BusinessException("LOGIN_FAILED", "账号或密码不正确", HttpStatus.UNAUTHORIZED));
    if (!user.enabled() || !passwordService.matches(request.password(), user.passwordHash())) {
      throw new BusinessException("LOGIN_FAILED", "账号或密码不正确", HttpStatus.UNAUTHORIZED);
    }
    String token = newToken();
    authRepository.createToken(token, user.tenantId(), user.id(), OffsetDateTime.now().plusHours(tokenTtlHours));
    return new LoginResponse(token, toSessionUser(user));
  }

  public void logout(String authorization) {
    String token = extractToken(authorization);
    if (token == null) {
      throw new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    }
    AuthUser user = authRepository.findByToken(token)
        .orElseThrow(() -> new BusinessException("UNAUTHORIZED", "登录已失效，请重新登录", HttpStatus.UNAUTHORIZED));
    authRepository.deleteToken(token);
    auditRepository.writeLog(user, new AuditLogRequest(
        "logout",
        "auth_session",
        String.valueOf(user.id()),
        null,
        null,
        "用户主动退出登录",
        null,
        null
    ));
    log.info("User logged out. tenantId={} userId={}", user.tenantId(), user.id());
  }

  public AuthUser requireUser(String authorization) {
    String token = extractToken(authorization);
    if (token == null) {
      throw new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    }
    return authRepository.findByToken(token)
        .orElseThrow(() -> new BusinessException("UNAUTHORIZED", "登录已失效，请重新登录", HttpStatus.UNAUTHORIZED));
  }

  public SessionUser toSessionUser(AuthUser user) {
    return new SessionUser(
        user.id(),
        user.tenantId(),
        user.tenantName(),
        user.displayName(),
        user.role(),
        roleLabel(user.role()),
        authRepository.storeScope(user.tenantId(), user.id(), user.role(), user.storeId())
    );
  }

  private String newToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String extractToken(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      return null;
    }
    String value = authorization.trim();
    if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return value.substring(7).trim();
    }
    return value;
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
}
