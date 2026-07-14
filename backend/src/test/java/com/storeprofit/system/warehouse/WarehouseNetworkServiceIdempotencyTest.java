package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WarehouseNetworkServiceIdempotencyTest {
  private final WarehouseTopologyRepository repository = mock(WarehouseTopologyRepository.class);
  private final WarehouseTopologyService topology = mock(WarehouseTopologyService.class);
  private final WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
  private final WarehouseNetworkService service = new WarehouseNetworkService(
      repository, topology, warehouseRepository, mock(AccessControlService.class));
  private final AuthUser user = new AuthUser(9, 1, "企业", "sd", "", "山东仓", "WAREHOUSE", null, true);

  @Test
  void actionKeyCannotBeReusedAcrossTransferOrders() {
    WarehouseTransferResponse transfer = transfer("T-1");
    when(repository.transferForUpdate(1, "T-1")).thenReturn(Optional.of(transfer));
    when(repository.facility(1, 1)).thenReturn(Optional.of(central()));
    when(repository.facility(1, 2)).thenReturn(Optional.of(regional()));
    when(repository.actionTransferId(1, "SUBMIT", "same-key")).thenReturn(Optional.of("T-2"));

    assertThatThrownBy(() -> service.submit(
        user, "T-1", new WarehouseTransferActionRequest("same-key", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));

    verify(repository, never()).markSubmitted(1, "T-1", 0);
  }

  private WarehouseTransferResponse transfer(String id) {
    return new WarehouseTransferResponse(
        id, "DB001", "DRAFT", "草稿", 1, "荆州总仓", 2, "山东分仓",
        BigDecimal.ZERO, "山东仓", null, null, null, null,
        null, null, null, null, null, null, null, null, 0, List.of());
  }

  private FacilityRow central() {
    return new FacilityRow(1, "JZ-CENTRAL", "荆州总仓", "CENTRAL", "JINGZHOU",
        null, null, true, true, true);
  }

  private FacilityRow regional() {
    return new FacilityRow(2, "SD-REGIONAL", "山东分仓", "REGIONAL", "SHANDONG",
        1L, "荆州总仓", false, true, true);
  }
}
