<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Home, Pencil, Plus, Power, RefreshCw, Search, X } from 'lucide-vue-next'
import {
  createStore,
  getBrands,
  getStoreArchiveOptions,
  getStores,
  updateStore,
  updateStoreStatus,
  type BrandInfo,
  type StoreArchiveOptions,
  type StoreInfo,
  type StorePayload,
} from '../api/operations'
import BrandBadge from '../components/common/BrandBadge.vue'
import PageHeader from '../components/common/PageHeader.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import { isBossRole } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import { normalizeBrandName } from '../utils/brand'

const EMPTY_OPTIONS: StoreArchiveOptions = {
  regions: [],
  managers: [],
  statuses: [],
  costAccounts: [],
}

function normalizeArchiveOptions(value: unknown): StoreArchiveOptions {
  const candidate = value && typeof value === 'object'
    ? value as Partial<StoreArchiveOptions>
    : {}
  return {
    regions: Array.isArray(candidate.regions) ? candidate.regions : [],
    managers: Array.isArray(candidate.managers) ? candidate.managers : [],
    statuses: Array.isArray(candidate.statuses) ? candidate.statuses : [],
    costAccounts: Array.isArray(candidate.costAccounts) ? candidate.costAccounts : [],
  }
}

const auth = useAuthStore()
const stores = ref<StoreInfo[]>([])
const brands = ref<BrandInfo[]>([])
const archiveOptions = ref<StoreArchiveOptions>(EMPTY_OPTIONS)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const editorOpen = ref(false)
const editingStore = ref<StoreInfo | null>(null)
const confirmTarget = ref<StoreInfo | null>(null)
const form = reactive<StorePayload>(emptyForm())
const statusFilter = ref<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL')
const searchQuery = ref('')

const activeStoreCount = computed(() => stores.value.filter(isActiveStore).length)
const canManageStores = computed(() => isBossRole(auth.role))
const editorTitle = computed(() => (
  editingStore.value ? `编辑门店档案：${editingStore.value.name}` : '新增门店档案'
))
const contactIsValid = computed(() => validContact(form.managerPhone))
const canSaveEditor = computed(() => Boolean(
  form.code.trim()
  && form.name.trim()
  && Number(form.brandId) > 0
  && form.regionCode
  && form.managerEmployeeId
  && contactIsValid.value
  && form.status
  && form.costAccountStoreId,
))
const filteredStores = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  return stores.value.filter((store) => {
    const statusMatched = statusFilter.value === 'ALL'
      || (statusFilter.value === 'ACTIVE' ? isActiveStore(store) : !isActiveStore(store))
    if (!statusMatched) return false
    if (!keyword) return true
    return [
      store.name,
      store.code,
      store.area,
      store.regionCode,
      store.supplyWarehouseName,
      store.manager,
      store.managerPhone,
      store.costAccountStoreName,
    ].some((value) => String(value || '').toLowerCase().includes(keyword))
  })
})
const costAccountOptions = computed(() => archiveOptions.value.costAccounts.filter(
  (option) => option.storeId !== editingStore.value?.id,
))
const confirmTitle = computed(() => {
  const store = confirmTarget.value
  if (!store) return ''
  return `${isActiveStore(store) ? '停用' : '启用'}门店：${store.name}`
})
const confirmMessage = computed(() => {
  const store = confirmTarget.value
  if (!store) return ''
  return isActiveStore(store)
    ? '停用后仍保留历史经营、财务、库存和业务单据，但该门店不能再创建新的业务单据。'
    : '重新启用前系统会校验区域、负责人、联系方式和成本账归属；通过后恢复新业务权限。'
})
const confirmLabel = computed(() => (
  confirmTarget.value && isActiveStore(confirmTarget.value) ? '确认停用' : '确认启用'
))

function emptyForm(): StorePayload {
  return {
    code: '',
    name: '',
    brandId: 0,
    managerEmployeeId: '',
    managerPhone: '',
    openDate: '',
    status: '',
    note: '',
    regionCode: '',
    costAccountStoreId: '',
  }
}

function validContact(value: string) {
  const normalized = String(value || '').replace(/\s/g, '')
  return /^1[3-9]\d{9}$/.test(normalized)
    || /^(?:0\d{2,3}-?)?\d{7,8}(?:-\d{1,6})?$/.test(normalized)
    || /^[48]00-?\d{3}-?\d{4}$/.test(normalized)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [storeRows, brandRows, optionRows] = await Promise.all([
      getStores(),
      getBrands(),
      getStoreArchiveOptions(),
    ])
    stores.value = storeRows
    brands.value = brandRows
    archiveOptions.value = normalizeArchiveOptions(optionRows)
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : '门店管理加载失败'
  } finally {
    loading.value = false
  }
}

