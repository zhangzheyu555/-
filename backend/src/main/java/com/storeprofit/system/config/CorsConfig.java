package com.storeprofit.system.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
  @Bean
  @ConfigurationProperties(prefix = "app.cors")
  public CorsProperties corsProperties() {
    return new CorsProperties();
  }

  @Bean
  public CorsFilter corsFilter(CorsProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(properties.getAllowedOriginPatterns());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of("X-Export-Filename", "Content-Disposition"));
    config.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return new CorsFilter(source);
  }

  public static class CorsProperties {
    private List<String> allowedOriginPatterns = List.of("http://localhost:*", "http://127.0.0.1:*");

    public List<String> getAllowedOriginPatterns() {
      return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
      this.allowedOriginPatterns = allowedOriginPatterns;
    }
  }
}
