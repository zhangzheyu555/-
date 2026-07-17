package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class QmaiClient {
  private static final String SHOP_LIST_PATH = "v3/org/shop/getShopList";
  private static final String TURNOVER_PATH = "v3/dataone/item/store/turnover";
  private static final AtomicInteger NONCE = new AtomicInteger(new java.security.SecureRandom().nextInt(1_000_000));
  private final QmaiProperties properties;

  public QmaiClient(QmaiProperties properties) {
    this.properties = properties;
  }

  public List<QmaiModels.DiscoveredShop> discoverShops() {
    requireConfigured();
    List<QmaiModels.DiscoveredShop> result = new ArrayList<>();
    for (int page = 1; page <= 50; page++) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("pageNum", page);
      params.put("pageSize", 50);
      params.put("containCloseFlag", 1);
      List<Map<String, Object>> rows = nestedRows(call(SHOP_LIST_PATH, params), "list");
      for (Map<String, Object> row : rows) {
        String id = string(first(row, "id", "shopId"));
        if (!id.isBlank()) {
          String name = string(first(row, "name", "shopName"));
          result.add(new QmaiModels.DiscoveredShop(id, name.isBlank() ? id : name));
        }
      }
      if (rows.size() < 50) {
        break;
      }
    }
    return List.copyOf(result);
  }

  public QmaiModels.DailySnapshot fetchDay(QmaiModels.ShopMapping mapping, LocalDate date) {
    requireConfigured();
    long shopId;
    try {
      shopId = Long.parseLong(mapping.qmaiShopId());
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("企迈门店编号必须是数字");
    }
    int sourceRows = 0;
    BigDecimal receivable = BigDecimal.ZERO;
    BigDecimal received = BigDecimal.ZERO;
    BigDecimal cost = BigDecimal.ZERO;
    BigDecimal refund = BigDecimal.ZERO;
    Map<String, MutableProduct> products = new LinkedHashMap<>();
    for (int page = 1; page <= 100; page++) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("pageNo", page);
      params.put("pageSize", 200);
      params.put("shopId", shopId);
      params.put("queryDate", date.format(DateTimeFormatter.ISO_LOCAL_DATE));
      List<Map<String, Object>> rows = nestedRows(call(TURNOVER_PATH, params), "resultList");
      for (Map<String, Object> row : rows) {
        sourceRows++;
        receivable = receivable.add(money(row.get("receivableAmount")));
        received = received.add(money(row.get("receivedAmount")));
        cost = cost.add(money(row.get("costAmount")));
        refund = refund.add(money(row.get("refundAmount")));
        String itemName = string(first(row, "name", "itemName", "goodsName"));
        if (itemName.isBlank()) {
          continue;
        }
        String productId = string(first(row, "itemId", "goodsId", "productId"));
        String skuId = string(first(row, "skuId", "itemSkuId", "specId"));
        String category = string(first(row, "categoryName", "category"));
        String productKey = productKey(productId, skuId, itemName, category);
        products.computeIfAbsent(productKey,
                key -> new MutableProduct(key, productId, skuId, itemName, category))
            .add(quantity(row.get("num")), quantity(row.get("refundNum")),
                money(row.get("receivableAmount")), money(row.get("receivedAmount")),
                money(row.get("costAmount")), money(row.get("refundAmount")));
      }
      if (rows.size() < 200) {
        break;
      }
      if (page == 100) {
        throw new IllegalStateException("企迈单日商品分页超过安全上限");
      }
    }
    List<QmaiModels.ProductSnapshot> snapshots = products.values().stream().map(MutableProduct::snapshot).toList();
    return new QmaiModels.DailySnapshot(mapping.qmaiShopId(), mapping.storeId(), date, sourceRows,
        scale(receivable), scale(received), scale(cost), scale(refund), snapshots);
  }

  Map<String, Object> call(String path, Map<String, Object> params) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= properties.getMaxRetries(); attempt++) {
      try {
        return callOnce(path, params);
      } catch (RuntimeException ex) {
        last = ex;
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        boolean retryable = message.contains("110004") || message.contains("限流")
            || message.contains("繁忙") || message.contains("频繁");
        if (!retryable || attempt == properties.getMaxRetries()) {
          throw ex;
        }
        try {
          Thread.sleep(150L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("企迈请求已取消");
        }
      }
    }
    throw last == null ? new IllegalStateException("企迈请求失败") : last;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> callOnce(String path, Map<String, Object> params) {
    if (!SHOP_LIST_PATH.equals(path) && !TURNOVER_PATH.equals(path)) {
      throw new IllegalArgumentException("不允许访问未登记的企迈接口");
    }
    long timestamp = System.currentTimeMillis() / 1000L;
    int nonce = NONCE.updateAndGet(value -> value >= 2_000_000_000 ? 1 : value + 1);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("openId", properties.getOpenId());
    body.put("grantCode", properties.getGrantCode());
    body.put("nonce", nonce);
    body.put("timestamp", timestamp);
    body.put("token", sign(timestamp, nonce));
    body.put("params", params);
    String base = properties.validatedBaseUri().toString().replaceAll("/+$", "");
    Map<String, Object> response = restClient().post().uri(base + "/" + path)
        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(Map.class);
    if (response == null) {
      throw new IllegalStateException("企迈接口返回空响应");
    }
    Object code = first(response, "code", "errcode", "status");
    Object success = response.get("success");
    boolean ok = Boolean.TRUE.equals(success)
        || "0".equals(String.valueOf(code)) || "200".equals(String.valueOf(code))
        || (code == null && success == null && response.containsKey("data"));
    if (!ok) {
      String message = string(first(response, "message", "msg", "errmsg"));
      throw new IllegalStateException("企迈接口失败：" + code + (message.isBlank() ? "" : "，" + message));
    }
    return response;
  }

  String sign(long timestamp, int nonce) {
    TreeMap<String, String> values = new TreeMap<>();
    values.put("openId", properties.getOpenId());
    values.put("grantCode", properties.getGrantCode());
    values.put("nonce", String.valueOf(nonce));
    values.put("timestamp", String.valueOf(timestamp));
    String canonical = values.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(java.util.stream.Collectors.joining("&"));
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(properties.getOpenKey().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("企迈签名计算失败");
    }
  }

  private RestClient restClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int timeout = Math.toIntExact(Math.min(properties.getTimeout().toMillis(), Integer.MAX_VALUE));
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(factory).build();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> nestedRows(Map<String, Object> response, String key) {
    Object data = response.get("data");
    Object value = data instanceof Map<?, ?> map ? ((Map<String, Object>) map).get(key) : null;
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        rows.add((Map<String, Object>) map);
      }
    }
    return rows;
  }

  private Object first(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private BigDecimal money(Object value) {
    return decimal(value, 2);
  }

  private BigDecimal quantity(Object value) {
    return decimal(value, 3).abs();
  }

  private BigDecimal decimal(Object value, int scale) {
    if (value == null || String.valueOf(value).isBlank()) {
      return BigDecimal.ZERO.setScale(scale);
    }
    try {
      return new BigDecimal(String.valueOf(value)).setScale(scale, RoundingMode.HALF_UP);
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("企迈金额或数量格式不正确");
    }
  }

  private String productKey(String productId, String skuId, String itemName, String category) {
    String source = productId + "|" + skuId + "|" + itemName + "|" + category;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("商品标识生成失败");
    }
  }

  private BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private void requireConfigured() {
    if (!properties.isConfigured()) {
      throw new IllegalStateException("企迈凭证尚未由部署环境配置");
    }
    properties.validatedBaseUri();
  }

  private static final class MutableProduct {
    private final String key;
    private final String productId;
    private final String skuId;
    private final String name;
    private final String category;
    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal refundQuantity = BigDecimal.ZERO;
    private BigDecimal receivable = BigDecimal.ZERO;
    private BigDecimal received = BigDecimal.ZERO;
    private BigDecimal cost = BigDecimal.ZERO;
    private BigDecimal refund = BigDecimal.ZERO;

    private MutableProduct(String key, String productId, String skuId, String name, String category) {
      this.key = key;
      this.productId = productId;
      this.skuId = skuId;
      this.name = name;
      this.category = category;
    }

    private void add(BigDecimal qty, BigDecimal refundQty, BigDecimal receivableAmount,
        BigDecimal receivedAmount, BigDecimal costAmount, BigDecimal refundAmount) {
      quantity = quantity.add(qty);
      refundQuantity = refundQuantity.add(refundQty);
      receivable = receivable.add(receivableAmount);
      received = received.add(receivedAmount);
      cost = cost.add(costAmount);
      refund = refund.add(refundAmount);
    }

    private QmaiModels.ProductSnapshot snapshot() {
      return new QmaiModels.ProductSnapshot(key, productId, skuId, name, category,
          quantity.setScale(3, RoundingMode.HALF_UP), refundQuantity.setScale(3, RoundingMode.HALF_UP),
          receivable.setScale(2, RoundingMode.HALF_UP), received.setScale(2, RoundingMode.HALF_UP),
          cost.setScale(2, RoundingMode.HALF_UP), refund.setScale(2, RoundingMode.HALF_UP));
    }
  }
}
