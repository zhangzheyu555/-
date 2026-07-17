<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, ExternalLink, Link2, Plus, RefreshCw, Trash2, X } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import UiButton from '../components/ui/UiButton.vue'
import { ApiError } from '../api/http'
import { getStores, type StoreInfo } from '../api/operations'
import {
  discoverQmaiShops,
  getQmaiConfig,
  getQmaiStatus,
  saveQmaiConfig,
  type QmaiConfig,
  type QmaiMapping,
} from '../api/qmai'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const qmai = ref<QmaiConfig | null>(null)
const stores = ref<StoreInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const discovering = ref(false)
const configOpen = ref(false)
const error = ref('')
const notice = ref('')
const form = ref({ enabled: false, displayName: '企迈', mappings: [] as QmaiMapping[] })

const canManage = computed(() => auth.hasPermission(PERMISSIONS.PLATFORM_MANAGE))
const canViewBusiness = computed(() => auth.hasPermission(PERMISSIONS.FINANCE_PROFIT_READ))
const statusText = computed(() => {
  if (!qmai.value?.credentialConfigured) return '待部署凭证'
  if (!qmai.value.enabled) return '未启用'
  if (!qmai.value.mappings.length) return '待映射门店'
  return '连接就绪'
})
const statusClass = computed(() => statusText.value === '连接就绪' ? 'ok' : 'warn')

onMounted(loadStatus)

async function loadStatus() {
  loading.value = true
  error.value = ''
  try {
    qmai.value = await getQmaiStatus()
  } catch (reason) {
    error.value = messageOf(reason)
  } finally {
    loading.value = false
  }
}

async function openConfig() {
  error.value = ''
  notice.value = ''
  try {
    const [config, storeRows] = await Promise.all([getQmaiConfig(), getStores()])
    qmai.value = config
    stores.value = storeRows
    form.value = {
      enabled: config.enabled,
      displayName: config.displayName || '企迈',
      mappings: config.mappings.map((mapping) => ({ ...mapping })),
    }
    configOpen.value = true
  } catch (reason) {
    error.value = messageOf(reason)
  }
}

async function discover() {
  discovering.value = true
  error.value = ''
  try {
    const discovered = await discoverQmaiShops()
    const existing = new Map(form.value.mappings.map((mapping) => [mapping.qmaiShopId, mapping]))
    form.value.mappings = discovered.map((shop) => ({
      qmaiShopId: shop.qmaiShopId,
      qmaiShopName: shop.qmaiShopName,
      storeId: existing.get(shop.qmaiShopId)?.storeId || '',
    }))
    notice.value = `已读取 ${discovered.length} 家企迈授权门店，请完成系统门店映射。`
  } catch (reason) {
    error.value = messageOf(reason)
  } finally {
    discovering.value = false
  }
}

function addMapping() {
  form.value.mappings.push({ qmaiShopId: '', qmaiShopName: '', storeId: '' })
}

function removeMapping(index: number) {
  form.value.mappings.splice(index, 1)
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    qmai.value = await saveQmaiConfig({
      enabled: form.value.enabled,
      displayName: form.value.displayName,
      mappings: form.value.mappings,
    })
    configOpen.value = false
    notice.value = '企迈连接配置已保存。'
  } catch (reason) {
    error.value = messageOf(reason)
  } finally {
    saving.value = false
  }
}

function messageOf(reason: unknown) {
  return reason instanceof ApiError ? reason.message : '企迈配置加载失败，请稍后重试'
}
</script>

