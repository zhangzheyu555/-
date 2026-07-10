package com.storeprofit.system.importing;

import java.util.List;

public record ProfitImportCommitResponse(
    int saved,
    int skipped,
    List<ProfitImportRow> rows
) {
}
