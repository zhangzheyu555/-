<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { KeyRound, Pencil, Plus, RefreshCw, ShieldCheck, Store, X } from 'lucide-vue-next'
import { getStores, type StoreInfo } from '../api/operations'
import {
  createUser,
  getUsers,
  resetUserPassword,
  updateUser,
  type UserAccount,
  type UserCreatePayload,
  type UserProfilePayload,
} from '../api/users'
import { useAuthStore } from '../stores/auth'

type AccountForm = UserCreatePayload

const auth = useAuthStore()
const users = ref<UserAccount[]>([])
const stores = ref<StoreInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const successMessage = ref('')
const editorOpen = ref(false)
const editingUser = ref<UserAccount | null>(null)
const form = reactive<AccountForm>(emptyForm())

const roles = [
  { value: 'ADMIN', label: '系统管理员' },
  { value: 'BOSS', label: '老板' },
  { value: 'FINANCE', label: '财务' },
  { value: 'STORE_MANAGER', label: '店长' },
  { value: 'SUPERVISOR', label: '督导' },
  { value: 'WAREHOUSE', label: '仓库管理员' },
  { value: 'OPERATIONS', label: '运营' },
  { value: 'EMPLOYEE', label: '员工' },
]

const globalScopeRoles = new Set(['ADMIN', 'BOSS', 'FINANCE'])
const canManage = computed(() => auth.role === 'ADMIN')
const enabledCount = computed(() => users.value.filter((user) => user.enabled).length)
const managerCount = computed(() => users.value.filter((user) => user.role === 'STORE_MANAGER').length)
const roleRows = computed(() => {
  const groups = new Map<string, number>()
  for (const user of users.value) {
    const label = user.roleLabel || user.role
    groups.set(label, (groups.get(label) || 0) + 1)
  }
  return Array.from(groups.entries()).map(([label, count]) => ({ label, count }))
})
const globalScope = computed(() => globalScopeRoles.has(form.role))

function emptyForm(): AccountForm {
  return {
    username: '',
    displayName: '',
    role: 'STORE_MANAGER',
    storeId: '',
    storeScope: [],
    enabled: true,
    password: '',
  }
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [userRows, storeRows] = await Promise.all([getUsers(), getStores()])
    users.value = userRows
    stores.value = storeRows
  } catch (loadError) {
    error.value = displayError(loadError, '用户权限加载失败，请刷新后重试。')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingUser.value = null
  Object.assign(form, emptyForm())
  editorOpen.value = true
}

function openEdit(user: UserAccount) {
  editingUser.value = user
  Object.assign(form, {
    username: user.username,
    displayName: user.displayName || '',
    role: user.role,
    storeId: user.storeId || '',
    storeScope: (user.storeScope || []).filter((value) => value !== 'all'),
    enabled: user.enabled,
    password: '',
  })
  editorOpen.value = true
}

function closeEditor() {
  if (saving.value) return
  editorOpen.value = false
}

async function save() {
  error.value = ''
  successMessage.value = ''
  if (!form.displayName.trim()) {
    error.value = '请填写姓名或显示名称。'
    return
  }
  if (!editingUser.value && !/^[a-z0-9_.-]{3,40}$/.test(form.username.trim().toLowerCase())) {
    error.value = '登录账号为 3 至 40 位小写字母、数字、点、下划线或短横线。'
    return
  }
  if (!editingUser.value && form.password.length < 8) {
    error.value = '初始密码至少需要 8 位。'
    return
  }
  if (!globalScope.value && !form.storeScope.length && !form.storeId) {
    error.value = '请至少选择一个门店范围。'
    return
  }

  saving.value = true
  try {
    const scope = globalScope.value
      ? []
      : Array.from(new Set([...form.storeScope, form.storeId].filter((value): value is string => Boolean(value))))
    const profile: UserProfilePayload = {
      displayName: form.displayName.trim(),
      role: form.role,
      storeId: globalScope.value ? '' : form.storeId || scope[0] || '',
      storeScope: scope,
      enabled: form.enabled,
    }
    if (editingUser.value) {
      await updateUser(editingUser.value.id, profile)
      successMessage.value = '账号权限已更新。'
    } else {
      await createUser({
        ...profile,
        username: form.username.trim().toLowerCase(),
        password: form.password,
      })
      successMessage.value = '账号已创建。'
    }
    editorOpen.value = false
    await refresh()
  } catch (saveError) {
    error.value = displayError(saveError, '账号保存失败，请稍后重试。')
  } finally {
    saving.value = false
  }
}