<template>
  <section class="page-panel platform-page">
    <PageHeader title="平台配置" subtitle="平台密钥由部署环境保管，这里只管理启用状态与门店对应关系" />

    <p v-if="error" class="feedback feedback--error">{{ error }}</p>
    <p v-if="notice" class="feedback feedback--success">{{ notice }}</p>

    <div class="platform-grid">
      <article class="content-card platform-card qmai-card">
        <div class="platform-card__top">
          <span class="platform-icon"><Link2 :size="21" /></span>
          <span class="status-badge" :class="statusClass">{{ loading ? '读取中' : statusText }}</span>
        </div>
        <div>
          <h3>{{ qmai?.displayName || '企迈' }}</h3>
          <p>营业额与商品销售数据</p>
        </div>
        <dl class="connection-facts">
          <div><dt>部署凭证</dt><dd>{{ qmai?.credentialConfigured ? '已配置' : '未配置' }}</dd></div>
          <div><dt>门店映射</dt><dd>{{ qmai?.mappings.length || 0 }} 家</dd></div>
        </dl>
        <div class="platform-actions">
          <UiButton v-if="canViewBusiness" variant="primary" @click="router.push('/qmai-business')">
            查看经营数据 <template #icon><ArrowRight :size="16" /></template>
          </UiButton>
          <UiButton v-if="canManage" @click="openConfig">配置连接</UiButton>
        </div>
      </article>

      <article v-for="platform in ['美团', '饿了么', '抖音', '京东']" :key="platform" class="content-card platform-card quiet-card">
        <div class="platform-card__top"><span class="platform-icon"><ExternalLink :size="20" /></span><span class="status-badge warn">待接入</span></div>
        <div><h3>{{ platform }}</h3><p>当前未启用经营数据同步</p></div>
      </article>
    </div>

    <Teleport to="body">
      <div v-if="configOpen" class="modal-backdrop" @click.self="configOpen = false">
        <section class="config-dialog" role="dialog" aria-modal="true" aria-labelledby="qmai-config-title">
          <header>
            <div><span class="eyebrow">QMAI CONNECTION</span><h2 id="qmai-config-title">企迈连接配置</h2></div>
            <button class="icon-button" type="button" aria-label="关闭" @click="configOpen = false"><X :size="20" /></button>
          </header>

          <div class="credential-note" :class="{ ready: qmai?.credentialConfigured }">
            <strong>{{ qmai?.credentialConfigured ? '部署凭证已就绪' : '部署凭证尚未配置' }}</strong>
            <span>openId、grantCode、openKey 只能通过服务器环境变量配置，不会写入数据库。</span>
          </div>

          <label class="field"><span>连接名称</span><input v-model="form.displayName" maxlength="120" /></label>
          <label class="toggle-row"><input v-model="form.enabled" type="checkbox" /><span><strong>启用企迈同步</strong><small>只有凭证和门店映射都完整时才能同步。</small></span></label>

          <div class="mapping-heading">
            <div><h3>门店映射</h3><p>每家企迈门店必须对应当前系统中的唯一门店。</p></div>
            <div>
              <UiButton :loading="discovering" :disabled="!qmai?.credentialConfigured" @click="discover"><template #icon><RefreshCw :size="16" /></template>读取授权门店</UiButton>
              <UiButton variant="ghost" icon-only aria-label="新增映射" @click="addMapping"><template #icon><Plus :size="17" /></template></UiButton>
            </div>
          </div>

          <p v-if="notice" class="feedback feedback--success">{{ notice }}</p>
          <p v-if="error" class="feedback feedback--error">{{ error }}</p>

          <div class="mapping-list">
            <div v-for="(mapping, index) in form.mappings" :key="`${mapping.qmaiShopId}-${index}`" class="mapping-row">
              <input v-model="mapping.qmaiShopId" placeholder="企迈 shopId" aria-label="企迈门店编号" />
              <input v-model="mapping.qmaiShopName" placeholder="企迈门店名称" aria-label="企迈门店名称" />
              <select v-model="mapping.storeId" aria-label="对应系统门店">
                <option value="">选择系统门店</option>
                <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
              </select>
              <button class="remove-button" type="button" aria-label="删除映射" @click="removeMapping(index)"><Trash2 :size="16" /></button>
            </div>
            <div v-if="!form.mappings.length" class="empty-mapping">尚未配置门店映射，可读取授权门店或手动新增。</div>
          </div>

          <footer><UiButton @click="configOpen = false">取消</UiButton><UiButton variant="primary" :loading="saving" @click="save">保存配置</UiButton></footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.platform-page { display: grid; gap: 18px; }
