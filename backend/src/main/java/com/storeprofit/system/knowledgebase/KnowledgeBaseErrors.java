package com.storeprofit.system.knowledgebase;

import com.storeprofit.system.common.BusinessException;
import org.springframework.http.HttpStatus;

final class KnowledgeBaseErrors {
  private KnowledgeBaseErrors() {}

  static BusinessException badRequest(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }
}
