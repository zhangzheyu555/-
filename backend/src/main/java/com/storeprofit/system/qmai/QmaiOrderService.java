package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 企迈订单拉取与营业额聚合服务。
 *
 * <p>流程：授权门店 → 订单/营业额接口 → 按 shopCode + 日期聚合金额。仅在已配置
 * openId/grantCode/openKey 时调真实接口；未配置或调用失败时返回空结果和明确状态，
 * 绝不伪造营业数据。
 *
 * <p><b>上线前必须校准两处（见 README 接入说明）：</b>
 * <ol>
 *   <li>{@link #sign} 的签名算法——当前按「排序拼接 + openKey 包裹 + MD5 大写」实现，
 *       与本仓库饿了么模块同规则；需用一条真实签名请求（qmai CLI 抓包）核对 canonical 拼法
 *       与哈希类型（MD5 / HMAC-SHA256）是否一致。</li>
 *   <li>订单接口路径 {@link #ORDER_LIST_METHOD} 与响应字段名（金额/日期/状态）——以企迈开放平台
 *       文档为准，当前解析已做多字段名容错。</li>
 * </ol>
 */
@Service
public class QmaiOrderService {
  private static final Logger log = LoggerFactory.getLogger(QmaiOrderService.class);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int MAX_DAYS = 62;

  /** 订单/营业额列表接口路径。以企迈文档为准，需上线前确认。 */
  /** 门店营业额（按门店 + 日期，返回商品级明细，实收金额即营业额）。 */
  private static final String TURNOVER_METHOD = "v3/dataone/item/store/turnover";
  /** 门店列表（分页返回名下所有门店，id 即企迈 shopId）。 */
  private static final String SHOP_LIST_METHOD = "v3/org/shop/getShopList";

  private final QmaiConfigService configService;

  public QmaiOrderService(QmaiConfigService configService) {
    this.configService = configService;
  }

  /** 按数据范围拉取最近 N 天营业额。allowedStoreIds 为 null 仅用于已验证的 ALL。 */
  public QmaiSummaryResponse summary(long tenantId, String brand, int requestedDays,
      Collection<String> allowedStoreIds) {
    int days = Math.max(1, Math.min(requestedDays, MAX_DAYS));
    LocalDate today = LocalDate.now(ZONE);
    return summaryForDates(tenantId, brand, datesBack(today, days), days,
        "最近 " + days + " 天", allowedStoreIds);
  }

  /**
   * 诊断：用当前凭证调企迈门店列表接口，验证签名是否被接受并发现门店编码。
   *
   * @return ok=true 时 raw 为企迈原始返回；ok=false 时 error 为企迈报错信息（如签名错误）
   */
  public Map<String, Object> probeShops(long tenantId, String brand) {
    return probe(tenantId, brand, "v3/org/shop/getShopList", new LinkedHashMap<>());
  }

  /** 诊断：调用任意企迈接口路径（带可选业务参数），用于验证权限并发现正确的营业额接口。 */
  public Map<String, Object> probe(long tenantId, String brand, String path,
      Map<String, Object> bizParams) {
    return probe(tenantId, brand, path, bizParams, 0);
  }

  /** 诊断（可指定签名基串构造模式，用于快速定位企迈验签规则）。 */
  public Map<String, Object> probe(long tenantId, String brand, String path,
      Map<String, Object> bizParams, int signMode) {
    QmaiConfigService.EffectiveConfig cfg = configService.resolve(tenantId, brand);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("path", path);
    if (!cfg.isConfigured()) {
      out.put("ok", false);
      out.put("error", "凭证未配置齐全（openId/grantCode/openKey）。");
      return out;
    }
    try {
      Map<String, Object> resp = callOpenApi(cfg, path,
          bizParams == null ? new LinkedHashMap<>() : bizParams, signMode);
      out.put("ok", true);
      out.put("raw", resp);
    } catch (Exception ex) {
      out.put("ok", false);
      out.put("error", ex.getMessage());
    }
    return out;
  }

  /** 按自然月聚合。 */
  public QmaiSummaryResponse summaryForMonth(long tenantId, String brand, String month,
      Collection<String> allowedStoreIds) {
    YearMonth ym;
    try {
      ym = YearMonth.parse(month);
    } catch (RuntimeException ex) {
      ym = YearMonth.now(ZONE);
    }
    LocalDate today = LocalDate.now(ZONE);
    LocalDate first = ym.atDay(1);
    LocalDate last = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
      dates.add(d);
    }
    String label = ym.getYear() + "年" + ym.getMonthValue() + "月";
    return summaryForDates(tenantId, brand, dates, dates.size(), label, allowedStoreIds);
  }

  private QmaiSummaryResponse summaryForDates(long tenantId, String brand, List<LocalDate> dates,
      int days, String rangeLabel, Collection<String> allowedStoreIds) {
    QmaiConfigService.EffectiveConfig cfg = configService.resolve(tenantId, brand);
    if (!cfg.isConfigured()) {
      return unavailable(days, "UNCONFIGURED", "未配置企迈订单接口，暂无可用数据。");
    }
    List<QmaiProperties.ShopMapping> shops = cfg.resolveShops(allowedStoreIds);
    // 未手动配置门店时，自动从企迈拉取名下所有门店。
    if (shops.isEmpty()) {
      try {
        shops = listAllShops(cfg);
      } catch (Exception ex) {
        log.warn("企迈门店列表获取失败，回退到手动配置门店", ex);
      }
    }
    if (shops.isEmpty()) {
      return unavailable(days, "UNCONFIGURED",
          "未能获取企迈门店（门店列表接口无权限时请在“授权门店”手动填写 shopId）。");
    }
    try {
      return live(cfg, days, dates, shops);
    } catch (Exception ex) {
      log.error("企迈真实接口调用失败，未返回模拟营业数据", ex);
      return unavailable(days, "ERROR", "企迈订单接口暂时不可用，请检查平台配置后重试。");
    }
  }

  /** 从企迈拉取名下所有门店（分页），id 作为 shopId。 */
  @SuppressWarnings("unchecked")
  private List<QmaiProperties.ShopMapping> listAllShops(QmaiConfigService.EffectiveConfig cfg) {
    List<QmaiProperties.ShopMapping> result = new ArrayList<>();
    int pageNum = 1;
    int pageSize = 50; // getShopList 上限 50
    while (pageNum <= 50) {
      Map<String, Object> bizParams = new LinkedHashMap<>();
      bizParams.put("pageNum", pageNum);
      bizParams.put("pageSize", pageSize);
      bizParams.put("containCloseFlag", 1);
      Map<String, Object> resp = callOpenApi(cfg, SHOP_LIST_METHOD, bizParams, 0);
      Object data = resp.get("data");
      List<?> list = data instanceof Map<?, ?> dm && ((Map<String, Object>) dm).get("list") instanceof List<?> l
          ? l : List.of();
      for (Object it : list) {
        if (it instanceof Map<?, ?> m) {
          Object id = ((Map<String, Object>) m).get("id");
          Object name = ((Map<String, Object>) m).get("name");
          if (id != null) {
            result.add(new QmaiProperties.ShopMapping(
                String.valueOf(id), name == null ? String.valueOf(id) : String.valueOf(name), null));
          }
        }
      }
      if (list.size() < pageSize) {
        break;
      }
      pageNum++;
    }
    return result;
  }

  private List<LocalDate> datesBack(LocalDate today, int days) {
    List<LocalDate> dates = new ArrayList<>();
    for (int d = 0; d < days; d++) {
      dates.add(today.minusDays(d));
    }
    return dates;
  }

  /* ---------------- 真实接口：拉单 + 聚合 ---------------- */

  private QmaiSummaryResponse live(QmaiConfigService.EffectiveConfig cfg, int days,
      List<LocalDate> dates, List<QmaiProperties.ShopMapping> shops) {
    // 聚合键：storeId → [商品行数] / [应收, 实收]（多门店时按门店汇总整段区间）
    Map<String, long[]> counts = new TreeMap<>();
    Map<String, BigDecimal[]> money = new TreeMap<>();
    Map<String, String> shopNames = new LinkedHashMap<>();
    List<QmaiSummaryResponse.Item> items = new ArrayList<>();

    // 门店多时并发拉取（每店内部按日期串行）；验签并发失败已由 callOpenApi 自动重试。
    int poolSize = Math.max(1, Math.min(4, shops.size()));
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(poolSize);
    try {
      List<java.util.concurrent.Future<ShopPartial>> futures = new ArrayList<>();
      for (QmaiProperties.ShopMapping shop : shops) {
        futures.add(pool.submit(() -> fetchShop(cfg, shop, dates)));
      }
      for (java.util.concurrent.Future<ShopPartial> f : futures) {
        try {
          ShopPartial p = f.get();
          if (p == null) {
            continue;
          }
          shopNames.putIfAbsent(p.storeId, p.storeName);
          String key = p.storeId + "|" + p.rangeLabel;
          counts.computeIfAbsent(key, k -> new long[1])[0] += p.recordCount;
          BigDecimal[] m = money.computeIfAbsent(key,
              k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
          m[0] = m[0].add(p.receivable);
          m[1] = m[1].add(p.received);
          m[2] = m[2].add(p.cost);
          m[3] = m[3].add(p.refund);
          items.addAll(p.items);
        } catch (Exception ex) {
          log.warn("企迈单店营业额拉取失败，已跳过该店", ex);
        }
      }
    } finally {
      pool.shutdownNow();
    }
    return build(days, "LIVE",
        "✅ 实时数据：已拉取企迈名下门店营业额并按门店汇总（实收金额）。",
        counts, money, shopNames, items);
  }

  /** 单门店在整段日期区间的营业额汇总（供并发调用）。 */
  private ShopPartial fetchShop(QmaiConfigService.EffectiveConfig cfg,
      QmaiProperties.ShopMapping shop, List<LocalDate> dates) {
    long shopId;
    try {
      shopId = Long.parseLong(shop.shopCode().trim());
    } catch (NumberFormatException ex) {
      return null;
    }
    String storeId = String.valueOf(shopId);
    String storeName = shop.shopName();
    long recordCount = 0;
    BigDecimal receivable = BigDecimal.ZERO;
    BigDecimal received = BigDecimal.ZERO;
    BigDecimal cost = BigDecimal.ZERO;
    BigDecimal refund = BigDecimal.ZERO;
    // 商品聚合：商品名 → [销量, 实收, 成本, 退款, 退款数量]（整段区间累加）
    Map<String, BigDecimal[]> itemAgg = new LinkedHashMap<>();
    Map<String, String> itemCategory = new LinkedHashMap<>();
    String rangeLabel = dates.size() == 1
        ? dates.get(0).format(DATE)
        : dates.stream().min(LocalDate::compareTo).get().format(DATE) + " ~ "
            + dates.stream().max(LocalDate::compareTo).get().format(DATE);
    for (LocalDate d : dates) {
      try {
        int pageNo = 1;
        int pageSize = 200;
        while (true) {
          Map<String, Object> bizParams = new LinkedHashMap<>();
          bizParams.put("pageNo", pageNo);
          bizParams.put("pageSize", pageSize);
          bizParams.put("shopId", shopId);
          bizParams.put("queryDate", d.format(DATE));
          Map<String, Object> resp = callOpenApi(cfg, TURNOVER_METHOD, bizParams, 0);
          List<Map<String, Object>> rows = turnoverRows(resp);
          for (Map<String, Object> row : rows) {
            String name = str(firstNonNull(row, "storeName"));
            if (name != null && !name.isBlank()) {
              storeName = name;
            }
            recordCount++;
            receivable = receivable.add(money(row.get("receivableAmount")));
            received = received.add(money(row.get("receivedAmount")));
            cost = cost.add(money(row.get("costAmount")));
            refund = refund.add(money(row.get("refundAmount")));
            // 商品级：每行即一个商品当日销售，name=商品名、num=销量。
            // 名字先归一化（去杯型/做法等规格后缀），同一商品不同规格合并成一行。
            String itemName = normalizeItemName(str(firstNonNull(row, "name", "itemName", "goodsName")));
            if (itemName != null && !itemName.isBlank()) {
              BigDecimal[] agg = itemAgg.computeIfAbsent(itemName, k -> new BigDecimal[] {
                  BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
              agg[0] = agg[0].add(qty(row.get("num")));
              agg[1] = agg[1].add(money(row.get("receivedAmount")));
              agg[2] = agg[2].add(money(row.get("costAmount")));
              agg[3] = agg[3].add(money(row.get("refundAmount")));
              agg[4] = agg[4].add(qty(row.get("refundNum")));
              String cat = str(firstNonNull(row, "categoryName"));
              if (cat != null && !cat.isBlank()) {
                // 合并规格时优先保留真实分类，覆盖「未关联商品分类」占位
                itemCategory.merge(itemName, cat,
                    (oldV, newV) -> oldV.contains("未关联") && !newV.contains("未关联") ? newV : oldV);
              }
            }
          }
          if (rows.size() < pageSize) {
            break;
          }
          pageNo++;
        }
      } catch (Exception ex) {
        // 单日失败只跳过该日，不影响整店其余日期的汇总。
        log.warn("企迈门店 {} {} 营业额拉取失败，已跳过该日", shopId, d, ex);
      }
    }
    List<QmaiSummaryResponse.Item> items = new ArrayList<>();
    for (Map.Entry<String, BigDecimal[]> e : itemAgg.entrySet()) {
      BigDecimal[] a = e.getValue();
      items.add(new QmaiSummaryResponse.Item(storeId, storeName, e.getKey(),
          itemCategory.getOrDefault(e.getKey(), ""), a[0], a[1], a[2], a[3], a[4]));
    }
    return new ShopPartial(storeId, storeName, rangeLabel, recordCount,
        receivable, received, cost, refund, items);
  }

  private record ShopPartial(String storeId, String storeName, String rangeLabel,
      long recordCount, BigDecimal receivable, BigDecimal received,
      BigDecimal cost, BigDecimal refund, List<QmaiSummaryResponse.Item> items) {}

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> turnoverRows(Map<String, Object> resp) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Object data = resp.get("data");
    if (data instanceof Map<?, ?> dm) {
      Object list = ((Map<String, Object>) dm).get("resultList");
      if (list instanceof List<?> l) {
        for (Object it : l) {
          if (it instanceof Map<?, ?> m) {
            rows.add((Map<String, Object>) m);
          }
        }
      }
    }
    return rows;
  }

  /**
   * 企迈开放平台调用：公共参数 + 业务参数 → 签名 → JSON POST。
   *
   * <p>公共参数：openId、grantCode（门店授权码，作为令牌传递）、timestamp（毫秒）、
   * nonce（随机串防重放）、version、sign。openKey 只参与签名，不随请求发送。
   */
  /** 全局唯一 nonce 计数，避免并发下 nonce 撞车被企迈判为重放。 */
  private static final java.util.concurrent.atomic.AtomicInteger NONCE_SEQ =
      new java.util.concurrent.atomic.AtomicInteger(new java.security.SecureRandom().nextInt(1_000_000));

  private Map<String, Object> callOpenApi(QmaiConfigService.EffectiveConfig cfg,
      String method, Map<String, Object> bizParams, int signMode) {
    // 验签失败（并发/防重放）时换新 nonce+timestamp 重试几次。
    IllegalStateException last = null;
    for (int attempt = 0; attempt < 6; attempt++) {
      try {
        return callOnce(cfg, method, bizParams, signMode);
      } catch (IllegalStateException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        if (msg.contains("验签") || msg.contains("110004") || msg.contains("110007")
            || msg.contains("频") || msg.contains("限流") || msg.contains("繁忙")) {
          last = ex;
          try {
            Thread.sleep(120L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ex;
          }
          continue;
        }
        throw ex; // 其它业务错误（参数、权限等）不重试
      }
    }
    throw last != null ? last : new IllegalStateException(method + " 调用失败");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> callOnce(QmaiConfigService.EffectiveConfig cfg,
      String method, Map<String, Object> bizParams, int signMode) {
    // 依据企迈签名示例：timestamp 为 10 位秒级，nonce 为整数，token=Base64(HMAC-SHA1)。
    long ts = System.currentTimeMillis() / 1000L;
    int nonce = NONCE_SEQ.updateAndGet(v -> v >= 2_000_000_000 ? 1 : v + 1);
    String token = signHmacSha1(cfg, ts, nonce, bizParams, signMode);

    // 请求体结构对齐示例：{openId, grantCode, nonce, timestamp, token, params:{业务参数}}。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("openId", cfg.openId());
    body.put("grantCode", cfg.grantCode());
    body.put("nonce", nonce);
    body.put("timestamp", ts);
    body.put("token", token);
    body.put("params", bizParams);

    String url = cfg.baseUrl().replaceAll("/+$", "") + "/" + method;
    Map<String, Object> resp = restClient(cfg).post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(Map.class);
    if (resp == null) {
      throw new IllegalStateException(method + " 返回空响应");
    }
    // 企迈返回一般含 code/success 字段，非成功即抛出（不泄露凭证）。
    Object code = firstNonNull(resp, "code", "errcode", "status");
    Object success = resp.get("success");
    boolean ok = Boolean.TRUE.equals(success)
        || (code != null && ("0".equals(String.valueOf(code)) || "200".equals(String.valueOf(code))))
        || (code == null && success == null && resp.containsKey("data"));
    if (!ok) {
      throw new IllegalStateException(method + " 接口报错：code=" + code
          + " msg=" + firstNonNull(resp, "message", "msg", "errmsg"));
    }
    return resp;
  }

  /**
   * 企迈 token 签名：公共参数 + 业务参数按 key 升序拼成 key=value&… 基串，
   * 用 openKey 作密钥 HMAC-SHA1，结果 Base64。（依据官方签名示例反推的标准构造。）
   *
   * <p>基串具体拼法（是否含业务参数、分隔符）如未通过真实接口，可调本方法微调；
   * 基串仅 debug 输出且不含 openKey。
   */
  private String signHmacSha1(QmaiConfigService.EffectiveConfig cfg, long ts, int nonce,
      Map<String, Object> bizParams, int signMode) {
    // 公共参数
    TreeMap<String, String> authOnly = new TreeMap<>();
    authOnly.put("openId", cfg.openId());
    authOnly.put("grantCode", cfg.grantCode());
    authOnly.put("nonce", String.valueOf(nonce));
    authOnly.put("timestamp", String.valueOf(ts));
    // 公共 + 业务参数（展平）
    TreeMap<String, String> withBiz = new TreeMap<>(authOnly);
    bizParams.forEach((k, v) -> withBiz.put(k, String.valueOf(v)));
    String paramsJson;
    try {
      paramsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(bizParams);
    } catch (Exception e) {
      paramsJson = "{}";
    }

    String base;
    switch (signMode) {
      case 1 -> base = kv(authOnly, "&", true);              // 仅公共, key=value&
      case 2 -> base = kv(withBiz, "&", true);               // 公共+业务, key=value&
      case 3 -> base = kv(authOnly, "", false);              // 仅公共, keyvalue 连接
      case 4 -> base = kv(withBiz, "", false);               // 公共+业务, keyvalue 连接
      case 5 -> base = valuesOnly(authOnly);                 // 仅公共, 值拼接
      case 6 -> base = kv(authOnly, "&", true) + "&key=" + cfg.openKey(); // 公共 + openKey 尾
      case 7 -> {                                            // 公共 + params=<json>, key=value&
        TreeMap<String, String> m = new TreeMap<>(authOnly);
        m.put("params", paramsJson);
        base = kv(m, "&", true);
      }
      case 8 -> base = cfg.openKey() + kv(authOnly, "", false) + cfg.openKey(); // openKey 首尾包裹
      // 默认=已验证的正确构造：仅公共参数按 key 升序 key=value& + HMAC-SHA1(openKey) + Base64。
      default -> base = kv(authOnly, "&", true);
    }
    if (log.isDebugEnabled()) {
      log.debug("企迈 token 基串 mode={}（不含 openKey）: {}", signMode, base);
    }
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
      mac.init(new javax.crypto.spec.SecretKeySpec(
          cfg.openKey().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      byte[] raw = mac.doFinal(base.getBytes(StandardCharsets.UTF_8));
      return java.util.Base64.getEncoder().encodeToString(raw);
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC-SHA1 计算失败", ex);
    }
  }

  private String kv(TreeMap<String, String> m, String sep, boolean withEq) {
    StringBuilder b = new StringBuilder();
    m.forEach((k, v) -> {
      if (b.length() > 0) {
        b.append(sep);
      }
      b.append(k);
      if (withEq) {
        b.append('=');
      }
      b.append(v);
    });
    return b.toString();
  }

  private String valuesOnly(TreeMap<String, String> m) {
    StringBuilder b = new StringBuilder();
    m.forEach((k, v) -> b.append(v));
    return b.toString();
  }

  private RestClient restClient(QmaiConfigService.EffectiveConfig cfg) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int t = Math.toIntExact(Math.min(cfg.timeout().toMillis(), Integer.MAX_VALUE));
    factory.setConnectTimeout(t);
    factory.setReadTimeout(t);
    return RestClient.builder().requestFactory(factory).build();
  }

  /* ---------------- 响应解析 ---------------- */

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extractOrders(Map<String, Object> resp) {
    List<Map<String, Object>> orders = new ArrayList<>();
    Object data = firstNonNull(resp, "data", "result");
    Object listNode = data;
    if (data instanceof Map<?, ?> dm) {
      listNode = firstNonNull((Map<String, Object>) dm, "list", "orders", "records", "items");
    }
    if (listNode instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          orders.add((Map<String, Object>) m);
        }
      }
    }
    return orders;
  }

  /* ---------------- 汇总组装 ---------------- */

  private QmaiSummaryResponse unavailable(int days, String mode, String note) {
    return build(days, mode, note, Map.of(), Map.of(), Map.of(), List.of());
  }

  private QmaiSummaryResponse build(int days, String mode, String note,
      Map<String, long[]> counts, Map<String, BigDecimal[]> money, Map<String, String> shopNames,
      List<QmaiSummaryResponse.Item> items) {
    List<QmaiSummaryResponse.Row> rows = new ArrayList<>();
    long orderTotal = 0;
    BigDecimal totalAmount = BigDecimal.ZERO;
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal costTotal = BigDecimal.ZERO;
    BigDecimal refundTotal = BigDecimal.ZERO;
    BigDecimal profitTotal = BigDecimal.ZERO;
    for (Map.Entry<String, long[]> e : counts.entrySet()) {
      String[] parts = e.getKey().split("\\|", 2);
      String shopCode = parts[0];
      String bizDate = parts.length > 1 ? parts[1] : "";
      long oc = e.getValue()[0];
      BigDecimal[] m = money.getOrDefault(e.getKey(),
          new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
      BigDecimal profit = m[1].subtract(m[2]); // 毛利 = 实收 - 成本
      rows.add(new QmaiSummaryResponse.Row(
          shopCode, shopNames.getOrDefault(shopCode, shopCode), bizDate, oc,
          m[0], m[1], m[2], m[3], profit));
      orderTotal += oc;
      totalAmount = totalAmount.add(m[0]);
      income = income.add(m[1]);
      costTotal = costTotal.add(m[2]);
      refundTotal = refundTotal.add(m[3]);
      profitTotal = profitTotal.add(profit);
    }
    rows.sort((a, b) -> {
      int c = b.incomeSum().compareTo(a.incomeSum()); // 按实收降序
      return c != 0 ? c : a.shopCode().compareTo(b.shopCode());
    });
    List<QmaiSummaryResponse.Item> itemRows = new ArrayList<>(items);
    itemRows.sort((a, b) -> {
      int c = b.num().compareTo(a.num()); // 按销量降序
      if (c != 0) {
        return c;
      }
      c = b.incomeSum().compareTo(a.incomeSum());
      return c != 0 ? c : a.itemName().compareTo(b.itemName());
    });
    return new QmaiSummaryResponse(mode, note, days,
        OffsetDateTime.now(ZONE).format(STAMP), totalAmount, income,
        costTotal, refundTotal, profitTotal, orderTotal, rows, itemRows);
  }

  /* ---------------- 工具 ---------------- */

  private boolean isValid(String status) {
    if (status == null) {
      return true; // 无状态字段时默认计入，由金额字段兜底
    }
    String s = status.toLowerCase();
    return !(s.contains("cancel") || s.contains("refund") || s.contains("close")
        || s.contains("invalid") || s.equals("0"));
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
      // 企迈金额通常以元（两位小数）返回；若为分（大整数）则换算成元
      BigDecimal d = new BigDecimal(String.valueOf(v)).abs();
      boolean looksLikeCents = d.scale() == 0 && d.compareTo(BigDecimal.valueOf(100000)) > 0;
      return looksLikeCents
          ? d.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
          : d.setScale(2, RoundingMode.HALF_UP);
    } catch (RuntimeException ex) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * 归一化商品名：外卖渠道的商品名常拼有杯型/做法等规格后缀
   * （如「芒芒甘露-大杯有茶冻[去冰（2」「牛油果甘露.」），只保留商品本名，
   * 让同一商品的不同规格在聚合时合并成一行。
   */
  private String normalizeItemName(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    // 去括号及其后的备注（中英文括号/方括号，含未闭合的）
    for (String br : new String[] {"（", "(", "[", "【"}) {
      int idx = s.indexOf(br);
      if (idx > 0) {
        s = s.substring(0, idx);
      }
    }
    // 去「-大杯…」等杯型规格后缀（需有分隔符引导，避免误伤本名）
    s = s.replaceFirst("[-–—.。·]\\s*(超大杯|大杯|中杯|小杯|标准杯).*$", "");
    // 去结尾残留的分隔符/点号
    s = s.replaceAll("[-–—.。·\\s]+$", "");
    return s.isBlank() ? raw.trim() : s;
  }

  /** 商品数量（销量/退款数量）：企迈以小数返回（如 2.0，退款数量为负），取绝对值，不做分/元换算。 */
  private BigDecimal qty(Object v) {
    if (v == null) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(String.valueOf(v)).abs().setScale(2, RoundingMode.HALF_UP);
    } catch (RuntimeException ex) {
      return BigDecimal.ZERO;
    }
  }

  private Object firstNonNull(Map<String, Object> map, String... keys) {
    if (map == null) {
      return null;
    }
    for (String k : keys) {
      Object v = map.get(k);
      if (v != null) {
        return v;
      }
    }
    return null;
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
