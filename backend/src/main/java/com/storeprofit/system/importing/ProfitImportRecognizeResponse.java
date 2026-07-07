package com.storeprofit.system.importing;

import java.util.List;

public record ProfitImportRecognizeResponse(
    String importId,
    ProfitImportSourceType sourceType,
    String status,
    List<ProfitImportRow> rows,
    List<String> errors
) {
}
