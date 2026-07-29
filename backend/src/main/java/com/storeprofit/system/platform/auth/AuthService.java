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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
  private static final long PASSWORD_CHANGE_GRANT_TTL_MILLIS = 10 * 60 * 1000L;
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final AuditRepository auditRepository;
  private final AuthorizationService authorizationService;
  private final DataScopeService dataScopeService;
  private final WorkspaceAccessResolver workspaceAccessResolver;
  private final BusinessScopeResolver businessScopeResolver;
  private final WeChatMiniProgramService weChatMiniProgramService;
  private final WeChatMiniProgramRepository weChatMiniProgramRepository;
  private final LoginAttemptGuard loginAttemptGuard;
  private final PasswordVerificationGuard passwordVerificationGuard;
  private final long tokenTtlHours;
  private final long passwordChangeGrantTtlMillis;
  private final SecureRandom secureRandom = new SecureRandom();
  private final ConcurrentMap<String, PasswordChangeGrant> passwordChangeGrants = new ConcurrentHashMap<>();

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
      @Value("${app.auth.password-change-grant-ttl-minutes:10}") long passwordChangeGrantTtlMinutes,
      WeChatMiniProgramService weChatMiniProgramService,
      WeChatMiniProgramRepository weChatMiniProgramRepository,
      LoginAttemptGuard loginAttemptGuard,
      PasswordVerificationGuard passwordVerificationGuard
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.auditRepository = auditRepository;
    this.authorizationService = authorizationService;
    this.dataScopeService = dataScopeService;
    this.workspaceAccessResolver = workspaceAccessResolver;
    this.businessScopeResolver = businessScopeResolver;
    this.weChatMiniProgramService = weChatMiniProgramService;
    this.weChatMiniProgramRepository = weChatMiniProgramRepository;
    this.loginAttemptGuard = loginAttemptGuard;
    this.passwordVerificationGuard = passwordVerificationGuard;
    this.tokenTtlHours = tokenTtlHours;
    this.passwordChangeGrantTtlMillis = Math.max(0, passwordChangeGrantTtlMinutes) * 60 * 1000L;
  }

  /** Compatibility constructor retained for focused tests that provide WeChat collaborators. */
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      WorkspaceAccessResolver workspaceAccessResolver,
      BusinessScopeResolver businessScopeResolver,
      long tokenTtlHours,
      long passwordChangeGrantTtlMinutes,
      WeChatMiniProgramService weChatMiniProgramService,
      WeChatMiniProgramRepository weChatMiniProgramRepository
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        authorizationService,
        dataScopeService,
        workspaceAccessResolver,
        businessScopeResolver,
        tokenTtlHours,
        passwordChangeGrantTtlMinutes,
        weChatMiniProgramService,
        weChatMiniProgramRepository,
        defaultLoginAttemptGuard(),
        defaultPasswordVerificationGuard()
    );
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
        tokenTtlHours,
        10,
        null,
        null
    );
  }

  /** Compatibility constructor retained for tests with explicit workspace and scope resolvers. */
  public AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      WorkspaceAccessResolver workspaceAccessResolver,
      BusinessScopeResolver businessScopeResolver,
      long tokenTtlHours
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        authorizationService,
        dataScopeService,
        workspaceAccessResolver,
        businessScopeResolver,
        tokenTtlHours,
        10,
        null,
        null
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

  AuthService(
      AuthRepository authRepository,
      PasswordService passwordService,
      AuditRepository auditRepository,
      long tokenTtlHours,
      long passwordChangeGrantTtlMinutes
  ) {
    this(
        authRepository,
        passwordService,
        auditRepository,
        null,
        null,
        new WorkspaceAccessResolver(),
        null,
        tokenTtlHours,
        passwordChangeGrantTtlMinutes,
        null,
        null
    );
  }

  @Transactional
  public LoginResponse login(LoginRequest request) {
    return loginInternal(request);
  }

  @Transactional
  public LoginResponse login(LoginRequest request, String sourceIp) {
    return loginInternal(request, sourceIp);
  }

  private LoginResponse loginInternal(LoginRequest request) {
    return loginInternal(request, "");
  }

  private LoginResponse loginInternal(LoginRequest request, String sourceIp) {
    long tenantId = request.tenantId() == null ? TenantDefaults.DEFAULT_TENANT_ID : request.tenantId();
    String username = request.username().trim();
    loginAttemptGuard.acquirePassword(tenantId, username, sourceIp);
    AuthUser user = authRepository.findByUsername(tenantId, username).orElse(null);
    boolean passwordAccepted = user != null
        && user.enabled()
        && passwordVerificationGuard.verify(
            () -> passwordService.matches(request.password(), user.passwordHash()));
    if (!passwordAccepted) {
      throw new BusinessException("LOGIN_FAILED", "账号或密码错误", HttpStatus.UNAUTHORIZED);
    }
    loginAttemptGuard.clearAccount(tenantId, username);
    if (authRepository.passwordChangeRequired(user.tenantId(), user.id())) {
      authRepository.deleteTokensForUser(user.tenantId(), user.id());
      return LoginResponse.passwordChangeRequired(issuePasswordChangeGrant(user));
    }
    return issueSession(user);
  }

  @Transactional
  public LoginResponse weChatLogin(String code, Long tenantId) {
    return weChatLogin(code, tenantId, "");
  }

  @Transactional
  public LoginResponse weChatLogin(String code, Long tenantId, String sourceIp) {
    requireWeChatSupport();
    // 当前为单租户部署；未传租户时保持与账号密码登录相同的默认租户规则。
    long effectiveTenantId = tenantId == null ? TenantDefaults.DEFAULT_TENANT_ID : tenantId;
    loginAttemptGuard.acquireWeChat(effectiveTenantId, sourceIp);
    WeChatMiniProgramService.Identity identity = weChatMiniProgramService.exchangeCode(code);
    long userId = weChatMiniProgramRepository.boundUserId(
        effectiveTenantId, weChatMiniProgramService.appId(), identity.openid()
    ).orElseThrow(() -> new BusinessException(
        "WECHAT_NOT_BOUND", "该微信尚未绑定账号，请先用账号密码登录后在“我的”中绑定", HttpStatus.UNAUTHORIZED));
    AuthUser user = authRepository.user(effectiveTenantId, userId)
        .filter(AuthUser::enabled)
        .orElseThrow(() -> new BusinessException("LOGIN_FAILED", "账号不可用，请联系管理员", HttpStatus.UNAUTHORIZED));
    return issueSession(user);
  }

  public WeChatBindingStatus weChatBindingStatus(AuthUser user) {
    requireWeChatSupport();
    boolean configured = weChatMiniProgramService.configured();
    boolean bound = configured && weChatMiniProgramRepository.isBound(
        user.tenantId(), user.id(), weChatMiniProgramService.appId());
    return new WeChatBindingStatus(configured, bound);
  }

  @Transactional
  public WeChatBindingStatus bindWeChat(AuthUser user, String code) {
    requireWeChatSupport();
    WeChatMiniProgramService.Identity identity = weChatMiniProgramService.exchangeCode(code);
    String appId = weChatMiniProgramService.appId();
    var boundUserId = weChatMiniProgramRepository.boundUserId(user.tenantId(), appId, identity.openid());
    if (boundUserId.isPresent()) {
      if (boundUserId.get() == user.id()) return new WeChatBindingStatus(true, true);
      throw new BusinessException("WECHAT_ALREADY_BOUND", "该微信已绑定其他账号，请先在原账号解绑", HttpStatus.CONFLICT);
    }
    try {
      weChatMiniProgramRepository.bind(user.tenantId(), user.id(), appId, identity.openid(), identity.unionid());
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      throw new BusinessException("WECHAT_ALREADY_BOUND", "该微信已绑定其他账号，请先在原账号解绑", HttpStatus.CONFLICT);
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        "绑定微信一键登录", "wechat_mini_program_binding", String.valueOf(user.id()), user.storeId(), null,
        "已绑定微信小程序 appId=" + appId + "，openid=" + maskOpenId(identity.openid()), null, null));
    return new WeChatBindingStatus(true, true);
  }

  private LoginResponse issueSession(AuthUser user) {
    SessionUser sessionUser = toSessionUser(user);
    String token = newToken();
    authRepository.deleteTokensForUser(user.tenantId(), user.id());
    authRepository.createToken(token, user.tenantId(), user.id(), user.permissionVersion(),
        OffsetDateTime.now().plusHours(tokenTtlHours));
    return LoginResponse.authenticated(token, sessionUser);
  }

  private void requireWeChatSupport() {
    if (weChatMiniProgramService == null || weChatMiniProgramRepository == null) {
      throw new IllegalStateException("WeChat mini program authentication is not configured");
    }
  }

  private String maskOpenId(String openId) {
    if (openId == null || openId.length() <= 6) return "***";
    return openId.substring(0, 3) + "***" + openId.substring(openId.length() - 3);
  }

  @Transactional
  public void changeInitialPassword(InitialPasswordChangeRequest request) {
    if (request == null || request.credential() == null || request.credential().isBlank()) {
      throw invalidPasswordChangeCredential();
    }
    PasswordChangeGrant grant = passwordChangeGrants.get(hashCredential(request.credential()));
    if (grant == null || grant.expiresAtMillis() <= System.currentTimeMillis()) {
      if (grant != null) {
        passwordChangeGrants.remove(hashCredential(request.credential()), grant);
      }
      throw invalidPasswordChangeCredential();
    }
    String newPassword = request.newPassword();
    if (newPassword == null || request.confirmPassword() == null) {
      throw new BusinessException("PASSWORD_INVALID", "请完整填写新密码和确认密码", HttpStatus.BAD_REQUEST);
    }
    if (!newPassword.equals(request.confirmPassword())) {
      throw new BusinessException("PASSWORD_CONFIRMATION_MISMATCH", "两次输入的新密码不一致", HttpStatus.BAD_REQUEST);
    }
    if (newPassword.length() < 8 || newPassword.length() > 128) {
      throw new BusinessException("PASSWORD_INVALID", "密码长度必须为 8 至 128 位", HttpStatus.BAD_REQUEST);
    }
    AuthUser user = authRepository.user(grant.tenantId(), grant.userId())
        .filter(AuthUser::enabled)
        .orElseThrow(this::invalidPasswordChangeCredential);
    if (!authRepository.passwordChangeRequired(user.tenantId(), user.id())) {
      passwordChangeGrants.remove(hashCredential(request.credential()), grant);
      throw invalidPasswordChangeCredential();
    }
    if (passwordService.matches(newPassword, user.passwordHash())) {
      throw new BusinessException("PASSWORD_REUSE_REJECTED", "新密码不能与初始密码相同", HttpStatus.BAD_REQUEST);
    }
    String passwordHash = passwordService.hash(newPassword);
    if (!authRepository.completeInitialPasswordChange(user.tenantId(), user.id(), passwordHash)) {
      throw invalidPasswordChangeCredential();
    }
    authRepository.deleteTokensForUser(user.tenantId(), user.id());
    auditRepository.writeLog(user, new AuditLogRequest(
        "首次登录修改密码",
        "auth_user",
        String.valueOf(user.id()),
        user.storeId(),
        null,
        "首次登录密码修改成功，会话已失效",
        null,
        null
    ));
    passwordChangeGrants.entrySet().removeIf(entry ->
        entry.getValue().tenantId() == user.tenantId() && entry.getValue().userId() == user.id());
  }

  private String issuePasswordChangeGrant(AuthUser user) {
    passwordChangeGrants.entrySet().removeIf(entry ->
        entry.getValue().tenantId() == user.tenantId() && entry.getValue().userId() == user.id());
    String credential = newToken();
    passwordChangeGrants.put(hashCredential(credential), new PasswordChangeGrant(
        user.tenantId(), user.id(), System.currentTimeMillis() + passwordChangeGrantTtlMillis));
    return credential;
  }

  private BusinessException invalidPasswordChangeCredential() {
    return new BusinessException(
        "PASSWORD_CHANGE_CREDENTIAL_INVALID", "改密凭据无效或已过期，请重新登录", HttpStatus.UNAUTHORIZED);
  }

  @Scheduled(fixedDelay = PASSWORD_CHANGE_GRANT_TTL_MILLIS)
  public void deleteExpiredPasswordChangeGrants() {
    long now = System.currentTimeMillis();
    passwordChangeGrants.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
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

  private String hashCredential(String credential) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(credential.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
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
      case "EMPLOYEE" -> "员工";
      default -> role;
    };
  }

  private static LoginAttemptGuard defaultLoginAttemptGuard() {
    LoginProtectionProperties properties = new LoginProtectionProperties();
    return new LoginAttemptGuard(properties, null, Clock.systemUTC());
  }

  private static PasswordVerificationGuard defaultPasswordVerificationGuard() {
    return new PasswordVerificationGuard(new LoginProtectionProperties());
  }

  private record PasswordChangeGrant(long tenantId, long userId, long expiresAtMillis) {
  }
}
