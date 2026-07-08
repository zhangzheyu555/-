package com.storeprofit.system.staticresources;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class StaticResourceCacheTest {
  @Test
  void staticResourceConfigAllowsBrowserReuse() throws IOException {
    PropertySource<?> applicationYaml = new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"))
        .getFirst();

    assertThat(applicationYaml.getProperty("spring.web.resources.cache.cachecontrol.no-cache"))
        .isNotEqualTo(true);
    assertThat(applicationYaml.getProperty("spring.web.resources.cache.cachecontrol.cache-public"))
        .isEqualTo(true);
    assertThat(applicationYaml.getProperty("spring.web.resources.cache.cachecontrol.max-age"))
        .isEqualTo("1h");
    assertThat(applicationYaml.getProperty("server.compression.enabled"))
        .isEqualTo(true);
    assertThat(applicationYaml.getProperty("server.compression.mime-types[0]"))
        .isEqualTo("text/html");
    assertThat(applicationYaml.getProperty("server.compression.mime-types[3]"))
        .isEqualTo("text/javascript");
  }
}
