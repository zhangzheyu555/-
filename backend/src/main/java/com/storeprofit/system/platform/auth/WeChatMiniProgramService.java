package com.storeprofit.system.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WeChatMiniProgramService {
  private final String appId;
  private final String appSecret;
  private final String baseUrl;
  private final ObjectMapper objectMapper;

  public WeChatMiniProgramService(
      @Value("${app.wechat-mini-program.app-id:}") String appId,
      @Value("${app.wechat-mini-program.app-secret:}") String appSecret,
      @Value("${app.wechat-mini-program.base-url:https://api.weixin.qq.com}") String baseUrl,
      ObjectMapper objectMapper
  ) {
    this.appId = trim(appId);
    this.appSecret = trim(appSecret);
    this.baseUrl = trim(baseUrl).replaceAll("/+$", "");
    this.objectMapper = objectMapper;
  }

  public boolean configured() {
    return !appId.isBlank() && !appSecret.isBlank();
  }

  public String appId() {
    return appId;
  }

  public Identity exchangeCode(String code) {
    if (!configured()) {
      throw new BusinessException("WECHAT_LOGIN_UNAVAILABLE", "微信登录暂未配置，请使用账号密码登录", HttpStatus.SERVICE_UNAVAILABLE);
    }
    String normalizedCode = trim(code);
    if (normalizedCode.length() < 8 || normalizedCode.length() > 512) {
      throw new BusinessException("WECHAT_CODE_INVALID", "微信登录凭据无效，请重新进入小程序后重试", HttpStatus.BAD_REQUEST);
    }
    try {
      SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(3_000);
      factory.setReadTimeout(5_000);
      URI requestUri = UriComponentsBuilder.fromUriString(baseUrl)
          .path("/sns/jscode2session")
          .queryParam("appid", appId)
          .queryParam("secret", appSecret)
          .queryParam("js_code", normalizedCode)
          .queryParam("grant_type", "authorization_code")
          .build().encode().toUri();
      String response = RestClient.builder().requestFactory(factory).build().get()
          .uri(requestUri)
          .retrieve()
          .body(String.class);
      JsonNode body = objectMapper.readTree(response == null ? "" : response);
      String openid = trim(body.path("openid").asText());
      if (openid.isBlank()) {
        throw new BusinessException("WECHAT_CODE_EXCHANGE_FAILED", "微信授权已失效，请重新进入小程序后重试", HttpStatus.UNAUTHORIZED);
      }
      return new Identity(openid, blankToNull(body.path("unionid").asText()));
    } catch (BusinessException ex) {
      throw ex;
    } catch (RestClientException | java.io.IOException ex) {
      throw new BusinessException("WECHAT_SERVICE_UNAVAILABLE", "微信登录服务暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private static String trim(String value) { return value == null ? "" : value.trim(); }
  private static String blankToNull(String value) { String normalized = trim(value); return normalized.isBlank() ? null : normalized; }

  public record Identity(String openid, String unionid) { }
}
