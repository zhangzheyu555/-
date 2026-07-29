package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企迈凭证配置服务：凭证只由部署环境变量提供，绝不从数据库读取或写入。
 *
 * <p>openKey、商户后台密码只在服务端使用，读取接口（{@link #maskedView}）绝不回传明文，
 * 只给掩码与是否已配置。
 */
@Service
public class QmaiConfigService {
  /** 默认品牌（既有茹菓配置归属此品牌，且只有它回退环境变量凭证）。 */
  public static final String DEFAULT_BRAND = "ruguo";

  private final QmaiProperties properties;
  public QmaiConfigService(
      QmaiProperties properties,
      QmaiConfigRepository repository,
      QmaiCredentialCipher credentialCipher,
      QmaiOutboundPolicy outboundPolicy
  ) {
    this.properties = properties;
  }

  /** 品牌参数归一化：空值回退默认品牌。 */
  public static String normBrand(String brand) {
    return brand == null || brand.isBlank() ? DEFAULT_BRAND : brand.trim();
  }

  /** 生效配置只来自环境变量；历史数据库记录保留但永不参与运行。 */
  public EffectiveConfig resolve(long tenantId, String brand) {
    String b = normBrand(brand);
    return new EffectiveConfig(
        properties.getOpenId(), properties.getGrantCode(), properties.getOpenKey(),
        properties.getBaseUrl(), properties.getVersion(), properties.getTimeout(), properties.getShops(),
        "", "", "", "ENV");
  }

  /** 保存网页表单提交的配置（upsert）。空字段保留原值，openKey/后台密码为空时不覆盖已存值。 */
  @Transactional
  public void save(long tenantId, String brand, QmaiConfigForm form, Long actorId,
      String actorName) {
    throw new BusinessException(
        "QMAI_ENVIRONMENT_MANAGED", "企迈凭证由服务器环境变量管理，请联系系统管理员修改。",
        HttpStatus.CONFLICT);
  }

  /** 给前端的安全视图：不含任何明文密钥，只给是否已配置、掩码与门店。 */
  public Map<String, Object> maskedView(long tenantId, String brand) {
    String b = normBrand(brand);
    EffectiveConfig cfg = resolve(tenantId, b);
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
    view.put("updatedBy", null);
    view.put("updatedAt", null);

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
