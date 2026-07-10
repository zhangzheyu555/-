package com.storeprofit.system.appauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.storage.StorageService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 旧版页面（index.html + database.js）的登录鉴权。原先登录完全在浏览器里做，
 * 账号表通过无鉴权的 /api/storage 下发，任何人都能读走全部数据。
 * 这里把登录搬到后端：密码在服务端对 accounts blob 校验，通过后发一个 token，
 * 之后所有 /api/storage 调用必须带这个 token。会话放内存 map（单实例部署足够，
 * 重启后需重新登录），不建表、不加数据库迁移，改动最小、可回滚。
 */
@Service
public class AppAuthService {

  /** accounts blob 里的一条账号；只取需要的字段，其余忽略。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Account(String pass, String role, String sid, String name) {}

  public record LoginResult(String token, String role, String sid, String name) {}

  private record Session(String role, String sid, String name, Instant expiresAt) {}

  private static final String ACCOUNTS_KEY = "accounts";
  /** 空库引导：还没有任何账号时，用这个密码作管理员登录，随后前端会自动建全套账号。 */
  private static final String BOOTSTRAP_PASSWORD = "123";

  private final StorageService storageService;
  private final ObjectMapper objectMapper;
  private final long tokenTtlHours;
  private final SecureRandom secureRandom = new SecureRandom();
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  public AppAuthService(
      StorageService storageService,
      ObjectMapper objectMapper,
      @Value("${app.auth.token-ttl-hours:12}") long tokenTtlHours
  ) {
    this.storageService = storageService;
    this.objectMapper = objectMapper;
    this.tokenTtlHours = tokenTtlHours;
  }

  public LoginResult login(String password) {
    if (password == null || password.isBlank()) {
      throw unauthorized("请输入密码");
    }
    Account matched = findAccount(password.trim());
    String token = newToken();
    sessions.put(token, new Session(
        matched.role(), matched.sid(), matched.name(),
        Instant.now().plusSeconds(tokenTtlHours * 3600)
    ));
    return new LoginResult(token, matched.role(), matched.sid(), matched.name());
  }

  public void logout(String authorization) {
    String token = extractToken(authorization);
    if (token != null) {
      sessions.remove(token);
    }
  }

  /** 校验 token，无效/过期抛 401。供 StorageController 守门用。 */
  public void requireSession(String authorization) {
    String token = extractToken(authorization);
    if (token == null) {
      throw unauthorized("请先登录");
    }
    Session session = sessions.get(token);
    if (session == null) {
      throw unauthorized("登录已失效，请重新登录");
    }
    if (session.expiresAt().isBefore(Instant.now())) {
      sessions.remove(token);
      throw unauthorized("登录已失效，请重新登录");
    }
  }

  private Account findAccount(String password) {
    List<Account> accounts = readAccounts();
    if (accounts.isEmpty()) {
      // 空库引导：还没建任何账号时，只认 123 作管理员，登录后前端会自动补齐账号表。
      if (BOOTSTRAP_PASSWORD.equals(password)) {
        return new Account(BOOTSTRAP_PASSWORD, "管理员", "", "系统管理员");
      }
      throw unauthorized("账号或密码不正确");
    }
    return accounts.stream()
        .filter(a -> a.pass() != null && a.pass().equals(password))
        .findFirst()
        .orElseThrow(() -> unauthorized("账号或密码不正确"));
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
