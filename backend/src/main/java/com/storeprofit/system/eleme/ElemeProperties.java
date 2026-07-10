package com.storeprofit.system.eleme;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
  /** 已授权门店清单（shopId:shopName，逗号分隔），用于示例数据与门店维度聚合。 */
  private List<String> shops = new ArrayList<>();
  private Duration timeout = Duration.ofSeconds(20);

  /** 是否具备真实调用凭证。缺任意一项即不能发起真实订单查询。 */
  public boolean isConfigured() {
    return notBlank(appKey) && notBlank(appSecret) && notBlank(accessToken);
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

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }
}
