package com.storeprofit.system.eleme;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 饿了么开放平台接入配置。
 *
 * <p>依据《饿了么开放平台订单营业额获取步骤文档 v1.0》：应用创建后拿到 appKey/appSecret，
 * 门店主动授权后拿到 token，再调用订单列表/详情接口。以上凭证均通过环境变量注入，
 * 禁止写入仓库明文（文档步骤 1 第 4 条）。未配置凭证时服务返回未配置状态，不会生成模拟营业数据。
 */
@Component
@ConfigurationProperties(prefix = "app.eleme")
public class ElemeProperties {
  /** 应用凭证。 */
  private String appKey = "";
  private String appSecret = "";
  /** 开放平台网关地址（订单接口调用入口）。 */
  private String baseUrl = "https://api-be.ele.me/eleme.order.getOrders";
  /** 门店授权令牌；多门店可用逗号分隔配置多个 token。 */
  private String accessToken = "";
  /**
   * 已授权门店清单（shopId:shopName:storeId，逗号分隔）。
   *
   * <p>旧的 shopId:shopName 仍可供全量数据范围账号使用；为避免无法确认业务门店归属，
   * 门店受限账号不会读取未配置 storeId 的旧格式条目。
   */
  private List<String> shops = new ArrayList<>();
  private Duration timeout = Duration.ofSeconds(20);
  /**
   * 回调验签密钥必须独立注入，禁止默认复用开放平台应用密钥。
   * 只有完成平台/网关契约确认后才允许启用回调。
   */
  private String webhookSecret = "";
  /** 当前明确支持的接入侧验签契约；默认关闭以确保未联调环境 fail-closed。 */
  private String webhookSignatureMode = "DISABLED";
  private String webhookSignatureHeader = "X-Eleme-Signature";
  private String webhookEventIdHeader = "X-Eleme-Event-Id";
  private long webhookMaxPayloadBytes = 1_048_576L;

  /** 是否具备真实调用凭证。缺任意一项即不能发起真实订单查询。 */
  public boolean isConfigured() {
    return notBlank(appKey) && notBlank(appSecret) && notBlank(accessToken);
  }

  /** 是否已显式配置当前代码支持的回调验签契约。 */
  public boolean isWebhookConfigured() {
    return notBlank(webhookSecret)
        && "HMAC_SHA256_BODY".equalsIgnoreCase(webhookSignatureMode)
        && notBlank(webhookSignatureHeader)
        && notBlank(webhookEventIdHeader);
  }

  private boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  public String getAppKey() {
    return appKey;
  }

  public void setAppKey(String appKey) {
    this.appKey = appKey;
  }

  public String getAppSecret() {
    return appSecret;
  }

  public void setAppSecret(String appSecret) {
    this.appSecret = appSecret;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public List<String> getShops() {
    return shops;
  }

  public void setShops(List<String> shops) {
    this.shops = shops == null ? new ArrayList<>() : shops;
  }

  /** 返回可用于展示和订单查询的非敏感门店映射。null 表示全量数据范围。 */
  public List<ShopMapping> resolveShops(Collection<String> allowedStoreIds) {
    Set<String> allowed = allowedStoreIds == null ? null : normalizeStoreIds(allowedStoreIds);
    List<ShopMapping> resolved = new ArrayList<>();
    for (String entry : shops) {
      ShopMapping mapping = parseShop(entry);
      if (mapping == null) {
        continue;
      }
      // 门店受限时 fail-closed：旧二段配置没有 storeId，不能猜测归属。
      if (allowed != null
          && (mapping.storeId().isBlank() || !allowed.contains(mapping.storeId()))) {
        continue;
      }
      resolved.add(mapping);
    }
    return List.copyOf(resolved);
  }

  public int shopCount(Collection<String> allowedStoreIds) {
    return resolveShops(allowedStoreIds).size();
  }

  private ShopMapping parseShop(String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String[] parts = entry.split(":", 3);
    String shopId = parts[0].trim();
    if (shopId.isBlank()) {
      return null;
    }
    String shopName = parts.length > 1 && !parts[1].isBlank()
        ? parts[1].trim()
        : shopId;
    String storeId = parts.length > 2 ? parts[2].trim() : "";
    return new ShopMapping(shopId, shopName, storeId);
  }

  private Set<String> normalizeStoreIds(Collection<String> storeIds) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String storeId : storeIds) {
      if (storeId != null && !storeId.isBlank()) {
        normalized.add(storeId.trim());
      }
    }
    return normalized;
  }

  /** 仅包含门店标识，不包含 token、appKey 或密钥。 */
  public record ShopMapping(String shopId, String shopName, String storeId) {
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public String getWebhookSecret() {
    return webhookSecret;
  }

  public void setWebhookSecret(String webhookSecret) {
    this.webhookSecret = webhookSecret;
  }

  public String getWebhookSignatureMode() {
    return webhookSignatureMode;
  }

  public void setWebhookSignatureMode(String webhookSignatureMode) {
    this.webhookSignatureMode = webhookSignatureMode;
  }

  public String getWebhookSignatureHeader() {
    return webhookSignatureHeader;
  }

  public void setWebhookSignatureHeader(String webhookSignatureHeader) {
    this.webhookSignatureHeader = webhookSignatureHeader;
  }

  public String getWebhookEventIdHeader() {
    return webhookEventIdHeader;
  }

  public void setWebhookEventIdHeader(String webhookEventIdHeader) {
    this.webhookEventIdHeader = webhookEventIdHeader;
  }

  public long getWebhookMaxPayloadBytes() {
    return webhookMaxPayloadBytes;
  }

  public void setWebhookMaxPayloadBytes(long webhookMaxPayloadBytes) {
    this.webhookMaxPayloadBytes = webhookMaxPayloadBytes;
  }
}
