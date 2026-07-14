package com.storeprofit.system.platform.users;

public record UserAccessProfileResponse(
    UserResponse user,
    UserAuthorizationResponse authorization
) {
}
