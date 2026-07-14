package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InspectionStandardServiceTest {
  private final InspectionStandardRepository repository = mock(InspectionStandardRepository.class);
  private final InspectionStandardService service = new InspectionStandardService(repository);
  private final AuthUser supervisor = new AuthUser(
      1L, 1L, "默认企业", "supervisor", "", "督导", "SUPERVISOR", null, true);

  @Test
  void returnsCurrentTenantActiveStandardOnly() {
    InspectionStandardRepository.VersionRow version = new InspectionStandardRepository.VersionRow(
        7L,
        "全门店通用标准",
        new BigDecimal("100.00"),
        "2026.07",
        LocalDate.of(2026, 7, 1)
    );
    InspectionStandardItemResponse item = new InspectionStandardItemResponse(
        11L,
        "食品安全",
        "FS-01",
        "冷藏温度符合要求",
        "冷藏设备温度在规定范围内。",
        new BigDecimal("3.00"),
        false,
        true,
        10
    );
    when(repository.activeVersion(1L)).thenReturn(Optional.of(version));
    when(repository.items(1L, 7L)).thenReturn(List.of(item));

    InspectionStandardResponse response = service.activeStandard(supervisor);

    assertThat(response.title()).isEqualTo("全门店通用标准");
    assertThat(response.version()).isEqualTo("2026.07");
    assertThat(response.items()).containsExactly(item);
    assertThat(response.valid()).isFalse();
    assertThat(response.saveAllowed()).isFalse();
    assertThat(response.validationError()).contains("标准总分应为200分", "标准应为105条");
    assertThat(response.items()).containsExactly(item);
  }

  @Test
  void returnsAnEmptyConfigurationWhenNoActiveVersionExists() {
    when(repository.activeVersion(1L)).thenReturn(Optional.empty());

    InspectionStandardResponse response = service.activeStandard(supervisor);

    assertThat(response.title()).isEqualTo("全门店通用标准");
    assertThat(response.items()).isEmpty();
    assertThat(response.valid()).isFalse();
    assertThat(response.saveAllowed()).isFalse();
    assertThat(response.validationError()).isEqualTo("当前没有启用的巡检标准");
  }

  @Test
  void returnsAllInvalidItemsWithActionableCategoryDiagnostics() {
    InspectionStandardRepository.VersionRow version = new InspectionStandardRepository.VersionRow(
        38L,
        "错误标准",
        new BigDecimal("200.00"),
        new BigDecimal("180.00"),
        "2025.11.06",
        LocalDate.of(2025, 11, 6)
    );
    List<InspectionStandardItemResponse> items = List.of(
        item(1, "物料标准", "49.00", "RED", true),
        item(2, "卫生标准", "66.00", "NORMAL", false),
        item(3, "服务标准", "85.00", "YELLOW", false)
    );
    when(repository.activeVersion(1L)).thenReturn(Optional.of(version));
    when(repository.items(1L, 38L)).thenReturn(items);

    InspectionStandardResponse response = service.activeStandard(supervisor);

    assertThat(response.items()).containsExactlyElementsOf(items);
    assertThat(response.valid()).isFalse();
    assertThat(response.saveAllowed()).isFalse();
    assertThat(response.diagnostics()).extracting(InspectionStandardDiagnostic::message)
        .contains(
            "物料应为40条/37分，当前1条/49分",
            "卫生应为47条/63分，当前1条/66分",
            "服务应为18条/100分，当前1条/85分"
        );
    assertThat(response.categoryStats()).extracting(InspectionStandardCategoryStats::categoryCode)
        .containsExactly("MATERIAL", "HYGIENE", "SERVICE");
    assertThat(response.riskStats().redLineCount()).isEqualTo(1);
    assertThat(response.riskStats().yellowLineCount()).isEqualTo(1);
  }

  private InspectionStandardItemResponse item(
      long id,
      String dimension,
      String score,
      String riskLevel,
      boolean redLine
  ) {
    return new InspectionStandardItemResponse(
        id, dimension, "CODE-" + id, "条款" + id, "说明",
        new BigDecimal(score), redLine, true, (int) id, "方法", riskLevel);
  }
}
