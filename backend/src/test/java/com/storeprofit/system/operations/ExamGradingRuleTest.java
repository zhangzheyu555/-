package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExamGradingRuleTest {
  @Test
  void numericRequirementsMustAllMatchWhileTextAllowsEquivalentKeywords() {
    var recipe = question("TEXT", "白糖2000g、开水1000g、熬4分钟、冷藏72小时", "2000,1000,4,72,白糖");

    assertThat(ExamCenterService.isCorrect(recipe, "白糖2000克 开水1000克 熬4分钟 冷藏72小时")).isTrue();
    assertThat(ExamCenterService.isCorrect(recipe, "白糖2000克 开水1000克 熬4分钟")).isFalse();
    assertThat(ExamCenterService.isCorrect(recipe, "白糖3000克 开水1000克 熬4分钟 冷藏72小时")).isFalse();

    var service = question("TEXT", "让顾客开心地离开", "开心,走出去");
    assertThat(ExamCenterService.isCorrect(service, "顾客开心地离开")).isTrue();
  }

  @Test
  void fullWidthNumbersAreNormalizedButEssayStillRequiresManualReview() {
    var schedule = question("TEXT", "9:30 上架，21:00 前报备", "9:30,21:00,上架,报备");
    assertThat(ExamCenterService.isCorrect(schedule, "９：３０准时上架，２１：００前报备")).isTrue();
    assertThat(ExamCenterService.isCorrect(schedule, "10:30 上架，21:00 前报备")).isFalse();

    var essay = question("ESSAY", "人工参考答案", "关键词");
    assertThat(ExamCenterService.isCorrect(essay, "关键词")).isFalse();
  }

  private OperationsBusinessRepository.QuestionForGrade question(
      String type, String answer, String keywords
  ) {
    return new OperationsBusinessRepository.QuestionForGrade(
        1L, type, "题干", answer, keywords, BigDecimal.TEN);
  }
}
