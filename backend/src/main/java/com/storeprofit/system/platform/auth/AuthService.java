package com.storeprofit.system.platform.auth;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.session.SessionUser;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.WorkspaceAccessProfile;
import com.storeprofit.system.platform.authorization.WorkspaceAccessResolver;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
  private static final long FAILED_LOGIN_WINDOW_MILLIS = 5 * 60 * 1000L;
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final AuditRepository auditRepository;
  private final AuthorizationService authorizationService;
  private final DataScopeService dataScopeService;
  private final WorkspaceAccessResolver workspaceAccessResolver;
  private final BusinessScopeResolver businessScopeResolver;
  private final long tokenTtlHours;
  private final boolean bootstrapDefaultUsersEnabled;
  private final String bootstrapDefaultUsersPassword;
  private final SecureRandom secureRandom = new SecureRandom();
  private final ConcurrentMap<String, FailedLoginWindow> failedLogins = new ConcurrentHashMap<>();

  @Autowired
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      WorkspaceAccessResolver workspaceAccessResolver,
      BusinessScopeResolver businessScopeResolver,
      @Value("${app.auth.token-ttl-hours:12}") long tokenTtlHours,
      @Value("${app.bootstrap.default-users-enabled:false}") boolean bootstrapDefaultUsersEnabled,
      @Value("${app.bootstrap.default-users-password:}") String bootstrapDefaultUsersPassword
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.auditRepository = auditRepository;
    this.authorizationService = authorizationService;
    this.dataScopeService = dataScopeService;
    this.workspaceAccessResolver = workspaceAccessResolver;
    this.businessScopeResolver = businessScopeResolver;
    this.tokenTtlHours = tokenTtlHours;
    this.bootstrapDefaultUsersEnabled = bootstrapDefaultUsersEnabled;
    this.bootstrapDefaultUsersPassword = bootstrapDefaultUsersPassword == null ? "" : bootstrapDefaultUsersPassword.trim();
  }

  /** Compatibility constructor retained for focused authorization tests. */
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      long tokenTtlHours,
      boolean bootstrapDefaultUsersEnabled,
      String bootstrapDefaultUsersPassword
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        authorizationService,
        dataScopeService,
        new WorkspaceAccessResolver(),
        null,
        tokenTtlHours,
        bootstrapDefaultUsersEnabled,
        bootstrapDefaultUsersPassword
    );
  }

  /** Compatibility constructor retained for focused unit tests. */
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      long tokenTtlHours,
      boolean bootstrapDefaultUsersEnabled,
      String bootstrapDefaultUsersPassword
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        null,
        null,
        tokenTtlHours,
        bootstrapDefaultUsersEnabled,
        bootstrapDefaultUsersPassword
    );
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
    ensureDefaultUser("boss", bootstrapDefaultUsersPassword, "老板", "BOSS");
    ensureDefaultUser("finance", bootstrapDefaultUsersPassword, "财务", "FINANCE");
    ensureDefaultUser("supervisor", bootstrapDefaultUsersPassword, "运营", "OPERATIONS");
    ensureDefaultUser("warehouse", bootstrapDefaultUsersPassword, "仓库管理员", "WAREHOUSE");
    ensureDefaultUser("ops", bootstrapDefaultUsersPassword, "运营", "OPERATIONS");
    ensureDefaultUser("operations", bootstrapDefaultUsersPassword, "运营", "OPERATIONS");
  }

  private void ensureDefaultUser(String username, String password, String displayName, String role) {
    if (!authRepository.userExists(TenantDefaults.DEFAULT_TENANT_ID, username)) {
      authRepository.createUser(TenantDefaults.DEFAULT_TENANT_ID, username, passwordService.hash(password), displayName, role, null);
    }
  }

  public LoginResponse login(LoginRequest request) {
    long tenantId = request.tenantId() == null ? TenantDefaults.DEFAULT_TENANT_ID : request.tenantId();
    String username = request.username().trim();
    String attemptKey = tenantId + ":" + username.toLowerCase(Locale.ROOT);
    requireLoginAllowed(attemptKey);
    AuthUser user = authRepository.findByUsername(tenantId, username).orElse(null);
    if (user == null || !user.enabled() || !passwordService.matches(request.password(), user.passwordHash())) {
      recordLoginFailure(attemptKey);
      throw new BusinessException("LOGIN_FAILED", "账号或密码错误", HttpStatus.UNAUTHORIZED);
    }
    failedLogins.remove(attemptKey);
    // Resolve permissions and the effective single-store context before issuing a token. A store
    // manager with an invalid binding must not receive a usable session token.
    SessionUser sessionUser = toSessionUser(user);
    String token = newToken();
    authRepository.createToken(
        token,
        user.tenantId(),
        user.id(),
        user.permissionVersion(),
        OffsetDateTime.now().plusHours(tokenTtlHours)
    );
    return new LoginResponse(token, sessionUser);
  }

  private void requireLoginAllowed(String attemptKey) {
    FailedLoginWindow state = failedLogins.get(attemptKey);
    long now = System.currentTimeMillis();
    if (state == null || now - state.startedAtMillis() >= FAILED_LOGIN_WINDOW_MILLIS) {
      if (state != null) failedLogins.remove(attemptKey, state);
      return;
    }
    if (state.attempts() >= MAX_FAILED_LOGIN_ATTEMPTS) {
      throw new BusinessException("LOGIN_RATE_LIMITED", "登录尝试过多，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
    }
  }

  private void recordLoginFailure(String attemptKey) {
    long now = System.currentTimeMillis();
    failedLogins.compute(attemptKey, (key, state) -> {
      if (state == null || now - state.startedAtMillis() >= FAILED_LOGIN_WINDOW_MILLIS) {
        return new FailedLoginWindow(1, now);
      }
      return new FailedLoginWindow(state.attempts() + 1, state.startedAtMillis());
    });
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
    Set<String> effectivePermissions = authorizationService == null
        ? AuthorizationService.legacyTemplatePermissions(user.role())
        : authorizationService.effectivePermissions(user);
    Map<String, DataScope> effectiveDataScopes = dataScopeService == null
        ? legacyDataScopes(user)
        : dataScopeService.dataScopes(user);
    WorkspaceAccessProfile workspaceAccess = workspaceAccessResolver.resolve(
        user,
        effectivePermissions,
        effectiveDataScopes,
        authRepository.assignedStoreScope(user.tenantId(), user.id())
    );
    BusinessScope businessScope = businessScopeResolver == null
        ? new BusinessScope(
            "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role())) ? user.storeId() : null,
            null,
            null,
            null,
            effectiveDataScopes.getOrDefault(DataScopeDomains.STORE, DataScope.none()))
        : businessScopeResolver.sessionScope(user);
    return new SessionUser(
        user.id(),
        user.tenantId(),
        user.tenantName(),
        user.displayName(),
        AccessControlService.canonicalRole(user.role()),
        roleLabel(user.role()),
        legacyStoreScope(effectiveDataScopes),
        effectivePermissions.stream().sorted().toList(),
        effectiveDataScopes,
        workspaceAccess.defaultWorkspace(),
        user.permissionVersion(),
        businessScope.storeId(),
        businessScope.storeName(),
        businessScope.brandId(),
        businessScope.brandName(),
        businessScope.dataScope()
    );
  }

  private List<String> legacyStoreScope(Map<String, DataScope> dataScopes) {
    DataScope storeScope = dataScopes.getOrDefault(DataScopeDomains.STORE, DataScope.none());
    return storeScope.allowsAllStores() ? List.of("all") : storeScope.storeIds();
  }

  public String defaultWorkspace(String role) {
    return workspaceAccessResolver.recommendedWorkspace(role);
  }

  private Map<String, DataScope> legacyDataScopes(AuthUser user) {
    LinkedHashMap<String, DataScope> result = new LinkedHashMap<>();
    DataScopeDomains.ALL.stream().sorted().forEach(domain -> result.put(domain, DataScope.none()));
    if (AccessControlService.isBoss(user)) {
      result.replaceAll((domain, ignored) -> DataScope.all());
      return Map.copyOf(result);
    }
    List<String> storeScope = authRepository.storeScope(
        user.tenantId(), user.id(), user.role(), user.storeId());
    if (!storeScope.isEmpty()) {
      DataScope scope = new DataScope(
          "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))
              ? DataScopeModes.OWN_STORE
              : DataScopeModes.STORE_LIST,
          storeScope
      );
      result.put(DataScopeDomains.STORE, scope);
    }
    if ("EMPLOYEE".equals(AccessControlService.canonicalRole(user.role()))) {
      result.put(DataScopeDomains.EXAM, new DataScope(DataScopeModes.SELF, List.of()));
    }
    return Map.copyOf(result);
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
    return switch (AccessControlService.canonicalRole(role)) {
      case "BOSS" -> "老板（系统管理员）";
      case "FINANCE" -> "财务";
      case "STORE_MANAGER" -> "店长";
      case "WAREHOUSE" -> "仓库管理员";
      case "OPERATIONS" -> "运营";
      case "EMPLOYEE" -> "学员（兼容身份）";
      default -> role;
    };
  }

  private record FailedLoginWindow(int attempts, long startedAtMillis) {
  }
}
