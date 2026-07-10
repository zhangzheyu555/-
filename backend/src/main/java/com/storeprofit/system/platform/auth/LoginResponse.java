package com.storeprofit.system.platform.auth;

import com.storeprofit.system.platform.session.SessionUser;

public record LoginResponse(
    String token,
    SessionUser user
) {
}
