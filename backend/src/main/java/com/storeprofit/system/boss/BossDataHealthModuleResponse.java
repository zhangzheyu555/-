package com.storeprofit.system.boss;

public record BossDataHealthModuleResponse(
    String moduleName,
    String status,
    String dataSource,
    String lastUpdatedAt,
    String businessScope,
    String migrationNote,
    String recommendation
) {
}
