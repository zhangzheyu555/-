package com.storeprofit.system.platform.auth;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.session.SessionUser;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final long tokenTtlHours;
  private final SecureRandom secureRandom = new SecureRandom();

  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      @Value("${app.auth.token-ttl-hours:12}") long tokenTtlHours
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.tokenTtlHours = tokenTtlHours;
  }

  @PostConstruct
  public void ensureDefaultUsers() {
    authRepository.migrateAdminAccountToBoss(TenantDefaults.DEFAULT_TENANT_ID);
    ensureDefaultUser("boss", "123", "老板", "BOSS");
    ensureDefaultUser("finance", "finance888", "财务", "FINANCE");
    ensureDefaultUser("supervisor", "supervisor888", "督导", "SUPERVISOR");
    ensureDefaultUser("warehouse", "warehouse888", "仓库管理员", "WAREHOUSE");
    ensureDefaultUser("operations", "ops888", "运营", "OPERATIONS");
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
    if (token != null) {
      authRepository.deleteToken(token);
    }
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
