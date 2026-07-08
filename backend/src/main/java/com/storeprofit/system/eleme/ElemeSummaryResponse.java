package com.storeprofit.system.eleme;

import java.math.BigDecimal;
import java.util.List;

/**
 * 饿了么订单营业额聚合结果（文档步骤 4：按门店 + 日期维度汇总 totalPrice / income）。
 *
 * @param mode        LIVE=已配置凭证调真实接口；DEMO=未配置，返回示例数据
 * @param note        展示给前端的说明（授权限制 / 30 天窗口 / 示例提示）
 * @param days        统计窗口天数（接口仅支持最近 30 天）
 * @param generatedAt 生成时间
 * @param totalPrice  订单总额 SUM(totalPrice)
 * @param income      商家实收 SUM(income)
 * @param orderCount  有效订单数 COUNT(DISTINCT orderId)
 * @param shops       各门店 × 日期聚合明细
 */
public record ElemeSummaryResponse(
    String mode,
    String note,
    int days,
    String generatedAt,
    BigDecimal totalPrice,
    BigDecimal income,
    long orderCount,
    List<Row> shops
) {
  /**
   * 门店 × 日期营收行。
   */
  public record Row(
      String shopId,
      String shopName,
      String bizDate,
      long validOrderCount,
      BigDecimal totalPriceSum,
      BigDecimal incomeSum
  ) {}
}
