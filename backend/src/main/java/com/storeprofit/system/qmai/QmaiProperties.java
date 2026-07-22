package com.storeprofit.system.qmai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企迈（Qmai）开放平台接入配置。
 *
 * <p>凭证三件套 openId / grantCode / openKey 均通过环境变量注入（见 application.yml 的
 * {@code app.qmai.*}），禁止写入仓库明文，也不下发前端。openKey 仅用于服务端计算签名，
 * 绝不随请求发送。未配置凭证时服务返回未配置状态，不会生成任何模拟营业数据。
 *
 * <p>网关与签名规范以企迈开放平台文档（https://open.qmai.com 文档中心 / openapi.qmai.cn）为准。
 */
@Component
@ConfigurationProperties(prefix = "app.qmai")
public class QmaiProperties {
  /** 应用/门店凭证。openId 为应用标识，grantCode 为门店授权码，openKey 为签名密钥。 */
  private String openId = "";
  private String grantCode = "";
  private String openKey = "";

  /** 开放平台网关地址。 */
  private String baseUrl = "https://openapi.qmai.cn";
  /** 接口版本号，参与签名。 */
  private String version = "1.0";

  /**
   * 已授权门店清单（shopCode:shopName:storeId，逗号分隔）。
   *
   * <p>storeId 用于把企迈门店映射到本系统门店；门店受限账号不会读取缺 storeId 的旧格式条目。
   */
  private List<String> shops = new ArrayList<>();

  private Duration timeout = Duration.ofSeconds(20);

  /**
   * Outbound access is opt-in.  Local QA can only call a loopback mock; a real integration
   * requires an explicit LIVE setting supplied by deployment configuration.
   */
  private String outboundMode = "DISABLED";

  /** QMAI-04 配方用量能力默认关闭，只有完成专项验收后才允许显式开启。 */
  private boolean recipeEnabled = false;

  /** AES key (base64, 16/24/32 bytes) used only to encrypt persisted platform secrets. */
  private String credentialEncryptionKey = "";

  /** 是否具备真实调用凭证。缺任意一项即不能发起真实业务查询。 */
  public boolean isConfigured() {
    return notBlank(openId) && notBlank(grantCode) && notBlank(openKey);
  }

  private boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  /** 按数据范围解析门店映射；allowedStoreIds 为 null 表示已验证的 ALL 范围。 */
  public List<ShopMapping> resolveShops(Collection<String> allowedStoreIds) {
    return parseShops(shops, allowedStoreIds);
  }

  /** 解析门店条目列表（供数据库配置复用），按数据范围过滤。 */
  public static List<ShopMapping> parseShops(List<String> entries, Collection<String> allowedStoreIds) {
    List<ShopMapping> resolved = new ArrayList<>();
    if (entries == null) {
      return resolved;
    }
    for (String entry : entries) {
      ShopMapping mapping = parseShop(entry);
      if (mapping == null) {
        continue;
      }
      if (allowedStoreIds == null || (mapping.storeId() != null
          && allowedStoreIds.contains(mapping.storeId()))) {
        resolved.add(mapping);
      }
    }
    return resolved;
  }

  public int shopCount(Collection<String> allowedStoreIds) {
    return resolveShops(allowedStoreIds).size();
  }

  private static ShopMapping parseShop(String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String[] parts = entry.split(":", 3);
    String shopCode = parts[0].trim();
    if (shopCode.isEmpty()) {
      return null;
    }
    String shopName = parts.length > 1 ? parts[1].trim() : shopCode;
    String storeId = parts.length > 2 && !parts[2].trim().isEmpty() ? parts[2].trim() : null;
    return new ShopMapping(shopCode, shopName, storeId);
  }

  /** 企迈门店映射：shopCode=企迈门店编码，storeId=本系统门店 id。 */
  public record ShopMapping(String shopCode, String shopName, String storeId) {}

  public String getOpenId() {
    return openId;
  }

  public void setOpenId(String openId) {
    this.openId = openId;
  }

  public String getGrantCode() {
    return grantCode;
  }

  public void setGrantCode(String grantCode) {
    this.grantCode = grantCode;
  }

  public String getOpenKey() {
    return openKey;
  }

  public void setOpenKey(String openKey) {
    this.openKey = openKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public List<String> getShops() {
    return shops;
  }

  public void setShops(List<String> shops) {
    this.shops = shops;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public String getOutboundMode() {
    return outboundMode;
  }

  public void setOutboundMode(String outboundMode) {
    this.outboundMode = outboundMode;
  }

  public boolean isRecipeEnabled() {
    return recipeEnabled;
  }

  public void setRecipeEnabled(boolean recipeEnabled) {
    this.recipeEnabled = recipeEnabled;
  }

  public String getCredentialEncryptionKey() {
    return credentialEncryptionKey;
  }

  public void setCredentialEncryptionKey(String credentialEncryptionKey) {
    this.credentialEncryptionKey = credentialEncryptionKey;
  }
}
