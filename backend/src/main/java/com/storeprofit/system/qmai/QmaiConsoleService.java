package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 企迈商户后台（console.qmai.cn）令牌复用抓取通道。
 *
 * <p>用户浏览器登录后台后，复制 cookie {@code qm_seller_token} 粘贴入系统（存后端 DB）。
 * 本服务带该 cookie 调后台网关 {@code webapi.qmai.cn/gw/...}，绕开登录页的阿里云验证码。
 * 令牌有时效，过期后需重新粘贴。
 */
@Service
public class QmaiConsoleService {
  private static final Logger log = LoggerFactory.getLogger(QmaiConsoleService.class);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  /** 后台数据网关（console 报表接口实际走 inapi.qmai.cn/gw，抓包确认）。 */
  private static final String GATEWAY = "https://inapi.qmai.cn/gw";
  /** 营业收入（按支付渠道）报表接口。 */
  private static final String INCOME_METHOD = "data-center/trd/pc/list-business-income";

  private final QmaiConfigService configService;

  public QmaiConsoleService(QmaiConfigService configService) {
    this.configService = configService;
  }

  /**
   * 用已存令牌调后台任意接口路径，返回原始响应，用于定位营业额报表接口与参数。
   *
   * @param path 网关下的接口路径，如 biCenter/dataInfo/businessReport
   * @param body POST JSON 体（业务参数），可为空
   */
  public Map<String, Object> probe(long tenantId, String brand, String path, Map<String, Object> body) {
    QmaiConfigService.EffectiveConfig cfg = configService.resolve(tenantId, brand);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("path", path);
    if (!cfg.hasConsoleToken()) {
      out.put("ok", false);
      out.put("error", "未粘贴商户后台登录令牌（qm_seller_token）。请在配置弹窗粘贴后重试。");
      return out;
    }
    try {
      Map<String, Object> resp = call(cfg.consoleToken(), firstSellerId(cfg), path,
          body == null ? new LinkedHashMap<>() : body);
      out.put("ok", true);
      out.put("raw", resp);
    } catch (Exception ex) {
      out.put("ok", false);
      out.put("error", ex.getMessage());
    }
    return out;
  }

  /**
   * 按自然月拉取商户后台「营业收入（按支付渠道）」并汇总。
   * 令牌复用通道：需已粘贴 qm_seller_token 且配置门店 sellerId（存于 shops 字段）。
   */
  public QmaiConsoleIncomeResponse incomeForMonth(long tenantId, String brand, String month) {
    YearMonth ym;
    try {
      ym = YearMonth.parse(month);
    } catch (RuntimeException ex) {
      ym = YearMonth.now(ZONE);
    }
    LocalDate today = LocalDate.now(ZONE);
    LocalDate first = ym.atDay(1);
    LocalDate last = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
    String label = ym.getYear() + "年" + ym.getMonthValue() + "月";
    String now = OffsetDateTime.now(ZONE).format(STAMP);

    QmaiConfigService.EffectiveConfig cfg = configService.resolve(tenantId, brand);
    if (!cfg.hasConsoleToken()) {
      return incomeUnavailable("UNCONFIGURED",
          "未粘贴商户后台登录令牌（qm_seller_token）。请在配置弹窗粘贴后重试。", label, now);
    }
    String sellerId = firstSellerId(cfg);
    if (sellerId == null) {
      return incomeUnavailable("UNCONFIGURED",
          "未配置门店 ID（sellerId）。请在「授权门店」栏填入门店 sellerId。", label, now);
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("dateType", 2);
      body.put("startDate", first.format(DATE));
      body.put("endDate", last.format(DATE));
      body.put("client", "pc");
      body.put("isBusinessDate", 0);
      body.put("businessType", 13);
      body.put("type", "day");
      body.put("bizType", 1);
      body.put("industryEn", "tea");
      body.put("orderSource", List.of());
      body.put("orderType", List.of());
      body.put("mealPart", List.of());
      body.put("level", 1);
      Map<String, Object> resp = call(cfg.consoleToken(), sellerId, INCOME_METHOD, body);
      return buildIncome(resp, label, now);
    } catch (Exception ex) {
      String msg = ex.getMessage() == null ? "" : ex.getMessage();
      String note = msg.contains("失效") || msg.contains("过期") || msg.contains("9001")
          ? msg
          : "企迈后台营业额接口暂时不可用：" + msg;
      return incomeUnavailable("ERROR", note, label, now);
    }
  }

