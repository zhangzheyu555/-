package com.storeprofit.system.storemanager;

import com.storeprofit.system.expense.ExpenseClaimResponse;
import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.todo.RoleTodoItemResponse;
import com.storeprofit.system.warehouse.WarehouseDeliveryResponse;
import com.storeprofit.system.warehouse.WarehouseOverviewResponse;
import com.storeprofit.system.warehouse.WarehouseRequisitionResponse;
import java.math.BigDecimal;
import java.util.List;

public record StoreManagerWorkbenchResponse(
    String roleName,
    String dataSource,
    String updatedAt,
    StoreScope store,
    StoreManagerFocus todayFocus,
    List<StoreManagerWorkbenchItem> todayFocusItems,
    List<StoreManagerWorkbenchItem> needMyAction,
    StoreManagerBusinessReminder businessReminder,
    WarehouseOverviewResponse warehouse,
    List<InspectionRecordResponse> rectifications,
    StoreManagerRecords records
) {
  public record StoreScope(
      String storeId,
      String storeName
  ) {
  }

  public record StoreManagerFocus(
      int pendingCount,
      int pendingReceiptCount,
      int rectificationCount,
      int rejectedExpenseCount,
      int businessRiskCount,
      String summary
  ) {
  }

  public record StoreManagerWorkbenchItem(
      String id,
      String title,
      String summary,
      String status,
      int priority,
      String sourceModule,
      String sourceRecordId,
      String dueAt,
      String nextActionLabel,
      String target,
      String actionMonth,
      String storeId,
      String storeName
  ) {
  }

  public record StoreManagerBusinessReminder(
      String month,
      BigDecimal income,
      BigDecimal net,
      BigDecimal margin,
      BigDecimal costRatio,
      String risk,
      String previousMonth,
      BigDecimal previousIncome,
      BigDecimal incomeChangeRate,
      List<String> reminders
  ) {
  }

  public record StoreManagerRecords(
      List<WarehouseRequisitionResponse> requisitions,
      List<WarehouseDeliveryResponse> deliveries,
      List<InspectionRecordResponse> inspections,
      List<ExpenseClaimResponse> expenses,
      List<RoleTodoItemResponse> doneTodos
  ) {
  }
}