.platform-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.platform-card { display: grid; min-height: 220px; align-content: space-between; gap: 18px; }
.qmai-card { grid-column: span 2; border-top: 3px solid var(--ds-primary-hover); }
.platform-card__top, .platform-actions, .mapping-heading, .mapping-heading > div:last-child { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.platform-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 10px; background: var(--ds-primary-soft); color: var(--ds-primary-hover); }
.platform-card h3 { margin: 0; color: var(--ds-ink); font-size: 20px; }
.platform-card p { margin: 5px 0 0; color: var(--ds-muted); font-size: 13px; }
.quiet-card { min-height: 160px; }
.connection-facts { display: grid; grid-template-columns: repeat(2, 1fr); margin: 0; border-block: 1px solid var(--ds-line); }
.connection-facts div { padding: 12px 0; }
.connection-facts dt { color: var(--ds-muted); font-size: 12px; }
.connection-facts dd { margin: 4px 0 0; color: var(--ds-ink); font-weight: 700; }
.status-badge { padding: 5px 9px; border-radius: 999px; font-size: 12px; font-weight: 700; }
.status-badge.ok { background: #e7f6ed; color: #197044; }
.status-badge.warn { background: #fff4dd; color: #93631c; }
.feedback { margin: 0; padding: 10px 12px; border-radius: 8px; font-size: 13px; }
.feedback--error { background: #fff0f1; color: #a62f3d; }
.feedback--success { background: #eaf7f2; color: #1d6c4e; }
.modal-backdrop { position: fixed; z-index: 1200; inset: 0; display: grid; place-items: center; padding: 24px; background: rgba(18, 34, 33, .48); }
.config-dialog { display: grid; width: min(980px, 100%); max-height: calc(100vh - 48px); overflow: auto; gap: 18px; padding: 24px; border-radius: 14px; background: #fff; box-shadow: 0 24px 70px rgba(16, 39, 37, .22); }
.config-dialog > header, .config-dialog > footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.config-dialog h2, .mapping-heading h3 { margin: 2px 0 0; color: var(--ds-ink); }
.mapping-heading p { margin: 4px 0 0; color: var(--ds-muted); font-size: 13px; }
.eyebrow { color: var(--ds-primary-hover); font-size: 11px; font-weight: 800; letter-spacing: .12em; }
.icon-button, .remove-button { display: grid; width: 38px; height: 38px; place-items: center; border: 0; border-radius: 8px; background: transparent; color: var(--ds-muted); cursor: pointer; }
.remove-button:hover { background: #fff0f1; color: #a62f3d; }
.credential-note { display: grid; gap: 4px; padding: 13px 15px; border-left: 3px solid #d59a3a; background: #fff8e9; color: #74521b; }
.credential-note.ready { border-color: var(--ds-primary-hover); background: var(--ds-primary-soft); color: #245c57; }
.credential-note span { font-size: 13px; }
.field { display: grid; gap: 7px; color: var(--ds-ink); font-size: 13px; font-weight: 700; }
.field input, .mapping-row input, .mapping-row select { min-width: 0; height: 42px; padding: 0 11px; border: 1px solid var(--ds-line-strong); border-radius: 7px; background: #fff; color: var(--ds-ink); }
.toggle-row { display: flex; align-items: flex-start; gap: 10px; padding: 13px; border: 1px solid var(--ds-line); border-radius: 9px; }
.toggle-row input { margin-top: 3px; }
.toggle-row span { display: grid; gap: 3px; }
.toggle-row small { color: var(--ds-muted); }
.mapping-list { display: grid; gap: 8px; }
.mapping-row { display: grid; grid-template-columns: 1fr 1.5fr 1.5fr 40px; gap: 8px; align-items: center; }
.empty-mapping { padding: 24px; border: 1px dashed var(--ds-line-strong); border-radius: 9px; color: var(--ds-muted); text-align: center; }
.config-dialog > footer { justify-content: flex-end; padding-top: 4px; }
@media (max-width: 900px) { .platform-grid { grid-template-columns: 1fr 1fr; } .qmai-card { grid-column: 1 / -1; } .mapping-row { grid-template-columns: 1fr 1fr 40px; } .mapping-row select { grid-column: 1 / 3; } }
@media (max-width: 640px) { .platform-grid { grid-template-columns: 1fr; } .qmai-card { grid-column: auto; } .platform-actions, .mapping-heading { align-items: stretch; flex-direction: column; } .platform-actions :deep(button) { width: 100%; } .modal-backdrop { padding: 0; place-items: end center; } .config-dialog { max-height: 92vh; border-radius: 14px 14px 0 0; padding: 18px; } .mapping-row { grid-template-columns: 1fr 40px; } .mapping-row input, .mapping-row select { grid-column: 1; } .mapping-row .remove-button { grid-column: 2; grid-row: 1; } }
</style>
