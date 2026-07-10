package com.storeprofit.system.appauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import com.storeprofit.system.storage.StorageService;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 旧版页面（index.html + database.js）的登录鉴权：页面只有一个密码框，没有用户名。
 * 密码在服务端对 auth_user 表逐个 bcrypt 比对（老系统里每个账号的密码互不相同，
 * 密码本身即身份）。命中后签发一个正式的 AuthService token（写入 auth_token 表），
 * 该 token 对 /api/storage 以及所有需要 requireUser 的新接口一律有效，
 * 页面上只需要维护这一个 token。
 *
 * 兼容旧数据：auth_user 里没有命中时，回退到 legacy accounts blob（kv_storage 的
 * accounts 键）比对；命中则把该账号自动开通成 auth_user（密码转 bcrypt 存储），
 * 再签发 token——老账号第一次登录时自然完成迁移。
 * 空库引导：既没有用户也没有 accounts blob 时，密码 123 会创建 admin 管理员。
 */
@Service
public class AppAuthService {

  /** accounts blob 里的一条账号；只取需要的字段，其余忽略。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Account(String pass, String role, String sid, String name) {}

  public record LoginResult(String token, String role, String sid, String name) {}

  private static final String ACCOUNTS_KEY = "accounts";
  /** 空库引导：还没有任何账号时，用这个密码作管理员登录，随后可在用户权限页建号。 */
  private static final String BOOTSTRAP_PASSWORD = "123";

  private final StorageService storageService;
  private final ObjectMapper objectMapper;
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final long tokenTtlHours;
  private final SecureRandom secureRandom = new SecureRandom();

  public AppAuthService(
      StorageService storageService,
      ObjectMapper objectMapper,
      AuthRepository authRepository,
      PasswordService passwordService,
      @Value("${app.auth.token-ttl-hours:12}") long tokenTtlHours
  ) {
    this.storageService = storageService;
    this.objectMapper = objectMapper;
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.tokenTtlHours = tokenTtlHours;
  }

  public LoginResult login(String password) {
    if (password == null || password.isBlank()) {
      throw unauthorized("请输入密码");
    }
    String trimmed = password.trim();
    long tenantId = TenantDefaults.DEFAULT_TENANT_ID;
    AuthUser user = findByPassword(tenantId, trimmed)
        .or(() -> provisionFromLegacyAccounts(tenantId, trimmed))
        .or(() -> bootstrapAdmin(tenantId, trimmed))
        .orElseThrow(() -> unauthorized("账号或密码不正确"));
    String token = newToken();
    authRepository.createToken(token, tenantId, user.id(), OffsetDateTime.now().plusHours(tokenTtlHours));
    return new LoginResult(token, legacyRoleLabel(user.role()), user.storeId(), user.displayName());
  }

  public void logout(String authorization) {
    String token = extractToken(authorization);
    if (token != null) {
      authRepository.deleteToken(token);
    }
  }

  /** 老系统密码即身份：在租户的启用用户里逐个 bcrypt 比对。用户量为几十级，可接受。 */
  private Optional<AuthUser> findByPassword(long tenantId, String password) {
    return authRepository.users(tenantId).stream()
        .filter(AuthUser::enabled)
        .filter(u -> u.passwordHash() != null && !u.passwordHash().isBlank())
        .filter(u -> passwordService.matches(password, u.passwordHash()))
        .findFirst();
  }

  /** 旧 accounts blob 命中时把账号迁移进 auth_user（密码转 bcrypt），再按新体系登录。 */
  private Optional<AuthUser> provisionFromLegacyAccounts(long tenantId, String password) {
    Account matched = readAccounts().stream()
        .filter(a -> a.pass() != null && a.pass().equals(password))
        .findFirst()
        .orElse(null);
    if (matched == null) {
      return Optional.empty();
    }
    String username = legacyUsername(matched);
    if (username.isBlank() || authRepository.userExists(tenantId, username)) {
      // 同名用户已存在但密码不匹配：以 auth_user 为准，不让旧 blob 覆盖新密码。
      return Optional.empty();
    }
    String role = legacyRoleCode(matched.role());
    String storeId = "STORE_MANAGER".equals(role) ? matched.sid() : null;
    String displayName = matched.name() == null || matched.name().isBlank() ? matched.role() : matched.name();
    authRepository.createUser(tenantId, username, passwordService.hash(password), displayName, role, storeId);
    return authRepository.findByUsername(tenantId, username);
  }

  /** 空库引导：没有任何用户、也没有旧 accounts blob 时，123 建 admin。 */
  private Optional<AuthUser> bootstrapAdmin(long tenantId, String password) {
    if (!BOOTSTRAP_PASSWORD.equals(password)) {
      return Optional.empty();
    }
    if (!authRepository.users(tenantId).isEmpty() || !readAccounts().isEmpty()) {
      return Optional.empty();
    }
    authRepository.createUser(tenantId, "admin", passwordService.hash(password), "系统管理员", "ADMIN", null);
    return authRepository.findByUsername(tenantId, "admin");
  }

  private List<Account> readAccounts() {
    Optional<String> raw = storageService.get(ACCOUNTS_KEY);
    if (raw.isEmpty() || raw.get().isBlank()) {
      return List.of();
    }
    try {
      List<Account> list = objectMapper.readValue(raw.get(), new TypeReference<List<Account>>() {});
      return list == null ? List.of() : list;
    } catch (Exception ex) {
      // accounts blob 损坏时不放行，避免异常被误当成"无账号"从而触发引导登录。
      throw new BusinessException("ACCOUNTS_UNREADABLE", "账号数据异常，请联系管理员", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  /** 旧页面角色名 → auth_user 角色码。 */
  private String legacyRoleCode(String label) {
    if (label == null) return "STORE_MANAGER";
    return switch (label.trim()) {
      case "老板" -> "BOSS";
      case "管理员" -> "ADMIN";
      case "财务" -> "FINANCE";
      case "督导" -> "SUPERVISOR";
      case "仓库管理员", "仓库" -> "WAREHOUSE";
      case "运营" -> "OPERATIONS";
      default -> "STORE_MANAGER";
    };
  }

  /** auth_user 角色码 → 旧页面显示的角色名（页面按这些中文名控制界面权限）。 */
  private String legacyRoleLabel(String role) {
    if (role == null) return "店长";
    return switch (role) {
      case "BOSS", "OWNER" -> "老板";
      case "ADMIN" -> "管理员";
      case "FINANCE" -> "财务";
      case "SUPERVISOR" -> "督导";
      case "WAREHOUSE" -> "仓库管理员";
      case "OPERATIONS" -> "运营";
      case "STORE_MANAGER" -> "店长";
      default -> role;
    };
  }

  private String legacyUsername(Account account) {
    String role = legacyRoleCode(account.role());
    if ("STORE_MANAGER".equals(role)) {
      return account.sid() == null ? "" : account.sid().trim();
    }
    return switch (role) {
      case "BOSS" -> "boss";
      case "ADMIN" -> "admin";
      case "FINANCE" -> "finance";
      case "SUPERVISOR" -> "supervisor";
      case "WAREHOUSE" -> "warehouse";
      case "OPERATIONS" -> "operations";
      default -> "";
    };
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

  private BusinessException unauthorized(String message) {
    return new BusinessException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED);
  }
}
