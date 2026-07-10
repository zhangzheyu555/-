package com.storeprofit.system.platform.system;

import java.util.List;

public record SystemOverview(
    String appName,
    String version,
    String activeProfile,
    List<String> modules
) {
}
