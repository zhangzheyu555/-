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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
  private static final long FAILED_LOGIN_WINDOW_MILLIS = 5 * 60 * 1000L;
  private static final int MAX_TRACKED_LOGIN_FAILURE_KEYS = 10_000;
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final AuditRepository auditRepository;
  private final AuthorizationService authorizationService;
  private final DataScopeService dataScopeService;
  private final WorkspaceAccessResolver workspaceAccessResolver;
  private final BusinessScopeResolver businessScopeResolver;
  private final long tokenTtlHours;
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
      @Value("${app.auth.token-ttl-hours:12}") long tokenTtlHours
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.auditRepository = auditRepository;
    this.authorizationService = authorizationService;
    this.dataScopeService = dataScopeService;
    this.workspaceAccessResolver = workspaceAccessResolver;
    this.businessScopeResolver = businessScopeResolver;
    this.tokenTtlHours = tokenTtlHours;
  }

  /** Compatibility constructor retained for focused authorization tests. */
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      long tokenTtlHours
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        authorizationService,
        dataScopeService,
        new WorkspaceAccessResolver(),
        null,
        tokenTtlHours
    );
  }

  /** Compatibility constructor retained for focused unit tests. */
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      long tokenTtlHours
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        null,
        null,
        tokenTtlHours
    );
  }

  @Transactional
  public LoginResponse login(LoginRequest request) {
    return loginInternal(request, "unknown");
  }

  @Transactional
  public LoginResponse login(LoginRequest request, String sourceIp) {
    return loginInternal(request, sourceIp);
  }

  private LoginResponse loginInternal(LoginRequest request, String sourceIp) {
    long tenantId = request.tenantId() == null ? TenantDefaults.DEFAULT_TENANT_ID : request.tenantId();
    String username = request.username().trim();
    List<String> attemptKeys = loginAttemptKeys(tenantId, username, sourceIp);
    AuthUser user = authRepository.findByUsername(tenantId, username).orElse(null);
    boolean passwordAccepted = user != null
        && user.enabled()
        && passwordService.matches(request.password(), user.passwordHash());
    if (!passwordAccepted) {
      requireLoginAllowed(attemptKeys);
      recordLoginFailure(attemptKeys);
      throw new BusinessException("LOGIN_FAILED", "账号或密码错误", HttpStatus.UNAUTHORIZED);
    }
    attemptKeys.forEach(failedLogins::remove);
    // Resolve permissions and the effective single-store context before issuing a token. A store
    // manager with an invalid binding must not receive a usable session token.
    SessionUser sessionUser = toSessionUser(user);
    String token = newToken();
    authRepository.deleteTokensForUser(user.tenantId(), user.id());
    authRepository.createToken(
        token,
        user.tenantId(),
        user.id(),
        user.permissionVersion(),
        OffsetDateTime.now().plusHours(tokenTtlHours)
    );
    return new LoginResponse(token, sessionUser);
  }

  private List<String> loginAttemptKeys(long tenantId, String username, String sourceIp) {
    String normalizedUsername = username.toLowerCase(Locale.ROOT);
    if (normalizedUsername.length() > 160) {
      normalizedUsername = normalizedUsername.substring(0, 160);
    }
    String normalizedIp = sourceIp == null ? "unknown" : sourceIp.trim().toLowerCase(Locale.ROOT);
    if (normalizedIp.isBlank()) {
      normalizedIp = "unknown";
    } else if (normalizedIp.length() > 64) {
      normalizedIp = normalizedIp.substring(0, 64);
    }
    return List.of(
        "account:" + tenantId + ':' + normalizedUsername,
        "source:" + normalizedIp
    );
  }

  private void requireLoginAllowed(List<String> attemptKeys) {
    long now = System.currentTimeMillis();
    for (String attemptKey : attemptKeys) {
      FailedLoginWindow state = failedLogins.get(attemptKey);
      if (state == null || now - state.startedAtMillis() >= FAILED_LOGIN_WINDOW_MILLIS) {
        if (state != null) {
          failedLogins.remove(attemptKey, state);
        }
        continue;
      }
      if (state.attempts() >= MAX_FAILED_LOGIN_ATTEMPTS) {
        throw new BusinessException(
            "LOGIN_RATE_LIMITED", "登录尝试过多，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
      }
    }
  }

  private void recordLoginFailure(List<String> attemptKeys) {
    long now = System.currentTimeMillis();
    for (String attemptKey : attemptKeys) {
      failedLogins.compute(attemptKey, (key, state) -> {
        if (state == null && failedLogins.size() >= MAX_TRACKED_LOGIN_FAILURE_KEYS) {
          return null;
        }
        if (state == null || now - state.startedAtMillis() >= FAILED_LOGIN_WINDOW_MILLIS) {
          return new FailedLoginWindow(1, now);
        }
        return new FailedLoginWindow(state.attempts() + 1, state.startedAtMillis());
      });
    }
  }

  @Scheduled(fixedDelay = FAILED_LOGIN_WINDOW_MILLIS)
  public void deleteExpiredLoginFailures() {
    long cutoff = System.currentTimeMillis() - FAILED_LOGIN_WINDOW_MILLIS;
    failedLogins.entrySet().removeIf(entry -> entry.getValue().startedAtMillis() <= cutoff);
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
      case "EMPLOYEE" -> "员工";
      default -> role;
    };
  }

  private record FailedLoginWindow(int attempts, long startedAtMillis) {
  }
}
