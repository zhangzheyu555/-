import type {
  WarehouseItem,
  WarehouseRequisition,
  WarehouseRequisitionLine,
  WarehouseReturnOrder,
} from '../api/warehouse'

const NON_COUNTING_RETURN_STATUSES = new Set(['REJECTED', 'CANCELLED'])

function optionalQuantity(value: number | undefined) {
  if (value === undefined || value === null) return null
  const quantity = Number(value)
  return Number.isFinite(quantity) ? Math.max(0, quantity) : null
}

function historicalReturnedQuantity(
  requisitionId: string,
  itemId: number,
  returns: WarehouseReturnOrder[],
) {
  return returns
    .filter((row) => (
      row.sourceRequisitionId === requisitionId
      && !NON_COUNTING_RETURN_STATUSES.has(row.status)
    ))
    .flatMap((row) => row.lines)
    .filter((line) => line.itemId === itemId)
    .reduce((total, line) => total + Math.max(0, Number(line.quantity || 0)), 0)
}

/**
 * The overview API is authoritative and returns `availableReturnQuantity`.
 * The fallback keeps mocked/older responses usable while applying the same
 * “received - returned, capped by current store stock” rule.
 */
export function availableStoreReturnQuantity(
  requisition: WarehouseRequisition,
  line: WarehouseRequisitionLine,
  returns: WarehouseReturnOrder[],
  items: WarehouseItem[],
) {
  const serverAvailable = optionalQuantity(line.availableReturnQuantity)
  if (serverAvailable !== null) return serverAvailable

  const received = optionalQuantity(line.receivedQuantity)
  const shipped = optionalQuantity(line.shippedQuantity) || 0
  const sourceQuantity = received !== null && received > 0 ? received : shipped
  const returned = optionalQuantity(line.returnedQuantity)
    ?? historicalReturnedQuantity(requisition.id, line.itemId, returns)
  const serverSourceAvailable = optionalQuantity(line.sourceAvailableReturnQuantity)
  const sourceAvailable = serverSourceAvailable
    ?? Math.max(0, sourceQuantity - returned)
  const serverStoreInventory = optionalQuantity(line.storeInventoryQuantity)
  const item = items.find((candidate) => candidate.id === line.itemId)
  const storeInventory = serverStoreInventory
    ?? (item ? Math.max(0, Number(item.storeStockQuantity || 0)) : sourceAvailable)

  return Math.max(0, Math.min(sourceAvailable, storeInventory))
}

export function hasAvailableStoreReturn(
  requisition: WarehouseRequisition,
  returns: WarehouseReturnOrder[],
  items: WarehouseItem[],
) {
  return requisition.lines.some((line) => (
    availableStoreReturnQuantity(requisition, line, returns, items) > 0
  ))
}
