package com.storeprofit.system.platform.session;

import com.storeprofit.system.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {
  @GetMapping("/me")
  public ApiResponse<SessionUser> me() {
    return ApiResponse.ok(new SessionUser(
        1L,
        "管理员",
        "ADMIN",
        "管理员",
        List.of("all")
    ));
  }
}
