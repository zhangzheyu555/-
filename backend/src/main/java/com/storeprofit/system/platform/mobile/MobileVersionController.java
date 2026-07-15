package com.storeprofit.system.platform.mobile;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile")
public class MobileVersionController {
  private final AuthService authService;
  private final MobileVersionProperties properties;

  public MobileVersionController(AuthService authService, MobileVersionProperties properties) {
    this.authService = authService;
    this.properties = properties;
  }

  @GetMapping("/version")
  public ApiResponse<MobileVersionResponse> version(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String version
  ) {
    authService.requireUser(authorization);
    String normalizedPlatform = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
    if (!"android".equals(normalizedPlatform) && !"ios".equals(normalizedPlatform)) {
      throw new BusinessException(
          "MOBILE_PLATFORM_INVALID", "仅支持检查 Android 或 iOS 版本", HttpStatus.BAD_REQUEST);
    }
    MobileVersionProperties.PlatformVersion configured = properties.forPlatform(normalizedPlatform);
    String downloadUrl = configured.getDownloadUrl();
    // A force-update flag without a usable public distribution URL would lock users out. Fail
    // closed until both release metadata and the URL are deliberately configured.
    boolean hasDownload = isUsableDownloadUrl(downloadUrl);
    String installedVersion = version == null ? "" : version.trim();
    boolean belowLatest = installedVersion.isBlank()
        || compareVersions(installedVersion, configured.getCurrentVersion()) < 0;
    boolean belowMinimum = !installedVersion.isBlank()
        && compareVersions(installedVersion, configured.getMinimumVersion()) < 0;
    boolean updateAvailable = hasDownload
        && ((configured.isUpdateAvailable() && belowLatest) || belowMinimum);
    boolean forceUpdate = updateAvailable && (configured.isForceUpdate() || belowMinimum);
    return ApiResponse.ok(new MobileVersionResponse(
        configured.getCurrentVersion(),
        configured.getMinimumVersion(),
        updateAvailable,
        forceUpdate,
        updateAvailable ? downloadUrl : null,
        configured.getMessage()
    ));
  }

  private int compareVersions(String left, String right) {
    String[] leftParts = versionCore(left).split("\\.");
    String[] rightParts = versionCore(right).split("\\.");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int index = 0; index < length; index++) {
      int leftValue = index < leftParts.length ? numericPart(leftParts[index]) : 0;
      int rightValue = index < rightParts.length ? numericPart(rightParts[index]) : 0;
      int compared = Integer.compare(leftValue, rightValue);
      if (compared != 0) {
        return compared;
      }
    }
    return 0;
  }

  private boolean isUsableDownloadUrl(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(value.trim());
      return uri.isAbsolute()
          && "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getUserInfo() == null;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private String versionCore(String value) {
    String normalized = value == null ? "" : value.trim();
    int suffix = normalized.indexOf('-');
    return suffix < 0 ? normalized : normalized.substring(0, suffix);
  }

  private int numericPart(String value) {
    String digits = value == null ? "" : value.replaceAll("[^0-9].*$", "");
    if (digits.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException ignored) {
      return Integer.MAX_VALUE;
    }
  }
}
