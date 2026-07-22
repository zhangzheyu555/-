package com.storeprofit.system.platform.auth;

import com.storeprofit.system.platform.session.SessionUser;

public record LoginResponse(
    String token,
    SessionUser user,
    String status,
    String passwordChangeCredential
) {
  public static LoginResponse authenticated(String token, SessionUser user) {
    return new LoginResponse(token, user, "AUTHENTICATED", null);
  }

  public static LoginResponse passwordChangeRequired(String credential) {
    return new LoginResponse(null, null, "PASSWORD_CHANGE_REQUIRED", credential);
  }
}