  @SuppressWarnings("unchecked")
  private QmaiConsoleIncomeResponse buildIncome(Map<String, Object> resp, String label, String now) {
    Object data = resp.get("data");
    BigDecimal totalRevenue = BigDecimal.ZERO;
    long totalCount = 0;
    List<QmaiConsoleIncomeResponse.Channel> channels = new ArrayList<>();
    if (data instanceof List<?> list) {
      for (Object it : list) {
        if (!(it instanceof Map<?, ?> m)) {
          continue;
        }
        Map<String, Object> row = (Map<String, Object>) m;
        String name = str(row.get("payTypeName"));
        BigDecimal amt = money(row.get("revenueAmt"));
        long cnt = longVal(row.get("revenueCnt"));
        // 首行 payType 为空且名称含「营业收入」= 总计，其余为各支付渠道
        boolean isTotal = (row.get("payType") == null || str(row.get("payType")).isBlank())
            && name != null && name.contains("营业收入");
        if (isTotal) {
          totalRevenue = amt;
          totalCount = cnt;
        } else if (name != null && !name.isBlank()) {
          channels.add(new QmaiConsoleIncomeResponse.Channel(name, amt, cnt));
        }
      }
    }
    channels.sort((a, b) -> b.revenue().compareTo(a.revenue()));
    // 若接口未单列总计行，用渠道求和兜底
    if (totalRevenue.signum() == 0 && !channels.isEmpty()) {
      for (QmaiConsoleIncomeResponse.Channel c : channels) {
        totalRevenue = totalRevenue.add(c.revenue());
        totalCount += c.count();
      }
    }
    return new QmaiConsoleIncomeResponse("LIVE",
        "✅ 实时数据：已从企迈后台拉取营业收入（按支付渠道）。", label, now,
        totalRevenue, totalCount, channels);
  }

  private QmaiConsoleIncomeResponse incomeUnavailable(String mode, String note, String label,
      String now) {
    return new QmaiConsoleIncomeResponse(mode, note, label, now,
        BigDecimal.ZERO, 0, List.of());
  }

  /** 门店 sellerId：取自 shops 配置的第一个纯数字项。 */
  private String firstSellerId(QmaiConfigService.EffectiveConfig cfg) {
    if (cfg.shops() == null) {
      return null;
    }
    for (String s : cfg.shops()) {
      String v = s == null ? "" : s.trim();
      if (v.matches("\\d+")) {
        return v;
      }
    }
    return null;
  }

  private String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private BigDecimal money(Object v) {
    if (v == null) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
    } catch (RuntimeException ex) {
      return BigDecimal.ZERO;
    }
  }

  private long longVal(Object v) {
    if (v == null) {
      return 0;
    }
    try {
      return new BigDecimal(String.valueOf(v)).longValue();
    } catch (RuntimeException ex) {
      return 0;
    }
  }

  Map<String, Object> call(String token, String path, Map<String, Object> body) {
    return call(token, "", path, body);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> call(String token, String sellerId, String path, Map<String, Object> body) {
    String url = GATEWAY + "/" + path.replaceFirst("^/+", "");
    // 后台鉴权：cookie qm_seller_token + ALL_DATA_SELLERID（选中门店），配真实 console 请求头。
    String cookie = "qm_seller_token=" + token
        + (sellerId != null && !sellerId.isBlank() ? "; ALL_DATA_SELLERID=" + sellerId : "");
    RestClient client = restClient();
    Map<String, Object> resp = client.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .header("Cookie", cookie)
        .header("device-no", "storeprofit-backend")
        .header("device-type", "Chrome")
        .header("login-client-type", "1")
        .header("new-menu-flag", "1")
        .header("qm-channel", "console")
        .header("qm-device", "mac")
        .header("qm-from", "pc, console")
        .header("front-route", "/biCenter/orderOverview")
        .header("Origin", "https://console.qmai.cn")
        .header("Referer", "https://console.qmai.cn/")
        .body(body)
        .retrieve()
        .body(Map.class);
    if (resp == null) {
      throw new IllegalStateException(path + " 返回空响应（令牌可能已过期，请重新登录后台复制粘贴）");
    }
    Object code = resp.get("code");
    Object status = resp.get("status");
    boolean ok = Boolean.TRUE.equals(status)
        || (code != null && ("0".equals(String.valueOf(code)) || "200".equals(String.valueOf(code))))
        || (code == null && resp.containsKey("data"));
    if (!ok) {
      String msg = String.valueOf(resp.getOrDefault("message", resp.get("msg")));
      if (String.valueOf(code).matches("9001|43002|401")) {
        throw new IllegalStateException("登录令牌已失效（" + code + "）：请重新登录企迈后台复制 qm_seller_token 粘贴。");
      }
      throw new IllegalStateException(path + " 后台报错：code=" + code + " msg=" + msg);
    }
    return resp;
  }

  private RestClient restClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int t = Math.toIntExact(Duration.ofSeconds(30).toMillis());
    factory.setConnectTimeout(t);
    factory.setReadTimeout(t);
    return RestClient.builder().requestFactory(factory).build();
  }
}
