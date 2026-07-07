package com.storeprofit.system.inspection;

import com.storeprofit.system.common.BusinessException;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InspectionService {
  public record ExportFile(String filename, byte[] content) {}

  private final RestClient detectClient;
  private final RestClient exportClient;

  public InspectionService(
      @Value("${app.inspection.detect-url:http://127.0.0.1:8000/detect}") String detectUrl,
      @Value("${app.inspection.export-url:http://127.0.0.1:8000/export}") String exportUrl,
      @Value("${app.inspection.timeout:60s}") Duration timeout
  ) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(timeout);
    this.detectClient = RestClient.builder()
        .baseUrl(detectUrl)
        .requestFactory(factory)
        .build();
    this.exportClient = RestClient.builder()
        .baseUrl(exportUrl)
        .requestFactory(factory)
        .build();
  }

  public ExportFile export(Map<String, Object> payload) {
    try {
      return exportClient.post()
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
              throw new BusinessException(
                  "INSPECTION_EXPORT_FAILED",
                  "Excel 生成失败（识别服务返回 " + response.getStatusCode().value() + "）",
                  HttpStatus.BAD_GATEWAY
              );
            }
            String filename = response.getHeaders().getFirst("X-Export-Filename");
            byte[] content = response.getBody().readAllBytes();
            return new ExportFile(filename == null ? "export.xlsx" : filename, content);
          });
    } catch (BusinessException ex) {
      throw ex;
    } catch (RestClientException | java.io.UncheckedIOException ex) {
      throw new BusinessException(
          "INSPECTION_SERVICE_UNAVAILABLE",
          "卫生识别服务不可用，请确认识别服务已启动",
          HttpStatus.BAD_GATEWAY
      );
    }
  }

  public Map<String, Object> detect(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException("INSPECTION_EMPTY_FILE", "请上传图片文件", HttpStatus.BAD_REQUEST);
    }

    ByteArrayResource resource;
    try {
      byte[] bytes = file.getBytes();
      String filename = file.getOriginalFilename() == null ? "photo.jpg" : file.getOriginalFilename();
      resource = new ByteArrayResource(bytes) {
        @Override
        public String getFilename() {
          return filename;
        }
      };
    } catch (IOException ex) {
      throw new BusinessException("INSPECTION_READ_FAILED", "图片读取失败", HttpStatus.BAD_REQUEST);
    }

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", resource);

    try {
      Map<String, Object> result = detectClient.post()
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      if (result == null) {
        throw new BusinessException("INSPECTION_EMPTY_RESULT", "识别服务返回为空", HttpStatus.BAD_GATEWAY);
      }
      return result;
    } catch (RestClientException ex) {
      throw new BusinessException(
          "INSPECTION_SERVICE_UNAVAILABLE",
          "卫生识别服务不可用，请确认识别服务已启动",
          HttpStatus.BAD_GATEWAY
      );
    }
  }
}