function brandName(id: number) {
  return normalizeBrandName(brands.value.find((brand) => brand.id === id)?.name || '-')
}

function isActiveStore(store: StoreInfo) {
  return !store.status || store.status === '营业中' || store.status.toUpperCase() === 'ACTIVE'
}

function payloadFromStore(store: StoreInfo): StorePayload {
  return {
    id: store.id,
    code: store.code || '',
    name: store.name,
    brandId: store.brandId,
    managerEmployeeId: store.managerEmployeeId || '',
    managerPhone: store.managerPhone || '',
    openDate: store.openDate || '',
    status: store.status || '',
    note: store.note || '',
    regionCode: store.regionCode || '',
    costAccountStoreId: !store.costAccountStoreId || store.costAccountStoreId === store.id
      ? 'SELF'
      : store.costAccountStoreId,
    version: Number(store.version || 0),
  }
}

function openEditor(store: StoreInfo) {
  editingStore.value = store
  Object.assign(form, payloadFromStore(store))
  error.value = ''
  notice.value = ''
  editorOpen.value = true
}

function openCreateEditor() {
  editingStore.value = null
  Object.assign(form, emptyForm())
  error.value = ''
  notice.value = ''
  editorOpen.value = true
}

function closeEditor() {
  if (saving.value) return
  editorOpen.value = false
  editingStore.value = null
  error.value = ''
  Object.assign(form, emptyForm())
}

function managerChanged() {
  const manager = archiveOptions.value.managers.find(
    (option) => option.employeeId === form.managerEmployeeId,
  )
  if (manager?.phone) {
    form.managerPhone = manager.phone
  }
}

async function saveEditor() {
  if (!canSaveEditor.value || saving.value) return
  const creating = !editingStore.value
  saving.value = true
  error.value = ''
  notice.value = ''
  const basePayload = {
    code: form.code.trim(),
    name: form.name.trim(),
    brandId: Number(form.brandId),
    managerEmployeeId: form.managerEmployeeId,
    managerPhone: form.managerPhone.replace(/\s/g, ''),
    openDate: form.openDate || '',
    status: form.status,
    note: form.note?.trim() || '',
    regionCode: form.regionCode,
    costAccountStoreId: form.costAccountStoreId,
  }
  try {
    if (creating) {
      await createStore(basePayload)
    } else {
      await updateStore({
        ...basePayload,
        id: editingStore.value!.id,
        version: Number(form.version),
      })
    }
    notice.value = creating ? '门店档案已新增。' : '门店档案已保存。'
    editorOpen.value = false
    editingStore.value = null
    Object.assign(form, emptyForm())
    await load()
  } catch (saveError) {
    error.value = saveError instanceof Error ? saveError.message : '门店档案保存失败'
  } finally {
    saving.value = false
  }
}

function requestToggle(store: StoreInfo) {
  confirmTarget.value = store
  error.value = ''
  notice.value = ''
}

