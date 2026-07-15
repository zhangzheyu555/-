package com.storeprofit.system.platform.mobile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Public release metadata only. Credentials, signing material and store API keys never belong here. */
@Component
@ConfigurationProperties(prefix = "app.mobile.version")
public class MobileVersionProperties {
  private PlatformVersion android = new PlatformVersion();
  private PlatformVersion ios = new PlatformVersion();

  public PlatformVersion getAndroid() {
    return android;
  }

  public void setAndroid(PlatformVersion android) {
    this.android = android == null ? new PlatformVersion() : android;
  }

  public PlatformVersion getIos() {
    return ios;
  }

  public void setIos(PlatformVersion ios) {
    this.ios = ios == null ? new PlatformVersion() : ios;
  }

  public PlatformVersion forPlatform(String platform) {
    return "android".equals(platform) ? android : ios;
  }

  public static class PlatformVersion {
    private String currentVersion = "0.1.0";
    private String minimumVersion = "0.1.0";
    private boolean updateAvailable;
    private boolean forceUpdate;
    private String downloadUrl = "";
    private String message = "当前已是可用版本";

    public String getCurrentVersion() {
      return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
      this.currentVersion = normalizeVersion(currentVersion);
    }

    public String getMinimumVersion() {
      return minimumVersion;
    }

    public void setMinimumVersion(String minimumVersion) {
      this.minimumVersion = normalizeVersion(minimumVersion);
    }

    public boolean isUpdateAvailable() {
      return updateAvailable;
    }

    public void setUpdateAvailable(boolean updateAvailable) {
      this.updateAvailable = updateAvailable;
    }

    public boolean isForceUpdate() {
      return forceUpdate;
    }

    public void setForceUpdate(boolean forceUpdate) {
      this.forceUpdate = forceUpdate;
    }

    public String getDownloadUrl() {
      return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
      this.downloadUrl = normalizeText(downloadUrl, "");
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = normalizeText(message, "当前已是可用版本");
    }

    private String normalizeVersion(String value) {
      return normalizeText(value, "0.1.0");
    }

    private String normalizeText(String value, String fallback) {
      return value == null || value.isBlank() ? fallback : value.trim();
    }
  }
}
