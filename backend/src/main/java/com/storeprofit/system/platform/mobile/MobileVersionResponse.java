package com.storeprofit.system.platform.mobile;

public record MobileVersionResponse(
    String currentVersion,
    String minimumVersion,
    boolean updateAvailable,
    boolean forceUpdate,
    String downloadUrl,
    String message
) {
}
