package com.storeprofit.system.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StorageWriteRequest(
    // key 用来做数据分区，正常都是 accounts/entries/salary 等短标识，给个宽松上限防滥用
    @NotBlank @Size(max = 200, message = "key 过长") String key,
    // 单个 value 上限 20MB：现有最大 blob（salary）约 200KB，报销含 base64 截图会更大，
    // 20MB 足够正常业务，又能挡住"写超大数据撑爆库"。网关层还有 30MB body 上限兜底。
    @NotNull @Size(max = 20_971_520, message = "数据过大（超过 20MB）") String value
) {
}
