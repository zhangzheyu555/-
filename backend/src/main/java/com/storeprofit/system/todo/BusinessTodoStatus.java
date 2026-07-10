package com.storeprofit.system.todo;

import com.storeprofit.system.common.BusinessException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public enum BusinessTodoStatus {
  PENDING("待处理"),
  IN_PROGRESS("处理中"),
  PENDING_REVIEW("待复核"),
  COMPLETED("已完成"),
  REJECTED("已驳回");

  private final String label;

  BusinessTodoStatus(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public boolean terminal() {
    return this == COMPLETED || this == REJECTED;
  }

  public static BusinessTodoStatus parse(String value) {
    if (value == null || value.isBlank()) {
      throw new BusinessException("TODO_STATUS_REQUIRED", "请选择待办状态", HttpStatus.BAD_REQUEST);
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("TODO_STATUS_INVALID", "待办状态不正确", HttpStatus.BAD_REQUEST);
    }
  }
}
