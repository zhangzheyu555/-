package com.storeprofit.system.qmai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.qmai")
public class QmaiProperties {
  private String openId = "";
  private String grantCode = "";
  private String openKey = "";
  private String baseUrl = "https://openapi.qmai.cn";
  private Duration timeout = Duration.ofSeconds(20);
  private int maxRetries = 4;
  private int concurrency = 4;

  public boolean isConfigured() {
    return notBlank(openId) && notBlank(grantCode) && notBlank(openKey);
  }

  public URI validatedBaseUri() {
    URI uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || !"openapi.qmai.cn".equalsIgnoreCase(uri.getHost())) {
      throw new IllegalStateException("企迈网关必须使用 https://openapi.qmai.cn");
    }
    return uri;
  }

  private boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  public String getOpenId() { return openId; }
  public void setOpenId(String openId) { this.openId = safe(openId); }
  public String getGrantCode() { return grantCode; }
  public void setGrantCode(String grantCode) { this.grantCode = safe(grantCode); }
  public String getOpenKey() { return openKey; }
  public void setOpenKey(String openKey) { this.openKey = safe(openKey); }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = safe(baseUrl); }
  public Duration getTimeout() { return timeout; }
  public void setTimeout(Duration timeout) { this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout; }
  public int getMaxRetries() { return maxRetries; }
  public void setMaxRetries(int maxRetries) { this.maxRetries = Math.max(1, Math.min(maxRetries, 6)); }
  public int getConcurrency() { return concurrency; }
  public void setConcurrency(int concurrency) { this.concurrency = Math.max(1, Math.min(concurrency, 8)); }

  private String safe(String value) { return value == null ? "" : value.trim(); }
}
