package com.storeprofit.system.boss;

import java.util.List;

public record BossDataHealthResponse(
    String dataSource,
    String lastUpdatedAt,
    List<BossDataHealthModuleResponse> modules
) {
}
