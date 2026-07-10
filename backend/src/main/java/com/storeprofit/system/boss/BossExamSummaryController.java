package com.storeprofit.system.boss;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boss")
public class BossExamSummaryController {
  private final AuthService authService;
  private final BossExamSummaryService examSummaryService;

  public BossExamSummaryController(AuthService authService, BossExamSummaryService examSummaryService) {
    this.authService = authService;
    this.examSummaryService = examSummaryService;
  }

  @GetMapping("/exam-summary")
  public ApiResponse<BossExamSummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(examSummaryService.summary(authService.requireUser(authorization)));
  }
}
