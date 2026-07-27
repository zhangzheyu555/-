<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  Calculator,
  Download,
  FileClock,
  PackageCheck,
  RefreshCw,
  Send,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import {
  downloadInventoryCheckExcel,
  getInventoryCheck,
  getInventoryChecks,
  getInventoryItems,
  reviewInventoryCheck,
  saveInventoryCheck,
  type InventoryCheck,
  type InventoryCheckLine,
  type InventoryCheckLinePayload,
  type InventoryDecimal,
  type InventoryItem,
} from '../api/inventoryChecks'
import { getStores, type StoreInfo } from '../api/operations'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import PageHeader from '../components/common/PageHeader.vue'
import SearchInput from '../components/common/SearchInput.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import UiButton from '../components/ui/UiButton.vue'
import { useBusinessScope } from '../composables/useBusinessScope'
import { PERMISSIONS } from '../permissions/permissions'
import { isBossRole, normalizeRoleCode } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import { normalizeBrandName } from '../utils/brand'
import { formatCny, formatDecimal } from '../utils/currency'

interface DraftLine extends InventoryItem {
  countedQuantity: string
  note: string
}

const route = useRoute()
const auth = useAuthStore()
const scope = useBusinessScope()
const inventoryItems = ref<InventoryItem[]>([])
const checks = ref<InventoryCheck[]>([])
const stores = ref<StoreInfo[]>([])
const draftLines = ref<DraftLine[]>([])
const itemSearch = ref('')
const itemCategory = ref('')
const historyStoreId = ref(queryValue(route.query.storeId))
const historyMonth = ref('')
const historyStatus = ref('')
const checkDate = ref(localDate())
const checkNote = ref('')
const activeCheck = ref<InventoryCheck | null>(null)
const detailRef = ref<HTMLElement | null>(null)
const loadingItems = ref(false)
const loadingChecks = ref(false)
const loadingStores = ref(false)
const refreshing = ref(false)
const saving = ref(false)
const detailLoadingId = ref<number | null>(null)
const reviewingId = ref<number | null>(null)
const exportingId = ref<number | null>(null)
const pageError = ref('')
const actionMessage = ref('')
const itemsError = ref('')
const checksError = ref('')
const reviewCandidate = ref<InventoryCheck | null>(null)

const normalizedRole = computed(() => normalizeRoleCode(auth.role))
const isBoss = computed(() => isBossRole(auth.role))
const isStoreManager = computed(() => normalizedRole.value === 'STORE_MANAGER')
const isRuguoStoreManager = computed(() => (
  isStoreManager.value && normalizeBrandName(auth.boundBrandName) === '茹菓'
))
const isReadOnlyViewer = computed(() => (
  ['FINANCE', 'SUPERVISOR', 'WAREHOUSE'].includes(normalizedRole.value)
))
const isInventoryRole = computed(() => (
  isBoss.value || isStoreManager.value || isReadOnlyViewer.value
))
const canReadPage = computed(() => (
  isInventoryRole.value
  && auth.hasPermission(PERMISSIONS.INVENTORY_READ)
))
const canLoadInventoryData = computed(() => !isStoreManager.value || isRuguoStoreManager.value)
const canManageDraft = computed(() => (
  isRuguoStoreManager.value
  && auth.hasPermission(PERMISSIONS.INVENTORY_MANAGE)
  && !scope.configurationError.value
))
const canReview = computed(() => (
  canReadPage.value
  && (isBoss.value || ['FINANCE', 'SUPERVISOR', 'WAREHOUSE'].includes(normalizedRole.value))
  && auth.hasPermission(PERMISSIONS.INVENTORY_REVIEW)
))
const canExport = computed(() => (
  canReadPage.value
  && (isBoss.value || ['FINANCE', 'SUPERVISOR', 'WAREHOUSE'].includes(normalizedRole.value))
))
const canFilterStores = computed(() => canReadPage.value && !isStoreManager.value)
const managerStoreId = computed(() => scope.boundStoreId.value)
const effectiveHistoryStoreId = computed(() => (
  isStoreManager.value ? managerStoreId.value : historyStoreId.value
))
const ruguoStores = computed(() => (
  stores.value.filter((store) => normalizeBrandName(store.brandName) === '茹菓')
))
const ruguoStoreIds = computed(() => new Set(ruguoStores.value.map((store) => store.id)))
const brandScopedChecks = computed(() => (
  isStoreManager.value
    ? (isRuguoStoreManager.value ? checks.value.filter(hasVisibleStatus) : [])
    : checks.value.filter((check) => ruguoStoreIds.value.has(check.storeId) && hasVisibleStatus(check))
))

const categories = computed(() => {
  const counts = new Map<string, number>()
  for (const item of draftLines.value) {
    const category = item.category?.trim() || '未分类'
    counts.set(category, (counts.get(category) || 0) + 1)
  }
  return Array.from(counts, ([name, count]) => ({ name, count }))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
})

const visibleDraftLines = computed(() => {
  const keyword = itemSearch.value.trim().toLowerCase()
  return draftLines.value.filter((line) => {
    const category = line.category?.trim() || '未分类'
    if (itemCategory.value && category !== itemCategory.value) return false
    if (!keyword) return true
    return [line.itemCode, line.itemName, line.category, line.spec, line.unit]
      .some((value) => String(value || '').toLowerCase().includes(keyword))
  })
})