async function confirmStoreAction() {
  const store = confirmTarget.value
  if (!store || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  const active = isActiveStore(store)
  const nextStatus = active ? '停用' : '营业中'
  try {
    await updateStoreStatus(store.id, nextStatus, Number(store.version || 0))
    notice.value = `门店“${store.name}”已${active ? '停用' : '启用'}。`
    confirmTarget.value = null
    await load()
  } catch (actionError) {
    error.value = actionError instanceof Error ? actionError.message : '门店状态变更失败'
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="page-panel stores-page">
    <PageHeader>
      <template #actions>
        <div class="store-toolbar">
          <button
            v-if="canManageStores"
            class="primary-button"
            type="button"
            :disabled="loading || saving"
            @click="openCreateEditor"
          >
            <Plus :size="16" />新增门店
          </button>
          <button class="ghost-button" type="button" :disabled="loading" @click="load">
            <RefreshCw :size="16" />刷新
          </button>
        </div>
      </template>
    </PageHeader>

    <div v-if="error && !editorOpen" class="error-box">{{ error }}</div>
    <div v-if="notice" class="success-box">{{ notice }}</div>

    <div class="metric-grid">
      <article class="metric-card">
        <span>门店总数</span>
        <b>{{ stores.length }}</b>
      </article>
      <article class="metric-card">
        <span>营业中</span>
        <b>{{ activeStoreCount }}</b>
        <small>停业或停用 {{ stores.length - activeStoreCount }} 家</small>
      </article>
      <article class="metric-card">
        <span>品牌数量</span>
        <b>{{ brands.length }}</b>
      </article>
    </div>

    <section class="content-card">
      <div class="table-heading">
        <div class="stores-title">
          <Home :size="20" />
          <div>
            <h3>门店档案</h3>
            <small>停用只影响新业务，历史资料始终保留</small>
          </div>
        </div>
        <div class="store-filters">
          <label class="store-search">
            <Search :size="15" />
            <input v-model="searchQuery" aria-label="查询门店" placeholder="名称、编号、负责人或成本账" />
          </label>
          <label class="store-status-filter">
            状态筛选
            <select v-model="statusFilter" aria-label="门店状态筛选">
              <option value="ALL">全部</option>
              <option value="ACTIVE">启用</option>
              <option value="INACTIVE">停用</option>
            </select>
          </label>
        </div>
      </div>

      <div v-if="loading && !stores.length" class="empty-state compact">正在读取门店档案...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>门店</th>
              <th>编号</th>
              <th>品牌</th>
              <th>所属区域</th>
              <th>负责人</th>
              <th>联系方式</th>
              <th>成本账归属</th>
              <th>状态</th>
              <th v-if="canManageStores">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="store in filteredStores" :key="store.id">
              <td><b>{{ store.name }}</b><small>{{ store.id }}</small></td>
              <td>{{ store.code || '-' }}</td>
              <td><BrandBadge :brand-name="store.brandName || brandName(store.brandId)" /></td>
              <td>{{ store.supplyWarehouseName || store.area || store.regionCode || '-' }}</td>
              <td>{{ store.manager || '-' }}</td>
              <td>{{ store.managerPhone || '-' }}</td>
              <td>{{ store.costAccountStoreName || store.name }}</td>
              <td>
                <span class="status-badge" :class="isActiveStore(store) ? 'ok' : 'warn'">
                  {{ store.status || '未设置' }}
                </span>
              </td>
              <td v-if="canManageStores">
                <div class="row-actions">
                  <button class="mini-button" type="button" :disabled="saving" @click="openEditor(store)">
                    <Pencil :size="14" />编辑
                  </button>
                  <button class="mini-button" type="button" :disabled="saving" @click="requestToggle(store)">
                    <Power :size="14" />{{ isActiveStore(store) ? '停用' : '启用' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!filteredStores.length">
              <td class="empty-table-row" :colspan="canManageStores ? 9 : 8">暂无符合条件的门店</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="editorOpen" class="store-editor-mask" @click.self="closeEditor">
        <form
          class="store-editor"
          role="dialog"
          aria-modal="true"
          :aria-label="editorTitle"
          @submit.prevent="saveEditor"
        >
          <header>
            <div>
              <h2>{{ editorTitle }}</h2>
              <span>下拉基础资料均由后端实时加载，保存后立即生效。</span>
            </div>
            <button
              class="icon-button"
              type="button"
              :disabled="saving"
              aria-label="关闭门店编辑"
              title="关闭"
              @click="closeEditor"
            >
              <X :size="20" />
            </button>
          </header>

          <div class="store-form-scroll">
            <div v-if="error" class="error-box">{{ error }}</div>
            <div class="store-form-grid">
              <label>
                门店编号
                <input v-model.trim="form.code" required placeholder="例如 RG003" />
              </label>
              <label>
                门店名称
                <input v-model.trim="form.name" required placeholder="请输入门店名称" />
              </label>
              <label>
                品牌
                <select v-model.number="form.brandId" required>
                  <option :value="0" disabled>请选择品牌</option>
                  <option v-for="brand in brands" :key="brand.id" :value="brand.id">
                    {{ normalizeBrandName(brand.name) }}
                  </option>
                </select>
              </label>
              <label>
                所属区域
                <select v-model="form.regionCode" required>
                  <option value="" disabled>请选择所属区域</option>
                  <option v-for="region in archiveOptions.regions" :key="region.code" :value="region.code">
                    {{ region.name }}
                  </option>
                </select>
              </label>
              <label>
                负责人
                <select v-model="form.managerEmployeeId" required @change="managerChanged">
                  <option value="" disabled>请选择负责人</option>
                  <option
                    v-for="manager in archiveOptions.managers"
                    :key="manager.employeeId"
                    :value="manager.employeeId"
                  >
                    {{ manager.name }}（{{ manager.storeName }}）
                  </option>
                </select>
              </label>
              <label>
                联系方式
                <input
                  v-model.trim="form.managerPhone"
                  required
                  inputmode="tel"
                  autocomplete="tel"
                  placeholder="手机号或合法联系电话"
                />
                <small v-if="form.managerPhone && !contactIsValid" class="form-error">
                  请输入手机号或合法联系电话
                </small>
              </label>
              <label>
                经营状态
                <select v-model="form.status" required>
                  <option value="" disabled>请选择经营状态</option>
                  <option
                    v-for="status in archiveOptions.statuses"
                    :key="status.value"
                    :value="status.value"
                  >
                    {{ status.label }}
                  </option>
                </select>
              </label>
              <label>
                成本账归属
                <select v-model="form.costAccountStoreId" required>
                  <option value="" disabled>请选择成本账归属</option>
                  <option value="SELF">本门店独立成本账</option>
                  <option
                    v-for="account in costAccountOptions"
                    :key="account.storeId"
                    :value="account.storeId"
                  >
                    {{ account.storeName }}（{{ account.storeCode }}）
                  </option>
                </select>
              </label>
              <label>
                开业日期
                <input v-model="form.openDate" type="date" />
              </label>
              <label class="wide">
                备注
                <textarea v-model.trim="form.note" rows="4" placeholder="补充说明，可留空" />
              </label>
            </div>
          </div>

          <footer>
            <button class="ghost-button" type="button" :disabled="saving" @click="closeEditor">取消</button>
            <button class="primary-button" type="submit" :disabled="saving || !canSaveEditor">
              {{ saving ? '保存中...' : editingStore ? '保存门店档案' : '新增门店' }}
            </button>
          </footer>
        </form>
      </div>
    </Teleport>

    <ActionConfirmDialog
      :open="Boolean(confirmTarget)"
      :title="confirmTitle"
      :message="confirmMessage"
      :confirm-label="confirmLabel"
      cancel-label="取消"
      :confirm-variant="confirmTarget && isActiveStore(confirmTarget) ? 'danger' : 'primary'"
      :busy="saving"
      @cancel="confirmTarget = null"
      @confirm="confirmStoreAction"
    />
  </section>
</template>

<style scoped>
.stores-page {
  display: grid;
  gap: 18px;
}

.stores-title {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.stores-title h3 {
  margin: 0 0 3px;
  font-size: 18px;
}

.stores-title small {
  color: var(--muted);
}

.store-toolbar,
.row-actions,
.store-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.store-filters {
  justify-content: flex-end;
}

.store-search {
  min-width: min(310px, 76vw);
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 0 10px;
  background: #fff;
  color: var(--muted);
}

.store-search input {
  width: 100%;
  border: 0;
  outline: 0;
  color: var(--ink);
  font: inherit;
  font-weight: 700;
}

.store-status-filter {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 800;
}

.store-status-filter select {
  min-height: 38px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 28px 6px 10px;
  background: #fff;
  color: var(--ink);
  font: inherit;
  font-weight: 700;
}

.empty-table-row {
  padding: 28px 16px;
  color: var(--muted);
  text-align: center;
}

.row-actions {
  justify-content: flex-start;
}

.mini-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.store-editor-mask {
  position: fixed;
  z-index: var(--ds-z-modal, 1400);
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: rgba(19, 39, 38, .42);
}

.store-editor {
  width: min(720px, 94vw);
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  box-shadow: -12px 0 36px rgba(19, 39, 38, .18);
}

.store-editor header,
.store-editor footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--line);
}

.store-editor footer {
  margin-top: auto;
  justify-content: flex-end;
  border-top: 1px solid var(--line);
  border-bottom: 0;
}

.store-editor footer button {
  min-width: 84px;
  white-space: nowrap;
}

.store-editor h2 {
  margin: 0 0 4px;
  font-size: 19px;
}

.store-editor header span {
  color: var(--muted);
  font-size: 13px;
}

.icon-button {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: var(--muted);
  cursor: pointer;
}

.store-form-scroll {
  overflow-y: auto;
  padding: 20px;
}

.store-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.store-form-grid label {
  align-content: start;
  display: grid;
  gap: 7px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 800;
}

.store-form-grid .wide {
  grid-column: 1 / -1;
}

.store-form-grid input,
.store-form-grid select,
.store-form-grid textarea {
  width: 100%;
  min-height: 40px;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 9px 11px;
  background: #fff;
  color: var(--ink);
  font: inherit;
  font-weight: 700;
}

.store-form-grid textarea {
  resize: vertical;
}

.store-form-grid input:focus,
.store-form-grid select:focus,
.store-form-grid textarea:focus {
  border-color: var(--accent);
  outline: 3px solid rgba(39, 107, 101, .16);
}

.form-error {
  color: var(--bad);
  font-size: 12px;
  font-weight: 700;
}

@media (max-width: 720px) {
  .store-toolbar,
  .store-toolbar button,
  .store-filters,
  .store-search,
  .store-status-filter {
    width: 100%;
  }

  .store-status-filter select {
    flex: 1;
  }

  .store-editor {
    width: 100vw;
  }

  .store-form-scroll {
    padding: 16px;
  }

  .store-form-grid {
    grid-template-columns: 1fr;
  }

  .store-form-grid .wide {
    grid-column: auto;
  }
}
</style>