async function resetPassword(user: UserAccount) {
  const password = window.prompt(`为 ${user.username} 设置新密码（至少 8 位）`)
  if (password === null) return
  if (password.length < 8) {
    error.value = '新密码至少需要 8 位。'
    return
  }
  error.value = ''
  try {
    await resetUserPassword(user.id, password)
    successMessage.value = '密码已重置，旧登录已失效。'
  } catch (resetError) {
    error.value = displayError(resetError, '密码重置失败，请稍后重试。')
  }
}

function storeName(storeId?: string) {
  if (!storeId) return '未绑定门店'
  return stores.value.find((store) => store.id === storeId)?.name || storeId
}

function scopeText(user: UserAccount) {
  if (!user.storeScope?.length) return '未配置'
  if (user.storeScope.includes('all')) return '全部门店'
  return user.storeScope.map((storeId) => storeName(storeId)).join('、')
}

function roleTone(role: string) {
  if (['ADMIN', 'BOSS'].includes(role)) return 'bad'
  if (role === 'STORE_MANAGER') return 'ok'
  if (role === 'WAREHOUSE') return 'warn'
  return 'info'
}

function displayError(reason: unknown, fallback: string) {
  const message = reason instanceof Error ? reason.message : String(reason || '')
  return message || fallback
}

watch(
  () => form.role,
  (role) => {
    if (globalScopeRoles.has(role)) {
      form.storeId = ''
      form.storeScope = []
    }
  },
)

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel users-page">
    <div class="page-head">
      <div><h2>账号权限</h2></div>
      <div class="page-actions">
        <button v-if="canManage" class="primary-button" type="button" @click="openCreate">
          <Plus :size="16" />
          新增账号
        </button>
        <button class="ghost-button" type="button" :disabled="loading" @click="refresh">
          <RefreshCw :size="16" />
          刷新
        </button>
      </div>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="successMessage" class="success-box">{{ successMessage }}</div>
    <div v-if="loading && !users.length" class="empty-state">正在读取账号权限...</div>

    <template v-else>
      <div class="metric-grid">
        <article class="metric-card"><span>账号总数</span><b>{{ users.length }}</b></article>
        <article class="metric-card"><span>启用账号</span><b>{{ enabledCount }}</b></article>
        <article class="metric-card"><span>店长账号</span><b>{{ managerCount }}</b></article>
        <article class="metric-card"><span>门店数量</span><b>{{ stores.length }}</b></article>
      </div>

      <div class="users-grid">
        <section class="content-card">
          <div class="table-heading"><h3>账号列表</h3></div>
          <div v-if="!users.length" class="empty-state compact">暂无账号数据。</div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>账号</th>
                  <th>姓名</th>
                  <th>角色</th>
                  <th>绑定门店</th>
                  <th>门店范围</th>
                  <th>状态</th>
                  <th v-if="canManage" class="r">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="user in users" :key="user.id">
                  <td><b>{{ user.username }}</b></td>
                  <td>{{ user.displayName || '-' }}</td>
                  <td><span class="status-badge" :class="roleTone(user.role)">{{ user.roleLabel || user.role }}</span></td>
                  <td>{{ storeName(user.storeId) }}</td>
                  <td>{{ scopeText(user) }}</td>
                  <td><span class="status-badge" :class="user.enabled ? 'ok' : 'bad'">{{ user.enabled ? '启用' : '停用' }}</span></td>
                  <td v-if="canManage" class="r actions-cell">
                    <button class="icon-button" type="button" title="编辑账号" @click="openEdit(user)"><Pencil :size="15" /></button>
                    <button class="icon-button" type="button" title="重置密码" @click="resetPassword(user)"><KeyRound :size="15" /></button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="side-stack">
          <section class="content-card">
            <div class="panel-title"><ShieldCheck :size="20" /><h3>角色分布</h3></div>
            <div class="role-list">
              <div v-for="row in roleRows" :key="row.label"><span>{{ row.label }}</span><b>{{ row.count }}</b></div>
            </div>
          </section>
          <section class="content-card">
            <div class="panel-title"><Store :size="20" /><h3>门店范围</h3></div>
            <div class="scope-list">
              <div v-for="user in users.filter((item) => !item.storeScope.includes('all'))" :key="user.id">
                <b>{{ user.displayName || user.username }}</b>
                <span>{{ scopeText(user) }}</span>
              </div>
              <div v-if="!users.some((item) => !item.storeScope.includes('all'))" class="empty-state compact">暂无门店范围配置。</div>
            </div>
          </section>
        </aside>
      </div>
    </template>

    <div v-if="editorOpen" class="editor-backdrop" @click.self="closeEditor">
      <form class="account-editor" @submit.prevent="save">
        <div class="editor-head">
          <h3>{{ editingUser ? '编辑账号' : '新增账号' }}</h3>
          <button class="icon-button" type="button" aria-label="关闭" :disabled="saving" @click="closeEditor"><X :size="17" /></button>
        </div>
        <div class="editor-body">
          <label>
            登录账号
            <input v-model.trim="form.username" type="text" autocomplete="off" :disabled="Boolean(editingUser) || saving" placeholder="例如：store-manager-01" />
          </label>
          <label>
            姓名或显示名称
            <input v-model.trim="form.displayName" type="text" :disabled="saving" placeholder="例如：荆州之星店店长" />
          </label>
          <label>
            角色
            <select v-model="form.role" :disabled="saving">
              <option v-for="role in roles" :key="role.value" :value="role.value">{{ role.label }}</option>
            </select>
          </label>
          <template v-if="!globalScope">
            <label>
              默认门店
              <select v-model="form.storeId" :disabled="saving">
                <option value="">从门店范围中选择</option>
                <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.brandName }} · {{ store.name }}</option>
              </select>
            </label>
            <div class="scope-picker">
              <b>门店范围</b>
              <label v-for="store in stores" :key="store.id" class="scope-option">
                <input v-model="form.storeScope" type="checkbox" :value="store.id" :disabled="saving" />
                <span>{{ store.brandName }} · {{ store.name }}</span>
              </label>
            </div>
          </template>
          <label v-if="!editingUser">
            初始密码
            <input v-model="form.password" type="password" autocomplete="new-password" :disabled="saving" placeholder="至少 8 位" />
          </label>
          <label class="enabled-row">
            <input v-model="form.enabled" type="checkbox" :disabled="saving" />
            <span>启用账号</span>
          </label>
        </div>
        <div class="editor-actions">
          <button class="ghost-button" type="button" :disabled="saving" @click="closeEditor">取消</button>
          <button class="primary-button" type="submit" :disabled="saving">{{ saving ? '正在保存' : '保存' }}</button>
        </div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.users-page,
