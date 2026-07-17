package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.util.List;

/**
 * 企迈门店销售汇总（按门店维度聚合整段区间）。
 *
 * @param mode        LIVE=真实接口；UNCONFIGURED=未配置；ERROR=平台调用失败
 * @param note        展示给前端的业务状态说明，不含凭证或技术堆栈
 * @param days        统计窗口天数
 * @param generatedAt 生成时间
 * @param totalAmount 应收合计
 * @param income      商家实收合计（营业额）
 * @param cost        成本合计
 * @param refund      退款合计
 * @param profit      毛利合计（实收 - 成本）
 * @param orderCount  商品记录数
 * @param shops       各门店聚合明细
 * @param items       各门店商品销量明细（同一批企迈调用顺带聚合，不额外请求）
 */
public record QmaiSummaryResponse(
    String mode,
    String note,
    int days,
    String generatedAt,
    BigDecimal totalAmount,
    BigDecimal income,
    BigDecimal cost,
    BigDecimal refund,
    BigDecimal profit,
    long orderCount,
    List<Row> shops,
    List<Item> items
) {
  /** 门店聚合行。 */
  public record Row(
      String shopCode,
      String shopName,
      String bizDate,
      long validOrderCount,
      BigDecimal totalAmountSum,
      BigDecimal incomeSum,
      BigDecimal costSum,
      BigDecimal refundSum,
      BigDecimal profitSum
  ) {}

  /** 门店 × 商品聚合行（整段区间内销量与金额累加）。 */
  public record Item(
      String shopCode,
      String shopName,
      String itemName,
      String categoryName,
      BigDecimal num,
      BigDecimal incomeSum,
      BigDecimal costSum,
      BigDecimal refundSum,
      BigDecimal refundNum
  ) {}
}
