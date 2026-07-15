package com.storeprofit.system.operations;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.operations.TrainingVideoModels.ProgressReportRequest;
import com.storeprofit.system.operations.TrainingVideoModels.ProgressResponse;
import com.storeprofit.system.operations.TrainingVideoModels.VideoResponse;
import com.storeprofit.system.operations.TrainingVideoModels.ViewerProgressRow;
import com.storeprofit.system.operations.TrainingVideoService.VideoContentResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/exam-center/videos")
public class TrainingVideoController {
  private final AuthService authService;
  private final TrainingVideoService trainingVideoService;

  public TrainingVideoController(AuthService authService, TrainingVideoService trainingVideoService) {
    this.authService = authService;
    this.trainingVideoService = trainingVideoService;
  }

  @GetMapping
  public ApiResponse<List<VideoResponse>> videos(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(trainingVideoService.videos(authService.requireUser(authorization)));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<VideoResponse> upload(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Long courseId,
      @RequestParam(required = false) Integer sortOrder
  ) {
    return ApiResponse.ok(trainingVideoService.upload(
        authService.requireUser(authorization), file, title, category, courseId, sortOrder));
  }

  @DeleteMapping("/{videoId}")
  public ApiResponse<Boolean> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long videoId
  ) {
    trainingVideoService.delete(authService.requireUser(authorization), videoId);
    return ApiResponse.ok(Boolean.TRUE);
  }

  @GetMapping("/{videoId}/content")
  public ResponseEntity<byte[]> content(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
      @PathVariable long videoId
  ) {
    VideoContentResponse video = trainingVideoService.content(
        authService.requireUser(authorization), videoId, range);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(video.contentType()));
    headers.setContentDisposition(ContentDisposition.inline()
        .filename(video.fileName(), StandardCharsets.UTF_8).build());
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.setCacheControl("no-store, private");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.setContentLength(video.content().length);
    if (video.partial()) {
      headers.set(HttpHeaders.CONTENT_RANGE,
          "bytes " + video.start() + "-" + video.end() + "/" + video.fileSize());
    }
    return ResponseEntity.status(video.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
        .headers(headers).body(video.content());
  }

  @PostMapping("/{videoId}/progress")
  public ApiResponse<ProgressResponse> reportProgress(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long videoId,
      @RequestBody ProgressReportRequest request
  ) {
    return ApiResponse.ok(trainingVideoService.reportProgress(
        authService.requireUser(authorization), videoId, request));
  }

  @GetMapping("/progress-report")
  public ApiResponse<List<ViewerProgressRow>> progressReport(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(trainingVideoService.progressReport(authService.requireUser(authorization)));
  }
}
