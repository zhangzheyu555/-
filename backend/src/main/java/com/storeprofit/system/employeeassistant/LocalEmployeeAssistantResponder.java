package com.storeprofit.system.employeeassistant;

import java.util.Locale;

/**
 * Deterministic, network-free service guidance for deployments without an external model account.
 *
 * <p>The responder receives only the already-sanitized question. It never reads business data and
 * never invents answers outside the built-in service boundaries.</p>
 */
final class LocalEmployeeAssistantResponder {

  LocalAnswer answer(String sanitizedQuestion) {
    String question = normalize(sanitizedQuestion);
    if (containsAny(question, "等待太久", "等太久", "排队", "催单", "出餐慢")) {
      return safe("先向顾客致歉并确认当前进度，说明会立即跟进；给出可执行的预计时间，无法确认时请值班经理协助，不要随意承诺补偿。");
    }
    if (containsAny(question, "太甜", "太淡", "太苦", "口味", "不好喝", "重做")) {
      return safe("先礼貌致歉并确认顾客的口味诉求，再按门店现行服务流程处理；需要重做或其他补救时，请值班经理确认后执行。");
    }
    if (containsAny(question, "会员券", "优惠券", "代金券", "券不能用", "券用不了")) {
      return safe("先核对券的有效期、适用门店、使用时段和使用条件，并向顾客清楚说明；仍无法判断时请值班经理处理，不要自行承诺现金补偿。");
    }
    if (containsAny(question, "交接班", "交班", "接班")) {
      return safe("交接时请依次确认未完成顾客事项、设备与卫生异常、待补物料和需要跟进的服务问题，并由接班人复述确认；不要在助手中填写顾客隐私或经营数据。");
    }
    if (containsAny(question, "漏发", "少发", "缺少", "吸管", "餐具")) {
      return safe("先向顾客致歉并确认缺少的通用物品，再按门店补发流程立即处理；无法现场解决时请值班经理跟进，不要在助手中填写订单号、电话或地址。");
    }
    if (containsAny(question, "投诉", "态度不好", "服务不好", "怎么回复", "怎么回应", "如何接待")) {
      return safe("先耐心听完并复述顾客诉求，再礼貌致歉、说明可立即执行的处理方案；超出本人权限时马上请值班经理接手，并记录处理结果。");
    }
    return new LocalAnswer(
        "这个问题超出本地安全话术范围，请联系值班经理处理；请勿在助手中补充顾客姓名、电话、订单号、地址或经营数据。",
        true,
        "LOCAL_NO_MATCH"
    );
  }

  private LocalAnswer safe(String answer) {
    return new LocalAnswer(answer, false, null);
  }

  private boolean containsAny(String value, String... keywords) {
    for (String keyword : keywords) {
      if (value.contains(keyword)) return true;
    }
    return false;
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT)
        .replaceAll("[\\s，。！？、,.!?:：；;\\-_/()（）]", "");
  }

  record LocalAnswer(String answer, boolean needsHuman, String handoffCategory) {
  }
}