const countedItemCount = computed(() => draftLines.value.filter((line) => quantityOf(line) > 0).length)
const draftTotal = computed(() => roundMoney(
  draftLines.value.reduce((total, line) => total + lineAmount(line), 0),
))

const storeOptions = computed(() => {
  const byId = new Map<string, { id: string; name: string; brandName?: string }>()
  for (const store of ruguoStores.value) {
    byId.set(store.id, { id: store.id, name: store.name || store.code || store.id, brandName: store.brandName })
  }
  return Array.from(byId.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
})

const monthOptions = computed(() => Array.from(new Set(
  brandScopedChecks.value.map((check) => check.checkDate?.slice(0, 7)).filter(Boolean),
)).sort().reverse())

const filteredChecks = computed(() => brandScopedChecks.value.filter((check) => {
  if (effectiveHistoryStoreId.value && check.storeId !== effectiveHistoryStoreId.value) return false
  if (historyMonth.value && check.checkDate?.slice(0, 7) !== historyMonth.value) return false
  if (historyStatus.value && check.status !== historyStatus.value) return false
  return true
}))

const filteredChecksTotal = computed(() => roundMoney(
  filteredChecks.value.reduce((total, check) => total + decimalNumber(check.totalAmount), 0),
))
const historyHelpText = computed(() => {
  if (isBoss.value) return '查看全部茹菓门店盘存；已提交记录可复核，全部记录可导出 Excel。'
  if (isStoreManager.value && !isRuguoStoreManager.value) return '店铺盘存仅适用于茹菓门店。'
  if (isStoreManager.value) return '录入并提交本店盘存；提交后由审核负责人复核。'
  return '查看权限范围内的茹菓门店盘存记录和金额，可导出 Excel，并可复核已提交记录。'
})

watch(
  () => route.query.storeId,
  (value) => {
    if (!canFilterStores.value) return
    historyStoreId.value = queryValue(value)
  },
)

onMounted(() => {
  void initialize()
})

async function initialize() {
  if (!canReadPage.value || !canLoadInventoryData.value) return
  await Promise.all([loadItems(), loadChecks(), canFilterStores.value ? loadStores() : Promise.resolve()])
}

async function refreshAll() {
  if (refreshing.value || !canLoadInventoryData.value) return
  refreshing.value = true
  pageError.value = ''
  actionMessage.value = ''
  try {
    await Promise.all([loadItems(true), loadChecks(), canFilterStores.value ? loadStores() : Promise.resolve()])
    actionMessage.value = '盘存数据已刷新。'
  } finally {
    refreshing.value = false
  }
}

async function loadItems(preserveDraft = false) {
  if (loadingItems.value) return
  loadingItems.value = true
  itemsError.value = ''
  try {
    const rows = await getInventoryItems()
    inventoryItems.value = [...rows].sort((a, b) => (
      Number(a.sortOrder ?? Number.MAX_SAFE_INTEGER) - Number(b.sortOrder ?? Number.MAX_SAFE_INTEGER)
      || a.itemCode.localeCompare(b.itemCode, 'zh-Hans-CN')
    ))
    if (isRuguoStoreManager.value) {
      draftLines.value = mergeDraftLines(
        inventoryItems.value,
        preserveDraft ? draftLines.value : [],
      )
    }
  } catch (error) {
    itemsError.value = readableError(error, '盘存物料档案加载失败，请稍后重试。')
  } finally {
    loadingItems.value = false
  }
}

async function loadChecks() {
  if (loadingChecks.value) return
  loadingChecks.value = true
  checksError.value = ''
  try {
    checks.value = await getInventoryChecks()
  } catch (error) {
    checksError.value = readableError(error, '盘存历史加载失败，请稍后重试。')
  } finally {
    loadingChecks.value = false
  }
}

async function loadStores() {
  if (loadingStores.value) return
  loadingStores.value = true
  try {
    stores.value = await getStores()
  } catch (error) {
    pageError.value = readableError(error, '门店列表加载失败，请稍后重试。')
  } finally {
    loadingStores.value = false
  }
}

function mergeDraftLines(items: InventoryItem[], previous: DraftLine[], detailLines: InventoryCheckLine[] = []) {
  const previousByCode = new Map(previous.map((line) => [line.itemCode, line]))
  const detailByCode = new Map(detailLines.map((line) => [String(line.itemCode || ''), line]))
  return items.map((item) => {
    const old = previousByCode.get(item.itemCode)
    const detail = detailByCode.get(item.itemCode)
    return {
      ...item,
      countedQuantity: detail
        ? quantityInput(detail.countedQuantity)
        : old?.countedQuantity || '',
      note: detail?.note || old?.note || '',
    } satisfies DraftLine
  })
}

function resetEntry() {
  checkDate.value = localDate()
  checkNote.value = ''
  draftLines.value = mergeDraftLines(inventoryItems.value, [])
  itemSearch.value = ''
  itemCategory.value = ''
  pageError.value = ''
}

async function submitCheck() {
  if (saving.value || !canManageDraft.value) return
  pageError.value = ''
  actionMessage.value = ''
  if (!managerStoreId.value) {
    pageError.value = '当前店长账号尚未绑定门店，无法提交盘存。'
    return
  }
  if (!checkDate.value) {
    pageError.value = '请选择盘存日期。'
    return
  }
  if (!draftLines.value.length) {
    pageError.value = '盘存物料档案为空，请联系老板补充档案后再提交。'
    return
  }
  const invalidLine = draftLines.value.find((line) => !validQuantity(line.countedQuantity))
  if (invalidLine) {
    pageError.value = `${invalidLine.itemName} 的盘存数量无效，请填写大于或等于 0 的数字。`
    return
  }
  saving.value = true
  try {
    const saved = await saveInventoryCheck({
      storeId: managerStoreId.value,
      checkDate: checkDate.value,
      note: checkNote.value.trim() || undefined,
      // 无论页面当前筛选显示多少行，都提交 API 返回的完整盘存物料档案。
      lines: draftLines.value.map(toLinePayload),
    })
    actionMessage.value = `盘存已提交（${saved.checkNo || `#${saved.id}`}），等待审核负责人复核。`
    resetEntry()
    await loadChecks()
  } catch (error) {
    pageError.value = readableError(error, '盘存提交失败，请检查后重试。')
  } finally {
    saving.value = false
  }
}

function toLinePayload(line: DraftLine): InventoryCheckLinePayload {
  const unitPrice = decimalNumber(line.unitPrice)
  return {
    itemId: line.id,
    itemCode: line.itemCode,
    itemName: line.itemName,
    category: line.category?.trim() || undefined,
    spec: line.spec?.trim() || undefined,
    unit: line.unit?.trim() || undefined,
    packageQuantity: decimalNumber(line.packageQuantity),
    packagePrice: decimalNumber(line.packagePrice),
    // 后端旧 DTO 中 unitPriceEach 的字段名未同步重命名，当前实际承载包装价。
    unitPrice,
    unitPriceEach: decimalNumber(line.packagePrice),
    countedQuantity: quantityOf(line),
    note: line.note.trim() || undefined,
  }
}

async function openCheck(check: InventoryCheck) {
  if (detailLoadingId.value) return
  detailLoadingId.value = check.id
  pageError.value = ''
  try {
    activeCheck.value = await getInventoryCheck(check.id)
    await nextTick()
    detailRef.value?.scrollIntoView({ block: 'start', behavior: reducedMotion() ? 'auto' : 'smooth' })
  } catch (error) {
    pageError.value = readableError(error, '盘存明细加载失败，请稍后重试。')
  } finally {
    detailLoadingId.value = null
  }
}

function askReview(check: InventoryCheck) {
  if (!canReview.value || check.status !== 'SUBMITTED') return
  reviewCandidate.value = check
}

async function confirmReview() {
  const check = reviewCandidate.value
  if (!check || reviewingId.value) return
  reviewingId.value = check.id
  pageError.value = ''
  try {
    const reviewed = await reviewInventoryCheck(check.id)
    actionMessage.value = `${reviewed.storeName || check.storeName} 的盘存已复核；审核负责人：${reviewerSummary(reviewed)}。`
    reviewCandidate.value = null
    if (activeCheck.value?.id === reviewed.id) activeCheck.value = await getInventoryCheck(reviewed.id)
    await loadChecks()
  } catch (error) {
    pageError.value = readableError(error, '盘存复核失败，请刷新后重试。')
  } finally {
    reviewingId.value = null
  }
}

async function exportCheck(check: InventoryCheck) {
  if (!canExport.value || exportingId.value) return
  exportingId.value = check.id
  pageError.value = ''
  try {
    await downloadInventoryCheckExcel(check)
    actionMessage.value = `${check.storeName} ${check.checkDate} 盘存 Excel 已开始下载。`
  } catch (error) {
    pageError.value = readableError(error, '盘存 Excel 导出失败，请稍后重试。')
  } finally {
    exportingId.value = null
  }
}

function lineAmount(line: Pick<DraftLine, 'countedQuantity' | 'unitPrice'>) {
  return roundMoney(quantityOf(line) * decimalNumber(line.unitPrice))
}

function quantityOf(line: Pick<DraftLine, 'countedQuantity'>) {
  const value = strictDecimal(line.countedQuantity)
  return value == null ? 0 : value
}

function validQuantity(value: string) {
  const parsed = strictDecimal(value)
  return parsed != null && parsed >= 0
}

function checkLineUnitPrice(line: InventoryCheckLine) {
  return line.unitPrice
}

function statusLabel(check: InventoryCheck) {
  if (check.statusLabel) return check.statusLabel
  const labels: Record<string, string> = {
    SUBMITTED: '已提交',
    REVIEWED: '已复核',
  }
  return labels[check.status] || '已提交'
}

function hasVisibleStatus(check: InventoryCheck) {
  return check.status === 'SUBMITTED' || check.status === 'REVIEWED'
}

function statusTone(status: string): 'ok' | 'warn' | 'bad' | 'info' | 'muted' {
  if (status === 'REVIEWED') return 'ok'
  return 'warn'
}

function reviewerRoleLabel(check: InventoryCheck) {
  if (check.reviewedByRoleLabel?.trim()) return check.reviewedByRoleLabel.trim()
  const role = normalizeRoleCode(check.reviewedByRole || '')
  const labels: Record<string, string> = {
    BOSS: '老板',
    FINANCE: '财务',
    SUPERVISOR: '督导',
    WAREHOUSE: '仓管',
  }
  return labels[role] || check.reviewedByRole || ''
}

function reviewerSummary(check: InventoryCheck) {
  if (check.status !== 'REVIEWED') return '待审核负责人复核'
  const name = check.reviewedByName?.trim() || (check.reviewedBy ? `账号 #${check.reviewedBy}` : '审核负责人')
  const role = reviewerRoleLabel(check)
  return role ? `${name}（${role}）` : name
}

function decimalNumber(value: InventoryDecimal) {
  const parsed = Number(String(value ?? '').replace(/,/g, ''))
  return Number.isFinite(parsed) ? parsed : 0
}

function strictDecimal(value: InventoryDecimal) {
  if (value == null || String(value).trim() === '') return 0
  const normalized = String(value).trim().replace(/,/g, '')
  if (!/^\d+(?:\.\d+)?$/.test(normalized)) return null
  const parsed = Number(normalized)
  return Number.isFinite(parsed) ? parsed : null
}

function quantityInput(value: InventoryDecimal) {
  const quantity = decimalNumber(value)
  return quantity ? String(value) : ''
}

function roundMoney(value: number) {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

function readableError(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function localDate() {
  const date = new Date()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function queryValue(value: unknown) {
  return Array.isArray(value) ? String(value[0] || '') : String(value || '')
}

function reducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
</script>

<template>
  <section class="page-panel inventory-check-page">
    <PageHeader title="店铺盘存">
      <template #actions>
        <UiButton :loading="refreshing" :disabled="!canReadPage || !canLoadInventoryData" @click="refreshAll">
          <template #icon><RefreshCw :size="16" /></template>
          刷新
        </UiButton>
      </template>
    </PageHeader>

    <div v-if="!canReadPage" class="message message--error" role="alert">
      当前账号没有访问店铺盘存的权限。
    </div>
    <template v-else>
      <div v-if="pageError" class="message message--error" role="alert">{{ pageError }}</div>
      <div v-if="actionMessage" class="message message--success" role="status">{{ actionMessage }}</div>
      <div v-if="isReadOnlyViewer" class="message message--info" role="status">
        当前可查看、导出并复核：可复核权限范围内已提交的茹菓门店盘存，不能录入。
      </div>
      <div v-if="isStoreManager && !isRuguoStoreManager" class="message message--info" role="status">
        店铺盘存仅适用于茹菓门店，当前门店不在盘存范围内。
      </div>

      <section class="content-card inventory-toolbar" aria-label="盘存筛选">
        <BusinessScopeBar v-if="isStoreManager" />
        <label v-else class="toolbar-field">
          <span>门店</span>
          <select v-model="historyStoreId" :disabled="loadingStores || loadingChecks" aria-label="盘存门店">
            <option value="">全部茹菓门店</option>
            <option v-for="store in storeOptions" :key="store.id" :value="store.id">
              {{ store.brandName ? `${store.brandName} · ` : '' }}{{ store.name }}
            </option>
          </select>
        </label>
        <label class="toolbar-field">
          <span>月份</span>
          <select v-model="historyMonth" :disabled="loadingChecks" aria-label="盘存月份">
            <option value="">全部月份</option>
            <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
          </select>
        </label>
        <label class="toolbar-field">
          <span>状态</span>
          <select v-model="historyStatus" :disabled="loadingChecks" aria-label="盘存状态">
            <option value="">全部状态</option>
            <option value="SUBMITTED">已提交</option>
            <option value="REVIEWED">已复核</option>
          </select>
        </label>
      </section>

      <section class="inventory-summary" aria-label="盘存摘要">
        <article>
          <span><PackageCheck :size="17" />参与盘存</span>
          <strong>{{ inventoryItems.length }}</strong>
          <small>{{ loadingItems ? '正在加载' : '项盘存物料' }}</small>
        </article>
        <article>
          <span><FileClock :size="17" />当前记录</span>
          <strong>{{ filteredChecks.length }}</strong>
          <small>符合当前筛选</small>
        </article>
        <article>
          <span><Calculator :size="17" />记录金额</span>
          <strong>{{ formatCny(filteredChecksTotal) }}</strong>
          <small>按当前筛选汇总</small>
        </article>
      </section>

      <section v-if="isRuguoStoreManager" class="content-card inventory-editor">
        <header class="section-head">
          <div>
            <h2>本店盘存录入</h2>
            <p>填写当前 {{ inventoryItems.length }} 项盘存物料并直接提交；提交后状态为“已提交”，等待审核负责人复核。</p>
          </div>
        </header>

        <div v-if="itemsError" class="inline-state inline-state--error" role="alert">{{ itemsError }}</div>
        <div v-else-if="loadingItems && !inventoryItems.length" class="inline-state" role="status">正在加载盘存物料档案…</div>
        <template v-else>
          <div class="draft-meta">
            <label>
              <span>盘存日期</span>
              <input v-model="checkDate" type="date" :disabled="saving" />
            </label>
            <label class="draft-note">
              <span>盘存备注</span>
              <input v-model.trim="checkNote" maxlength="300" :disabled="saving" placeholder="选填，例如交接班说明" />
            </label>
          </div>

          <div class="item-filter">
            <SearchInput v-model="itemSearch" aria-label="搜索盘存物料" placeholder="搜索物料名称、编码、分类或规格" />
            <label>
              <span class="sr-only">物料分类</span>
              <select v-model="itemCategory" aria-label="物料分类">
                <option value="">全部分类（{{ draftLines.length }}）</option>
                <option v-for="category in categories" :key="category.name" :value="category.name">
                  {{ category.name }}（{{ category.count }}）
                </option>
              </select>
            </label>
            <span class="filter-result">显示 {{ visibleDraftLines.length }} / {{ draftLines.length }} 项</span>
          </div>

          <div v-if="!visibleDraftLines.length" class="inline-state">没有符合当前搜索条件的物料。</div>
          <div v-else class="inventory-entry-table desktop-only">
            <table>
              <thead>
                <tr>
                  <th>物料</th>
                  <th>规格 / 单位</th>
                  <th class="number-cell">整件信息</th>
                  <th class="number-cell">计数单价</th>
                  <th class="quantity-column">盘存数量</th>
                  <th class="number-cell">金额</th>
                  <th class="note-column">备注</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="line in visibleDraftLines" :key="line.itemCode">
                  <td>
                    <b>{{ line.itemName }}</b>
                    <small>{{ line.itemCode }} · {{ line.category || '未分类' }}</small>
                  </td>
                  <td>{{ line.spec || '—' }}<small>{{ line.unit || '未设置单位' }}</small></td>
                  <td class="number-cell">
                    {{ decimalNumber(line.packageQuantity) ? `${formatDecimal(line.packageQuantity, 2)} ${line.unit || ''}` : '—' }}
                    <small>{{ line.packagePrice != null ? formatCny(line.packagePrice) : '未设置整件价' }}</small>
                  </td>
                  <td class="number-cell">
                    {{ formatCny(line.unitPrice, 4) }}
                  </td>
                  <td class="quantity-column">
                    <input
                      v-model="line.countedQuantity"
                      type="number"
                      min="0"
                      step="0.01"
                      inputmode="decimal"
                      :aria-label="`${line.itemName}盘存数量`"
                      :disabled="saving"
                      placeholder="0"
                    />
                  </td>
                  <td class="number-cell amount-cell">{{ formatCny(lineAmount(line)) }}</td>
                  <td class="note-column">
                    <input
                      v-model.trim="line.note"
                      maxlength="120"
                      :aria-label="`${line.itemName}备注`"
                      :disabled="saving"
                      placeholder="选填"
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="visibleDraftLines.length" class="inventory-entry-cards mobile-only">
            <article v-for="line in visibleDraftLines" :key="line.itemCode">
              <header>
                <div><b>{{ line.itemName }}</b><small>{{ line.itemCode }} · {{ line.category || '未分类' }}</small></div>
                <strong>{{ formatCny(line.unitPrice, 4) }}/{{ line.unit || '单位' }}</strong>
              </header>
              <dl>
                <div><dt>规格</dt><dd>{{ line.spec || '—' }}</dd></div>
                <div><dt>整件</dt><dd>{{ formatDecimal(line.packageQuantity, 2) }} {{ line.unit || '' }} / {{ formatCny(line.packagePrice) }}</dd></div>
              </dl>
              <label>
                <span>盘存数量</span>
                <input
                  v-model="line.countedQuantity"
                  type="number"
                  min="0"
                  step="0.01"
                  inputmode="decimal"
                  :aria-label="`${line.itemName}盘存数量`"
                  :disabled="saving"
                  placeholder="0"
                />
              </label>
              <label>
                <span>备注</span>
                <input
                  v-model.trim="line.note"
                  maxlength="120"
                  :aria-label="`${line.itemName}备注`"
                  :disabled="saving"
                  placeholder="选填"
                />
              </label>
              <footer><span>本项金额</span><strong>{{ formatCny(lineAmount(line)) }}</strong></footer>
            </article>
          </div>

          <footer class="editor-footer">
            <div>
              <span>已填写 {{ countedItemCount }} / {{ draftLines.length }} 项</span>
              <strong>盘存总金额 {{ formatCny(draftTotal) }}</strong>
            </div>
            <div class="editor-actions">
              <UiButton variant="primary" :loading="saving" :disabled="!canManageDraft || !draftLines.length" @click="submitCheck">
                <template #icon><Send :size="17" /></template>
                提交盘存
              </UiButton>
            </div>
          </footer>
        </template>
      </section>

      <section class="content-card inventory-history">
        <header class="section-head">
          <div>
            <h2>盘存历史</h2>
            <p>{{ historyHelpText }}</p>
          </div>
          <span class="panel-count">{{ filteredChecks.length }} 条</span>
        </header>
        <div v-if="checksError" class="inline-state inline-state--error" role="alert">{{ checksError }}</div>
        <div v-else-if="loadingChecks && !checks.length" class="inline-state">正在加载盘存历史…</div>
        <div v-else-if="!filteredChecks.length" class="inline-state">当前筛选范围暂无盘存记录。</div>
        <template v-else>
          <div class="history-table desktop-only">
            <table>
              <thead><tr><th>盘存单号</th><th>门店</th><th>日期</th><th>状态</th><th class="number-cell">金额</th><th>审核负责人 / 职务</th><th>更新时间</th><th class="history-actions-column">操作</th></tr></thead>
              <tbody>
                <tr v-for="check in filteredChecks" :key="check.id">
                  <td><b>{{ check.checkNo }}</b></td>
                  <td>{{ check.storeName || check.storeId }}</td>
                  <td>{{ check.checkDate }}</td>
                  <td><StatusBadge :label="statusLabel(check)" :tone="statusTone(check.status)" /></td>
                  <td class="number-cell amount-cell">{{ formatCny(check.totalAmount) }}</td>
                  <td>
                    <b>{{ reviewerSummary(check) }}</b>
                    <small v-if="check.reviewedAt">审核时间：{{ check.reviewedAt }}</small>
                  </td>
                  <td>{{ check.updatedAt || check.createdAt || '—' }}</td>
                  <td class="history-actions-column">
                    <button type="button" :disabled="detailLoadingId === check.id" @click="openCheck(check)">
                      {{ detailLoadingId === check.id ? '读取中' : '查看明细' }}
                    </button>
                    <button v-if="canExport" type="button" :disabled="exportingId === check.id" @click="exportCheck(check)">
                      {{ exportingId === check.id ? '导出中' : '导出 Excel' }}
                    </button>
                    <button v-if="canReview && check.status === 'SUBMITTED'" class="primary-text" type="button" @click="askReview(check)">复核</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="history-cards mobile-only">
            <article v-for="check in filteredChecks" :key="check.id">
              <header><div><b>{{ check.storeName || check.storeId }}</b><small>{{ check.checkNo }}</small></div><StatusBadge :label="statusLabel(check)" :tone="statusTone(check.status)" /></header>
              <dl>
                <div><dt>盘存日期</dt><dd>{{ check.checkDate }}</dd></div>
                <div><dt>盘存金额</dt><dd>{{ formatCny(check.totalAmount) }}</dd></div>
                <div><dt>审核负责人 / 职务</dt><dd>{{ reviewerSummary(check) }}</dd></div>
                <div v-if="check.reviewedAt"><dt>审核时间</dt><dd>{{ check.reviewedAt }}</dd></div>
              </dl>
              <footer>
                <button type="button" :disabled="detailLoadingId === check.id" @click="openCheck(check)">查看明细</button>
                <button v-if="canExport" type="button" :disabled="exportingId === check.id" @click="exportCheck(check)">导出 Excel</button>
                <button v-if="canReview && check.status === 'SUBMITTED'" class="primary-text" type="button" @click="askReview(check)">复核</button>
              </footer>
            </article>
          </div>
        </template>
      </section>

      <section v-if="activeCheck" ref="detailRef" class="content-card inventory-detail" tabindex="-1">
        <header class="section-head">
          <div>
            <h2>{{ activeCheck.storeName || activeCheck.storeId }} · {{ activeCheck.checkNo }}</h2>
            <p>{{ activeCheck.checkDate }} · 共 {{ activeCheck.lines?.length || 0 }} 项物料</p>
          </div>
          <div class="detail-actions">
            <StatusBadge :label="statusLabel(activeCheck)" :tone="statusTone(activeCheck.status)" />
            <UiButton v-if="canExport" :loading="exportingId === activeCheck.id" @click="exportCheck(activeCheck)">
              <template #icon><Download :size="16" /></template>
              导出 Excel
            </UiButton>
            <UiButton v-if="canReview && activeCheck.status === 'SUBMITTED'" variant="primary" @click="askReview(activeCheck)">复核通过</UiButton>
          </div>
        </header>
        <div class="detail-total">
          <span>盘存总金额</span><strong>{{ formatCny(activeCheck.totalAmount) }}</strong>
          <small v-if="activeCheck.note">备注：{{ activeCheck.note }}</small>
        </div>
        <div class="review-accountability">
          <div>
            <span>审核负责人 / 职务</span>
            <strong>{{ reviewerSummary(activeCheck) }}</strong>
          </div>
          <small v-if="activeCheck.reviewedAt">审核时间：{{ activeCheck.reviewedAt }}</small>
          <small v-else>状态为“已提交”，等待老板、财务、督导或仓管复核。</small>
        </div>
        <div v-if="!activeCheck.lines?.length" class="inline-state">该盘存单没有可显示的明细。</div>
        <template v-else>
          <div class="detail-table desktop-only">
            <table>
              <thead><tr><th>物料</th><th>规格 / 单位</th><th class="number-cell">计数单价</th><th class="number-cell">盘存数量</th><th class="number-cell">金额</th><th>备注</th></tr></thead>
              <tbody>
                <tr v-for="(line, index) in activeCheck.lines" :key="line.id || `${line.itemCode}-${index}`">
                  <td><b>{{ line.itemName }}</b><small>{{ line.itemCode || '无编码' }} · {{ line.category || '未分类' }}</small></td>
                  <td>{{ line.spec || '—' }}<small>{{ line.unit || '未设置单位' }}</small></td>
                  <td class="number-cell">{{ formatCny(checkLineUnitPrice(line), 4) }}</td>
                  <td class="number-cell">{{ formatDecimal(line.countedQuantity, 2) }}</td>
                  <td class="number-cell amount-cell">{{ formatCny(line.amount) }}</td>
                  <td>{{ line.note || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="detail-cards mobile-only">
            <article v-for="(line, index) in activeCheck.lines" :key="line.id || `${line.itemCode}-${index}`">
              <header><div><b>{{ line.itemName }}</b><small>{{ line.itemCode || '无编码' }} · {{ line.category || '未分类' }}</small></div><strong>{{ formatCny(line.amount) }}</strong></header>
              <dl>
                <div><dt>规格</dt><dd>{{ line.spec || '—' }} / {{ line.unit || '未设置单位' }}</dd></div>
                <div><dt>数量</dt><dd>{{ formatDecimal(line.countedQuantity, 2) }}</dd></div>
                <div><dt>计数单价</dt><dd>{{ formatCny(checkLineUnitPrice(line), 4) }}</dd></div>
              </dl>
              <p v-if="line.note">{{ line.note }}</p>
            </article>
          </div>
        </template>
      </section>
    </template>

    <ActionConfirmDialog
      :open="Boolean(reviewCandidate)"
      title="确认复核盘存"
      :message="reviewCandidate ? `确认 ${reviewCandidate.storeName} ${reviewCandidate.checkDate} 的盘存明细和金额无误？确认后将记录当前账号为审核负责人及其职务。` : ''"
      confirm-label="确认复核"
      :busy="Boolean(reviewingId)"
      @cancel="reviewCandidate = null"
      @confirm="confirmReview"
    />
  </section>
</template>

<style scoped>
.inventory-check-page {
  display: grid;
  gap: 16px;
}

.message {
  padding: 11px 13px;
  border: 1px solid;
  border-radius: 6px;
  font-size: 14px;
  line-height: 1.55;
}

.message--error,
.inline-state--error {
  border-color: #efc9c5;
  background: var(--ds-danger-soft, #fff0f1);
  color: #a53d35;
}

.message--success {
  border-color: #bfe0d3;
  background: var(--ds-success-soft, #edf8f2);
  color: #276f55;
}

.message--info {
  border-color: #bfd9e8;
  background: #f0f7fb;
  color: #2f637d;
}

.content-card {
  min-width: 0;
  padding: 18px;
}

.inventory-toolbar {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-field {
  display: grid;
  min-width: 172px;
  gap: 5px;
  color: var(--ds-muted);
  font-size: 12px;
  font-weight: 700;
}

.toolbar-field select,
.draft-meta input,
.item-filter select {
  width: 100%;
  min-height: 42px;
}

.inventory-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.inventory-summary article {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 14px 16px;
  border-right: 1px solid var(--ds-line);
}

.inventory-summary article:last-child { border-right: 0; }
.inventory-summary span { display: flex; align-items: center; gap: 7px; color: var(--ds-secondary); font-size: 13px; }
.inventory-summary strong { overflow: hidden; color: var(--ds-ink); font-size: 23px; text-overflow: ellipsis; white-space: nowrap; }
.inventory-summary small { color: var(--ds-muted); font-size: 12px; }

.section-head {
  display: flex;
  min-width: 0;
  align-items: start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-head > div:first-child { min-width: 0; }
.section-head h2 { margin: 0; color: var(--ds-ink); font-size: 18px; }
.section-head p { margin: 5px 0 0; color: var(--ds-muted); font-size: 13px; line-height: 1.55; }
.panel-count { flex: none; color: var(--ds-secondary); font-size: 13px; font-weight: 700; }

.inline-state {
  min-height: 88px;
  display: grid;
  place-items: center;
  padding: 18px;
  border: 1px dashed var(--ds-line);
  border-radius: 6px;
  color: var(--ds-muted);
  text-align: center;
}

.draft-meta {
  display: grid;
  grid-template-columns: minmax(180px, .35fr) minmax(280px, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

.draft-meta label {
  display: grid;
  min-width: 0;
  gap: 5px;
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 700;
}

.item-filter {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(190px, .45fr) auto;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.filter-result {
  color: var(--ds-muted);
  font-size: 12px;
  white-space: nowrap;
}

.inventory-entry-table,
.history-table,
.detail-table {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
}

table {
  width: 100%;
  border-collapse: collapse;
  color: var(--ds-secondary);
  font-size: 13px;
}

.inventory-entry-table table { min-width: 1080px; }
.history-table table { min-width: 1120px; }
.detail-table table { min-width: 820px; }

th,
td {
  min-height: 44px;
  padding: 9px 10px;
  border-bottom: 1px solid #e5ecea;
  text-align: left;
  vertical-align: middle;
}

th {
  background: #f6f9f8;
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

tbody tr:last-child td { border-bottom: 0; }
td b { display: block; color: var(--ds-ink); font-weight: 700; }
td small { display: block; margin-top: 3px; color: var(--ds-muted); font-size: 11px; }
.number-cell { text-align: right; font-variant-numeric: tabular-nums; }
.amount-cell { color: var(--ds-ink); font-weight: 800; }
.quantity-column { width: 132px; }
.note-column { width: 170px; }
.quantity-column input,
.note-column input {
  width: 100%;
  min-height: 38px;
}

.editor-footer {
  position: sticky;
  z-index: 5;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 14px -18px -18px;
  padding: 13px 18px calc(13px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--ds-line);
  background: rgba(255, 255, 255, .96);
  box-shadow: 0 -8px 18px rgba(28, 61, 58, .06);
  backdrop-filter: blur(8px);
}

.editor-footer > div:first-child { display: grid; gap: 3px; }
.editor-footer span { color: var(--ds-muted); font-size: 12px; }
.editor-footer strong { color: var(--ds-ink); font-size: 17px; }
.editor-actions,
.detail-actions { display: flex; align-items: center; justify-content: flex-end; gap: 9px; flex-wrap: wrap; }

.history-actions-column button,
.history-cards footer button {
  min-height: 34px;
  border: 1px solid var(--ds-line);
  border-radius: 5px;
  background: #fff;
  color: var(--ds-primary-hover);
  font-size: 12px;
  font-weight: 700;
}

.history-actions-column { width: 300px; text-align: right; white-space: nowrap; }
.history-actions-column button { margin-left: 5px; padding: 0 9px; }
.history-actions-column button.primary-text,
.history-cards footer button.primary-text { border-color: var(--ds-primary); background: var(--ds-primary-soft); }

.detail-total {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #f7faf9;
}
.detail-total span { color: var(--ds-muted); font-size: 13px; }
.detail-total strong { color: var(--ds-ink); font-size: 22px; }
.detail-total small { margin-left: auto; color: var(--ds-secondary); }

.review-accountability {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid #bfd9e8;
  border-radius: 6px;
  background: #f0f7fb;
}

.review-accountability > div {
  display: grid;
  gap: 3px;
}

.review-accountability span,
.review-accountability small {
  color: var(--ds-muted);
  font-size: 12px;
}

.review-accountability strong {
  color: var(--ds-ink);
  font-size: 14px;
}

.mobile-only { display: none; }
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

@media (max-width: 760px) {
  .desktop-only { display: none; }
  .mobile-only { display: grid; }
  .content-card { padding: 14px; }
  .inventory-toolbar,
  .inventory-toolbar :deep(.business-scope-static),
  .inventory-toolbar :deep(.business-scope-error),
  .toolbar-field { width: 100%; }
  .draft-meta,
  .item-filter { grid-template-columns: 1fr; }
  .filter-result { justify-self: start; }
  .section-head { align-items: stretch; flex-direction: column; gap: 10px; }
  .section-head :deep(.ui-button) { width: 100%; }
  .inventory-entry-cards,
  .history-cards,
  .detail-cards { gap: 10px; }
  .inventory-entry-cards article,
  .history-cards article,
  .detail-cards article {
    display: grid;
    gap: 11px;
    padding: 13px;
    border: 1px solid var(--ds-line);
    border-radius: 7px;
    background: #fff;
  }
  .inventory-entry-cards header,
  .history-cards header,
  .detail-cards header { display: flex; align-items: start; justify-content: space-between; gap: 10px; }
  .inventory-entry-cards header b,
  .history-cards header b,
  .detail-cards header b { display: block; color: var(--ds-ink); font-size: 14px; }
  .inventory-entry-cards header small,
  .history-cards header small,
  .detail-cards header small { display: block; margin-top: 3px; color: var(--ds-muted); font-size: 11px; }
  .inventory-entry-cards header strong,
  .detail-cards header strong { color: var(--ds-primary-hover); font-size: 13px; white-space: nowrap; }
  .inventory-entry-cards dl,
  .history-cards dl,
  .detail-cards dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 0; }
  .inventory-entry-cards dl div,
  .history-cards dl div,
  .detail-cards dl div { min-width: 0; padding: 8px; border-radius: 5px; background: #f5f8f7; }
  .inventory-entry-cards dt,
  .history-cards dt,
  .detail-cards dt { color: var(--ds-muted); font-size: 11px; }
  .inventory-entry-cards dd,
  .history-cards dd,
  .detail-cards dd { margin: 3px 0 0; color: var(--ds-ink); font-size: 13px; overflow-wrap: anywhere; }
  .inventory-entry-cards label { display: grid; gap: 5px; color: var(--ds-secondary); font-size: 12px; font-weight: 700; }
  .inventory-entry-cards input { width: 100%; min-height: 44px; }
  .inventory-entry-cards footer { display: flex; align-items: center; justify-content: space-between; padding-top: 10px; border-top: 1px solid var(--ds-line); }
  .inventory-entry-cards footer span { color: var(--ds-muted); font-size: 12px; }
  .inventory-entry-cards footer strong { color: var(--ds-ink); font-size: 17px; }
  .history-cards footer { display: flex; gap: 7px; flex-wrap: wrap; }
  .history-cards footer button { min-height: 44px; flex: 1 1 112px; padding: 0 10px; }
  .detail-cards p { margin: 0; color: var(--ds-secondary); font-size: 12px; }
  .editor-footer { align-items: stretch; flex-direction: column; margin: 12px -14px -14px; padding-right: 14px; padding-left: 14px; }
  .editor-actions { display: grid; grid-template-columns: 1fr; width: 100%; }
  .editor-actions :deep(.ui-button) { width: 100%; min-width: 0; padding: 0 10px; }
  .detail-actions { width: 100%; justify-content: flex-start; }
  .detail-actions :deep(.ui-button) { flex: 1 1 145px; }
  .detail-total { align-items: start; flex-direction: column; }
  .detail-total small { margin-left: 0; }
  .review-accountability { align-items: start; flex-direction: column; }
}

@media (max-width: 430px) {
  .inventory-summary { grid-template-columns: 1fr; }
  .inventory-summary article,
  .inventory-summary article:nth-child(2) { border-right: 0; border-bottom: 1px solid var(--ds-line); }
  .inventory-summary article:last-child { border-bottom: 0; }
  .editor-actions { grid-template-columns: 1fr; }
}
</style>
