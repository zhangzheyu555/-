package com.storeprofit.system.platform.mobile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MobileVersionControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final MobileVersionProperties properties = new MobileVersionProperties();
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new MobileVersionController(authService, properties))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void requiresBearerSession() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(get("/api/mobile/version").param("platform", "android"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void defaultMetadataCannotAccidentallyForceAnUpdate() throws Exception {
    AuthUser user = user();
    when(authService.requireUser("Bearer mobile-token")).thenReturn(user);

    mockMvc.perform(get("/api/mobile/version")
            .header("Authorization", "Bearer mobile-token")
            .param("platform", "ios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentVersion").value("0.1.0"))
        .andExpect(jsonPath("$.data.minimumVersion").value("0.1.0"))
        .andExpect(jsonPath("$.data.updateAvailable").value(false))
        .andExpect(jsonPath("$.data.forceUpdate").value(false))
        .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
  }

  @Test
  void returnsConfiguredPublicAndroidReleaseMetadata() throws Exception {
    AuthUser user = user();
    when(authService.requireUser("Bearer mobile-token")).thenReturn(user);
    MobileVersionProperties.PlatformVersion android = properties.getAndroid();
    android.setCurrentVersion("0.2.0");
    android.setMinimumVersion("0.1.5");
    android.setUpdateAvailable(true);
    android.setForceUpdate(true);
    android.setDownloadUrl("https://download.example.com/app-0.2.0.apk");
    android.setMessage("发现安全更新");

    mockMvc.perform(get("/api/mobile/version")
            .header("Authorization", "Bearer mobile-token")
            .param("platform", "ANDROID")
            .param("version", "0.1.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentVersion").value("0.2.0"))
        .andExpect(jsonPath("$.data.minimumVersion").value("0.1.5"))
        .andExpect(jsonPath("$.data.updateAvailable").value(true))
        .andExpect(jsonPath("$.data.forceUpdate").value(true))
        .andExpect(jsonPath("$.data.downloadUrl")
            .value("https://download.example.com/app-0.2.0.apk"))
        .andExpect(jsonPath("$.data.message").value("发现安全更新"));

    mockMvc.perform(get("/api/mobile/version")
            .header("Authorization", "Bearer mobile-token")
            .param("platform", "android")
            .param("version", "0.2.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.updateAvailable").value(false))
        .andExpect(jsonPath("$.data.forceUpdate").value(false))
        .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
  }

  @Test
  void rejectsUnsupportedPlatformAfterAuthentication() throws Exception {
    AuthUser user = user();
    when(authService.requireUser("Bearer mobile-token")).thenReturn(user);

    mockMvc.perform(get("/api/mobile/version")
            .header("Authorization", "Bearer mobile-token")
            .param("platform", "h5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MOBILE_PLATFORM_INVALID"));

    verify(authService).requireUser("Bearer mobile-token");
  }

  @Test
  void rejectsMissingPlatformWithAStableBusinessError() throws Exception {
    when(authService.requireUser("Bearer mobile-token")).thenReturn(user());

    mockMvc.perform(get("/api/mobile/version")
            .header("Authorization", "Bearer mobile-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MOBILE_PLATFORM_INVALID"));
  }

  @Test
  void refusesUnsafeDownloadSchemesEvenWhenReleaseFlagsAreEnabled() throws Exception {
    when(authService.requireUser("Bearer mobile-token")).thenReturn(user());
    MobileVersionProperties.PlatformVersion android = properties.getAndroid();
    android.setCurrentVersion("0.2.0");
    android.setMinimumVersion("0.1.5");
    android.setUpdateAvailable(true);
    android.setForceUpdate(true);

    for (String unsafeUrl : new String[] {
        "http://download.example.com/app.apk",
        "javascript:alert(1)",
        "https://release-user:release-password@download.example.com/app.apk",
        "/downloads/app.apk"
    }) {
      android.setDownloadUrl(unsafeUrl);
      mockMvc.perform(get("/api/mobile/version")
              .header("Authorization", "Bearer mobile-token")
              .param("platform", "android")
              .param("version", "0.1.0"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.updateAvailable").value(false))
          .andExpect(jsonPath("$.data.forceUpdate").value(false))
          .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
    }
  }

  private AuthUser user() {
    return new AuthUser(7L, 1L, "测试企业", "employee", "", "员工", "EMPLOYEE", "s1", true);
  }
}
