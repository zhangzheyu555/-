package com.storeprofit.system.employee;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
  private final AuthService authService;
  private final EmployeeService employeeService;

  public EmployeeController(AuthService authService, EmployeeService employeeService) {
    this.authService = authService;
    this.employeeService = employeeService;
  }

  @GetMapping
  public ApiResponse<List<EmployeeResponse>> records(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.ok(employeeService.records(authService.requireUser(authorization), brandId, storeId, status));
  }
}
