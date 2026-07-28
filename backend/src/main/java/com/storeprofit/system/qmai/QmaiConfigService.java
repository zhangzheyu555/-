package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企迈凭证配置服务：数据库配置（网页表单保存）优先，回退 application.yml 环境变量默认值。
 * 支持多品牌（茹菓/霸王茶姬…），每品牌独立一套凭证。
 *
 * <p>openKey、商户后台密码只在服务端使用，读取接口（{@link #maskedView}）绝不回传明文，
 * 只给掩码与是否已配置。
 */
@Service
public class QmaiConfigService {
  /** 默认品牌（既有茹菓配置归属此品牌，且只有它回退环境变量凭证）。 */
  public static final String DEFAULT_BRAND = "ruguo";

  private final QmaiProperties properties;
  private final QmaiConfigRepository repository;
  private final QmaiCredentialCipher credentialCipher;
  private final QmaiOutboundPolicy outboundPolicy;

  public QmaiConfigService(
      QmaiProperties properties,
      QmaiConfigRepository repository,
      QmaiCredentialCipher credentialCipher,
      QmaiOutboundPolicy outboundPolicy
  ) {
    this.properties = properties;
    this.repository = repository;
    this.credentialCipher = credentialCipher;
    this.outboundPolicy = outboundPolicy;
  }

  /** 品牌参数归一化：空值回退默认品牌。 */
  public static String normBrand(String brand) {
    return brand == null || brand.isBlank() ? DEFAULT_BRAND : brand.trim();
  }

  /** 生效配置：数据库值覆盖环境默认；环境回退仅默认品牌享有，避免新品牌串用茹菓凭证。 */
  public EffectiveConfig resolve(long tenantId, String brand) {
    String b = normBrand(brand);
    boolean envFallback = DEFAULT_BRAND.equals(b);
    Optional<QmaiConfigRepository.QmaiConfigRow> row = repository.find(tenantId, b);
    String openId = envFallback ? properties.getOpenId() : "";
    String grantCode = envFallback ? properties.getGrantCode() : "";
    String openKey = envFallback ? properties.getOpenKey() : "";
    String baseUrl = properties.getBaseUrl();
    String version = properties.getVersion();
    List<String> shops = envFallback ? properties.getShops() : List.of();
    String consoleAccount = "";
    String consolePassword = "";
    String consoleToken = "";
    String source = "ENV";
    if (row.isPresent()) {
      QmaiConfigRepository.QmaiConfigRow r = row.get();
      if (notBlank(r.openId())) {
        openId = credentialCipher.decrypt(r.openId());
      }
      if (notBlank(r.grantCode())) {
        grantCode = credentialCipher.decrypt(r.grantCode());
      }
      if (notBlank(r.openKey())) {
        openKey = credentialCipher.decrypt(r.openKey());
      }
      if (notBlank(r.baseUrl())) {
        baseUrl = r.baseUrl();
      }
      if (notBlank(r.version())) {
        version = r.version();
      }
      // A database row is canonical even when its shop list is explicitly empty. Falling back
      // to QMAI_SHOPS here would silently resurrect mappings that an administrator just cleared.
      shops = splitCsv(r.shops() == null ? "" : r.shops());
      if (notBlank(r.consoleAccount())) {
        consoleAccount = credentialCipher.decrypt(r.consoleAccount());
      }
      if (notBlank(r.consolePassword())) {
        consolePassword = credentialCipher.decrypt(r.consolePassword());
      }
      if (notBlank(r.consoleToken())) {
        consoleToken = credentialCipher.decrypt(r.consoleToken());
      }
      source = "DB";
    }
    return new EffectiveConfig(
        openId, grantCode, openKey, baseUrl, version, properties.getTimeout(), shops,
        consoleAccount, consolePassword, consoleToken, source);
  }

  /** 保存网页表单提交的配置（upsert）。空字段保留原值，openKey/后台密码为空时不覆盖已存值。 */
  @Transactional
  public void save(long tenantId, String brand, QmaiConfigForm form, Long actorId,
      String actorName) {
    if (form == null) {
      throw new BusinessException(
          "QMAI_CONFIG_INVALID", "企迈配置不能为空", HttpStatus.BAD_REQUEST);
    }
    String b = normBrand(brand);
    Optional<QmaiConfigRepository.QmaiConfigRow> existing = repository.find(tenantId, b);
    String openId = pick(form.openId(), existing.map(r -> credentialCipher.decrypt(r.openId())));
    String grantCode = pick(form.grantCode(),
        existing.map(r -> credentialCipher.decrypt(r.grantCode())));
    // openKey / 后台密码属敏感项：表单留空表示“不修改”，沿用已存值。
    String openKey = pick(form.openKey(), existing.map(r -> credentialCipher.decrypt(r.openKey())));
    String consoleAccount = pick(form.consoleAccount(),
        existing.map(r -> credentialCipher.decrypt(r.consoleAccount())));
    String consolePassword = pick(form.consolePassword(),
        existing.map(r -> credentialCipher.decrypt(r.consolePassword())));
    String consoleToken = pick(form.consoleToken(),
        existing.map(r -> credentialCipher.decrypt(r.consoleToken())));
    String baseUrl = form.baseUrl() != null && !form.baseUrl().isBlank()
        ? form.baseUrl().trim() : properties.getBaseUrl();
    outboundPolicy.requireValidBaseUrl(baseUrl);
    String version = form.version() != null && !form.version().isBlank()
        ? form.version().trim() : properties.getVersion();
    String rawShops = form.shops() != null
        ? form.shops()
        : existing.map(QmaiConfigRepository.QmaiConfigRow::shops)
            .orElseGet(() -> DEFAULT_BRAND.equals(b)
                ? String.join(",", properties.getShops()) : "");
    List<QmaiProperties.ShopMapping> mappings =
        parseAndValidateMappings(tenantId, rawShops);
    String shops = mappings.stream()
        .map(mapping -> mapping.shopCode() + ":" + mapping.shopName() + ":" + mapping.storeId())
        .collect(java.util.stream.Collectors.joining(","));
    repository.upsert(tenantId, b, credentialCipher.encrypt(openId), credentialCipher.encrypt(grantCode),
        credentialCipher.encrypt(openKey), baseUrl, version, shops,
        credentialCipher.encrypt(consoleAccount), credentialCipher.encrypt(consolePassword),
        credentialCipher.encrypt(consoleToken), actorId, actorName);
    repository.replaceStoreMappings(tenantId, b, mappings);
  }

  /**
   * Platform configuration is the single source of truth for shop mappings.
   * The normalized V60 mapping table is only a transactionally refreshed mirror.
   */
  private List<QmaiProperties.ShopMapping> parseAndValidateMappings(
      long tenantId, String rawShops) {
    if (rawShops == null || rawShops.isBlank()) {
      return List.of();
    }
    List<QmaiProperties.ShopMapping> mappings = new ArrayList<>();
    Set<String> shopIds = new LinkedHashSet<>();
    Set<String> storeIds = new LinkedHashSet<>();
    for (String rawEntry : rawShops.split(",")) {
      String entry = rawEntry == null ? "" : rawEntry.trim();
      if (entry.isEmpty()) {
        continue;
      }
      String[] parts = entry.split(":", -1);
      if (parts.length != 3) {
        throw mappingError("每个企迈门店都必须填写对应的系统门店");
      }
      String shopId = parts[0].trim();
      String shopName = parts[1].trim();
      String storeId = parts[2].trim();
      if (!shopId.matches("\\d+") || shopName.isEmpty() || storeId.isEmpty()) {
        throw mappingError("企迈门店映射格式不正确");
      }
      if (shopId.length() > 80 || shopName.length() > 160 || storeId.length() > 64) {
        throw mappingError("企迈门店映射内容过长");
      }
      if (!shopIds.add(shopId) || !storeIds.add(storeId)) {
        throw mappingError("企迈门店与系统门店必须一一对应");
      }
      if (!repository.storeExists(tenantId, storeId)) {
        throw mappingError("企迈门店对应的系统门店不存在");
      }
      mappings.add(new QmaiProperties.ShopMapping(shopId, shopName, storeId));
    }
    return List.copyOf(mappings);
  }

  private BusinessException mappingError(String message) {
    return new BusinessException(
        "QMAI_SHOP_MAPPING_INVALID", message, HttpStatus.BAD_REQUEST);
  }

  /** 给前端的安全视图：不含任何明文密钥，只给是否已配置、掩码与门店。 */
  public Map<String, Object> maskedView(long tenantId, String brand) {
    String b = normBrand(brand);
    EffectiveConfig cfg = resolve(tenantId, b);
    Optional<QmaiConfigRepository.QmaiConfigRow> row = repository.find(tenantId, b);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("platform", "企迈");
    view.put("brand", b);
    view.put("configured", cfg.isConfigured());
    view.put("source", cfg.source());
    view.put("openIdMasked", mask(cfg.openId()));
    view.put("grantCodeMasked", mask(cfg.grantCode()));
    view.put("openKeySet", notBlank(cfg.openKey()));
    view.put("consoleAccountMasked", mask(cfg.consoleAccount()));
    view.put("consolePasswordSet", notBlank(cfg.consolePassword()));
    view.put("consoleTokenSet", notBlank(cfg.consoleToken()));
    view.put("baseUrl", cfg.baseUrl());
    view.put("version", cfg.version());
    view.put("shops", cfg.shops() == null ? "" : String.join(",", cfg.shops()));
    view.put("updatedBy", row.map(QmaiConfigRepository.QmaiConfigRow::updatedByName).orElse(null));
    view.put("updatedAt", row.map(QmaiConfigRepository.QmaiConfigRow::updatedAt).orElse(null));

    // 精确提示还差哪几项，便于只有 id/secret、暂无门店授权码的用户按需补齐。
    List<String> missing = new java.util.ArrayList<>();
    if (!notBlank(cfg.openId())) {
      missing.add("应用ID（openId）");
    }
    if (!notBlank(cfg.openKey())) {
      missing.add("签名密钥（openKey）");
    }
    if (!notBlank(cfg.grantCode())) {
      missing.add("门店授权码（grantCode）");
    }
    view.put("missing", missing);
    view.put("statusText", cfg.isConfigured() ? "已配置，可拉取数据"
        : (missing.isEmpty() ? "未配置" : "还差：" + String.join("、", missing)));
    return view;
  }

  private String pick(String incoming, Optional<String> fallback) {
    if (incoming != null && !incoming.isBlank()) {
      return incoming.trim();
    }
    return fallback.filter(this::notBlank).orElse("");
  }

  private List<String> splitCsv(String csv) {
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private String mask(String v) {
    if (v == null || v.isBlank()) {
      return "";
    }
    String s = v.trim();
    if (s.length() <= 4) {
      return "****";
    }
    return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
  }

  /** 生效配置（合并后），供 QmaiOrderService 使用。 */
  public record EffectiveConfig(
      String openId,
      String grantCode,
      String openKey,
      String baseUrl,
      String version,
      Duration timeout,
      List<String> shops,
      String consoleAccount,
      String consolePassword,
      String consoleToken,
      String source
  ) {
    public boolean isConfigured() {
      return nb(openId) && nb(grantCode) && nb(openKey);
    }

    /** 是否已配置商户后台登录凭证（备用抓取通道）。 */
    public boolean hasConsoleLogin() {
      return nb(consoleAccount) && nb(consolePassword);
    }

    /** 是否已粘贴商户后台登录令牌（qm_seller_token）。 */
    public boolean hasConsoleToken() {
      return nb(consoleToken);
    }

    public List<QmaiProperties.ShopMapping> resolveShops(Collection<String> allowedStoreIds) {
      return QmaiProperties.parseShops(shops, allowedStoreIds);
    }

    private static boolean nb(String v) {
      return v != null && !v.isBlank();
    }
  }

  /** 网页表单提交体。openKey / consolePassword 留空表示不修改。 */
  public record QmaiConfigForm(
      String openId,
      String grantCode,
      String openKey,
      String baseUrl,
      String version,
      String shops,
      String consoleAccount,
      String consolePassword,
      String consoleToken
  ) {}
}
