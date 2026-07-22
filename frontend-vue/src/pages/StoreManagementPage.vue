<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Home, Pencil, Plus, Power, RefreshCw, Trash2, X } from 'lucide-vue-next'
import {
  createStore,
  deleteStore,
  getBrands,
  getStores,
  updateStore,
  type BrandInfo,
  type StoreInfo,
  type StorePayload,
} from '../api/operations'
import BrandBadge from '../components/common/BrandBadge.vue'
import PageHeader from '../components/common/PageHeader.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import { isBossRole } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import { normalizeBrandName } from '../utils/brand'

const auth = useAuthStore()
const stores = ref<StoreInfo[]>([])
const brands = ref<BrandInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const editorOpen = ref(false)
const editingStore = ref<StoreInfo | null>(null)
const confirmTarget = ref<{ type: 'toggle' | 'delete'; store: StoreInfo } | null>(null)
const form = reactive<StorePayload>(emptyForm())
const statusFilter = ref<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL')

const activeStoreCount = computed(() => stores.value.filter(isActiveStore).length)
const filteredStores = computed(() => stores.value.filter((store) => {
  if (statusFilter.value === 'ALL') return true
  return statusFilter.value === 'ACTIVE' ? isActiveStore(store) : !isActiveStore(store)
}))
const canManageStores = computed(() => isBossRole(auth.role))
const editorTitle = computed(() => editingStore.value ? `编辑门店档案：${editingStore.value.name}` : '新增门店档案')
const confirmTitle = computed(() => {
  const target = confirmTarget.value
  if (!target) return ''
  if (target.type === 'delete') return `删除门店：${target.store.name}`
  return `${isActiveStore(target.store) ? '停用' : '启用'}门店：${target.store.name}`
})
const confirmMessage = computed(() => {
  const target = confirmTarget.value
  if (!target) return ''
  if (target.type === 'delete') {
    return '只允许删除没有任何经营、仓库、工资、报销、巡店或账号关联的门店。已有业务数据的门店请使用停用，历史记录仍会保留。'
  }
  return isActiveStore(target.store)
    ? '停用后该门店会从营业中统计中移出，历史经营、仓库、工资、报销和巡店记录不会被删除。'
    : '启用后该门店会重新计入营业中门店，并可继续参与业务流程。'
})
const confirmLabel = computed(() => {
  const target = confirmTarget.value
  if (!target) return '确认'
  if (target.type === 'delete') return '确认删除'
  return isActiveStore(target.store) ? '确认停用' : '确认启用'
})
const confirmVariant = computed(() => confirmTarget.value?.type === 'delete' ? 'danger' : 'primary')
const canSaveEditor = computed(() => Boolean(form.id.trim() && form.name.trim() && Number(form.brandId) > 0))

