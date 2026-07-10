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
  }

  @Test
  void returnsAnEmptyConfigurationWhenNoActiveVersionExists() {
    when(repository.activeVersion(1L)).thenReturn(Optional.empty());

    InspectionStandardResponse response = service.activeStandard(supervisor);

    assertThat(response.title()).isEqualTo("全门店通用标准");
    assertThat(response.items()).isEmpty();
  }
}
