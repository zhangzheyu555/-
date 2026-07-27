package com.storeprofit.system.audit;

import java.util.List;

public record OperationLogQueryResponse(
    List<OperationLogResponse> rows,
    long total,
    int page,
    int pageSize,
    int totalPages,
    List<String> operators,
    List<String> actions
) {
}