.side-stack,
.role-list,
.scope-list {
  display: grid;
  gap: 14px;
}

.page-actions,
.actions-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.users-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 14px;
}

.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 10px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: middle; }
th { color: var(--muted); font-size: 12px; font-weight: 800; }
.r { text-align: right; }

.panel-title { display: flex; align-items: center; gap: 8px; }
.panel-title h3, .table-heading h3 { margin: 0; font-size: 16px; }
.role-list > div, .scope-list > div {
  display: grid;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.role-list > div { grid-template-columns: 1fr auto; align-items: center; }
.role-list span, .scope-list span { color: var(--muted); font-size: 12px; }

.editor-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  justify-content: flex-end;
  background: rgba(20, 24, 32, .34);
}
.account-editor {
  display: flex;
  width: min(520px, 100vw);
  min-height: 100%;
  flex-direction: column;
  background: #fff;
  box-shadow: -12px 0 32px rgba(22, 26, 34, .16);
}
.editor-head, .editor-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}
.editor-head h3 { margin: 0; font-size: 18px; }
.editor-body {
  display: grid;
  flex: 1;
  align-content: start;
  gap: 14px;
  overflow-y: auto;
  padding: 18px;
}
.editor-body > label { display: grid; gap: 7px; font-size: 13px; font-weight: 800; }
.editor-body input[type='text'], .editor-body input[type='password'], .editor-body select {
  width: 100%; box-sizing: border-box; padding: 10px 11px; border: 1px solid var(--line); border-radius: 8px; background: #fff; font: inherit;
}
.scope-picker { display: grid; gap: 8px; }
.scope-option, .enabled-row { display: flex !important; align-items: center; gap: 8px; font-weight: 600 !important; }
.editor-actions { justify-content: flex-end; border-top: 1px solid var(--line); border-bottom: 0; }

@media (max-width: 960px) { .users-grid { grid-template-columns: 1fr; } }
</style>
