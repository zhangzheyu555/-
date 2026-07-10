package com.storeprofit.system.eleme;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 饿了么订单拉取与营业额聚合服务。
 *
 * <p>流程遵循接入文档：授权门店 → 订单列表 eleme.order.getOrders → 订单详情 eleme.order.getOrder
 * → 按 shopId + 日期聚合 totalPrice / income。仅在已配置 appKey/appSecret/token 时调真实接口；
 * 未配置或调用失败时返回空结果和明确状态，绝不伪造营业数据。接口仅支持最近 30 天。
 */
@Service
public class ElemeOrderService {
  private static final Logger log = LoggerFactory.getLogger(ElemeOrderService.class);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int MAX_DAYS = 30;

  private final ElemeProperties properties;

  public ElemeOrderService(ElemeProperties properties) {
    this.properties = properties;
  }

  public ElemeSummaryResponse summary(int requestedDays) {
    int days = Math.max(1, Math.min(requestedDays, MAX_DAYS));
    LocalDate today = LocalDate.now(ZONE);
    return summaryForDates(datesBack(today, days), days,
        "最近 " + days + " 天", null);
  }

  /** 按自然月聚合；文档只支持最近 30 天订单。 */
  public ElemeSummaryResponse summaryForMonth(String month) {
    java.time.YearMonth ym;
    try {
      ym = java.time.YearMonth.parse(month);
    } catch (RuntimeException ex) {
      ym = java.time.YearMonth.now(ZONE);
    }
    LocalDate today = LocalDate.now(ZONE);
    LocalDate first = ym.atDay(1);
    LocalDate last = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
      dates.add(d);
    }
    boolean beyondWindow = first.isBefore(today.minusDays(MAX_DAYS));
    String label = ym.getYear() + "年" + ym.getMonthValue() + "月";
    return summaryForDates(dates, dates.size(), label, beyondWindow ? label : null);
  }

  private ElemeSummaryResponse summaryForDates(List<LocalDate> dates, int days,
      String rangeLabel, String beyondWindowMonth) {
    if (properties.isConfigured() && beyondWindowMonth == null) {
      if (resolveShops().isEmpty()) {
        return unavailable(days, "UNCONFIGURED", "未配置已授权门店，暂时无法拉取订单数据。");
      }
      try {
        return live(days);
      } catch (Exception ex) {
        log.error("饿了么真实接口调用失败，未返回模拟营业数据", ex);
        return unavailable(days, "ERROR", "饿了么订单接口暂时不可用，请检查平台配置后重试。");
      }
    }
    if (beyondWindowMonth != null) {
      return unavailable(days, "UNAVAILABLE", beyondWindowMonth + " 超出订单接口最近 30 天可查询范围，暂无可用数据。");
    }
    return unavailable(days, "UNCONFIGURED", "未配置饿了么订单接口，暂无可用数据。");
  }

  private List<LocalDate> datesBack(LocalDate today, int days) {
    List<LocalDate> dates = new ArrayList<>();
    for (int d = 0; d < days; d++) {
      dates.add(today.minusDays(d));
    }
    return dates;
  }

  /* ---------------- 真实接口：拉单 + 聚合 ---------------- */

  private ElemeSummaryResponse live(int days) {
    LocalDate today = LocalDate.now(ZONE);
    String startTime = today.minusDays(days).atStartOfDay(ZONE).toOffsetDateTime()
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    String endTime = OffsetDateTime.now(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    // 聚合键：shopId|bizDate → [orderCount, totalPrice, income]
    Map<String, long[]> counts = new TreeMap<>();
    Map<String, BigDecimal[]> money = new TreeMap<>();
    Map<String, String> shopNames = new LinkedHashMap<>();

    for (String shopEntry : resolveShops()) {
      String[] pair = shopEntry.split(":", 2);
      String shopId = pair[0].trim();
      shopNames.put(shopId, pair.length > 1 ? pair[1].trim() : shopId);

      // 步骤 3：订单列表接口按门店 + 时间范围拉取（分页游标略，示范单页）
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("shopId", shopId);
      params.put("startTime", startTime);
      params.put("endTime", endTime);
      Map<String, Object> listResp = callOpenApi("eleme.order.getOrders", params);
      List<String> orderIds = extractOrderIds(listResp);

      for (String orderId : orderIds) {
        // 步骤 3：逐单调订单详情，取 status/totalPrice/income
        Map<String, Object> detail = callOpenApi(
            "eleme.order.getOrder", Map.of("orderId", orderId));
        Map<String, Object> order = asMap(detail.get("result"));
        if (order == null || !isValid(str(order.get("status")))) {
          continue; // 仅统计有效/已完结订单
        }
        String bizDate = toDate(str(order.get("activeTime")), today);
        String key = shopId + "|" + bizDate;
        counts.computeIfAbsent(key, k -> new long[1])[0] += 1;
        BigDecimal[] m = money.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        m[0] = m[0].add(money(order.get("totalPrice")));
        m[1] = m[1].add(money(order.get("income")));
      }
    }
    return build(days, "LIVE", "✅ 实时数据：已按授权门店拉取最近 " + days
        + " 天订单并按 totalPrice / income 聚合。", counts, money, shopNames);
  }

  /** 饿了么开放平台 nop 协议调用：系统参数 + 业务参数排序后 MD5 签名。 */
  @SuppressWarnings("unchecked")
  private Map<String, Object> callOpenApi(String method, Map<String, Object> bizParams) {
    long ts = System.currentTimeMillis() / 1000L;
    String nop = "1.0.0";
    TreeMap<String, String> signParams = new TreeMap<>();
    signParams.put("app_key", properties.getAppKey());
    signParams.put("timestamp", String.valueOf(ts));
    signParams.put("v", nop);
    signParams.put("action", method);
    signParams.put("token", properties.getAccessToken());
    bizParams.forEach((k, v) -> signParams.put(k, String.valueOf(v)));

    StringBuilder raw = new StringBuilder(properties.getAppSecret());
    signParams.forEach((k, v) -> raw.append(k).append(v));
    raw.append(properties.getAppSecret());
    String signature = md5(raw.toString()).toUpperCase();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nop", nop);
    body.put("id", java.util.UUID.randomUUID().toString());
    body.put("action", method);
    body.put("token", properties.getAccessToken());
    body.put("metas", Map.of("app_key", properties.getAppKey(), "timestamp", ts, "signature", signature));
    body.put("params", bizParams);

    RestClient client = restClient();
    Map<String, Object> resp = client.post()
        .uri(properties.getBaseUrl())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(Map.class);
    if (resp == null) {
      throw new IllegalStateException(method + " 返回空响应");
    }
    Object error = resp.get("error");
    if (error != null) {
      throw new IllegalStateException(method + " 接口报错：" + error);
    }
    return resp;
  }

  private RestClient restClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int t = Math.toIntExact(Math.min(properties.getTimeout().toMillis(), Integer.MAX_VALUE));
    factory.setConnectTimeout(t);
    factory.setReadTimeout(t);
    return RestClient.builder().requestFactory(factory).build();
  }

  @SuppressWarnings("unchecked")
  private List<String> extractOrderIds(Map<String, Object> listResp) {
    List<String> ids = new ArrayList<>();
    Object result = listResp.get("result");
    if (result instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m && m.get("orderId") != null) {
          ids.add(String.valueOf(m.get("orderId")));
        } else if (item != null) {
          ids.add(String.valueOf(item));
        }
      }
    } else if (result instanceof Map<?, ?> m && m.get("list") instanceof List<?> inner) {
      for (Object item : inner) {
        if (item instanceof Map<?, ?> im && im.get("orderId") != null) {
          ids.add(String.valueOf(im.get("orderId")));
        }
      }
    }
    return ids;
  }

  /* ---------------- 汇总组装 ---------------- */

  private ElemeSummaryResponse unavailable(int days, String mode, String note) {
    return build(days, mode, note, Map.of(), Map.of(), Map.of());
  }

  private ElemeSummaryResponse build(int days, String mode, String note,
      Map<String, long[]> counts, Map<String, BigDecimal[]> money, Map<String, String> shopNames) {
    List<ElemeSummaryResponse.Row> rows = new ArrayList<>();
    long orderTotal = 0;
    BigDecimal totalPrice = BigDecimal.ZERO;
    BigDecimal income = BigDecimal.ZERO;
    for (Map.Entry<String, long[]> e : counts.entrySet()) {
      String[] parts = e.getKey().split("\\|", 2);
      String shopId = parts[0];
      String bizDate = parts.length > 1 ? parts[1] : "";
      long oc = e.getValue()[0];
      BigDecimal[] m = money.getOrDefault(e.getKey(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      rows.add(new ElemeSummaryResponse.Row(
          shopId, shopNames.getOrDefault(shopId, shopId), bizDate, oc, m[0], m[1]));
      orderTotal += oc;
      totalPrice = totalPrice.add(m[0]);
      income = income.add(m[1]);
    }
    rows.sort((a, b) -> {
      int c = b.bizDate().compareTo(a.bizDate());
      return c != 0 ? c : a.shopId().compareTo(b.shopId());
    });
    return new ElemeSummaryResponse(mode, note, days,
        OffsetDateTime.now(ZONE).format(STAMP), totalPrice, income, orderTotal, rows);
  }

  private List<String> resolveShops() {
    return properties.getShops();
  }

  /* ---------------- 工具 ---------------- */

  private boolean isValid(String status) {
    if (status == null) {
      return false;
    }
    String s = status.toLowerCase();
    return s.contains("settled") || s.contains("success") || s.contains("finish") || s.equals("18");
  }

  private String toDate(String raw, LocalDate fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback.format(DATE);
    }
    try {
      if (raw.length() >= 10 && raw.charAt(4) == '-') {
        return raw.substring(0, 10);
      }
      long epoch = Long.parseLong(raw);
      if (epoch < 10_000_000_000L) {
        epoch *= 1000L;
      }
      return java.time.Instant.ofEpochMilli(epoch).atZone(ZONE).toLocalDate().format(DATE);
    } catch (RuntimeException ex) {
      return fallback.format(DATE);
    }
  }

  private BigDecimal money(Object v) {
    if (v == null) {
      return BigDecimal.ZERO;
    }
    try {
      BigDecimal d = new BigDecimal(String.valueOf(v));
      // 饿了么金额多以分为单位；大额时按分换算成元
      return d.abs().compareTo(BigDecimal.valueOf(100000)) > 0
          ? d.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
          : d.setScale(2, RoundingMode.HALF_UP);
    } catch (RuntimeException ex) {
      return BigDecimal.ZERO;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object o) {
    return o instanceof Map ? (Map<String, Object>) o : null;
  }

  private String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private String md5(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("MD5 失败", ex);
    }
  }
}