function emptyForm(): StorePayload {
  return {
    id: '',
    code: '',
    name: '',
    brandId: 0,
    area: '',
    manager: '',
    openDate: '',
    status: '营业中',
    note: '',
    regionCode: '',
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [storeRows, brandRows] = await Promise.all([getStores(), getBrands()])
    stores.value = storeRows
    brands.value = brandRows
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

function payloadFromStore(store: StoreInfo, overrides: Partial<StorePayload> = {}): StorePayload {
  return {
    id: store.id,
    code: store.code || '',
    name: store.name,
    brandId: store.brandId,
    area: store.area || '',
    manager: store.manager || '',
    openDate: store.openDate || '',
    status: store.status || '营业中',
    note: store.note || '',
    regionCode: store.regionCode || '',
    ...overrides,
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
  Object.assign(form, emptyForm())
}

async function saveEditor() {
  if (!canSaveEditor.value || saving.value) return
  const creating = !editingStore.value
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload = {
      id: form.id.trim(),
      code: form.code?.trim() || '',
      name: form.name.trim(),
      brandId: Number(form.brandId),
      area: form.area?.trim() || '',
      manager: form.manager?.trim() || '',
      openDate: form.openDate || '',
      status: form.status || '营业中',
      note: form.note?.trim() || '',
      regionCode: form.regionCode?.trim() || '',
    }
    if (creating) {
      await createStore(payload)
    } else {
      await updateStore(payload)
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
  confirmTarget.value = { type: 'toggle', store }
  error.value = ''
  notice.value = ''
}

function requestDelete(store: StoreInfo) {
  confirmTarget.value = { type: 'delete', store }
  error.value = ''
  notice.value = ''
}

async function confirmStoreAction() {
  const target = confirmTarget.value
  if (!target || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    if (target.type === 'delete') {
      await deleteStore(target.store.id)
      notice.value = `门店“${target.store.name}”已删除。`
    } else {
      const nextStatus = isActiveStore(target.store) ? '停用' : '营业中'
      await updateStore(payloadFromStore(target.store, { status: nextStatus }))
      notice.value = `门店“${target.store.name}”已${nextStatus === '营业中' ? '启用' : '停用'}。`
    }
    confirmTarget.value = null
    await load()
  } catch (actionError) {
    error.value = actionError instanceof Error ? actionError.message : '门店操作失败'
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
          <button v-if="canManageStores" class="primary-button" type="button" :disabled="loading || saving" @click="openCreateEditor">
            <Plus :size="16" />新增门店
          </button>
          <button class="ghost-button" type="button" :disabled="loading" @click="load">
            <RefreshCw :size="16" />刷新
          </button>
          <button v-if="canManageStores" class="ghost-button danger" type="button" disabled>清空全部数据</button>
        </div>
      </template>
    </PageHeader>

    <div v-if="error" class="error-box">{{ error }}</div>
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
            </div>
          </div>
        <label class="store-status-filter">
          状态筛选
          <select v-model="statusFilter" aria-label="门店状态筛选">
            <option value="ALL">全部</option>
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
      </div>
      <div v-if="loading && !stores.length" class="empty-state compact">正在读取门店档案...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>门店</th>
              <th>编号</th>
              <th>品牌</th>
              <th>区域</th>
              <th>负责人</th>
              <th>开业日期</th>
              <th>状态</th>
              <th v-if="canManageStores">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="store in filteredStores" :key="store.id">
              <td><b>{{ store.name }}</b><small>{{ store.id }}</small></td>
              <td>{{ store.code || '-' }}</td>
              <td><BrandBadge :brand-name="store.brandName || brandName(store.brandId)" /></td>
              <td>{{ store.area || '-' }}</td>
              <td>{{ store.manager || '-' }}</td>
              <td>{{ store.openDate || '-' }}</td>
              <td><span class="status-badge" :class="store.status === '营业中' ? 'ok' : 'warn'">{{ store.status || '未设置' }}</span></td>
              <td v-if="canManageStores">
                <div class="row-actions">
                  <button class="mini-button" type="button" :disabled="saving" @click="openEditor(store)">
                    <Pencil :size="14" />编辑
                  </button>
                  <button class="mini-button" type="button" :disabled="saving" @click="requestToggle(store)">
                    <Power :size="14" />{{ isActiveStore(store) ? '停用' : '启用' }}
                  </button>
                  <button class="mini-button danger" type="button" :disabled="saving" @click="requestDelete(store)">
                    <Trash2 :size="14" />删除
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!filteredStores.length">
              <td class="empty-table-row" :colspan="canManageStores ? 8 : 7">暂无符合当前状态筛选的门店</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="editorOpen" class="store-editor-mask" @click.self="closeEditor">
        <form class="store-editor" role="dialog" aria-modal="true" :aria-label="editorTitle" @submit.prevent="saveEditor">
          <header>
            <div>
              <h2>{{ editorTitle }}</h2>
              <span>保存会提交门店档案，并保留操作日志。</span>
            </div>
            <button class="icon-button" type="button" :disabled="saving" aria-label="关闭门店编辑" title="关闭" @click="closeEditor">
              <X :size="20" />
            </button>
          </header>

          <div class="store-form-grid">
            <label>
              门店 ID
              <input v-model.trim="form.id" :disabled="Boolean(editingStore)" required placeholder="例如 RG003" />
            </label>
            <label>
              门店编号
              <input v-model.trim="form.code" placeholder="例如 RG001" />
            </label>
            <label class="wide">
              门店名称
              <input v-model.trim="form.name" required placeholder="请输入门店名称" />
            </label>
            <label>
              品牌
              <select v-model.number="form.brandId" required>
                <option :value="0" disabled>请选择品牌</option>
                <option v-for="brand in brands" :key="brand.id" :value="brand.id">{{ normalizeBrandName(brand.name) }}</option>
              </select>
            </label>
            <label>
              状态
              <select v-model="form.status">
                <option value="营业中">营业中</option>
                <option value="停用">停用</option>
                <option value="停业">停业</option>
              </select>
            </label>
            <label>
              区域
              <input v-model.trim="form.area" placeholder="例如 荆州" />
            </label>
            <label>
              负责人
              <input v-model.trim="form.manager" placeholder="店长或负责人" />
            </label>
            <label>
              开业日期
              <input v-model="form.openDate" type="date" />
            </label>
            <label>
              区域编码
              <input v-model.trim="form.regionCode" placeholder="例如 JINGZHOU / SHANDONG" />
            </label>
            <label class="wide">
              备注
              <textarea v-model.trim="form.note" rows="4" placeholder="补充说明，可留空" />
            </label>
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
      :confirm-variant="confirmVariant"
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

.store-toolbar,
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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
  min-height: 36px;
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

.mini-button.danger {
  color: var(--bad);
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
  width: min(720px, 92vw);
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

.icon-button:hover:not(:disabled) {
  color: var(--ink);
  border-color: var(--accent);
}

.store-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  padding: 20px;
  overflow-y: auto;
}

.store-form-grid label {
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

.store-form-grid input:disabled {
  background: #f5f8f7;
  color: var(--muted);
}

@media (max-width: 720px) {
  .store-toolbar,
  .store-toolbar button {
    width: 100%;
  }

  .store-editor {
    width: 100vw;
  }

  .store-form-grid {
    grid-template-columns: 1fr;
    padding: 16px;
  }
}
</style>
