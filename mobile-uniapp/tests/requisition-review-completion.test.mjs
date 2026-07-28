import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { test } from 'node:test'

const readSource = (relativePath) => {
  const path = new URL(relativePath, import.meta.url)
  return existsSync(path) ? readFileSync(path, 'utf8') : ''
}

const apiSource = readSource('../src/api/business.ts')
const detailSource = readSource('../src/pkg-store/requisition-detail/index.vue')
const priceEditorSource = readSource('../src/pkg-store/requisition-detail/RequisitionReviewPriceEditor.vue')
const warehouseTodosSource = readSource('../src/pkg-warehouse/index.vue')
const operationsSource = readSource('../src/pkg-warehouse/operations/index.vue')
const todoSource = readSource('../src/pages/todo/index.vue')
const storeRequisitionSource = readSource('../src/pages/requisition/index.vue')

test('移动端审核契约显式携带全量完成与逐项单价', () => {
  assert.match(apiSource, /export interface MobileRequisitionReviewPayload/)
  assert.match(apiSource, /handlingMode:\s*'FULL'/)
  assert.match(apiSource, /completeOnReview:\s*true/)
  assert.match(apiSource, /unitPrice:\s*number/)
  assert.match(detailSource, /handlingMode:\s*'FULL'/)
  assert.match(detailSource, /completeOnReview:\s*true/)
  assert.match(detailSource, /unitPrice:\s*Number\(reviewPrices\[line\.itemId\]\)/)
})

test('仓库审核页允许逐项修改非负单价并展示调整后总额', () => {
  assert.match(detailSource, /RequisitionReviewPriceEditor/)
  assert.match(priceEditorSource, /审核单价/)
  assert.match(priceEditorSource, /调整后总额/)
  assert.match(priceEditorSource, /type="digit"/)
  assert.match(priceEditorSource, /价格不能小于 0/)
})

test('新叫货流程在审核时完成库存同步且不再产生普通发货收货待办', () => {
  assert.match(detailSource, /仓库审核并入账/)
  assert.match(detailSource, /仓库库存已扣减，门店库存已增加/)
  assert.match(todoSource, /\.filter\(record => record\.status === 'SUBMITTED'/)
  assert.doesNotMatch(todoSource, /确认叫货发货/)
  assert.match(operationsSource, /filter\(row=>row\.status==='SUBMITTED'\)/)
})

test('历史已审核和已发货单仍保留兼容处理入口并明确标记历史流程', () => {
  assert.match(warehouseTodosSource, /历史待发货/)
  assert.match(warehouseTodosSource, /shipMobileRequisition/)
  assert.match(storeRequisitionSource, /历史待收货/)
  assert.match(storeRequisitionSource, /receiveMobileRequisition/)
  assert.match(detailSource, /历史流程：仓库确认发货/)
  assert.match(detailSource, /历史流程：门店确认收货/)
})
