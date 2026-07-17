package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.util.List;

/**
 * 企迈商户后台「营业收入（按支付渠道）」汇总（令牌复用通道抓取）。
 *
 * @param mode         LIVE=真实数据；UNCONFIGURED=未配置令牌/门店；ERROR=抓取失败（如令牌失效）
 * @param note         展示给前端的状态说明
 * @param rangeLabel   统计区间标签（如 2026年7月）
 * @param generatedAt  生成时间
 * @param totalRevenue 营业收入合计（元）
 * @param totalCount   订单数合计
 * @param channels     各支付渠道明细（按营业额降序）
 */
public record QmaiConsoleIncomeResponse(
    String mode,
    String note,
    String rangeLabel,
    String generatedAt,
    BigDecimal totalRevenue,
    long totalCount,
    List<Channel> channels
) {
  /** 支付渠道行（如美团外卖支付、微信支付、淘宝闪购支付…）。 */
  public record Channel(
      String name,
      BigDecimal revenue,
      long count
  ) {}
}
